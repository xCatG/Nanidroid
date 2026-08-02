package com.cattailsw.nanidroid.install

import androidx.work.ListenableWorker
import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.durable.AttemptId
import com.cattailsw.nanidroid.durable.DurableOperationSupervisor
import com.cattailsw.nanidroid.durable.ExternalJobBinding
import com.cattailsw.nanidroid.durable.OperationCancellation
import com.cattailsw.nanidroid.durable.OperationHandle
import com.cattailsw.nanidroid.durable.OperationId
import com.cattailsw.nanidroid.durable.OperationKind
import com.cattailsw.nanidroid.durable.OperationStatus
import com.cattailsw.nanidroid.durable.SharedPreferencesDurableOperationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NarDownloadRepositoryTest {
    private val store = NarDownloadStore(NarDownloadStore.MemoryStorage())
    private val downloads = FakeDownloadGateway()
    private val work = FakeWorkScheduler()
    private val installer = FakeArchiveInstaller()
    private val ownedData = FakeOwnedData()
    private val attempts = FakeAttemptPaths()
    private val operationStore = SharedPreferencesDurableOperationStore(
        SharedPreferencesDurableOperationStore.MemoryStorage(),
    )
    private val cancellations = RecordingOperationCancellation(downloads, work)
    private val supervisor = DurableOperationSupervisor(
        operationStore,
        MonotonicClock { 0L },
        cancellations,
    )
    private val remoteProgress = FakeRemoteProgressObserver(downloads, supervisor)
    private val ids = ArrayDeque(listOf("old-item", "new-item", "third-item"))
    private val repository = NarDownloadRepository(
        store = store,
        downloads = downloads,
        work = work,
        installer = installer,
        ownedData = ownedData,
        attemptPaths = attempts,
        supervisor = supervisor,
        remoteProgress = remoteProgress,
        nextId = { ids.removeFirst() },
    )

    @Test fun remoteEnqueueStartsProgressObservationForExactAttemptAndRow() {
        downloads.nextDownloadId = 30L

        val item = repository.enqueueRemote("https://example.invalid/archive.nar")

        assertEquals(listOf(item.handle() to 30L), remoteProgress.started)
    }

    @Test fun remoteDownloadHeartbeatsOnlyWhenBoundRowBytesIncrease() {
        downloads.nextDownloadId = 31L
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")
        downloads.downloadedBytes[31L] = 8L

        assertTrue(repository.observeRemoteProgress(item.id))
        assertEquals(8L, operationStore.read().single().progress.completed)
        assertTrue(!repository.observeRemoteProgress(item.id))

        downloads.downloadedBytes[31L] = 9L
        assertTrue(repository.observeRemoteProgress(item.id))
        assertEquals(9L, operationStore.read().single().progress.completed)
    }

    @Test fun remoteProgressPollingContinuesAcrossRepeatedByteCountsUntilTerminalRow() {
        downloads.nextDownloadId = 32L
        downloads.downloadedBytes[32L] = 0L
        downloads.statuses[32L] = NarRemoteDownloadStatus.InProgress
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")
        val scheduler = FakeProgressScheduler()
        val observer = DownloadManagerProgressObserver(downloads, supervisor, scheduler)

        observer.start(item.handle(), 32L)
        scheduler.runNext()
        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()
        assertEquals(1, scheduler.pendingCount)

        downloads.statuses[32L] = NarRemoteDownloadStatus.Successful(item.retainedUri)
        scheduler.runNext()
        assertEquals(0, scheduler.pendingCount)
    }

    @Test fun cancelThenRetryUsesNewAttemptAndRejectsLateDownloadCompletion() {
        downloads.nextDownloadId = 41L
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")

        assertTrue(repository.stop(item.id))
        assertEquals(NarDownloadState.Cancelled, store.get(item.id)!!.state)
        assertEquals(listOf(41L), downloads.removedIds)
        assertEquals(OperationStatus.CANCELLED, operationStore.read().single().status)

        downloads.nextDownloadId = 42L
        val retry = repository.retry(item.id)!!
        assertEquals(item.attemptId + 1L, retry.attemptId)
        assertEquals(42L, retry.downloadManagerId)

        repository.onDownloadComplete(41L)

        assertTrue(work.enqueuedNames.isEmpty())
        assertEquals(retry, store.get(item.id))
    }

    @Test fun duplicateCompletionAfterInstallHandoffCannotAdvanceAttemptAgain() {
        downloads.nextDownloadId = 43L
        val remote = repository.enqueueRemote("https://example.invalid/archive.nar")
        repository.onDownloadComplete(43L)
        val install = store.get(remote.id)!!

        repository.onDownloadComplete(43L)

        assertEquals(install, store.get(remote.id))
        assertEquals(listOf("install-nar-${remote.id}"), work.enqueuedNames)
    }

    @Test fun reconciliationDoesNotTreatInstallAttemptAsRemoteDownload() {
        downloads.nextDownloadId = 44L
        val remote = repository.enqueueRemote("https://example.invalid/archive.nar")
        repository.onDownloadComplete(44L)
        val install = store.get(remote.id)!!
        work.enqueuedNames.clear()
        downloads.statuses[44L] = NarRemoteDownloadStatus.Successful(install.retainedUri)

        repository.reconcile()

        assertEquals(install, store.get(remote.id))
        assertEquals(listOf("install-nar-${remote.id}"), work.enqueuedNames)
    }

    @Test fun staleInstallWorkerCannotMutateNewAttempt() {
        val item = repository.enqueueLocal("file:///owned/archive.nar")
        val firstAttempt = item.attemptId
        assertTrue(repository.stop(item.id))
        val retry = repository.retry(item.id)!!

        repository.install(item.id, firstAttempt) { false }

        assertEquals(retry, store.get(item.id))
        assertTrue(installer.stagingDirectories.isEmpty())
    }

    @Test fun installProgressUsesExactAttemptAndTerminalCallbackIsFenced() {
        val item = repository.enqueueLocal("file:///owned/archive.nar")
        installer.onInstall = { _, _, _, onProgress ->
            onProgress("Extracting archive", 8L)
            onProgress("Extracting archive", 8L)
            ArchiveInstallResult.Installed("installed")
        }

        repository.install(item.id, item.attemptId) { false }

        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
        val terminal = operationStore.read().single()
        assertEquals(OperationStatus.COMPLETED, terminal.status)
        assertEquals(8L, terminal.progress.completed)
        assertTrue(!supervisor.reportProgress(item.handle(), "Late", 9L))
    }

    @Test fun stopAfterAtomicPublicationStillCompletesQueueAndCleanup() {
        val item = repository.enqueueLocal("file:///owned/archive.nar")
        installer.onInstall = { download, _, isStopped, _ ->
            assertTrue(repository.stop(download.id))
            assertTrue(isStopped())
            ArchiveInstallResult.Installed("installed")
        }

        repository.install(item.id, item.attemptId) { false }

        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
        assertEquals(listOf(item.id), ownedData.deletedItemIds)
    }

    @Test fun retainedUriCopyReportsBytesAndHandsOffToANewInstallAttempt() {
        val item = repository.enqueueLocalCopy("content://provider/archive.nar")
        val phases = mutableListOf<Pair<String, Long>>()
        val privateImports = File.createTempFile("nar-private-imports", "").also {
            assertTrue(it.delete())
            assertTrue(it.mkdir())
        }

        repository.stageLocal(item.id, item.attemptId, { false }) { _, isCancelled, onProgress ->
            NarLocalArchiveStager.stage(
                directory = privateImports,
                open = { ByteArrayInputStream(ByteArray(20 * 1024) { 1 }) },
                isCancelled = isCancelled,
                onProgress = { completed ->
                    phases += "Copying archive" to completed
                    onProgress("Copying archive", completed)
                },
            )
        }

        val staged = store.get(item.id)!!
        assertEquals(item.attemptId + 1L, staged.attemptId)
        assertEquals(NarDownloadState.Queued, staged.state)
        assertTrue(staged.retainedUri!!.startsWith("file:"))
        assertTrue(phases.zipWithNext().all { (left, right) -> right.second > left.second })
        assertEquals(listOf("install-nar-${item.id}"), work.enqueuedNames)
        NarLocalArchiveStager.discard(staged.retainedUri!!)
        privateImports.delete()
    }

    @Test fun cancelledLocalCopyDeletesPartialPrivateArchive() {
        val directory = File.createTempFile("nar-local-stage", "").also {
            assertTrue(it.delete())
            assertTrue(it.mkdir())
        }
        var stopRequested = false

        val result = NarLocalArchiveStager.stage(
            directory = directory,
            open = { ByteArrayInputStream(ByteArray(20 * 1024) { 2 }) },
            isCancelled = { stopRequested },
            onProgress = { stopRequested = true },
        )

        assertEquals(NarLocalArchiveStager.Result.Cancelled, result)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun lateLocalStageWorkerCannotReplaceRetryAttempt() {
        val item = repository.enqueueLocalCopy("content://provider/archive.nar")
        assertTrue(repository.stop(item.id))
        val retry = repository.retry(item.id)!!
        var staleStageStarted = false

        repository.stageLocal(item.id, item.attemptId, { false }) { _, _, _ ->
            staleStageStarted = true
            NarLocalArchiveStager.Result.Staged("file:///stale/archive.nar")
        }

        assertTrue(!staleStageStarted)
        assertEquals(retry, store.get(item.id))
    }

    @Test fun stoppedInstallWorkerCancelsOnlyItsMatchingAttempt() {
        val item = repository.enqueueLocal("file:///owned/archive.nar")

        repository.workerStopped(item.id, item.attemptId)

        assertEquals(NarDownloadState.Cancelled, store.get(item.id)!!.state)
        val retry = repository.retry(item.id)!!

        repository.workerStopped(item.id, item.attemptId)

        assertEquals(retry, store.get(item.id))
    }

    @Test fun unknownCompletionDoesNotScheduleWork() {
        repository.onDownloadComplete(999L)

        assertTrue(work.enqueuedNames.isEmpty())
    }

    @Test fun duplicateCompletionDoesNotRetryNeedsAttention() {
        downloads.nextDownloadId = 21L
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")
        store.update(item.id) {
            it.copy(
                state = NarDownloadState.NeedsAttention(
                    NarDownloadState.Failure("invalid archive"),
                ),
            )
        }

        repository.onDownloadComplete(21L)

        assertTrue(work.enqueuedNames.isEmpty())
    }

    @Test fun failedInstallPersistsNeedsAttentionAndWorkerSucceeds() {
        val item = repository.enqueueLocal("content://provider/archive.nar")
        installer.result = ArchiveInstallResult.Failed(
            "invalid archive",
            ArchiveInstallFailure.InvalidArchive,
        )

        val result = InstallNarWorker.execute(repository, item.id) { false }

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(store.get(item.id)!!.state is NarDownloadState.NeedsAttention)
    }

    @Test fun rescheduledInstallDoesNotRetryNeedsAttention() {
        val item = repository.enqueueLocal("content://provider/archive.nar")
        val attention = NarDownloadState.NeedsAttention(
            NarDownloadState.Failure("install interrupted"),
        )
        store.update(item.id) { it.copy(state = attention) }

        repository.install(item.id) { false }

        assertEquals(attention, store.get(item.id)!!.state)
        assertTrue(installer.stagingDirectories.isEmpty())
    }

    @Test fun successfulInstallCleansOwnedArchiveAndKeepsCompletionVisible() {
        val item = repository.enqueueLocal("file:///owned/archive.nar", "file:///owned/archive.nar")

        InstallNarWorker.execute(repository, item.id) { false }

        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
        assertEquals(listOf(item.id), ownedData.deletedItemIds)
    }

    @Test fun successfulRemoteInstallRemovesCompletedDownload() {
        downloads.nextDownloadId = 61L
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")

        repository.install(item.id) { false }

        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
        assertEquals(listOf(61L), downloads.removedIds)
    }

    @Test fun retryPersistsDestinationBeforeEnqueueing() {
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")
        downloads.onEnqueue = { itemId ->
            assertEquals("file:///owned/$itemId.nar", store.get(itemId)!!.retainedUri)
        }

        repository.retry(item.id)
    }

    @Test fun retryDestinationFailureReturnsItemToAttention() {
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")
        downloads.intendedDestinationFailure = IllegalStateException("storage unavailable")

        repository.retry(item.id)

        assertTrue(store.get(item.id)!!.state is NarDownloadState.NeedsAttention)
    }

    @Test fun reconciliationCleansCompletedInstallAfterInterruptedCleanup() {
        val item = store.create(
            NarDownload(
                id = "completed-item",
                source = NarDownloadSource.Remote("https://example.invalid/archive.nar"),
                retainedUri = "file:///owned/completed-item.nar",
                downloadManagerId = 62L,
                state = NarDownloadState.Complete,
            ),
        )

        repository.reconcile()

        assertEquals(listOf(62L), downloads.removedIds)
        assertEquals(listOf(item.id), ownedData.deletedItemIds)
    }

    @Test fun deletingOneOfTwoSharedDocumentSourcesRetainsItsGrant() {
        val source = "content://provider/shared.nar"
        val first = repository.enqueueLocal(source, source)
        val second = repository.enqueueLocal(source, source)

        repository.delete(first.id)

        assertTrue(ownedData.releasedItemIds.isEmpty())
        repository.delete(second.id)
        assertEquals(listOf(second.id), ownedData.releasedItemIds)
    }

    @Test fun reportsSharedDocumentSourceReferencedAfterPlaceholderDeletion() {
        val source = "content://provider/shared.nar"
        val placeholder = repository.retainLocalSourceForCopy(source)
        repository.enqueueLocal(source, source)

        repository.delete(placeholder.id)

        assertTrue(repository.isSourceReferenced(source))
    }

    @Test fun copyingPlaceholderCannotBeRetriedBeforeStagingFinishes() {
        val item = repository.retainLocalSourceForCopy("content://provider/archive.nar")

        repository.retry(item.id)

        assertEquals(NarDownloadState.Copying, store.get(item.id)!!.state)
        assertTrue(work.enqueuedNames.isEmpty())
    }

    @Test fun reconciliationLeavesBoundLocalCopyForItsWorkerToResume() {
        val item = repository.enqueueLocalCopy("content://provider/archive.nar")
        val bound = store.get(item.id)!!

        repository.reconcile()

        assertEquals(bound, store.get(item.id))
        assertEquals(NarDownloadState.Copying, store.get(item.id)!!.state)
    }

    @Test fun temporaryReplacementUsesSupervisedCancellableCopyAttempt() {
        val item = repository.enqueueLocal("content://provider/unavailable.nar")
        installer.failure = SecurityException("grant revoked")
        repository.install(item.id, item.attemptId) { false }
        installer.failure = null

        val replacement = repository.replaceLocalSourceForCopy(
            item.id,
            "content://provider/temporary.nar",
        )!!

        assertNotEquals(item.id, replacement.id)
        assertEquals(1L, replacement.attemptId)
        assertEquals(NarDownloadState.Copying, replacement.state)
        assertEquals(
            "stage-local-nar-${replacement.id}-${replacement.attemptId}",
            replacement.workManagerId,
        )
        val operation = operationStore.read().single { it.id.value == replacement.id }
        assertEquals(OperationKind.LOCAL_NAR, operation.kind)
        assertEquals(OperationStatus.RUNNING, operation.status)
        assertTrue(repository.stop(replacement.id))
        assertEquals(listOf(replacement.workManagerId), work.cancelledBindings)
    }

    @Test fun oneShotGrantIsAcquiredLiveAndCopiedOffMainIntoSupervisedDurableStorage() {
        val callerThread = Thread.currentThread()
        val grantLive = AtomicBoolean(true)
        val openedOn = AtomicReference<Thread>()
        val copiedOn = AtomicReference<Thread>()
        val supervisedProgressObserved = AtomicBoolean(false)
        val sourceCloseCount = AtomicInteger()
        val copyStarted = CountDownLatch(1)
        val allowCopy = CountDownLatch(1)
        val copyFinished = CountDownLatch(1)
        val privateImports = File.createTempFile("nar-live-grant", "").also {
            assertTrue(it.delete())
            assertTrue(it.mkdir())
        }
        val executor = Executor { task ->
            Thread({
                copiedOn.set(Thread.currentThread())
                copyStarted.countDown()
                allowCopy.await(5, TimeUnit.SECONDS)
                task.run()
                copyFinished.countDown()
            }, "nar-live-grant-copy").start()
        }
        val handoff = NarLiveGrantHandoff(
            repository = repository,
            executor = executor,
            stage = { source, isCancelled, onProgress ->
                NarLocalArchiveStager.stage(
                    directory = privateImports,
                    open = { source },
                    isCancelled = isCancelled,
                    onProgress = { completed ->
                        onProgress("Copying archive", completed)
                        val operation = operationStore.read().single()
                        supervisedProgressObserved.set(
                            operation.attemptId.value == 1L &&
                                operation.status == OperationStatus.RUNNING &&
                                operation.externalJob == ExternalJobBinding.WorkManager(
                                    "stage-local-nar-${operation.id.value}-1",
                                ) &&
                                operation.progress.completed > 0L,
                        )
                    },
                )
            },
        )

        val item = handoff.enqueue("content://provider/one-shot.nar", replacementId = null) {
            assertTrue("source opened after grant window", grantLive.get())
            openedOn.set(Thread.currentThread())
            object : FilterInputStream(ByteArrayInputStream(ByteArray(20 * 1024) { 6 })) {
                override fun read(target: ByteArray, offset: Int, length: Int): Int {
                    assertTrue("source copied after grant window", grantLive.get())
                    return super.read(target, offset, length)
                }

                override fun close() {
                    sourceCloseCount.incrementAndGet()
                    super.close()
                }
            }
        }!!

        assertTrue(copyStarted.await(2, TimeUnit.SECONDS))
        assertEquals(callerThread, openedOn.get())
        assertEquals(NarDownloadState.Copying, store.get(item.id)!!.state)
        var workerTriedToOpen = false
        val workerAccepted = repository.stageLocal(item.id, item.attemptId, { false }) { _, _, _ ->
            workerTriedToOpen = true
            NarLocalArchiveStager.Result.Failed("duplicate opener")
        }
        assertTrue(!workerAccepted)
        assertTrue(!workerTriedToOpen)
        assertEquals(NarDownloadState.Copying, store.get(item.id)!!.state)
        allowCopy.countDown()
        assertTrue(copyFinished.await(5, TimeUnit.SECONDS))
        grantLive.set(false)

        val staged = store.get(item.id)!!
        assertTrue(copiedOn.get() !== callerThread)
        assertEquals(NarDownloadState.Queued, staged.state)
        assertTrue(staged.retainedUri!!.startsWith("file:"))
        assertTrue(supervisedProgressObserved.get())
        assertEquals(1, sourceCloseCount.get())
        NarLocalArchiveStager.discard(staged.retainedUri!!)
        privateImports.delete()
    }

    @Test fun oneShotStreamClosesExactlyOnceWhenStagingReturnsBeforeOpen() {
        listOf<NarLocalArchiveStager.Result>(
            NarLocalArchiveStager.Result.Cancelled,
            NarLocalArchiveStager.Result.Failed("storage unavailable"),
        ).forEachIndexed { index, result ->
            val closeCount = AtomicInteger()
            val source = closeCountingSource(closeCount)
            val handoff = NarLiveGrantHandoff(
                repository = repository,
                executor = Executor(Runnable::run),
                stage = { _, _, _ -> result },
            )

            handoff.enqueue("content://provider/before-open-$index.nar", null) { source }

            assertEquals(1, closeCount.get())
        }
    }

    @Test fun oneShotStreamClosesExactlyOnceWhenHandoffCannotStart() {
        val rejectedCloseCount = AtomicInteger()
        val rejectingHandoff = NarLiveGrantHandoff(
            repository = repository,
            executor = Executor { throw RejectedExecutionException("executor stopped") },
            stage = { _, _, _ -> NarLocalArchiveStager.Result.Cancelled },
        )

        rejectingHandoff.enqueue("content://provider/rejected.nar", null) {
            closeCountingSource(rejectedCloseCount)
        }

        val missingReplacementCloseCount = AtomicInteger()
        val missingReplacement = NarLiveGrantHandoff(
            repository = repository,
            executor = Executor(Runnable::run),
            stage = { _, _, _ -> NarLocalArchiveStager.Result.Cancelled },
        ).enqueue("content://provider/missing-replacement.nar", "missing-item") {
            closeCountingSource(missingReplacementCloseCount)
        }
        assertEquals(1, rejectedCloseCount.get())
        assertNull(missingReplacement)
        assertEquals(1, missingReplacementCloseCount.get())
    }

    @Test fun queuedOneShotCopyDoesNotRetainItsActivityOwner() {
        val queuedTask = AtomicReference<Runnable>()
        val sourceCloseCount = AtomicInteger()
        val privateImports = File.createTempFile("nar-owner-release", "").also {
            assertTrue(it.delete())
            assertTrue(it.mkdir())
        }
        val ownerReference = enqueueFromActivityLikeOwner(
            repository,
            Executor(queuedTask::set),
            privateImports,
            sourceCloseCount,
        )

        assertEventuallyCollected(ownerReference)
        queuedTask.get().run()

        val staged = store.get("old-item")!!
        assertEquals(NarDownloadState.Queued, staged.state)
        assertEquals(1, sourceCloseCount.get())
        NarLocalArchiveStager.discard(staged.retainedUri!!)
        privateImports.delete()
    }

    @Test fun failedCopyBecomesActionableAttention() {
        val item = repository.retainLocalSourceForCopy("content://provider/archive.nar")

        repository.copyFailed(item.id)

        assertTrue(store.get(item.id)!!.state is NarDownloadState.NeedsAttention)
    }

    @Test fun reselectingTheSameDocumentKeepsItsPersistedGrant() {
        val source = "content://provider/reselected.nar"
        val item = repository.enqueueLocal(source, source)

        repository.replaceLocalSource(item.id, source)

        assertTrue(ownedData.releasedItemIds.isEmpty())
    }

    @Test fun completedHistoryDoesNotRetainAReacquiredDocumentGrant() {
        val source = "content://provider/reacquired.nar"
        val completed = repository.enqueueLocal(source, source)
        repository.install(completed.id) { false }
        ownedData.releasedItemIds.clear()
        val active = repository.enqueueLocal(source, source)

        repository.delete(active.id)

        assertEquals(listOf(active.id), ownedData.releasedItemIds)
    }

    @Test fun recoveringPublishedInstallMarksTargetConflictComplete() {
        val item = repository.enqueueLocal("file:///owned/archive.nar", "file:///owned/archive.nar")
        store.update(item.id) { it.copy(state = NarDownloadState.Installing) }
        installer.result = ArchiveInstallResult.Failed(
            "target exists",
            ArchiveInstallFailure.TargetExists,
        )

        repository.install(item.id) { false }

        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
    }

    @Test fun freshInstallTargetConflictStillNeedsAttention() {
        val item = repository.enqueueLocal("file:///owned/archive.nar", "file:///owned/archive.nar")
        installer.result = ArchiveInstallResult.Failed(
            "target exists",
            ArchiveInstallFailure.TargetExists,
        )

        repository.install(item.id) { false }

        assertTrue(store.get(item.id)!!.state is NarDownloadState.NeedsAttention)
    }

    @Test fun deleteCancelsUniqueWorkRemovesDownloadAndDeletesOwnedData() {
        downloads.nextDownloadId = 41L
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")

        repository.delete(item.id)

        assertEquals(listOf("install-nar-${item.id}"), work.cancelledNames)
        assertEquals(listOf(41L), downloads.removedIds)
        assertEquals(listOf(item.id), ownedData.deletedItemIds)
        assertNull(store.get(item.id))
    }

    @Test fun revokedPersistedUriBecomesReselectableNeedsAttention() {
        val item = repository.enqueueLocal("content://provider/archive.nar")
        installer.failure = SecurityException("grant revoked")

        InstallNarWorker.execute(repository, item.id) { false }

        val state = store.get(item.id)!!.state as NarDownloadState.NeedsAttention
        assertTrue(state.failure.message.contains("select", ignoreCase = true))
    }

    @Test fun missingProviderBecomesReselectableNeedsAttention() {
        val item = repository.enqueueLocal("content://provider/archive.nar")
        installer.failure = FileNotFoundException("provider missing")

        InstallNarWorker.execute(repository, item.id) { false }

        val state = store.get(item.id)!!.state as NarDownloadState.NeedsAttention
        assertTrue(state.failure.message.contains("select", ignoreCase = true))
    }

    @Test fun replacingUnavailableLocalSourceKeepsRecordAndSchedulesInstall() {
        val item = repository.enqueueLocal("content://provider/unavailable.nar")
        installer.failure = SecurityException("grant revoked")
        InstallNarWorker.execute(repository, item.id) { false }

        repository.replaceLocalSource(item.id, "file:///owned/reselected.nar")

        val replaced = store.get(item.id)!!
        assertEquals(NarDownloadSource.Local("file:///owned/reselected.nar"), replaced.source)
        assertEquals(NarDownloadState.Queued, replaced.state)
        assertEquals("file:///owned/reselected.nar", replaced.retainedUri)
        assertEquals(listOf("install-nar-${item.id}", "install-nar-${item.id}"), work.enqueuedNames)
    }

    @Test fun reselectStartsANewAttemptAndFencesTheUnavailableWorker() {
        val item = repository.enqueueLocal("content://provider/unavailable.nar")
        installer.failure = SecurityException("grant revoked")
        repository.install(item.id, item.attemptId) { false }
        installer.failure = null

        val replaced = repository.replaceLocalSource(
            item.id,
            "content://provider/reselected.nar",
        )!!

        assertEquals(item.attemptId + 1L, replaced.attemptId)
        repository.install(item.id, item.attemptId) { false }
        assertEquals(replaced, store.get(item.id))
    }

    @Test fun reconciliationSchedulesCompletedRegisteredDownload() {
        downloads.nextDownloadId = 73L
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")
        downloads.statuses[73L] = NarRemoteDownloadStatus.Successful(
            "file:///owned/${item.id}.nar",
        )

        repository.reconcile()

        assertEquals(listOf("install-nar-${item.id}"), work.enqueuedNames)
        assertEquals("file:///owned/${item.id}.nar", store.get(item.id)!!.retainedUri)
    }

    @Test fun reconciliationReattachesDownloadWhoseIdWasNotPersisted() {
        val item = store.create(
            NarDownload(
                id = "orphaned-item",
                source = NarDownloadSource.Remote("https://example.invalid/archive.nar"),
                retainedUri = "file:///owned/orphaned-item.nar",
                state = NarDownloadState.Downloading,
            ),
        )
        downloads.recoveredIds[item.retainedUri!!] = 74L
        downloads.statuses[74L] = NarRemoteDownloadStatus.InProgress

        repository.reconcile()

        assertEquals(74L, store.get(item.id)!!.downloadManagerId)
        assertEquals(NarDownloadState.Downloading, store.get(item.id)!!.state)
    }

    @Test fun reconciliationSchedulesQueuedLocalArchive() {
        val item = repository.enqueueLocal("file:///owned/archive.nar")
        work.enqueuedNames.clear()

        repository.reconcile()

        assertEquals(listOf("install-nar-${item.id}"), work.enqueuedNames)
        assertEquals(NarDownloadState.Queued, store.get(item.id)!!.state)
    }

    @Test fun reconciliationPreservesTrackedLocalArchiveFilesDuringCleanup() {
        val item = store.create(
            NarDownload(
                id = "tracked-copy",
                source = NarDownloadSource.Local("content://provider/archive.nar"),
                retainedUri = "file:///owned/tracked-copy.nar",
                state = NarDownloadState.NeedsAttention(NarDownloadState.Failure("copy interrupted")),
            ),
        )

        repository.reconcile()

        assertEquals(setOf(item.retainedUri), ownedData.retainedLocalArchiveUris)
    }

    @Test fun deleteThenReenqueueUsesSeparateStagingDirectories() {
        val oldItem = repository.enqueueLocal("content://provider/archive.nar")
        repository.install(oldItem.id) { false }
        val oldAttempt = installer.stagingDirectories.single()
        repository.delete(oldItem.id)

        val newItem = repository.enqueueLocal("content://provider/archive.nar")
        repository.install(newItem.id) { false }

        assertNotEquals(oldItem.id, newItem.id)
        assertNotEquals(oldAttempt, installer.stagingDirectories.last())
    }

    @Test fun deleteCanCancelWhileInstallIsStillRunning() {
        val enteredInstall = CountDownLatch(1)
        val releaseInstall = CountDownLatch(1)
        val blockingInstaller = object : NarArchiveInstaller {
            override fun install(
                download: NarDownload,
                stagingDirectory: File,
                isStopped: () -> Boolean,
            ): ArchiveInstallResult {
                enteredInstall.countDown()
                releaseInstall.await(5, TimeUnit.SECONDS)
                return ArchiveInstallResult.Cancelled
            }
        }
        val cancellableRepository = NarDownloadRepository(
            store = store,
            downloads = downloads,
            work = work,
            installer = blockingInstaller,
            ownedData = ownedData,
            attemptPaths = attempts,
            supervisor = supervisor,
            remoteProgress = remoteProgress,
            nextId = { ids.removeFirst() },
        )
        val item = cancellableRepository.enqueueLocal("content://provider/archive.nar")
        val installThread = Thread { cancellableRepository.install(item.id) { false } }
        installThread.start()
        assertTrue(enteredInstall.await(2, TimeUnit.SECONDS))

        val deleteThread = Thread { cancellableRepository.delete(item.id) }
        deleteThread.start()
        deleteThread.join(2_000)
        val deleteCompletedBeforeInstall = !deleteThread.isAlive
        releaseInstall.countDown()
        installThread.join(5_000)
        deleteThread.join(5_000)

        assertTrue("delete waited for the running install", deleteCompletedBeforeInstall)
        assertNull(store.get(item.id))
    }

    private class FakeDownloadGateway : NarDownloadGateway {
        var nextDownloadId = 1L
        var onEnqueue: ((String) -> Unit)? = null
        var intendedDestinationFailure: Exception? = null
        val statuses = mutableMapOf<Long, NarRemoteDownloadStatus?>()
        val recoveredIds = mutableMapOf<String, Long>()
        val removedIds = mutableListOf<Long>()
        val downloadedBytes = mutableMapOf<Long, Long>()

        override fun intendedRetainedUri(itemId: String): String {
            intendedDestinationFailure?.let { throw it }
            return "file:///owned/$itemId.nar"
        }

        override fun enqueue(itemId: String, normalizedHttpsUrl: String): NarRemoteEnqueue {
            onEnqueue?.invoke(itemId)
            return NarRemoteEnqueue(nextDownloadId, "file:///owned/$itemId.nar")
        }

        override fun findDownloadId(retainedUri: String) = recoveredIds[retainedUri]

        override fun remove(downloadManagerId: Long) {
            removedIds += downloadManagerId
        }

        override fun status(downloadManagerId: Long) = statuses[downloadManagerId]

        override fun downloadedBytes(downloadManagerId: Long) = downloadedBytes[downloadManagerId]
    }

    private class FakeWorkScheduler : NarInstallWorkScheduler {
        val enqueuedNames = mutableListOf<String>()
        val cancelledNames = mutableListOf<String>()
        val cancelledBindings = mutableListOf<String>()

        override fun enqueue(itemId: String) {
            enqueuedNames += NarDownloadRepository.workName(itemId)
        }

        override fun cancel(itemId: String) {
            cancelledNames += NarDownloadRepository.workName(itemId)
        }
    }

    private class FakeArchiveInstaller : NarArchiveInstaller {
        var result: ArchiveInstallResult = ArchiveInstallResult.Installed("installed")
        var failure: Exception? = null
        val stagingDirectories = mutableListOf<File>()
        var onInstall: ((NarDownload, File, () -> Boolean, (String, Long) -> Unit) -> ArchiveInstallResult)? = null

        override fun install(
            download: NarDownload,
            stagingDirectory: File,
            isStopped: () -> Boolean,
        ): ArchiveInstallResult {
            stagingDirectories += stagingDirectory
            failure?.let { throw it }
            return result
        }

        override fun install(
            download: NarDownload,
            stagingDirectory: File,
            isStopped: () -> Boolean,
            onProgress: (phase: String, completed: Long) -> Unit,
        ): ArchiveInstallResult {
            stagingDirectories += stagingDirectory
            failure?.let { throw it }
            return onInstall?.invoke(download, stagingDirectory, isStopped, onProgress) ?: result
        }
    }

    private class FakeOwnedData : NarOwnedDownloadData {
        val deletedItemIds = mutableListOf<String>()
        val releasedItemIds = mutableListOf<String>()
        var retainedLocalArchiveUris = emptySet<String?>()

        override fun delete(download: NarDownload) {
            deletedItemIds += download.id
        }

        override fun releasePersistedGrant(download: NarDownload) {
            releasedItemIds += download.id
        }

        override fun deleteAbandonedLocalArchives(retainedUris: Set<String>) {
            retainedLocalArchiveUris = retainedUris
        }
    }

    private class FakeAttemptPaths : NarInstallAttemptPaths {
        private var attempt = 0

        override fun create(itemId: String): File =
            File("staging/$itemId/attempt-${attempt++}")
    }

    private class RecordingOperationCancellation(
        private val downloads: FakeDownloadGateway,
        private val work: FakeWorkScheduler,
    ) : OperationCancellation {
        override fun cancel(handle: OperationHandle, binding: ExternalJobBinding) {
            when (binding) {
                is ExternalJobBinding.DownloadManager -> downloads.remove(binding.id)
                is ExternalJobBinding.WorkManager -> work.cancelledBindings += binding.uuid
            }
        }
    }

    private class FakeRemoteProgressObserver(
        downloads: NarDownloadGateway,
        supervisor: DurableOperationSupervisor,
    ) : NarRemoteProgressObserver {
        private val delegate = DownloadManagerProgressObserver(downloads, supervisor)
        val started = mutableListOf<Pair<OperationHandle, Long>>()
        val stopped = mutableListOf<OperationHandle>()

        override fun start(handle: OperationHandle, downloadManagerId: Long) {
            started += handle to downloadManagerId
        }

        override fun stop(handle: OperationHandle) {
            stopped += handle
        }

        override fun observeOnce(handle: OperationHandle, downloadManagerId: Long) =
            delegate.observeOnce(handle, downloadManagerId)
    }

    private class FakeProgressScheduler : NarProgressScheduler {
        private val pending = ArrayDeque<Runnable>()
        val pendingCount get() = pending.size

        override fun post(task: Runnable, delayMillis: Long) {
            pending += task
        }

        override fun cancel(task: Runnable) {
            pending.remove(task)
        }

        fun runNext() {
            pending.removeFirst().run()
        }
    }

    private class ActivityLikeOwner(
        repository: NarDownloadRepository,
        executor: Executor,
        privateImports: File,
    ) {
        val handoff = NarLiveGrantHandoff(repository, executor, privateImports)
    }

    private fun enqueueFromActivityLikeOwner(
        repository: NarDownloadRepository,
        executor: Executor,
        privateImports: File,
        closeCount: AtomicInteger,
    ): WeakReference<ActivityLikeOwner> {
        val owner = ActivityLikeOwner(repository, executor, privateImports)
        owner.handoff.enqueue("content://provider/owner-release.nar", null) {
            closeCountingSource(closeCount)
        }
        return WeakReference(owner)
    }

    private fun closeCountingSource(closeCount: AtomicInteger) =
        object : ByteArrayInputStream(ByteArray(20 * 1024) { 7 }) {
            override fun close() {
                closeCount.incrementAndGet()
                super.close()
            }
        }

    private fun assertEventuallyCollected(reference: WeakReference<*>) {
        repeat(40) {
            System.gc()
            if (reference.get() == null) return
            Thread.sleep(25L)
        }
        assertNull("submitted copy retained its Activity owner", reference.get())
    }

    private fun NarDownload.handle() = OperationHandle(OperationId(id), AttemptId(attemptId))
}
