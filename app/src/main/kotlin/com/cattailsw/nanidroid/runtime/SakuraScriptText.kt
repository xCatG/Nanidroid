package com.cattailsw.nanidroid.runtime

/** Serializes substituted data as text, protecting both script tags and later name macros. */
internal object SakuraScriptText {
    fun escape(value: String): String = buildString {
        value.forEach { character ->
            if (character == '\\' || character == '%') append('\\')
            append(character)
        }
    }

    fun isEscape(script: String, index: Int): Boolean = script[index] == '\\' &&
        script.getOrNull(index + 1).let { it == '\\' || it == '%' }
}
