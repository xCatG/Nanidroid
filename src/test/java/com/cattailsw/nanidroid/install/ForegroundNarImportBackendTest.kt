package com.cattailsw.nanidroid.install

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.Dispatchers

class ForegroundNarImportBackendTest {
    @Test fun absentOrIdleSingletonCanBeReplacedAndReset() {
        val first = idleCoordinator("first")
        val second = idleCoordinator("second")

        ForegroundNarImportCoordinator.replaceForTesting(first)
        ForegroundNarImportCoordinator.replaceForTesting(second)
        ForegroundNarImportCoordinator.resetForTesting()
    }

    @Test fun activeSingletonCannotBeReplacedOrReset() {
        val active = idleCoordinator("active")
        val replacement = idleCoordinator("replacement")
        ForegroundNarImportCoordinator.replaceForTesting(active)
        val token = requireNotNull(active.armPicker())
        try {
            assertThrows(IllegalStateException::class.java) {
                ForegroundNarImportCoordinator.replaceForTesting(replacement)
            }
            assertThrows(IllegalStateException::class.java) {
                ForegroundNarImportCoordinator.resetForTesting()
            }
        } finally {
            assertTrue(active.abandonPicker(token))
            ForegroundNarImportCoordinator.resetForTesting()
        }
    }

    @Test fun rejectsNonContentSelectionBeforeOpeningIt() {
        var opened = false
        val backend = backend(openContent = {
            opened = true
            ByteArrayInputStream(byteArrayOf(1))
        })

        val result = backend.importDocument(
            NarDocumentSelection("file:///sdcard/test.nar", "file"),
            isCancelled = { false },
            onInstallingProgress = { _, _ -> },
        )

        assertEquals(
            ArchiveInstallResult.Failed(
                "Choose a document from the system picker.",
                ArchiveInstallFailure.SourceUnavailable,
            ),
            result,
        )
        assertFalse(opened)
        assertEquals(0, installerCalls)
    }

    @Test fun selectedContentCopiesPrivatelyThenCallsInstallerOnce() {
        val bytes = "nar bytes".toByteArray()
        val installed = ArchiveInstallResult.Installed("/ghost/test", "test")
        val phases = mutableListOf<String>()
        val backend = backend(
            openContent = { ByteArrayInputStream(bytes) },
            installResult = installed,
            installerProgress = { phase, completed ->
                assertEquals("Installer progress", phase)
                assertEquals(7L, completed)
            },
        )

        val result = backend.importDocument(
            NarDocumentSelection("content://provider/test.nar", "content"),
            isCancelled = { false },
            onInstallingProgress = { phase, _ -> phases += phase },
        )

        assertSame(installed, result)
        assertEquals(1, installerCalls)
        assertArrayEquals(bytes, installedArchiveBytes)
        assertEquals(listOf("Preparing installer", "Installer progress"), phases)
        assertTrue(importRoot.listFiles().orEmpty().none { it.name.startsWith("nar-import-") })
    }

    @Test fun selectedContentIsRejectedAtTheConfiguredCopyBound() {
        val backend = backend(
            openContent = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) },
            maximumArchiveBytes = 4,
        )

        val result = backend.importDocument(
            NarDocumentSelection("content://provider/large.nar", "content"),
            isCancelled = { false },
            onInstallingProgress = { _, _ -> },
        )

        assertEquals(
            ArchiveInstallResult.Failed(
                "The selected document exceeds Nanidroid's archive size limit.",
                ArchiveInstallFailure.ArchiveTooLarge,
            ),
            result,
        )
        assertEquals(0, installerCalls)
        assertTrue(importRoot.listFiles().orEmpty().none { it.name.startsWith("nar-import-") })
    }

    @Test fun installerSuccessWithoutTargetIdUsesPublishedDirectoryName() {
        val backend = backend(
            openContent = { ByteArrayInputStream(byteArrayOf(1)) },
            installResult = ArchiveInstallResult.Installed("/ghost/normalized-target"),
        )

        val result = backend.importDocument(
            NarDocumentSelection("content://provider/test.nar", "content"),
            isCancelled = { false },
            onInstallingProgress = { _, _ -> },
        )

        assertEquals(ArchiveInstallResult.Installed("/ghost/normalized-target", "normalized-target"), result)
    }

    @Test fun unavailableGhostStorageFailsBeforeOpeningSelectedContent() {
        var opened = false
        val backend = backend(
            ghostRoot = { null },
            openContent = {
                opened = true
                ByteArrayInputStream(byteArrayOf(1))
            },
        )

        val result = backend.importDocument(
            NarDocumentSelection("content://provider/test.nar", "content"),
            isCancelled = { false },
            onInstallingProgress = { _, _ -> },
        )

        assertEquals(
            ArchiveInstallResult.Failed(
                "Nanidroid cannot access its ghost storage.",
                ArchiveInstallFailure.StorageUnavailable,
            ),
            result,
        )
        assertFalse(opened)
        assertEquals(0, installerCalls)
    }

    @Test fun recoveryCleansBothDomainsAndAnyCleanedYieldsCleaned() {
        File(importRoot, "nar-import-0123456789abcdef01234567.zip").writeText("residue")
        var recoveredGhostRoot: File? = null
        val backend = backend(installerRecovery = { root ->
            recoveredGhostRoot = root
            OwnedStagingRecoveryResult.Clean
        })

        val result = backend.recoverOwnedStaging()

        assertEquals(NarImportRecoveryResult.Cleaned, result)
        assertEquals(ghostRoot, recoveredGhostRoot)
        assertTrue(importRoot.listFiles().orEmpty().isEmpty())
    }

    @Test fun recoveryFailurePreservesItsResidueAndWinsOverOtherDomainCleanup() {
        val undeletableKind = File(importRoot, "nar-import-0123456789abcdef01234567.zip")
        assertTrue(undeletableKind.mkdirs())
        var installerRecoveryCalls = 0
        val backend = backend(installerRecovery = {
            installerRecoveryCalls += 1
            OwnedStagingRecoveryResult.Cleaned
        })

        val result = backend.recoverOwnedStaging()

        assertTrue(result is NarImportRecoveryResult.Failed)
        assertTrue(undeletableKind.isDirectory)
        assertEquals(1, installerRecoveryCalls)
    }

    @Test fun unavailableGhostStorageStillCleansImportsAndLaterRetryCanSucceed() {
        val residue = File(importRoot, "nar-import-fedcba987654321001234567.zip")
        residue.writeText("residue")
        var availableGhostRoot: File? = null
        var installerRecoveryCalls = 0
        val backend = backend(
            ghostRoot = { availableGhostRoot },
            installerRecovery = {
                installerRecoveryCalls += 1
                OwnedStagingRecoveryResult.Clean
            },
        )

        val unavailable = backend.recoverOwnedStaging()

        assertEquals(
            NarImportRecoveryResult.Failed("Nanidroid cannot access its ghost storage."),
            unavailable,
        )
        assertFalse(residue.exists())
        assertEquals(0, installerRecoveryCalls)

        availableGhostRoot = ghostRoot

        assertEquals(NarImportRecoveryResult.Clean, backend.recoverOwnedStaging())
        assertEquals(1, installerRecoveryCalls)
    }

    private fun backend(
        ghostRoot: () -> File? = { this.ghostRoot },
        openContent: (String) -> ByteArrayInputStream? = { ByteArrayInputStream(byteArrayOf(1)) },
        installResult: ArchiveInstallResult = ArchiveInstallResult.Installed("/ghost/test", "test"),
        installerProgress: (String, Long) -> Unit = { _, _ -> },
        installerRecovery: (File) -> OwnedStagingRecoveryResult = { OwnedStagingRecoveryResult.Clean },
        maximumArchiveBytes: Long = NarContentUriImport.MAX_ARCHIVE_BYTES,
    ) = AndroidForegroundNarImportBackend(
        importRoot = importRoot,
        ghostRoot = ghostRoot,
        openContent = openContent,
        install = { staged, root, forcedId, _, progress ->
            installerCalls += 1
            installedArchiveBytes = staged.readBytes()
            assertEquals(this.ghostRoot, root)
            assertEquals(null, forcedId)
            progress("Installer progress", 7L)
            installerProgress("Installer progress", 7L)
            installResult
        },
        recoverInstallerStaging = installerRecovery,
        maximumArchiveBytes = maximumArchiveBytes,
    )

    private fun idleCoordinator(processNonce: String) = ForegroundNarImportCoordinator(
        backend = object : ForegroundNarImportBackend {
            override fun recoverOwnedStaging() = NarImportRecoveryResult.Clean

            override fun importDocument(
                selection: NarDocumentSelection,
                isCancelled: () -> Boolean,
                onInstallingProgress: (String, Long) -> Unit,
            ) = ArchiveInstallResult.Cancelled
        },
        dispatcher = Dispatchers.Unconfined,
        processNonce = processNonce,
    ).also { assertEquals(ForegroundNarImportState.Idle, it.state.value) }

    private val testRoot = kotlin.io.path.createTempDirectory("foreground-nar-backend-").toFile()
    private val importRoot = File(testRoot, "imports").apply { mkdirs() }
    private val ghostRoot = File(testRoot, "ghost").apply { mkdirs() }
    private var installerCalls = 0
    private var installedArchiveBytes = ByteArray(0)
}
