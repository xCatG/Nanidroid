package com.cattailsw.nanidroid.llmghost.model

import kotlin.test.Test
import kotlin.test.assertEquals

class PortableContractsTest {
    @Test
    fun request_round_trips_through_common_json() {
        val request = GhostGenerationRequest(
            scenario = GenerationScenario(ScenarioKind.IDLE, topic = "rain"),
            language = OutputLanguage.JAPANESE,
            examples = listOf(sampleTalk()),
            validSurfaces = mapOf(GhostSpeakerId.SAKURA to setOf(0, 3)),
        )

        val encoded = SpikeJson.encodeToString(request)

        assertEquals(request, SpikeJson.decodeFromString(encoded))
    }

    private fun sampleTalk() = CanonicalTalk(
        id = "idle-rain",
        sourcePath = "talk.txt",
        sourceLine = 1,
        heading = "Rain",
        category = TalkCategory.IDLE,
        turns = listOf(
            CanonicalTurn(GhostSpeakerId.SAKURA, 0, "Rain again."),
            CanonicalTurn(GhostSpeakerId.KERO, 3, "Take an umbrella."),
        ),
    )
}
