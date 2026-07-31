package com.cattailsw.nanidroid.compose

import java.io.File
import java.nio.charset.Charset

/**
 * Bounded document policy for installed ghosts.
 *
 * NAR readmes are displayed as decoded text, never interpreted as HTML.  This
 * intentionally keeps line breaks while excluding script, relative-file, and
 * embedded-browser behavior from untrusted archive content.
 */
internal object PlainTextDocument {
    private const val UTF8_BOM = "\uFEFF"

    fun read(file: File): String {
        val bytes = file.readBytes()
        val utf8 = bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        return bytes.toString(if (utf8) Charsets.UTF_8 else Charset.forName("Shift_JIS"))
            .removePrefix(UTF8_BOM)
    }

    /** Only explicit web and email links are actionable in the Compose reader. */
    val linkPattern = Regex("(?i)\\b(?:https?://|mailto:)[^\\s<>]+")
}
