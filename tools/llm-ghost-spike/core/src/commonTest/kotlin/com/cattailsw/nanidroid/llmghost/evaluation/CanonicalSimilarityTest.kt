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
import kotlin.test.assertFailsWith

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

    @Test
    fun case_normalization_folds_latin_but_preserves_greek_case() {
        val latinFinding = CanonicalSimilarity.evaluate(
            generatedTurns = listOf(GeneratedTurn("sakura", 0, "hello")),
            canonicalTalks = listOf(
                talk("latin", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "HELLO"))),
            ),
        ).single()
        val greekFinding = CanonicalSimilarity.evaluate(
            generatedTurns = listOf(GeneratedTurn("sakura", 0, "α")),
            canonicalTalks = listOf(
                talk("greek", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "Α"))),
            ),
        ).single()

        assertTrue(latinFinding.exact)
        assertFalse(greekFinding.exact)
        assertEquals(0.0, greekFinding.ratio)
    }

    @Test
    fun latin_compatibility_letter_folds_when_its_lowercase_is_latin() {
        val finding = CanonicalSimilarity.evaluate(
            generatedTurns = listOf(GeneratedTurn("sakura", 0, "k")),
            canonicalTalks = listOf(
                talk("kelvin", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "\u212A"))),
            ),
        ).single()

        assertTrue(finding.exact)
        assertEquals(1.0, finding.ratio)
    }

    @Test
    fun invisible_characters_and_compatibility_forms_cannot_hide_exact_copies() {
        val variants = listOf(
            "c\u200Bo\u200Bp\u200By" to "copy",
            "co\u202Epy" to "copy",
            "cop\uFE0Fy" to "copy",
            "\uFF43\uFF4F\uFF50\uFF59" to "copy",
            "\uFB02ower" to "flower",
            "co\uFFF0py" to "copy",
        )

        variants.forEach { (generated, canonical) ->
            val finding = CanonicalSimilarity.evaluate(
                generatedTurns = listOf(GeneratedTurn("sakura", 0, generated)),
                canonicalTalks = listOf(talk("copy", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, canonical)))),
            ).single()
            assertTrue(finding.exact, "Expected exact copy for $generated")
        }
    }

    @Test
    fun mathematical_alphanumeric_copy_normalizes_to_plain_text() {
        val finding = CanonicalSimilarity.evaluate(
            generatedTurns = listOf(
                GeneratedTurn("sakura", 0, "\uD835\uDC1C\uD835\uDC28\uD835\uDC29\uD835\uDC32"),
            ),
            canonicalTalks = listOf(
                talk("plain", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "copy"))),
            ),
        ).single()

        assertTrue(finding.exact)
    }

    @Test
    fun pinned_nfkd_handles_kana_halfwidth_and_cjk_compatibility_sequences() {
        val pairs = listOf(
            "ゟ" to "より",
            "ヿ" to "コト",
            "ﾃｽﾄ" to "ﾃｽﾄ",
            "ｶﾞ" to "ガ",
            "℃" to "°C",
        )

        pairs.forEach { (generated, canonical) ->
            val finding = CanonicalSimilarity.evaluate(
                generatedTurns = listOf(GeneratedTurn("sakura", 0, generated)),
                canonicalTalks = listOf(
                    talk("plain", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, canonical))),
                ),
            ).single()
            assertTrue(finding.exact, "Expected NFKD exact match: $generated / $canonical")
        }
    }

    @Test
    fun pinned_nfkd_reorders_combining_marks_by_canonical_class() {
        val finding = CanonicalSimilarity.evaluate(
            generatedTurns = listOf(GeneratedTurn("sakura", 0, "a\u0315\u0300")),
            canonicalTalks = listOf(
                talk("canonical", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "à\u0315"))),
            ),
        ).single()

        assertTrue(finding.exact)
    }

    @Test
    fun pinned_nfkd_reorders_combining_marks_after_ignored_punctuation_is_removed() {
        val finding = CanonicalSimilarity.evaluate(
            generatedTurns = listOf(GeneratedTurn("sakura", 0, "a\u0315,\u0300")),
            canonicalTalks = listOf(
                talk("canonical", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "a\u0300\u0315"))),
            ),
        ).single()

        assertTrue(finding.exact)
    }

    @Test
    fun exact_canonical_turn_split_across_adjacent_generated_turns_is_detected() {
        val generated = listOf(
            GeneratedTurn("sakura", 0, "A fresh"),
            GeneratedTurn("kero", 1, " canonical"),
            GeneratedTurn("sakura", 0, " line"),
        )

        val exact = CanonicalSimilarity.evaluate(
            generatedTurns = generated,
            canonicalTalks = listOf(talk("split", listOf(CanonicalTurn(GhostSpeakerId.KERO, 1, "A fresh canonical line")))),
        ).single { it.exact }

        assertEquals("split", exact.canonicalTalkId)
        assertEquals(0, exact.generatedTurnStartIndex)
        assertEquals(2, exact.generatedTurnEndIndex)
        assertEquals(generated.first(), exact.generatedTurn)
    }

    @Test
    fun adjacent_window_findings_have_deterministic_start_then_end_order() {
        val findings = CanonicalSimilarity.evaluate(
            generatedTurns = listOf(
                GeneratedTurn("sakura", 0, "a"),
                GeneratedTurn("kero", 1, "b"),
                GeneratedTurn("sakura", 0, "c"),
            ),
            canonicalTalks = listOf(
                talk("ab", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "ab"))),
                talk("bc", listOf(CanonicalTurn(GhostSpeakerId.KERO, 1, "bc"))),
            ),
        ).filter { it.exact }

        assertEquals(listOf(0 to 1, 1 to 2), findings.map { it.generatedTurnStartIndex to it.generatedTurnEndIndex })
    }

    @Test
    fun duplicate_canonical_candidates_do_not_consume_repeated_dp_budget() {
        val canonical = CanonicalTurn(GhostSpeakerId.SAKURA, 0, "12345678901234567890")
        val findings = CanonicalSimilarity.evaluate(
            generatedTurns = listOf(GeneratedTurn("sakura", 0, "123456789012345678XX")),
            canonicalTalks = List(1_000) { talk("duplicate-$it", listOf(canonical)) },
            budget = SimilarityBudget(maxComparisons = 1, maxDpCells = 400),
        )

        assertEquals(0.9, findings.single().ratio, absoluteTolerance = 0.000_001)
        assertEquals("duplicate-0", findings.single().canonicalTalkId)
    }

    @Test
    fun excessive_comparison_work_fails_closed_deterministically() {
        val talks = List(3) { index ->
            talk("talk-$index", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "a".repeat(20) + index)))
        }

        val failure = assertFailsWith<SimilarityBudgetExceededException> {
            CanonicalSimilarity.evaluate(
                generatedTurns = listOf(GeneratedTurn("sakura", 0, "a".repeat(20) + "x")),
                canonicalTalks = talks,
                budget = SimilarityBudget(maxComparisons = 2, maxDpCells = 10_000),
            )
        }

        assertEquals(2, failure.completedComparisons)
    }

    @Test
    fun maximum_length_irrelevant_canonical_line_is_safely_filtered_without_dp() {
        val findings = CanonicalSimilarity.evaluate(
            generatedTurns = listOf(GeneratedTurn("sakura", 0, "short original line")),
            canonicalTalks = listOf(
                talk("maximum", listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "x".repeat(65_536)))),
            ),
            budget = SimilarityBudget(maxComparisons = 0, maxDpCells = 0),
        )

        assertEquals(0.0, findings.single().ratio)
        assertFalse(findings.single().exact)
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
