package com.cattailsw.nanidroid.install

import org.junit.Assert
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** End-to-end contract for the fresh-install-only NAR transaction.  */
class NarTransactionalInstallerTest {
    @Test
    @Throws(Exception::class)
    fun installsValidatedArchiveAsOneNewGhostDirectory() {
        val root = temporaryDirectory("transaction-root")
        val archive = zip(
            "bundle/install.txt", descriptor("ignored"),
            "bundle/ghost/master.txt", bytes("hello"),
            "bundle/shell/master.txt", bytes("world")
        )

        val result = NarTransactionalInstaller.install(archive, root, "forced-id")

        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals("forced-id", result.targetId)
        Assert.assertEquals(
            File(root, "forced-id").canonicalFile,
            result.installedDirectory
        )
        Assert.assertArrayEquals(
            bytes("hello"), read(File(root, "forced-id/ghost/master.txt"))
        )
        Assert.assertArrayEquals(
            bytes("world"), read(File(root, "forced-id/shell/master.txt"))
        )
        Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())
    }

    @Test
    @Throws(Exception::class)
    fun installsWrappedArchiveWithDeeperBundledDescriptorPayload() {
        val root = temporaryDirectory("transaction-nested-descriptor")
        val archive = zip(
            "bundle/install.txt", descriptor("forced-id"),
            "bundle/ghost/master.txt", bytes("hello"),
            "bundle/shell/install.txt", bytes("type,shell\nname,Nested Shell\ndirectory,ignored\n"),
            "bundle/balloon/install.txt", bytes("type,balloon\nname,Nested Balloon\ndirectory,ignored\n"),
        )

        val result = NarTransactionalInstaller.install(archive, root, null)

        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals("forced-id", result.targetId)
        Assert.assertArrayEquals(
            bytes("hello"), read(File(root, "forced-id/ghost/master.txt"))
        )
        Assert.assertArrayEquals(
            bytes("type,shell\nname,Nested Shell\ndirectory,ignored\n"), read(File(root, "forced-id/shell/install.txt"))
        )
        Assert.assertArrayEquals(
            bytes("type,balloon\nname,Nested Balloon\ndirectory,ignored\n"), read(File(root, "forced-id/balloon/install.txt"))
        )
        Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())
    }

    @Test
    @Throws(Exception::class)
    fun rejectsExistingTargetWithoutChangingItOrCreatingStaging() {
        val root = temporaryDirectory("transaction-existing")
        val existing = File(root, "ghost-id")
        Assert.assertTrue(existing.mkdir())
        write(File(existing, "keep.txt"), bytes("keep"))
        val archive = zip(
            "install.txt", descriptor("ghost-id"),
            "ghost/master.txt", bytes("replacement")
        )

        val result = NarTransactionalInstaller.install(archive, root, null)

        Assert.assertFalse(result.isSuccess)
        Assert.assertEquals(NarTransactionalInstaller.Error.TARGET_EXISTS, result.error)
        Assert.assertArrayEquals(bytes("keep"), read(File(existing, "keep.txt")))
        Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())
    }

    @Test
    @Throws(Exception::class)
    fun invalidArchiveLeavesNoTargetOrStagingResidue() {
        val root = temporaryDirectory("transaction-invalid")
        val archive = zip(
            "install.txt", descriptor("ghost-id"),
            "../outside.txt", bytes("bad")
        )

        val result = NarTransactionalInstaller.install(archive, root, null)

        Assert.assertFalse(result.isSuccess)
        Assert.assertEquals(NarTransactionalInstaller.Error.ARCHIVE_REJECTED, result.error)
        Assert.assertFalse(File(root, "ghost-id").exists())
        Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())
    }

    @Test
    @Throws(Exception::class)
    fun corruptLocalArchiveLeavesNoPartialStateAndValidRetrySucceeds() {
        val root = temporaryDirectory("transaction-retry")
        val interrupted = File.createTempFile("nar-interrupted", ".nar")
        write(interrupted, bytes("incomplete archive transfer"))

        val rejected = NarTransactionalInstaller.install(interrupted, root, "retry-id")

        Assert.assertFalse(rejected.isSuccess)
        Assert.assertEquals(NarTransactionalInstaller.Error.ARCHIVE_REJECTED, rejected.error)
        Assert.assertFalse(File(root, "retry-id").exists())
        Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())

        val retry = zip(
            "install.txt", descriptor("retry-id"),
            "ghost/master.txt", bytes("recovered")
        )
        val installed = NarTransactionalInstaller.install(retry, root, null)

        Assert.assertTrue(installed.isSuccess)
        Assert.assertEquals("retry-id", installed.targetId)
        Assert.assertArrayEquals(
            bytes("recovered"), read(File(root, "retry-id/ghost/master.txt"))
        )
        Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())
    }

    @Test
    @Throws(Exception::class)
    fun insufficientSpaceDuringExtractionLeavesNoPartialStateAndRetrySucceeds() {
        val root = temporaryDirectory("transaction-no-space")
        val archive = zip(
            "install.txt", descriptor("space-id"),
            "ghost/master.txt", bytes("payload")
        )

        val failed = NarTransactionalInstaller.install(
            archive, root, null, failingOutput("no space left on device")
        )

        assertFailureLeavesNoPartialState(
            failed, NarTransactionalInstaller.Error.EXTRACTION_FAILED, root, "space-id"
        )
        assertSuccessfulRetry(archive, root, "space-id")
    }

    @Test
    @Throws(Exception::class)
    fun extractionIoFailureLeavesNoPartialStateAndRetrySucceeds() {
        val root = temporaryDirectory("transaction-io")
        val archive = zip(
            "install.txt", descriptor("io-id"),
            "ghost/master.txt", bytes("payload")
        )

        val failed = NarTransactionalInstaller.install(
            archive, root, null, failingOutput("simulated write failure")
        )

        assertFailureLeavesNoPartialState(
            failed, NarTransactionalInstaller.Error.EXTRACTION_FAILED, root, "io-id"
        )
        assertSuccessfulRetry(archive, root, "io-id")
    }

    @Test
    @Throws(Exception::class)
    fun publishFailureLeavesNoPartialStateAndRetrySucceeds() {
        val root = temporaryDirectory("transaction-publish")
        val archive = zip(
            "install.txt", descriptor("publish-id"),
            "ghost/master.txt", bytes("payload")
        )

        val failed = NarTransactionalInstaller.install(
            archive, root, null, refusingPublish()
        )

        assertFailureLeavesNoPartialState(
            failed, NarTransactionalInstaller.Error.PUBLISH_FAILED, root, "publish-id"
        )
        assertSuccessfulRetry(archive, root, "publish-id")
    }

    @Test
    @Throws(Exception::class)
    fun failureIsCategorizedForUserFacingErrorMapping() {
        val root = temporaryDirectory("transaction-missing")
        val result = NarTransactionalInstaller.install(
            File(root, "missing.nar"), root, null
        )

        Assert.assertFalse(result.isSuccess)
        Assert.assertEquals(NarTransactionalInstaller.Error.SOURCE_UNAVAILABLE, result.error)
        Assert.assertNull(result.installedDirectory)
        Assert.assertTrue(result.message.isNotEmpty())
    }

    @Test
    @Throws(Exception::class)
    fun cancellationDuringExtractionDeletesPartialTransactionWithoutPublishing() {
        val root = temporaryDirectory("transaction-cancelled")
        val archive = zip(
            "install.txt", descriptor("cancel-id"),
            "ghost/master.txt", ByteArray(16 * 1024) { 1 },
        )
        var bytesWritten = false
        val result = NarTransactionalInstaller.install(
            archive = archive,
            installRoot = root,
            forcedId = null,
            fileOperations = cancellationAwareOutput { bytesWritten = true },
            isCancelled = { bytesWritten },
        )

        Assert.assertTrue(result === ArchiveInstallResult.Cancelled)
        Assert.assertFalse(File(root, "cancel-id").exists())
        Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())
    }

    @Test
    fun installReportsPhaseBoundariesAndRealByteProgress() {
        val root = temporaryDirectory("transaction-progress")
        val archive = zip(
            "install.txt", descriptor("progress-id"),
            "ghost/master.txt", ByteArray(20 * 1024) { 7 },
        )
        val progress = mutableListOf<Pair<String, Long>>()

        val result = NarTransactionalInstaller.install(
            archive = archive,
            installRoot = root,
            forcedId = null,
            isCancelled = { false },
            onProgress = { phase, completed -> progress += phase to completed },
        )

        Assert.assertTrue(result is ArchiveInstallResult.Installed)
        Assert.assertTrue(progress.any { it.first == "Copying archive" && it.second > 0L })
        Assert.assertTrue(progress.any { it.first == "Preflighting archive" })
        Assert.assertTrue(progress.any { it.first == "Verifying archive" })
        Assert.assertTrue(progress.any { it.first == "Extracting archive" && it.second > 0L })
        Assert.assertTrue(progress.any { it.first == "Preparing commit" })
        Assert.assertTrue(progress.any { it.first == "Publishing archive" })
        Assert.assertTrue(progress.any { it.first == "Cleaning up" })
        val byteHeartbeats = progress.filter { it.second > 0L }
        Assert.assertTrue(byteHeartbeats.zipWithNext().all { (left, right) ->
            left.first != right.first || right.second > left.second
        })
    }

    @Test
    fun cancellationAtInstallBoundariesDoesNotPublish() {
        listOf(
            "Preflighting archive",
            "Verifying archive",
            "Preparing commit",
            "Publishing archive",
        ).forEach { cancelledPhase ->
            val root = temporaryDirectory("transaction-boundary")
            val targetId = "cancel-${cancelledPhase.hashCode()}"
            val archive = zip(
                "install.txt", descriptor(targetId),
                "ghost/master.txt", bytes("payload"),
            )
            var stopRequested = false

            val result = NarTransactionalInstaller.install(
                archive = archive,
                installRoot = root,
                forcedId = null,
                isCancelled = { stopRequested },
                onProgress = { phase, _ ->
                    if (phase == cancelledPhase) stopRequested = true
                },
            )

            Assert.assertTrue("$cancelledPhase returned $result", result === ArchiveInstallResult.Cancelled)
            Assert.assertFalse(File(root, targetId).exists())
            Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())
        }
    }

    @Test
    fun cancellationDuringCentralPreflightAfterProgressDoesNotPublish() {
        val root = temporaryDirectory("transaction-preflight-cancel")
        val archive = zip(
            "install.txt", descriptor("preflight-cancel-id"),
            "ghost/master.txt", ByteArray(64 * 1024) { 4 },
        )
        var stopRequested = false

        val result = NarTransactionalInstaller.install(
            archive = archive,
            installRoot = root,
            forcedId = null,
            isCancelled = { stopRequested },
            onProgress = { phase, completed ->
                if (phase == "Preflighting archive" && completed > 0L) stopRequested = true
            },
        )

        Assert.assertEquals(ArchiveInstallResult.Cancelled, result)
        Assert.assertFalse(File(root, "preflight-cancel-id").exists())
        Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())
    }

    @Test
    fun cancellationDuringArchiveVerificationAfterProgressDoesNotPublish() {
        val root = temporaryDirectory("transaction-verification-cancel")
        val archive = zip(
            "install.txt", descriptor("verification-cancel-id"),
            "ghost/master.txt", ByteArray(64 * 1024) { 5 },
        )
        var stopRequested = false

        val result = NarTransactionalInstaller.install(
            archive = archive,
            installRoot = root,
            forcedId = null,
            isCancelled = { stopRequested },
            onProgress = { phase, completed ->
                if (phase == "Verifying archive" && completed > 0L) stopRequested = true
            },
        )

        Assert.assertEquals(ArchiveInstallResult.Cancelled, result)
        Assert.assertFalse(File(root, "verification-cancel-id").exists())
        Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())
    }

    @Test
    fun stop_install_removes_only_staging_and_preserves_live_ghost() {
        val root = temporaryDirectory("transaction-owned-cleanup")
        val installedGhost = File(root, "live-ghost")
        Assert.assertTrue(installedGhost.mkdir())
        write(File(installedGhost, "ghost/master.txt"), bytes("previous live tree"))
        val previousTree = read(File(installedGhost, "ghost/master.txt"))
        val archive = zip(
            "install.txt", descriptor("candidate-ghost"),
            "ghost/master.txt", ByteArray(20 * 1024) { 3 },
        )
        var stopRequested = false

        val result = NarTransactionalInstaller.install(
            archive = archive,
            installRoot = root,
            forcedId = null,
            isCancelled = { stopRequested },
            onProgress = { phase, completed ->
                if (phase == "Extracting archive" && completed > 0L) stopRequested = true
            },
        )

        Assert.assertEquals(ArchiveInstallResult.Cancelled, result)
        Assert.assertArrayEquals(previousTree, read(File(installedGhost, "ghost/master.txt")))
        Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())
        Assert.assertFalse(File(root, "candidate-ghost").exists())
    }

    @Test
    fun cancellationRequestedAfterPublishPreservesCommittedGhostAndFinishesCleanup() {
        val root = temporaryDirectory("transaction-post-commit")
        val archive = zip(
            "install.txt", descriptor("published-id"),
            "ghost/master.txt", bytes("committed"),
        )
        var stopRequested = false

        val result = NarTransactionalInstaller.install(
            archive = archive,
            installRoot = root,
            forcedId = null,
            isCancelled = { stopRequested },
            onProgress = { phase, _ ->
                if (phase == "Cleaning up") stopRequested = true
            },
        )

        Assert.assertTrue(result is ArchiveInstallResult.Installed)
        Assert.assertArrayEquals(bytes("committed"), read(File(root, "published-id/ghost/master.txt")))
        Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())
    }

    companion object {
        private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

        @Throws(IOException::class)
        private fun temporaryDirectory(label: String): File {
            val file = File.createTempFile(label, "")
            if (!file.delete() || !file.mkdir()) throw IOException("temporary root")
            return file
        }

        private fun failingOutput(message: String): NarTransactionalInstaller.FileOperations =
            object : NarTransactionalInstaller.FileOperations {
                @Throws(IOException::class)
                override fun openOutput(file: File): FileOutputStream {
                    throw IOException(message)
                }

                override fun rename(source: File, destination: File): Boolean =
                    source.renameTo(destination)
            }

        private fun refusingPublish(): NarTransactionalInstaller.FileOperations =
            object : NarTransactionalInstaller.FileOperations {
                @Throws(IOException::class)
                override fun openOutput(file: File): FileOutputStream = FileOutputStream(file)

                override fun rename(source: File, destination: File): Boolean = false
            }

        private fun cancellationAwareOutput(
            onWrite: () -> Unit,
        ): NarTransactionalInstaller.FileOperations =
            object : NarTransactionalInstaller.FileOperations {
                @Throws(IOException::class)
                override fun openOutput(file: File): FileOutputStream =
                    object : FileOutputStream(file) {
                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            super.write(buffer, offset, length)
                            onWrite()
                        }
                    }

                override fun rename(source: File, destination: File): Boolean =
                    source.renameTo(destination)
            }

        private fun assertFailureLeavesNoPartialState(
            result: NarTransactionalInstaller.Result,
            error: NarTransactionalInstaller.Error,
            root: File,
            targetId: String
        ) {
            Assert.assertFalse(result.isSuccess)
            Assert.assertEquals(error, result.error)
            Assert.assertFalse(File(root, targetId).exists())
            Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())
        }

        private fun assertSuccessfulRetry(archive: File, root: File, targetId: String) {
            val retry = NarTransactionalInstaller.install(archive, root, null)
            Assert.assertTrue(retry.isSuccess)
            Assert.assertEquals(targetId, retry.targetId)
            Assert.assertFalse(File(root, ".nanidroid-install-staging").exists())
        }

        @Throws(IOException::class)
        private fun zip(vararg values: Any): File {
            val archive = File.createTempFile("nar-transaction", ".nar")
            ZipOutputStream(FileOutputStream(archive)).use { output ->
                var index = 0
                while (index < values.size) {
                    val entry = ZipEntry(values[index] as String)
                    output.putNextEntry(entry)
                    output.write(values[index + 1] as ByteArray)
                    output.closeEntry()
                    index += 2
                }
            }
            return archive
        }

        private fun descriptor(id: String): ByteArray =
            "type,ghost\nname,Test Ghost\ndirectory,$id\n".toByteArray(SHIFT_JIS)

        private fun bytes(value: String): ByteArray = value.toByteArray(SHIFT_JIS)

        @Throws(IOException::class)
        private fun write(target: File, content: ByteArray) {
            val parent = target.parentFile
            if (!parent.exists() && !parent.mkdirs()) throw IOException("parent")
            FileOutputStream(target).use { output -> output.write(content) }
        }

        @Throws(IOException::class)
        private fun read(source: File): ByteArray {
            FileInputStream(source).use { input ->
                val content = ByteArray(source.length().toInt())
                var offset = 0
                while (offset < content.size) {
                    val count = input.read(content, offset, content.size - offset)
                    if (count < 0) throw IOException("unexpected EOF")
                    offset += count
                }
                return content
            }
        }
    }
}
