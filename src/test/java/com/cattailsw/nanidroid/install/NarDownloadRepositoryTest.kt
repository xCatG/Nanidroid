package com.cattailsw.nanidroid.install

import androidx.work.ListenableWorker
import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.durable.AttemptId
import com.cattailsw.nanidroid.durable.DurableOperationSupervisor
import com.cattailsw.nanidroid.durable.ExternalJobBinding
import com.cattailsw.nanidroid.durable.OperationCancellation
import com.cattailsw.nanidroid.durable.OperationHandle
import com.cattailsw.nanidroid.durable.OperationId
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    private val ids = ArrayDeque(listOf("old-item", "new-item", "third-item"))
    private val repository = NarDownloadRepository(
        store = store,
        downloads = downloads,
        work = work,
        installer = installer,
        ownedData = ownedData,
        attemptPaths = attempts,
        supervisor = supervisor,
        nextId = { ids.removeFirst() },
    )

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

    private fun NarDownload.handle() = OperationHandle(OperationId(id), AttemptId(attemptId))
}
