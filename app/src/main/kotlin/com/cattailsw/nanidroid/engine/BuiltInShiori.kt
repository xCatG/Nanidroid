package com.cattailsw.nanidroid.engine

import com.cattailsw.nanidroid.runtime.SakuraScriptText

class BuiltInShiori private constructor(
    content: String,
    private val bundledMode: Boolean,
    internal val developerCommandsEnabled: Boolean,
    private val nanoSpike: (suspend () -> ShioriReply)? = null,
    private val classifyHelpQuestion: (suspend (String) -> HelpClassification)? = null
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
        if (developerCommandsEnabled && event.id == NANO_SPIKE_EVENT && nanoSpike != null) {
            return nanoSpike.invoke()
        }
        if (developerCommandsEnabled && classifyHelpQuestion != null) {
            if (event.id == ASK_HELP_EVENT) {
                return ShioriReply(200, "\\![open,inputbox,$ASK_HELP_INPUT]\\e")
            }
            if (event.id == "OnUserInput" && event.references.firstOrNull() == ASK_HELP_INPUT) {
                val question = event.references.getOrNull(1).orEmpty().trim()
                val classification = if (question.length in 1..300) {
                    classifyHelpQuestion(question)
                } else {
                    HelpClassification.Topic("UNKNOWN")
                }
                val value = when (classification) {
                    is HelpClassification.Downloading -> {
                        val message = when {
                            classification.percent != null ->
                                "Gemini Nano is downloading (${classification.percent}%). Please try again in a moment."
                            classification.startedNow ->
                                "Started downloading Gemini Nano. Please try again in a moment."
                            else ->
                                "Gemini Nano is still downloading. Please try again in a moment."
                        }
                        withChoice(
                            "\\0${SakuraScriptText.escape(message)}\\e",
                            "Ask Nanidroid",
                            ASK_HELP_EVENT
                        )
                    }

                    HelpClassification.TimedOut ->
                        withChoice(
                            "\\0${SakuraScriptText.escape("That took too long. Please try asking again.")}\\e",
                            "Ask Nanidroid",
                            ASK_HELP_EVENT
                        )

                    HelpClassification.Busy ->
                        withChoice(
                            "\\0${SakuraScriptText.escape(
                                "Gemini Nano is busy or cooling down right now. Please try again in a moment."
                            )}\\e",
                            "Ask Nanidroid",
                            ASK_HELP_EVENT
                        )

                    is HelpClassification.Topic -> {
                        val answer = scripts[HELP_TOPICS[classification.label]]
                        when {
                            answer == null ->
                                scripts["OnNanidroidHelp"]?.let {
                                    withChoice(it, "Ask Nanidroid", ASK_HELP_EVENT)
                                }
                            classification.label == "IMPORT" && scripts.containsKey(OPEN_GHOSTS_EVENT) ->
                                withChoice(answer, "Open Ghosts", OPEN_GHOSTS_EVENT)
                            else -> answer
                        }
                    }

                    HelpClassification.Unavailable, HelpClassification.Failed ->
                        scripts["OnNanidroidHelp"]?.let {
                            withChoice(it, "Ask Nanidroid", ASK_HELP_EVENT)
                        }
                }
                return if (value != null) ShioriReply(200, value) else ShioriReply(204)
            }
        }
        if ('@' in event.id && selector(event.id) != null) return ShioriReply(204)
        val reference3 = event.references.getOrNull(3)?.let(::canonicalInt)
        val mouseEligible = event.id !in MOUSE_EVENTS ||
            (event.references.size >= 3 && (0..2).all { event.references[it].toIntOrNull() != null })
        val script = (
            if (mouseEligible) {
                reference3?.let { if (developerCommandsEnabled) variants[Selector(event.id, it, true)] else null }
                    ?: reference3?.let { variants[Selector(event.id, it, false)] }
                    ?: if (developerCommandsEnabled) variants[Selector(event.id, null, true)] else null
            } else null
            ) ?: scripts[event.id] ?: if (event.id == "OnClose" && !bundledMode) {
            "\\0\\s0Thank you.\\e"
        } else {
            return ShioriReply(204)
        }
        val value = if (event.id == "OnGhostChanging" || event.id == "OnGhostChanged") {
            script.replace("%1\$s", SakuraScriptText.escape(event.references.firstOrNull().orEmpty()))
        } else script
        val openingHelp = event.id == "OnNanidroidHelp" ||
            (event.id == "OnMouseClick" && mouseEligible && reference3 == 0)
        val reply = if (openingHelp && developerCommandsEnabled && classifyHelpQuestion != null) {
            withChoice(value, "Ask Nanidroid", ASK_HELP_EVENT)
        } else value
        return ShioriReply(200, reply)
    }

    private fun withChoice(script: String, label: String, event: String): String =
        script.removeSuffix("\\e") + "\\n\\q[$label,$event]\\e"

    companion object {
        private const val ASK_HELP_EVENT = "OnNanidroidAsk"
        private const val ASK_HELP_INPUT = "nanidroid-ask-help"
        private const val OPEN_GHOSTS_EVENT = "OnNanidroidOpenGhosts"
        private val HELP_TOPICS = mapOf(
            "INTRO" to "OnNanidroidIntro",
            "IMPORT" to "OnNanidroidImportHelp",
            "CONTROLS" to "OnNanidroidInteractionHelp"
        )

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
                match.groupValues[1], reference?.let { canonicalInt(it) ?: return null },
                match.groupValues[3].isNotEmpty()
            )
        }
        internal const val NANO_SPIKE_EVENT = "OnNanidroidNanoSpike"

        fun bundled(
            content: String,
            debugToolsEnabled: Boolean,
            classifyHelpQuestion: (suspend (String) -> HelpClassification)? = null,
            nanoSpike: (suspend () -> ShioriReply)? = null
        ): BuiltInShiori = BuiltInShiori(content, true, debugToolsEnabled, nanoSpike, classifyHelpQuestion)
    }
}
