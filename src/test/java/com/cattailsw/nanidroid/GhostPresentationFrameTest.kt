package com.cattailsw.nanidroid

import org.junit.Assert
import org.junit.Test

/** Characterizes the UI-free visibility policy used by presentation renderers.  */
class GhostPresentationFrameTest {
    @Test
    fun retainsLegacyBalloonVisibilityPolicyForBothSpeakers() {
        val hidden: GhostPresentationFrame.Speaker =
            GhostPresentationFrame.Speaker("", "0", null, "-1")
        val visibleFromBalloon: GhostPresentationFrame.Speaker =
            GhostPresentationFrame.Speaker("", "10", null, "0")
        val visibleFromText: GhostPresentationFrame.Speaker =
            GhostPresentationFrame.Speaker("hello", "10", null, "-1")

        Assert.assertFalse(hidden.balloonVisible)
        Assert.assertTrue(visibleFromBalloon.balloonVisible)
        Assert.assertTrue(visibleFromText.balloonVisible)
    }
}
