package com.cattailsw.nanidroid.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceSelectorTest {
    private val selector = SurfaceSelector()

    @Test
    fun materia_and_ssp_tokens_ranges_and_exclusions_are_supported() {
        val result = selector.parse(SourceLine("surfaces.txt", 1, "surface1,surface3,4-6,!5"))

        assertEquals(linkedSetOf(1, 3, 4, 6), result.selection.included)
        assertEquals(setOf(5), result.selection.excluded)
        assertEquals(SurfaceBlockMode.DEFINE, result.mode)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun an_exclusion_stays_excluded_when_a_later_token_reincludes_it() {
        val result = selector.parse(SourceLine("surfaces.txt", 4, "surface1-5,!2-4,3"))

        assertEquals(linkedSetOf(1, 5), result.selection.included)
        assertEquals(setOf(2, 3, 4), result.selection.excluded)
    }

    @Test
    fun malformed_tokens_are_diagnosed_and_never_alias_surface_zero() {
        val result = selector.parse(SourceLine("surfaces.txt", 8, "surface0,broken,2,!wat"))

        assertEquals(linkedSetOf(0, 2), result.selection.included)
        assertEquals(2, result.diagnostics.size)
        assertTrue(result.diagnostics.all { it.reason == SurfaceDiagnosticReason.SELECTOR })
    }

    @Test
    fun reversed_overflow_and_over_budget_ranges_are_rejected_without_partial_expansion() {
        val result = selector.parse(
            SourceLine(
                "surfaces.txt",
                12,
                "surface7,9-3,2147483648,10-10010,8",
            ),
        )

        assertEquals(linkedSetOf(7, 8), result.selection.included)
        assertEquals(3, result.diagnostics.size)
    }

    @Test
    fun cumulative_work_budget_counts_overlaps_and_excluded_expansions() {
        val result = selector.parse(
            SourceLine(
                "surfaces.txt",
                14,
                "surface0-4999,!0-4999,0-4999,42",
            ),
        )

        assertEquals(emptySet<Int>(), result.selection.included)
        assertEquals((0..4_999).toSet(), result.selection.excluded)
        assertEquals(1, result.diagnostics.size)
    }

    @Test
    fun standalone_invalid_token_diagnostics_are_capped_and_a_trailing_scalar_recovers() {
        val invalid = (0 until 300).joinToString(",") { "broken$it" }
        val result = selector.parse(SourceLine("surfaces.txt", 15, "surface$invalid,2147483647"))

        assertEquals(linkedSetOf(Int.MAX_VALUE), result.selection.included)
        assertEquals(256, result.diagnostics.size)
    }

    @Test
    fun append_prefix_is_not_confused_with_a_normal_selector() {
        val result = selector.parse(SourceLine("surfaces.txt", 16, "surface.append1,3-4"))

        assertEquals(SurfaceBlockMode.APPEND_EXISTING, result.mode)
        assertEquals(linkedSetOf(1, 3, 4), result.selection.included)
    }

    @Test
    fun shared_budget_exhaustion_is_explicit_even_after_a_partial_selection() {
        var positiveCharges = 0
        val result = selector.parse(
            SourceLine("surfaces.txt", 17, "surface1,2"),
            SurfaceSelectorWorkBudget { amount ->
                if (amount == 0L || positiveCharges++ == 0) {
                    SurfaceBudgetCharge(accepted = true)
                } else {
                    SurfaceBudgetCharge(accepted = false, report = true)
                }
            },
        )

        assertEquals(linkedSetOf(1), result.selection.included)
        assertTrue(result.exhausted)
    }
}
