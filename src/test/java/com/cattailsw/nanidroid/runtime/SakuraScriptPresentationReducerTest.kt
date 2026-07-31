package com.cattailsw.nanidroid.runtime

import org.junit.Assert
import org.junit.Test

/** Locks the state transitions that will replace SScriptRunner's mutable fields.  */
class SakuraScriptPresentationReducerTest {
    @Test
    fun requiredMigrationInvariant_scriptResetKeepsSurfacesButClearsTransientPresentation() {
        val changed =
            SakuraScriptPresentationReducer.queueAnimation(
                SakuraScriptPresentationReducer.changeBalloon(
                    SakuraScriptPresentationReducer.changeSurface(
                        SakuraScriptPresentationReducer.selectSpeaker(
                            SakuraScriptPresentationReducer.initial(), GhostSpeaker.KERO
                        ),
                        "42"
                    ),
                    "7"
                ),
                "3"
            )

        val reset = SakuraScriptPresentationReducer.resetForNextScript(changed)

        Assert.assertEquals(GhostSpeaker.SAKURA, reset.activeSpeaker)
        Assert.assertEquals("0", reset.sakuraSurfaceId)
        Assert.assertEquals("42", reset.keroSurfaceId)
        Assert.assertEquals("-1", reset.sakuraBalloonId)
        Assert.assertEquals("-1", reset.keroBalloonId)
        Assert.assertNull(reset.sakuraAnimationId)
        Assert.assertNull(reset.keroAnimationId)
    }

    @Test
    fun requiredMigrationInvariant_synchronizationAndKeroTextPreserveLegacyBalloonPolicy() {
        var state =
            SakuraScriptPresentationReducer.toggleSynchronization(
                SakuraScriptPresentationReducer.resetForNextScript(
                    SakuraScriptPresentationReducer.initial()
                )
            )
        state = SakuraScriptPresentationReducer.append(state, 'A')
        var snapshot = SakuraScriptPresentationReducer.snapshot(state)

        Assert.assertEquals("A", snapshot.sakura.text)
        Assert.assertEquals("A", snapshot.kero.text)
        Assert.assertTrue(snapshot.kero.balloonVisible)

        state = SakuraScriptPresentationReducer.clearActiveText(state)
        snapshot = SakuraScriptPresentationReducer.snapshot(state)
        Assert.assertFalse(snapshot.sakura.balloonVisible)
        Assert.assertTrue(snapshot.kero.balloonVisible)
    }

    @Test
    fun requiredMigrationInvariant_reselectingCurrentSpeakerRetainsItsText() {
        var state = SakuraScriptPresentationReducer.resetForNextScript(
            SakuraScriptPresentationReducer.initial()
        )
        state = SakuraScriptPresentationReducer.append(state, 'A')
        state = SakuraScriptPresentationReducer.selectSpeaker(state, GhostSpeaker.SAKURA)
        state = SakuraScriptPresentationReducer.append(state, 'B')

        Assert.assertEquals("AB", state.sakuraText)

        state = SakuraScriptPresentationReducer.selectSpeaker(state, GhostSpeaker.KERO)
        state = SakuraScriptPresentationReducer.append(state, 'C')
        state = SakuraScriptPresentationReducer.selectSpeaker(state, GhostSpeaker.SAKURA)
        Assert.assertEquals("", state.sakuraText)
        Assert.assertEquals("C", state.keroText)
    }

    @Test
    fun requiredMigrationInvariant_animationIsVisibleOnceThenExplicitlyConsumed() {
        val queued = SakuraScriptPresentationReducer.queueAnimation(
            SakuraScriptPresentationReducer.initial(), "3"
        )

        Assert.assertEquals(
            "3", SakuraScriptPresentationReducer.snapshot(queued).sakura.animationId
        )
        Assert.assertNull(
            SakuraScriptPresentationReducer.snapshot(
                SakuraScriptPresentationReducer.consumeAnimations(queued)
            ).sakura.animationId
        )
    }
}