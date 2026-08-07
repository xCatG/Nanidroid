package com.cattailsw.nanidroid.llmghost.generation

import com.cattailsw.nanidroid.llmghost.model.GeneratedDialogue
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId

data class TrustedTurn(
    val speaker: GhostSpeakerId,
    val surface: Int,
    val text: String,
    val waitAfterMs: Int,
)

data class TrustedDialogue(val turns: List<TrustedTurn>)

data class DialogueViolation(
    val code: String,
    val turnIndex: Int?,
    val detail: String,
)

data class DialogueValidationResult(
    val dialogue: TrustedDialogue?,
    val violations: List<DialogueViolation>,
)

class GeneratedDialogueValidator {
    fun validate(
        dialogue: GeneratedDialogue,
        validSurfaces: Map<GhostSpeakerId, Set<Int>>,
    ): DialogueValidationResult {
        val violations = mutableListOf<DialogueViolation>()
        if (dialogue.turns.size !in MIN_TURNS..MAX_TURNS) {
            violations += DialogueViolation(
                code = "turn-count",
                turnIndex = null,
                detail = "Dialogue must contain between 1 and 8 turns.",
            )
        }

        val trustedTurns = dialogue.turns.mapIndexed { index, turn ->
            val speaker = turn.speaker.toSpeakerId()
            if (speaker == null) {
                violations += DialogueViolation(
                    code = "unknown-speaker",
                    turnIndex = index,
                    detail = "Speaker must be exactly sakura or kero.",
                )
            } else if (turn.surface !in validSurfaces[speaker].orEmpty()) {
                violations += DialogueViolation(
                    code = "surface-not-allowed",
                    turnIndex = index,
                    detail = "Surface ${turn.surface} is not authorized for ${turn.speaker}.",
                )
            }

            validateText(turn.text, index, violations)
            if (turn.waitAfterMs !in MIN_WAIT_MS..MAX_WAIT_MS) {
                violations += DialogueViolation(
                    code = "wait-out-of-range",
                    turnIndex = index,
                    detail = "waitAfterMs must be between 0 and 2000.",
                )
            }

            speaker?.let {
                TrustedTurn(
                    speaker = it,
                    surface = turn.surface,
                    text = turn.text,
                    waitAfterMs = turn.waitAfterMs,
                )
            }
        }

        return DialogueValidationResult(
            dialogue = if (violations.isEmpty()) TrustedDialogue(trustedTurns.filterNotNull()) else null,
            violations = violations,
        )
    }

    private fun validateText(
        text: String,
        turnIndex: Int,
        violations: MutableList<DialogueViolation>,
    ) {
        if (text.isBlank()) {
            violations += DialogueViolation(
                code = "text-blank",
                turnIndex = turnIndex,
                detail = "Text must contain a visible character.",
            )
        }

        when (val scalarCount = countUnicodeScalars(text)) {
            null -> violations += DialogueViolation(
                code = "invalid-unicode",
                turnIndex = turnIndex,
                detail = "Text contains an unpaired UTF-16 surrogate.",
            )
            else -> if (scalarCount > MAX_TEXT_SCALARS) {
                violations += DialogueViolation(
                    code = "text-too-long",
                    turnIndex = turnIndex,
                    detail = "Text must contain at most 500 Unicode scalar values.",
                )
            }
        }

        if ('\\' in text) {
            violations += DialogueViolation(
                code = "forbidden-backslash",
                turnIndex = turnIndex,
                detail = "Backslashes are not allowed in generated text.",
            )
        }
        if (text.any { it.isControlCharacter() }) {
            violations += DialogueViolation(
                code = "forbidden-control",
                turnIndex = turnIndex,
                detail = "Control characters are not allowed in generated text.",
            )
        }
        if (URL_PATTERN.containsMatchIn(text)) {
            violations += DialogueViolation(
                code = "forbidden-url",
                turnIndex = turnIndex,
                detail = "URLs are not allowed in generated text.",
            )
        }
        if (SCRIPT_SCHEME_PATTERN.containsMatchIn(text)) {
            violations += DialogueViolation(
                code = "forbidden-script-scheme",
                turnIndex = turnIndex,
                detail = "script: payloads are not allowed in generated text.",
            )
        }
        if (CHOICE_PATTERN.containsMatchIn(text)) {
            violations += DialogueViolation(
                code = "forbidden-choice",
                turnIndex = turnIndex,
                detail = "Choice-like payloads are not allowed in generated text.",
            )
        }
    }

    private fun countUnicodeScalars(text: String): Int? {
        var count = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                in HIGH_SURROGATE_RANGE -> {
                    if (index + 1 >= text.length || text[index + 1] !in LOW_SURROGATE_RANGE) return null
                    index += 2
                }
                in LOW_SURROGATE_RANGE -> return null
                else -> index++
            }
            count++
        }
        return count
    }

    private fun Char.isControlCharacter(): Boolean =
        this in '\u0000'..'\u001F' || this in '\u007F'..'\u009F'

    private fun String.toSpeakerId(): GhostSpeakerId? = when (this) {
        "sakura" -> GhostSpeakerId.SAKURA
        "kero" -> GhostSpeakerId.KERO
        else -> null
    }

    private companion object {
        const val MIN_TURNS = 1
        const val MAX_TURNS = 8
        const val MIN_WAIT_MS = 0
        const val MAX_WAIT_MS = 2_000
        const val MAX_TEXT_SCALARS = 500
        val HIGH_SURROGATE_RANGE = '\uD800'..'\uDBFF'
        val LOW_SURROGATE_RANGE = '\uDC00'..'\uDFFF'
        val URL_PATTERN = Regex("(?:https?://|www\\.)", RegexOption.IGNORE_CASE)
        val SCRIPT_SCHEME_PATTERN = Regex("script\\s*:", RegexOption.IGNORE_CASE)
        val CHOICE_PATTERN = Regex("(?:\\([^()\\r\\n]*,[^()\\r\\n]*\\)|（[^（）\\r\\n]*,[^（）\\r\\n]*）)")
    }
}
