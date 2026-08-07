package com.cattailsw.nanidroid.llmghost.model

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GhostGenerationRequest(
    val scenario: GenerationScenario,
    val language: OutputLanguage,
    val examples: List<CanonicalTalk>,
    val validSurfaces: Map<GhostSpeakerId, Set<Int>>,
    val recentGeneratedHistory: GeneratedDialogue? = null,
)

@Serializable
data class ModelCapabilities(
    val streaming: Boolean,
    val structuredOutput: Boolean,
)

@Serializable
sealed interface ModelPreparation {
    @Serializable
    data object Ready : ModelPreparation

    @Serializable
    data class Downloading(val progressPercent: Int?) : ModelPreparation

    @Serializable
    data class Failed(
        val code: String,
        val detail: String,
    ) : ModelPreparation
}

@Serializable
data class GenerationUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)

@Serializable
sealed interface GenerationEvent {
    @Serializable
    data class TextDelta(val text: String) : GenerationEvent

    @Serializable
    data class Completed(val usage: GenerationUsage?) : GenerationEvent

    @Serializable
    data class Failed(
        val code: String,
        val detail: String,
    ) : GenerationEvent
}

@Serializable
data class GeneratedTurn(
    val speaker: String,
    val surface: Int,
    val text: String,
    val waitAfterMs: Int = 0,
)

@Serializable
data class GeneratedDialogue(val turns: List<GeneratedTurn>)

interface GhostModelBackend {
    val capabilities: ModelCapabilities

    fun prepare(): Flow<ModelPreparation>

    fun generate(request: GhostGenerationRequest): Flow<GenerationEvent>

    suspend fun close()
}

val SpikeJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
    prettyPrint = false
}
