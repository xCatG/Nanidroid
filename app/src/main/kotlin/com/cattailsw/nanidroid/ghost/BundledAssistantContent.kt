package com.cattailsw.nanidroid.ghost

import java.io.InputStream

class BundledAssistantContent private constructor(private val entries: Map<String, String>) {
    fun script(eventId: String): String? = entries[eventId]

    fun label(key: String): String = entries.getValue(key)

    companion object {
        private const val MAX_LOCALE_BYTES = 64 * 1024
        private val REQUIRED_KEYS = setOf(
            "OnFirstBoot", "OnBoot", "OnNanidroidHelp", "OnNanidroidIntro",
            "OnNanidroidImportHelp", "OnNanidroidInteractionHelp", "OnNanidroidTools",
            "OnNanidroidToolsDebug", "OnNanidroidDismiss",
            "DebugToolsTitle", "ShowTouchBounds", "Close",
        )

        fun load(openAsset: (String) -> InputStream, language: String): BundledAssistantContent {
            val locale = if (language == "en") "en" else "ja"
            val text = openAsset("assistant/$locale/content.txt").use { input ->
                // Read at most one byte beyond the limit, without an unbounded readBytes allocation.
                val bytes = ByteArray(MAX_LOCALE_BYTES + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read == -1) break
                    count += read
                }
                require(count <= MAX_LOCALE_BYTES) { "Assistant content exceeds 64 KiB" }
                String(bytes, 0, count, Charsets.UTF_8)
            }
            val entries = buildMap {
                text.removePrefix("\uFEFF").split(Regex("\r\n|\n")).forEach { line ->
                    if (line.isEmpty() || line.startsWith(';')) return@forEach
                    val comma = line.indexOf(',')
                    if (comma <= 0) return@forEach
                    put(line.substring(0, comma), line.substring(comma + 1))
                }
            }
            require(REQUIRED_KEYS.all { entries[it]?.isNotBlank() == true }) {
                "Assistant content is missing a required script or label"
            }
            return BundledAssistantContent(entries)
        }
    }
}
