package com.cattailsw.nanidroid

import java.io.File
import java.nio.charset.Charset
import java.util.Hashtable

/** Parses a ghost descriptor using its declared character set. */
class DescReader(private val path: String) {

    private fun charsetForFirstLine(firstLine: String?): Charset {
        if (firstLine == null) throw NullPointerException()
        val line = firstLine.removePrefix(UTF8_BOM)
        val charsetFields = line.split(",".toRegex())
        if (charsetFields.size != 2 || !charsetFields[0].contains("charset")) {
            return DEFAULT_CHARSET
        }
        return try {
            Charset.forName(charsetFields[1])
        } catch (_: Exception) {
            LegacyPlatform.debug(TAG, "trouble charset is:${charsetFields[1]}")
            DEFAULT_CHARSET
        }
    }

    private fun parseBytes(bytes: ByteArray, destination: MutableMap<String, String>) {
        if (bytes.isEmpty()) throw NullPointerException()
        val defaultLines = bytes.toString(DEFAULT_CHARSET).lineSequence().toList()
        val charset = charsetForFirstLine(defaultLines.firstOrNull())
        bytes.toString(charset).lineSequence().forEach { line ->
            val pair = line.split(",".toRegex())
            if (pair.size != 2) return@forEach
            destination[pair[0]] = pair[1]
        }
    }

    fun parse(): MutableMap<String, String> {
        val started = LegacyPlatform.uptimeMillis()
        val result = Hashtable<String, String>()
        File(path).inputStream().use { input -> parseBytes(input.readBytes(), result) }
        val elapsed = LegacyPlatform.uptimeMillis() - started
        LegacyPlatform.debug(TAG, "parsing took:${elapsed}ms")
        return result
    }

    private companion object {
        const val UTF8_BOM = "\uFEFF"
        val DEFAULT_CHARSET: Charset = Charset.forName("Shift_JIS")
        const val TAG = "DescReader"
    }
}
