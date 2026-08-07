package com.cattailsw.nanidroid.llmghost.corpus

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CanonicalTurn
import com.cattailsw.nanidroid.llmghost.model.GhostCorpusInput
import com.cattailsw.nanidroid.llmghost.model.GhostSourceFile
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.TalkCategory

data class CorpusExtractionResult(
    val talks: List<CanonicalTalk>,
    val diagnostics: List<CorpusDiagnostic>,
)

data class CorpusDiagnostic(
    val code: String,
    val path: String,
    val line: Int,
    val detail: String,
)

/**
 * Extracts authored SATORI dialogue without evaluating the dictionary language.
 *
 * Only speaker scopes and numeric surface controls are interpreted. Every other
 * control-like construct invalidates its containing talk block and is reported.
 */
class SatoriTalkExtractor {
    fun extract(input: GhostCorpusInput): CorpusExtractionResult {
        val talks = mutableListOf<CanonicalTalk>()
        val diagnostics = mutableListOf<CorpusDiagnostic>()

        input.files.forEach { file ->
            val blocks = discoverBlocks(file)
            val pointerDispatchSuffixes = discoverPointerDispatchSuffixes(blocks)
            blocks.forEachIndexed { index, block ->
                extractBlock(file, block, index + 1, pointerDispatchSuffixes, diagnostics)?.let(talks::add)
            }
        }

        return CorpusExtractionResult(talks, diagnostics)
    }

    private fun discoverBlocks(file: GhostSourceFile): List<TalkBlock> {
        val blocks = mutableListOf<TalkBlock>()
        var current: TalkBlock? = null

        file.text.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (line.startsWith(BLOCK_MARKER)) {
                current?.let(blocks::add)
                current = TalkBlock(
                    heading = line.removePrefix(BLOCK_MARKER).trim(),
                    headingLine = index + 1,
                )
            } else if (line.startsWith(DIALOGUE_MARKER)) {
                current?.dialogueLines?.add(DialogueLine(index + 1, line.removePrefix(DIALOGUE_MARKER)))
            } else if (line.startsWith(SELECTOR_MARKER)) {
                current?.selectors?.add(line.removePrefix(SELECTOR_MARKER))
            }
        }
        current?.let(blocks::add)

        return blocks
    }

    /**
     * Recognizes only the literal pointer dispatch used by the shipped 2elf dictionaries:
     * an OnMouse block selector whose complete value is `（Ｒ３）（Ｒ４）<suffix>`. The suffix
     * is 1..64 letters, digits, hyphens, or underscores. No SATORI expression is evaluated.
     */
    private fun discoverPointerDispatchSuffixes(blocks: List<TalkBlock>): Set<String> = blocks
        .asSequence()
        .filter { it.heading.startsWith("OnMouse") }
        .flatMap { it.selectors.asSequence() }
        .mapNotNull { selector ->
            if (!selector.startsWith(POINTER_DISPATCH_PREFIX)) return@mapNotNull null
            selector.removePrefix(POINTER_DISPATCH_PREFIX).takeIf(::isBoundedLiteral)
        }
        .toHashSet()

    private fun extractBlock(
        file: GhostSourceFile,
        block: TalkBlock,
        ordinal: Int,
        pointerDispatchSuffixes: Set<String>,
        diagnostics: MutableList<CorpusDiagnostic>,
    ): CanonicalTalk? {
        if (block.dialogueLines.isEmpty()) {
            diagnostics += diagnostic("empty-talk", file, block.headingLine, "The block has no dialogue lines.")
            return null
        }

        var speaker: GhostSpeakerId? = null
        var surface: Int? = null
        var invalid = false
        val turns = mutableListOf<CanonicalTurn>()

        block.dialogueLines.forEach { dialogue ->
            when (val parsed = parseDialogue(dialogue.text)) {
                is ParsedDialogue.Invalid -> {
                    diagnostics += diagnostic(parsed.code, file, dialogue.line, parsed.detail)
                    invalid = true
                }

                is ParsedDialogue.Valid -> parsed.parts.forEach { part ->
                    when (part) {
                        is DialoguePart.Scope -> speaker = part.speaker
                        is DialoguePart.Surface -> surface = part.surface
                        is DialoguePart.Text -> if (part.text.isNotEmpty()) {
                            val currentSpeaker = speaker
                            if (currentSpeaker == null) {
                                diagnostics += diagnostic(
                                    "missing-speaker",
                                    file,
                                    dialogue.line,
                                    "Visible text appeared before a supported speaker scope.",
                                )
                                invalid = true
                            } else {
                                turns += CanonicalTurn(currentSpeaker, surface, part.text)
                            }
                        }
                    }
                }
            }
        }

        if (invalid) return null
        if (turns.isEmpty()) {
            diagnostics += diagnostic("empty-talk", file, block.headingLine, "The block contains no visible dialogue.")
            return null
        }

        val pointer = pointerMetadata(block.heading, pointerDispatchSuffixes)
        return CanonicalTalk(
            id = "${file.path}:${block.headingLine}:$ordinal",
            sourcePath = file.path,
            sourceLine = block.headingLine,
            heading = block.heading.ifBlank { null },
            category = if (pointer != null) TalkCategory.TOUCH else categoryFor(block.heading),
            touchSpeaker = pointer?.speaker,
            touchRegion = pointer?.region,
            turns = turns,
        )
    }

    internal fun pointerMetadata(heading: String, suffixes: Set<String>): PointerMetadata? {
        val speaker = when (heading.firstOrNull()) {
            '0' -> GhostSpeakerId.SAKURA
            '1' -> GhostSpeakerId.KERO
            else -> return null
        }
        val body = heading.substring(1)
        if (body.length !in 2..MAX_POINTER_BODY_LENGTH || !body.all(::isLiteralCharacter)) return null

        val minimumSuffixLength = maxOf(1, body.length - MAX_LITERAL_LENGTH)
        val maximumSuffixLength = minOf(MAX_LITERAL_LENGTH, body.length - 1)
        var matchedRegion: String? = null
        for (suffixLength in minimumSuffixLength..maximumSuffixLength) {
            val regionEnd = heading.length - suffixLength
            if (!suffixes.contains(heading.substring(regionEnd))) continue
            if (matchedRegion != null) return null
            matchedRegion = heading.substring(1, regionEnd)
        }
        return matchedRegion?.let { PointerMetadata(speaker, it) }
    }

    private fun isBoundedLiteral(value: String): Boolean =
        value.length in 1..MAX_LITERAL_LENGTH && value.all(::isLiteralCharacter)

    private fun isLiteralCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || character == '-' || character == '_'

    private fun parseDialogue(text: String): ParsedDialogue {
        if (text.contains("http://") || text.contains("https://") || text.contains("${'$'}{") || text.contains("${'$'}(")) {
            return ParsedDialogue.Invalid("unsupported-control", "URLs and variable expressions are not extracted.")
        }

        val parts = mutableListOf<DialoguePart>()
        val visible = StringBuilder()
        var index = 0

        fun flushVisible() {
            if (visible.isNotEmpty()) {
                parts += DialoguePart.Text(visible.toString())
                visible.clear()
            }
        }

        while (index < text.length) {
            when {
                text[index] == '\\' && index + 1 < text.length -> {
                    flushVisible()
                    when (val control = text[index + 1]) {
                        '0', 'h' -> {
                            parts += DialoguePart.Scope(GhostSpeakerId.SAKURA)
                            index += 2
                        }

                        '1', 'u' -> {
                            parts += DialoguePart.Scope(GhostSpeakerId.KERO)
                            index += 2
                        }

                        's' -> {
                            if (index + 2 >= text.length || text[index + 2] != '[') {
                                return ParsedDialogue.Invalid("malformed-control", "Surface control is missing an opening bracket.")
                            }
                            val closing = text.indexOf(']', index + 3)
                            if (closing < 0) {
                                return ParsedDialogue.Invalid("malformed-control", "Surface control is missing a closing bracket.")
                            }
                            val surfaceText = text.substring(index + 3, closing)
                            val parsedSurface = surfaceText.toIntOrNull()
                            if (parsedSurface == null || parsedSurface < 0) {
                                return ParsedDialogue.Invalid("malformed-control", "Surface control must contain a non-negative integer.")
                            }
                            parts += DialoguePart.Surface(parsedSurface)
                            index = closing + 1
                        }

                        else -> return ParsedDialogue.Invalid(
                            "unsupported-control",
                            "Unsupported SATORI control \\$control.",
                        )
                    }
                }

                text[index] == '\\' -> return ParsedDialogue.Invalid(
                    "malformed-control",
                    "A backslash control is truncated.",
                )

                text[index] == '（' || text[index] == '(' -> return ParsedDialogue.Invalid(
                    "unsupported-control",
                    "Conditions and calls are not extracted.",
                )

                else -> {
                    visible.append(text[index])
                    index += 1
                }
            }
        }

        flushVisible()
        return ParsedDialogue.Valid(parts)
    }

    private fun categoryFor(heading: String): TalkCategory = when {
        heading.isBlank() || heading.equals("random", ignoreCase = true) -> TalkCategory.IDLE
        heading.startsWith("OnMouse") -> TalkCategory.TOUCH
        heading.startsWith("On") -> TalkCategory.EVENT
        else -> TalkCategory.OTHER
    }

    private fun diagnostic(code: String, file: GhostSourceFile, line: Int, detail: String) = CorpusDiagnostic(
        code = code,
        path = file.path,
        line = line,
        detail = detail,
    )

    private data class TalkBlock(
        val heading: String,
        val headingLine: Int,
        val dialogueLines: MutableList<DialogueLine> = mutableListOf(),
        val selectors: MutableList<String> = mutableListOf(),
    )

    internal data class PointerMetadata(
        val speaker: GhostSpeakerId,
        val region: String,
    )

    private data class DialogueLine(
        val line: Int,
        val text: String,
    )

    private sealed interface DialoguePart {
        data class Scope(val speaker: GhostSpeakerId) : DialoguePart
        data class Surface(val surface: Int) : DialoguePart
        data class Text(val text: String) : DialoguePart
    }

    private sealed interface ParsedDialogue {
        data class Valid(val parts: List<DialoguePart>) : ParsedDialogue
        data class Invalid(val code: String, val detail: String) : ParsedDialogue
    }

    private companion object {
        const val BLOCK_MARKER = "＊"
        const val DIALOGUE_MARKER = "："
        const val SELECTOR_MARKER = "＞"
        const val POINTER_DISPATCH_PREFIX = "（Ｒ３）（Ｒ４）"
        const val MAX_LITERAL_LENGTH = 64
        const val MAX_POINTER_BODY_LENGTH = MAX_LITERAL_LENGTH * 2
    }
}
