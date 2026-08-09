package com.cattailsw.nanidroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.CountDownLatch

class NarDownloadStoreTest {
    private val store = NarDownloadStore(NarDownloadStore.MemoryStorage())

    @Test fun updatingOneRecordDoesNotDiscardAnother() {
        store.create(remote(id = "a", state = NarDownloadState.Downloading))
        store.create(remote(id = "b", state = NarDownloadState.Downloading))

        store.update("a") {
            it.copy(state = NarDownloadState.NeedsAttention(NarDownloadState.Failure("offline")))
        }

        assertEquals(NarDownloadState.Downloading, store.get("b")!!.state)
    }

    @Test fun needsAttentionRetainsItsSourceForRetry() {
        val retainedUri = "content://downloads/archive.nar"
        val item = remote(id = "a", retainedUri = retainedUri)

        store.create(item)
        store.update("a") {
            it.copy(state = NarDownloadState.NeedsAttention(NarDownloadState.Failure("invalid archive")))
        }

        assertEquals(retainedUri, store.get("a")!!.retainedUri)
    }

    @Test fun needsAttentionCannotReplaceItsRetrySource() {
        val originalSource = NarDownloadSource.Remote("https://example.invalid/original.nar")
        val originalRetainedUri = "content://downloads/original.nar"
        store.create(
            NarDownload(
                id = "a",
                source = originalSource,
                retainedUri = originalRetainedUri,
                state = NarDownloadState.Downloading,
            ),
        )

        store.update("a") {
            it.copy(
                source = NarDownloadSource.Remote("https://example.invalid/replacement.nar"),
                retainedUri = "content://downloads/replacement.nar",
                state = NarDownloadState.NeedsAttention(NarDownloadState.Failure("offline")),
            )
        }

        val stored = store.get("a")!!
        assertEquals(originalSource, stored.source)
        assertEquals(originalRetainedUri, stored.retainedUri)
    }

    @Test fun recordsSurviveRecreatingTheStore() {
        val storage = NarDownloadStore.MemoryStorage()
        NarDownloadStore(storage).create(remote(id = "a"))

        val restored = NarDownloadStore(storage).get("a")

        assertNotNull(restored)
        assertEquals(NarDownloadSource.Remote("https://example.invalid/archive.nar"), restored!!.source)
    }

    @Test fun copyingStateSurvivesRecreatingTheStore() {
        val storage = NarDownloadStore.MemoryStorage()
        NarDownloadStore(storage).create(remote(id = "a", state = NarDownloadState.Copying))

        assertEquals(NarDownloadState.Copying, NarDownloadStore(storage).get("a")!!.state)
    }

    @Test fun pendingGrantCleanupSurvivesRecreatingTheStore() {
        val storage = NarDownloadStore.MemoryStorage()
        val source = "content://provider/archive.nar"
        NarDownloadStore(storage).create(
            NarDownload(
                id = "a",
                source = NarDownloadSource.Local(source),
                retainedUri = "file:///owned/archive.nar",
                pendingPersistedGrantReleaseUri = source,
            ),
        )

        assertEquals(
            source,
            NarDownloadStore(storage).get("a")!!.pendingPersistedGrantReleaseUri,
        )
    }

    @Test fun detachedPendingGrantCleanupSurvivesRecreatingTheStore() {
        val storage = NarDownloadStore.MemoryStorage()
        val source = "content://provider/archive.nar"
        NarDownloadStore(storage).addPendingPersistedGrantRelease(source)

        assertEquals(setOf(source), NarDownloadStore(storage).pendingPersistedGrantReleases())
    }

    @Test fun recordsAreListedInEnqueueOrder() {
        store.create(remote(id = "z"))
        store.create(remote(id = "a"))

        assertEquals(listOf("z", "a"), store.getAll().map(NarDownload::id))
    }

    @Test fun concurrentStoresDoNotLoseEitherCreatedRecord() {
        val storage = NarDownloadStore.MemoryStorage()
        val first = NarDownloadStore(storage)
        val second = NarDownloadStore(storage)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val firstThread = Thread { ready.countDown(); start.await(); first.create(remote(id = "a")) }
        val secondThread = Thread { ready.countDown(); start.await(); second.create(remote(id = "b")) }

        firstThread.start()
        secondThread.start()
        ready.await()
        start.countDown()
        firstThread.join(5_000)
        secondThread.join(5_000)

        assertFalse(firstThread.isAlive)
        assertFalse(secondThread.isAlive)
        assertEquals(setOf("a", "b"), first.getAll().map { it.id }.toSet())
    }

    private fun remote(
        id: String,
        retainedUri: String? = null,
        state: NarDownloadState = NarDownloadState.Downloading,
    ) = NarDownload(
        id = id,
        source = NarDownloadSource.Remote("https://example.invalid/archive.nar"),
        retainedUri = retainedUri,
        state = state,
    )
}
