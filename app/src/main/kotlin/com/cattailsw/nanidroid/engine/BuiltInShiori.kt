package com.cattailsw.nanidroid.engine

class BuiltInShiori(content: String) : ShioriEngine {
    private val scripts: Map<String, String> = buildMap {
        content.removePrefix("\uFEFF").split(Regex("\r\n|\n")).forEach { line ->
            if (line.isEmpty() || line.startsWith(';')) return@forEach
            val comma = line.indexOf(',')
            if (comma <= 0) return@forEach
            put(line.substring(0, comma), line.substring(comma + 1))
        }
    }

    override suspend fun request(event: ShioriEvent): ShioriReply {
        val script = scripts[event.id] ?: if (event.id == "OnClose") {
            "\\0\\s0Thank you.\\e"
        } else {
            return ShioriReply(204)
        }
        val value = if (event.id == "OnGhostChanging" || event.id == "OnGhostChanged") {
            script.replace("%1\$s", event.references.firstOrNull().orEmpty())
        } else script
        return ShioriReply(200, value)
    }
}
