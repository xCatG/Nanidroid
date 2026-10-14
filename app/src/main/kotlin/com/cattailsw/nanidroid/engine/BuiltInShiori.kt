package com.cattailsw.nanidroid.engine

import com.cattailsw.nanidroid.runtime.SakuraScriptText

class BuiltInShiori private constructor(
    content: String,
    private val bundledMode: Boolean,
    internal val developerCommandsEnabled: Boolean
) : ShioriEngine {
    constructor(content: String) : this(content, false, false)

    private data class Selector(val event: String, val reference3: Int?, val debug: Boolean)
    private val variants = mutableMapOf<Selector, String>()
    private val scripts: Map<String, String> = buildMap {
        dictionaryEntries(content).forEach { (key, script) ->
            val selector = selector(key)
            if (selector != null) variants[selector] = script else put(key, script)
        }
    }

    override suspend fun request(event: ShioriEvent): ShioriReply {
        if ('@' in event.id && selector(event.id) != null) return ShioriReply(204)
        val reference3 = event.references.getOrNull(3)?.let(::canonicalInt)
        val mouseEligible = event.id !in MOUSE_EVENTS ||
            (event.references.size >= 3 && (0..2).all { event.references[it].toIntOrNull() != null })
        val script = (
            if (mouseEligible) {
                reference3?.let { if (developerCommandsEnabled) variants[Selector(event.id, it, true)] else null }
                    ?: reference3?.let { variants[Selector(event.id, it, false)] }
                    ?: if (developerCommandsEnabled) variants[Selector(event.id, null, true)] else null
            } else {
                null
            }
            ) ?: scripts[event.id] ?: if (event.id == "OnClose" && !bundledMode) {
            "\\0\\s0Thank you.\\e"
        } else {
            return ShioriReply(204)
        }
        val value = if (event.id == "OnGhostChanging" || event.id == "OnGhostChanged") {
            script.replace("%1\$s", SakuraScriptText.escape(event.references.firstOrNull().orEmpty()))
        } else {
            script
        }
        return ShioriReply(200, value)
    }

    companion object {
        internal fun dictionaryEntries(content: String): Map<String, String> = buildMap {
            content.removePrefix("\uFEFF").split(Regex("\r\n|\n")).forEach { line ->
                if (line.isEmpty() || line.startsWith(';')) return@forEach
                val comma = line.indexOf(',')
                if (comma > 0) put(line.substring(0, comma), line.substring(comma + 1))
            }
        }
        private val MOUSE_EVENTS = setOf("OnMouseClick", "OnMouseDoubleClick", "OnMouseMove")
        private val SELECTOR = Regex("^([^@]+)(?:@r3=(0|[1-9][0-9]*))?(@debug)?$")
        private fun canonicalInt(value: String): Int? = value.toIntOrNull()
            ?.takeIf { it >= 0 && it.toString() == value }
        private fun selector(key: String): Selector? {
            val match = SELECTOR.matchEntire(key) ?: return null
            if (match.groupValues[2].isEmpty() && match.groupValues[3].isEmpty()) return null
            val reference = match.groupValues[2].takeIf { it.isNotEmpty() }
            return Selector(
                match.groupValues[1],
                reference?.let { canonicalInt(it) ?: return null },
                match.groupValues[3].isNotEmpty()
            )
        }
        fun bundled(content: String, debugToolsEnabled: Boolean): BuiltInShiori =
            BuiltInShiori(content, true, debugToolsEnabled)
    }
}
