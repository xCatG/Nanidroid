package com.cattailsw.nanidroid.compose.debug

import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.compose.SurfaceAnimationScheduler
import com.cattailsw.nanidroid.compose.SurfaceRenderAnimation
import com.cattailsw.nanidroid.compose.SurfaceRenderBase
import com.cattailsw.nanidroid.compose.SurfaceRenderClock
import com.cattailsw.nanidroid.compose.SurfaceRenderEntropy
import com.cattailsw.nanidroid.compose.SurfaceRenderFrame
import com.cattailsw.nanidroid.compose.SurfaceRenderPlan
import com.cattailsw.nanidroid.compose.ComposedSurface
import com.cattailsw.nanidroid.compose.SurfacePixelImage
import com.cattailsw.nanidroid.compose.stage.StageMeasuredSnapshot
import com.cattailsw.nanidroid.compose.stage.StageSurfaceSnapshot
import com.cattailsw.nanidroid.runtime.stage.StageDpRect
import com.cattailsw.nanidroid.runtime.stage.StageGeometryKey
import com.cattailsw.nanidroid.runtime.stage.StageLayoutDp
import com.cattailsw.nanidroid.runtime.stage.StageLayoutPx
import com.cattailsw.nanidroid.runtime.stage.StagePosture
import com.cattailsw.nanidroid.runtime.stage.StageSizingBaseline
import com.cattailsw.nanidroid.runtime.stage.StageWindowKey
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey
import com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx
import com.cattailsw.nanidroid.runtime.stage.StageMode
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.ShellSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugPanelStateTest {
    @Test
    fun debugSelectionReportsSchedulerAnimationAfterPresentationRequestIsConsumed() {
        val scheduler = SurfaceAnimationScheduler(
            plan = SurfaceRenderPlan(
                surfaceId = 1,
                width = 10,
                height = 10,
                base = SurfaceRenderBase.Missing,
                animations = listOf(
                    SurfaceRenderAnimation(
                        id = "talk",
                        interval = ShellSurface.A_TYPE_TALK,
                        exclusive = false,
                        frames = listOf(SurfaceRenderFrame.Reset(10), SurfaceRenderFrame.Reset(10)),
                        alternatives = emptyList(),
                    ),
                ),
            ),
            clock = SurfaceRenderClock { 0L },
            entropy = SurfaceRenderEntropy { 0.0 },
        )
        scheduler.presentationUpdated(
            hasVisibleSpeech = true,
            talkUpdate = com.cattailsw.nanidroid.compose.SurfaceTalkCadence.Update(true),
        )

        // The presentation frame has already been consumed, so it no longer
        // carries the talk request even though the scheduler is still active.
        val consumedPresentation = com.cattailsw.nanidroid.runtime.GhostPresentationRuntimeState.Initial
        assertNull(consumedPresentation.presentation.sakura.animationId)
        val selection = measured(activeAnimationId = scheduler.activeAnimationId).debugSelection(
            selectedSpeaker = SurfaceSpeaker.SAKURA,
            runtime = consumedPresentation,
        )

        assertEquals("talk", selection?.animationId)
        assertEquals(true, selection?.animationRunning)
    }

    @Test
    fun pointerDispatchOutcomeDistinguishesResolutionAndDispatchResult() {
        assertEquals(PointerDispatchOutcome.NOT_RESOLVED, pointerDispatchOutcome(null, null))
        assertEquals(
            PointerDispatchOutcome.REJECTED,
            pointerDispatchOutcome("OnMouseClick", false),
        )
        assertEquals(
            PointerDispatchOutcome.ACCEPTED,
            pointerDispatchOutcome("OnMouseClick", true),
        )
    }

    @Test
    fun ordinaryPanelDismissalClearsCollisionOverlay() {
        val state = DebugPanelState(
            visible = true,
            selectedSpeaker = SurfaceSpeaker.KERO,
            showCollisionOverlay = true,
        ).dismissDebugSurface()

        assertEquals(false, state.visible)
        assertEquals(false, state.showCollisionOverlay)
        assertNull(
            state.collisionOverlaySpeaker(
                loading = false,
                debugBuild = true,
            ),
        )
    }

    @Test
    fun showOnStageDismissesPanelWhileKeepingSelectedOverlayVisible() {
        val state = DebugPanelState(
            visible = true,
            selectedSpeaker = SurfaceSpeaker.KERO,
            showCollisionOverlay = true,
        ).showCollisionOverlayOnStage()

        assertEquals(false, state.visible)
        assertEquals(true, state.showCollisionOverlay)
        assertEquals(
            SurfaceSpeaker.KERO,
            state.collisionOverlaySpeaker(
                loading = false,
                debugBuild = true,
            ),
        )
    }

    @Test
    fun collisionOverlayStaysHiddenOutsideInteractiveDebugState() {
        val enabled = DebugPanelState(showCollisionOverlay = true)

        assertNull(enabled.collisionOverlaySpeaker(loading = true, debugBuild = true))
        assertNull(enabled.collisionOverlaySpeaker(loading = false, debugBuild = false))
        assertNull(
            DebugPanelState(showCollisionOverlay = false).collisionOverlaySpeaker(
                loading = false,
                debugBuild = true,
            ),
        )
    }

    @Test
    fun sampleFeedbackTokenChangesForEveryActivationAndClearsWhenPanelChanges() {
        val openPanel = DebugPanelState(visible = true)
        val firstActivation = openPanel.recordSampleFeedback()
        val secondActivation = firstActivation.recordSampleFeedback()

        assertNotEquals(firstActivation.sampleFeedbackToken, secondActivation.sampleFeedbackToken)
        assertEquals(0L, secondActivation.dismissDebugSurface().sampleFeedbackToken)
        assertEquals(0L, secondActivation.showDebugSurface().sampleFeedbackToken)
    }

    private fun measured(activeAnimationId: String?): StageMeasuredSnapshot {
        val content = StageDpRect(0.dp, 0.dp, 100.dp, 100.dp)
        val geometry = StageGeometryKey(
            ghostKey = "debug",
            windowKey = StageWindowKey(content, 1f, 0.dp, StagePosture.FLAT, emptyList()),
            mode = StageMode.STANDARD,
            content = content,
            keroRegion = null,
            sakuraRegion = null,
        )
        val layout = StageLayoutDp(
            mode = StageMode.STANDARD,
            content = content,
            keroLane = null,
            sakuraLane = null,
            keroBubble = null,
            sakuraBubble = null,
            keroSurfaceRegion = null,
            sakuraSurfaceRegion = null,
            keroSurface = null,
            sakuraSurface = null,
            sizingBaseline = StageSizingBaseline(geometry, 1f, null, null),
            tinyFallback = false,
        )
        return StageMeasuredSnapshot(
            layoutDp = layout,
            layoutPx = StageLayoutPx.from(layout, 1f),
            kero = null,
            sakura = StageSurfaceSnapshot(
                speaker = SurfaceSpeaker.SAKURA,
                composedSurface = ComposedSurface(
                    image = SurfacePixelImage.of(10, 10, IntArray(100)),
                    canvasSize = IntSize(10, 10),
                    visiblePixelBounds = IntRect(0, 0, 10, 10),
                    effectiveCollisions = emptyList(),
                    surfaceKey = SurfaceKey(1, IntSize(10, 10)),
                    revision = 1L,
                    explicitlyHidden = false,
                ),
                transform = SurfaceTransformPx(
                    intrinsicSize = IntSize(10, 10),
                    renderedBounds = IntRect(0, 0, 10, 10),
                    scale = 1f,
                    stageToRoot = androidx.compose.ui.unit.IntOffset.Zero,
                ),
                activeAnimationId = activeAnimationId,
            ),
        )
    }
}
