package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.install.ArchiveInstallResult
import com.cattailsw.nanidroid.install.ForegroundNarImportBackend
import com.cattailsw.nanidroid.install.ForegroundNarImportCoordinator
import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarDocumentSelection
import com.cattailsw.nanidroid.install.NarImportRecoveryResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

class NanidroidGhostStartupTest {
    @get:Rule
    val androidStubs = HostAndroidStubRule()

    @Test
    fun foregroundImportRefreshCannotPublishPreCommitCatalogScan() {
        val externalRoot = File.createTempFile("nanidroid-catalog", "").apply {
            check(delete() && mkdir())
        }
        val context = mockk<Context>()
        every { context.getExternalFilesDir(null) } returns externalRoot
        every { context.applicationContext } returns context
        try {
            val dispatcher = QueuedDispatcher()
            lateinit var manager: GhostMgr
            val coordinator = ForegroundNarImportCoordinator(
                backend = object : ForegroundNarImportBackend {
                    override fun recoverOwnedStaging() = NarImportRecoveryResult.Clean

                    override fun importDocument(
                        selection: NarDocumentSelection,
                        isCancelled: () -> Boolean,
                        onInstallingProgress: (String, Long) -> Unit,
                    ): ArchiveInstallResult {
                        // The catalog cannot have been published while the committed root is absent.
                        assertEquals(0, manager.getGhostCount())
                        descriptor(File(externalRoot, "ghost/ghost-a"))
                        return ArchiveInstallResult.Installed(
                            File(externalRoot, "ghost/ghost-a").path,
                            "ghost-a",
                        )
                    }
                },
                dispatcher = dispatcher,
                processNonce = "catalog-test",
            )
            dispatcher.runNext()
            manager = GhostMgr(context)
            val token = requireNotNull(coordinator.armPicker())

            assertTrue(
                coordinator.consumePickerResult(
                    token,
                    NarDocumentSelection("content://fixture/ghost-a.nar", "ghost-a.nar"),
                    importAllowed = true,
                ),
            )
            assertEquals(ForegroundNarImportState.Copying(token), coordinator.state.value)
            assertEquals(0, manager.getGhostCount())

            dispatcher.runNext()

            assertTrue(coordinator.state.value is ForegroundNarImportState.Installed)
            assertEquals(0, manager.getGhostCount())
            manager.refreshGhost()

            assertEquals(listOf("ghost-a"), requireNotNull(manager.getGnames()).toList())
        } finally {
            externalRoot.deleteRecursively()
        }
    }

    @Test
    fun `recreated switch shows progress only after authored playback completes`() {
        GhostRuntimePhase.entries.forEach { phase ->
            assertEquals(
                phase == GhostRuntimePhase.Replacing,
                switchProgressVisibleFor(phase),
            )
        }
    }

    @Test
    fun `installed metadata keeps the canonical root for transitional activation`() {
        val root = File.createTempFile("nanidroid-installed", "").apply {
            check(delete() && mkdir())
        }.canonicalFile
        try {
            val metadata = InstalledGhostMetadata(
                id = root.name,
                canonicalRoot = root,
                name = "Fixture",
                sakuraName = "Sakura",
                readme = File(root, "readme.txt"),
            )

            assertEquals(root, metadata.canonicalRoot)
            assertEquals(File(root, "readme.txt"), metadata.readme)
        } finally {
            root.deleteRecursively()
        }
    }

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
        assertTrue(!finishAfterRestoredNotice(android.R.string.ok))
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

    private fun descriptor(root: File) {
        File(root, "ghost/master").mkdirs()
        File(root, "ghost/master/descript.txt").writeText(
            "charset,UTF-8\nname,Fixture\nsakura.name,Sakura\n",
        )
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runNext() = requireNotNull(tasks.pollFirst()).run()
    }
}
