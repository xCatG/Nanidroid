package com.cattailsw.nanidroid.engine

import com.cattailsw.nanidroid.ghost.GhostDescriptor
import java.io.File
import java.nio.file.Files
import java.io.FileInputStream

enum class EngineKind { BUILTIN, SATORI, KAWARI, YAYA, UNSUPPORTED }

object EngineSelector {
    private const val MAX_TOTAL_DLL_INSPECTION_BYTES = 32L * 1024 * 1024
    private const val MAX_DLL_CANDIDATES = 128
    private val marker = "yaya.dll".toByteArray(Charsets.US_ASCII)
    private val markerPrefix = IntArray(marker.size).apply {
        var matched = 0
        for (index in 1 until marker.size) {
            while (matched > 0 && marker[index] != marker[matched]) matched = this[matched - 1]
            if (marker[index] == marker[matched]) matched++
            this[index] = matched
        }
    }

    fun select(descriptor: GhostDescriptor): EngineKind {
        val master = descriptor.masterPath
        fun has(name: String): Boolean {
            if (name.isBlank() || name == "." || name == ".." ||
                name.any { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) return false
            val file = File(master, name)
            return file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                file.canonicalFile.toPath().startsWith(master.canonicalFile.toPath())
        }
        fun containsYayaMarker(file: File, byteLimit: Long): Boolean {
            return runCatching {
                FileInputStream(file).use { stream ->
                    val buffer = ByteArray(8192)
                    var matched = 0
                    var inspected = 0L
                    while (inspected < byteLimit) {
                        val count = stream.read(buffer, 0,
                            minOf(buffer.size.toLong(), byteLimit - inspected).toInt())
                        if (count < 0) break
                        inspected += count
                        for (index in 0 until count) {
                            while (matched > 0 && buffer[index] != marker[matched])
                                matched = markerPrefix[matched - 1]
                            if (buffer[index] == marker[matched]) matched++
                            if (matched == marker.size) return@runCatching true
                        }
                    }
                    false
                }
            }.getOrDefault(false)
        }
        fun discoversYaya(): Boolean {
            val declaration = descriptor.engineDeclaration ?: return false
            if (!declaration.endsWith(".dll", ignoreCase = true) || !hasSafeName(declaration)) return false
            if (has("yaya.txt")) return true
            var remaining = MAX_TOTAL_DLL_INSPECTION_BYTES
            return master.listFiles().orEmpty().asSequence()
                .filter { it.name.endsWith(".dll", ignoreCase = true) }
                .filter { has(it.name) && has(it.name.substringBeforeLast('.') + ".txt") }
                .take(MAX_DLL_CANDIDATES)
                .any {
                    val size = it.length()
                    if (size > remaining) false
                    else {
                        remaining -= size
                        containsYayaMarker(it, size)
                    }
                }
        }
        return when {
            descriptor.engineDeclaration == "Nanidroid" -> EngineKind.BUILTIN
            descriptor.engineDeclaration.equals("satori.dll", ignoreCase = true) -> EngineKind.SATORI
            descriptor.engineDeclaration.equals("shiori.dll", ignoreCase = true) && has("kawarirc.kis") -> EngineKind.KAWARI
            discoversYaya() -> EngineKind.YAYA
            else -> EngineKind.UNSUPPORTED
        }
    }

    private fun hasSafeName(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }
}
