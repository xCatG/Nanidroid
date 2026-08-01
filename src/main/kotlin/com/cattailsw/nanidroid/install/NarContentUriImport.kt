package com.cattailsw.nanidroid.install

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom

/** Copies a one-shot picker URI into private storage before a transactional install. */
class NarContentUriImport private constructor() {
    data class Result(val installedPath: String?, val message: String, val retryable: Boolean = false) { val isSuccess get() = installedPath != null }

    companion object {
        /** Stages a one-shot document opened by the platform picker. */
        @JvmStatic fun importContent(
            scheme: String?, cacheDir: File?, open: () -> InputStream?, install: (File) -> String?
        ): Result {
            if (scheme != "content") return Result(null, "Choose a document from the system picker.")
            return importStream(cacheDir, open, install = install)
        }

        /** Stages one untrusted stream in private storage before a transactional install. */
        @JvmStatic fun importStream(
            cacheDir: File?, open: () -> InputStream?, maxBytes: Long = Long.MAX_VALUE, install: (File) -> String?,
        ): Result {
            val root = cacheDir ?: return Result(null, "Nanidroid cannot prepare private import storage.")
            if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) return Result(null, "Nanidroid cannot prepare private import storage.")
            val staged = File(root, "nar-import-${randomName()}.zip")
            return try {
                val input = open() ?: return Result(null, "The selected document is no longer available.")
                input.use { source ->
                    FileOutputStream(staged).use { target ->
                        if (!copyWithinLimit(source, target, maxBytes)) {
                            return Result(null, "The selected archive exceeds Nanidroid's size limit.")
                        }
                    }
                }
                val installed = install(staged)
                if (installed == null) Result(null, "Nanidroid could not install the selected ghost.") else Result(installed, "")
            } catch (_: IOException) {
                Result(null, "Nanidroid could not read the selected document.", retryable = true)
        } catch (_: SecurityException) {
                Result(null, "Nanidroid cannot read the selected document.")
            } finally { staged.delete() }
        }

        private fun copyWithinLimit(source: InputStream, target: FileOutputStream, maxBytes: Long): Boolean {
            if (maxBytes < 0) return false
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val remaining = maxBytes - total
                val readLimit = if (remaining >= buffer.size) buffer.size else (remaining + 1).toInt()
                val count = source.read(buffer, 0, readLimit)
                if (count == -1) return true
                target.write(buffer, 0, minOf(count.toLong(), remaining).toInt())
                total += count
                if (total > maxBytes) return false
            }
        }
        private fun randomName(): String { val bytes = ByteArray(12); SecureRandom().nextBytes(bytes); return bytes.joinToString("") { "%02x".format(it) } }
    }
}
