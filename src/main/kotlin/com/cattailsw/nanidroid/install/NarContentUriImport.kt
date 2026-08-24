package com.cattailsw.nanidroid.install

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom

sealed class ArchiveInstallResult {
    data class Installed(val installedPath: String, val targetId: String? = null) : ArchiveInstallResult()
    data class Failed(val message: String, val failure: ArchiveInstallFailure) : ArchiveInstallResult()
    object Cancelled : ArchiveInstallResult()
}

sealed class ArchiveInstallFailure {
    object SourceUnavailable : ArchiveInstallFailure()
    object StorageUnavailable : ArchiveInstallFailure()
    object InvalidArchive : ArchiveInstallFailure()
    object TargetExists : ArchiveInstallFailure()
    object StagingFailed : ArchiveInstallFailure()
    object ExtractionFailed : ArchiveInstallFailure()
    object PublishFailed : ArchiveInstallFailure()
    object ArchiveTooLarge : ArchiveInstallFailure()
}

/** Copies a one-shot picker URI into private storage before a transactional install. */
class NarContentUriImport private constructor() {
    data class Result(val installedPath: String?, val message: String) { val isSuccess get() = installedPath != null }

    companion object {
        internal const val MAX_ARCHIVE_BYTES = 544L * 1024L * 1024L
        private const val BUFFER_SIZE = 8192

        /** Stages a one-shot document opened by the platform picker. */
        @JvmStatic fun importContent(
            scheme: String?, cacheDir: File?, open: () -> InputStream?, install: (File) -> String?
        ): Result = when (val result = importContent(
            scheme, cacheDir, open,
            install = { staged: File -> install(staged)?.let { ArchiveInstallResult.Installed(it) }
                ?: ArchiveInstallResult.Failed("Nanidroid could not install the selected ghost.", ArchiveInstallFailure.InvalidArchive) },
            isCancelled = { false },
        )) {
            is ArchiveInstallResult.Installed -> Result(result.installedPath, "")
            is ArchiveInstallResult.Failed -> Result(null, result.message)
            ArchiveInstallResult.Cancelled -> Result(null, "The selected document import was cancelled.")
        }

        @JvmStatic fun importContent(
            scheme: String?,
            cacheDir: File?,
            open: () -> InputStream?,
            install: (File) -> ArchiveInstallResult,
            isCancelled: () -> Boolean,
            maximumArchiveBytes: Long = MAX_ARCHIVE_BYTES,
            deleteStaged: (File) -> Unit = { it.delete() },
        ): ArchiveInstallResult {
            if (scheme != "content") return failed("Choose a document from the system picker.", ArchiveInstallFailure.SourceUnavailable)
            val root = cacheDir ?: return failed("Nanidroid cannot prepare private import storage.", ArchiveInstallFailure.StorageUnavailable)
            if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) return failed("Nanidroid cannot prepare private import storage.", ArchiveInstallFailure.StorageUnavailable)
            if (maximumArchiveBytes < 0) return failed("Nanidroid could not prepare the selected document.", ArchiveInstallFailure.StagingFailed)
            val staged = File(root, "nar-import-${randomName()}.zip")
            return try {
                if (isCancelled()) return ArchiveInstallResult.Cancelled
                val input = open() ?: return failed("The selected document is no longer available.", ArchiveInstallFailure.SourceUnavailable)
                input.use { source ->
                    FileOutputStream(staged).use { target ->
                        when (copyBounded(source, target, maximumArchiveBytes, isCancelled)) {
                            CopyOutcome.COMPLETE -> Unit
                            CopyOutcome.CANCELLED -> return ArchiveInstallResult.Cancelled
                            CopyOutcome.TOO_LARGE -> return failed("The selected document exceeds Nanidroid's archive size limit.", ArchiveInstallFailure.ArchiveTooLarge)
                        }
                    }
                }
                if (isCancelled()) ArchiveInstallResult.Cancelled else install(staged)
            } catch (_: IOException) {
                failed("Nanidroid could not read the selected document.", ArchiveInstallFailure.SourceUnavailable)
            } catch (_: SecurityException) {
                failed("Nanidroid cannot read the selected document.", ArchiveInstallFailure.SourceUnavailable)
            } finally {
                try {
                    deleteStaged(staged)
                } catch (_: Exception) {
                    // Publication is authoritative; owned staging recovery retries cleanup.
                }
            }
        }

        private fun copyBounded(
            source: InputStream,
            target: FileOutputStream,
            maximumArchiveBytes: Long,
            isCancelled: () -> Boolean,
        ): CopyOutcome {
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                if (isCancelled()) return CopyOutcome.CANCELLED
                val count = source.read(buffer)
                if (count < 0) return CopyOutcome.COMPLETE
                if (count == 0) continue
                if (count > maximumArchiveBytes - total) return CopyOutcome.TOO_LARGE
                target.write(buffer, 0, count)
                total += count
            }
        }

        private fun failed(message: String, failure: ArchiveInstallFailure) =
            ArchiveInstallResult.Failed(message, failure)

        private enum class CopyOutcome { COMPLETE, CANCELLED, TOO_LARGE }
        private fun randomName(): String { val bytes = ByteArray(12); SecureRandom().nextBytes(bytes); return bytes.joinToString("") { "%02x".format(it) } }
    }
}
