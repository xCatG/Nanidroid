package com.cattailsw.nanidroid.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.cattailsw.nanidroid.ghost.SurfaceImageLoader
import com.cattailsw.nanidroid.ghost.ShellCatalog
import com.cattailsw.nanidroid.ghost.SurfaceDefinitions
import com.cattailsw.nanidroid.ghost.SurfaceCollisionHitTest
import com.cattailsw.nanidroid.runtime.DialogueToken
import com.cattailsw.nanidroid.runtime.InteractionToken
import com.cattailsw.nanidroid.runtime.PlaybackFrame
import com.cattailsw.nanidroid.runtime.PresentedChoice
import com.cattailsw.nanidroid.runtime.SpeakerFrame
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.runtime.SurfaceVisual
import java.io.File
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.cattailsw.nanidroid.testing.OwnedFixtureDirectoryRule
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test

class StagePolishTest {
    private val fixtures = OwnedFixtureDirectoryRule()
    val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixtures).around(compose)
    private val dialogue = DialogueToken(7, 11)
    private val surface = Bitmap.createBitmap(160, 200, Bitmap.Config.ARGB_8888).asImageBitmap()
    private val loader = SurfaceImageLoader { surface }

    private fun stage(text: String = "", choices: List<PresentedChoice> = emptyList()) = StageState.Ready(
        PlaybackFrame(SpeakerFrame(0, true, text, true), SpeakerFrame(10, true, "", false), false),
        "Test", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")),
        choices = mapOf(0 to choices), dialogueToken = dialogue,
    )

    @Test fun debugTouchBoundsToggleOutlinesCharacterWithoutChangingTapCoordinates() {
        val taps = mutableListOf<Pair<Int, Int>>()
        val current = mutableStateOf(stage())
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            GhostStage(current.value, loader, {}, {}, {},
                onCharacterClick = { _, x, y -> taps += x to y })
        }
        val character = compose.onNodeWithTag("sakura")
        val centerX = character.captureToImage().width / 2
        assertTrue(character.captureToImage().asAndroidBitmap().getPixel(centerX, 1) !=
            android.graphics.Color.MAGENTA)
        compose.onNodeWithContentDescription("Show touch bounds").performClick()
        compose.waitForIdle()
        assertEquals(android.graphics.Color.MAGENTA,
            character.captureToImage().asAndroidBitmap().getPixel(centerX, 1))
        compose.runOnIdle { current.value = stage().copy(ghostName = "Switched ghost") }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Hide touch bounds").assertExists()
        assertEquals(android.graphics.Color.MAGENTA,
            character.captureToImage().asAndroidBitmap().getPixel(centerX, 1))
        character.performTouchInput { click(center) }
        compose.waitUntil(2_000) { taps.size == 1 }
        assertEquals(80 to 100, taps.single())
        compose.onNodeWithContentDescription("Hide touch bounds").performClick()
        assertTrue(character.captureToImage().asAndroidBitmap().getPixel(centerX, 1) !=
            android.graphics.Color.MAGENTA)
    }

    @Test fun debugBoundsDrawsBothAuthoredCollisionsAndTracksDisplayedBase() {
        val shellDir = fixtures.directory("bounds-shell-${System.nanoTime()}")
        val numbered = listOf(0, 2, 10).associateWith { id ->
            File(shellDir, "surface$id.png").apply { writeBytes(byteArrayOf(1)) }
        }
        File(shellDir, "body.png").writeBytes(byteArrayOf(1))
        val definitions = SurfaceDefinitions.parse("""
            surface0 {
            element0,base,body.png,20,30
            collision0,40,50,70,120,Head
            collision1,100,130,130,160,Hand
            collision3,10,170,40,220,Clipped
            collision4,50,50,80,120,Face
            }
            surface2 {
            collision2,50,90,80,120,Other
            }
        """.trimIndent())
        val catalog = ShellCatalog(numbered, definitions, shellDir)
        val base = stage().copy(surfaces = numbered, shell = catalog)
        val current = mutableStateOf(base)
        val hits = mutableListOf<String>()
        compose.setContent {
            GhostStage(current.value, SurfaceImageLoader {
                Bitmap.createBitmap(160, 200, Bitmap.Config.ARGB_8888).asImageBitmap()
            }, {}, {}, {}, onCharacterClickResolved = { _, x, y, baseId, _ ->
                hits += SurfaceCollisionHitTest.find(definitions.definition(baseId)?.collisions.orEmpty(), x, y)
            })
        }
        val character = compose.onNodeWithTag("sakura")
        fun hasCyanAt(authoredX: Int, authoredY: Int, originX: Int, originY: Int): Boolean {
            val image = character.captureToImage().asAndroidBitmap()
            val x = ((authoredX - originX) * image.width / 160f).toInt()
            val y = ((authoredY - originY) * image.height / 200f).toInt()
            return (x - 2..x + 2).any { px -> (y - 2..y + 2).any { py ->
                px in 0 until image.width && py in 0 until image.height &&
                    image.getPixel(px, py) == android.graphics.Color.CYAN
            } }
        }
        assertTrue(!hasCyanAt(40, 65, 20, 30))
        compose.onNodeWithContentDescription("Show touch bounds").performClick()
        assertTrue("Head edge is missing", hasCyanAt(40, 65, 20, 30))
        assertTrue("Hand edge is missing", hasCyanAt(100, 145, 20, 30))
        assertTrue("Out-of-image collision is not clipped to the interactive surface",
            hasCyanAt(30, 170, 20, 30))
        val withLabels = character.captureToImage().asAndroidBitmap()
        val labelTop = ((50 - 30) * withLabels.height / 200f).toInt()
        val labelHeight = (16 * compose.density.density).toInt()
        val labelTextPixels = ((55 - 20) * withLabels.width / 160f).toInt()..
            ((65 - 20) * withLabels.width / 160f).toInt()
        val secondLabelCyan = labelTextPixels.sumOf { x ->
            (labelTop + labelHeight + 5..labelTop + labelHeight + 30).count { y ->
                x in 0 until withLabels.width && y in 0 until withLabels.height &&
                    withLabels.getPixel(x, y) == android.graphics.Color.CYAN
            }
        }
        assertTrue("Overlapping Face label is hidden by Head label", secondLabelCyan > 10)
        val characterImage = character.captureToImage()
        character.performTouchInput { click(Offset((55f - 20f) * characterImage.width / 160f,
            (65f - 30f) * characterImage.height / 200f)) }
        compose.waitUntil(2_000) { hits.isNotEmpty() }
        assertEquals("Head", hits.single())
        compose.runOnIdle { current.value = base.copy(frame = base.frame.copy(
            sakura = base.frame.sakura.copy(visual = SurfaceVisual(2, emptyList(), 0, 0)))) }
        compose.waitForIdle()
        assertTrue("Current base edge is missing", hasCyanAt(50, 105, 0, 0))
        assertTrue("Old base edge remains", !hasCyanAt(40, 65, 20, 30))
        compose.onNodeWithContentDescription("Hide touch bounds").performClick()
        assertTrue(!hasCyanAt(50, 105, 0, 0))
    }

    @Test fun emptyStageAndInertBalloonToggleInitiallyVisibleControls() {
        compose.setContent { GhostStage(stage("Plain dialogue"), loader) { _, _, _ -> } }
        compose.onNodeWithTag("ghosts-action").assertExists()
        compose.onNodeWithTag("about-action").assertExists()
        compose.onNodeWithTag("stage-background").performClick()
        compose.onNodeWithTag("ghosts-action").assertDoesNotExist()
        compose.onNodeWithTag("balloon-Sakura").performTouchInput { click() }
        compose.onNodeWithTag("about-action").assertExists()
    }

    @Test fun characterChoiceLinkAndScrollDoNotToggleControls() {
        val choice = PresentedChoice(InteractionToken(7, 11, 1), "Choose", 0)
        val current = mutableStateOf(stage("Choice text", listOf(choice)))
        var characterTaps = 0
        var choices = 0
        var links = 0
        compose.setContent {
            GhostStage(current.value,
                loader, {}, {}, {}, onCharacterClick = { _, _, _ -> characterTaps++ },
                onChoose = { choices++ }, onOpenLink = { _, _ -> links++ })
        }
        compose.onNodeWithTag("sakura").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("ghosts-action").assertExists()
        compose.onNodeWithTag("choice-1").performScrollTo().performClick()
        compose.onNodeWithTag("ghosts-action").assertExists()
        compose.runOnIdle { current.value = stage("https://example.org") }
        compose.onNodeWithText("https://example.org").performClick()
        compose.onNodeWithTag("ghosts-action").assertExists()
        compose.runOnIdle { current.value = stage("long line\n".repeat(30)) }
        compose.onNodeWithTag("balloon-Sakura").performTouchInput { swipeUp() }
        compose.onNodeWithTag("ghosts-action").assertExists()
        assertEquals(1, characterTaps)
        assertEquals(1, choices)
        assertEquals(1, links)
    }

    @Test fun hiddenControlsSurviveSavedStateRecreationAndCanBeShownAccessibly() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { GhostStage(stage(), loader) { _, _, _ -> } }
        compose.onNodeWithTag("stage-background").performClick()
        compose.onNodeWithTag("ghosts-action").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("ghosts-action").assertDoesNotExist()
        compose.onNodeWithTag("stage-controls-toggle").performClick()
        compose.onNodeWithTag("ghosts-action").assertExists()
    }

    @Test fun hidingControlsReclaimsTheirMeasuredHeight() {
        compose.setContent { GhostStage(stage(), loader) { _, _, _ -> } }
        val shownTop = compose.onNodeWithTag("stage-controls-toggle").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("stage-background").performClick()
        val hiddenTop = compose.onNodeWithTag("stage-controls-toggle").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Hidden stage still reserves the control bar: $shownTop to $hiddenTop", hiddenTop < shownTop)
    }

    @Test fun aboutDismissReturnsToStageWithoutClosing() {
        var closes = 0
        compose.setContent {
            GhostStage(stage(), loader, {}, {}, {}, onClose = { closes++ })
        }
        compose.onNodeWithTag("about-action").performClick()
        compose.onNodeWithText("Nanidroid").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("Nanidroid").assertDoesNotExist()
        compose.onNodeWithTag("ghosts-action").assertExists()
        assertEquals(0, closes)
    }

    @Test fun primaryPhoneMatrixKeepsSpeakersControlsAndScrollableLastChoiceInViewport() {
        val opaqueSurface = Bitmap.createBitmap(160, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(150, 110, 170))
        }.asImageBitmap()
        val longText = "長い日本語の会話。Long Latin dialogue with wrapping. ".repeat(8)
        val choices = (1L..8L).map {
            PresentedChoice(InteractionToken(7, 11, it), "Choice $it", longText.length)
        }
        val chosen = mutableListOf<InteractionToken>()
        var safeTopPx = 0
        var safeBottomPx = 0
        val ready = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, longText, true),
                SpeakerFrame(10, true, longText, true), false),
            "Test", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")),
            choices = mapOf(0 to choices, 1 to choices.map { it.copy(token =
                InteractionToken(7, 11, it.token.itemKey + 100)) }), dialogueToken = dialogue,
        )
        compose.setContent {
            val density = LocalDensity.current
            safeTopPx = WindowInsets.safeDrawing.getTop(density)
            safeBottomPx = WindowInsets.safeDrawing.getBottom(density)
            GhostStage(ready, SurfaceImageLoader { opaqueSurface }, {}, {}, {}, onChoose = { chosen += it })
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        InstrumentationRegistry.getArguments().getString("expectedViewport")?.let { expected ->
            assertEquals("Actual matrix root viewport and orientation", expected,
                "${root.width.toInt()}x${root.height.toInt()}")
        }
        for (tag in listOf("sakura", "kero", "balloon-Sakura", "balloon-Kero", "ghosts-action", "about-action")) {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag has no visible bounds: $bounds", bounds.width > 0f && bounds.height > 0f)
            assertTrue("$tag escapes viewport $root: $bounds",
                bounds.left >= root.left && bounds.top >= root.top &&
                    bounds.right <= root.right && bounds.bottom <= root.bottom)
            if (tag == "ghosts-action" || tag == "about-action")
                assertTrue("$tag enters system bars: $bounds, top=$safeTopPx bottom=$safeBottomPx",
                    bounds.top >= root.top + safeTopPx && bounds.bottom <= root.bottom - safeBottomPx)
        }
        if (root.width > root.height && compose.density.fontScale >= 2f) {
            val textNodes = compose.onAllNodesWithText(longText, useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertEquals(2, textNodes.size)
            for (node in textNodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                val room = minOf(
                    compose.onNodeWithTag("balloon-Sakura").fetchSemanticsNode().boundsInRoot.height,
                    compose.onNodeWithTag("balloon-Kero").fetchSemanticsNode().boundsInRoot.height,
                )
                assertTrue("Three complete lines need more room: $room vs ${layouts.single().getLineBottom(2)}",
                    room >= layouts.single().getLineBottom(2) + 24f * compose.density.density)
            }
        }
        val sakura = compose.onNodeWithTag("balloon-Sakura")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val widthDp = (root.width / compose.density.density).toInt()
        val heightDp = (root.height / compose.density.density).toInt()
        val font = context.resources.configuration.fontScale
        fun capture(suffix: String) {
            val file = File(context.getExternalFilesDir(null),
                "task5-matrix-${widthDp}x${heightDp}-f$font-$suffix.png")
            file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("TASK5_SCREENSHOT ${file.absolutePath}")
        }
        repeat(12) { sakura.performTouchInput { swipeDown() } }
        val kero = compose.onNodeWithTag("balloon-Kero")
        repeat(12) { kero.performTouchInput { swipeDown() } }
        capture("dialogue")
        repeat(12) { sakura.performTouchInput { swipeUp() } }
        compose.waitForIdle()
        println("TASK5_CHOICE balloon=${sakura.fetchSemanticsNode().boundsInRoot} last=${compose.onNodeWithTag("choice-8").fetchSemanticsNode().boundsInRoot}")
        compose.onNodeWithTag("choice-8").assertIsDisplayed().performTouchInput { click() }
        assertEquals(listOf(choices.last().token), chosen)
        capture("choices")
        println("TASK5_MATRIX viewport=$root density=${compose.density.density} font=$font safeTop=$safeTopPx safeBottom=$safeBottomPx")
    }

    @Test fun balloonTailsPointTowardRenderedSpeakersInBothLayouts() {
        val scenario = mutableStateOf(0)
        val sakuraImage = Bitmap.createBitmap(160, 200, Bitmap.Config.ARGB_8888).asImageBitmap()
        val largeKeroImage = Bitmap.createBitmap(160, 200, Bitmap.Config.ARGB_8888).asImageBitmap()
        val smallKeroImage = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).asImageBitmap()
        compose.setContent {
            val smallCompanion = scenario.value % 2 == 1
            val shifted = scenario.value >= 2
            val ready = StageState.Ready(
                PlaybackFrame(
                    SpeakerFrame(0, true, "Sakura speaks", true,
                        if (shifted) SurfaceVisual(0, emptyList(), -24, 0) else null),
                    SpeakerFrame(10, true, "Kero speaks", true,
                        if (shifted) SurfaceVisual(10, emptyList(), 24, 0) else null), false),
                "Test", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")),
            )
            val keroImage = if (smallCompanion) smallKeroImage else largeKeroImage
            GhostStage(ready, SurfaceImageLoader { file ->
                if (file.name == "kero") keroImage else sakuraImage
            }) { _, _, _ -> }
        }
        for (case in 0..3) {
            if (case > 0) compose.runOnIdle { scenario.value = case }
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            for ((balloonTag, characterTag) in listOf(
                "balloon-Sakura" to "sakura", "balloon-Kero" to "kero")) {
                val balloon = compose.onNodeWithTag(balloonTag).fetchSemanticsNode().boundsInRoot
                val character = compose.onNodeWithTag(characterTag).fetchSemanticsNode().boundsInRoot
                val targetX = character.center.x.toInt()
                val sampleY = balloon.bottom.toInt()
                val ivory = android.graphics.Color.rgb(255, 248, 233)
                val halfWindow = (3f * compose.density.density).toInt()
                val pointsTowardSpeaker = (targetX - halfWindow..targetX + halfWindow).any { x ->
                    x in 0 until bitmap.width && sampleY in 0 until bitmap.height &&
                        bitmap.getPixel(x, sampleY) == ivory
                }
                assertTrue("$balloonTag tail does not point toward $characterTag in " +
                    "${if (case % 2 == 1) "full/small" else "dual-half"} layout " +
                    "with shifted=${case >= 2}: " +
                    "balloon=$balloon character=$character sample=($targetX,$sampleY)",
                    pointsTowardSpeaker)
            }
        }
    }
}
