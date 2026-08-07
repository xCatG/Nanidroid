package com.cattailsw.nanidroid.corpus

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NarCorpusSourceSyntaxInspectorTest {
    @Test
    fun inspects_real_normal_selector_without_a_dot_after_surface() {
        val declaration = NarCorpusSourceSyntaxInspector.inspect(
            file = "shell/master/surfaces.txt",
            lineNumber = 1,
            rawLine = "surface10,6000-6009,!6001,!6004,6550-6559,!6551,!6554",
        )

        assertNotNull(declaration)
        requireNotNull(declaration)
        assertFalse(declaration.isAppend)
        assertTrue(declaration.hasCommaSelectors)
        assertTrue(declaration.hasRangeSelectors)
        assertTrue(declaration.hasExclusionSelectors)
        assertTrue(6000 in declaration.includedIds)
        assertTrue(6559 in declaration.includedIds)
        assertEquals(setOf(6001, 6004, 6551, 6554), declaration.excludedIds)
    }

    @Test
    fun inspects_real_append_selector_without_a_dot_after_append() {
        val declaration = NarCorpusSourceSyntaxInspector.inspect(
            file = "shell/master/surfaces.txt",
            lineNumber = 2,
            rawLine = "surface.append0-9 // dressups",
        )

        assertNotNull(declaration)
        requireNotNull(declaration)
        assertTrue(declaration.isAppend)
        assertFalse(declaration.hasCommaSelectors)
        assertTrue(declaration.hasRangeSelectors)
        assertFalse(declaration.hasExclusionSelectors)
        assertEquals((0..9).toSet(), declaration.includedIds)
    }

    @Test
    fun ignores_non_selector_source_lines() {
        val declaration = NarCorpusSourceSyntaxInspector.inspect(
            file = "ghost/master/example.dic",
            lineNumber = 3,
            rawLine = "OnBoot { return `hello`; }",
        )

        assertEquals(null, declaration)
    }
}
