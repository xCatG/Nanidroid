package com.cattailsw.nanidroid.ghost

import java.nio.charset.Charset

/** User-provided 2elf, LOBO, and Earthquake NAR descriptors range from 217 to 2624 bytes. */
const val GHOST_DESCRIPTOR_LIMIT = 64 * 1024

object DescriptorReader {
    /** `%selfname2` falls back to the main sakura name when the ghost declares none. */
    fun sakuraName2(fields: Map<String, String>): String =
        fields["sakura.name2"]?.takeIf { it.isNotBlank() } ?: fields["sakura.name"].orEmpty()

    fun read(bytes: ByteArray): Map<String, String> {
        val hasUtf8Bom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val data = if (hasUtf8Bom) bytes.copyOfRange(3, bytes.size) else bytes
        val defaultCharset = Charset.forName("windows-31j")
        val declared = data.toString(defaultCharset)
            .split(Regex("\r\n|\n"))
            .firstOrNull()
            ?.takeIf { it.startsWith("charset,", ignoreCase = true) }
            ?.substringAfter(',')
            ?.trim()
        val charset = declared?.let(Charset::forName) ?: if (hasUtf8Bom) Charsets.UTF_8 else defaultCharset
        return buildMap {
            data.toString(charset)
                .split(Regex("\r\n|\n")).forEach { line ->
                    if (line.isEmpty() || line.startsWith(';') || line.startsWith("//")) return@forEach
                    val comma = line.indexOf(',')
                    if (comma <= 0) return@forEach
                    put(line.substring(0, comma), line.substring(comma + 1))
                }
        }
    }
}
