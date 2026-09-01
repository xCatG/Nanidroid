package com.cattailsw.nanidroid

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntSize
import com.cattailsw.nanidroid.compose.SurfaceAnimationScheduleEffect
import com.cattailsw.nanidroid.compose.SurfaceAnimationScheduler
import com.cattailsw.nanidroid.compose.SurfaceRenderAnimation
import com.cattailsw.nanidroid.compose.SurfaceRenderClock
import com.cattailsw.nanidroid.compose.SurfaceRenderEntropy
import com.cattailsw.nanidroid.compose.SurfaceRenderFrame
import com.cattailsw.nanidroid.compose.SurfaceRenderPlan
import com.cattailsw.nanidroid.compose.SurfaceRenderBase
import com.cattailsw.nanidroid.compose.GhostPresentationStage
import com.cattailsw.nanidroid.compose.opaqueStageTestSurface
import com.cattailsw.nanidroid.compose.stage.GhostStageMeasureState
import com.cattailsw.nanidroid.runtime.GhostPresentationReducer
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Device contract for the Compose-only stage and its animation scheduler. */
class SurfaceAnimationExecutionCharacterizationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun composeStagePlacesVisibleSpeakerAndBalloonWithoutRetainedViews() {
        val presentation = GhostPresentationReducer.snapshot(
            sakuraText = "Compose animation",
            sakuraSurfaceId = "0",
            sakuraAnimationId = "3",
            sakuraBalloonId = "0",
            keroText = "",
            keroSurfaceId = "10",
            keroAnimationId = null,
            keroBalloonId = "-1",
        )
        val measureState = GhostStageMeasureState().also { it.resetFor(this) }
        val sakuraSurface = opaqueStageTestSurface(0, IntSize(120, 160))
        val keroSurface = opaqueStageTestSurface(10, IntSize(80, 120))
        composeRule.setContent {
            GhostPresentationStage(
                presentation = presentation,
                sakuraComposedSurface = sakuraSurface,
                keroComposedSurface = keroSurface,
                measureState = measureState,
                ghostKey = "surface-animation-characterization",
                sakuraDialogue = DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(DialogueSegment.Text(presentation.sakura.text)),
                ),
                sakuraSurface = { _ -> Text("Sakura surface 0") },
            )
        }

        composeRule.onNodeWithText("Sakura surface 0").assertIsDisplayed()
        composeRule.onNodeWithText("Compose animation").assertIsDisplayed()
    }

    @Test fun composeSchedulerStartsTalkAnimationAtItsFirstFrame() {
        val scheduler = SurfaceAnimationScheduler(
            SurfaceRenderPlan(
                surfaceId = 0,
                width = 120,
                height = 160,
                base = SurfaceRenderBase.Missing,
                animations = listOf(
                    SurfaceRenderAnimation(
                        id = "3",
                        interval = ShellSurface.A_TYPE_TALK,
                        exclusive = false,
                        frames = listOf(SurfaceRenderFrame.Reset(37)),
                        alternatives = emptyList(),
                    ),
                ),
            ),
            SurfaceRenderClock { 0L },
            SurfaceRenderEntropy { 0.0 },
        )

        assertEquals(
            listOf(SurfaceAnimationScheduleEffect.Frame("3", 0, SurfaceRenderFrame.Reset(37))),
            scheduler.presentationUpdated(true, talkingAnimationEnabled = true),
        )
    }
}
