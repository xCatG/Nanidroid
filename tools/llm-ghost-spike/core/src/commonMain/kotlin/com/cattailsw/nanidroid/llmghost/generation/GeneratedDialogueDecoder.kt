package com.cattailsw.nanidroid.llmghost.generation

import com.cattailsw.nanidroid.llmghost.model.GeneratedDialogue
import com.cattailsw.nanidroid.llmghost.model.SpikeJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject

data class DialogueDecodeError(
    val code: String,
    val detail: String,
)

data class DialogueDecodeResult(
    val dialogue: GeneratedDialogue?,
    val error: DialogueDecodeError?,
)

class GeneratedDialogueDecoder {
    fun decode(rawText: String): DialogueDecodeResult {
        val payload = when (val extracted = extractPayload(rawText.trim())) {
            is PayloadExtraction.Success -> extracted.payload
            is PayloadExtraction.Failure -> return failure(extracted.code, extracted.detail)
        }

        val completeObjectEnd = completeObjectEnd(payload)
        if (completeObjectEnd != null && completeObjectEnd != payload.length) {
            return failure("ambiguous-output", "Output contains content outside one JSON object.")
        }

        val element = try {
            SpikeJson.parseToJsonElement(payload)
        } catch (_: SerializationException) {
            return failure("malformed-json", "Output is not valid JSON.")
        }
        if (element !is JsonObject) {
            return failure("schema-invalid", "The JSON root must be an object.")
        }

        val dialogue = try {
            SpikeJson.decodeFromString<GeneratedDialogue>(payload)
        } catch (_: SerializationException) {
            return failure("schema-invalid", "The JSON object does not match the dialogue schema.")
        }
        return DialogueDecodeResult(dialogue = dialogue, error = null)
    }

    private fun extractPayload(trimmed: String): PayloadExtraction {
        if (!trimmed.startsWith(FENCE)) {
            val firstObject = trimmed.indexOf('{')
            if (firstObject > 0 && completeObjectEnd(trimmed, firstObject) != null) {
                return PayloadExtraction.Failure(
                    "ambiguous-output",
                    "Output contains prose around a JSON object.",
                )
            }
            return PayloadExtraction.Success(trimmed)
        }

        val match = JSON_FENCE.matchEntire(trimmed)
            ?: return PayloadExtraction.Failure(
                "ambiguous-output",
                "Only one complete json fence with no surrounding content is allowed.",
            )
        val payload = match.groupValues[1]
        if (payload.lineSequence().any { line -> line.trim().isFenceDelimiter() }) {
            return PayloadExtraction.Failure(
                "ambiguous-output",
                "Output must contain exactly one structurally complete fenced JSON block.",
            )
        }
        return PayloadExtraction.Success(payload)
    }

    private fun completeObjectEnd(text: String, start: Int = 0): Int? {
        if (start !in text.indices || text[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
        }
        return null
    }

    private fun failure(code: String, detail: String) = DialogueDecodeResult(
        dialogue = null,
        error = DialogueDecodeError(code, detail),
    )

    private fun String.isFenceDelimiter(): Boolean = this == FENCE || startsWith("```json")

    private sealed interface PayloadExtraction {
        data class Success(val payload: String) : PayloadExtraction
        data class Failure(val code: String, val detail: String) : PayloadExtraction
    }

    private companion object {
        const val FENCE = "```"
        val JSON_FENCE = Regex("^```json[ \\t]*\\r?\\n([\\s\\S]*)\\r?\\n```$")
    }
}
