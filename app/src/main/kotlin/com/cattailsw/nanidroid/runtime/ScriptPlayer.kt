package com.cattailsw.nanidroid.runtime

class ScriptPlayer(private val script: String) {
    private var cursor = 0
    private var speaker = 0
    private var sakura = SpeakerFrame(0, true, "", false)
    private var kero = SpeakerFrame(10, true, "", false)
    private var elapsed = 0L
    private var waitRemaining = 0L
    private var endPause = -1L
    private var pendingNewline = false
    private var skippingArgument = false
    private var skippingTagName = false
    private var deferredTag: Char? = null
    private var deferredSubtag: Char? = null
    private var deferredArgumentStart = 0
    private var quick = false
    private var sync = false
    private var input: InputRequest? = null
    private var nextKey = 1L
    private var ended = false

    fun resolveInput(key: Long): Boolean {
        if (input?.key != key) return false
        input = null
        return true
    }

    fun advanceBy(milliseconds: Long): PlaybackFrame {
        require(milliseconds >= 0)
        if (input == null && !ended) elapsed = saturatingAdd(elapsed, milliseconds)
        val advanced = mutableSetOf<Int>()
        val animations = mutableListOf<AnimationRequest>()
        val surfaces = mutableListOf<SurfaceChange>()
        var budget = MAX_SOURCE_CHARS_PER_CALL
        while (!ended && input == null && budget > 0) {
            if (skippingTagName) {
                val start = cursor
                val limit = minOf(script.length.toLong(), cursor.toLong() + budget).toInt()
                while (cursor < limit && script[cursor].isTagChar()) cursor++
                if (cursor < limit) {
                    skippingTagName = false
                    if (script[cursor] == '[') {
                        cursor++
                        skippingArgument = true
                        deferredArgumentStart = cursor
                        deferredTag = null
                    }
                } else if (cursor >= script.length) skippingTagName = false
                budget -= cursor - start
                continue
            }
            if (skippingArgument) {
                val start = cursor
                val limit = minOf(script.length.toLong(), cursor.toLong() + budget).toInt()
                while (cursor < limit && script[cursor] != ']') cursor++
                if (cursor < limit) {
                    val argument = if (deferredTag != null)
                        script.substring(deferredArgumentStart, cursor) else null
                    cursor++
                    skippingArgument = false
                    val tag = deferredTag
                    if (argument != null && tag != null)
                        applyTag(tag, deferredSubtag, argument, animations, surfaces)
                    deferredTag = null
                } else if (cursor >= script.length) {
                    skippingArgument = false
                    deferredTag = null
                }
                budget -= cursor - start
                continue
            }
            if (waitRemaining > 0) {
                val spent = minOf(elapsed, waitRemaining)
                elapsed -= spent
                waitRemaining -= spent
                if (waitRemaining > 0) break
            }
            if (endPause >= 0) {
                if (elapsed < endPause) break
                elapsed -= endPause
                endPause = -1
                ended = true
                if (sakura.choices.isEmpty()) sakura = sakura.copy(balloonVisible = false)
                if (kero.choices.isEmpty()) kero = kero.copy(balloonVisible = false)
                break
            }
            if (cursor >= script.length && !pendingNewline) {
                endPause = 1_000
                continue
            }
            if (pendingNewline || script[cursor] != '\\') {
                if (!quick && elapsed < 50) break
                if (!quick) elapsed -= 50
                val character = if (pendingNewline) '\n' else script[cursor++]
                pendingNewline = false
                budget--
                append(character, advanced)
                continue
            }
            val start = cursor
            if (budget < 2) break
            if (skipOversizedCommand(budget)) {
                budget = 0
                break
            }
            cursor++
            if (cursor >= script.length) {
                budget -= cursor - start
                continue
            }
            val tag = script[cursor++]
            if (tag != '_' && hasExtendedTagArgument(budget - (cursor - start))) consumeUnknownTail()
            else when (tag) {
                '0', 'h' -> selectSpeaker(0)
                '1', 'u' -> selectSpeaker(1)
                in DIGIT_ARGUMENT_TAGS -> applyTag(tag, null, argumentOrDigit(), animations, surfaces)
                'n', 'q', '!' -> applyTag(tag, null, bracketArgument(), animations, surfaces)
                'c' -> clearCurrent()
                'e' -> endPause = 1_000
                '_' -> parseUnderscore(animations, surfaces)
                '-', '4', '5', '6', 'v' -> Unit
                else -> consumeUnknownTail()
            }
            budget -= cursor - start
        }
        return PlaybackFrame(sakura, kero, ended, advanced, input, animations, surfaces)
    }

    private inline fun update(target: Int = speaker, change: (SpeakerFrame) -> SpeakerFrame) {
        if (target == 0) sakura = change(sakura) else kero = change(kero)
    }

    private fun targets(): List<Int> = if (sync) BOTH_SPEAKERS else listOf(speaker)

    private fun append(character: Char, advanced: MutableSet<Int>) {
        for (target in targets()) {
            advanced += target
            update(target) { it.copy(text = it.text + character, balloonVisible = true) }
        }
    }

    private fun hideBalloon(clearText: Boolean = false) = update {
        it.copy(text = if (clearText) "" else it.text, balloonVisible = false, choices = emptyList())
    }

    private fun selectSpeaker(target: Int) {
        if (speaker == target) return
        speaker = target
        if ((if (target == 0) sakura else kero).choices.isEmpty()) hideBalloon()
    }

    private fun parseSurface(raw: String, changes: MutableList<SurfaceChange>) {
        val id = raw.toIntOrNull() ?: return
        if ((if (speaker == 0) sakura else kero).surfaceId == id) return
        update { it.copy(surfaceId = id, visible = id != -1) }
        changes += SurfaceChange(sakura.surfaceId, kero.surfaceId)
    }

    private fun parseBalloon(raw: String) {
        if (raw.trim() == "-1") hideBalloon()
    }

    private fun clearCurrent() = hideBalloon(clearText = true)

    private fun parseChoice(raw: String) {
        val fields = raw.split(',')
        if (fields.size < 2 || fields[0].isEmpty()) return
        val choiceId = fields[1]
        if ('\u0001' in choiceId) {
            // Satori needs the complete payload in Reference0 to recover choice metadata.
            val parts = choiceId.split('\u0001')
            if (parts.size != 3 || parts[0].isEmpty() || parts[0].startsWith("On") ||
                !ScriptInput.isAllowed(parts[0]) || parts[1].isEmpty() || !ScriptInput.isAllowed(parts[1]) ||
                parts[2].isEmpty() || parts[2].any { it !in '0'..'9' }) return
        } else if (choiceId.isEmpty() || !ScriptInput.isAllowed(choiceId)) return
        val references = if (ChoiceItem.isEvent(choiceId)) fields.drop(2) else emptyList()
        if (references.any { !ScriptInput.isAllowed(it) }) return
        val key = nextKey++
        for (target in targets()) update(target) {
            it.copy(choices = it.choices + ChoiceItem(key, fields[0], choiceId, it.text.length, references),
                balloonVisible = true)
        }
    }

    private fun parseInput(raw: String) {
        val fields = raw.split(',', limit = 4)
        if (fields.size < 3 || fields[0] != "open" || fields[1] != "inputbox" || fields[2].isBlank()) return
        input = InputRequest(nextKey++, fields[2])
        elapsed = 0
    }

    private fun parseUnderscore(animations: MutableList<AnimationRequest>, surfaces: MutableList<SurfaceChange>) {
        if (cursor >= script.length) return
        when (val subtag = script[cursor++]) {
            'q' -> quick = !quick
            's' -> sync = !sync
            'w', 'b' -> applyTag('_', subtag, bracketArgument(), animations, surfaces)
            'n', 'V' -> Unit
            else -> consumeUnknownTail()
        }
    }

    private fun skipOversizedCommand(budget: Int): Boolean {
        val start = cursor
        val tag = script.getOrNull(start + 1) ?: return false
        if (tag in ARGUMENTLESS_TAGS) return false
        if (tag == '_' && budget < 3) return true
        if (tag in DIGIT_ARGUMENT_TAGS && script.getOrNull(start + 2)?.isDigit() == true && budget < 3) return true
        val maxEnd = minOf(script.length.toLong(), start.toLong() + budget).toInt()
        var probe = start + 2
        while (probe < maxEnd && script[probe].isTagChar()) probe++
        if (probe == maxEnd && probe < script.length &&
            (script[probe].isTagChar() || script[probe] == '[')) {
            if (tag in ARGUMENT_TAGS && script[probe] != '[') return false
            if (tag !in ARGUMENT_TAGS) {
                cursor = maxEnd
                skippingTagName = true
            }
            return true
        }
        if (script.getOrNull(probe) != '[' && tag in ARGUMENT_TAGS) return false
        if (script.getOrNull(probe) != '[') return probe - start > budget
        var close = probe + 1
        while (close < maxEnd && script[close] != ']') close++
        if (close < maxEnd) return close + 1 - start > budget
        cursor = minOf(script.length.toLong(), start.toLong() + budget).toInt()
        skippingArgument = true
        deferredTag = if (probe == start + 2 || tag == '_' && probe == start + 3) tag else null
        deferredSubtag = if (tag == '_') script.getOrNull(start + 2) else null
        deferredArgumentStart = probe + 1
        return true
    }

    /** Applies an argument-taking tag, whether its argument was read inline or across calls. */
    private fun applyTag(
        tag: Char,
        subtag: Char?,
        argument: String,
        animations: MutableList<AnimationRequest>,
        surfaces: MutableList<SurfaceChange>,
    ) {
        when (tag) {
            's' -> parseSurface(argument, surfaces)
            'i' -> argument.toIntOrNull()?.let { animations += AnimationRequest(speaker, it) }
            'b' -> parseBalloon(argument)
            'n' -> pendingNewline = true
            'w' -> waitRemaining = saturatingMultiply(argument.toLongOrNull()?.coerceAtLeast(0) ?: 0, 50)
            'q' -> parseChoice(argument)
            '!' -> parseInput(argument)
            '_' -> when (subtag) {
                'w' -> waitRemaining = argument.toLongOrNull()?.coerceAtLeast(0) ?: 0
                'b' -> parseBalloon(argument)
            }
        }
    }

    private fun hasExtendedTagArgument(budget: Int): Boolean {
        var probe = cursor
        val limit = minOf(script.length.toLong(), cursor.toLong() + budget).toInt()
        while (probe < limit && script[probe].isTagChar()) probe++
        return probe > cursor && script.getOrNull(probe) == '['
    }

    private fun consumeUnknownTail() {
        while (cursor < script.length && script[cursor].isTagChar()) cursor++
        bracketArgument()
    }

    private fun argumentOrDigit(): String = if (cursor < script.length && script[cursor] == '[') {
        bracketArgument()
    } else if (cursor < script.length && script[cursor].isDigit()) {
        script[cursor++].toString()
    } else ""

    private fun bracketArgument(): String {
        if (cursor >= script.length || script[cursor] != '[') return ""
        val start = ++cursor
        while (cursor < script.length && script[cursor] != ']') cursor++
        val result = script.substring(start, cursor)
        if (cursor < script.length) cursor++
        return result
    }

    companion object {
        private const val MAX_SOURCE_CHARS_PER_CALL = 4_096
        private const val ARGUMENTLESS_TAGS = "01huce-456v"
        private const val ARGUMENT_TAGS = "sibnwq!_"
        /** Argument tags that also accept a single unbracketed digit. */
        private const val DIGIT_ARGUMENT_TAGS = "siwb"
        private val BOTH_SPEAKERS = listOf(0, 1)
        private val MACROS = listOf("%selfname2", "%username", "%selfname", "%keroname")

        fun substituteNames(script: String, names: Map<String, String>): String = buildString {
            var index = 0
            while (index < script.length) {
                val macro = MACROS.firstOrNull { script.startsWith(it, index) }
                if (macro != null) {
                    append(names[macro.substring(1)] ?: macro)
                    index += macro.length
                } else append(script[index++])
            }
        }

        private fun Char.isTagChar(): Boolean = this in 'a'..'z' || this in 'A'..'Z' ||
            this in '0'..'9' || this == '_'
        private fun saturatingAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
        private fun saturatingMultiply(a: Long, b: Long): Long = if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
    }
}
