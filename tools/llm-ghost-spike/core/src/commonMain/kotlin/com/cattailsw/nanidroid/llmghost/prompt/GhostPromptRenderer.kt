package com.cattailsw.nanidroid.llmghost.prompt

import com.cattailsw.nanidroid.llmghost.model.GeneratedDialogue
import com.cattailsw.nanidroid.llmghost.model.GenerationScenario
import com.cattailsw.nanidroid.llmghost.model.GhostGenerationRequest
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.OutputLanguage
import com.cattailsw.nanidroid.llmghost.model.ScenarioKind
import com.cattailsw.nanidroid.llmghost.model.SpikeJson
import com.cattailsw.nanidroid.llmghost.retrieval.CanonicalTalkRetriever
import com.cattailsw.nanidroid.llmghost.retrieval.RetrievedExample
import kotlinx.serialization.encodeToString

data class RenderedGhostPrompt(
    val system: String,
    val user: String,
    val selectedExamples: List<RetrievedExample>,
)

class GhostPromptRenderer(
    private val retriever: CanonicalTalkRetriever = CanonicalTalkRetriever(),
) {
    fun render(request: GhostGenerationRequest): RenderedGhostPrompt {
        val selectedExamples = retriever.retrieve(request.examples, request.scenario, EXAMPLE_LIMIT)
        return RenderedGhostPrompt(
            system = systemPrompt(request.language),
            user = userPrompt(request, selectedExamples),
            selectedExamples = selectedExamples,
        )
    }

    private fun systemPrompt(language: OutputLanguage): String = buildString {
        appendLine("Generate a short ghost dialogue from the corpus examples supplied by the user.")
        appendLine("Return exactly one JSON object with no markdown, matching this schema:")
        appendLine(OUTPUT_SCHEMA)
        appendLine("Use only the speaker values sakura and kero and only the allowed surfaces provided by the user.")
        appendLine("Do not copy any complete line from the examples.")
        append(
            when (language) {
                OutputLanguage.JAPANESE -> "Write natural Japanese that preserves the characters' observed behavior."
                OutputLanguage.ENGLISH -> "Write natural English that preserves the characters' observed behavior; do not translate literally."
            },
        )
    }

    private fun userPrompt(
        request: GhostGenerationRequest,
        selectedExamples: List<RetrievedExample>,
    ): String = buildString {
        appendLine("Scenario:")
        appendLine(SpikeJson.encodeToString<GenerationScenario>(request.scenario))
        appendLine()
        appendLine("Allowed surfaces:")
        GhostSpeakerId.entries.forEach { speaker ->
            appendLine("${speaker.promptName()}: ${request.validSurfaces[speaker].orEmpty().sorted()}")
        }
        continuationHistory(request)?.let { history ->
            appendLine()
            appendLine("Recent generated history:")
            appendLine(SpikeJson.encodeToString<GeneratedDialogue>(history))
        }
        appendLine()
        appendLine("Corpus examples:")
        selectedExamples.forEachIndexed { index, example ->
            appendLine("Example ${index + 1} (score=${example.score}; reasons=${example.reasons.joinToString(", ")}):")
            appendLine(SpikeJson.encodeToString(example.talk))
        }
    }.trimEnd()

    private fun continuationHistory(request: GhostGenerationRequest): GeneratedDialogue? =
        request.recentGeneratedHistory?.takeIf { request.scenario.kind == ScenarioKind.CONTINUATION }

    private fun GhostSpeakerId.promptName(): String = when (this) {
        GhostSpeakerId.SAKURA -> "sakura"
        GhostSpeakerId.KERO -> "kero"
    }

    private companion object {
        const val EXAMPLE_LIMIT = 3
        const val OUTPUT_SCHEMA = "{\"turns\":[{\"speaker\":\"sakura|kero\",\"surface\":0,\"text\":\"string\",\"waitAfterMs\":0}]}"
    }
}
