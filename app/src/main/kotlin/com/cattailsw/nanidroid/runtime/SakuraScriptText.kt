package com.cattailsw.nanidroid.runtime

/** Serializes substituted data as text, protecting both script tags and later name macros. */
internal object SakuraScriptText {
    private val ESCAPED = setOf('\\', '%', '[', ']', ',')
    fun escape(value: String): String = buildString {
        value.forEach { character ->
            if (character in ESCAPED) append('\\')
            append(character)
        }
    }

    fun isEscape(script: String, index: Int): Boolean = script[index] == '\\' &&
        script.getOrNull(index + 1) in ESCAPED

    /** Decode only after the parser has identified the surrounding data boundaries. */
    fun decode(value: String): String = if ('\\' !in value) value else buildString {
        var index = 0
        while (index < value.length) {
            if (isEscape(value, index)) index++
            append(value[index++])
        }
    }

    fun fields(value: String, limit: Int = Int.MAX_VALUE): List<String> = buildList {
        var start = 0
        var index = 0
        while (index < value.length) {
            if (isEscape(value, index)) index += 2
            else if (value[index] == ',' && size < limit - 1) {
                add(decode(value.substring(start, index)))
                start = ++index
            } else index++
        }
        add(decode(value.substring(start)))
    }
}
