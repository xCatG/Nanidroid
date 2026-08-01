package com.cattailsw.nanidroid.install

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.zip.CRC32

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
            legacy(installLocked(archive, installRoot, forcedId, fileOperations, { false }))
        }

        @JvmStatic
        fun install(
            archive: File?,
            installRoot: File?,
            forcedId: String?,
            isCancelled: () -> Boolean,
        ): ArchiveInstallResult = install(archive, installRoot, forcedId, RealFileOperations, isCancelled)

        @JvmStatic
        fun install(
            archive: File?,
            installRoot: File?,
            forcedId: String?,
            fileOperations: FileOperations,
            isCancelled: () -> Boolean,
        ): ArchiveInstallResult = synchronized(INSTALL_LOCK) {
            installLocked(archive, installRoot, forcedId, fileOperations, isCancelled)
        }

        private fun installLocked(
            archive: File?,
            installRoot: File?,
            forcedId: String?,
            fileOperations: FileOperations,
            isCancelled: () -> Boolean,
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
                val copied = NarStagedSource.copy(archive, transaction, isCancelled)
                if (!copied.isSuccess()) {
                    result = if (copied.getError() == NarStagedSourceCopyError.CANCELLED) ArchiveInstallResult.Cancelled
                    else failure(Error.STAGING_FAILED, "Nanidroid could not safely copy the selected ghost archive.")
                } else if (isCancelled()) {
                    result = ArchiveInstallResult.Cancelled
                } else {
                    val validated = NarInstallPlanValidator().validateStaged(copied.getSource(), root, forcedId)
                    if (!validated.isSuccess()) {
                        result = failure(Error.ARCHIVE_REJECTED, archiveMessage(validated.error))
                    } else {
                        val plan = validated.plan!!
                        val target = plan.targetDirectory
                        if (target.exists()) {
                            closeQuietly(validated.getVerifiedSession())
                            result = failure(Error.TARGET_EXISTS, "This ghost is already installed. Remove it before installing a new copy.")
                        } else {
                            session = validated.getVerifiedSession()
                            candidate = File(transaction, "tree").takeIf { it.mkdir() }
                            result = if (candidate == null) {
                                failure(Error.STAGING_FAILED, "Nanidroid could not prepare the new ghost files.")
                            } else {
                                extractAndPublish(session!!, plan, candidate, target, fileOperations, isCancelled)
                            }
                            closeQuietly(session)
                            session = null
                        }
                    }
                }
            } finally {
                if (session != null) closeQuietly(session)
                if (candidate != null && candidate.exists()) deleteTree(candidate)
                if (transaction.exists()) deleteTree(transaction)
                if (staging.exists() && !staging.delete() && resultCleanupNeeded(staging)) {
                    // Candidate/session cleanup cannot turn a successful publication into a false negative.
                }
            }
            return result
        }

        private fun resultCleanupNeeded(staging: File): Boolean = staging.list() != null && staging.list()!!.isNotEmpty()

        private fun extractAndPublish(
            session: NarVerifiedInstallSession,
            plan: NarInstallPlan,
            candidate: File,
            target: File,
            fileOperations: FileOperations,
            isCancelled: () -> Boolean,
        ): ArchiveInstallResult {
            val total = longArrayOf(0L)
            try {
                for (entry in plan.entries) {
                    if (isCancelled()) return ArchiveInstallResult.Cancelled
                    if (!entry.isInstallEntry) continue
                    val output = child(candidate, entry.relativePath!!) ?: return failure(Error.EXTRACTION_FAILED, "The ghost archive contains an unsafe file path.")
                    if (entry.isDirectory) {
                        if (!output.mkdirs() && !output.isDirectory) return failure(Error.EXTRACTION_FAILED, "Nanidroid could not create a ghost directory.")
                    } else when (copyEntry(session, entry, output, total, fileOperations, isCancelled)) {
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
            if (isCancelled()) return ArchiveInstallResult.Cancelled
            if (target.exists() || !fileOperations.rename(candidate, target)) return failure(Error.PUBLISH_FAILED, "The ghost files were prepared but could not be published. Please try again.")
            return success(target, plan.descriptor.getTargetId())
        }

        private fun copyEntry(
            session: NarVerifiedInstallSession,
            entry: NarInstallPlan.Entry,
            output: File,
            total: LongArray,
            fileOperations: FileOperations,
            isCancelled: () -> Boolean,
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
                }
                target.fd.sync()
                complete = entry.declaredSize < 0 || fileBytes == entry.declaredSize
                if (complete && entry.crc >= 0) complete = crc.value == entry.crc
                return if (complete) CopyEntryOutcome.COMPLETE else CopyEntryOutcome.FAILED
            } catch (_: IOException) {
                return CopyEntryOutcome.FAILED
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

        private fun closeQuietly(value: NarVerifiedInstallSession?) { try { value?.close() } catch (_: Throwable) { } }
        private fun closeQuietly(value: InputStream?) { try { value?.close() } catch (_: IOException) { } }
        private fun closeQuietly(value: FileOutputStream?) { try { value?.close() } catch (_: IOException) { } }
        private fun deleteTree(file: File) { if (file.isDirectory) file.listFiles()?.forEach { deleteTree(it) }; file.delete() }

        private object RealFileOperations : FileOperations {
            override fun openOutput(file: File): FileOutputStream = FileOutputStream(file)
            override fun rename(source: File, destination: File): Boolean = source.renameTo(destination)
        }

    }
}
