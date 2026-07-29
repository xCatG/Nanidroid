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
            install(archive, installRoot, forcedId, RealFileOperations)

        /** Test seam for write and publication failures; callers retain the three-argument API. */
        @JvmStatic
        fun install(
            archive: File?,
            installRoot: File?,
            forcedId: String?,
            fileOperations: FileOperations
        ): Result = synchronized(INSTALL_LOCK) {
            installLocked(archive, installRoot, forcedId, fileOperations)
        }

        private fun installLocked(
            archive: File?,
            installRoot: File?,
            forcedId: String?,
            fileOperations: FileOperations
        ): Result {
            if (archive == null || !archive.isFile) return failure(Error.SOURCE_UNAVAILABLE, "The selected ghost archive is no longer available.")
            val root = try { installRoot?.canonicalFile } catch (_: IOException) { null }
            if (root == null || !root.isDirectory) return failure(Error.INSTALL_ROOT_INVALID, "Nanidroid cannot access its ghost storage.")
            val staging = File(root, STAGING_DIRECTORY)
            if ((!staging.exists() && !staging.mkdir()) || !staging.isDirectory) return failure(Error.STAGING_FAILED, "Nanidroid could not prepare a private install transaction.")
            val transaction = candidate(staging) ?: return failure(Error.STAGING_FAILED, "Nanidroid could not prepare a private install transaction.")
            var candidate: File? = null
            var session: NarVerifiedInstallSession? = null
            var result = failure(Error.STAGING_FAILED, "Nanidroid could not complete the install transaction.")
            try {
                val copied = NarStagedSource.copy(archive, transaction)
                if (!copied.isSuccess()) {
                    result = failure(Error.STAGING_FAILED, "Nanidroid could not safely copy the selected ghost archive.")
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
                                extractAndPublish(session!!, plan, candidate, target, fileOperations)
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
            fileOperations: FileOperations
        ): Result {
            val total = longArrayOf(0L)
            try {
                for (entry in plan.entries) {
                    if (!entry.isInstallEntry) continue
                    val output = child(candidate, entry.relativePath!!) ?: return failure(Error.EXTRACTION_FAILED, "The ghost archive contains an unsafe file path.")
                    if (entry.isDirectory) {
                        if (!output.mkdirs() && !output.isDirectory) return failure(Error.EXTRACTION_FAILED, "Nanidroid could not create a ghost directory.")
                    } else if (!copyEntry(session, entry, output, total, fileOperations)) {
                        return failure(Error.EXTRACTION_FAILED, "The ghost archive could not be extracted safely.")
                    }
                }
                session.close()
            } catch (_: IOException) {
                return failure(Error.EXTRACTION_FAILED, "The ghost archive could not be extracted safely.")
            } catch (_: RuntimeException) {
                return failure(Error.EXTRACTION_FAILED, "The ghost archive could not be extracted safely.")
            }
            if (target.exists() || !fileOperations.rename(candidate, target)) return failure(Error.PUBLISH_FAILED, "The ghost files were prepared but could not be published. Please try again.")
            return success(target, plan.descriptor.getTargetId())
        }

        private fun copyEntry(
            session: NarVerifiedInstallSession,
            entry: NarInstallPlan.Entry,
            output: File,
            total: LongArray,
            fileOperations: FileOperations
        ): Boolean {
            val parent = output.parentFile!!
            if (!parent.exists() && !parent.mkdirs()) return false
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
                    if (fileBytes > MAX_FILE_BYTES || total[0] > MAX_TOTAL_BYTES) return false
                    crc.update(buffer, 0, count)
                    target.write(buffer, 0, count)
                }
                target.fd.sync()
                complete = entry.declaredSize < 0 || fileBytes == entry.declaredSize
                if (complete && entry.crc >= 0) complete = crc.value == entry.crc
                return complete
            } catch (_: IOException) {
                return false
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

        private fun success(directory: File?, targetId: String?): Result = Result.create(directory, targetId, null, "")
        private fun failure(error: Error?, message: String): Result = Result.create(null, null, error, message)

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
