package com.cattailsw.nanidroid.runtime.dialogue

/** Shared SakuraScript command syntax for bracketed arguments and scope controls. */
internal object SakuraScriptCommandParser {
    data class Bracket(val value: String, val nextIndex: Int)

    fun readBracket(script: String, start: Int): Bracket? {
        if (script.getOrNull(start) != '[') return null
        val body = StringBuilder()
        var index = start + 1
        var depth = 1
        var quoted = false
        while (index < script.length) {
            val character = script[index++]
            if (character == '\\' && index < script.length) {
                val escaped = script[index++]
                body.append('\\').append(escaped)
                continue
            }
            if (character == '"') {
                if (quoted && script.getOrNull(index) == '"') {
                    body.append("\"\"")
                    index++
                } else {
                    quoted = !quoted
                    body.append(character)
                }
                continue
            }
            if (!quoted && character == '[') depth++
            if (!quoted && character == ']') {
                depth--
                if (depth == 0) return Bracket(body.toString(), index)
            }
            body.append(character)
        }
        return null
    }

    fun splitArguments(value: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character == '\\' && index < value.length) {
                val escaped = value[index++]
                if (escaped in setOf(']', '\\', ',')) current.append(escaped)
                else current.append('\\').append(escaped)
                continue
            }
            if (character == '"') {
                if (quoted && value.getOrNull(index) == '"') {
                    current.append('"')
                    index++
                } else quoted = !quoted
                continue
            }
            if (character == ',' && !quoted) {
                values += current.toString()
                current.setLength(0)
            } else current.append(character)
        }
        values += current.toString()
        return values
    }

    fun parseScope(script: String, start: Int): Pair<Int, Int>? {
        val direct = script.getOrNull(start)?.digitToIntOrNull()
        if (direct != null) return direct to start + 1
        val bracket = readBracket(script, start) ?: return null
        return bracket.value.toIntOrNull()?.let { it to bracket.nextIndex }
    }
}
