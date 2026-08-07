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
            discoverBlocks(file).forEachIndexed { index, block ->
                extractBlock(file, block, index + 1, diagnostics)?.let(talks::add)
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
            }
        }
        current?.let(blocks::add)

        return blocks
    }

    private fun extractBlock(
        file: GhostSourceFile,
        block: TalkBlock,
        ordinal: Int,
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

        return CanonicalTalk(
            id = "${file.path}:${block.headingLine}:$ordinal",
            sourcePath = file.path,
            sourceLine = block.headingLine,
            heading = block.heading.ifBlank { null },
            category = categoryFor(block.heading),
            turns = turns,
        )
    }

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
    }
}
