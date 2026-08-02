package com.cattailsw.nanidroid.install

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.security.SecureRandom

/** Makes a temporary content grant durable before handing it to background work. */
object NarLocalArchiveStager {
    sealed class Result {
        data class Staged(val location: String) : Result()
        data class Failed(val message: String) : Result()
    }

    fun stage(directory: File, open: () -> InputStream?): Result {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            return Result.Failed("Nanidroid cannot prepare private import storage.")
        }
        val file = File(directory, "nar-local-${randomName()}.nar")
        val result = try {
            val input = open()
            if (input == null) Result.Failed("The selected document is no longer available.")
            else input.use { source ->
                FileOutputStream(file).use { target ->
                    val buffer = ByteArray(8192)
                    var copied = 0L
                    var exceededLimit = false
                    while (!exceededLimit) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (count > NarContentUriImport.MAX_ARCHIVE_BYTES - copied) {
                            exceededLimit = true
                        } else {
                            target.write(buffer, 0, count)
                            copied += count
                        }
                    }
                    if (exceededLimit) Result.Failed("The selected document exceeds Nanidroid's archive size limit.")
                    else Result.Staged(file.toURI().toString())
                }
            }
        } catch (_: Exception) {
            Result.Failed("Nanidroid could not read the selected document.")
        }
        if (result !is Result.Staged) file.delete()
        return result
    }

    fun discard(location: String) {
        runCatching { File(URI(location)).delete() }
    }

    private fun randomName(): String = ByteArray(12).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }
}
