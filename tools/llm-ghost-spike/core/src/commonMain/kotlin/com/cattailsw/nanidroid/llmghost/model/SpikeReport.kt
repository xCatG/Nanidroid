package com.cattailsw.nanidroid.llmghost.model

import com.cattailsw.nanidroid.llmghost.evaluation.SimilarityFinding
import kotlinx.serialization.Serializable

@Serializable
enum class CaseStatus {
    PASSED,
    FAILED,
}

@Serializable
data class SpikeCase(
    val caseId: String,
    val request: GhostGenerationRequest,
)

@Serializable
data class RenderedPromptReport(
    val system: String = "",
    val user: String = "",
    val selectedExampleIds: List<String> = emptyList(),
)

@Serializable
data class SpikeFailure(
    val code: String,
    val detail: String,
    val sourceCode: String? = null,
)

@Serializable
data class SpikeWarning(
    val code: String,
    val detail: String,
)

@Serializable
data class CompiledScriptValidationReport(
    val valid: Boolean,
    val violationCodes: List<String> = emptyList(),
)

@Serializable
data class SpikeCaseReport(
    val caseId: String,
    val status: CaseStatus,
    val rawResponse: String,
    val compiledSakuraScript: String? = null,
    val renderedPrompt: RenderedPromptReport = RenderedPromptReport(),
    val startedAtMillis: Long = 0,
    val elapsedMillis: Long = 0,
    val preparationEvents: List<ModelPreparation> = emptyList(),
    val generationEvents: List<GenerationEvent> = emptyList(),
    val usage: GenerationUsage? = null,
    val generatedDialogue: GeneratedDialogue? = null,
    val similarityFindings: List<SimilarityFinding> = emptyList(),
    val warnings: List<SpikeWarning> = emptyList(),
    val compiledScriptValidation: CompiledScriptValidationReport? = null,
    val failure: SpikeFailure? = null,
)
