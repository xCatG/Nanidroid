package com.cattailsw.nanidroid.install

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NarDownloadRepositoryTest {
    private val store = NarDownloadStore(NarDownloadStore.MemoryStorage())
    private val downloads = FakeDownloadGateway()
    private val work = FakeWorkScheduler()
    private val installer = FakeArchiveInstaller()
    private val ownedData = FakeOwnedData()
    private val attempts = FakeAttemptPaths()
    private val ids = ArrayDeque(listOf("old-item", "new-item", "third-item"))
    private val repository = NarDownloadRepository(
        store = store,
        downloads = downloads,
        work = work,
        installer = installer,
        ownedData = ownedData,
        attemptPaths = attempts,
        nextId = { ids.removeFirst() },
    )

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
    }

    private class FakeWorkScheduler : NarInstallWorkScheduler {
        val enqueuedNames = mutableListOf<String>()
        val cancelledNames = mutableListOf<String>()

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

        override fun install(
            download: NarDownload,
            stagingDirectory: File,
            isStopped: () -> Boolean,
        ): ArchiveInstallResult {
            stagingDirectories += stagingDirectory
            failure?.let { throw it }
            return result
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
}
