package com.cattailsw.nanidroid.ui

import com.cattailsw.nanidroid.runtime.DialogueToken
import java.net.URI

data class BalloonLink(val start: Int, val end: Int, val url: String)

object BalloonLinks {
    private val candidates = Regex("(?i)(?:https?://|mailto:)[^\\s。、！？，；：「」『』]+")
    private val trailing = ".,!?;:。！？，；：)]}」』"
    private val japaneseScripts = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
    )

    fun find(text: String): List<BalloonLink> = candidates.findAll(text).mapNotNull { match ->
        val start = match.range.first
        if (start > 0) {
            val previous = text.codePointBefore(start)
            var boundary = start
            while (boundary > 0 && text[boundary - 1] == 'ー') boundary--
            val japaneseAdjacent = boundary > 0 &&
                Character.UnicodeScript.of(text.codePointBefore(boundary)) in japaneseScripts
            if ((Character.isLetterOrDigit(previous) || previous == '_'.code) && !japaneseAdjacent)
                return@mapNotNull null
        }
        var end = match.range.last + 1
        while (end > start && text[end - 1] in trailing) end--
        val url = text.substring(start, end)
        if (isAllowed(url)) BalloonLink(start, end, url) else null
    }.toList()

    fun isAllowed(url: String): Boolean {
        if (url.isBlank() || url.any { it.isWhitespace() || Character.isISOControl(it) }) return false
        val uri = try { URI(url) } catch (_: Exception) { return false }
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> !uri.host.isNullOrBlank() && uri.rawUserInfo == null
            "mailto" -> !uri.schemeSpecificPart.isNullOrBlank() && uri.schemeSpecificPart.contains('@')
            else -> false
        }
    }

    /** Returns a readable error, or null after a successful launch. */
    fun open(token: DialogueToken, url: String, isCurrent: (DialogueToken) -> Boolean,
        launch: (String) -> Unit): String? {
        if (!isCurrent(token)) return null
        if (!isAllowed(url)) return "This link is not supported."
        return try {
            launch(url)
            null
        } catch (_: Exception) {
            "No app can open this link."
        }
    }
}
