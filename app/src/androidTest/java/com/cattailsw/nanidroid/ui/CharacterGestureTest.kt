package com.cattailsw.nanidroid.ui

import android.graphics.Bitmap
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipe
import androidx.compose.runtime.mutableStateOf
import com.cattailsw.nanidroid.ghost.SurfaceImageLoader
import com.cattailsw.nanidroid.ghost.SurfaceLayer
import com.cattailsw.nanidroid.runtime.PlaybackFrame
import com.cattailsw.nanidroid.runtime.SpeakerFrame
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.runtime.DialogueToken
import com.cattailsw.nanidroid.runtime.RenderedMoveSource
import com.cattailsw.nanidroid.runtime.SurfaceImageBounds
import com.cattailsw.nanidroid.runtime.SurfaceVisual
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class CharacterGestureTest {
    @get:Rule val compose = createComposeRule()
    private val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).asImageBitmap()
    private val loader = SurfaceImageLoader { image }
    private val stage = StageState.Ready(
        PlaybackFrame(SpeakerFrame(0, true, "Hello", true), SpeakerFrame(10, true, "", false), false),
        "Nanidroid", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")))

    @Test fun dragSendsMovesWithoutTapOrDoubleTap() {
        val taps = mutableListOf<Int>()
        val doubles = mutableListOf<Int>()
        val moves = mutableListOf<Pair<Int, Int>>()
        compose.setContent {
            GhostStage(stage, loader, {}, {}, {},
                onCharacterDoubleClick = { speaker, _, _ -> doubles += speaker },
                onCharacterClick = { speaker, _, _ -> taps += speaker },
                onCharacterMove = { _, x, y -> moves += x to y })
        }
        compose.onNodeWithTag("kero").performTouchInput { swipe(center, bottomRight) }
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), taps)
        assertEquals(emptyList<Int>(), doubles)
        assertTrue(moves.isNotEmpty())
    }

    @Test fun singleTapUsesClickCallback() {
        val taps = mutableListOf<Int>()
        val doubles = mutableListOf<Int>()
        compose.setContent {
            GhostStage(stage, loader, {}, {}, {},
                onCharacterDoubleClick = { speaker, _, _ -> doubles += speaker },
                onCharacterClick = { speaker, _, _ -> taps += speaker })
        }
        compose.onNodeWithTag("kero").performTouchInput { click(center) }
        compose.waitUntil(2_000) { taps.size == 1 }
        assertEquals(listOf(1), taps)
        assertEquals(emptyList<Int>(), doubles)
    }

    @Test fun doubleTapUsesDoubleClickCallbackOnly() {
        val taps = mutableListOf<Int>()
        val doubles = mutableListOf<Int>()
        compose.setContent {
            GhostStage(stage, loader, {}, {}, {},
                onCharacterDoubleClick = { speaker, _, _ -> doubles += speaker },
                onCharacterClick = { speaker, _, _ -> taps += speaker })
        }
        compose.onNodeWithTag("kero").performTouchInput { doubleClick(center) }
        compose.waitUntil(2_000) { doubles.size == 1 }
        assertEquals(listOf(1), doubles)
        assertEquals(emptyList<Int>(), taps)
    }

    @Test fun balloonScrollDoesNotTriggerCharacterActions() {
        var taps = 0
        var moves = 0
        compose.setContent {
            GhostStage(stage, loader, {}, {}, {},
                onCharacterClick = { _, _, _ -> taps++ },
                onCharacterMove = { _, _, _ -> moves++ })
        }
        compose.onNodeWithTag("balloon-Sakura").performTouchInput { swipe(center, topCenter) }
        compose.waitForIdle()
        assertEquals(0, taps)
        assertEquals(0, moves)
    }

    @Test fun removingCharacterDuringDragCancelsPendingMove() {
        val current = mutableStateOf<StageState>(stage)
        var moves = 0
        var cancellations = 0
        compose.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterClick = { _, _, _ -> },
                onCharacterMove = { _, _, _ -> moves++ },
                onCharacterMoveCancel = { cancellations++ })
        }
        compose.onNodeWithTag("kero").performTouchInput { down(center); moveTo(bottomRight) }
        compose.waitUntil(2_000) { moves > 0 }
        compose.runOnIdle { current.value = StageState.Loading }
        compose.waitUntil(2_000) { cancellations == 1 }
        assertEquals(1, cancellations)
    }

    @Test fun missingLogicalImageReportsDisplayedFallbackCollisionSurface() {
        val fallback = stage.copy(frame = stage.frame.copy(
            sakura = stage.frame.sakura.copy(surfaceId = 42)))
        val resolved = mutableListOf<Int>()
        compose.setContent {
            GhostStage(fallback, loader, {}, {}, {},
                onCharacterClick = { _, _, _ -> },
                onCharacterClickResolved = { _, _, _, collisionId, _ -> resolved += collisionId })
        }
        compose.onNodeWithTag("sakura").performTouchInput { click(center) }
        compose.waitUntil(2_000) { resolved.isNotEmpty() }
        compose.onNodeWithTag("sakura").performClick()
        compose.waitUntil(2_000) { resolved.size == 2 }
        assertEquals(listOf(0, 0), resolved)
    }

    @Test fun dragReportsImmutableRenderedMoveIdentity() {
        val visual = SurfaceVisual(10, emptyList(), 4, -3)
        val token = DialogueToken(7, 8)
        val rendered = stage.copy(
            frame = stage.frame.copy(kero = stage.frame.kero.copy(visual = visual)),
            dialogueToken = token)
        val sources = mutableListOf<RenderedMoveSource>()
        compose.setContent {
            GhostStage(rendered, loader, {}, {}, {},
                onCharacterClick = { _, _, _ -> },
                onCharacterMoveResolved = { _, _, _, _, source -> sources += source })
        }
        compose.onNodeWithTag("kero").performTouchInput { swipe(center, bottomRight) }
        compose.waitUntil(2_000) { sources.isNotEmpty() }
        assertTrue(sources.all { it.dialogueToken == token && it.logicalSurfaceId == 10 &&
            it.visual == visual && it.geometry?.images?.get(10) ==
            SurfaceImageBounds(0, 0, 200, 200) })
    }

    @Test fun pendingSingleTapSurvivesMoveOnlyVisualTick() {
        val token = DialogueToken(7, 8)
        val first = stage.copy(dialogueToken = token,
            frame = stage.frame.copy(kero = stage.frame.kero.copy(
                visual = SurfaceVisual(10, emptyList(), 0, 0))))
        val current = mutableStateOf(first)
        var clicks = 0
        compose.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterClick = { _, _, _ -> clicks++ })
        }
        compose.onNodeWithTag("kero").performTouchInput { click(center) }
        compose.runOnIdle {
            current.value = first.copy(frame = first.frame.copy(kero = first.frame.kero.copy(
                visual = SurfaceVisual(10, emptyList(), 4, -3))))
        }
        compose.waitUntil(2_000) { clicks == 1 }
        assertEquals(1, clicks)
    }

    @Test fun doubleTapSurvivesMoveOnlyVisualTick() {
        val first = stage.copy(dialogueToken = DialogueToken(7, 8),
            frame = stage.frame.copy(kero = stage.frame.kero.copy(
                visual = SurfaceVisual(10, emptyList(), 0, 0))))
        val current = mutableStateOf(first)
        var clicks = 0
        var doubles = 0
        compose.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterClick = { _, _, _ -> clicks++ },
                onCharacterDoubleClick = { _, _, _ -> doubles++ })
        }
        compose.onNodeWithTag("kero").performTouchInput { down(center); up() }
        compose.runOnIdle {
            current.value = first.copy(frame = first.frame.copy(kero = first.frame.kero.copy(
                visual = SurfaceVisual(10, emptyList(), 4, -3))))
        }
        compose.onNodeWithTag("kero").performTouchInput { down(center); up() }
        compose.waitUntil(2_000) { doubles == 1 }
        assertEquals(0, clicks)
    }

    @Test fun heldDragCancelsWhenMoveOffsetChangesCoordinateSpace() {
        val token = DialogueToken(7, 8)
        val firstVisual = SurfaceVisual(10, emptyList(), 0, 0)
        val first = stage.copy(dialogueToken = token,
            frame = stage.frame.copy(kero = stage.frame.kero.copy(visual = firstVisual)))
        val current = mutableStateOf(first)
        val sources = mutableListOf<RenderedMoveSource>()
        var clicks = 0
        var cancellations = 0
        compose.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterClick = { _, _, _ -> clicks++ },
                onCharacterMoveCancel = { cancellations++ },
                onCharacterMoveResolved = { _, _, _, _, source -> sources += source })
        }
        compose.onNodeWithTag("kero").performTouchInput { down(center); moveTo(bottomCenter) }
        compose.waitUntil(2_000) { sources.isNotEmpty() }
        val before = sources.size
        compose.runOnIdle {
            current.value = first.copy(frame = first.frame.copy(kero = first.frame.kero.copy(
                visual = SurfaceVisual(10, emptyList(), 4, -3))))
        }
        compose.waitUntil(2_000) { cancellations == 1 }
        compose.onNodeWithTag("kero").performTouchInput { moveTo(bottomRight); up() }
        compose.waitForIdle()
        assertEquals(before, sources.size)
        assertEquals(1, cancellations)
        assertTrue(sources.all { it.dialogueToken == token && it.logicalSurfaceId == 10 &&
            it.visual == firstVisual && it.geometry?.images?.get(10) ==
            SurfaceImageBounds(0, 0, 200, 200) })
        assertEquals(0, clicks)
    }

    @Test fun viewportResizeCancelsHeldDragAndPendingMove() {
        val viewportWidth = mutableStateOf(90.dp)
        val moves = mutableListOf<Pair<Int, Int>>()
        var cancellations = 0
        var clicks = 0
        compose.setContent {
            Box(Modifier.size(viewportWidth.value, 600.dp)) {
                GhostStage(stage, loader, {}, {}, {},
                    onCharacterClick = { _, _, _ -> clicks++ },
                    onCharacterMove = { _, x, y -> moves += x to y },
                    onCharacterMoveCancel = { cancellations++ })
            }
        }
        assumeTrue("Requires a 600 dp tall fixture viewport",
            compose.onRoot().fetchSemanticsNode().boundsInRoot.height / compose.density.density >= 600f)
        val beforeBounds = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("kero").performTouchInput { down(center); moveTo(bottomCenter) }
        compose.waitUntil(2_000) { moves.isNotEmpty() }
        val admittedBeforeResize = moves.size
        compose.runOnIdle { viewportWidth.value = 125.dp }
        val afterBounds = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot
        assertTrue("before=$beforeBounds after=$afterBounds",
            afterBounds.width > beforeBounds.width)
        compose.onNodeWithTag("kero").performTouchInput { moveTo(bottomRight); up() }
        compose.waitForIdle()
        assertEquals(admittedBeforeResize, moves.size)
        assertEquals(1, cancellations)
        assertEquals(0, clicks)
    }

    @Test fun sameBoundsOverlayTickKeepsHeldDragActive() {
        val token = DialogueToken(7, 8)
        val first = stage.copy(dialogueToken = token,
            surfaces = stage.surfaces + mapOf(1001 to File("eye-a"), 1002 to File("eye-b")),
            frame = stage.frame.copy(kero = stage.frame.kero.copy(
                visual = SurfaceVisual(10, listOf(SurfaceLayer(1001, "overlay", 0, 0)), 0, 0))))
        val current = mutableStateOf(first)
        var moves = 0
        var cancellations = 0
        compose.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterMove = { _, _, _ -> moves++ },
                onCharacterMoveCancel = { cancellations++ })
        }
        compose.onNodeWithTag("kero").performTouchInput { down(center); moveTo(bottomCenter) }
        compose.waitUntil(2_000) { moves > 0 }
        val before = moves
        compose.runOnIdle {
            current.value = first.copy(frame = first.frame.copy(kero = first.frame.kero.copy(
                visual = SurfaceVisual(10, listOf(SurfaceLayer(1002, "overlay", 0, 0)), 0, 0))))
        }
        compose.onNodeWithTag("kero").performTouchInput { moveTo(bottomRight); up() }
        compose.waitUntil(2_000) { moves > before }
        assertEquals(0, cancellations)
    }

    @Test fun sameBoundsBaseSwapCancelsHeldDrag() {
        val first = stage.copy(dialogueToken = DialogueToken(7, 8),
            surfaces = stage.surfaces + (1001 to File("replacement")))
        val current = mutableStateOf(first)
        var moves = 0
        var cancellations = 0
        compose.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterMove = { _, _, _ -> moves++ },
                onCharacterMoveCancel = { cancellations++ })
        }
        val beforeBounds = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("kero").performTouchInput { down(center); moveTo(bottomCenter) }
        compose.waitUntil(2_000) { moves > 0 }
        val admittedBeforeSwap = moves
        compose.runOnIdle {
            current.value = first.copy(frame = first.frame.copy(kero = first.frame.kero.copy(
                visual = SurfaceVisual(1001, emptyList(), 0, 0))))
        }
        val afterBounds = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot
        assertEquals(beforeBounds, afterBounds)
        compose.onNodeWithTag("kero").performTouchInput { moveTo(bottomRight); up() }
        compose.waitForIdle()
        assertEquals(admittedBeforeSwap, moves)
        assertEquals(1, cancellations)
    }

    @Test fun tapPressedAcrossBaseSwapStillClicks() {
        val first = stage.copy(dialogueToken = DialogueToken(7, 8),
            surfaces = stage.surfaces + (1001 to File("replacement")))
        val current = mutableStateOf(first)
        val clicks = mutableListOf<Int>()
        var doubles = 0
        compose.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterClickResolved = { _, _, _, collision, _ -> clicks += collision },
                onCharacterDoubleClick = { _, _, _ -> doubles++ })
        }
        compose.onNodeWithTag("kero").performTouchInput { down(center) }
        compose.runOnIdle {
            current.value = first.copy(frame = first.frame.copy(kero = first.frame.kero.copy(
                visual = SurfaceVisual(1001, emptyList(), 0, 0))))
        }
        compose.onNodeWithTag("kero").performTouchInput { up() }
        compose.waitUntil(2_000) { clicks.isNotEmpty() }
        assertEquals(listOf(10), clicks)
        assertEquals(0, doubles)
    }

    @Test fun doubleTapWithSecondPressAcrossBaseSwapStillDoubleClicks() {
        val first = stage.copy(dialogueToken = DialogueToken(7, 8),
            surfaces = stage.surfaces + (1001 to File("replacement")))
        val current = mutableStateOf(first)
        var clicks = 0
        var doubles = 0
        compose.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterClick = { _, _, _ -> clicks++ },
                onCharacterDoubleClick = { _, _, _ -> doubles++ })
        }
        compose.onNodeWithTag("kero").performTouchInput { down(center); up(); down(center) }
        compose.runOnIdle {
            current.value = first.copy(frame = first.frame.copy(kero = first.frame.kero.copy(
                visual = SurfaceVisual(1001, emptyList(), 0, 0))))
        }
        compose.onNodeWithTag("kero").performTouchInput { up() }
        compose.waitUntil(2_000) { doubles == 1 || clicks > 0 }
        assertEquals(1, doubles)
        assertEquals(0, clicks)
    }

    @Test fun pauseCancelsPendingSingleTap() {
        val active = mutableStateOf(true)
        var clicks = 0
        compose.setContent {
            GhostStage(stage, loader, {}, {}, {},
                onCharacterClick = { _, _, _ -> clicks++ },
                interactionActive = active.value)
        }
        compose.onNodeWithTag("kero").performTouchInput { click(center) }
        compose.runOnIdle { active.value = false }
        Thread.sleep(400)
        compose.runOnIdle { active.value = true }
        Thread.sleep(400)
        compose.waitForIdle()
        assertEquals(0, clicks)
    }

    @Test fun sessionReplacementCancelsPendingSingleTap() {
        val first = stage.copy(dialogueToken = DialogueToken(7, 8))
        val current = mutableStateOf(first)
        var clicks = 0
        compose.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterClick = { _, _, _ -> clicks++ })
        }
        compose.onNodeWithTag("kero").performTouchInput { click(center) }
        compose.runOnIdle { current.value = first.copy(dialogueToken = DialogueToken(9, 1)) }
        Thread.sleep(400)
        compose.waitForIdle()
        assertEquals(0, clicks)
        compose.onNodeWithTag("kero").performTouchInput { click(center) }
        compose.waitUntil(2_000) { clicks == 1 }
    }

    @Test fun translatedCanvasDistantScreenTapsAreTwoSingles() {
        val slop = ViewConfiguration.get(InstrumentationRegistry.getInstrumentation().targetContext)
            .scaledDoubleTapSlop.toFloat()
        val first = stage.copy(dialogueToken = DialogueToken(7, 8),
            frame = stage.frame.copy(kero = stage.frame.kero.copy(
                visual = SurfaceVisual(10, emptyList(), 0, 0))))
        val current = mutableStateOf(first)
        var singles = 0
        var doubles = 0
        compose.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterClick = { _, _, _ -> singles++ },
                onCharacterDoubleClick = { _, _, _ -> doubles++ })
        }
        val firstCenter = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("kero").performTouchInput { click(center) }
        compose.runOnIdle {
            current.value = first.copy(frame = first.frame.copy(kero = first.frame.kero.copy(
                visual = SurfaceVisual(10, emptyList(), 0, -(slop + 50f).roundToInt()))))
        }
        val secondCenter = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot.center
        assertTrue(abs(secondCenter.y - firstCenter.y) > slop)
        compose.onNodeWithTag("kero").performTouchInput { click(center) }
        compose.waitUntil(2_000) { singles == 2 || doubles > 0 }
        assertEquals(2, singles)
        assertEquals(0, doubles)
    }

    @Test fun translatedCanvasSameScreenPointIsDoubleTap() {
        val slop = ViewConfiguration.get(InstrumentationRegistry.getInstrumentation().targetContext)
            .scaledDoubleTapSlop.toFloat()
        val large = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).asImageBitmap()
        val largeLoader = SurfaceImageLoader { large }
        val first = stage.copy(dialogueToken = DialogueToken(7, 8),
            frame = stage.frame.copy(kero = stage.frame.kero.copy(
                visual = SurfaceVisual(10, emptyList(), 0, 0))))
        val current = mutableStateOf(first)
        var singles = 0
        var doubles = 0
        compose.setContent {
            GhostStage(current.value, largeLoader, {}, {}, {},
                onCharacterClick = { _, _, _ -> singles++ },
                onCharacterDoubleClick = { _, _, _ -> doubles++ })
        }
        val original = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot
        val firstLocal = Offset(original.width / 2f, 40f)
        compose.onNodeWithTag("kero").performTouchInput { click(firstLocal) }
        compose.runOnIdle {
            current.value = first.copy(frame = first.frame.copy(kero = first.frame.kero.copy(
                visual = SurfaceVisual(10, emptyList(), 0, -(slop + 25f).roundToInt()))))
        }
        val moved = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot
        val secondLocal = original.topLeft + firstLocal - moved.topLeft
        assertTrue("slop=$slop original=$original moved=$moved secondLocal=$secondLocal",
            secondLocal.y > 0f && secondLocal.y < moved.height)
        assertTrue(abs(secondLocal.y - firstLocal.y) > slop)
        compose.onNodeWithTag("kero").performTouchInput { click(secondLocal) }
        compose.waitUntil(2_000) { doubles == 1 || singles > 0 }
        assertEquals(1, doubles)
        assertEquals(0, singles)
    }

    @Test fun stationaryScreenPointerAcrossCanvasTranslationKeepsPressCoordinates() {
        val first = stage.copy(dialogueToken = DialogueToken(7, 8),
            frame = stage.frame.copy(kero = stage.frame.kero.copy(
                visual = SurfaceVisual(10, emptyList(), 0, 0))))
        val current = mutableStateOf(first)
        val clicks = mutableListOf<Triple<Int, Int, Int>>()
        var moves = 0
        compose.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterClickResolved = { _, x, y, collision, _ ->
                    clicks += Triple(x, y, collision)
                },
                onCharacterMove = { _, _, _ -> moves++ })
        }
        val before = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot
        val screenPoint = before.center
        compose.onNodeWithTag("kero").performTouchInput { down(center) }
        compose.runOnIdle {
            current.value = first.copy(frame = first.frame.copy(kero = first.frame.kero.copy(
                visual = SurfaceVisual(10, emptyList(), 40, 0))))
        }
        val after = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot
        assertTrue(after.left > before.left + 20f)
        val releaseLocal = screenPoint - after.topLeft
        compose.onNodeWithTag("kero").performTouchInput { moveTo(releaseLocal); up() }
        compose.waitUntil(2_000) { clicks.isNotEmpty() || moves > 0 }
        assertEquals(listOf(Triple(100, 100, 10)), clicks)
        assertEquals(0, moves)
    }

    @Test fun stationaryScreenPointerAcrossViewportResizeKeepsPressCoordinates() {
        val viewportWidth = mutableStateOf(90.dp)
        val clicks = mutableListOf<Triple<Int, Int, Int>>()
        var moves = 0
        compose.setContent {
            Box(Modifier.size(viewportWidth.value, 600.dp)) {
                GhostStage(stage, loader, {}, {}, {},
                    onCharacterClickResolved = { _, x, y, collision, _ ->
                        clicks += Triple(x, y, collision)
                    },
                    onCharacterMove = { _, _, _ -> moves++ })
            }
        }
        assumeTrue("Requires a 600 dp tall fixture viewport",
            compose.onRoot().fetchSemanticsNode().boundsInRoot.height / compose.density.density >= 600f)
        val before = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot
        val screenPoint = before.center
        compose.onNodeWithTag("kero").performTouchInput { down(center) }
        compose.runOnIdle { viewportWidth.value = 125.dp }
        val after = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot
        assertTrue(after.width > before.width)
        val releaseLocal = screenPoint - after.topLeft
        compose.onNodeWithTag("kero").performTouchInput { moveTo(releaseLocal); up() }
        compose.waitUntil(2_000) { clicks.isNotEmpty() || moves > 0 }
        assertEquals(listOf(Triple(100, 100, 10)), clicks)
        assertEquals(0, moves)
    }

    @Test fun heldPressKeepsAuthoredCenterWhenDialogueChangesAtLargeFont() {
        val current = mutableStateOf(stage.copy(dialogueToken = DialogueToken(7, 8)))
        val clicks = mutableListOf<Triple<Int, Int, Int>>()
        compose.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterClickResolved = { _, x, y, collision, _ ->
                    clicks += Triple(x, y, collision)
                })
        }
        compose.onNodeWithTag("kero").performTouchInput { down(center) }
        compose.runOnIdle {
            val prior = current.value
            current.value = prior.copy(frame = prior.frame.copy(kero =
                prior.frame.kero.copy(text = "長い文章の途中で更新されても押した座標を保持する")))
        }
        compose.onNodeWithTag("kero").performTouchInput { up() }
        compose.waitUntil(2_000) { clicks.isNotEmpty() }
        assertEquals(listOf(Triple(100, 100, 10)), clicks)
    }

    @Test fun secondHeldTapAcrossTranslationStillDoubleClicksAtPressCoordinates() {
        val first = stage.copy(dialogueToken = DialogueToken(7, 8),
            frame = stage.frame.copy(kero = stage.frame.kero.copy(
                visual = SurfaceVisual(10, emptyList(), 0, 0))))
        val current = mutableStateOf(first)
        val doubles = mutableListOf<Triple<Int, Int, Int>>()
        var singles = 0
        var moves = 0
        compose.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterClick = { _, _, _ -> singles++ },
                onCharacterDoubleClickResolved = { _, x, y, collision, _ ->
                    doubles += Triple(x, y, collision)
                },
                onCharacterMove = { _, _, _ -> moves++ })
        }
        val before = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot
        val screenPoint = before.center
        compose.onNodeWithTag("kero").performTouchInput { down(center); up(); down(center) }
        compose.runOnIdle {
            current.value = first.copy(frame = first.frame.copy(kero = first.frame.kero.copy(
                visual = SurfaceVisual(10, emptyList(), 40, 0))))
        }
        val after = compose.onNodeWithTag("kero").fetchSemanticsNode().boundsInRoot
        assertTrue(after.left > before.left + 20f)
        compose.onNodeWithTag("kero").performTouchInput {
            moveTo(screenPoint - after.topLeft); up()
        }
        compose.waitUntil(2_000) { doubles.isNotEmpty() || singles > 0 || moves > 0 }
        assertEquals(listOf(Triple(100, 100, 10)), doubles)
        assertEquals(0, singles)
        assertEquals(0, moves)
    }
}
