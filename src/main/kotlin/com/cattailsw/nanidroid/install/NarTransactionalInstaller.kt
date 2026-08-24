package com.cattailsw.nanidroid.install

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Fresh-install-only transactional NAR installer. */
class NarTransactionalInstaller private constructor() {
    /**
     * Minimal filesystem boundary for deterministic transaction-failure tests.
     * Production retains the direct FileOutputStream/rename behavior.
     */
    interface FileOperations {
        @Throws(IOException::class)
        fun openOutput(file: File): FileOutputStream
        fun rename(source: File, destination: File): Boolean
    }

    enum class Error {
        SOURCE_UNAVAILABLE,
        INSTALL_ROOT_INVALID,
        ARCHIVE_REJECTED,
        TARGET_EXISTS,
        STAGING_FAILED,
        EXTRACTION_FAILED,
        PUBLISH_FAILED
    }

    class Result private constructor(
        val installedDirectory: File?,
        val targetId: String?,
        val error: Error?,
        val message: String
    ) {
        val isSuccess: Boolean get() = installedDirectory != null

        companion object {
            internal fun create(
                installedDirectory: File?,
                targetId: String?,
                error: Error?,
                message: String
            ) = Result(installedDirectory, targetId, error, message)
        }
    }

    companion object {
        private const val STAGING_DIRECTORY = ".nanidroid-install-staging"
        private const val MAX_FILE_BYTES = 128L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
        private const val BUFFER_SIZE = 8192
        private val INSTALL_LOCK = Any()

        @JvmStatic
        fun install(archive: File?, installRoot: File?, forcedId: String?): Result =
            legacy(install(archive, installRoot, forcedId, RealFileOperations, { false }))

        /** Test seam for write and publication failures; callers retain the three-argument API. */
        @JvmStatic
        fun install(
            archive: File?,
            installRoot: File?,
            forcedId: String?,
            fileOperations: FileOperations,
        ): Result = synchronized(INSTALL_LOCK) {
            legacy(
                installLocked(
                    archive,
                    installRoot,
                    forcedId,
                    fileOperations,
                    { false },
                    { _, _ -> },
                ),
            )
        }

        @JvmStatic
        fun install(
            archive: File?,
            installRoot: File?,
            forcedId: String?,
            isCancelled: () -> Boolean,
        ): ArchiveInstallResult = install(
            archive,
            installRoot,
            forcedId,
            RealFileOperations,
            isCancelled,
            { _, _ -> },
        )

        @JvmStatic
        fun install(
            archive: File?,
            installRoot: File?,
            forcedId: String?,
            isCancelled: () -> Boolean,
            onProgress: (phase: String, completed: Long) -> Unit,
        ): ArchiveInstallResult = install(
            archive,
            installRoot,
            forcedId,
            RealFileOperations,
            isCancelled,
            onProgress,
        )

        @JvmStatic
        fun install(
            archive: File?,
            installRoot: File?,
            forcedId: String?,
            fileOperations: FileOperations,
            isCancelled: () -> Boolean,
        ): ArchiveInstallResult = install(
            archive,
            installRoot,
            forcedId,
            fileOperations,
            isCancelled,
            { _, _ -> },
        )

        @JvmStatic
        fun install(
            archive: File?,
            installRoot: File?,
            forcedId: String?,
            fileOperations: FileOperations,
            isCancelled: () -> Boolean,
            onProgress: (phase: String, completed: Long) -> Unit,
        ): ArchiveInstallResult = synchronized(INSTALL_LOCK) {
            installLocked(
                archive,
                installRoot,
                forcedId,
                fileOperations,
                isCancelled,
                onProgress,
            )
        }

        private fun installLocked(
            archive: File?,
            installRoot: File?,
            forcedId: String?,
            fileOperations: FileOperations,
            isCancelled: () -> Boolean,
            onProgress: (phase: String, completed: Long) -> Unit,
        ): ArchiveInstallResult {
            if (isCancelled()) return ArchiveInstallResult.Cancelled
            if (archive == null || !archive.isFile) return failure(Error.SOURCE_UNAVAILABLE, "The selected ghost archive is no longer available.")
            val root = try { installRoot?.canonicalFile } catch (_: IOException) { null }
            if (root == null || !root.isDirectory) return failure(Error.INSTALL_ROOT_INVALID, "Nanidroid cannot access its ghost storage.")
            val staging = File(root, STAGING_DIRECTORY)
            if ((!staging.exists() && !staging.mkdir()) || !staging.isDirectory) return failure(Error.STAGING_FAILED, "Nanidroid could not prepare a private install transaction.")
            val transaction = candidate(staging) ?: return failure(Error.STAGING_FAILED, "Nanidroid could not prepare a private install transaction.")
            var candidate: File? = null
            var session: NarVerifiedInstallSession? = null
            var result: ArchiveInstallResult = failure(Error.STAGING_FAILED, "Nanidroid could not complete the install transaction.")
            try {
                onProgress("Copying archive", 0L)
                if (isCancelled()) return ArchiveInstallResult.Cancelled
                val copied = NarStagedSource.copy(archive, transaction, isCancelled) { completed ->
                    onProgress("Copying archive", completed)
                }
                if (!copied.isSuccess()) {
                    result = if (copied.getError() == NarStagedSourceCopyError.CANCELLED) ArchiveInstallResult.Cancelled
                    else failure(Error.STAGING_FAILED, "Nanidroid could not safely copy the selected ghost archive.")
                } else if (isCancelled()) {
                    result = ArchiveInstallResult.Cancelled
                } else {
                    onProgress("Preflighting archive", 0L)
                    if (isCancelled()) return ArchiveInstallResult.Cancelled
                    val validationIo = CancellableArchiveIo(isCancelled, onProgress)
                    val validated = NarInstallPlanValidator(validationIo)
                        .validateStaged(copied.getSource(), root, forcedId)
                    if (isCancelled()) {
                        result = ArchiveInstallResult.Cancelled
                    } else if (!validated.isSuccess()) {
                        result = failure(Error.ARCHIVE_REJECTED, archiveMessage(validated.error))
                    } else {
                        session = validated.getVerifiedSession()
                        if (isCancelled()) {
                            result = ArchiveInstallResult.Cancelled
                            return result
                        }
                        val plan = validated.plan!!
                        val target = plan.targetDirectory
                        val rootEntries = root.listFiles()
                        if (rootEntries == null) {
                            result = failure(Error.INSTALL_ROOT_INVALID, "Nanidroid cannot access its ghost storage.")
                        } else if (hasLogicalTargetName(rootEntries, plan.descriptor.getTargetId())) {
                            result = failure(Error.TARGET_EXISTS, "This ghost is already installed. Remove it before installing a new copy.")
                        } else {
                            candidate = File(transaction, "tree").takeIf { it.mkdir() }
                            result = if (candidate == null) {
                                failure(Error.STAGING_FAILED, "Nanidroid could not prepare the new ghost files.")
                            } else {
                                extractAndPublish(
                                    session!!,
                                    plan,
                                    candidate,
                                    target,
                                    fileOperations,
                                    isCancelled,
                                    onProgress,
                                )
                            }
                        }
                        closeQuietly(session)
                        session = null
                    }
                }
            } finally {
                if (session != null) closeQuietly(session)
                recoverOwnedStaging(candidate, transaction, staging)
            }
            return result
        }

        private fun extractAndPublish(
            session: NarVerifiedInstallSession,
            plan: NarInstallPlan,
            candidate: File,
            target: File,
            fileOperations: FileOperations,
            isCancelled: () -> Boolean,
            onProgress: (phase: String, completed: Long) -> Unit,
        ): ArchiveInstallResult {
            val total = longArrayOf(0L)
            onProgress("Extracting archive", 0L)
            try {
                for (entry in plan.entries) {
                    if (isCancelled()) return ArchiveInstallResult.Cancelled
                    if (!entry.isInstallEntry) continue
                    val output = child(candidate, entry.relativePath!!) ?: return failure(Error.EXTRACTION_FAILED, "The ghost archive contains an unsafe file path.")
                    if (entry.isDirectory) {
                        if (!output.mkdirs() && !output.isDirectory) return failure(Error.EXTRACTION_FAILED, "Nanidroid could not create a ghost directory.")
                    } else when (copyEntry(session, entry, output, total, fileOperations, isCancelled, onProgress)) {
                        CopyEntryOutcome.COMPLETE -> Unit
                        CopyEntryOutcome.CANCELLED -> return ArchiveInstallResult.Cancelled
                        CopyEntryOutcome.FAILED -> return failure(Error.EXTRACTION_FAILED, "The ghost archive could not be extracted safely.")
                    }
                }
                session.close()
            } catch (_: IOException) {
                return failure(Error.EXTRACTION_FAILED, "The ghost archive could not be extracted safely.")
            } catch (_: RuntimeException) {
                return failure(Error.EXTRACTION_FAILED, "The ghost archive could not be extracted safely.")
            }
            if (!NarGhostDiscoverabilityValidator.validate(candidate)) {
                return failure(Error.ARCHIVE_REJECTED, "The ghost archive is invalid or exceeds Nanidroid's safety limits.")
            }
            onProgress("Preparing commit", total[0])
            if (isCancelled()) return ArchiveInstallResult.Cancelled
            onProgress("Publishing archive", total[0])
            if (isCancelled()) return ArchiveInstallResult.Cancelled
            val rootEntries = target.parentFile?.listFiles()
                ?: return failure(Error.INSTALL_ROOT_INVALID, "Nanidroid cannot access its ghost storage.")
            if (hasLogicalTargetName(rootEntries, plan.descriptor.getTargetId())) {
                return failure(Error.TARGET_EXISTS, "This ghost is already installed. Remove it before installing a new copy.")
            }
            val renamed = try {
                fileOperations.rename(candidate, target)
            } catch (_: Exception) {
                try {
                    target.isDirectory && !candidate.exists()
                } catch (_: Exception) {
                    false
                }
            }
            if (!renamed) return failure(Error.PUBLISH_FAILED, "The ghost files were prepared but could not be published. Please try again.")
            val installed = success(target, plan.descriptor.getTargetId())
            try {
                onProgress("Cleaning up", total[0])
            } catch (_: Exception) {
                // Publication is authoritative; exact staging recovery owns residue.
            }
            return installed
        }

        private fun copyEntry(
            session: NarVerifiedInstallSession,
            entry: NarInstallPlan.Entry,
            output: File,
            total: LongArray,
            fileOperations: FileOperations,
            isCancelled: () -> Boolean,
            onProgress: (phase: String, completed: Long) -> Unit,
        ): CopyEntryOutcome {
            val parent = output.parentFile!!
            if (!parent.exists() && !parent.mkdirs()) return CopyEntryOutcome.FAILED
            var input: InputStream? = null
            var target: FileOutputStream? = null
            var complete = false
            try {
                input = session.open(entry)
                target = fileOperations.openOutput(output)
                val buffer = ByteArray(BUFFER_SIZE)
                val crc = CRC32()
                var fileBytes = 0L
                while (true) {
                    if (isCancelled()) return CopyEntryOutcome.CANCELLED
                    var count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        val single = input.read()
                        if (single < 0) break
                        buffer[0] = single.toByte()
                        count = 1
                    }
                    fileBytes += count
                    total[0] += count
                    if (fileBytes > MAX_FILE_BYTES || total[0] > MAX_TOTAL_BYTES) return CopyEntryOutcome.FAILED
                    if (isCancelled()) return CopyEntryOutcome.CANCELLED
                    crc.update(buffer, 0, count)
                    target.write(buffer, 0, count)
                    onProgress("Extracting archive", total[0])
                }
                if (isCancelled()) return CopyEntryOutcome.CANCELLED
                target.fd.sync()
                complete = entry.declaredSize < 0 || fileBytes == entry.declaredSize
                if (complete && entry.crc >= 0) complete = crc.value == entry.crc
                return if (complete) CopyEntryOutcome.COMPLETE else CopyEntryOutcome.FAILED
            } catch (_: IOException) {
                return if (isCancelled()) CopyEntryOutcome.CANCELLED else CopyEntryOutcome.FAILED
            } finally {
                closeQuietly(input)
                closeQuietly(target)
                if (!complete) output.delete()
            }
        }

        @Throws(IOException::class)
        private fun child(root: File, path: String): File? {
            val child = File(root, path).canonicalFile
            return if (root == child.parentFile || child.path.startsWith(root.path + File.separator)) child else null
        }

        private fun candidate(staging: File): File? {
            val random = ByteArray(16)
            SecureRandom().nextBytes(random)
            val name = buildString {
                append("candidate-")
                for (value in random) append(String.format("%02x", value.toInt() and 0xff))
            }
            val candidate = File(staging, name)
            return candidate.takeIf { it.mkdir() }
        }

        private class CancellableArchiveIo(
            private val isCancelled: () -> Boolean,
            onProgress: (phase: String, completed: Long) -> Unit,
        ) : NarInstallPlanValidator.ArchiveIo {
            private val preflightProgress = ProgressCounter("Preflighting archive", onProgress)
            private val verificationProgress = ProgressCounter("Verifying archive", onProgress)

            override fun length(file: File): Long {
                checkCancellation()
                return file.length()
            }

            override fun openSource(file: File): InputStream {
                checkCancellation()
                return CancellableInputStream(FileInputStream(file), verificationProgress)
            }

            override fun preflight(file: File): Int {
                checkCancellation()
                RandomAccessFile(file, "r").use { random ->
                    val source = object : NarZipCentralPreflight.RandomAccessSource {
                        override fun length(): Long {
                            checkCancellation()
                            return random.length()
                        }

                        override fun readFully(
                            position: Long,
                            target: ByteArray,
                            offset: Int,
                            length: Int,
                        ) {
                            checkCancellation()
                            random.seek(position)
                            random.readFully(target, offset, length)
                            preflightProgress.advance(length.toLong())
                            checkCancellation()
                        }
                    }
                    return NarZipCentralPreflight.inspect(source).getEntryCount()
                }
            }

            override fun openArchive(file: File): NarInstallPlanValidator.OpenArchive {
                checkCancellation()
                return CancellableZipArchive(file)
            }

            override fun canonical(file: File): File {
                checkCancellation()
                return file.canonicalFile
            }

            override fun delete(file: File): Boolean = file.delete()

            private fun checkCancellation() {
                if (isCancelled()) throw InstallCancelledException()
            }

            private inner class CancellableInputStream(
                input: InputStream,
                private val progress: ProgressCounter?,
            ) : FilterInputStream(input) {
                override fun read(): Int {
                    checkCancellation()
                    val value = super.read()
                    if (value >= 0) progress?.advance(1L)
                    checkCancellation()
                    return value
                }

                override fun read(target: ByteArray, offset: Int, length: Int): Int {
                    checkCancellation()
                    val count = super.read(target, offset, length)
                    if (count > 0) progress?.advance(count.toLong())
                    checkCancellation()
                    return count
                }
            }

            private inner class CancellableZipArchive(file: File) :
                NarInstallPlanValidator.OpenArchive {
                private val zip = ZipFile(file)

                override fun entries(limit: Int): List<NarInstallPlanValidator.ArchiveEntry> {
                    val result = ArrayList<NarInstallPlanValidator.ArchiveEntry>()
                    val source = zip.entries()
                    var ordinal = 0
                    while (source.hasMoreElements() && result.size < limit) {
                        checkCancellation()
                        result += CancellableZipEntry(this, ordinal++, source.nextElement())
                    }
                    checkCancellation()
                    return result
                }

                override fun open(entry: NarInstallPlanValidator.ArchiveEntry): InputStream {
                    if (entry !is CancellableZipEntry || entry.owner !== this) {
                        throw IOException("foreign ZIP entry")
                    }
                    checkCancellation()
                    return CancellableInputStream(zip.getInputStream(entry.entry), null)
                }

                override fun close() = zip.close()
            }

            private class CancellableZipEntry(
                val owner: CancellableZipArchive,
                private val ordinal: Int,
                val entry: ZipEntry,
            ) : NarInstallPlanValidator.ArchiveEntry {
                override fun getOrdinal() = ordinal
                override fun getRawName() = entry.name
                override fun isDirectory() = entry.isDirectory
                override fun getCrc() = entry.crc
                override fun getMethod() = entry.method
                override fun getDeclaredSize() = entry.size
                override fun getCompressedSize() = entry.compressedSize
            }

            private class ProgressCounter(
                private val phase: String,
                private val onProgress: (phase: String, completed: Long) -> Unit,
            ) {
                private var completed = 0L

                fun advance(amount: Long) {
                    completed += amount
                    onProgress(phase, completed)
                }
            }

            private class InstallCancelledException : IOException("archive install cancelled")
        }

        private fun archiveMessage(error: NarInstallError?): String = when (error) {
            NarInstallError.UNSUPPORTED_TYPE,
            NarInstallError.UNSUPPORTED_REFRESH,
            NarInstallError.UNSUPPORTED_COMPOUND_INSTALL -> "This ghost update is incompatible with Nanidroid."
            else -> "This ghost archive is invalid or exceeds Nanidroid's safety limits."
        }

        private fun success(directory: File, targetId: String): ArchiveInstallResult =
            ArchiveInstallResult.Installed(directory.path, targetId)

        private fun failure(error: Error, message: String): ArchiveInstallResult =
            ArchiveInstallResult.Failed(message, error.toArchiveFailure())

        private fun legacy(result: ArchiveInstallResult): Result = when (result) {
            is ArchiveInstallResult.Installed -> Result.create(File(result.installedPath), result.targetId, null, "")
            is ArchiveInstallResult.Failed -> Result.create(null, null, result.failure.toError(), result.message)
            ArchiveInstallResult.Cancelled -> Result.create(null, null, Error.STAGING_FAILED, "The selected ghost archive install was cancelled.")
        }

        private fun Error.toArchiveFailure(): ArchiveInstallFailure = when (this) {
            Error.SOURCE_UNAVAILABLE -> ArchiveInstallFailure.SourceUnavailable
            Error.INSTALL_ROOT_INVALID -> ArchiveInstallFailure.StorageUnavailable
            Error.ARCHIVE_REJECTED -> ArchiveInstallFailure.InvalidArchive
            Error.TARGET_EXISTS -> ArchiveInstallFailure.TargetExists
            Error.STAGING_FAILED -> ArchiveInstallFailure.StagingFailed
            Error.EXTRACTION_FAILED -> ArchiveInstallFailure.ExtractionFailed
            Error.PUBLISH_FAILED -> ArchiveInstallFailure.PublishFailed
        }

        private fun ArchiveInstallFailure.toError(): Error = when (this) {
            ArchiveInstallFailure.SourceUnavailable -> Error.SOURCE_UNAVAILABLE
            ArchiveInstallFailure.StorageUnavailable -> Error.INSTALL_ROOT_INVALID
            ArchiveInstallFailure.InvalidArchive,
            ArchiveInstallFailure.ArchiveTooLarge -> Error.ARCHIVE_REJECTED
            ArchiveInstallFailure.TargetExists -> Error.TARGET_EXISTS
            ArchiveInstallFailure.StagingFailed -> Error.STAGING_FAILED
            ArchiveInstallFailure.ExtractionFailed -> Error.EXTRACTION_FAILED
            ArchiveInstallFailure.PublishFailed -> Error.PUBLISH_FAILED
        }

        private enum class CopyEntryOutcome { COMPLETE, CANCELLED, FAILED }

        private fun closeQuietly(value: NarVerifiedInstallSession?) { try { value?.close() } catch (_: Exception) { } }
        private fun closeQuietly(value: InputStream?) { try { value?.close() } catch (_: IOException) { } }
        private fun closeQuietly(value: FileOutputStream?) { try { value?.close() } catch (_: IOException) { } }
        internal fun hasLogicalTargetName(entries: Array<out File>, targetId: String): Boolean =
            entries.any { it.name.equals(targetId, ignoreCase = true) }

        private fun recoverOwnedStaging(candidate: File?, transaction: File, staging: File) {
            deleteOwnedTree(candidate)
            deleteOwnedTree(transaction)
            deleteOwnedTree(staging)
        }

        private fun deleteOwnedTree(file: File?) {
            if (file == null) return
            try {
                val path = file.toPath()
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attributes.isDirectory) {
                    Files.newDirectoryStream(path).use { children ->
                        children.forEach { deleteOwnedTree(it.toFile()) }
                    }
                }
                Files.deleteIfExists(path)
            } catch (_: Exception) {
                // Exact staging recovery owns any leftover transaction residue.
            }
        }

        private object RealFileOperations : FileOperations {
            override fun openOutput(file: File): FileOutputStream = FileOutputStream(file)
            override fun rename(source: File, destination: File): Boolean = try {
                source.renameTo(destination)
            } catch (_: SecurityException) {
                false
            }
        }

    }
}
