package com.cattailsw.nanidroid.compose.stage

import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import androidx.window.testing.layout.FoldingFeature as TestFoldingFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class StageEnvironmentProviderTest {
    private val publisher = WindowLayoutInfoPublisherRule()
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(publisher).around(composeRule)

    @Test
    fun publisherUpdatesFlatBookAndTabletopPosturesWithoutActivityRecreation() {
        val observed = mutableStateOf<StageWindowEnvironment?>(null)
        val originalActivity = composeRule.activity
        composeRule.setContent {
            StageEnvironmentProvider { environment ->
                SideEffect { observed.value = environment }
            }
        }

        publish(emptyList())
        composeRule.waitUntil { observed.value?.displayFeatures?.isEmpty() == true }
        assertEquals(com.cattailsw.nanidroid.runtime.stage.StagePosture.FLAT, observed.value!!.posture)

        val bounds = Rect(0, 0, observed.value!!.windowSizePx.width, observed.value!!.windowSizePx.height)
        val book = TestFoldingFeature(
            windowBounds = bounds,
            center = bounds.centerX(),
            size = 10,
            state = FoldingFeature.State.FLAT,
            orientation = FoldingFeature.Orientation.VERTICAL,
        )
        publish(listOf(book))
        composeRule.waitUntil { observed.value?.posture == com.cattailsw.nanidroid.runtime.stage.StagePosture.BOOK }
        assertSame(originalActivity, composeRule.activity)

        val tabletop = TestFoldingFeature(
            windowBounds = bounds,
            center = bounds.centerY(),
            size = 12,
            state = FoldingFeature.State.HALF_OPENED,
            orientation = FoldingFeature.Orientation.HORIZONTAL,
        )
        publish(listOf(tabletop))
        composeRule.waitUntil { observed.value?.posture == com.cattailsw.nanidroid.runtime.stage.StagePosture.TABLETOP }
        assertSame(originalActivity, composeRule.activity)

        publish(emptyList())
        composeRule.waitUntil { observed.value?.displayFeatures?.isEmpty() == true }
        assertEquals(com.cattailsw.nanidroid.runtime.stage.StagePosture.FLAT, observed.value!!.posture)
    }

    @Test
    fun stageAdapterKeepsWindowLocalInsetsAndFeaturesInOneCoordinateSpace() {
        val first = FixedFeature(Rect(40, 100, 80, 260))
        val second = FixedFeature(Rect(300, 20, 320, 400))
        val window = StageWindowEnvironment.forTest(
            windowSizePx = IntSize(500, 900),
            safeBoundsInWindowPx = IntRect(20, 40, 480, 860),
            density = 2f,
            fontScale = 1f,
            displayFeatures = listOf(second, first),
        )

        val environment = window.toStageEnvironment(
            stageBoundsInWindowPx = IntRect(20, 40, 480, 860),
            canonicalAppBarHeight = 64.dp,
            ghostKey = "fixture",
        )

        assertEquals(DpSize(230.dp, 410.dp), environment.safeSize)
        assertEquals(0.dp, environment.safeBounds.left)
        assertEquals(0.dp, environment.safeBounds.top)
        assertEquals(64.dp, environment.canonicalAppBarHeight)
        assertEquals(
            listOf(
                com.cattailsw.nanidroid.runtime.stage.StageDpRect(140.dp, (-10).dp, 150.dp, 180.dp),
                com.cattailsw.nanidroid.runtime.stage.StageDpRect(10.dp, 30.dp, 30.dp, 110.dp),
            ),
            environment.displayFeatures.map { it.bounds },
        )
    }

    @Test
    fun imeInsetsDoNotChangeStageClassificationBounds() {
        val common = StageWindowEnvironment.forTest(
            windowSizePx = IntSize(500, 900),
            safeBoundsInWindowPx = IntRect(20, 40, 480, 860),
            density = 2f,
            fontScale = 1f,
            displayFeatures = emptyList(),
        )
        val withIme = StageWindowEnvironment.forTest(
            windowSizePx = IntSize(500, 900),
            safeBoundsInWindowPx = IntRect(20, 40, 480, 860),
            density = 2f,
            fontScale = 1f,
            displayFeatures = emptyList(),
            imeInsetsPx = IntInsetsPx(0, 0, 0, 500),
        )

        val stageBounds = IntRect(20, 40, 480, 860)
        assertEquals(
            common.toStageEnvironment(stageBounds, 64.dp, "fixture"),
            withIme.toStageEnvironment(stageBounds, 64.dp, "fixture"),
        )
    }

    @Test
    fun multipleFeaturesAreOrderStableAndRemovalIsObservable() {
        val observed = mutableStateOf<StageWindowEnvironment?>(null)
        composeRule.setContent {
            StageEnvironmentProvider { environment -> SideEffect { observed.value = environment } }
        }
        publish(emptyList())
        composeRule.waitUntil { observed.value != null }
        val width = observed.value!!.windowSizePx.width
        val height = observed.value!!.windowSizePx.height
        val vertical = TestFoldingFeature(
            Rect(0, 0, width, height), width / 3, 8,
            FoldingFeature.State.FLAT, FoldingFeature.Orientation.VERTICAL,
        )
        val horizontal = TestFoldingFeature(
            Rect(0, 0, width, height), height / 2, 0,
            FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.HORIZONTAL,
        )

        publish(listOf(vertical, horizontal))
        composeRule.waitUntil { observed.value?.displayFeatures?.size == 2 }
        val forward = observed.value!!.displayFeatures
        publish(listOf(horizontal, vertical))
        composeRule.waitUntil { observed.value?.displayFeatures?.size == 2 }
        assertEquals(forward, observed.value!!.displayFeatures)

        publish(listOf(vertical))
        composeRule.waitUntil { observed.value?.displayFeatures?.size == 1 }
        assertTrue(observed.value!!.displayFeatures.single().separating)
    }

    @Test
    fun lifecycleStopCancelsCollectionAndRestartReceivesTheLatestLayout() {
        val observed = mutableStateOf<StageWindowEnvironment?>(null)
        composeRule.setContent {
            StageEnvironmentProvider { environment -> SideEffect { observed.value = environment } }
        }
        publish(emptyList())
        composeRule.waitUntil { observed.value != null }
        val before = observed.value

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.waitForIdle()
        val width = before!!.windowSizePx.width
        val height = before.windowSizePx.height
        val feature = TestFoldingFeature(
            Rect(0, 0, width, height), width / 2, 10,
            FoldingFeature.State.FLAT, FoldingFeature.Orientation.VERTICAL,
        )
        publish(listOf(feature))
        composeRule.runOnIdle { assertEquals(before, observed.value) }

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        publish(listOf(feature))
        composeRule.waitUntil { observed.value?.displayFeatures?.size == 1 }
        assertFalse(observed.value!!.displayFeatures.isEmpty())
    }

    private fun publish(features: List<DisplayFeature>) {
        publisher.overrideWindowLayoutInfo(WindowLayoutInfo(features))
    }

    private data class FixedFeature(private val rect: Rect) : DisplayFeature {
        override val bounds: Rect get() = Rect(rect)
    }
}
