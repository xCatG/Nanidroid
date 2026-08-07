package com.cattailsw.nanidroid.llmghost

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.GenerationScenario
import com.cattailsw.nanidroid.llmghost.model.GhostGenerationRequest
import com.cattailsw.nanidroid.llmghost.model.GhostIdentity
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.OutputLanguage
import com.cattailsw.nanidroid.llmghost.model.ScenarioKind
import com.cattailsw.nanidroid.llmghost.model.SpikeCase

data class SeededSpikeCase(
    val case: SpikeCase,
    val seed: Long,
)

sealed interface ScenarioMatrixResult {
    data class Success(val cases: List<SeededSpikeCase>) : ScenarioMatrixResult

    data class Failure(
        val code: String,
        val detail: String,
    ) : ScenarioMatrixResult
}

object SpikeScenarioFactory {
    fun requiredCases(
        identity: GhostIdentity,
        talks: List<CanonicalTalk>,
        seed: Long,
    ): ScenarioMatrixResult {
        if (talks.isEmpty()) {
            return failure("missing-corpus", "No canonical talks were extracted.")
        }
        val continuation = talks.firstOrNull { it.turns.isNotEmpty() }
            ?: return failure("missing-continuation-example", "No canonical talk can seed a continuation.")
        val pointer = talks.firstOrNull {
            it.touchSpeaker != null && !it.touchRegion.isNullOrBlank() && it.turns.isNotEmpty()
        } ?: return failure(
            "missing-pointer-example",
            "No canonical pointer talk has an observed speaker and region.",
        )
        val validSurfaces = GhostSpeakerId.entries.associateWith { speaker ->
            val observed = talks.asSequence()
                .flatMap { it.turns.asSequence() }
                .filter { it.speaker == speaker }
                .mapNotNull { it.surface }
                .toSet()
            observed intersect identity.shellSurfaces[speaker].orEmpty()
        }
        val missingSpeaker = GhostSpeakerId.entries.firstOrNull { validSurfaces[it].isNullOrEmpty() }
        if (missingSpeaker != null) {
            return failure(
                "missing-authorized-surface",
                "No corpus-observed installed-shell surface is available for ${missingSpeaker.name.lowercase()}.",
            )
        }

        val scenarios = listOf(
            GenerationScenario(ScenarioKind.IDLE),
            GenerationScenario(
                kind = ScenarioKind.CONTINUATION,
                topic = continuation.turns.joinToString(" ") { it.text }.take(MAX_TOPIC_LENGTH),
                canonicalTalkId = continuation.id,
            ),
            GenerationScenario(
                kind = ScenarioKind.POINTER_EVENT,
                topic = pointer.heading.orEmpty(),
                touchSpeaker = pointer.touchSpeaker,
                touchRegion = pointer.touchRegion,
            ),
        )
        val cases = buildList {
            scenarios.forEach { scenario ->
                OutputLanguage.entries.forEach { language ->
                    val caseId = "${scenario.kind.caseName()}-${language.name.lowercase()}"
                    add(
                        SeededSpikeCase(
                            case = SpikeCase(
                                caseId = caseId,
                                request = GhostGenerationRequest(
                                    scenario = scenario,
                                    language = language,
                                    examples = talks,
                                    validSurfaces = validSurfaces,
                                ),
                            ),
                            seed = seed + size,
                        ),
                    )
                }
            }
        }
        return ScenarioMatrixResult.Success(cases)
    }

    private fun failure(code: String, detail: String) = ScenarioMatrixResult.Failure(code, detail)

    private fun ScenarioKind.caseName(): String = when (this) {
        ScenarioKind.IDLE -> "idle"
        ScenarioKind.CONTINUATION -> "continuation"
        ScenarioKind.POINTER_EVENT -> "pointer"
    }

    private const val MAX_TOPIC_LENGTH = 500
}
