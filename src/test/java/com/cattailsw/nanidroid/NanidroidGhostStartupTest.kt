package com.cattailsw.nanidroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.io.File

class NanidroidGhostStartupTest {
    @Test
    fun `blocked preferred ghost falls back to first healthy candidate`() {
        val attempted = mutableListOf<String>()
        val selected = launchCandidateIds("blocked-a", listOf("healthy-b", "healthy-c"))
            .firstOrNull { id ->
                attempted += id
                id == "healthy-b"
            }

        assertEquals("healthy-b", selected)
        assertEquals(listOf("blocked-a", "healthy-b"), attempted)
    }

    @Test
    fun `no available ghost produces a non-throwing empty fallback`() {
        val selected = launchCandidateIds("blocked-a", emptyList())
            .firstOrNull { false }

        assertTrue(selected == null)
    }

    @Test
    fun `restored terminal startup notices keep finish confirmation`() {
        assertTrue(finishAfterRestoredNotice(R.string.err_no_sdcard))
        assertTrue(finishAfterRestoredNotice(R.string.err_no_ghost_available))
        assertTrue(!finishAfterRestoredNotice(R.string.not_implemented))
    }

    @Test
    fun `service dispatch does not run ghost reconciliation inline`() {
        var queued: Runnable? = null
        var enteredBlockingReconciliation = false
        val deferred = Executor { queued = it }

        dispatchGhostUpdateEnqueue(deferred) {
            enteredBlockingReconciliation = true
        }

        assertTrue(!enteredBlockingReconciliation)
        queued!!.run()
        assertTrue(enteredBlockingReconciliation)
    }

    @Test
    fun `bundled ghost installs only into physically empty storage`() {
        val storage = File.createTempFile("nanidroid-bootstrap", "").apply {
            check(delete() && mkdir())
        }
        try {
            assertTrue(shouldInstallBundledGhost(0, storage.listFiles().orEmpty()))

            val blockedNanidroid = File(storage, "nanidroid").apply { mkdirs() }
            val marker = File(blockedNanidroid, "ghost/master.txt").apply {
                parentFile!!.mkdirs()
                writeText("old")
            }
            File(storage, ".nanidroid-update-owned").mkdirs()

            assertTrue(!shouldInstallBundledGhost(0, storage.listFiles().orEmpty()))
            assertEquals("old", marker.readText())

            blockedNanidroid.deleteRecursively()
            File(storage, ".nanidroid-update-owned").deleteRecursively()
            File(storage, ".nanidroid-install-staging/abandoned/tree").mkdirs()
            assertTrue(shouldInstallBundledGhost(0, storage.listFiles().orEmpty()))

            File(storage, "unknown-owner").mkdirs()
            assertTrue(!shouldInstallBundledGhost(0, storage.listFiles().orEmpty()))
        } finally {
            storage.deleteRecursively()
        }
    }
}
