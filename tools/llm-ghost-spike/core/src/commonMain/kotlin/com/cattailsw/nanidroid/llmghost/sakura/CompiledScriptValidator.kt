package com.cattailsw.nanidroid.llmghost.sakura

import com.cattailsw.nanidroid.llmghost.generation.GeneratedDialogueValidator
import com.cattailsw.nanidroid.llmghost.generation.TrustedDialogue
import com.cattailsw.nanidroid.llmghost.model.GeneratedDialogue
import com.cattailsw.nanidroid.llmghost.model.GeneratedTurn
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId

data class CompiledScriptViolation(
    val code: String,
    val offset: Int,
    val detail: String,
)

data class CompiledScriptValidation(
    val dialogue: TrustedDialogue?,
    val violations: List<CompiledScriptViolation>,
)

object CompiledScriptValidator {
    fun validate(script: String): CompiledScriptValidation {
        val turns = mutableListOf<ParsedTurn>()
        var offset = 0

        while (offset < script.length) {
            if (script.startsWith(END_COMMAND, offset)) {
                return if (turns.isNotEmpty() && offset + END_COMMAND.length == script.length) {
                    validateDialogue(turns, offset)
                } else {
                    failure("invalid-grammar", offset, "The end command must occur exactly once at the end.")
                }
            }

            val turnOffset = offset
            val speaker = when {
                script.startsWith(SAKURA_SCOPE, offset) -> GhostSpeakerId.SAKURA
                script.startsWith(KERO_SCOPE, offset) -> GhostSpeakerId.KERO
                else -> return unexpectedCommand(script, offset, "A turn must begin with a scope command.")
            }
            offset += SAKURA_SCOPE.length

            val surface = parseIntegerCommand(script, offset, SURFACE_PREFIX)
                ?: return unexpectedCommand(script, offset, "A scope command must be followed by a surface command.")
            if (surface.value == null) {
                return failure("invalid-grammar", offset, "Surface must be a canonical non-negative integer.")
            }
            offset = surface.nextOffset

            val text = StringBuilder()
            while (offset < script.length && !script.startsWith(WAIT_PREFIX, offset)) {
                if (script[offset] != '\\') {
                    text.append(script[offset])
                    offset++
                } else if (script.startsWith(ESCAPED_BACKSLASH, offset)) {
                    text.append('\\')
                    offset += ESCAPED_BACKSLASH.length
                } else {
                    return unexpectedCommand(script, offset, "Only an escaped backslash is allowed inside text.")
                }
            }

            val wait = parseIntegerCommand(script, offset, WAIT_PREFIX)
                ?: return unexpectedCommand(script, offset, "Text must be followed by a wait command.")
            if (wait.value == null) {
                return failure("invalid-grammar", offset, "Wait must be a canonical non-negative integer.")
            }
            offset = wait.nextOffset
            turns += ParsedTurn(
                speaker = speaker,
                surface = surface.value,
                text = text.toString(),
                waitAfterMs = wait.value,
                offset = turnOffset,
            )

            if (offset >= script.length) {
                return failure("invalid-grammar", offset, "Script is missing its terminal end command.")
            }
            if (script.startsWith(END_COMMAND, offset)) {
                return if (offset + END_COMMAND.length == script.length) {
                    validateDialogue(turns, offset)
                } else {
                    failure("invalid-grammar", offset, "The end command must occur exactly once at the end.")
                }
            }
            if (!script.startsWith(SAKURA_SCOPE, offset) && !script.startsWith(KERO_SCOPE, offset)) {
                return unexpectedCommand(script, offset, "A wait command must be followed by a scope or end command.")
            }
        }

        return failure("invalid-grammar", offset, "Script is missing its terminal end command.")
    }

    private fun parseIntegerCommand(
        script: String,
        offset: Int,
        prefix: String,
    ): ParsedInteger? {
        if (!script.startsWith(prefix, offset)) return null
        val valueStart = offset + prefix.length
        val close = script.indexOf(']', valueStart)
        if (close < 0) return ParsedInteger(null, script.length)
        val token = script.substring(valueStart, close)
        val value = token.takeIf { CANONICAL_INTEGER.matches(it) }?.toIntOrNull()
        return ParsedInteger(value, close + 1)
    }

    private fun unexpectedCommand(
        script: String,
        offset: Int,
        grammarDetail: String,
    ): CompiledScriptValidation {
        val code = if (script.getOrNull(offset) == '\\' && !isCompilerCommand(script, offset)) {
            "unsupported-command"
        } else {
            "invalid-grammar"
        }
        val detail = if (code == "unsupported-command") {
            "Script contains a command that the trusted compiler cannot emit."
        } else {
            grammarDetail
        }
        return failure(code, offset, detail)
    }

    private fun isCompilerCommand(script: String, offset: Int): Boolean = COMPILER_COMMANDS.any {
        script.startsWith(it, offset)
    }

    private fun validateDialogue(
        turns: List<ParsedTurn>,
        terminalOffset: Int,
    ): CompiledScriptValidation {
        val generated = GeneratedDialogue(
            turns.map { turn ->
                GeneratedTurn(
                    speaker = turn.speaker.wireName(),
                    surface = turn.surface,
                    text = turn.text,
                    waitAfterMs = turn.waitAfterMs,
                )
            },
        )
        val intrinsicSurfaceAuthorization = turns
            .groupBy(ParsedTurn::speaker, ParsedTurn::surface)
            .mapValues { (_, surfaces) -> surfaces.toSet() }
        val validation = GeneratedDialogueValidator().validate(generated, intrinsicSurfaceAuthorization)
        if (validation.violations.isNotEmpty()) {
            return CompiledScriptValidation(
                dialogue = null,
                violations = validation.violations.map { violation ->
                    CompiledScriptViolation(
                        code = violation.code,
                        offset = violation.turnIndex?.let { turns[it].offset } ?: terminalOffset,
                        detail = violation.detail,
                    )
                },
            )
        }
        return CompiledScriptValidation(
            dialogue = validation.dialogue,
            violations = emptyList(),
        )
    }

    private fun failure(code: String, offset: Int, detail: String) = CompiledScriptValidation(
        dialogue = null,
        violations = listOf(CompiledScriptViolation(code, offset, detail)),
    )

    private data class ParsedInteger(
        val value: Int?,
        val nextOffset: Int,
    )

    private data class ParsedTurn(
        val speaker: GhostSpeakerId,
        val surface: Int,
        val text: String,
        val waitAfterMs: Int,
        val offset: Int,
    )

    private fun GhostSpeakerId.wireName(): String = when (this) {
        GhostSpeakerId.SAKURA -> "sakura"
        GhostSpeakerId.KERO -> "kero"
    }

    private const val SAKURA_SCOPE = "\\0"
    private const val KERO_SCOPE = "\\1"
    private const val SURFACE_PREFIX = "\\s["
    private const val WAIT_PREFIX = "\\_w["
    private const val END_COMMAND = "\\e"
    private const val ESCAPED_BACKSLASH = "\\\\"
    private val CANONICAL_INTEGER = Regex("(?:0|[1-9][0-9]*)")
    private val COMPILER_COMMANDS = listOf(
        SAKURA_SCOPE,
        KERO_SCOPE,
        SURFACE_PREFIX,
        WAIT_PREFIX,
        END_COMMAND,
        ESCAPED_BACKSLASH,
    )
}
