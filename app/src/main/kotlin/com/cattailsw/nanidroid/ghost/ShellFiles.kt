package com.cattailsw.nanidroid.ghost

import java.io.File
import java.nio.file.Files

/** Shell-directory confinement shared by surface, layer, and definition loading. */
object ShellFiles {
    /** Reads [file], failing with [message] once more than [limit] bytes arrive. */
    fun readAtMost(file: File, limit: Int, message: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(minOf(8192, limit + 1))
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                if (count < 0) break
                require(output.size() <= limit - count) { message }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    /** True when [file] is a regular, non-linked file directly inside a non-linked [shell]. */
    fun isDirectChild(shell: File, file: File): Boolean =
        !Files.isSymbolicLink(shell.toPath()) && !Files.isSymbolicLink(file.toPath()) &&
            file.isFile && file.canonicalFile.parentFile == shell.canonicalFile

    /** Resolve a relative shell asset without following any linked component. */
    fun resolve(shell: File, authoredName: String): File? {
        val normalized = authoredName.replace('\\', '/')
        if (normalized.isBlank() || normalized.startsWith('/') || ':' in normalized) return null
        val segments = normalized.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        if (!shell.isDirectory || Files.isSymbolicLink(shell.toPath())) return null
        var current = shell
        for (segment in segments) {
            current = File(current, segment)
            if (Files.isSymbolicLink(current.toPath())) return null
        }
        if (!current.isFile || !current.canonicalFile.toPath().startsWith(shell.canonicalFile.toPath())) return null
        val mask = File(current.parentFile, "${current.nameWithoutExtension}.pna")
        if (Files.isSymbolicLink(mask.toPath())) return null
        return current
    }
}
