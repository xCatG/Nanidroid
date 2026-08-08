package com.cattailsw.nanidroid.runtime.dialogue

import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptCommandParser.parseScope
import com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptCommandParser.readBracket
import com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptCommandParser.splitArguments

internal data class LegacyChoice(val label: String, val id: String)

/** Incremental, bracket-balanced SakuraScript tokenizer for dialogue-only state. */
object SakuraScriptTokenizer {
    @JvmStatic
    fun tokenize(
        script: String,
        onDiagnostic: (String) -> Unit = {},
    ): List<DialogueContent> = tokenizeInternal(script, false, onDiagnostic).contents

    /**
     * Projects a source prefix consumed by playback. Incomplete anchors remain
     * plain, progressively revealed text until their closing token is reached.
     */
    @JvmStatic
    fun tokenizeRevealed(script: String): List<DialogueContent> =
        tokenizeInternal(script, true) {}.contents

    internal fun remainingVisibleChoices(
        script: String,
        commandStart: Int,
        initialScope: Int,
    ): List<LegacyChoice> {
        val choices = mutableListOf<LegacyChoice>()
        var scope = initialScope
        var index = commandStart
        while (index < script.length) {
            if (script[index++] != '\\') continue
            if (index >= script.length) break
            when (script[index++]) {
                'h', '0' -> scope = 0
                'u', '1' -> scope = 1
                'p' -> {
                    val scopeResult = parseScope(script, index)
                    if (scopeResult != null) {
                        scope = scopeResult.first
                        index = scopeResult.second
                    } else if (script.getOrNull(index) == '[') {
                        val bracket = readBracket(script, index)
                        index = bracket?.nextIndex ?: resumeAfterMalformedCommand(script, index)
                    }
                }
                'q' -> {
                    val bracket = readBracket(script, index)
                    if (bracket == null) {
                        index = resumeAfterMalformedCommand(script, index)
                    } else {
                        index = bracket.nextIndex
                        val args = splitArguments(bracket.value)
                        if (scope < 2 && args.size >= 2) {
                            choices += LegacyChoice(args[0], args[1])
                        }
                    }
                }
            }
        }
        return choices
    }

    internal fun tokenizeInternal(
        script: String,
        allowIncompleteAnchorText: Boolean,
        onDiagnostic: (String) -> Unit,
    ): SakuraScriptTokenization {
        val segments = linkedMapOf<GhostSpeaker, MutableList<DialogueSegment>>()
        val interactions = mutableListOf<SakuraScriptInteraction>()
        var speaker = GhostSpeaker.SAKURA
        var sync = false
        var scope = 0
        var scopeDiagnosticEmitted = false
        var index = 0

        fun activeSegments(): MutableList<DialogueSegment> = segments.getOrPut(speaker) { mutableListOf() }
        fun appendVisible(target: MutableList<DialogueSegment>, value: String) {
            val previous = target.lastOrNull()
            if (previous is DialogueSegment.Text) {
                target[target.lastIndex] = DialogueSegment.Text(previous.value + value)
            } else {
                target += DialogueSegment.Text(value)
            }
        }
        fun emit(value: String) {
            if (value.isEmpty() || scope >= 2) return
            if (sync) {
                appendVisible(segments.getOrPut(GhostSpeaker.SAKURA) { mutableListOf() }, value)
                appendVisible(segments.getOrPut(GhostSpeaker.KERO) { mutableListOf() }, value)
            } else {
                appendVisible(activeSegments(), value)
            }
        }
        fun selectSpeaker(newSpeaker: GhostSpeaker, clearIfCurrent: Boolean = false) {
            if (speaker == newSpeaker) {
                if (clearIfCurrent) activeSegments() += DialogueSegment.SpeakerChangeClear
                return
            }
            speaker = newSpeaker
            activeSegments() += DialogueSegment.SpeakerChangeClear
        }
        fun emit(segment: DialogueSegment) {
            if (scope >= 2) return
            activeSegments() += segment
            if (!sync) return
            val mirror = when (segment) {
                DialogueSegment.NewLine -> DialogueSegment.NewLine
                is DialogueSegment.Anchor -> DialogueSegment.Text(segment.action.visibleLabel)
                is DialogueSegment.Choice -> DialogueSegment.Text(segment.action.visibleLabel)
                else -> null
            } ?: return
            val otherSpeaker = if (speaker == GhostSpeaker.SAKURA) GhostSpeaker.KERO else GhostSpeaker.SAKURA
            val target = segments.getOrPut(otherSpeaker) { mutableListOf() }
            if (mirror is DialogueSegment.Text) appendVisible(target, mirror.value) else target += mirror
        }
        fun recordChoice(action: DialogueAction, sourceEnd: Int) {
            if (scope >= 2) return
            interactions += SakuraScriptInteraction(sourceEnd, scope, speaker, action)
        }
        fun diagnostic(value: String) = onDiagnostic(value)

        while (index < script.length) {
            val character = script[index++]
            if (character != '\\') {
                emit(character.toString())
                continue
            }
            if (index >= script.length) {
                emit("\\")
                break
            }
            when (val command = script[index++]) {
                '\\' -> emit("\\")
                'h', '0' -> {
                    scope = 0
                    selectSpeaker(GhostSpeaker.SAKURA)
                }
                'u', '1' -> {
                    scope = 1
                    selectSpeaker(GhostSpeaker.KERO, clearIfCurrent = true)
                }
                'p' -> {
                    val scopeResult = parseScope(script, index)
                    if (scopeResult != null) {
                        scope = scopeResult.first
                        index = scopeResult.second
                        when (scope) {
                            0 -> selectSpeaker(GhostSpeaker.SAKURA)
                            1 -> selectSpeaker(GhostSpeaker.KERO)
                        }
                        if (scope >= 2 && !scopeDiagnosticEmitted) {
                            diagnostic("unsupported-scope:$scope")
                            scopeDiagnosticEmitted = true
                        }
                    } else {
                        if (script.getOrNull(index) == '[') {
                            val bracket = readBracket(script, index)
                            if (bracket != null) index = bracket.nextIndex
                            else index = resumeAfterMalformedCommand(script, index)
                        }
                        diagnostic("malformed-scope")
                    }
                }
                'q' -> {
                    val bracket = readBracket(script, index)
                    if (bracket == null) {
                        diagnostic("truncated-choice")
                        index = resumeAfterMalformedCommand(script, index)
                    } else {
                        index = bracket.nextIndex
                        val args = splitArguments(bracket.value)
                        if (args.size >= 2) {
                            val action = choice(args)
                            recordChoice(action, index)
                            emit(DialogueSegment.Choice(action))
                        } else diagnostic("malformed-choice")
                    }
                }
                '_' -> {
                    if (index >= script.length) continue
                    when (val underscoreCommand = script[index++]) {
                        'a' -> {
                            val bracket = readBracket(script, index)
                            if (bracket == null) {
                                diagnostic("truncated-anchor")
                                index = resumeAfterMalformedCommand(script, index)
                            } else {
                                index = bracket.nextIndex
                                val closing = findAnchorClosing(script, index)
                                if (closing < 0) {
                                    if (allowIncompleteAnchorText) {
                                        emit(flattenAnchorLabel(script.substring(index)))
                                        index = script.length
                                    } else {
                                        diagnostic("truncated-anchor")
                                        index = resumeAfterMalformedCommand(script, index)
                                    }
                                } else {
                                    val label = flattenAnchorLabel(script.substring(index, closing))
                                    index = closing + 3
                                    val args = splitArguments(bracket.value)
                                    if (args.isNotEmpty()) emit(DialogueSegment.Anchor(anchor(label, args)))
                                    else diagnostic("malformed-anchor")
                                }
                            }
                        }
                        'l', 'b', 'v', 'r' -> {
                            val bracket = readBracket(script, index)
                            if (bracket != null) index = bracket.nextIndex
                            else if (script.getOrNull(index) == '[') index = resumeAfterMalformedCommand(script, index)
                            diagnostic(
                                if (underscoreCommand == 'r') "unsupported-command:_r"
                                else "unsupported-presentation:_$underscoreCommand",
                            )
                        }
                        'w' -> {
                            val bracket = readBracket(script, index)
                            if (bracket != null) {
                                index = bracket.nextIndex
                                bracket.value.toLongOrNull()?.let { emit(DialogueSegment.Wait(it)) }
                            } else if (script.getOrNull(index) == '[') {
                                diagnostic("truncated-wait")
                                index = resumeAfterMalformedCommand(script, index)
                            }
                        }
                        'q' -> Unit
                        's' -> {
                            val bracket = readBracket(script, index)
                            if (bracket != null) {
                                index = bracket.nextIndex
                            } else if (script.getOrNull(index) == '[') {
                                index = resumeAfterMalformedCommand(script, index)
                            } else {
                                sync = !sync
                            }
                        }
                        else -> {
                            val bracket = readBracket(script, index)
                            if (bracket != null) index = bracket.nextIndex
                            else if (script.getOrNull(index) == '[') index = resumeAfterMalformedCommand(script, index)
                            diagnostic("unsupported-command:_$underscoreCommand")
                        }
                    }
                }
                'j' -> {
                    val bracket = readBracket(script, index)
                    if (bracket == null) {
                        diagnostic("truncated-url")
                        index = resumeAfterMalformedCommand(script, index)
                    }
                    else {
                        index = bracket.nextIndex
                        val uri = unquote(bracket.value)
                        if (uri.startsWith("http://") || uri.startsWith("https://")) {
                            emit(DialogueSegment.ExternalUrl(uri, uri))
                        } else diagnostic("unsupported-url:${uri.substringBefore(':')}")
                    }
                }
                '!' -> {
                    val bracket = readBracket(script, index)
                    if (bracket == null) {
                        diagnostic("truncated-inputbox")
                        val next = script.indexOf('\\', index)
                        if (next < 0) {
                            index = script.length
                        } else {
                            val prefix = script.substring(index, next)
                            val textStart = prefix.indexOfFirst { it.isWhitespace() }
                            if (textStart >= 0) emit(prefix.substring(textStart + 1))
                            index = next
                        }
                    } else {
                        index = bracket.nextIndex
                        handleExclaim(splitArguments(bracket.value), ::emit, ::diagnostic)
                    }
                }
                'n' -> {
                    val bracket = readBracket(script, index)
                    if (bracket != null) {
                        index = bracket.nextIndex
                        emit(DialogueSegment.NewLine)
                    }
                    else if (script.getOrNull(index) == '[') {
                        diagnostic("truncated-newline")
                        index = resumeAfterMalformedCommand(script, index)
                    } else emit(DialogueSegment.NewLine)
                }
                'w' -> {
                    val digit = script.getOrNull(index)?.digitToIntOrNull()
                    if (digit != null) {
                        index++
                        emit(DialogueSegment.Wait(digit * 50L))
                    }
                }
                'c' -> {
                    val bracket = readBracket(script, index)
                    if (bracket != null) index = bracket.nextIndex
                    else if (script.getOrNull(index) == '[') index = resumeAfterMalformedCommand(script, index)
                    else emit(DialogueSegment.Clear)
                }
                'e' -> break
                '4', '5', '6', 'v', '-' -> diagnostic("unsupported-presentation:$command")
                's', 'i', 'b', 'f', 'x' -> {
                    val bracket = readBracket(script, index)
                    if (bracket != null) index = bracket.nextIndex
                    else if (script.getOrNull(index) == '[') index = resumeAfterMalformedCommand(script, index)
                    else if (command in setOf('s', 'b')) index = consumeDirectDigit(script, index)
                }
                'r' -> {
                    val bracket = readBracket(script, index)
                    if (bracket != null) index = bracket.nextIndex
                    else if (script.getOrNull(index) == '[') index = resumeAfterMalformedCommand(script, index)
                    diagnostic("unsupported-command:r")
                }
                else -> {
                    val bracket = readBracket(script, index)
                    if (bracket != null) index = bracket.nextIndex
                    else if (script.getOrNull(index) == '[') index = resumeAfterMalformedCommand(script, index)
                    diagnostic("unsupported-command:$command")
                }
            }
        }
        return SakuraScriptTokenization(
            contents = segments.map { (owner, values) -> DialogueContent(owner, values.toList()) },
            interactions = interactions.toList(),
        )
    }

    private val DialogueAction.visibleLabel: String
        get() = when (this) {
            is DialogueAction.Normal -> label
            is DialogueAction.DirectEvent -> label
            is DialogueAction.Script -> label
        }

    private val AnchorAction.visibleLabel: String
        get() = when (this) {
            is AnchorAction.Normal -> label
            is AnchorAction.DirectEvent -> label
        }

    private fun choice(args: List<String>): DialogueAction {
        val label = args[0]
        val target = args[1]
        return when {
            target.startsWith("On") -> DialogueAction.DirectEvent(label, target, args.drop(2))
            target.startsWith("script:") -> DialogueAction.Script(label, target.removePrefix("script:"))
            else -> DialogueAction.Normal(label, target, args.drop(2))
        }
    }

    private fun anchor(label: String, args: List<String>): AnchorAction {
        val target = args.first()
        return if (target.startsWith("On")) {
            AnchorAction.DirectEvent(label, target, args.drop(1))
        } else {
            AnchorAction.Normal(label, target, args.drop(1))
        }
    }

    private fun handleExclaim(
        args: List<String>,
        emit: (DialogueSegment) -> Unit,
        diagnostic: (String) -> Unit,
    ) {
        if (args.size == 2 && args[0] in setOf("enter", "leave") && args[1] == "passivemode") {
            emit(DialogueSegment.PassiveMode(args[0] == "enter"))
            return
        }
        if (args.size >= 3 && args[0] == "open" && args[1] == "inputbox") {
            emit(DialogueSegment.InputBox(inputBox(args.drop(2))))
            return
        }
        if (args.firstOrNull() in setOf("enter", "leave")) {
            diagnostic("malformed-passive")
        } else {
            diagnostic("unsupported-command:${args.firstOrNull() ?: "!"}")
        }
    }

    private fun inputBox(args: List<String>): InputBoxSpec {
        val id = args.firstOrNull().orEmpty()
        var cursor = 1
        var timeout: Long? = null
        var initial = ""
        args.getOrNull(cursor)?.let { positional ->
            when {
                positional.isEmpty() -> cursor++
                positional.toLongOrNull() != null -> {
                    timeout = positional.toLong()
                    cursor++
                }
            }
        }
        args.getOrNull(cursor)?.takeUnless { it.startsWith("--") }?.let { positional ->
            initial = positional
            cursor++
        }
        var supplement = ""
        val options = linkedSetOf<InputBehavior>()
        val references = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        args.drop(cursor).forEach { value ->
            when {
                value.startsWith("--timeout=") -> value.removePrefix("--timeout=").toLongOrNull()?.let {
                    timeout = it
                } ?: unknown.add(value)
                value.startsWith("--text=") -> initial = value.removePrefix("--text=")
                value.startsWith("--supplement=") -> supplement = value.removePrefix("--supplement=")
                value.startsWith("--reference=") -> references += value.removePrefix("--reference=")
                value.startsWith("--option=") -> when (val option = value.removePrefix("--option=")) {
                    "password" -> options += InputBehavior.PASSWORD
                    "multiline" -> options += InputBehavior.MULTILINE
                    "noempty" -> options += InputBehavior.NO_EMPTY
                    "nocancel" -> options += InputBehavior.NO_CANCEL
                    else -> unknown += option
                }
                else -> unknown += value
            }
        }
        val dispatch = if (id.startsWith("On")) InputDispatch.DirectEvent(id) else InputDispatch.Normal(id)
        return InputBoxSpec(dispatch, timeout, initial, options, supplement, references, unknown)
    }

    private fun consumeDirectDigit(script: String, start: Int): Int =
        if (script.getOrNull(start)?.isDigit() == true) start + 1 else start

    private fun resumeAfterMalformedCommand(script: String, start: Int): Int =
        script.indexOf('\\', start).takeIf { it >= 0 } ?: script.length

    private fun findAnchorClosing(script: String, start: Int): Int {
        var index = start
        while (index < script.length - 2) {
            if (script[index] == '\\') {
                if (script.getOrNull(index + 1) == '\\') {
                    index += 2
                } else if (script.getOrNull(index + 1) == '_' && script.getOrNull(index + 2) == 'a') {
                    return index
                } else index++
            } else index++
        }
        return -1
    }

    /** Labels are visible text only; formatting controls cannot create nested actions. */
    private fun flattenAnchorLabel(label: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < label.length) {
            val character = label[index++]
            if (character != '\\' || index >= label.length) {
                result.append(character)
                continue
            }
            when (val command = label[index++]) {
                '\\' -> result.append('\\')
                'n' -> {
                    val bracket = readBracket(label, index)
                    if (bracket != null) {
                        index = bracket.nextIndex
                        result.append('\n')
                    } else if (label.getOrNull(index) == '[') {
                        index = resumeAfterMalformedCommand(label, index)
                    } else result.append('\n')
                }
                's', 'b' -> {
                    val bracket = readBracket(label, index)
                    if (bracket != null) index = bracket.nextIndex
                    else index = consumeDirectDigit(label, index)
                }
                'i' -> {
                    val bracket = readBracket(label, index)
                    if (bracket != null) index = bracket.nextIndex
                    else if (label.getOrNull(index) == '[') index = resumeAfterMalformedCommand(label, index)
                }
                'p' -> {
                    val bracket = readBracket(label, index)
                    if (bracket != null) index = bracket.nextIndex
                    else if (label.getOrNull(index)?.isDigit() == true) index++
                    else if (label.getOrNull(index) == '[') index = resumeAfterMalformedCommand(label, index)
                }
                'f', 'x', 'r' -> {
                    val bracket = readBracket(label, index)
                    if (bracket != null) index = bracket.nextIndex
                    else if (label.getOrNull(index) == '[') index = resumeAfterMalformedCommand(label, index)
                }
                '_' -> {
                    val underscore = label.getOrNull(index++)
                    val bracket = readBracket(label, index)
                    if (bracket != null) index = bracket.nextIndex
                    else if (label.getOrNull(index) == '[') index = resumeAfterMalformedCommand(label, index)
                    if (underscore == null) break
                }
                'c', 'w', '!' -> {
                    val bracket = readBracket(label, index)
                    if (bracket != null) index = bracket.nextIndex
                    else if (command == 'w') index = consumeDirectDigit(label, index)
                    else if (label.getOrNull(index) == '[') index = resumeAfterMalformedCommand(label, index)
                }
                else -> {
                    val bracket = readBracket(label, index)
                    if (bracket != null) index = bracket.nextIndex
                    else if (label.getOrNull(index) == '[') index = resumeAfterMalformedCommand(label, index)
                }
            }
        }
        return result.toString()
    }

    private fun unquote(value: String): String = splitArguments(value).singleOrNull() ?: value
}
