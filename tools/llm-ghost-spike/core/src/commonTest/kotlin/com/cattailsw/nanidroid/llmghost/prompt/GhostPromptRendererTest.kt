package com.cattailsw.nanidroid.llmghost.prompt

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CanonicalTurn
import com.cattailsw.nanidroid.llmghost.model.GeneratedDialogue
import com.cattailsw.nanidroid.llmghost.model.GeneratedTurn
import com.cattailsw.nanidroid.llmghost.model.GenerationScenario
import com.cattailsw.nanidroid.llmghost.model.GhostGenerationRequest
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.OutputLanguage
import com.cattailsw.nanidroid.llmghost.model.ScenarioKind
import com.cattailsw.nanidroid.llmghost.model.SpikeJson
import com.cattailsw.nanidroid.llmghost.model.TalkCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GhostPromptRendererTest {
    private val renderer = GhostPromptRenderer()

    @Test
    fun japanesePromptIncludesSchemaScenarioSurfacesAndCorpusExamples() {
        val prompt = renderer.render(request(language = OutputLanguage.JAPANESE))
        val rendered = prompt.system + prompt.user

        assertTrue(rendered.contains(OUTPUT_SCHEMA))
        assertTrue(rendered.contains("sakura: [0, 3]"))
        assertTrue(rendered.contains("kero: [19]"))
        assertTrue(prompt.user.contains("Scenario:\n{\"kind\":\"POINTER_EVENT\""))
        assertTrue(prompt.user.contains("Example 1"))
        assertTrue(prompt.user.contains(SpikeJson.encodeToString(examples.first())))
        assertTrue(rendered.contains("Do not copy any complete line from the examples."))
        assertEquals(listOf("touch", "idle"), prompt.selectedExamples.map { it.talk.id })
        assertFalse(rendered.contains("biography", ignoreCase = true))
    }

    @Test
    fun englishContinuationPromptIncludesHistoryAndRequestsAdaptation() {
        val history = GeneratedDialogue(
            listOf(GeneratedTurn("sakura", 3, "Earlier generated reply.")),
        )
        val prompt = renderer.render(
            request(
                language = OutputLanguage.ENGLISH,
                scenario = GenerationScenario(
                    kind = ScenarioKind.CONTINUATION,
                    topic = "rain",
                    canonicalTalkId = "idle",
                ),
                recentGeneratedHistory = history,
            ),
        )
        val rendered = prompt.system + prompt.user

        assertTrue(rendered.contains(OUTPUT_SCHEMA))
        assertTrue(prompt.user.contains("Recent generated history:\n${SpikeJson.encodeToString(history)}"))
        assertTrue(rendered.contains("Write natural English that preserves the characters' observed behavior; do not translate literally."))
        assertTrue(prompt.user.contains("Example 1"))
        assertEquals("idle", prompt.selectedExamples.first().talk.id)
        assertFalse(rendered.contains("biography", ignoreCase = true))
    }

    private fun request(
        language: OutputLanguage,
        scenario: GenerationScenario = GenerationScenario(
            kind = ScenarioKind.POINTER_EVENT,
            topic = "head",
            touchSpeaker = GhostSpeakerId.KERO,
            touchRegion = "head",
        ),
        recentGeneratedHistory: GeneratedDialogue? = null,
    ) = GhostGenerationRequest(
        scenario = scenario,
        language = language,
        examples = examples,
        validSurfaces = mapOf(
            GhostSpeakerId.SAKURA to setOf(3, 0),
            GhostSpeakerId.KERO to setOf(19),
        ),
        recentGeneratedHistory = recentGeneratedHistory,
    )

    private val examples = listOf(
        CanonicalTalk(
            id = "idle",
            sourcePath = "idle.txt",
            sourceLine = 1,
            heading = "Rain",
            category = TalkCategory.IDLE,
            turns = listOf(
                CanonicalTurn(GhostSpeakerId.SAKURA, 0, "A quoted \"source\" line."),
                CanonicalTurn(GhostSpeakerId.KERO, 19, "Bring an umbrella."),
            ),
        ),
        CanonicalTalk(
            id = "touch",
            sourcePath = "touch.txt",
            sourceLine = 4,
            heading = "OnMouseMove",
            category = TalkCategory.TOUCH,
            touchSpeaker = GhostSpeakerId.KERO,
            touchRegion = "head",
            turns = listOf(CanonicalTurn(GhostSpeakerId.KERO, 19, "Don't poke my head.")),
        ),
    )

    private companion object {
        const val OUTPUT_SCHEMA = "{\"turns\":[{\"speaker\":\"sakura|kero\",\"surface\":0,\"text\":\"string\",\"waitAfterMs\":0}]}"
    }
}
