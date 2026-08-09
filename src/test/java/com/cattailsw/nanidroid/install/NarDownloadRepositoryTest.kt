package com.cattailsw.nanidroid.install

import androidx.work.ListenableWorker
import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.durable.AttemptId
import com.cattailsw.nanidroid.durable.DurableOperationRecord
import com.cattailsw.nanidroid.durable.DurableOperationStore
import com.cattailsw.nanidroid.durable.DurableOperationSupervisor
import com.cattailsw.nanidroid.durable.ExternalJobBinding
import com.cattailsw.nanidroid.durable.OperationCancellation
import com.cattailsw.nanidroid.durable.OperationHandle
import com.cattailsw.nanidroid.durable.OperationId
import com.cattailsw.nanidroid.durable.OperationKind
import com.cattailsw.nanidroid.durable.OperationStatus
import com.cattailsw.nanidroid.durable.SharedPreferencesDurableOperationStore
import com.cattailsw.nanidroid.durable.durableWorkManagerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private fun workId(itemId: String, attemptId: Long, kind: OperationKind): String =
    durableWorkManagerId(
        OperationHandle(OperationId(itemId), AttemptId(attemptId)),
        kind,
    ).toString()

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
    private val stopReconciliation = FakeStopReconciliationScheduler()
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
        stopReconciliation = stopReconciliation,
        nextId = { ids.removeFirst() },
    )

    @Test fun remoteEnqueueStartsProgressObservationForExactAttemptAndRow() {
        downloads.nextDownloadId = 30L

        val result = repository.enqueueRemoteForUser("https://example.invalid/archive.nar")
        val item = result.download

        assertTrue(result.acceptedActive)
        assertEquals(listOf(item.handle() to 30L), remoteProgress.started)
    }

    @Test fun remoteUserEnqueueReportsRejectedWhenDownloadCannotStart() {
        downloads.intendedDestinationFailure = IllegalStateException("storage unavailable")

        val result = repository.enqueueRemoteForUser("https://example.invalid/archive.nar")

        assertTrue(!result.acceptedActive)
        assertTrue(result.download.state is NarDownloadState.NeedsAttention)
    }

    @Test fun localCopyUserEnqueueReportsAcceptedOnlyWhenStageWorkIsActive() {
        val result = repository.enqueueLocalCopyForUser("content://provider/archive.nar")

        assertTrue(result.acceptedActive)
        assertTrue(result.download.state is NarDownloadState.Copying)
        assertNotNull(result.download.workManagerId)
    }

    @Test fun reselectUserEnqueueRejectsAnUnchangedStoppingAttempt() {
        val item = repository.enqueueLocal("content://provider/archive.nar")
        assertTrue(repository.stop(item.id))

        val result = repository.replaceLocalSourceForUser(
            item.id,
            "content://provider/reselected.nar",
        )!!

        assertTrue(!result.acceptedActive)
        assertEquals(item, result.download)
        assertEquals(OperationStatus.CANCEL_REQUESTED, operationStore.read().single().status)
    }

    @Test fun liveGrantReselectRejectsAnUnchangedStoppingAttempt() {
        val item = repository.enqueueLocalCopy("content://provider/archive.nar")
        assertTrue(repository.stop(item.id))
        val closeCount = AtomicInteger()
        val handoff = NarLiveGrantHandoff(
            repository = repository,
            executor = Executor { throw AssertionError("stopping replacement must not be scheduled") },
            stage = { _, _, _ -> NarLocalArchiveStager.Result.Cancelled },
        )

        val result = handoff.enqueueForUser(
            "content://provider/reselected.nar",
            item.id,
        ) { closeCountingSource(closeCount) }!!

        assertTrue(!result.acceptedActive)
        assertEquals(item, result.download)
        assertEquals(1, closeCount.get())
        assertEquals(OperationStatus.CANCEL_REQUESTED, operationStore.read().single().status)
    }

    @Test fun liveGrantReselectAcceptsReplacementAttempt() {
        val item = repository.enqueueLocalCopy("content://provider/archive.nar")
        val handoff = NarLiveGrantHandoff(
            repository = repository,
            executor = Executor(Runnable::run),
            stage = { _, _, _ -> NarLocalArchiveStager.Result.Cancelled },
        )

        val result = handoff.enqueueForUser(
            "content://provider/reselected.nar",
            item.id,
        ) { ByteArrayInputStream(byteArrayOf(1)) }!!

        assertTrue(result.acceptedActive)
        assertEquals(NarDownloadSource.Local("content://provider/reselected.nar"), result.download.source)
        assertTrue(result.download.handle() != item.handle())
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

    @Test fun losingDownloadRowCannotReportProgressForWinningRow() {
        downloads.nextDownloadId = 33L
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")
        downloads.downloadedBytes[32L] = 9L
        downloads.downloadedBytes[33L] = 10L
        val observer = DownloadManagerProgressObserver(downloads, supervisor)

        assertTrue(!observer.observeOnce(item.handle(), 32L))
        assertEquals(0L, operationStore.read().single().progress.completed)
        assertTrue(observer.observeOnce(item.handle(), 33L))
        assertEquals(10L, operationStore.read().single().progress.completed)
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

    @Test fun stoppedProgressObservationCannotRescheduleAfterItsPollRuns() {
        downloads.nextDownloadId = 34L
        downloads.downloadedBytes[34L] = 1L
        downloads.statuses[34L] = NarRemoteDownloadStatus.InProgress
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")
        val scheduler = FakeProgressScheduler()
        val observer = DownloadManagerProgressObserver(downloads, supervisor, scheduler)

        observer.start(item.handle(), 34L)
        val stalePoll = scheduler.takeNext()
        observer.stop(item.handle())
        stalePoll.run()

        assertEquals(0, scheduler.pendingCount)
    }

    @Test fun cancelThenRetryUsesNewAttemptAndRejectsLateDownloadCompletion() {
        downloads.nextDownloadId = 41L
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")

        assertTrue(repository.stop(item.id))
        assertEquals(NarDownloadState.Downloading, store.get(item.id)!!.state)
        assertEquals(OperationStatus.CANCEL_REQUESTED, operationStore.read().single().status)
        assertEquals(item, repository.retry(item.id))
        repository.reconcile()
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

    @Test fun remoteStopStaysStoppingWhileExactDownloadIsActive() {
        downloads.nextDownloadId = 45L
        val item = repository.enqueueRemote("https://example.invalid/active.nar")
        downloads.statuses[45L] = NarRemoteDownloadStatus.InProgress
        val enqueuesBeforeRetry = work.enqueuedNames.toList()
        val progressStartsBeforeStop = remoteProgress.started.toList()

        assertTrue(repository.stop(item.id))
        val retryWhileStopping = repository.retry(item.id)
        repository.reconcile()

        assertEquals(item, retryWhileStopping)
        assertEquals(NarDownloadState.Downloading, store.get(item.id)!!.state)
        assertEquals(OperationStatus.CANCEL_REQUESTED, operationStore.read().single().status)
        assertEquals(enqueuesBeforeRetry, work.enqueuedNames)
        assertEquals(listOf(item.handle()), remoteProgress.stopped)
        assertEquals(progressStartsBeforeStop, remoteProgress.started)
    }

    @Test fun remoteFailureWinsOverRequestedCancellation() {
        downloads.nextDownloadId = 46L
        val item = repository.enqueueRemote("https://example.invalid/failed.nar")
        downloads.statuses[46L] = NarRemoteDownloadStatus.Failed

        assertTrue(repository.stop(item.id))
        repository.reconcile()

        assertTrue(store.get(item.id)!!.state is NarDownloadState.NeedsAttention)
        assertEquals(OperationStatus.FAILED, operationStore.read().single().status)
    }

    @Test fun stopReconciliationConfirmsRemoteCancellationWithoutProcessRestart() {
        downloads.nextDownloadId = 48L
        val item = repository.enqueueRemote("https://example.invalid/cancelled.nar")

        assertTrue(repository.stop(item.id))
        assertTrue(stopReconciliation.hasPending(item.handle()))
        stopReconciliation.run(item.handle())

        assertEquals(NarDownloadState.Cancelled, store.get(item.id)!!.state)
        assertEquals(OperationStatus.CANCELLED, operationStore.read().single().status)
        assertTrue(!stopReconciliation.hasPending(item.handle()))
    }

    @Test fun productionStopReconciliationRunsOffTheCallingThread() {
        val scheduler = BackgroundStopReconciliationScheduler()
        val handle = OperationHandle(OperationId("background-stop"), AttemptId(1L))
        val caller = Thread.currentThread()
        val executedOn = AtomicReference<Thread>()
        val completed = CountDownLatch(1)

        scheduler.schedule(handle, 0L, Runnable {
            executedOn.set(Thread.currentThread())
            completed.countDown()
        })

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertNotEquals(caller, executedOn.get())
        assertEquals("nanidroid-stop-reconciliation", executedOn.get().name)
    }

    @Test fun productionRemoteProgressPollingRunsQueriesAndPersistenceOffTheCallerThread() {
        val progressStore = CountingOperationStore()
        val progressSupervisor = DurableOperationSupervisor(
            progressStore,
            MonotonicClock { 0L },
            cancellations,
        )
        val handle = OperationHandle(OperationId("background-progress"), AttemptId(1L))
        val binding = ExternalJobBinding.DownloadManager(32L)
        val caller = Thread.currentThread()
        val queriedOn = AtomicReference<Thread>()
        val persistedOn = AtomicReference<Thread>()
        val completed = CountDownLatch(1)
        downloads.downloadedBytes[32L] = 1L
        downloads.statuses[32L] = NarRemoteDownloadStatus.InProgress
        downloads.onDownloadedBytes = { queriedOn.set(Thread.currentThread()) }
        progressStore.onProgressUpdate = {
            persistedOn.set(Thread.currentThread())
            completed.countDown()
        }
        assertTrue(
            progressSupervisor.start(
                handle,
                OperationKind.REMOTE_NAR,
                "Downloading archive",
                0L,
                binding,
            ),
        )

        DownloadManagerProgressObserver(downloads, progressSupervisor).start(handle, 32L)

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertNotEquals(caller, queriedOn.get())
        assertEquals(queriedOn.get(), persistedOn.get())
        assertEquals("nanidroid-download-progress", queriedOn.get().name)
    }

    @Test fun deleteCannotOrphanStoppingAttempt() {
        val item = repository.enqueueLocal("file:///owned/delete-stopping.nar")

        assertTrue(repository.stop(item.id))

        assertTrue(!repository.delete(item.id))
        assertEquals(item, store.get(item.id))
        assertEquals(OperationStatus.CANCEL_REQUESTED, operationStore.read().single().status)
    }

    @Test fun remoteCompletionWinsOverRequestedCancellationAndHandsOffOnce() {
        downloads.nextDownloadId = 47L
        val item = repository.enqueueRemote("https://example.invalid/complete.nar")

        assertTrue(repository.stop(item.id))
        repository.onDownloadComplete(47L)

        val install = store.get(item.id)!!
        assertEquals(item.attemptId + 1L, install.attemptId)
        assertEquals(NarDownloadState.Queued, install.state)
        assertEquals(OperationKind.NAR_INSTALL, operationStore.read().single().kind)
        assertEquals(OperationStatus.RUNNING, operationStore.read().single().status)
        assertEquals(listOf(NarDownloadRepository.workName(item.id)), work.enqueuedNames)
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
        assertTrue(work.enqueuedNames.isEmpty())
        assertEquals(1, work.installEnqueuedIds.size)
    }

    @Test fun staleInstallWorkerCannotMutateNewAttempt() {
        val item = repository.enqueueLocal("file:///owned/archive.nar")
        val firstAttempt = item.attemptId
        assertTrue(repository.stop(item.id))
        repository.workerStopped(item.id, item.attemptId, item.workManagerId!!)
        val retry = repository.retry(item.id)!!

        repository.install(item.id, firstAttempt, item.workManagerId!!) { false }

        assertEquals(retry, store.get(item.id))
        assertTrue(installer.stagingDirectories.isEmpty())
    }

    @Test fun losingInstallWorkerCannotMutateWinningAttempt() {
        val item = repository.enqueueLocal("file:///owned/archive.nar")
        val winningWorkId = item.workManagerId!!
        val losingWorkId = "install-nar-${item.id}-loser"

        assertTrue(repository.install(item.id, item.attemptId, losingWorkId) { false })
        assertEquals(item, store.get(item.id))
        assertTrue(installer.stagingDirectories.isEmpty())

        assertTrue(repository.install(item.id, item.attemptId, winningWorkId) { false })
        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
    }

    @Test fun losingStageWorkerCannotMutateWinningAttempt() {
        val item = repository.enqueueLocalCopy("content://provider/archive.nar")
        val winningWorkId = item.workManagerId!!
        val losingWorkId = "stage-local-nar-${item.id}-loser"
        var losingStageRan = false

        assertTrue(
            repository.stageLocal(item.id, item.attemptId, losingWorkId, { false }) { _, _, _ ->
                losingStageRan = true
                NarLocalArchiveStager.Result.Staged("file:///owned/loser.nar")
            },
        )
        assertTrue(!losingStageRan)
        assertEquals(item, store.get(item.id))

        assertTrue(
            repository.stageLocal(item.id, item.attemptId, winningWorkId, { false }) { _, _, _ ->
                NarLocalArchiveStager.Result.Staged("file:///owned/winner.nar")
            },
        )
        val installAttempt = store.get(item.id)!!
        assertEquals(item.attemptId + 1L, installAttempt.attemptId)
        assertEquals("file:///owned/winner.nar", installAttempt.retainedUri)
    }

    @Test fun losingStageEnqueueCannotFailWinningBinding() {
        val winningBinding = ExternalJobBinding.WorkManager(
            "33333333-3333-3333-3333-333333333333",
        )
        var winningRow: NarDownload? = null
        work.beforeNextStagePrepared = { itemId, attemptId ->
            val handle = OperationHandle(OperationId(itemId), AttemptId(attemptId))
            assertTrue(supervisor.bindExternalJob(handle, winningBinding))
            winningRow = store.update(itemId) { current ->
                current.copy(workManagerId = winningBinding.uuid)
            }
        }

        val item = repository.enqueueLocalCopy("content://provider/archive.nar")

        assertEquals(winningRow, store.get(item.id))
        val operation = operationStore.read().single()
        assertEquals(OperationKind.LOCAL_NAR, operation.kind)
        assertEquals(OperationStatus.RUNNING, operation.status)
        assertEquals(winningBinding, operation.externalJob)
    }

    @Test fun losingInstallEnqueueCannotFailWinningBinding() {
        val winningBinding = ExternalJobBinding.WorkManager(
            "44444444-4444-4444-4444-444444444444",
        )
        var winningRow: NarDownload? = null
        work.beforeNextInstallPrepared = { itemId, attemptId ->
            val handle = OperationHandle(OperationId(itemId), AttemptId(attemptId))
            assertTrue(supervisor.bindExternalJob(handle, winningBinding))
            winningRow = store.update(itemId) { current ->
                current.copy(workManagerId = winningBinding.uuid)
            }
        }

        val item = repository.enqueueLocal("file:///owned/archive.nar")

        assertEquals(winningRow, store.get(item.id))
        val operation = operationStore.read().single()
        assertEquals(OperationKind.NAR_INSTALL, operation.kind)
        assertEquals(OperationStatus.RUNNING, operation.status)
        assertEquals(winningBinding, operation.externalJob)
    }

    @Test fun installProgressUsesExactAttemptAndTerminalCallbackIsFenced() {
        val item = repository.enqueueLocal("file:///owned/archive.nar")
        installer.onInstall = { _, _, _, onProgress ->
            onProgress("Extracting archive", 8L)
            onProgress("Extracting archive", 8L)
            ArchiveInstallResult.Installed("installed")
        }

        repository.install(item.id, item.attemptId, item.workManagerId!!) { false }

        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
        val terminal = operationStore.read().single()
        assertEquals(OperationStatus.COMPLETED, terminal.status)
        assertEquals(8L, terminal.progress.completed)
        assertTrue(
            !supervisor.reportProgress(
                item.handle(),
                ExternalJobBinding.WorkManager(item.workManagerId!!),
                "Late",
                9L,
            ),
        )
    }

    @Test fun repositoryKeepsEveryInstallerCallbackWhileBoundingDurableProgressWrites() {
        val progressStore = CountingOperationStore()
        val progressClock = MutableClock()
        val progressSupervisor = DurableOperationSupervisor(
            progressStore,
            progressClock,
            cancellations,
        )
        val progressInstaller = FakeArchiveInstaller()
        var callbackCount = 0
        val archiveBytes = 512L * 1024L * 1024L
        val chunkBytes = 8L * 1024L
        progressInstaller.onInstall = { _, _, _, onProgress ->
            var completed = chunkBytes
            while (completed <= archiveBytes) {
                callbackCount += 1
                onProgress("Extracting archive", completed)
                completed += chunkBytes
            }
            ArchiveInstallResult.Installed("installed")
        }
        val boundedRepository = NarDownloadRepository(
            store = NarDownloadStore(NarDownloadStore.MemoryStorage()),
            downloads = downloads,
            work = work,
            installer = progressInstaller,
            ownedData = ownedData,
            attemptPaths = attempts,
            supervisor = progressSupervisor,
            installProgress = ThrottledNarInstallProgressReporter(
                progressSupervisor,
                progressClock,
            ),
            nextId = { "bounded-progress" },
        )
        val item = boundedRepository.enqueueLocal("file:///owned/large.nar")

        boundedRepository.install(item.id, item.attemptId, item.workManagerId!!) { false }

        assertEquals(65_536, callbackCount)
        assertEquals(513, progressStore.progressUpdateCount)
        val completed = progressStore.read().single()
        assertEquals(archiveBytes, completed.progress.completed)
        assertEquals(OperationStatus.COMPLETED, completed.status)
    }

    @Test fun stopAfterAtomicPublicationStillCompletesQueueAndCleanup() {
        val item = repository.enqueueLocal("file:///owned/archive.nar")
        installer.onInstall = { download, _, isStopped, _ ->
            assertTrue(repository.stop(download.id))
            assertTrue(isStopped())
            ArchiveInstallResult.Installed("installed")
        }

        repository.install(item.id, item.attemptId, item.workManagerId!!) { false }

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

        repository.stageLocal(
            item.id,
            item.attemptId,
            item.workManagerId!!,
            { false },
        ) { _, isCancelled, onProgress ->
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

    @Test fun recreatedStageWorkerCommitsHandoffWhenCopySupervisorAlreadyCompleted() {
        val item = repository.enqueueLocalCopy("content://provider/archive.nar")
        assertTrue(
            supervisor.finish(
                item.handle(),
                ExternalJobBinding.WorkManager(item.workManagerId!!),
                OperationStatus.COMPLETED,
            ),
        )
        val recreated = recreatedRepository()

        recreated.stageLocal(
            item.id,
            item.attemptId,
            item.workManagerId!!,
            { false },
        ) { _, _, _ ->
            NarLocalArchiveStager.Result.Staged("file:///owned/replayed-copy.nar")
        }

        val installAttempt = store.get(item.id)!!
        assertEquals(item.attemptId + 1L, installAttempt.attemptId)
        assertEquals(NarDownloadState.Queued, installAttempt.state)
        assertEquals("file:///owned/replayed-copy.nar", installAttempt.retainedUri)
        var staleCallbackRan = false
        recreated.stageLocal(
            item.id,
            item.attemptId,
            item.workManagerId!!,
            { false },
        ) { _, _, _ ->
            staleCallbackRan = true
            NarLocalArchiveStager.Result.Staged("file:///owned/stale-copy.nar")
        }
        assertTrue(!staleCallbackRan)
        assertEquals(installAttempt, store.get(item.id))
    }

    @Test fun reconciliationFinishesCopyPredecessorAfterQueuedHandoffWasPersisted() {
        val item = repository.enqueueLocalCopy("content://provider/archive.nar")
        store.update(item.id) {
            it.copy(
                attemptId = item.attemptId + 1L,
                retainedUri = "file:///owned/staged-copy.nar",
                workManagerId = null,
                state = NarDownloadState.Queued,
            )
        }

        recreatedRepository().reconcile()

        val installAttempt = store.get(item.id)!!
        assertEquals(item.attemptId + 1L, installAttempt.attemptId)
        assertEquals(workId(item.id, installAttempt.attemptId, OperationKind.NAR_INSTALL), installAttempt.workManagerId)
        val operation = operationStore.read().single()
        assertEquals(installAttempt.attemptId, operation.attemptId.value)
        assertEquals(OperationKind.NAR_INSTALL, operation.kind)
        assertEquals(OperationStatus.RUNNING, operation.status)
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
        repository.workerStopped(item.id, item.attemptId, item.workManagerId!!)
        val retry = repository.retry(item.id)!!
        var staleStageStarted = false

        repository.stageLocal(item.id, item.attemptId, item.workManagerId!!, { false }) { _, _, _ ->
            staleStageStarted = true
            NarLocalArchiveStager.Result.Staged("file:///stale/archive.nar")
        }

        assertTrue(!staleStageStarted)
        assertEquals(retry, store.get(item.id))
    }

    @Test fun schedulerStoppedCallbackDoesNotCancelAndLateOldCallbackCannotMutateRetry() {
        val item = repository.enqueueLocal("file:///owned/archive.nar")

        repository.workerStopped(item.id, item.attemptId, item.workManagerId!!)

        assertEquals(NarDownloadState.Queued, store.get(item.id)!!.state)
        assertEquals(OperationStatus.RUNNING, operationStore.read().single().status)
        assertTrue(repository.stop(item.id))
        assertTrue(repository.stop(item.id))
        assertEquals(NarDownloadState.Queued, store.get(item.id)!!.state)
        assertEquals(OperationStatus.CANCEL_REQUESTED, operationStore.read().single().status)
        assertEquals(item, repository.retry(item.id))
        repository.workerStopped(item.id, item.attemptId, item.workManagerId!!)
        assertEquals(NarDownloadState.Cancelled, store.get(item.id)!!.state)
        assertEquals(OperationStatus.CANCELLED, operationStore.read().single().status)
        assertEquals(1, work.cancelledBindings.size)
        val retry = repository.retry(item.id)!!

        repository.workerStopped(item.id, item.attemptId, item.workManagerId!!)

        assertEquals(retry, store.get(item.id))
    }

    @Test fun recreatedStoppingInstallDoesNotRecreateMissingExactWork() {
        val item = repository.enqueueLocal("file:///owned/stopping-missing.nar")
        assertTrue(repository.stop(item.id))
        work.installEnqueuedIds.clear()
        work.enqueuedNames.clear()

        recreatedRepository().reconcile()

        assertEquals(NarDownloadState.Cancelled, store.get(item.id)!!.state)
        assertEquals(OperationStatus.CANCELLED, operationStore.read().single().status)
        assertTrue(work.installEnqueuedIds.isEmpty())
        assertTrue(work.enqueuedNames.isEmpty())
    }

    @Test fun recreatedStoppingStageDoesNotRecreateMissingExactWork() {
        val item = repository.enqueueLocalCopy("content://provider/stopping-missing.nar")
        assertTrue(repository.stop(item.id))
        work.stageWorkStates.remove(item.workManagerId!!)
        work.stageRecreatedIds.clear()

        recreatedRepository().reconcile()

        assertEquals(NarDownloadState.Cancelled, store.get(item.id)!!.state)
        assertEquals(OperationStatus.CANCELLED, operationStore.read().single().status)
        assertTrue(work.stageRecreatedIds.isEmpty())
    }

    @Test fun systemStoppedInstallWorkerRetriesWithoutCancellingAndCanReplay() {
        val item = repository.enqueueLocal("file:///owned/archive.nar")
        var cancellationObserved = false
        installer.onInstall = { _, _, isStopped, _ ->
            cancellationObserved = isStopped()
            ArchiveInstallResult.Cancelled
        }

        val stopped = InstallNarWorker.execute(
            repository,
            item.id,
            item.attemptId,
            item.workManagerId!!,
        ) { true }

        assertEquals(ListenableWorker.Result.retry(), stopped)
        assertTrue(cancellationObserved)
        assertEquals(NarDownloadState.Installing, store.get(item.id)!!.state)
        assertEquals(OperationStatus.RUNNING, operationStore.read().single().status)

        repository.workerStopped(item.id, item.attemptId, item.workManagerId!!)
        assertEquals(NarDownloadState.Installing, store.get(item.id)!!.state)
        assertEquals(OperationStatus.RUNNING, operationStore.read().single().status)

        installer.onInstall = null
        installer.result = ArchiveInstallResult.Installed("installed")
        val replay = InstallNarWorker.execute(
            repository,
            item.id,
            item.attemptId,
            item.workManagerId!!,
        ) { false }

        assertEquals(ListenableWorker.Result.success(), replay)
        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
        assertEquals(OperationStatus.COMPLETED, operationStore.read().single().status)

        repository.workerStopped(item.id, item.attemptId, item.workManagerId!!)
        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
    }

    @Test fun systemStoppedStageWorkerRetriesWithoutCancellingAndCanReplay() {
        val item = repository.enqueueLocalCopy("content://provider/archive.nar")
        var cancellationObserved = false

        val stopped = StageLocalNarWorker.execute(
            repository,
            item.id,
            item.attemptId,
            item.workManagerId!!,
            { true },
        ) { _, isCancelled, _ ->
            cancellationObserved = isCancelled()
            NarLocalArchiveStager.Result.Cancelled
        }

        assertEquals(ListenableWorker.Result.retry(), stopped)
        assertTrue(cancellationObserved)
        assertEquals(NarDownloadState.Copying, store.get(item.id)!!.state)
        assertEquals(OperationStatus.RUNNING, operationStore.read().single().status)

        repository.workerStopped(item.id, item.attemptId, item.workManagerId!!)
        assertEquals(NarDownloadState.Copying, store.get(item.id)!!.state)
        assertEquals(OperationStatus.RUNNING, operationStore.read().single().status)

        val replay = StageLocalNarWorker.execute(
            repository,
            item.id,
            item.attemptId,
            item.workManagerId!!,
            { false },
        ) { _, _, _ ->
            NarLocalArchiveStager.Result.Staged("file:///owned/replayed-stage.nar")
        }

        assertEquals(ListenableWorker.Result.success(), replay)
        val installAttempt = store.get(item.id)!!
        assertEquals(item.attemptId + 1L, installAttempt.attemptId)
        assertEquals(NarDownloadState.Queued, installAttempt.state)
        assertEquals(OperationKind.NAR_INSTALL, operationStore.read().single().kind)
        assertEquals(OperationStatus.RUNNING, operationStore.read().single().status)

        repository.workerStopped(item.id, item.attemptId, item.workManagerId!!)
        assertEquals(installAttempt, store.get(item.id))
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

        val result = InstallNarWorker.execute(
            repository,
            item.id,
            item.attemptId,
            item.workManagerId!!,
        ) { false }

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(store.get(item.id)!!.state is NarDownloadState.NeedsAttention)
    }

    @Test fun rescheduledInstallDoesNotRetryNeedsAttention() {
        val item = repository.enqueueLocal("content://provider/archive.nar")
        val attention = NarDownloadState.NeedsAttention(
            NarDownloadState.Failure("install interrupted"),
        )
        store.update(item.id) { it.copy(state = attention) }

        repository.install(item.id, item.attemptId, item.workManagerId!!) { false }

        assertEquals(attention, store.get(item.id)!!.state)
        assertTrue(installer.stagingDirectories.isEmpty())
    }

    @Test fun successfulInstallCleansOwnedArchiveAndKeepsCompletionVisible() {
        val item = repository.enqueueLocal("file:///owned/archive.nar", "file:///owned/archive.nar")

        InstallNarWorker.execute(
            repository,
            item.id,
            item.attemptId,
            item.workManagerId!!,
        ) { false }

        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
        assertEquals(listOf(item.id), ownedData.deletedItemIds)
    }

    @Test fun successfulRemoteInstallRemovesCompletedDownload() {
        downloads.nextDownloadId = 61L
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")
        repository.onDownloadComplete(61L)
        val installAttempt = store.get(item.id)!!

        repository.install(
            installAttempt.id,
            installAttempt.attemptId,
            installAttempt.workManagerId!!,
        ) { false }

        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
        assertEquals(listOf(61L), downloads.removedIds)
    }

    @Test fun failedRemoteInstallRetriesRetainedArchiveAsNewInstallAttempt() {
        downloads.nextDownloadId = 62L
        val download = repository.enqueueRemote("https://example.invalid/archive.nar")
        repository.onDownloadComplete(62L)
        val firstInstall = store.get(download.id)!!
        installer.result = ArchiveInstallResult.Failed(
            "invalid archive",
            ArchiveInstallFailure.InvalidArchive,
        )
        repository.install(
            firstInstall.id,
            firstInstall.attemptId,
            firstInstall.workManagerId!!,
        ) { false }
        ownedData.isRetainedArchiveAvailable = true
        work.enqueuedNames.clear()
        downloads.removedIds.clear()
        ownedData.deletedItemIds.clear()

        val retry = repository.retry(download.id)!!

        assertEquals(firstInstall.attemptId + 1L, retry.attemptId)
        assertEquals(NarDownloadState.Queued, retry.state)
        assertEquals(firstInstall.retainedUri, retry.retainedUri)
        assertEquals(62L, retry.downloadManagerId)
        assertTrue(downloads.removedIds.isEmpty())
        assertTrue(ownedData.deletedItemIds.isEmpty())
        assertEquals(listOf("install-nar-${download.id}"), work.enqueuedNames)
        val operation = operationStore.read().single()
        assertEquals(OperationKind.NAR_INSTALL, operation.kind)
        assertEquals(retry.attemptId, operation.attemptId.value)

        repository.install(
            download.id,
            firstInstall.attemptId,
            firstInstall.workManagerId!!,
        ) { false }
        assertEquals(retry, store.get(download.id))
    }

    @Test fun missingRemoteInstallArchiveRetriesThroughExplicitReacquisitionAttempt() {
        downloads.nextDownloadId = 63L
        val download = repository.enqueueRemote("https://example.invalid/archive.nar")
        repository.onDownloadComplete(63L)
        val firstInstall = store.get(download.id)!!
        installer.failure = FileNotFoundException("download vanished")
        repository.install(
            firstInstall.id,
            firstInstall.attemptId,
            firstInstall.workManagerId!!,
        ) { false }
        ownedData.isRetainedArchiveAvailable = false
        downloads.nextDownloadId = 64L
        downloads.removedIds.clear()

        val retry = repository.retry(download.id)!!

        assertEquals(firstInstall.attemptId + 1L, retry.attemptId)
        assertEquals(NarDownloadState.Downloading, retry.state)
        assertEquals(64L, retry.downloadManagerId)
        assertEquals(listOf(63L), downloads.removedIds)
        val operation = operationStore.read().single()
        assertEquals(OperationKind.REMOTE_NAR, operation.kind)
        assertEquals(OperationStatus.RUNNING, operation.status)
        assertEquals(retry.attemptId, operation.attemptId.value)

        repository.install(
            download.id,
            firstInstall.attemptId,
            firstInstall.workManagerId!!,
        ) { false }
        assertEquals(retry, store.get(download.id))
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

    @Test fun reconciliationFinishesInstallSupervisorAfterCompleteWasPersisted() {
        val item = repository.enqueueLocal("file:///owned/archive.nar", "file:///owned/archive.nar")
        store.update(item.id) { it.copy(state = NarDownloadState.Complete) }

        recreatedRepository().reconcile()

        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
        assertEquals(OperationStatus.COMPLETED, operationStore.read().single().status)
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

    @Test fun reconciliationRecoversStageBindingPersistedOnlyBySupervisor() {
        val item = store.create(
            NarDownload(
                id = "stage-binding-crash-window",
                source = NarDownloadSource.Local("content://provider/archive.nar"),
                retainedUri = "content://provider/archive.nar",
                state = NarDownloadState.Copying,
            ),
        )
        val binding = ExternalJobBinding.WorkManager("11111111-1111-1111-1111-111111111111")
        assertTrue(
            supervisor.start(
                item.handle(),
                OperationKind.LOCAL_NAR,
                "Copying archive",
                0L,
            ),
        )
        assertTrue(supervisor.bindExternalJob(item.handle(), binding))
        val recreated = recreatedRepository()

        recreated.reconcile()
        val recovered = store.get(item.id)!!
        recreated.reconcile()

        assertEquals(NarDownloadState.Copying, recovered.state)
        assertEquals(binding.uuid, recovered.workManagerId)
        assertEquals(setOf(binding.uuid), work.stageEnqueuedIds)
        assertEquals(listOf(binding.uuid), work.stageRecreatedIds)
        val operation = operationStore.read().single()
        assertEquals(OperationKind.LOCAL_NAR, operation.kind)
        assertEquals(OperationStatus.RUNNING, operation.status)
        assertEquals(binding, operation.externalJob)
    }

    @Test fun reconciliationRecoversInstallBindingPersistedOnlyBySupervisor() {
        val item = store.create(
            NarDownload(
                id = "install-binding-crash-window",
                source = NarDownloadSource.Local("file:///owned/archive.nar"),
                retainedUri = "file:///owned/archive.nar",
                state = NarDownloadState.Queued,
            ),
        )
        val binding = ExternalJobBinding.WorkManager("22222222-2222-2222-2222-222222222222")
        assertTrue(
            supervisor.start(
                item.handle(),
                OperationKind.NAR_INSTALL,
                "Installing archive",
                0L,
            ),
        )
        assertTrue(supervisor.bindExternalJob(item.handle(), binding))
        val recreated = recreatedRepository()

        recreated.reconcile()
        val recovered = store.get(item.id)!!
        recreated.reconcile()

        assertEquals(NarDownloadState.Queued, recovered.state)
        assertEquals(binding.uuid, recovered.workManagerId)
        assertEquals(listOf(binding.uuid), work.installEnqueuedIds)
        val operation = operationStore.read().single()
        assertEquals(OperationKind.NAR_INSTALL, operation.kind)
        assertEquals(OperationStatus.RUNNING, operation.status)
        assertEquals(binding, operation.externalJob)
    }

    @Test fun stageBindingStoreWriteFailureTerminalizesExactBoundAttempt() {
        val queueStore = NarDownloadStore(FailSelectedWriteStorage(failOnWrite = 2))
        val exactOperationStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val exactSupervisor = DurableOperationSupervisor(
            exactOperationStore,
            MonotonicClock { 0L },
            cancellations,
        )
        val exactRepository = repositoryWith(queueStore, exactSupervisor, "stage-write-failure")

        val result = exactRepository.enqueueLocalCopyForUser("content://provider/archive.nar")
        val item = result.download

        assertTrue(!result.acceptedActive)
        assertTrue(item.state is NarDownloadState.NeedsAttention)
        assertNull(item.workManagerId)
        val operation = exactOperationStore.read().single()
        assertEquals(item.attemptId, operation.attemptId.value)
        assertEquals(OperationKind.LOCAL_NAR, operation.kind)
        assertEquals(
            ExternalJobBinding.WorkManager(workId(item.id, item.attemptId, OperationKind.LOCAL_NAR)),
            operation.externalJob,
        )
        assertEquals(OperationStatus.FAILED, operation.status)
        assertTrue(work.stageEnqueuedIds.isEmpty())
    }

    @Test fun installBindingStoreWriteFailureTerminalizesExactBoundAttempt() {
        val queueStore = NarDownloadStore(FailSelectedWriteStorage(failOnWrite = 2))
        val exactOperationStore = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val exactSupervisor = DurableOperationSupervisor(
            exactOperationStore,
            MonotonicClock { 0L },
            cancellations,
        )
        val exactRepository = repositoryWith(queueStore, exactSupervisor, "install-write-failure")

        val item = exactRepository.enqueueLocal("file:///owned/archive.nar")

        assertTrue(item.state is NarDownloadState.NeedsAttention)
        assertNull(item.workManagerId)
        val operation = exactOperationStore.read().single()
        assertEquals(item.attemptId, operation.attemptId.value)
        assertEquals(OperationKind.NAR_INSTALL, operation.kind)
        assertEquals(
            ExternalJobBinding.WorkManager(workId(item.id, item.attemptId, OperationKind.NAR_INSTALL)),
            operation.externalJob,
        )
        assertEquals(OperationStatus.FAILED, operation.status)
        assertTrue(work.installEnqueuedIds.isEmpty())
    }

    @Test fun reconciliationBindsMissingInstallWorkerToExactActiveAttemptOnce() {
        val item = store.create(
            NarDownload(
                id = "unbound-install",
                source = NarDownloadSource.Local("file:///owned/archive.nar"),
                retainedUri = "file:///owned/archive.nar",
                state = NarDownloadState.Queued,
            ),
        )
        assertTrue(
            supervisor.start(
                item.handle(),
                OperationKind.NAR_INSTALL,
                "Installing archive",
                0L,
            ),
        )
        val recreated = recreatedRepository()

        recreated.reconcile()
        val recovered = store.get(item.id)!!
        recreated.reconcile()

        assertEquals(item.attemptId, recovered.attemptId)
        assertEquals(workId(item.id, item.attemptId, OperationKind.NAR_INSTALL), recovered.workManagerId)
        assertEquals(listOf(recovered.workManagerId), work.installEnqueuedIds)
        val operation = operationStore.read().single()
        assertEquals(item.attemptId, operation.attemptId.value)
        assertEquals(
            ExternalJobBinding.WorkManager(recovered.workManagerId!!),
            operation.externalJob,
        )

        assertTrue(recreated.stop(item.id))
        recreated.workerStopped(item.id, item.attemptId, recovered.workManagerId!!)
        val retry = recreated.retry(item.id)!!
        recreated.install(
            recovered.id,
            recovered.attemptId,
            recovered.workManagerId!!,
        ) { false }
        assertEquals(retry, store.get(item.id))
    }

    @Test fun missingInstallWorkerSchedulerFailureTerminalizesAttemptAndBecomesActionable() {
        val item = store.create(
            NarDownload(
                id = "unbound-install-failure",
                source = NarDownloadSource.Local("file:///owned/archive.nar"),
                retainedUri = "file:///owned/archive.nar",
                state = NarDownloadState.Queued,
            ),
        )
        assertTrue(
            supervisor.start(
                item.handle(),
                OperationKind.NAR_INSTALL,
                "Installing archive",
                0L,
            ),
        )
        work.installEnqueueFailure = IllegalStateException("WorkManager unavailable")

        recreatedRepository().reconcile()

        assertTrue(store.get(item.id)!!.state is NarDownloadState.NeedsAttention)
        val operation = operationStore.read().single()
        assertEquals(OperationStatus.FAILED, operation.status)
        assertEquals(item.attemptId, operation.attemptId.value)
        assertTrue(work.installEnqueuedIds.isEmpty())
    }

    @Test fun reconciliationRepairsInstallQueueAfterExactSupervisorFailureWasAlreadyPersisted() {
        val item = repository.enqueueLocal("file:///owned/install-failure-replay.nar")
        work.installRecovery = NarInstallWorkRecovery.FAILED
        assertTrue(
            supervisor.finish(
                item.handle(),
                ExternalJobBinding.WorkManager(item.workManagerId!!),
                OperationStatus.FAILED,
                "Nanidroid could not schedule this archive install. Retry it.",
            ),
        )

        recreatedRepository().reconcile()

        val recovered = store.get(item.id)!!
        assertEquals(item.attemptId, recovered.attemptId)
        assertTrue(recovered.state is NarDownloadState.NeedsAttention)
        val failed = operationStore.read().single()
        assertEquals(OperationKind.NAR_INSTALL, failed.kind)
        assertEquals(OperationStatus.FAILED, failed.status)
    }

    @Test fun terminalInstallQueryCannotOverwriteConcurrentInstallCompletion() {
        val item = repository.enqueueLocal("file:///owned/concurrent-complete.nar")
        installer.onInstall = { _, _, _, _ -> ArchiveInstallResult.Installed("installed") }
        work.installRecovery = NarInstallWorkRecovery.FAILED
        val queryStarted = CountDownLatch(1)
        val allowQueryToFinish = CountDownLatch(1)
        work.installQueryStarted = queryStarted
        work.allowInstallQuery = allowQueryToFinish
        val reconciliation = startReconciliation(recreatedRepository())
        assertTrue(queryStarted.await(5, TimeUnit.SECONDS))

        repository.install(item.id, item.attemptId, item.workManagerId!!) { false }
        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
        allowQueryToFinish.countDown()
        finishReconciliation(reconciliation)

        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
        assertEquals(OperationStatus.COMPLETED, operationStore.read().single().status)
        assertEquals(listOf(item.id), ownedData.deletedItemIds)
    }

    @Test fun terminalInstallQueryCannotOverwriteConcurrentRetryAttempt() {
        val item = repository.enqueueLocal("file:///owned/concurrent-retry.nar")
        work.installRecovery = NarInstallWorkRecovery.FAILED
        val queryStarted = CountDownLatch(1)
        val allowQueryToFinish = CountDownLatch(1)
        work.installQueryStarted = queryStarted
        work.allowInstallQuery = allowQueryToFinish
        val reconciliation = startReconciliation(recreatedRepository())
        assertTrue(queryStarted.await(5, TimeUnit.SECONDS))

        assertTrue(repository.stop(item.id))
        val stopping = repository.retry(item.id)!!
        assertEquals(item.attemptId, stopping.attemptId)
        assertEquals(OperationStatus.CANCEL_REQUESTED, operationStore.read().single().status)
        allowQueryToFinish.countDown()
        finishReconciliation(reconciliation)

        val retry = repository.retry(item.id)!!
        assertEquals(retry, store.get(item.id))
        assertEquals(item.attemptId + 1L, retry.attemptId)
        assertEquals(workId(item.id, retry.attemptId, OperationKind.NAR_INSTALL), retry.workManagerId)
    }

    @Test fun installQueryExceptionCannotOverwriteConcurrentInstallCompletion() {
        val item = repository.enqueueLocal("file:///owned/concurrent-query-failure.nar")
        installer.onInstall = { _, _, _, _ -> ArchiveInstallResult.Installed("installed") }
        work.installQueryFailure = IllegalStateException("WorkManager unavailable")
        val queryStarted = CountDownLatch(1)
        val allowQueryToFinish = CountDownLatch(1)
        work.installQueryStarted = queryStarted
        work.allowInstallQuery = allowQueryToFinish
        val reconciliation = startReconciliation(recreatedRepository())
        assertTrue(queryStarted.await(5, TimeUnit.SECONDS))

        repository.install(item.id, item.attemptId, item.workManagerId!!) { false }
        allowQueryToFinish.countDown()
        finishReconciliation(reconciliation)

        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
        assertEquals(OperationStatus.COMPLETED, operationStore.read().single().status)
        assertEquals(listOf(item.id), ownedData.deletedItemIds)
    }

    @Test fun reconciliationRecreatesStageWorkMissingAfterPreparedUuidWasPersisted() {
        work.loseNextStageAfterPreparation = true
        val item = repository.enqueueLocalCopy("content://provider/archive.nar")
        val preparedWorkId = store.get(item.id)!!.workManagerId!!
        assertTrue(preparedWorkId !in work.stageEnqueuedIds)

        recreatedRepository().reconcile()

        assertEquals(setOf(preparedWorkId), work.stageEnqueuedIds)
        assertEquals(NarDownloadState.Copying, store.get(item.id)!!.state)
    }

    @Test fun reconciliationMakesSucceededStageWorkActionable() {
        assertTerminalStageWorkBecomesActionable(FakeStageWorkState.SUCCEEDED)
    }

    @Test fun reconciliationMakesFailedStageWorkActionable() {
        assertTerminalStageWorkBecomesActionable(FakeStageWorkState.FAILED)
    }

    @Test fun reconciliationMakesCancelledStageWorkActionable() {
        assertTerminalStageWorkBecomesActionable(FakeStageWorkState.CANCELLED)
    }

    @Test fun reconciliationRepairsStageQueueAfterExactSupervisorFailureWasAlreadyPersisted() {
        val item = repository.enqueueLocalCopy("content://provider/stage-failure-replay.nar")
        work.stageWorkStates[item.workManagerId!!] = FakeStageWorkState.FAILED
        assertTrue(
            supervisor.finish(
                item.handle(),
                ExternalJobBinding.WorkManager(item.workManagerId!!),
                OperationStatus.FAILED,
                "The archive copy was interrupted. Select the archive again to continue.",
            ),
        )

        recreatedRepository().reconcile()

        val recovered = store.get(item.id)!!
        assertEquals(item.attemptId, recovered.attemptId)
        assertTrue(recovered.state is NarDownloadState.NeedsAttention)
        val failed = operationStore.read().single()
        assertEquals(OperationKind.LOCAL_NAR, failed.kind)
        assertEquals(OperationStatus.FAILED, failed.status)
    }

    @Test fun reconciliationPreservesNonterminalStageWorkWithoutDuplicateEnqueue() {
        val items = listOf(
            FakeStageWorkState.ENQUEUED,
            FakeStageWorkState.RUNNING,
            FakeStageWorkState.BLOCKED,
        ).mapIndexed { index, state ->
            repository.enqueueLocalCopy("content://provider/nonterminal-$index.nar").also { item ->
                work.stageWorkStates[item.workManagerId!!] = state
            }
        }
        val before = items.map { store.get(it.id)!! }

        recreatedRepository().reconcile()

        assertEquals(before, items.map { store.get(it.id)!! })
        assertTrue(work.stageRecreatedIds.isEmpty())
    }

    @Test fun reconciliationMakesStageWorkQueryFailureActionable() {
        val item = repository.enqueueLocalCopy("content://provider/archive.nar")
        work.stageQueryFailure = IllegalStateException("WorkManager unavailable")

        recreatedRepository().reconcile()

        assertTrue(store.get(item.id)!!.state is NarDownloadState.NeedsAttention)
        assertTrue(work.stageRecreatedIds.isEmpty())
    }

    @Test fun terminalStageQueryCannotOverwriteConcurrentStageHandoff() {
        val item = repository.enqueueLocalCopy("content://provider/concurrent-stage.nar")
        work.stageWorkStates[item.workManagerId!!] = FakeStageWorkState.SUCCEEDED
        val queryStarted = CountDownLatch(1)
        val allowQueryToFinish = CountDownLatch(1)
        work.stageQueryStarted = queryStarted
        work.allowStageQuery = allowQueryToFinish
        val reconciliation = startReconciliation(recreatedRepository())
        assertTrue(queryStarted.await(5, TimeUnit.SECONDS))

        repository.stageLocal(item.id, item.attemptId, item.workManagerId!!, { false }) { _, _, _ ->
            NarLocalArchiveStager.Result.Staged("file:///owned/concurrent-stage.nar")
        }
        val installAttempt = store.get(item.id)!!
        assertEquals(NarDownloadState.Queued, installAttempt.state)
        allowQueryToFinish.countDown()
        finishReconciliation(reconciliation)

        assertEquals(installAttempt, store.get(item.id))
        assertEquals(item.attemptId + 1L, installAttempt.attemptId)
        assertEquals(workId(item.id, installAttempt.attemptId, OperationKind.NAR_INSTALL), installAttempt.workManagerId)
        assertEquals(listOf(installAttempt.workManagerId), work.installEnqueuedIds)
        var staleCallbackRan = false
        repository.stageLocal(item.id, item.attemptId, item.workManagerId!!, { false }) { _, _, _ ->
            staleCallbackRan = true
            NarLocalArchiveStager.Result.Staged("file:///owned/stale-stage.nar")
        }
        assertTrue(!staleCallbackRan)
    }

    @Test fun stageQueryExceptionCannotOverwriteConcurrentStageHandoff() {
        val item = repository.enqueueLocalCopy("content://provider/concurrent-stage-query-failure.nar")
        work.stageQueryFailure = IllegalStateException("WorkManager unavailable")
        val queryStarted = CountDownLatch(1)
        val allowQueryToFinish = CountDownLatch(1)
        work.stageQueryStarted = queryStarted
        work.allowStageQuery = allowQueryToFinish
        val reconciliation = startReconciliation(recreatedRepository())
        assertTrue(queryStarted.await(5, TimeUnit.SECONDS))

        repository.stageLocal(item.id, item.attemptId, item.workManagerId!!, { false }) { _, _, _ ->
            NarLocalArchiveStager.Result.Staged("file:///owned/concurrent-stage-query-failure.nar")
        }
        val installAttempt = store.get(item.id)!!
        allowQueryToFinish.countDown()
        finishReconciliation(reconciliation)

        assertEquals(installAttempt, store.get(item.id))
        assertEquals(NarDownloadState.Queued, installAttempt.state)
        assertEquals(item.attemptId + 1L, installAttempt.attemptId)
        assertEquals(listOf(installAttempt.workManagerId), work.installEnqueuedIds)
    }

    @Test fun temporaryReplacementUsesSupervisedCancellableCopyAttempt() {
        val item = repository.enqueueLocal("content://provider/unavailable.nar")
        installer.failure = SecurityException("grant revoked")
        repository.install(item.id, item.attemptId, item.workManagerId!!) { false }
        installer.failure = null

        val replacement = repository.replaceLocalSourceForCopy(
            item.id,
            "content://provider/temporary.nar",
        )!!

        assertNotEquals(item.id, replacement.id)
        assertEquals(1L, replacement.attemptId)
        assertEquals(NarDownloadState.Copying, replacement.state)
        assertEquals(
            workId(replacement.id, replacement.attemptId, OperationKind.LOCAL_NAR),
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
                                    workId(operation.id.value, 1L, OperationKind.LOCAL_NAR),
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
        val workerAccepted = repository.stageLocal(
            item.id,
            item.attemptId,
            item.workManagerId!!,
            { false },
        ) { _, _, _ ->
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

    @Test fun deletingLiveGrantCopyCancelsItsActiveCopyPredicate() {
        val item = repository.enqueueLiveLocalCopy("content://provider/delete-live-copy.nar")
        val copyEntered = CountDownLatch(1)
        val allowCancellationCheck = CountDownLatch(1)
        val copyFinished = CountDownLatch(1)
        val sawCancellation = AtomicBoolean(false)

        Thread {
            repository.stageLiveLocal(
                item.id,
                item.attemptId,
                item.workManagerId!!,
                { false },
            ) { _, isCancelled, _ ->
                copyEntered.countDown()
                assertTrue(allowCancellationCheck.await(5, TimeUnit.SECONDS))
                sawCancellation.set(isCancelled())
                if (isCancelled()) NarLocalArchiveStager.Result.Cancelled
                else NarLocalArchiveStager.Result.Failed("copy was not cancelled")
            }
            copyFinished.countDown()
        }.start()

        assertTrue(copyEntered.await(5, TimeUnit.SECONDS))
        assertTrue(repository.delete(item.id))
        allowCancellationCheck.countDown()
        assertTrue(copyFinished.await(5, TimeUnit.SECONDS))

        assertTrue("deleting the queue item must cancel the live copy", sawCancellation.get())
        assertNull(store.get(item.id))
    }

    @Test fun deletingLiveGrantQueueItemStopsItsHandoffBeforeTheNextProviderRead() {
        val privateImports = File.createTempFile("nar-live-delete", "").also {
            assertTrue(it.delete())
            assertTrue(it.mkdir())
        }
        val firstReadEntered = CountDownLatch(1)
        val allowFirstRead = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val reads = AtomicInteger()
        val handoff = NarLiveGrantHandoff(
            repository = repository,
            executor = Executor { task -> Thread(task, "nar-live-delete").start() },
            stage = { source, isCancelled, onProgress ->
                NarLocalArchiveStager.stage(
                    directory = privateImports,
                    open = { source },
                    isCancelled = isCancelled,
                    onProgress = { completed -> onProgress("Copying archive", completed) },
                )
            },
        )
        val source = object : InputStream() {
            override fun read(): Int = error("stager uses buffered reads")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val read = reads.incrementAndGet()
                if (read == 1) {
                    firstReadEntered.countDown()
                    assertTrue(allowFirstRead.await(5, TimeUnit.SECONDS))
                    buffer[offset] = 1
                    return 1
                }
                return 1
            }

            override fun close() {
                closed.countDown()
            }
        }

        val item = handoff.enqueue("content://provider/delete-before-eof.nar", null) { source }!!
        assertTrue(firstReadEntered.await(5, TimeUnit.SECONDS))
        assertTrue(repository.delete(item.id))
        allowFirstRead.countDown()
        assertTrue(closed.await(5, TimeUnit.SECONDS))

        assertEquals("the provider must not be read through EOF after deletion", 1, reads.get())
        assertNull(store.get(item.id))
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

        val rejected = rejectingHandoff.enqueueForUser("content://provider/rejected.nar", null) {
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
        assertTrue(rejected?.acceptedActive == false)
        assertNull(missingReplacement)
        assertEquals(1, missingReplacementCloseCount.get())
    }

    @Test fun rejectedOneShotHandoffStaysFencedUntilExactWorkerStops() {
        val fencedRepository = repository
        val handoff = NarLiveGrantHandoff(
            repository = fencedRepository,
            executor = Executor { throw RejectedExecutionException("executor stopped") },
            stage = { _, _, _ -> NarLocalArchiveStager.Result.Cancelled },
        )
        val item = handoff.enqueue("content://provider/rejected-race.nar", null) {
            ByteArrayInputStream(byteArrayOf(1))
        }
        assertNotNull(item)
        val stopping = store.get(item!!.id)!!
        assertEquals(NarDownloadState.Copying, stopping.state)
        assertEquals(OperationStatus.CANCEL_REQUESTED, operationStore.read().single().status)
        assertEquals(stopping, fencedRepository.retry(item.id))
        var workerOpenedSource = false

        fencedRepository.stageLocal(
            item.id,
            item.attemptId,
            item.workManagerId!!,
            { false },
        ) { _, _, _ ->
            workerOpenedSource = true
            NarLocalArchiveStager.Result.Failed("duplicate opener")
        }

        assertTrue("worker opened the one-shot URI before exact stop confirmation", !workerOpenedSource)
        fencedRepository.workerStopped(item.id, item.attemptId, item.workManagerId!!)
        assertEquals(NarDownloadState.Cancelled, store.getAll().single().state)
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
        repository.install(
            completed.id,
            completed.attemptId,
            completed.workManagerId!!,
        ) { false }
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

        repository.install(item.id, item.attemptId, item.workManagerId!!) { false }

        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
    }

    @Test fun recreatedInstallRecoversTargetConflictWhenSupervisorAlreadyCompleted() {
        val item = repository.enqueueLocal("file:///owned/archive.nar", "file:///owned/archive.nar")
        store.update(item.id) { it.copy(state = NarDownloadState.Installing) }
        assertTrue(
            supervisor.finish(
                item.handle(),
                ExternalJobBinding.WorkManager(item.workManagerId!!),
                OperationStatus.COMPLETED,
            ),
        )
        installer.result = ArchiveInstallResult.Failed(
            "target exists",
            ArchiveInstallFailure.TargetExists,
        )
        val recreated = recreatedRepository()

        recreated.install(item.id, item.attemptId, item.workManagerId!!) { false }

        assertEquals(NarDownloadState.Complete, store.get(item.id)!!.state)
        val nextAttempt = recreated.retry(item.id)!!
        recreated.install(item.id, item.attemptId, item.workManagerId!!) { false }
        assertEquals(nextAttempt, store.get(item.id))
    }

    @Test fun freshInstallTargetConflictStillNeedsAttention() {
        val item = repository.enqueueLocal("file:///owned/archive.nar", "file:///owned/archive.nar")
        installer.result = ArchiveInstallResult.Failed(
            "target exists",
            ArchiveInstallFailure.TargetExists,
        )

        repository.install(item.id, item.attemptId, item.workManagerId!!) { false }

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

        InstallNarWorker.execute(
            repository,
            item.id,
            item.attemptId,
            item.workManagerId!!,
        ) { false }

        val state = store.get(item.id)!!.state as NarDownloadState.NeedsAttention
        assertTrue(state.failure.message.contains("select", ignoreCase = true))
    }

    @Test fun missingProviderBecomesReselectableNeedsAttention() {
        val item = repository.enqueueLocal("content://provider/archive.nar")
        installer.failure = FileNotFoundException("provider missing")

        InstallNarWorker.execute(
            repository,
            item.id,
            item.attemptId,
            item.workManagerId!!,
        ) { false }

        val state = store.get(item.id)!!.state as NarDownloadState.NeedsAttention
        assertTrue(state.failure.message.contains("select", ignoreCase = true))
    }

    @Test fun replacingUnavailableLocalSourceKeepsRecordAndSchedulesInstall() {
        val item = repository.enqueueLocal("content://provider/unavailable.nar")
        installer.failure = SecurityException("grant revoked")
        InstallNarWorker.execute(
            repository,
            item.id,
            item.attemptId,
            item.workManagerId!!,
        ) { false }

        val result = repository.replaceLocalSourceForUser(
            item.id,
            "file:///owned/reselected.nar",
        )!!
        assertTrue(result.acceptedActive)

        val replaced = result.download
        assertEquals(NarDownloadSource.Local("file:///owned/reselected.nar"), replaced.source)
        assertEquals(NarDownloadState.Queued, replaced.state)
        assertEquals("file:///owned/reselected.nar", replaced.retainedUri)
        assertEquals(listOf("install-nar-${item.id}", "install-nar-${item.id}"), work.enqueuedNames)
    }

    @Test fun reselectStartsANewAttemptAndFencesTheUnavailableWorker() {
        val item = repository.enqueueLocal("content://provider/unavailable.nar")
        installer.failure = SecurityException("grant revoked")
        repository.install(item.id, item.attemptId, item.workManagerId!!) { false }
        installer.failure = null

        val replaced = repository.replaceLocalSource(
            item.id,
            "content://provider/reselected.nar",
        )!!

        assertEquals(item.attemptId + 1L, replaced.attemptId)
        repository.install(item.id, item.attemptId, item.workManagerId!!) { false }
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

    @Test fun failedRemoteRowIsTerminalizedBeforeRetryStartsNewDownloadAttempt() {
        downloads.nextDownloadId = 76L
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")
        downloads.statuses[76L] = NarRemoteDownloadStatus.Failed

        repository.reconcile()

        assertTrue(store.get(item.id)!!.state is NarDownloadState.NeedsAttention)
        assertEquals(OperationStatus.FAILED, operationStore.read().single().status)
        assertEquals(OperationKind.REMOTE_NAR, operationStore.read().single().kind)

        downloads.nextDownloadId = 77L
        val retry = repository.retry(item.id)!!

        assertEquals(item.attemptId + 1L, retry.attemptId)
        assertEquals(NarDownloadState.Downloading, retry.state)
        assertEquals(77L, retry.downloadManagerId)
        val retryOperation = operationStore.read().single()
        assertEquals(retry.attemptId, retryOperation.attemptId.value)
        assertEquals(OperationKind.REMOTE_NAR, retryOperation.kind)
        assertEquals(OperationStatus.RUNNING, retryOperation.status)

        repository.onDownloadComplete(76L)
        assertEquals(retry, store.get(item.id))
    }

    @Test fun installEnqueueFailureBeforeBindingCanRetryByReacquiringRemoteArchive() {
        downloads.nextDownloadId = 90L
        val downloadAttempt = repository.enqueueRemote("https://example.invalid/archive.nar")
        work.installEnqueueFailure = IllegalStateException("WorkManager unavailable")

        repository.onDownloadComplete(90L)

        val failedInstall = store.get(downloadAttempt.id)!!
        assertEquals(downloadAttempt.attemptId + 1L, failedInstall.attemptId)
        assertNull(failedInstall.workManagerId)
        assertTrue(failedInstall.state is NarDownloadState.NeedsAttention)
        val failedOperation = operationStore.read().single()
        assertEquals(OperationKind.NAR_INSTALL, failedOperation.kind)
        assertEquals(OperationStatus.FAILED, failedOperation.status)
        assertEquals(failedInstall.attemptId, failedOperation.attemptId.value)

        work.installEnqueueFailure = null
        ownedData.isRetainedArchiveAvailable = false
        downloads.nextDownloadId = 91L
        val retry = repository.retry(downloadAttempt.id)!!

        assertEquals(failedInstall.attemptId + 1L, retry.attemptId)
        assertEquals(NarDownloadState.Downloading, retry.state)
        assertEquals(91L, retry.downloadManagerId)
        val retryOperation = operationStore.read().single()
        assertEquals(retry.attemptId, retryOperation.attemptId.value)
        assertEquals(OperationKind.REMOTE_NAR, retryOperation.kind)
        assertEquals(OperationStatus.RUNNING, retryOperation.status)
    }

    @Test fun remoteEnqueueFailureBeforeRowBindingPersistsFailedAttempt() {
        downloads.enqueueFailure = IllegalStateException("DownloadManager unavailable")

        val item = repository.enqueueRemote("https://example.invalid/archive.nar")

        assertNull(item.downloadManagerId)
        assertTrue(store.get(item.id)!!.state is NarDownloadState.NeedsAttention)
        val failed = operationStore.read().single()
        assertEquals(item.id, failed.id.value)
        assertEquals(item.attemptId, failed.attemptId.value)
        assertEquals(OperationKind.REMOTE_NAR, failed.kind)
        assertEquals(OperationStatus.FAILED, failed.status)
        assertNull(failed.externalJob)

        downloads.enqueueFailure = null
        downloads.nextDownloadId = 91L
        val retry = recreatedRepository().retry(item.id)!!

        assertEquals(item.attemptId + 1L, retry.attemptId)
        assertEquals(91L, retry.downloadManagerId)
        val retriedOperation = operationStore.read().single()
        assertEquals(retry.attemptId, retriedOperation.attemptId.value)
        assertEquals(OperationStatus.RUNNING, retriedOperation.status)
        assertEquals(ExternalJobBinding.DownloadManager(91L), retriedOperation.externalJob)
    }

    @Test fun stopDuringRemoteEnqueueCannotRebindCancelledAttempt() {
        downloads.nextDownloadId = 92L
        downloads.onEnqueue = { itemId -> assertTrue(repository.stop(itemId)) }

        val item = repository.enqueueRemote("https://example.invalid/archive.nar")

        assertTrue(store.get(item.id)!!.state is NarDownloadState.Cancelled)
        assertEquals(OperationStatus.CANCELLED, operationStore.read().single().status)
        assertEquals(listOf(92L), downloads.removedIds)
    }

    @Test fun recreationBindsRecoveredRemoteRowAfterEnqueueBeforeBinding() {
        val accepted = store.create(
            NarDownload(
                id = "unbound-remote",
                source = NarDownloadSource.Remote("https://example.invalid/archive.nar"),
                retainedUri = "file:///owned/unbound-remote.nar",
                state = NarDownloadState.Downloading,
            ),
        )
        assertTrue(
            supervisor.start(
                accepted.handle(),
                OperationKind.REMOTE_NAR,
                "Downloading archive",
                0L,
            ),
        )
        downloads.recoveredIds[accepted.retainedUri!!] = 93L
        downloads.statuses[93L] = NarRemoteDownloadStatus.InProgress

        recreatedRepository().reconcile()

        assertEquals(93L, store.get(accepted.id)!!.downloadManagerId)
        assertEquals(
            ExternalJobBinding.DownloadManager(93L),
            operationStore.read().single().externalJob,
        )
    }

    @Test fun recreationKeepsUnboundRemoteAttemptRunningWhenRowRecoveryQueryFails() {
        val accepted = store.create(
            NarDownload(
                id = "unbound-remote-query-failure",
                source = NarDownloadSource.Remote("https://example.invalid/archive.nar"),
                retainedUri = "file:///owned/unbound-remote-query-failure.nar",
                state = NarDownloadState.Downloading,
            ),
        )
        assertTrue(
            supervisor.start(
                accepted.handle(),
                OperationKind.REMOTE_NAR,
                "Downloading archive",
                0L,
            ),
        )
        downloads.findDownloadIdFailure = IllegalStateException("DownloadManager unavailable")

        recreatedRepository().reconcile()

        assertEquals(NarDownloadState.Downloading, store.get(accepted.id)!!.state)
        val operation = operationStore.read().single()
        assertEquals(OperationStatus.RUNNING, operation.status)
        assertNull(operation.externalJob)
    }

    @Test fun recreationWithoutRemoteRowTerminalizesUnboundAttemptBeforeRetry() {
        val accepted = store.create(
            NarDownload(
                id = "unbound-remote-without-row",
                source = NarDownloadSource.Remote("https://example.invalid/archive.nar"),
                retainedUri = "file:///owned/unbound-remote-without-row.nar",
                state = NarDownloadState.Downloading,
            ),
        )
        assertTrue(
            supervisor.start(
                accepted.handle(),
                OperationKind.REMOTE_NAR,
                "Downloading archive",
                0L,
            ),
        )

        val recreated = recreatedRepository()
        recreated.reconcile()

        assertTrue(store.get(accepted.id)!!.state is NarDownloadState.NeedsAttention)
        assertEquals(OperationStatus.FAILED, operationStore.read().single().status)

        downloads.nextDownloadId = 94L
        val retry = recreated.retry(accepted.id)!!

        assertEquals(accepted.attemptId + 1L, retry.attemptId)
        assertEquals(94L, retry.downloadManagerId)
        assertEquals(OperationStatus.RUNNING, operationStore.read().single().status)
    }

    @Test fun recreationWithoutRemoteRowMakesAttemptActionableWhenSupervisorStartWasNotPersisted() {
        val accepted = store.create(
            NarDownload(
                id = "remote-without-supervisor",
                source = NarDownloadSource.Remote("https://example.invalid/archive.nar"),
                retainedUri = "file:///owned/remote-without-supervisor.nar",
                state = NarDownloadState.Downloading,
            ),
        )

        val recreated = recreatedRepository()
        recreated.reconcile()

        assertTrue(store.get(accepted.id)!!.state is NarDownloadState.NeedsAttention)
        assertTrue(operationStore.read().isEmpty())

        downloads.nextDownloadId = 95L
        val retry = recreated.retry(accepted.id)!!

        assertEquals(accepted.attemptId + 1L, retry.attemptId)
        assertEquals(95L, retry.downloadManagerId)
        assertEquals(OperationStatus.RUNNING, operationStore.read().single().status)
    }

    @Test fun recreationWithoutRemoteRowMakesAlreadyFailedAttemptActionable() {
        val accepted = store.create(
            NarDownload(
                id = "remote-failed-before-attention",
                source = NarDownloadSource.Remote("https://example.invalid/archive.nar"),
                retainedUri = "file:///owned/remote-failed-before-attention.nar",
                state = NarDownloadState.Downloading,
            ),
        )
        assertTrue(
            supervisor.start(
                accepted.handle(),
                OperationKind.REMOTE_NAR,
                "Downloading archive",
                0L,
            ),
        )
        assertTrue(supervisor.failUnboundAttempt(accepted.handle(), "prior failure"))

        recreatedRepository().reconcile()

        assertTrue(store.get(accepted.id)!!.state is NarDownloadState.NeedsAttention)
        assertEquals(OperationStatus.FAILED, operationStore.read().single().status)
    }

    @Test fun recreationPersistsMissingRemoteReacquisitionBeforeRetryingFailedInstall() {
        val accepted = store.create(
            NarDownload(
                id = "remote-reacquisition-without-supervisor",
                source = NarDownloadSource.Remote("https://example.invalid/archive.nar"),
                attemptId = 2L,
                retainedUri = "file:///owned/remote-reacquisition-without-supervisor.nar",
                state = NarDownloadState.Downloading,
            ),
        )
        val previousHandle = OperationHandle(OperationId(accepted.id), AttemptId(1L))
        assertTrue(
            supervisor.start(
                previousHandle,
                OperationKind.NAR_INSTALL,
                "Installing archive",
                0L,
            ),
        )
        assertTrue(supervisor.failUnboundAttempt(previousHandle, "install failed"))

        val recreated = recreatedRepository()
        recreated.reconcile()

        val recoveredAttempt = operationStore.read().single()
        assertEquals(OperationKind.REMOTE_NAR, recoveredAttempt.kind)
        assertEquals(accepted.attemptId, recoveredAttempt.attemptId.value)
        assertEquals(OperationStatus.FAILED, recoveredAttempt.status)
        assertTrue(store.get(accepted.id)!!.state is NarDownloadState.NeedsAttention)

        downloads.nextDownloadId = 96L
        val retry = recreated.retry(accepted.id)!!

        assertEquals(accepted.attemptId + 1L, retry.attemptId)
        assertEquals(96L, retry.downloadManagerId)
        assertEquals(OperationStatus.RUNNING, operationStore.read().single().status)
    }

    @Test fun recreationRepairsItemAfterUnboundCancellationWasAlreadyPersisted() {
        val accepted = store.create(
            NarDownload(
                id = "remote-cancelled-before-item-update",
                source = NarDownloadSource.Remote("https://example.invalid/archive.nar"),
                retainedUri = "file:///owned/remote-cancelled-before-item-update.nar",
                state = NarDownloadState.Downloading,
            ),
        )
        assertTrue(
            supervisor.start(
                accepted.handle(),
                OperationKind.REMOTE_NAR,
                "Downloading archive",
                0L,
            ),
        )
        assertTrue(supervisor.requestStop(accepted.handle()))
        assertTrue(supervisor.reconcileUnboundCancellation(accepted.handle()))

        recreatedRepository().reconcile()

        assertEquals(NarDownloadState.Cancelled, store.get(accepted.id)!!.state)
        assertEquals(OperationStatus.CANCELLED, operationStore.read().single().status)
    }

    @Test fun missingRemoteRowIsTerminalizedBeforeRetryStartsNewDownloadAttempt() {
        downloads.nextDownloadId = 78L
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")

        repository.reconcile()

        assertTrue(store.get(item.id)!!.state is NarDownloadState.NeedsAttention)
        assertEquals(OperationStatus.FAILED, operationStore.read().single().status)

        downloads.nextDownloadId = 79L
        val retry = repository.retry(item.id)!!

        assertEquals(item.attemptId + 1L, retry.attemptId)
        assertEquals(NarDownloadState.Downloading, retry.state)
        assertEquals(79L, retry.downloadManagerId)
        val retryOperation = operationStore.read().single()
        assertEquals(retry.attemptId, retryOperation.attemptId.value)
        assertEquals(OperationStatus.RUNNING, retryOperation.status)

        repository.onDownloadComplete(78L)
        assertEquals(retry, store.get(item.id))
    }

    @Test fun recreatedReconciliationCommitsDownloadHandoffWhenSupervisorAlreadyCompleted() {
        downloads.nextDownloadId = 75L
        val item = repository.enqueueRemote("https://example.invalid/archive.nar")
        assertTrue(
            supervisor.finish(
                item.handle(),
                ExternalJobBinding.DownloadManager(item.downloadManagerId!!),
                OperationStatus.COMPLETED,
            ),
        )
        downloads.statuses[75L] = NarRemoteDownloadStatus.Successful(
            "file:///owned/${item.id}.nar",
        )
        val recreated = recreatedRepository()

        recreated.reconcile()

        val installAttempt = store.get(item.id)!!
        assertEquals(item.attemptId + 1L, installAttempt.attemptId)
        assertEquals(NarDownloadState.Queued, installAttempt.state)
        assertEquals("file:///owned/${item.id}.nar", installAttempt.retainedUri)
        assertEquals(workId(item.id, installAttempt.attemptId, OperationKind.NAR_INSTALL), installAttempt.workManagerId)
        recreated.onDownloadComplete(75L)
        assertEquals(installAttempt, store.get(item.id))
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

    @Test fun reconciliationRebindsRecoveredDownloadBeforeProgressAndStop() {
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
        downloads.downloadedBytes[74L] = 12L
        val recreated = recreatedRepository()

        recreated.reconcile()

        val recoveredOperation = operationStore.read().single()
        assertEquals(item.handle().operationId, recoveredOperation.id)
        assertEquals(item.handle().attemptId, recoveredOperation.attemptId)
        assertEquals(OperationKind.REMOTE_NAR, recoveredOperation.kind)
        assertEquals(OperationStatus.RUNNING, recoveredOperation.status)
        assertEquals(ExternalJobBinding.DownloadManager(74L), recoveredOperation.externalJob)
        assertTrue(recreated.observeRemoteProgress(item.id))
        assertEquals(12L, operationStore.read().single().progress.completed)
        assertTrue(recreated.stop(item.id))
        assertEquals(listOf(74L), downloads.removedIds)
    }

    @Test fun reconciliationReacquiresRetriedDownloadAfterCrashBeforeStart() {
        downloads.nextDownloadId = 95L
        val initialDownload = repository.enqueueRemote("https://example.invalid/archive.nar")
        repository.onDownloadComplete(95L)
        val failedInstall = store.get(initialDownload.id)!!
        installer.failure = FileNotFoundException("download vanished")
        repository.install(
            failedInstall.id,
            failedInstall.attemptId,
            failedInstall.workManagerId!!,
        ) { false }
        assertEquals(OperationKind.NAR_INSTALL, operationStore.read().single().kind)
        assertEquals(OperationStatus.FAILED, operationStore.read().single().status)

        val retriedAttempt = store.update(initialDownload.id) {
            it.copy(
                attemptId = it.attemptId + 1L,
                retainedUri = "file:///owned/${it.id}.nar",
                downloadManagerId = null,
                workManagerId = null,
                state = NarDownloadState.Downloading,
            )
        }!!
        downloads.recoveredIds[retriedAttempt.retainedUri!!] = 96L
        downloads.statuses[96L] = NarRemoteDownloadStatus.InProgress

        recreatedRepository().reconcile()

        assertEquals(NarDownloadState.Downloading, store.get(retriedAttempt.id)!!.state)
        val recoveredOperation = operationStore.read().single()
        assertEquals(retriedAttempt.attemptId, recoveredOperation.attemptId.value)
        assertEquals(OperationKind.REMOTE_NAR, recoveredOperation.kind)
        assertEquals(OperationStatus.RUNNING, recoveredOperation.status)
        assertEquals(ExternalJobBinding.DownloadManager(96L), recoveredOperation.externalJob)
    }

    @Test fun reconciliationSchedulesQueuedLocalArchive() {
        val item = repository.enqueueLocal("file:///owned/archive.nar")
        work.enqueuedNames.clear()

        repository.reconcile()

        assertTrue(work.enqueuedNames.isEmpty())
        assertEquals(1, work.installEnqueuedIds.size)
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
        repository.install(oldItem.id, oldItem.attemptId, oldItem.workManagerId!!) { false }
        val oldAttempt = installer.stagingDirectories.single()
        repository.delete(oldItem.id)

        val newItem = repository.enqueueLocal("content://provider/archive.nar")
        repository.install(newItem.id, newItem.attemptId, newItem.workManagerId!!) { false }

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
        val installThread = Thread {
            cancellableRepository.install(
                item.id,
                item.attemptId,
                item.workManagerId!!,
            ) { false }
        }
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
        var enqueueFailure: Exception? = null
        var intendedDestinationFailure: Exception? = null
        var findDownloadIdFailure: Exception? = null
        val statuses = mutableMapOf<Long, NarRemoteDownloadStatus?>()
        val recoveredIds = mutableMapOf<String, Long>()
        val removedIds = mutableListOf<Long>()
        val downloadedBytes = mutableMapOf<Long, Long>()
        var onDownloadedBytes: (() -> Unit)? = null

        override fun intendedRetainedUri(itemId: String): String {
            intendedDestinationFailure?.let { throw it }
            return "file:///owned/$itemId.nar"
        }

        override fun enqueue(itemId: String, normalizedHttpsUrl: String): NarRemoteEnqueue {
            onEnqueue?.invoke(itemId)
            enqueueFailure?.let { throw it }
            return NarRemoteEnqueue(nextDownloadId, "file:///owned/$itemId.nar")
        }

        override fun findDownloadId(retainedUri: String): Long? {
            findDownloadIdFailure?.let { throw it }
            return recoveredIds[retainedUri]
        }

        override fun remove(downloadManagerId: Long) {
            removedIds += downloadManagerId
        }

        override fun status(downloadManagerId: Long) = statuses[downloadManagerId]

        override fun downloadedBytes(downloadManagerId: Long): Long? {
            onDownloadedBytes?.invoke()
            return downloadedBytes[downloadManagerId]
        }
    }

    private class FakeWorkScheduler : NarInstallWorkScheduler {
        val enqueuedNames = mutableListOf<String>()
        val cancelledNames = mutableListOf<String>()
        val cancelledBindings = mutableListOf<String>()
        val stageEnqueuedIds = mutableSetOf<String>()
        val stageRecreatedIds = mutableListOf<String>()
        val stageWorkStates = mutableMapOf<String, FakeStageWorkState>()
        val installEnqueuedIds = mutableListOf<String>()
        var loseNextStageAfterPreparation = false
        var stageQueryFailure: Exception? = null
        var stageQueryStarted: CountDownLatch? = null
        var allowStageQuery: CountDownLatch? = null
        var installEnqueueFailure: Exception? = null
        var installQueryFailure: Exception? = null
        var installRecovery = NarInstallWorkRecovery.ACTIVE
        var installQueryStarted: CountDownLatch? = null
        var allowInstallQuery: CountDownLatch? = null
        var beforeNextInstallPrepared: ((itemId: String, attemptId: Long) -> Unit)? = null
        var beforeNextStagePrepared: ((itemId: String, attemptId: Long) -> Unit)? = null

        override fun enqueue(itemId: String) {
            enqueuedNames += NarDownloadRepository.workName(itemId)
        }

        override fun cancel(itemId: String) {
            cancelledNames += NarDownloadRepository.workName(itemId)
        }

        override fun enqueue(
            itemId: String,
            attemptId: Long,
            onPrepared: (workManagerId: String) -> Boolean,
        ): Boolean {
            installEnqueueFailure?.let { throw it }
            val workManagerId = workId(itemId, attemptId, OperationKind.NAR_INSTALL)
            val beforePrepared = beforeNextInstallPrepared
            beforeNextInstallPrepared = null
            beforePrepared?.invoke(itemId, attemptId)
            if (!onPrepared(workManagerId)) return false
            installEnqueuedIds += workManagerId
            enqueue(itemId)
            return true
        }

        override fun ensureInstallEnqueued(
            itemId: String,
            attemptId: Long,
            workManagerId: String,
            recreateIfMissing: Boolean,
        ): NarInstallWorkRecovery {
            installQueryStarted?.countDown()
            allowInstallQuery?.let { latch ->
                check(latch.await(5, TimeUnit.SECONDS)) { "install query was not released" }
            }
            installQueryFailure?.let { throw it }
            if (workManagerId !in installEnqueuedIds && recreateIfMissing) {
                installEnqueuedIds += workManagerId
                enqueue(itemId)
            }
            if (workManagerId !in installEnqueuedIds) return NarInstallWorkRecovery.MISSING
            return installRecovery
        }

        override fun enqueueStage(
            itemId: String,
            attemptId: Long,
            onPrepared: (workManagerId: String) -> Boolean,
        ): Boolean {
            val workManagerId = workId(itemId, attemptId, OperationKind.LOCAL_NAR)
            val beforePrepared = beforeNextStagePrepared
            beforeNextStagePrepared = null
            beforePrepared?.invoke(itemId, attemptId)
            if (!onPrepared(workManagerId)) return false
            if (loseNextStageAfterPreparation) {
                loseNextStageAfterPreparation = false
            } else {
                stageEnqueuedIds += workManagerId
                stageWorkStates[workManagerId] = FakeStageWorkState.ENQUEUED
            }
            return true
        }

        override fun ensureStageEnqueued(
            itemId: String,
            attemptId: Long,
            workManagerId: String,
            recreateIfMissing: Boolean,
        ): NarStageWorkRecovery {
            stageQueryStarted?.countDown()
            allowStageQuery?.let { latch ->
                check(latch.await(5, TimeUnit.SECONDS)) { "stage query was not released" }
            }
            stageQueryFailure?.let { throw it }
            if (workManagerId !in stageWorkStates && recreateIfMissing) {
                stageEnqueuedIds += workManagerId
                stageRecreatedIds += workManagerId
                stageWorkStates[workManagerId] = FakeStageWorkState.ENQUEUED
            }
            if (workManagerId !in stageWorkStates) return NarStageWorkRecovery.MISSING
            return when (stageWorkStates.getValue(workManagerId)) {
                FakeStageWorkState.SUCCEEDED -> NarStageWorkRecovery.SUCCEEDED
                FakeStageWorkState.FAILED -> NarStageWorkRecovery.FAILED
                FakeStageWorkState.CANCELLED -> NarStageWorkRecovery.CANCELLED
                FakeStageWorkState.ENQUEUED,
                FakeStageWorkState.RUNNING,
                FakeStageWorkState.BLOCKED -> NarStageWorkRecovery.ACTIVE
            }
        }
    }

    private enum class FakeStageWorkState {
        ENQUEUED,
        RUNNING,
        BLOCKED,
        SUCCEEDED,
        FAILED,
        CANCELLED,
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
        var isRetainedArchiveAvailable = false

        override fun delete(download: NarDownload) {
            deletedItemIds += download.id
        }

        override fun retainedArchiveAvailable(download: NarDownload) = isRetainedArchiveAvailable

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

    private class MutableClock(var value: Long = 0L) : MonotonicClock {
        override fun nowMillis() = value
    }

    private class CountingOperationStore : DurableOperationStore {
        private var record: DurableOperationRecord? = null
        var progressUpdateCount = 0
            private set
        var onProgressUpdate: (() -> Unit)? = null

        override fun read() = listOfNotNull(record)

        override fun putIfAbsent(record: DurableOperationRecord): Boolean {
            if (this.record != null) return false
            this.record = record
            return true
        }

        override fun compareAndSet(
            expected: DurableOperationRecord,
            updated: DurableOperationRecord,
        ): Boolean {
            if (record != expected) return false
            record = updated
            if (expected.progress != updated.progress) {
                progressUpdateCount += 1
                onProgressUpdate?.invoke()
            }
            return true
        }
    }

    private fun recreatedRepository(): NarDownloadRepository {
        val recreatedSupervisor = DurableOperationSupervisor(
            operationStore,
            MonotonicClock { 0L },
            cancellations,
        )
        return NarDownloadRepository(
            store = store,
            downloads = downloads,
            work = work,
            installer = installer,
            ownedData = ownedData,
            attemptPaths = attempts,
            supervisor = recreatedSupervisor,
            remoteProgress = FakeRemoteProgressObserver(downloads, recreatedSupervisor),
            stopReconciliation = stopReconciliation,
            nextId = { ids.removeFirst() },
        )
    }

    private fun repositoryWith(
        queueStore: NarDownloadStore,
        exactSupervisor: DurableOperationSupervisor,
        itemId: String,
    ) = NarDownloadRepository(
        store = queueStore,
        downloads = downloads,
        work = work,
        installer = installer,
        ownedData = ownedData,
        attemptPaths = attempts,
        supervisor = exactSupervisor,
        remoteProgress = FakeRemoteProgressObserver(downloads, exactSupervisor),
        nextId = { itemId },
    )

    private class FailSelectedWriteStorage(
        private val failOnWrite: Int,
    ) : NarDownloadStore.Storage {
        private var value: String? = null
        private var writeCount = 0

        @Synchronized override fun read(): String? = value

        @Synchronized override fun write(value: String) {
            writeCount += 1
            if (writeCount == failOnWrite) throw IllegalStateException("queue write failed")
            this.value = value
        }
    }

    private fun startReconciliation(repository: NarDownloadRepository): BackgroundCall {
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                repository.reconcile()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        thread.start()
        return BackgroundCall(thread, failure)
    }

    private fun finishReconciliation(call: BackgroundCall) {
        call.thread.join(5_000)
        assertTrue(!call.thread.isAlive)
        call.failure.get()?.let { throw AssertionError("reconciliation failed", it) }
    }

    private data class BackgroundCall(
        val thread: Thread,
        val failure: AtomicReference<Throwable?>,
    )

    private fun assertTerminalStageWorkBecomesActionable(state: FakeStageWorkState) {
        val item = repository.enqueueLocalCopy("content://provider/terminal-$state.nar")
        work.stageWorkStates[item.workManagerId!!] = state
        val recreated = recreatedRepository()

        recreated.reconcile()

        val recovered = store.get(item.id)!!
        assertEquals(item.attemptId, recovered.attemptId)
        assertTrue(recovered.state is NarDownloadState.NeedsAttention)
        assertTrue(work.stageRecreatedIds.isEmpty())
        val failedCopy = operationStore.read().single()
        assertEquals(OperationKind.LOCAL_NAR, failedCopy.kind)
        assertEquals(OperationStatus.FAILED, failedCopy.status)

        val replacement = "file:///owned/reselected-$state.nar"
        val reselected = recreated.replaceLocalSource(item.id, replacement)!!
        assertEquals(item.attemptId + 1L, reselected.attemptId)
        assertEquals(NarDownloadState.Queued, reselected.state)
        assertEquals(replacement, reselected.retainedUri)
        assertEquals(workId(item.id, reselected.attemptId, OperationKind.NAR_INSTALL), reselected.workManagerId)
        val install = operationStore.read().single()
        assertEquals(OperationKind.NAR_INSTALL, install.kind)
        assertEquals(OperationStatus.RUNNING, install.status)
        assertEquals(reselected.attemptId, install.attemptId.value)

        var staleCallbackRan = false
        recreated.stageLocal(
            item.id,
            item.attemptId,
            item.workManagerId!!,
            { false },
        ) { _, _, _ ->
            staleCallbackRan = true
            NarLocalArchiveStager.Result.Staged("file:///owned/stale-$state.nar")
        }
        assertTrue(!staleCallbackRan)
        assertEquals(reselected, store.get(item.id))
    }

    private class RecordingOperationCancellation(
        private val downloads: FakeDownloadGateway,
        private val work: FakeWorkScheduler,
    ) : OperationCancellation {
        override fun cancel(handle: OperationHandle, kind: OperationKind, binding: ExternalJobBinding) {
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

        fun takeNext(): Runnable = pending.removeFirst()
    }

    private class FakeStopReconciliationScheduler : NarStopReconciliationScheduler {
        private val pending = mutableMapOf<OperationHandle, Runnable>()

        override fun schedule(handle: OperationHandle, delayMillis: Long, task: Runnable) {
            pending[handle] = task
        }

        override fun cancel(handle: OperationHandle) {
            pending.remove(handle)
        }

        fun hasPending(handle: OperationHandle) = handle in pending

        fun run(handle: OperationHandle) {
            pending.remove(handle)?.run()
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
