package com.cattailsw.nanidroid.compose.stage

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.compose.ComposedSurface
import com.cattailsw.nanidroid.compose.SurfacePixelImage
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.stage.BubbleHitRegionRegistry
import com.cattailsw.nanidroid.runtime.stage.BubbleInteractionTarget
import com.cattailsw.nanidroid.runtime.stage.MeasuredBubbleHitRegion
import com.cattailsw.nanidroid.runtime.stage.StageInputRouter
import com.cattailsw.nanidroid.runtime.stage.StageInputSnapshot
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey
import com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx
import com.cattailsw.nanidroid.surface.CollisionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StagePointerInputTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun touchSurfaceDispatchesOnceAndEmptyStageOnlyTogglesChrome() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var toggles = 0
        setStage({ snapshot(surfaces = listOf(surface(IntRect(0, 0, 100, 100)))) }, effects, toggle = { toggles++ })

        composeRule.onNodeWithTag(TAG).performTouchInput { click(Offset(50f, 50f)) }
        composeRule.waitForIdle()
        assertEquals(1, effects.size)
        assertEquals(PointerSource.TOUCH, effects.single().source)
        assertEquals(PointerEventKind.CLICK, effects.single().kind)
        assertEquals(0, toggles)

        composeRule.onNodeWithTag(TAG).performTouchInput { click(Offset(180f, 180f)) }
        composeRule.waitForIdle()
        assertEquals(1, effects.size)
        assertEquals(1, toggles)
    }

    @Test
    fun bubbleFrameBlocksSurfaceAndAbsentFrameDoesNot() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var toggles = 0
        val state = mutableStateOf(
            snapshot(
                bubbles = listOf(
                    MeasuredBubbleHitRegion(
                        IntRect(0, 0, 100, 100),
                        BubbleInteractionTarget.Frame(SurfaceSpeaker.SAKURA),
                    ),
                ),
                surfaces = listOf(surface(IntRect(0, 0, 100, 100))),
            ),
        )
        setStage({ state.value }, effects, toggle = { toggles++ })

        composeRule.onNodeWithTag(TAG).performTouchInput { click(Offset(50f, 50f)) }
        composeRule.runOnIdle {
            assertTrue(effects.isEmpty())
            assertEquals(0, toggles)
            state.value = snapshot(surfaces = listOf(surface(IntRect(0, 0, 100, 100))))
        }
        composeRule.onNodeWithTag(TAG).performTouchInput { click(Offset(50f, 50f)) }
        composeRule.runOnIdle { assertEquals(1, effects.size) }
    }

    @Test
    fun slopMultiPointerLeavingScopeAndGeometryChangesCancel() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        val state = mutableStateOf(snapshot(surfaces = listOf(surface(IntRect(0, 0, 100, 100)))))
        setStage({ state.value }, effects, toggle = {})

        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(Offset(20f, 20f))
            moveTo(Offset(80f, 80f))
            up()
        }
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, Offset(20f, 20f))
            down(1, Offset(25f, 25f))
            up(1)
            up(0)
        }
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(Offset(20f, 20f))
            moveTo(Offset(150f, 150f))
            up()
        }
        composeRule.onNodeWithTag(TAG).performTouchInput { down(Offset(20f, 20f)) }
        composeRule.runOnIdle {
            state.value = snapshot(surfaces = listOf(surface(IntRect(100, 100, 200, 200))))
        }
        composeRule.onNodeWithTag(TAG).performTouchInput { up() }
        composeRule.runOnIdle { assertTrue(effects.isEmpty()) }
    }

    @Test
    fun rasterOnlyRevisionContinuesButResizeUsesCurrentTransform() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        val first = surface(IntRect(0, 0, 100, 100), revision = 1)
        val state = mutableStateOf(snapshot(surfaces = listOf(first)))
        setStage({ state.value }, effects, toggle = {})
        composeRule.onNodeWithTag(TAG).performTouchInput { down(Offset(20f, 20f)) }
        composeRule.runOnIdle {
            state.value = snapshot(surfaces = listOf(first.copy(composedSurface = first.composedSurface.copy(revision = 2))))
        }
        composeRule.onNodeWithTag(TAG).performTouchInput { up() }
        composeRule.runOnIdle { assertEquals(1, effects.size) }

        composeRule.runOnIdle { state.value = snapshot(surfaces = listOf(surface(IntRect(100, 100, 200, 200)))) }
        composeRule.onNodeWithTag(TAG).performTouchInput { click(Offset(150f, 150f)) }
        composeRule.runOnIdle { assertEquals(2, effects.size) }
    }

    @Test
    fun mouseWaitsForSingleAndPointerTypesMapFromActualEvents() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        setStage({ snapshot(surfaces = listOf(surface(IntRect(0, 0, 200, 200)))) }, effects, toggle = {})

        composeRule.onNodeWithTag(TAG).performMouseInput { click(Offset(40f, 40f)) }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()
        assertEquals(PointerSource.MOUSE, effects.single().source)
        assertEquals(PointerSource.TOUCH, PointerType.Touch.toPointerSource())
        assertEquals(PointerSource.MOUSE, PointerType.Mouse.toPointerSource())
        assertEquals(PointerSource.PEN, PointerType.Stylus.toPointerSource())
        assertEquals(PointerSource.ERASER, PointerType.Eraser.toPointerSource())
    }

    @Test
    fun pendingPhysicalSingleIsCancelledWhenBlockingStateChanges() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        val surface = surface(IntRect(0, 0, 200, 200))
        val state = mutableStateOf(snapshot(surfaces = listOf(surface)))
        setStage({ state.value }, effects, toggle = {})

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(TAG).performMouseInput { click(Offset(40f, 40f)) }
        composeRule.runOnIdle {
            state.value = StageInputRouter.snapshot(
                blocking = true,
                bubbleRegistry = BubbleHitRegionRegistry.Empty,
                bubbleGeneration = 0,
                ghostKey = "device-fixture",
                surfaces = listOf(surface),
            )
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        assertTrue(effects.isEmpty())
    }

    @Test
    fun primaryStylusAndEraserEventsDispatchWhileSecondaryHoverAndWheelDoNot() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        setStage({ snapshot(surfaces = listOf(surface(IntRect(0, 0, 300, 300)))) }, effects, toggle = {})

        injectToolClick(MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS, 40f, 40f)
        composeRule.mainClock.advanceTimeBy(600)
        injectToolClick(MotionEvent.TOOL_TYPE_ERASER, InputDevice.SOURCE_STYLUS, 80f, 80f)
        composeRule.mainClock.advanceTimeBy(600)
        injectToolClick(
            MotionEvent.TOOL_TYPE_MOUSE,
            InputDevice.SOURCE_MOUSE,
            120f,
            120f,
            buttonState = MotionEvent.BUTTON_SECONDARY,
        )
        injectNonActivation(MotionEvent.ACTION_HOVER_MOVE, InputDevice.SOURCE_MOUSE, 100f, 100f)
        injectNonActivation(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_MOUSE, 100f, 100f)
        composeRule.waitForIdle()

        assertEquals(listOf(PointerSource.PEN, PointerSource.ERASER), effects.map { it.source })
    }

    @Test
    fun surfaceAndEmptySemanticsUseCentralResolverWithoutNestedDuplicate() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var toggles = 0
        val state = mutableStateOf(snapshot(surfaces = listOf(surface(IntRect(0, 0, 100, 100)))))
        setStage({ state.value }, effects, { toggles++ }, includeSurfaceSemantic = true)

        composeRule.onNodeWithTag(SURFACE_TAG, useUnmergedTree = true)
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle {
            assertEquals(1, effects.size)
            assertEquals(0, toggles)
            state.value = snapshot()
        }
        composeRule.onNodeWithTag(TAG).assertHasClickAction().performClick()
        composeRule.runOnIdle {
            assertEquals(1, effects.size)
            assertEquals(1, toggles)
        }
    }

    private fun setStage(
        snapshot: () -> StageInputSnapshot,
        effects: MutableList<SurfaceInteractionEffect>,
        toggle: () -> Unit,
        includeSurfaceSemantic: Boolean = false,
    ) {
        composeRule.mainClock.autoAdvance = true
        composeRule.setContent {
            StagePointerInput(
                snapshotProvider = snapshot,
                onSurfaceEffect = effects::add,
                onToggleChrome = toggle,
                modifier = Modifier.fillMaxSize().testTag(TAG),
            ) { semanticActivate ->
                if (includeSurfaceSemantic) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .stageSurfaceSemantics(SURFACE_TAG, SurfaceSpeaker.SAKURA, semanticActivate),
                    )
                }
            }
        }
    }

    private fun snapshot(
        bubbles: List<MeasuredBubbleHitRegion> = emptyList(),
        surfaces: List<StageSurfaceSnapshot> = emptyList(),
    ) = StageInputRouter.snapshot(
        blocking = false,
        bubbleRegistry = BubbleHitRegionRegistry.from(bubbles),
        bubbleGeneration = bubbles.hashCode().toLong(),
        ghostKey = "device-fixture",
        surfaces = surfaces,
    )

    private fun surface(bounds: IntRect, revision: Long = 1): StageSurfaceSnapshot {
        val size = IntSize(100, 100)
        val collision = SurfaceCollision(2, "Face", CollisionShape.Rectangle(IntRect(0, 0, 30, 30)), 0)
        val composed = ComposedSurface(
            SurfacePixelImage.of(size.width, size.height, IntArray(size.width * size.height) { 0xff102030.toInt() }),
            size,
            IntRect(0, 0, 100, 100),
            listOf(collision),
            SurfaceKey(0, size),
            revision,
            false,
        )
        return StageSurfaceSnapshot(
            SurfaceSpeaker.SAKURA,
            composed,
            SurfaceTransformPx(size, bounds, bounds.width / 100f, IntOffset.Zero),
        )
    }

    private fun injectToolClick(
        tool: Int,
        source: Int,
        x: Float,
        y: Float,
        buttonState: Int = MotionEvent.BUTTON_PRIMARY,
    ) {
        val downTime = SystemClock.uptimeMillis()
        dispatchMotion(downTime, downTime, MotionEvent.ACTION_DOWN, tool, source, x, y, buttonState)
        dispatchMotion(downTime, downTime + 10, MotionEvent.ACTION_UP, tool, source, x, y, 0)
    }

    private fun injectNonActivation(action: Int, source: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        dispatchMotion(now, now, action, MotionEvent.TOOL_TYPE_MOUSE, source, x, y)
    }

    private fun dispatchMotion(
        downTime: Long,
        eventTime: Long,
        action: Int,
        tool: Int,
        source: Int,
        x: Float,
        y: Float,
        buttonState: Int = if (action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0,
    ) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val event = MotionEvent.obtain(
            downTime, eventTime, action, 1, properties, coordinates,
            0, buttonState,
            1f, 1f, 0, 0, source, 0,
        )
        try {
            composeRule.runOnUiThread { composeRule.activity.dispatchTouchEvent(event) }
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val TAG = "central-stage-input"
        const val SURFACE_TAG = "semantic-surface"
    }
}
