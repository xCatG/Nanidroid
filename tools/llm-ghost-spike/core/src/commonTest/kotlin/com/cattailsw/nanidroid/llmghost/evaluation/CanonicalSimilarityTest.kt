package com.cattailsw.nanidroid.llmghost.evaluation

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CanonicalTurn
import com.cattailsw.nanidroid.llmghost.model.GeneratedTurn
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.TalkCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanonicalSimilarityTest {
    @Test
    fun normalized_exact_copy_is_identified_with_its_canonical_source() {
        val canonical = talk(
            id = "talk-exact",
            turns = listOf(
                CanonicalTurn(GhostSpeakerId.KERO, 1, "Unrelated"),
                CanonicalTurn(GhostSpeakerId.SAKURA, 0, "Hello,\u3000WORLD!"),
            ),
        )

        val finding = CanonicalSimilarity.evaluate(
            generatedTurns = listOf(GeneratedTurn("sakura", 0, "hello world。")),
            canonicalTalks = listOf(canonical),
        ).single()

        assertEquals("talk-exact", finding.canonicalTalkId)
        assertEquals(canonical.turns[1], finding.canonicalTurn)
        assertTrue(finding.exact)
        assertEquals(1.0, finding.ratio)
    }

    @Test
    fun near_copy_at_threshold_is_reported_but_not_exact() {
        val canonicalText = "12345678901234567890"
        val generatedText = "123456789012345678XX"

        val finding = CanonicalSimilarity.evaluate(
            generatedTurns = listOf(GeneratedTurn("sakura", 0, generatedText)),
            canonicalTalks = listOf(
                talk("weaker", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "abcdefghij"))),
                talk("strongest", listOf(CanonicalTurn(GhostSpeakerId.KERO, 1, canonicalText))),
            ),
        ).single()

        assertEquals("strongest", finding.canonicalTalkId)
        assertEquals(canonicalText, finding.canonicalTurn.text)
        assertFalse(finding.exact)
        assertEquals(0.9, finding.ratio, absoluteTolerance = 0.000_001)
        assertTrue(finding.ratio >= CanonicalSimilarity.NEAR_COPY_THRESHOLD)
    }

    @Test
    fun short_incidental_overlap_stays_below_warning_threshold() {
        val finding = CanonicalSimilarity.evaluate(
            generatedTurns = listOf(GeneratedTurn("sakura", 0, "cat naps")),
            canonicalTalks = listOf(
                talk("canonical", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "a cat walks outside"))),
            ),
        ).single()

        assertFalse(finding.exact)
        assertTrue(finding.ratio < CanonicalSimilarity.NEAR_COPY_THRESHOLD)
    }

    @Test
    fun normalization_with_no_remaining_characters_has_finite_ratios() {
        val findings = CanonicalSimilarity.evaluate(
            generatedTurns = listOf(
                GeneratedTurn("sakura", 0, "... \u3000"),
                GeneratedTurn("sakura", 0, "a"),
            ),
            canonicalTalks = listOf(
                talk("punctuation", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "！？"))),
            ),
        )

        assertEquals(1.0, findings[0].ratio)
        assertTrue(findings[0].exact)
        assertEquals(0.0, findings[1].ratio)
        assertFalse(findings[1].exact)
    }

    private fun talk(id: String, turns: List<CanonicalTurn>) = CanonicalTalk(
        id = id,
        sourcePath = "dic/$id.txt",
        sourceLine = 1,
        heading = null,
        category = TalkCategory.IDLE,
        turns = turns,
    )
}
