package com.cattailsw.nanidroid.compose.stage

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
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
        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(Offset(20f, 20f))
            cancel()
        }
        composeRule.onNodeWithTag(TAG).performTouchInput { down(Offset(20f, 20f)) }
        composeRule.runOnIdle {
            state.value = snapshot(surfaces = listOf(surface(IntRect(100, 100, 200, 200))))
        }
        composeRule.onNodeWithTag(TAG).performTouchInput { up() }
        composeRule.runOnIdle {
            state.value = snapshot(surfaces = listOf(surface(IntRect(0, 0, 100, 100))))
        }
        composeRule.onNodeWithTag(TAG).performTouchInput { down(Offset(20f, 20f)) }
        composeRule.runOnIdle {
            val current = state.value.surfaces.single()
            state.value = snapshot(
                surfaces = listOf(
                    current.copy(
                        composedSurface = current.composedSurface.copy(inputAuthority = "replacement-base"),
                    ),
                ),
            )
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
        setStage(
            { snapshot(surfaces = listOf(surface(IntRect(0, 0, 300, 300)))) },
            effects,
            toggle = {},
            monotonicNowMillis = SystemClock::uptimeMillis,
        )

        // Android does not set BUTTON_PRIMARY for an ordinary stylus-tip contact.
        injectToolClick(MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS, 40f, 40f, buttonState = 0)
        composeRule.mainClock.advanceTimeBy(600)
        injectToolClick(MotionEvent.TOOL_TYPE_ERASER, InputDevice.SOURCE_STYLUS, 80f, 80f, buttonState = 0)
        composeRule.mainClock.advanceTimeBy(600)
        injectToolClick(
            MotionEvent.TOOL_TYPE_MOUSE,
            InputDevice.SOURCE_MOUSE,
            120f,
            120f,
            buttonState = MotionEvent.BUTTON_SECONDARY,
        )
        injectToolClick(
            MotionEvent.TOOL_TYPE_STYLUS,
            InputDevice.SOURCE_STYLUS,
            160f,
            160f,
            buttonState = MotionEvent.BUTTON_STYLUS_SECONDARY,
        )
        injectNonActivation(MotionEvent.ACTION_HOVER_MOVE, InputDevice.SOURCE_MOUSE, 100f, 100f)
        injectNonActivation(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_MOUSE, 100f, 100f)
        composeRule.waitForIdle()

        assertEquals(listOf(PointerSource.PEN, PointerSource.ERASER), effects.map { it.source })
    }

    @Test
    fun hoverAndWheelReachChildWithoutConsumption() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        val observed = mutableListOf<Pair<PointerEventType, Boolean>>()
        setStage(
            { snapshot(surfaces = listOf(surface(IntRect(0, 0, 300, 300)))) },
            effects,
            toggle = {},
            pointerObserver = { type, consumed -> observed += type to consumed },
        )

        composeRule.onNodeWithTag(TAG).performMouseInput {
            enter(Offset(100f, 100f))
            moveTo(Offset(101f, 101f))
            scroll(12f)
        }
        composeRule.waitForIdle()

        assertTrue(observed.any { it.first == PointerEventType.Enter || it.first == PointerEventType.Move })
        assertTrue(observed.any { it.first == PointerEventType.Scroll })
        assertTrue(observed.none { it.second })
        assertTrue(effects.isEmpty())
    }

    @Test
    fun fractionalCoordinatesRemainContinuousThroughDownscaledSurfaceEdges() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        setStage(
            { snapshot(surfaces = listOf(downscaledSurface())) },
            effects,
            toggle = {},
            monotonicNowMillis = SystemClock::uptimeMillis,
        )

        injectToolClick(
            MotionEvent.TOOL_TYPE_STYLUS,
            InputDevice.SOURCE_STYLUS,
            49.75f,
            10f,
            buttonState = 0,
        )
        composeRule.mainClock.advanceTimeBy(600)
        injectToolClick(
            MotionEvent.TOOL_TYPE_STYLUS,
            InputDevice.SOURCE_STYLUS,
            -0.25f,
            10f,
            buttonState = 0,
        )
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()

        assertEquals(1, effects.size)
        assertEquals(IntOffset(99, 20), effects.single().intrinsic)
        assertEquals("FractionalEdge", effects.single().collisionIdentifier)
    }

    @Test
    fun actualMouseStreamPairsOnSecondDownAndUsesReleaseCoordinates() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        setStage({ snapshot(surfaces = listOf(surface(IntRect(0, 0, 200, 200)))) }, effects, toggle = {})
        composeRule.onNodeWithTag(TAG).performMouseInput {
            moveTo(Offset(40f, 40f))
            press()
            release()
            moveTo(Offset(42f, 42f), delayMillis = 100)
            press()
            moveTo(Offset(43f, 43f), delayMillis = 300)
            release()
        }

        composeRule.runOnIdle {
            assertEquals(listOf(PointerEventKind.DOUBLE_CLICK), effects.map { it.kind })
            assertEquals(IntOffset(21, 21), effects.single().intrinsic)
        }
    }

    @Test
    fun physicalPairUsesDownPointsForSlopWhenFirstReleaseMovesWithinGestureSlop() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        val configuration = ViewConfiguration.get(composeRule.activity)
        val movement = minOf(
            configuration.scaledTouchSlop / 2f,
            configuration.scaledDoubleTapSlop / 4f,
        ).coerceAtLeast(1f)
        val firstDown = Offset(250f, 250f)
        val firstUp = Offset(firstDown.x - movement, firstDown.y)
        val secondDown = Offset(
            firstDown.x + configuration.scaledDoubleTapSlop - movement / 2f,
            firstDown.y,
        )
        setStage({ snapshot(surfaces = listOf(surface(IntRect(0, 0, 600, 600)))) }, effects, toggle = {})

        composeRule.onNodeWithTag(TAG).performMouseInput {
            moveTo(firstDown)
            press()
            moveTo(firstUp, delayMillis = 10)
            release()
            moveTo(secondDown, delayMillis = 100)
            press()
            release()
        }
        composeRule.mainClock.advanceTimeBy(600)

        composeRule.runOnIdle {
            assertEquals(listOf(PointerEventKind.DOUBLE_CLICK), effects.map { it.kind })
        }
    }

    @Test
    fun emptyStageReleaseOutsideRootWithinSlopDoesNotToggleChrome() {
        var toggles = 0
        val effects = mutableListOf<SurfaceInteractionEffect>()
        setStage({ snapshot() }, effects, toggle = { toggles++ })
        val stageBounds = composeRule.onNodeWithTag(TAG).fetchSemanticsNode().boundsInRoot

        composeRule.onNodeWithTag(TAG).performTouchInput {
            val y = stageBounds.height / 2f
            down(Offset(stageBounds.width - 1f, y))
            moveTo(Offset(stageBounds.width + 1f, y))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(0, toggles)
            assertTrue(effects.isEmpty())
        }
    }

    @Test
    fun disposingHostCancelsPendingPhysicalSingle() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        val mounted = mutableStateOf(true)
        composeRule.setContent {
            if (mounted.value) {
                StagePointerInput(
                    snapshotProvider = { snapshot(surfaces = listOf(surface(IntRect(0, 0, 200, 200)))) },
                    onSurfaceEffect = effects::add,
                    onToggleChrome = {},
                    monotonicNowMillis = { composeRule.mainClock.currentTime },
                    modifier = Modifier.fillMaxSize().testTag(TAG),
                ) { }
            }
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(TAG).performMouseInput { click(Offset(40f, 40f)) }
        composeRule.runOnIdle { mounted.value = false }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { assertTrue(effects.isEmpty()) }
    }

    @Test
    fun disablingHostCancelsPendingPhysicalSingle() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        val enabled = mutableStateOf(true)
        setStage(
            snapshot = { snapshot(surfaces = listOf(surface(IntRect(0, 0, 200, 200)))) },
            effects = effects,
            toggle = {},
            enabled = { enabled.value },
        )
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithTag(TAG).performMouseInput { click(Offset(40f, 40f)) }
        composeRule.runOnIdle { enabled.value = false }
        composeRule.mainClock.advanceTimeBy(1_000)

        composeRule.runOnIdle { assertTrue(effects.isEmpty()) }
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
            state.value = snapshot(blocking = true)
        }
        composeRule.onNodeWithTag(TAG).performClick()
        composeRule.runOnIdle { assertEquals(1, toggles) }
    }

    @Test
    fun focusedStageActivatesChromeOnceOnEnterReleaseAndDpadCenterWithoutSurfaceEffect() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var toggles = 0
        setStage(
            { snapshot(surfaces = listOf(surface(IntRect(0, 0, 100, 100)))) },
            effects,
            toggle = { toggles++ },
        )
        val stage = composeRule.onNodeWithTag(TAG)

        stage.performSemanticsAction(SemanticsActions.RequestFocus)
        stage.assertIsFocused()
        stage.performKeyInput {
            keyDown(Key.Enter)
            advanceEventTime(600)
        }
        composeRule.runOnIdle {
            assertEquals(0, toggles)
            assertTrue(effects.isEmpty())
        }

        stage.performKeyInput { keyUp(Key.Enter) }
        composeRule.runOnIdle {
            assertEquals(1, toggles)
            assertTrue(effects.isEmpty())
        }

        stage.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle {
            assertEquals(2, toggles)
            assertTrue(effects.isEmpty())
        }
    }

    @Test
    fun focusedStageIgnoresUnsupportedKeysAndCannotActivateWhileBlocking() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var toggles = 0
        val surface = surface(IntRect(0, 0, 100, 100))
        val state = mutableStateOf(snapshot(surfaces = listOf(surface)))
        setStage({ state.value }, effects, toggle = { toggles++ })
        val stage = composeRule.onNodeWithTag(TAG)

        stage.performSemanticsAction(SemanticsActions.RequestFocus)
        stage.assertIsFocused()
        stage.performKeyInput { pressKey(Key.Spacebar) }
        composeRule.runOnIdle {
            assertEquals(0, toggles)
            assertTrue(effects.isEmpty())
            state.value = snapshot(blocking = true, surfaces = listOf(surface))
        }

        stage.performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle {
            assertEquals(0, toggles)
            assertTrue(effects.isEmpty())
        }
    }

    @Test
    fun overlappingActivationKeysRemainInvalidWhenCenterReleasesBeforeEnterRepeat() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var toggles = 0
        setStage({ snapshot() }, effects, toggle = { toggles++ })
        val stage = composeRule.onNodeWithTag(TAG)
        stage.performSemanticsAction(SemanticsActions.RequestFocus)

        stage.performKeyInput {
            keyDown(Key.Enter)
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
            advanceEventTime(600)
            keyUp(Key.Enter)
        }

        composeRule.runOnIdle {
            assertEquals(0, toggles)
            assertTrue(effects.isEmpty())
        }
    }

    @Test
    fun overlappingActivationKeysRemainInvalidWhenEnterReleasesBeforeCenterRepeat() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var toggles = 0
        setStage({ snapshot() }, effects, toggle = { toggles++ })
        val stage = composeRule.onNodeWithTag(TAG)
        stage.performSemanticsAction(SemanticsActions.RequestFocus)

        stage.performKeyInput {
            keyDown(Key.Enter)
            keyDown(Key.DirectionCenter)
            keyUp(Key.Enter)
            advanceEventTime(600)
            keyUp(Key.DirectionCenter)
        }

        composeRule.runOnIdle {
            assertEquals(0, toggles)
            assertTrue(effects.isEmpty())
        }
    }

    @Test
    fun blockedActivationKeyDownCannotPairWithUnblockedKeyUp() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var toggles = 0
        val state = mutableStateOf(snapshot(blocking = true))
        setStage({ state.value }, effects, toggle = { toggles++ })
        val stage = composeRule.onNodeWithTag(TAG)
        stage.performSemanticsAction(SemanticsActions.RequestFocus)

        stage.performKeyInput { keyDown(Key.Enter) }
        composeRule.runOnIdle { state.value = snapshot() }
        stage.performKeyInput { keyUp(Key.Enter) }

        composeRule.runOnIdle {
            assertEquals(0, toggles)
            assertTrue(effects.isEmpty())
        }
    }

    @Test
    fun acceptedActivationKeyDownIsCancelledByTransientBlocking() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var toggles = 0
        val state = mutableStateOf(snapshot())
        setStage({ state.value }, effects, toggle = { toggles++ })
        val stage = composeRule.onNodeWithTag(TAG)
        stage.performSemanticsAction(SemanticsActions.RequestFocus)

        stage.performKeyInput { keyDown(Key.Enter) }
        composeRule.runOnIdle { state.value = snapshot(blocking = true) }
        composeRule.runOnIdle { state.value = snapshot() }
        stage.performKeyInput { keyUp(Key.Enter) }

        composeRule.runOnIdle {
            assertEquals(0, toggles)
            assertTrue(effects.isEmpty())
        }
    }

    @Test
    fun acceptedDpadCenterDownIsCancelledByGhostIdentityChange() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var toggles = 0
        val state = mutableStateOf(snapshot(ghostKey = "first-ghost"))
        setStage({ state.value }, effects, toggle = { toggles++ })
        val stage = composeRule.onNodeWithTag(TAG)
        stage.performSemanticsAction(SemanticsActions.RequestFocus)

        stage.performKeyInput { keyDown(Key.DirectionCenter) }
        composeRule.runOnIdle { state.value = snapshot(ghostKey = "replacement-ghost") }
        stage.performKeyInput { keyUp(Key.DirectionCenter) }

        composeRule.runOnIdle {
            assertEquals(0, toggles)
            assertTrue(effects.isEmpty())
        }
    }

    @Test
    fun replacementStageRejectsUnpairedKeyUpFromDisposedStage() {
        val effects = mutableListOf<SurfaceInteractionEffect>()
        var toggles = 0
        val mounted = mutableStateOf(true)
        composeRule.setContent {
            if (mounted.value) {
                StagePointerInput(
                    snapshotProvider = { snapshot() },
                    onSurfaceEffect = effects::add,
                    onToggleChrome = { toggles++ },
                    modifier = Modifier.fillMaxSize().testTag(TAG),
                ) { }
            }
        }
        composeRule.onNodeWithTag(TAG).performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithTag(TAG).performKeyInput { keyDown(Key.Enter) }

        composeRule.runOnIdle { mounted.value = false }
        composeRule.runOnIdle { mounted.value = true }
        composeRule.onNodeWithTag(TAG).performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithTag(TAG).performKeyInput { keyUp(Key.Enter) }

        composeRule.runOnIdle {
            assertEquals(0, toggles)
            assertTrue(effects.isEmpty())
        }
    }

    private fun setStage(
        snapshot: () -> StageInputSnapshot,
        effects: MutableList<SurfaceInteractionEffect>,
        toggle: () -> Unit,
        includeSurfaceSemantic: Boolean = false,
        pointerObserver: ((PointerEventType, Boolean) -> Unit)? = null,
        enabled: () -> Boolean = { true },
        monotonicNowMillis: () -> Long = { composeRule.mainClock.currentTime },
    ) {
        composeRule.mainClock.autoAdvance = true
        composeRule.setContent {
            StagePointerInput(
                snapshotProvider = snapshot,
                onSurfaceEffect = effects::add,
                onToggleChrome = toggle,
                enabled = enabled(),
                monotonicNowMillis = monotonicNowMillis,
                modifier = Modifier.fillMaxSize().testTag(TAG),
            ) { semanticActivate ->
                if (includeSurfaceSemantic) {
                    snapshot().surfaces.singleOrNull()?.let { surface ->
                        val semantics = GhostStageSemantics.build(surface)
                        Box(
                            Modifier
                                .fillMaxSize()
                                .stageSurfaceSemantics(
                                    SURFACE_TAG,
                                    "Sakura character",
                                    semantics,
                                    "Operate Sakura character",
                                    semantics.collisionActions.map { "Operate ${it.spokenIdentifier}" },
                                    semanticActivate,
                                ),
                        )
                    }
                }
                if (pointerObserver != null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(pointerObserver) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Final)
                                        if (
                                            event.type == PointerEventType.Enter ||
                                            event.type == PointerEventType.Move ||
                                            event.type == PointerEventType.Scroll
                                        ) {
                                            pointerObserver(event.type, event.changes.any { it.isConsumed })
                                        }
                                    }
                                }
                            },
                    )
                }
            }
        }
    }

    private fun snapshot(
        blocking: Boolean = false,
        bubbles: List<MeasuredBubbleHitRegion> = emptyList(),
        surfaces: List<StageSurfaceSnapshot> = emptyList(),
        ghostKey: String = "device-fixture",
    ) = StageInputRouter.snapshot(
        blocking = blocking,
        bubbleRegistry = BubbleHitRegionRegistry.from(bubbles),
        bubbleGeneration = bubbles.hashCode().toLong(),
        ghostKey = ghostKey,
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

    private fun downscaledSurface(): StageSurfaceSnapshot {
        val size = IntSize(200, 100)
        val collision = SurfaceCollision(
            7,
            "FractionalEdge",
            CollisionShape.Rectangle(IntRect(99, 0, 100, 100)),
            0,
        )
        val composed = ComposedSurface(
            SurfacePixelImage.of(size.width, size.height, IntArray(size.width * size.height) { 0xff102030.toInt() }),
            size,
            IntRect(0, 0, size.width, size.height),
            listOf(collision),
            SurfaceKey(0, size),
            1,
            false,
        )
        return StageSurfaceSnapshot(
            SurfaceSpeaker.SAKURA,
            composed,
            SurfaceTransformPx(size, IntRect(0, 0, 100, 50), 0.5f, IntOffset.Zero),
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
            composeRule.runOnUiThread {
                when (action) {
                    MotionEvent.ACTION_HOVER_MOVE,
                    MotionEvent.ACTION_SCROLL,
                    -> composeRule.activity.dispatchGenericMotionEvent(event)
                    else -> composeRule.activity.dispatchTouchEvent(event)
                }
            }
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val TAG = "central-stage-input"
        const val SURFACE_TAG = "semantic-surface"
    }
}
