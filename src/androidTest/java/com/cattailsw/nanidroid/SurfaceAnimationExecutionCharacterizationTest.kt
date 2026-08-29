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
import com.cattailsw.nanidroid.compose.SurfaceTalkCadence
import com.cattailsw.nanidroid.compose.SizedGhostPresentationStage
import com.cattailsw.nanidroid.runtime.runtimePresentation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Device contract for the Compose-only stage and its animation scheduler. */
class SurfaceAnimationExecutionCharacterizationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun composeStagePlacesVisibleSpeakerAndBalloonWithoutRetainedViews() {
        composeRule.setContent {
            SizedGhostPresentationStage(
                presentation = runtimePresentation(
                    sakuraText = "Compose animation",
                    sakuraSurfaceId = "0",
                    sakuraAnimationId = "3",
                    sakuraBalloonId = "0",
                    keroText = "",
                    keroSurfaceId = "10",
                    keroAnimationId = null,
                    keroBalloonId = "-1",
                ),
                sakuraSurfaceSize = IntSize(120, 160),
                keroSurfaceSize = IntSize(80, 120),
                sakuraSurface = { Text("Sakura surface 0") },
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
            scheduler.presentationUpdated(true, talkUpdate = SurfaceTalkCadence().nextPresentationUpdate()),
        )
    }
}
