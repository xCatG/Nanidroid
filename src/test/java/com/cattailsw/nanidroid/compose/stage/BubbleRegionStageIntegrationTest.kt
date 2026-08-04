package com.cattailsw.nanidroid.compose.stage

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.compose.currentStageInputSnapshot
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.stage.BubbleInteractionTarget
import com.cattailsw.nanidroid.runtime.stage.BubbleRegionFence
import com.cattailsw.nanidroid.runtime.stage.BubbleRegionSet
import com.cattailsw.nanidroid.runtime.stage.MeasuredBubbleHitRegion
import com.cattailsw.nanidroid.runtime.stage.StageDpRect
import com.cattailsw.nanidroid.runtime.stage.StageGeometryKey
import com.cattailsw.nanidroid.runtime.stage.StageInputRouter
import com.cattailsw.nanidroid.runtime.stage.StageInputTarget
import com.cattailsw.nanidroid.runtime.stage.StageLayoutDp
import com.cattailsw.nanidroid.runtime.stage.StageLayoutPx
import com.cattailsw.nanidroid.runtime.stage.StageMode
import com.cattailsw.nanidroid.runtime.stage.StagePosture
import com.cattailsw.nanidroid.runtime.stage.StageSizingBaseline
import com.cattailsw.nanidroid.runtime.stage.StageWindowKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleRegionStageIntegrationTest {
    @Test
    fun `committed publication is authoritative and stale callbacks cannot restore removed geometry`() {
        val state = GhostStageMeasureState().also { it.resetFor(Any()) }
        val frame = IntRect(100, 20, 220, 180)
        val fence = BubbleRegionFence(SurfaceSpeaker.SAKURA, talkId = 7L, contentRevision = 11L, frame)
        val action = AnchorAction.Normal("Topic", "topic", listOf("one", "", "three"))
        state.commit(snapshot(active = mapOf(SurfaceSpeaker.SAKURA to fence)))

        assertTrue(
            state.publishBubbleRegions(
                BubbleRegionSet(
                    fence = fence,
                    actionRegions = listOf(
                        MeasuredBubbleHitRegion(
                            IntRect(108, 32, 208, 80),
                            BubbleInteractionTarget.Anchor(action),
                        ),
                    ),
                    scrollViewport = frame,
                ),
            ),
        )
        val published = currentStageInputSnapshot(
            measured = state.latest,
            blocking = false,
            ghostKey = "fixture",
            ghostIdentity = "owner",
        )
        assertEquals(
            StageInputTarget.Bubble(BubbleInteractionTarget.Anchor(action)),
            StageInputRouter.resolve(published, Offset(108f, 32f), PointerSource.TOUCH, 0).target,
        )
        assertEquals(
            StageInputTarget.Bubble(BubbleInteractionTarget.Scroll(SurfaceSpeaker.SAKURA)),
            StageInputRouter.resolve(published, Offset(215f, 100f), PointerSource.TOUCH, 0).target,
        )

        state.commit(snapshot(active = emptyMap()))
        val removed = currentStageInputSnapshot(
            measured = state.latest,
            blocking = false,
            ghostKey = "fixture",
            ghostIdentity = "owner",
        )

        assertEquals(StageInputTarget.EmptyStage, StageInputRouter.resolve(removed, Offset(108f, 32f), PointerSource.TOUCH, 0).target)
        assertNotEquals(published.geometryToken, removed.geometryToken)
        assertFalse(
            state.publishBubbleRegions(
                BubbleRegionSet(
                    fence = fence,
                    actionRegions = emptyList(),
                    scrollViewport = frame,
                ),
            ),
        )
        assertTrue(state.latest!!.bubbleRegions.isEmpty())
    }

    @Test
    fun `content replacement invalidates routing before the replacement child publishes`() {
        val state = GhostStageMeasureState().also { it.resetFor(Any()) }
        val frame = IntRect(0, 0, 120, 160)
        val firstFence = BubbleRegionFence(SurfaceSpeaker.KERO, 12L, 20L, frame)
        val replacementFence = firstFence.copy(contentRevision = 21L)
        state.commit(snapshot(active = mapOf(SurfaceSpeaker.KERO to firstFence)))
        assertTrue(
            state.publishBubbleRegions(
                BubbleRegionSet(firstFence, emptyList(), frame),
            ),
        )
        val oldToken = currentStageInputSnapshot(state.latest, false, "fixture", "owner").geometryToken
        val oldEpoch = state.inputEpoch

        state.commit(snapshot(active = mapOf(SurfaceSpeaker.KERO to replacementFence)))

        assertTrue(state.latest!!.bubbleRegions.isEmpty())
        assertTrue(state.inputEpoch > oldEpoch)
        assertNotEquals(oldToken, currentStageInputSnapshot(state.latest, false, "fixture", "owner").geometryToken)
        assertFalse(state.publishBubbleRegions(BubbleRegionSet(firstFence, emptyList(), frame)))
        assertTrue(state.publishBubbleRegions(BubbleRegionSet(replacementFence, emptyList(), frame)))
    }

    private fun snapshot(active: Map<SurfaceSpeaker, BubbleRegionFence>): StageMeasuredSnapshot {
        val content = StageDpRect(0.dp, 0.dp, 300.dp, 400.dp)
        val geometry = StageGeometryKey(
            ghostKey = "fixture",
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
            sakura = null,
            activeBubbleFences = active,
        )
    }
}
