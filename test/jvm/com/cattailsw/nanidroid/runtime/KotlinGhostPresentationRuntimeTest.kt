package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.GhostPresentationFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinGhostPresentationRuntimeTest {
    @Test
    fun render_emitsTheLegacyRendererEffectTraceInOrder() {
        val frame = GhostPresentationFrame(
            GhostPresentationFrame.Speaker("Sakura", "4", null, "0"),
            GhostPresentationFrame.Speaker("", "12", "7", "-1"),
            true,
        )

        val transition = GhostPresentationRuntimeReducer.reduce(
            GhostPresentationRuntimeState.Initial,
            GhostPresentationRuntimeEvent.Render(frame),
        )

        assertEquals(
            listOf(
                GhostPresentationRuntimeEffect.SurfaceChanged(GhostSpeaker.SAKURA, "4"),
                GhostPresentationRuntimeEffect.SurfaceChanged(GhostSpeaker.KERO, "12"),
                GhostPresentationRuntimeEffect.BalloonVisibilityChanged(GhostSpeaker.SAKURA, true),
                GhostPresentationRuntimeEffect.BalloonTextChanged(GhostSpeaker.SAKURA, "Sakura"),
                GhostPresentationRuntimeEffect.TalkingAnimationRequested(GhostSpeaker.SAKURA),
                GhostPresentationRuntimeEffect.BalloonVisibilityChanged(GhostSpeaker.KERO, false),
                GhostPresentationRuntimeEffect.LayoutRefreshRequested,
                GhostPresentationRuntimeEffect.OneShotAnimationRequested(GhostSpeaker.KERO, "7"),
            ),
            transition.effects,
        )
        assertEquals("4", transition.state.presentation.sakura.surfaceId)
        assertEquals("12", transition.state.presentation.kero.surfaceId)
        assertTrue(transition.state.presentation.sakura.balloonVisible)
        assertFalse(transition.state.presentation.kero.balloonVisible)
        assertEquals(1L, transition.state.revision)
    }

    @Test
    fun explicitAnimationSuppressesTalkingButKeepsVisibleBalloonText() {
        val frame = GhostPresentationFrame(
            GhostPresentationFrame.Speaker("hello", "0", "3", "-1"),
            GhostPresentationFrame.Speaker("", "10", null, "-1"),
            true,
        )

        val transition = GhostPresentationRuntimeReducer.reduce(
            GhostPresentationRuntimeState.Initial,
            GhostPresentationRuntimeEvent.Render(frame),
        )

        assertEquals(
            listOf(
                GhostPresentationRuntimeEffect.SurfaceChanged(GhostSpeaker.SAKURA, "0"),
                GhostPresentationRuntimeEffect.SurfaceChanged(GhostSpeaker.KERO, "10"),
                GhostPresentationRuntimeEffect.BalloonVisibilityChanged(GhostSpeaker.SAKURA, true),
                GhostPresentationRuntimeEffect.BalloonTextChanged(GhostSpeaker.SAKURA, "hello"),
                GhostPresentationRuntimeEffect.BalloonVisibilityChanged(GhostSpeaker.KERO, false),
                GhostPresentationRuntimeEffect.LayoutRefreshRequested,
                GhostPresentationRuntimeEffect.OneShotAnimationRequested(GhostSpeaker.SAKURA, "3"),
            ),
            transition.effects,
        )
    }

    @Test
    fun runtimeImplementsTheExistingRendererAbiWithoutRetainingViews() {
        val transitions = mutableListOf<GhostPresentationRuntimeTransition>()
        val renderer = KotlinGhostPresentationRuntime { transitions += it }

        renderer.render(
            GhostPresentationFrame(
                GhostPresentationFrame.Speaker("", "0", null, "-1"),
                GhostPresentationFrame.Speaker("K", "10", null, "0"),
                false,
            ),
        )

        assertEquals(1, transitions.size)
        assertEquals(transitions.single().state, renderer.state)
        assertEquals("K", renderer.state.presentation.kero.text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun renderRejectsMissingSurfaceIdLikeTheLegacyRenderer() {
        GhostPresentationRuntimeReducer.reduce(
            GhostPresentationRuntimeState.Initial,
            GhostPresentationRuntimeEvent.Render(
                GhostPresentationFrame(
                    GhostPresentationFrame.Speaker("", null, null, "-1"),
                    GhostPresentationFrame.Speaker("", "10", null, "-1"),
                    false,
                ),
            ),
        )
    }
}
