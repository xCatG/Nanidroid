package com.cattailsw.nanidroid.runtime.dialogue

import com.cattailsw.nanidroid.runtime.GhostSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SakuraScriptRevelationTest {
    @Test
    fun incompleteAnchorIsProgressivePlainTextUntilItsClosingTokenMakesItInteractive() {
        val partial = SakuraScriptTokenizer.tokenizeRevealed("\\hA\\_a[target]Li")
        assertEquals(
            listOf(DialogueSegment.Text("ALi")),
            partial.single().segments,
        )

        val complete = SakuraScriptTokenizer.tokenizeRevealed("\\hA\\_a[target]Link\\_a")
        assertEquals(GhostSpeaker.SAKURA, complete.single().speaker)
        assertFalse(complete.single().segments.any { it is DialogueSegment.Text && it.value.contains("Link") })
        val action = complete.single().segments.filterIsInstance<DialogueSegment.Anchor>().single().action
        val label = when (action) {
            is AnchorAction.Normal -> action.label
            is AnchorAction.DirectEvent -> action.label
        }
        assertEquals("Link", label)
    }
}
