package com.cattailsw.nanidroid.runtime

import org.junit.Assert
import org.junit.Test

/** Locks the pure presentation boundary that the legacy view adapter consumes.  */
class GhostPresentationReducerTest {
    @Test
    fun requiredMigrationInvariant_snapshotPreservesSpeakerTextSurfaceAnimationAndBalloonPolicy() {
        val state = GhostPresentationReducer.snapshot(
            "Sakura text", "120", "3", "-1",
            "Kero text", "11", null, "0"
        )

        Assert.assertEquals("Sakura text", state.sakura.text)
        Assert.assertEquals("120", state.sakura.surfaceId)
        Assert.assertEquals("3", state.sakura.animationId)
        Assert.assertTrue(state.sakura.balloonVisible)

        Assert.assertEquals("Kero text", state.kero.text)
        Assert.assertEquals("11", state.kero.surfaceId)
        Assert.assertEquals(null, state.kero.animationId)
        Assert.assertTrue(state.kero.balloonVisible)
    }

    @Test
    fun requiredMigrationInvariant_emptyTextAndDisabledBalloonRemainHidden() {
        val state = GhostPresentationReducer.snapshot(
            "", "0", null, "-1",
            "", "10", null, "-1"
        )

        Assert.assertFalse(state.sakura.balloonVisible)
        Assert.assertFalse(state.kero.balloonVisible)
    }
}