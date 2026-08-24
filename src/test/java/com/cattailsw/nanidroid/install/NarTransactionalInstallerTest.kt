package com.cattailsw.nanidroid.install

import com.cattailsw.nanidroid.HostAndroidStubRule
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** End-to-end contract for the fresh-install-only NAR transaction.  */
class NarTransactionalInstallerTest {
    @Rule @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun logicalTargetNamesUseGhostMgrCaseFolding() {
        val root = temporaryDirectory("logical-target")
        val first = File(root, "Foo").apply { mkdir() }

        Assert.assertTrue(NarTransactionalInstaller.hasLogicalTargetName(arrayOf(first), "foo"))
    }

    @Test
    fun missingDiscoveryDescriptorNeverPublishes() {
        val root = temporaryDirectory("undiscoverable")
        val archive = zip(
            "install.txt", descriptor("ghost-id"),
            "ghost/master/file.txt", bytes("payload"),
        )

        val result = NarTransactionalInstaller.install(archive, root, null, { false })

        Assert.assertTrue(result is ArchiveInstallResult.Failed)
        Assert.assertEquals(ArchiveInstallFailure.InvalidArchive, (result as ArchiveInstallResult.Failed).failure)
        Assert.assertFalse(File(root, "ghost-id").exists())
    }

    @Test
    fun caseVariantTargetConflictPreservesFirstTree() {
        val root = temporaryDirectory("case-variant-conflict")
        val first = validGhostZip("Foo", "ghost/master/file.txt", bytes("first"))
        val second = validGhostZip("foo", "ghost/master/file.txt", bytes("second"))

        Assert.assertTrue(NarTransactionalInstaller.install(first, root, null).isSuccess)
        val original = inventory(File(root, "Foo"))

        val result = NarTransactionalInstaller.install(second, root, null)

        Assert.assertEquals(NarTransactionalInstaller.Error.TARGET_EXISTS, result.error)
        Assert.assertEquals(original.keys, inventory(File(root, "Foo")).keys)
        original.forEach { (path, bytes) ->
            Assert.assertArrayEquals(bytes, inventory(File(root, "Foo")).getValue(path))
        }
        Assert.assertFalse(File(root, "foo").exists() && File(root, "foo").canonicalFile != File(root, "Foo").canonicalFile)
    }

    @Test
    fun postRenameExceptionsStillReportInstalled() {
        val root = temporaryDirectory("rename-after-move")
        val archive = validGhostZip("rename-id", "ghost/master/file.txt", bytes("payload"))
        val fileOperations = object : NarTransactionalInstaller.FileOperations {
            override fun openOutput(file: File) = FileOutputStream(file)
            override fun rename(source: File, destination: File): Boolean {
                source.renameTo(destination)
                throw IOException("rename completed before error")
            }
        }

        val result = NarTransactionalInstaller.install(
            archive, root, null, fileOperations, { false },
        ) { phase, _ ->
            if (phase == "Cleaning up") throw IllegalStateException("progress observer failed")
        }

        Assert.assertTrue(result is ArchiveInstallResult.Installed)
        Assert.assertEquals("rename-id", (result as ArchiveInstallResult.Installed).targetId)
        Assert.assertTrue(File(root, "rename-id").isDirectory)
    }

    @Test
    @Throws(Exception::class)
    fun installsValidatedArchiveAsOneNewGhostDirectory() {
        val root = temporaryDirectory("transaction-root")
        val archive = zip(
            "bundle/install.txt", descriptor("ignored"),
            "bundle/ghost/master/descript.txt", bytes("charset,UTF-8\nname,Test Ghost\nsakura.name,Sakura\n"),
            "bundle/ghost/master.txt", bytes("hello"),
            "bundle/shell/master.txt", bytes("world")
        )

        val result = NarTransactionalInstaller.install(archive, root, "forced-id")

        Assert.assertTrue("${result.error}: ${result.message}", result.isSuccess)
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
            "bundle/ghost/master/descript.txt", bytes("charset,UTF-8\nname,Test Ghost\nsakura.name,Sakura\n"),
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

        val retry = validGhostZip("retry-id", "ghost/master.txt", bytes("recovered"))
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
        val archive = validGhostZip("space-id", "ghost/master.txt", bytes("payload"))

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
        val archive = validGhostZip("io-id", "ghost/master.txt", bytes("payload"))

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
        val archive = validGhostZip("publish-id", "ghost/master.txt", bytes("payload"))

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
        val archive = validGhostZip("cancel-id", "ghost/master.txt", ByteArray(16 * 1024) { 1 })
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
        val archive = validGhostZip("progress-id", "ghost/master.txt", ByteArray(20 * 1024) { 7 })
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
            val archive = validGhostZip(targetId, "ghost/master.txt", bytes("payload"))
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
        val archive = validGhostZip("candidate-ghost", "ghost/master.txt", ByteArray(20 * 1024) { 3 })
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
        val archive = validGhostZip("published-id", "ghost/master.txt", bytes("committed"))
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

    @Test
    fun recoveryDeletesOnlyAbandonedMatchingCandidatesAndLeavesPublishedTargetUntouched() {
        val root = temporaryDirectory("recovery-targets")
        val staging = File(root, ".nanidroid-install-staging").apply { mkdir() }
        val abandoned = File(staging, "candidate-0123456789abcdef0123456789abcdef").apply { mkdir() }
        write(File(abandoned, "tree/partial.txt"), bytes("partial"))
        val unmatched = File(staging, "unmatched-candidate").apply { mkdir() }
        write(File(unmatched, "keep.txt"), bytes("keep"))
        val target = File(root, "published-id").apply { mkdir() }
        write(File(target, "ghost/master.txt"), bytes("published"))

        val result = NarTransactionalInstaller.recoverOwnedStaging(root)

        Assert.assertEquals(OwnedStagingRecoveryResult.Cleaned, result)
        Assert.assertFalse(abandoned.exists())
        Assert.assertArrayEquals(bytes("keep"), read(File(unmatched, "keep.txt")))
        Assert.assertArrayEquals(bytes("published"), read(File(target, "ghost/master.txt")))
        Assert.assertEquals(OwnedStagingRecoveryResult.Clean, NarTransactionalInstaller.recoverOwnedStaging(root))
    }

    @Test
    fun recoveryWrapperReportsDeleteFailureWithoutPublishingOrTouchingLiveTarget() {
        val root = temporaryDirectory("recovery-delete-failure")
        val staging = File(root, ".nanidroid-install-staging")
        val candidate = File(staging, "candidate-0123456789abcdef0123456789abcdef")
        val target = File(root, "published-id").apply { mkdir() }
        write(File(target, "ghost/master.txt"), bytes("published"))
        val files = RecoveryFileSystem().apply {
            directory(staging)
            directory(candidate)
            failDeletion(candidate)
        }

        val result = NarTransactionalInstaller.recoverOwnedStaging(root, files)

        Assert.assertTrue(result is OwnedStagingRecoveryResult.Failed)
        Assert.assertEquals(listOf(candidate), files.deleteAttempts)
        Assert.assertFalse(files.deleteAttempts.contains(target))
        Assert.assertArrayEquals(bytes("published"), read(File(target, "ghost/master.txt")))
    }

    @Test
    fun recoveryWaitsForInstallPublicationLockBeforeReconcilingStaging() {
        val root = temporaryDirectory("recovery-lock")
        val archive = validGhostZip("serialized-id", "ghost/master.txt", bytes("payload"))
        val installEntered = CountDownLatch(1)
        val releaseInstall = CountDownLatch(1)
        val recoveryStarted = CountDownLatch(1)
        val recoveryReturned = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val install = executor.submit<ArchiveInstallResult> {
                NarTransactionalInstaller.install(
                    archive = archive,
                    installRoot = root,
                    forcedId = null,
                    isCancelled = { false },
                    onProgress = { phase, _ ->
                        if (phase == "Copying archive") {
                            installEntered.countDown()
                            Assert.assertTrue(releaseInstall.await(5, TimeUnit.SECONDS))
                        }
                    },
                )
            }
            Assert.assertTrue(installEntered.await(5, TimeUnit.SECONDS))
            val recovery = executor.submit<OwnedStagingRecoveryResult> {
                try {
                    recoveryStarted.countDown()
                    NarTransactionalInstaller.recoverOwnedStaging(root)
                } finally {
                    recoveryReturned.countDown()
                }
            }

            Assert.assertTrue(recoveryStarted.await(5, TimeUnit.SECONDS))
            Assert.assertFalse(recoveryReturned.await(250, TimeUnit.MILLISECONDS))
            releaseInstall.countDown()
            Assert.assertTrue(install.get(5, TimeUnit.SECONDS) is ArchiveInstallResult.Installed)
            Assert.assertTrue(recoveryReturned.await(5, TimeUnit.SECONDS))
            Assert.assertEquals(OwnedStagingRecoveryResult.Clean, recovery.get(5, TimeUnit.SECONDS))
        } finally {
            releaseInstall.countDown()
            executor.shutdownNow()
        }
    }

    companion object {
        private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

        private class RecoveryFileSystem : OwnedStagingFileSystem {
            private val directories = mutableSetOf<File>()
            private val deleteFailures = mutableSetOf<File>()
            val deleteAttempts = mutableListOf<File>()

            fun directory(file: File) { directories += file }
            fun failDeletion(file: File) { deleteFailures += file }

            override fun canonical(file: File): File = file
            override fun existsNoFollow(file: File): Boolean = file in directories
            override fun isRegularFileNoFollow(file: File): Boolean = false
            override fun isDirectoryNoFollow(file: File): Boolean = file in directories
            override fun isSymbolicLink(file: File): Boolean = false
            override fun list(file: File): List<File>? = directories.filter { it.parentFile == file }
            override fun delete(file: File): Boolean {
                deleteAttempts += file
                return file !in deleteFailures && directories.remove(file)
            }
        }

        private fun validGhostZip(targetId: String, vararg values: Any): File = zip(
            "install.txt", descriptor(targetId),
            "ghost/master/descript.txt", bytes("charset,UTF-8\nname,Test Ghost\nsakura.name,Sakura\n"),
            *values,
        )

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
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw IOException("parent")
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

        private fun inventory(root: File): Map<String, ByteArray> = buildMap {
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                put(file.relativeTo(root).invariantSeparatorsPath, read(file))
            }
        }
    }
}
