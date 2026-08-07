package com.cattailsw.nanidroid.llmghost.retrieval

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CanonicalTurn
import com.cattailsw.nanidroid.llmghost.model.GenerationScenario
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.ScenarioKind
import com.cattailsw.nanidroid.llmghost.model.TalkCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanonicalTalkRetrieverTest {
    private val retriever = CanonicalTalkRetriever()

    @Test
    fun touchScenarioPrefersMatchingCharacterAndRegion() {
        val result = retriever.retrieve(talks, keroHeadScenario, limit = 3)

        assertEquals("kero-head", result.first().talk.id)
        assertEquals(result, retriever.retrieve(talks, keroHeadScenario, 3))
        assertTrue(result.first().reasons.contains("touch-region"))
    }

    @Test
    fun continuationPrefersItsCanonicalSourceAndExplainsEveryMatch() {
        val result = retriever.retrieve(
            listOf(
                talk(
                    id = "other",
                    path = "a.txt",
                    line = 1,
                    touchSpeaker = GhostSpeakerId.SAKURA,
                    touchRegion = "arm",
                    text = "An unrelated line.",
                ),
                CanonicalTalk(
                    id = "continued",
                    sourcePath = "b.txt",
                    sourceLine = 2,
                    heading = "Rain",
                    category = TalkCategory.IDLE,
                    turns = listOf(
                        CanonicalTurn(GhostSpeakerId.SAKURA, 0, "Rain is falling."),
                        CanonicalTurn(GhostSpeakerId.KERO, 1, "Bring an umbrella."),
                    ),
                ),
            ),
            GenerationScenario(
                kind = ScenarioKind.CONTINUATION,
                topic = "RAIN",
                canonicalTalkId = "continued",
            ),
            limit = 2,
        )

        assertEquals("continued", result.first().talk.id)
        assertEquals(150, result.first().score)
        assertEquals(
            listOf("continuation-source", "topic-token:rain", "both-speakers"),
            result.first().reasons,
        )
    }

    @Test
    fun limitsARepeatedSourceBeforeFillingFromTheOrderedTail() {
        val result = retriever.retrieve(
            listOf(
                idleTalk("a-1", "a.txt", 1),
                idleTalk("a-2", "a.txt", 2),
                idleTalk("a-3", "a.txt", 3),
                idleTalk("b-1", "b.txt", 1),
            ),
            GenerationScenario(ScenarioKind.IDLE),
            limit = 3,
        )

        assertEquals(listOf("a-1", "a-2", "b-1"), result.map { it.talk.id })
    }

    @Test
    fun fillsRemainingSlotsFromOneSourceAndBreaksTiesByProvenance() {
        val result = retriever.retrieve(
            listOf(
                idleTalk("late", "same.txt", 3),
                idleTalk("early-b", "same.txt", 1),
                idleTalk("early-a", "same.txt", 1),
            ),
            GenerationScenario(ScenarioKind.IDLE),
            limit = 3,
        )

        assertEquals(listOf("early-a", "early-b", "late"), result.map { it.talk.id })
    }

    private val keroHeadScenario = GenerationScenario(
        kind = ScenarioKind.POINTER_EVENT,
        topic = "head",
        touchSpeaker = GhostSpeakerId.KERO,
        touchRegion = "head",
    )

    private val talks = listOf(
        talk(
            id = "sakura-head",
            path = "a.txt",
            line = 2,
            touchSpeaker = GhostSpeakerId.SAKURA,
            touchRegion = "head",
            text = "My head is ticklish.",
        ),
        talk(
            id = "kero-head",
            path = "b.txt",
            line = 1,
            touchSpeaker = GhostSpeakerId.KERO,
            touchRegion = "head",
            text = "Don't poke my head.",
        ),
        talk(
            id = "kero-arm",
            path = "c.txt",
            line = 1,
            touchSpeaker = GhostSpeakerId.KERO,
            touchRegion = "arm",
            text = "My arm is busy.",
        ),
    )

    private fun talk(
        id: String,
        path: String,
        line: Int,
        touchSpeaker: GhostSpeakerId,
        touchRegion: String,
        text: String,
    ) = CanonicalTalk(
        id = id,
        sourcePath = path,
        sourceLine = line,
        heading = "OnMouseMove",
        category = TalkCategory.TOUCH,
        touchSpeaker = touchSpeaker,
        touchRegion = touchRegion,
        turns = listOf(CanonicalTurn(touchSpeaker, null, text)),
    )

    private fun idleTalk(id: String, path: String, line: Int) = CanonicalTalk(
        id = id,
        sourcePath = path,
        sourceLine = line,
        heading = "Random",
        category = TalkCategory.IDLE,
        turns = listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "Hello.")),
    )
}
