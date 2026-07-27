package com.cattailsw.nanidroid.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BalloonPresentationTest {
    @Test
    fun `selected balloon is visible even before script text arrives`() {
        val presentation = BalloonPresentationReducer.render("0", "", layout())

        assertTrue(presentation.visible)
        assertEquals(BalloonLinkification.ALL, presentation.linkification)
    }

    @Test
    fun `unselected balloon is hidden only when its text is empty`() {
        assertFalse(BalloonPresentationReducer.render("-1", "", layout()).visible)
        assertTrue(BalloonPresentationReducer.render("-1", "Hello", layout()).visible)
        assertFalse(BalloonPresentationReducer.render(null, null, layout()).visible)
    }

    @Test
    fun `update resets scroll refreshes links then scrolls an overflowing layout to its final line`() {
        val presentation = BalloonPresentationReducer.render(
            balloonId = "0",
            text = "https://example.test",
            layout = layout(lastLineBottom = 140, height = 100, top = 10, bottom = 10),
        )

        assertEquals(
            listOf(
                BalloonPresentationEffect.ResetScroll,
                BalloonPresentationEffect.RefreshLinks,
                BalloonPresentationEffect.EnableScrolling,
                BalloonPresentationEffect.ScrollBy(60),
            ),
            presentation.effects,
        )
    }

    @Test
    fun `fitting content refreshes link detection and disables scrolling`() {
        val presentation = BalloonPresentationReducer.render("0", "mailto:test@example.test", layout(80, 100, 10, 10))

        assertEquals(
            listOf(
                BalloonPresentationEffect.ResetScroll,
                BalloonPresentationEffect.RefreshLinks,
                BalloonPresentationEffect.DisableScrolling,
            ),
            presentation.effects,
        )
    }

    @Test
    fun `unmeasured text retains a deferred scroll decision instead of guessing`() {
        val presentation = BalloonPresentationReducer.render("0", "still rendering")

        assertEquals(
            listOf(
                BalloonPresentationEffect.ResetScroll,
                BalloonPresentationEffect.RefreshLinks,
                BalloonPresentationEffect.AwaitMeasurement,
            ),
            presentation.effects,
        )
    }

    @Test
    fun `invalid dimensions cannot create a negative scroll offset`() {
        val presentation = BalloonPresentationReducer.render(
            "0",
            "safe",
            layout(lastLineBottom = -10, height = -1, top = -2, bottom = -3),
        )

        assertEquals(BalloonPresentationEffect.DisableScrolling, presentation.effects.last())
    }

    private fun layout(
        lastLineBottom: Int = 1,
        height: Int = 10,
        top: Int = 0,
        bottom: Int = 0,
    ) = BalloonTextLayout(lastLineBottom, height, top, bottom)
}
