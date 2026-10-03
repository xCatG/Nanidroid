package com.cattailsw.nanidroid.engine

import java.nio.charset.Charset

object ShioriCodec {
    fun encode(event: ShioriEvent, charset: Charset, satori: Boolean): ByteArray = buildString {
        append(event.method.name).append(" SHIORI/3.0\r\nSender: Nanidroid\r\n")
        if (satori) append("Charset: Shift_JIS\r\n")
        append("ID: ").append(event.id).append("\r\nSecurityLevel: local\r\n")
        event.references.forEachIndexed { index, reference ->
            append("Reference").append(index).append(": ").append(reference).append("\r\n")
        }
        append("\r\n")
    }.toByteArray(charset)

    fun decode(bytes: ByteArray, fallbackCharset: Charset): ShioriReply {
        // Protocol tokens are ASCII across supported wire encodings. Decode only the
        // framing first, then decode the complete response in its selected charset.
        val charset = declaredCharset(bytes) ?: fallbackCharset
        val lines = String(bytes, charset).split(Regex("\r\n|\n"))
        val status = Regex("^SHIORI/[0-9]+\\.[0-9]+ ([0-9]{3})(?:[ \\t].*)?$", RegexOption.IGNORE_CASE)
            .matchEntire(lines.firstOrNull().orEmpty().removePrefix("\uFEFF"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val value = if (status == 200) lines.drop(1).takeWhile { it.isNotEmpty() }
            .firstOrNull { it.startsWith("Value:", true) }
            ?.substringAfter(':')?.removePrefix(" ") else null
        return ShioriReply(status, value, charset.name())
    }

    fun declaredCharset(bytes: ByteArray): Charset? {
        val framing = String(bytes, Charsets.ISO_8859_1)
        val declared = framing.split(Regex("\r\n|\n")).drop(1).takeWhile { it.isNotEmpty() }
            .firstOrNull { it.startsWith("Charset:", true) }
            ?.substringAfter(':')?.trim()
        return declared?.let { runCatching { Charset.forName(it) }.getOrNull() }
    }
}
