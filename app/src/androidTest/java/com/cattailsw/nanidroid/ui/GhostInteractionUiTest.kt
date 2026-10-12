package com.cattailsw.nanidroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import com.cattailsw.nanidroid.ghost.SurfaceImageLoader
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.runtime.PlaybackFrame
import com.cattailsw.nanidroid.runtime.SpeakerFrame
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.runtime.DialogueToken
import com.cattailsw.nanidroid.runtime.InteractionToken
import com.cattailsw.nanidroid.runtime.PresentedChoice
import com.cattailsw.nanidroid.runtime.PresentedInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import android.view.KeyEvent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.cattailsw.nanidroid.MainActivity
import com.cattailsw.nanidroid.NanidroidApplication
import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.engine.ShioriEngine
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.ghost.BundledGhost
import com.cattailsw.nanidroid.runtime.GhostRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class GhostInteractionUiTest {
    @get:Rule val compose = createComposeRule()
    private val dialogue = DialogueToken(7, 11)

    private fun imeShown(): Boolean {
        val dump = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("dumpsys input_method"))
            .bufferedReader().use { it.readText() }
        return dump.contains("mInputShown=true")
    }

    @Test fun realImeAtLargeFontLeavesInputActionsReachableAndDispatchesOnce() {
        val input = mutableStateOf(PresentedInput(InteractionToken(7, 11, 1), "name"))
        val draft = mutableStateOf("draft")
        val submitted = mutableListOf<String>()
        var cancelled = 0
        compose.setContent {
            GhostInputDialog(input.value, draft.value, onDraftChange = { draft.value = it },
                onSubmit = { submitted += it }, onCancel = { cancelled++ })
        }
        compose.onNodeWithTag("ghost-input").performClick()
        compose.waitUntil(8_000) { imeShown() }
        Thread.sleep(750)
        assertTrue("IME closed before input could be used", imeShown())
        compose.onNodeWithContentDescription("Input OK").assertIsDisplayed()
        compose.onNodeWithContentDescription("Input Cancel").assertIsDisplayed()
        val app = ApplicationProvider.getApplicationContext<NanidroidApplication>()
        val metrics = app.resources.displayMetrics
        val name = "task5-ime-${metrics.widthPixels}x${metrics.heightPixels}-f${app.resources.configuration.fontScale}.png"
        saveScreenshot(app, name, InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        compose.onNodeWithContentDescription("Input Cancel").performClick()
        assertEquals(1, cancelled)
        compose.runOnIdle { input.value = PresentedInput(InteractionToken(7, 11, 2), "next") }
        compose.onNodeWithTag("ghost-input").performClick()
        compose.onNodeWithContentDescription("Input OK").performClick()
        assertEquals(listOf("draft"), submitted)
        compose.runOnIdle { input.value = PresentedInput(InteractionToken(7, 11, 3), "final") }
        compose.onNodeWithTag("ghost-input").performClick()
        compose.onNodeWithTag("ghost-input").performImeAction()
        compose.onNodeWithContentDescription("Input OK").performClick()
        assertEquals(listOf("draft", "draft"), submitted)
    }

    @Test fun choicesStayInTextOrderWrapAndHaveSeparateTouchTargets() {
        val first = PresentedChoice(InteractionToken(7, 11, 1), "A very long choice label that must wrap onto more than one line", 5)
        val last = PresentedChoice(InteractionToken(7, 11, 2), "Final choice", 11)
        val chosen = mutableListOf<InteractionToken>()
        compose.setContent {
            Box(Modifier.width(220.dp).height(400.dp)) {
                BalloonContent("Start middle end", listOf(first, last), dialogue,
                    onChoose = { chosen += it }, onOpenLink = { _, _ -> })
            }
        }
        compose.onNodeWithTag("choice-1").assertExists()
        val firstBounds = compose.onNodeWithTag("choice-1").fetchSemanticsNode().boundsInRoot
        val lastBounds = compose.onNodeWithTag("choice-2").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue(firstBounds.height >= 48f * density)
        assertTrue(lastBounds.height >= 48f * density)
        assertTrue(firstBounds.bottom <= lastBounds.top)
        compose.onNodeWithTag("choice-2").performClick()
        assertEquals(listOf(last.token), chosen)
    }

    @Test fun finalChoiceCanBeReachedByScrolling() {
        val choices = (1L..8L).map {
            PresentedChoice(InteractionToken(7, 11, it), "Choice $it", 0)
        }
        val chosen = mutableListOf<InteractionToken>()
        compose.setContent {
            Box(Modifier.width(220.dp).height(120.dp)) {
                BalloonContent("", choices, dialogue, onChoose = { chosen += it },
                    onOpenLink = { _, _ -> })
            }
        }
        compose.onNodeWithTag("choice-8").performScrollTo().performClick()
        assertEquals(listOf(choices.last().token), chosen)
    }

    @Test fun inputDraftSurvivesRecompositionForSameTokenAndResetsForNextToken() {
        val input = mutableStateOf(PresentedInput(InteractionToken(7, 11, 3), "name"))
        val draft = mutableStateOf("hello")
        val submitted = mutableListOf<String>()
        compose.setContent {
            GhostInputDialog(input.value, draft.value, onDraftChange = { draft.value = it },
                onSubmit = { submitted += it }, onCancel = {})
        }
        compose.onNodeWithContentDescription("Input OK").performClick()
        assertEquals(listOf("hello"), submitted)
        compose.runOnIdle { input.value = PresentedInput(InteractionToken(7, 12, 4), "next"); draft.value = "" }
        compose.onNodeWithContentDescription("Input field").assertExists()
    }

    @Test fun focusedUserClearStillUpdatesDraftToEmpty() {
        val draft = mutableStateOf("clear me")
        compose.setContent {
            GhostInputDialog(PresentedInput(InteractionToken(7, 11, 3), "name"), draft.value,
                onDraftChange = { draft.value = it }, onSubmit = {}, onCancel = {})
        }
        compose.onNodeWithTag("ghost-input").performClick()
        compose.onNodeWithTag("ghost-input").performTextClearance()
        compose.runOnIdle { assertEquals("", draft.value) }
    }

    @Test fun inputImeAndOkConsumeTheSameRequestOnlyOnce() {
        val submitted = mutableListOf<String>()
        compose.setContent {
            GhostInputDialog(PresentedInput(InteractionToken(7, 11, 3), "name"), "answer",
                onDraftChange = {}, onSubmit = { submitted += it }, onCancel = {})
        }
        compose.onNodeWithTag("ghost-input").performImeAction()
        compose.onNodeWithContentDescription("Input OK").performClick()
        assertEquals(listOf("answer"), submitted)
    }

    @Test fun invalidPastedInputCanBeCorrectedAndSubmitted() {
        val draft = mutableStateOf("first\nsecond")
        val submitted = mutableListOf<String>()
        compose.setContent {
            GhostInputDialog(PresentedInput(InteractionToken(7, 11, 3), "name"), draft.value,
                onDraftChange = { draft.value = it }, onSubmit = { submitted += it }, onCancel = {})
        }
        compose.onNodeWithContentDescription("Input OK").performClick()
        compose.runOnIdle { draft.value = "corrected" }
        compose.onNodeWithContentDescription("Input OK").performClick()
        assertEquals(listOf("corrected"), submitted)
    }

    @Test fun invalidPastedInputCanStillBeCancelled() {
        var cancellations = 0
        compose.setContent {
            GhostInputDialog(PresentedInput(InteractionToken(7, 11, 3), "name"), "first\nsecond",
                onDraftChange = {}, onSubmit = {}, onCancel = { cancellations++ })
        }
        compose.onNodeWithContentDescription("Input OK").performClick()
        compose.onNodeWithContentDescription("Input Cancel").performClick()
        assertEquals(1, cancellations)
    }

    @Test fun annotatedLinkCallsOnlyExplicitLauncherCallback() {
        val opened = mutableListOf<Pair<DialogueToken, String>>()
        compose.setContent {
            Box(Modifier.width(300.dp).height(100.dp)) {
                BalloonContent("Visit https://example.org now", emptyList(), dialogue,
                    onChoose = {}, onOpenLink = { token, url -> opened += token to url })
            }
        }
        compose.onNodeWithText("Visit https://example.org now").performClick()
        assertEquals(listOf(dialogue to "https://example.org"), opened)
    }

    @Test fun stageChoiceAndLinkDoNotBecomeCharacterTaps() {
        val surface = Bitmap.createBitmap(160, 200, Bitmap.Config.ARGB_8888).asImageBitmap()
        val stage = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "Visit https://example.org", true),
                SpeakerFrame(10, true, "", false), false),
            "Task 5", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")),
            choices = mapOf(0 to listOf(PresentedChoice(InteractionToken(7, 11, 1), "Choose", 0))),
            dialogueToken = dialogue)
        var taps = 0
        var choices = 0
        var links = 0
        compose.setContent {
            GhostStage(stage, SurfaceImageLoader { surface }, {}, {}, {}, { _, _, _ -> },
                { _, _, _ -> taps++ }, onChoose = { choices++ }, onOpenLink = { _, _ -> links++ })
        }
        compose.onNodeWithTag("choice-1").performClick()
        compose.onNodeWithText("Visit https://example.org").performClick()
        assertEquals(1, choices)
        assertEquals(1, links)
        assertEquals(0, taps)
    }

    @Test fun ordinaryInputBackCancelsButFarewellInputBackSkipsClose() {
        val input = mutableStateOf(PresentedInput(InteractionToken(7, 11, 1), "first"))
        val farewell = mutableStateOf(false)
        val stage = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "", false), SpeakerFrame(10, true, "", false), false),
            "Task 5", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")),
            input = input.value, dialogueToken = dialogue)
        var cancels = 0
        var closes = 0
        compose.setContent {
            GhostStage(stage.copy(input = input.value), SurfaceImageLoader {
                Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).asImageBitmap()
            }, {}, {}, {}, { _, _, _ -> }, { _, _, _ -> },
                onCancelInput = { cancels++ }, onClose = { closes++ },
                isFarewellInput = { farewell.value })
        }
        compose.onNodeWithContentDescription("Input field").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertEquals(1, cancels)
        assertEquals(0, closes)
        compose.runOnIdle {
            input.value = PresentedInput(InteractionToken(7, 12, 2), "farewell")
            farewell.value = true
        }
        compose.onNodeWithContentDescription("Input field").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertEquals(1, cancels)
        assertEquals(1, closes)
    }

    @Test fun capturesChoiceLayoutAtNormalAndLargeFont() {
        val app = ApplicationProvider.getApplicationContext<NanidroidApplication>()
        val surface = Bitmap.createBitmap(200, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(170, 120, 130))
        }.asImageBitmap()
        val loader = SurfaceImageLoader { surface }
        val choice = PresentedChoice(InteractionToken(7, 11, 1),
            "A longer choice that wraps on a compact screen", 18)
        val stage = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "Hello from Sakura. Visit https://example.org", true),
                SpeakerFrame(10, true, "Companion", true), false),
            "Task 5", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")),
            choices = mapOf(0 to listOf(choice)), dialogueToken = dialogue,
        )
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                GhostStage(stage, loader) { _, _, _ -> }
            }
        }
        compose.waitUntil(3_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("choice-1"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        saveScreenshot(app, "normal.png", compose.onRoot().captureToImage().asAndroidBitmap())
        compose.runOnIdle { fontScale.value = 1.5f }
        compose.waitForIdle()
        val balloonBounds = compose.onNodeWithTag("balloon-Sakura").fetchSemanticsNode().boundsInRoot
        val choiceTextBounds = compose.onNodeWithText(choice.label, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Choice text escapes Sakura balloon: $choiceTextBounds vs $balloonBounds",
            choiceTextBounds.left >= balloonBounds.left && choiceTextBounds.right <= balloonBounds.right)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(choice.label, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertTrue("Choice label overflows its measured width", layouts.isNotEmpty() &&
            layouts.none { it.hasVisualOverflow })
        saveScreenshot(app, "large-font.png", compose.onRoot().captureToImage().asAndroidBitmap())
    }

    private fun saveScreenshot(app: NanidroidApplication, name: String, bitmap: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NanidroidTask5")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}

class GhostActivityRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun inputDraftSurvivesRealActivityRecreationAndSubmitsOnce() {
        runRecreationCase("verified", "remember me", 1)
    }

    /** Each invocation is a fresh instrumentation process; the host runs the fixed 20-case set. */
    @Test fun fixedTwoRecreationInputCase() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Run host fixed two-recreation probe; required arguments: variant, repetition, draft",
            listOf("variant", "repetition", "draft").any { args.containsKey(it) })
        require(listOf("variant", "repetition", "draft").all { !args.getString(it).isNullOrBlank() }) {
            "Provide all nonblank host arguments: variant, repetition, draft"
        }
        val variant = requireNotNull(args.getString("variant")) { "variant required" }
        require(variant == "immediate" || variant == "verified") { "Unexpected variant: $variant" }
        val repetition = requireNotNull(args.getString("repetition")) { "repetition required" }.toInt()
        require(repetition in 1..5) { "repetition must be 1..5" }
        val draft = requireNotNull(args.getString("draft")) { "draft required" }
        require(draft.isNotBlank()) { "draft must be nonblank" }
        runRecreationCase(variant, draft, 2)
    }

    private fun runRecreationCase(variant: String, draft: String, recreations: Int) {
        val app = ApplicationProvider.getApplicationContext<NanidroidApplication>()
        val surfaces = File(app.cacheDir, "task5-activity-surfaces").apply { mkdirs() }
        val sakura = File(surfaces, "sakura.png")
        val kero = File(surfaces, "kero.png")
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
            sakura.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            kero.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val events = java.util.Collections.synchronizedList(mutableListOf<ShioriEvent>())
        val loads = java.util.concurrent.atomic.AtomicInteger()
        val runtime = GhostRuntime(
            loadGhost = { loads.incrementAndGet(); BundledGhost("task5", "Task 5", "Sakura", "Kero",
                mapOf(0 to sakura, 10 to kero), "") },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    events += event
                    return if (event.id == "OnFirstBoot")
                        ShioriReply(200, "\\![open,inputbox,name]\\_qhttps://example.org\\_w[10000]\\e") else ShioriReply(204)
                }
            } },
            bootState = object : BootStateStore {
                override suspend fun recordActivation(directoryId: String) = true
                override suspend fun consumeOnboarding() = false
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
        val field = NanidroidApplication::class.java.getDeclaredField("runtime\$delegate")
        field.isAccessible = true
        val prior = field.get(app)
        field.set(app, lazy { runtime })
        var primary: Throwable? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(5_000) {
                    compose.onAllNodes(androidx.compose.ui.test.hasTestTag("ghost-input"))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("ghost-input").performTextInput(draft)
                val inputBefore = (runtime.state.value as StageState.Ready).input
                assertTrue("Runtime input absent before recreation", inputBefore != null)
                // No idle, assertion or frame wait between edit and first recreate in immediate mode.
                if (variant == "verified") assertDraftExactly(draft)
                println("M5_INPUT before variant=$variant draft=$draft token=${inputBefore!!.token} " +
                    "loads=${loads.get()} events=${synchronized(events) { events.toList() }}")
                repeat(recreations) { cycle ->
                    scenario.recreate()
                    compose.waitUntil(5_000) {
                        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("ghost-input"))
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    assertEquals("Runtime input changed in recreation $cycle", inputBefore,
                        (runtime.state.value as StageState.Ready).input)
                    assertDraftExactly(draft)
                    println("M5_INPUT restored variant=$variant cycle=${cycle + 1} " +
                        "token=${(runtime.state.value as StageState.Ready).input?.token} " +
                        "loads=${loads.get()} events=${synchronized(events) { events.toList() }}")
                }
                compose.onNodeWithContentDescription("Input OK").performClick()
                compose.waitUntil(5_000) { synchronized(events) { events.count { it.id == "OnUserInput" } == 1 } }
                assertEquals(1, loads.get())
                val observedEvents = synchronized(events) { events.toList() }
                assertEquals(1, observedEvents.count { it.id == "OnFirstBoot" })
                assertEquals(listOf("name", draft), observedEvents.single { it.id == "OnUserInput" }.references)
                assertEquals(1, observedEvents.count { it.id == "OnUserInput" })
                compose.waitUntil(5_000) {
                    compose.onAllNodes(androidx.compose.ui.test.hasText("https://example.org"))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("https://example.org").performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("link-error").assertDoesNotExist()
                runtime.close()
                compose.waitUntil(5_000) { runtime.state.value is StageState.Finished }
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                runtime.close()
                compose.waitUntil(5_000) { runtime.state.value is StageState.Finished }
            } catch (cleanup: Throwable) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            } finally {
                field.set(app, prior)
                sakura.delete(); kero.delete(); surfaces.delete()
            }
        }
    }

    private fun assertDraftExactly(expected: String) {
        val actual = compose.onNodeWithTag("ghost-input").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        assertEquals("Exact synthetic input draft", expected, actual)
    }
}
