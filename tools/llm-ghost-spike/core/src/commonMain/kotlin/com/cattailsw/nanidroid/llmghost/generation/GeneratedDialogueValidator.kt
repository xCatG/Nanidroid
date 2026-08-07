package com.cattailsw.nanidroid.llmghost.generation

import com.cattailsw.nanidroid.llmghost.model.GeneratedDialogue
import com.cattailsw.nanidroid.llmghost.model.GeneratedTurn
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId

/**
 * A turn that has crossed the dialogue validation boundary.
 *
 * The constructor is internal so public common callers can obtain instances
 * only from [GeneratedDialogueValidator]. It is intentionally not a data
 * class: a public `copy` function would allow callers to bypass validation.
 */
class TrustedTurn internal constructor(
    val speaker: GhostSpeakerId,
    val surface: Int,
    val text: String,
    val waitAfterMs: Int,
) {
    override fun equals(other: Any?): Boolean = other is TrustedTurn &&
        speaker == other.speaker &&
        surface == other.surface &&
        text == other.text &&
        waitAfterMs == other.waitAfterMs

    override fun hashCode(): Int {
        var result = speaker.hashCode()
        result = 31 * result + surface
        result = 31 * result + text.hashCode()
        result = 31 * result + waitAfterMs
        return result
    }

    override fun toString(): String =
        "TrustedTurn(speaker=$speaker, surface=$surface, text=$text, waitAfterMs=$waitAfterMs)"
}

/**
 * Validated dialogue accepted by the trusted compiler.
 *
 * Its internal constructor and immutable turn snapshot prevent public common
 * callers from manufacturing or mutating trusted content.
 */
class TrustedDialogue internal constructor(turns: List<TrustedTurn>) {
    val turns: List<TrustedTurn> = turns.toList()

    override fun equals(other: Any?): Boolean = other is TrustedDialogue && turns == other.turns

    override fun hashCode(): Int = turns.hashCode()

    override fun toString(): String = "TrustedDialogue(turns=$turns)"
}

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
            } else if (turn.surface < 0 || turn.surface !in validSurfaces[speaker].orEmpty()) {
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

    internal fun validateTrusted(dialogue: TrustedDialogue): List<DialogueViolation> {
        val generated = GeneratedDialogue(
            dialogue.turns.map { turn ->
                GeneratedTurn(
                    speaker = turn.speaker.wireName(),
                    surface = turn.surface,
                    text = turn.text,
                    waitAfterMs = turn.waitAfterMs,
                )
            },
        )
        val intrinsicSurfaceAuthorization = dialogue.turns
            .groupBy(TrustedTurn::speaker, TrustedTurn::surface)
            .mapValues { (_, surfaces) -> surfaces.toSet() }
        return validate(generated, intrinsicSurfaceAuthorization).violations
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

        if (UnicodeSecurity.containsInvisibleFormat(text)) {
            violations += DialogueViolation(
                code = "forbidden-invisible-format",
                turnIndex = turnIndex,
                detail = "Default-ignorable, format, bidi, variation, and tag characters are not allowed.",
            )
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
        if (text.containsUrl()) {
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

    private fun GhostSpeakerId.wireName(): String = when (this) {
        GhostSpeakerId.SAKURA -> "sakura"
        GhostSpeakerId.KERO -> "kero"
    }

    private fun String.containsUrl(): Boolean {
        val hasUriScheme = URI_SCHEME_PATTERN.findAll(this).any { match ->
            !match.value.substringBefore(':').equals("script", ignoreCase = true)
        }
        return hasUriScheme ||
            BARE_DOMAIN_PATTERN.containsMatchIn(this) ||
            BARE_IPV4_PATTERN.containsMatchIn(this) ||
            LOCALHOST_PATH_PATTERN.containsMatchIn(this)
    }

    private companion object {
        const val MIN_TURNS = 1
        const val MAX_TURNS = 8
        const val MIN_WAIT_MS = 0
        const val MAX_WAIT_MS = 2_000
        const val MAX_TEXT_SCALARS = 500
        val HIGH_SURROGATE_RANGE = '\uD800'..'\uDBFF'
        val LOW_SURROGATE_RANGE = '\uDC00'..'\uDFFF'
        val URI_SCHEME_PATTERN = Regex(
            "\\b[a-z][a-z0-9+.-]{0,31}:(?://)?[^\\s]+",
            RegexOption.IGNORE_CASE,
        )
        val BARE_DOMAIN_PATTERN = Regex(
            "(?:^|[^a-z0-9_-])(?:[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?\\.)+" +
                "[a-z]{2,63}(?::[0-9]{1,5})?(?:/[^\\s]*)?",
            RegexOption.IGNORE_CASE,
        )
        val BARE_IPV4_PATTERN = Regex(
            "(?:^|[^0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?(?:/[^\\s]*)?",
        )
        val LOCALHOST_PATH_PATTERN = Regex(
            "(?:^|[^a-z0-9_-])localhost(?::[0-9]{1,5})?/[^\\s]+",
            RegexOption.IGNORE_CASE,
        )
        val SCRIPT_SCHEME_PATTERN = Regex("script\\s*:", RegexOption.IGNORE_CASE)
        val CHOICE_PATTERN = Regex("(?:\\([^()\\r\\n]*,[^()\\r\\n]*\\)|（[^（）\\r\\n]*,[^（）\\r\\n]*）)")
    }
}

internal object UnicodeSecurity {
    fun containsInvisibleFormat(text: String): Boolean {
        var found = false
        forEachScalar(text) { if (isInvisibleFormat(it)) found = true }
        return found
    }

    inline fun forEachScalar(text: String, action: (Int) -> Unit) {
        var index = 0
        while (index < text.length) {
            val first = text[index]
            if (first in '\uD800'..'\uDBFF' && text.getOrNull(index + 1) in '\uDC00'..'\uDFFF') {
                val second = text[index + 1]
                action(0x10000 + ((first.code - 0xD800) shl 10) + second.code - 0xDC00)
                index += 2
            } else {
                action(first.code)
                index++
            }
        }
    }

    fun isInvisibleFormat(codePoint: Int): Boolean = when (codePoint) {
        0x00AD, 0x034F, 0x061C, 0x06DD, 0x070F, 0x08E2, 0xFEFF, 0x110BD, 0x110CD,
        0xE0001 -> true
        in 0x0600..0x0605, in 0x0890..0x0891, in 0x115F..0x1160,
        in 0x17B4..0x17B5, in 0x180B..0x180F, in 0x200B..0x200F,
        in 0x202A..0x202E, in 0x2060..0x206F, in 0x3164..0x3164,
        in 0xFE00..0xFE0F, in 0xFFA0..0xFFA0, in 0xFFF0..0xFFFB,
        in 0x13430..0x1343F, in 0x1BCA0..0x1BCA3, in 0x1D173..0x1D17A,
        in 0xE0000..0xE0FFF -> true
        else -> false
    }

}
