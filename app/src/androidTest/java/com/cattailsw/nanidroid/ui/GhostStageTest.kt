package com.cattailsw.nanidroid.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performClick
import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.engine.BuiltInShiori
import com.cattailsw.nanidroid.engine.ShioriEngine
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.ghost.BundledGhost
import com.cattailsw.nanidroid.ghost.GhostDescriptor
import com.cattailsw.nanidroid.ghost.SurfaceImageLoader
import com.cattailsw.nanidroid.runtime.GhostRuntime
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.runtime.PlaybackFrame
import com.cattailsw.nanidroid.runtime.SpeakerFrame
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test

class GhostStageTest {
    @get:Rule val compose = createComposeRule()
    private var shownRuntime: GhostRuntime? = null

    @After fun pauseShownRuntime() { shownRuntime?.setResumed(false) }

    private val sakura = Bitmap.createBitmap(250, 144, Bitmap.Config.ARGB_8888).asImageBitmap()
    private val kero = Bitmap.createBitmap(235, 200, Bitmap.Config.ARGB_8888).asImageBitmap()
    private val images = SurfaceImageLoader { file -> if (file.name == "sakura") sakura else kero }

    @Test fun keroTapSendsOneUnscaledMouseClick() {
        val events = mutableListOf<ShioriEvent>()
        val runtime = runtime { event ->
            events += event
            ShioriReply(204)
        }
        show(runtime, images)
        compose.onNodeWithTag("kero").performTouchInput { click(center) }
        compose.waitUntil(3_000) { events.any { it.id == "OnMouseClick" } }
        val clicks = events.filter { it.id == "OnMouseClick" }
        assertEquals(1, clicks.size)
        assertEquals(listOf("117", "100", "0", "1", "", "0", "touch"), clicks.single().references)
    }

    @Test fun doubleTapUsesSurfaceCoordinatesWithoutSendingSingleTap() {
        val stage = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "", false), SpeakerFrame(10, true, "", false), false),
            "Nanidroid", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")))
        val singles = mutableListOf<List<Int>>()
        val doubles = mutableListOf<List<Int>>()
        compose.setContent {
            GhostStage(stage, images,
                onSelectGhost = {}, onConfirmSwitch = {}, onDismissSwitch = {},
                onCharacterDoubleClick = { speaker, x, y -> doubles.add(listOf(speaker, x, y)) },
                onCharacterClick = { speaker, x, y -> singles.add(listOf(speaker, x, y)) })
        }
        compose.onNodeWithTag("kero").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertEquals(listOf(listOf(1, 117, 100)), doubles)
        assertEquals(emptyList<List<Int>>(), singles)
    }

    @Test fun builtInEngineReceivesTapOnceAndReturnsNoScript() {
        val realEngine = BuiltInShiori("OnFirstBoot,\\0\\s0Hello\\e")
        val events = mutableListOf<ShioriEvent>()
        val statuses = mutableListOf<Int>()
        val runtime = runtime { event ->
            events += event
            realEngine.request(event).also { statuses += it.status }
        }
        show(runtime, images)
        compose.onNodeWithTag("kero").performTouchInput { click(center) }
        compose.waitUntil(3_000) { events.any { it.id == "OnMouseClick" } }
        assertEquals(1, events.count { it.id == "OnMouseClick" })
        assertEquals(204, statuses[events.indexOfFirst { it.id == "OnMouseClick" }])
    }

    @Test fun blockedImageLoadShowsLoadingThenBothCharacters() {
        val gate = CompletableDeferred<androidx.compose.ui.graphics.ImageBitmap>()
        val runtime = runtime { ShioriReply(204) }
        show(runtime, SurfaceImageLoader { file -> if (file.name == "sakura") gate.await() else kero }, waitForImages = false)
        compose.onNodeWithText("Loading ghost…").assertExists()
        gate.complete(sakura)
        compose.waitUntil(3_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Sakura")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Kero").assertExists()

    }

    @Test fun failedImageLoadShowsMissingSurfaceMessage() {
        val runtime = runtime { ShioriReply(204) }
        show(runtime, SurfaceImageLoader { throw IllegalStateException("decode failed") }, waitForImages = false)
        compose.waitUntil(3_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Character surfaces are missing"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Character surfaces are missing").assertExists()
    }

    @Test fun balloonTapDoesNotDispatchCharacterClick() {
        var taps = 0
        val stage = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "Hello", true), SpeakerFrame(10, true, "", false), false),
            "Nanidroid", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")),
        )
        compose.setContent { GhostStage(stage, images) { _, _, _ -> taps++ } }
        compose.waitUntil(3_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("balloon-Sakura"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("balloon-Sakura").assertExists()
        compose.onNodeWithTag("balloon-Sakura").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(0, taps)
    }

    @Test fun ghostSelectionWaitsForConfirmationAndCancelKeepsCurrentGhost() {
        val candidate = GhostDescriptor("guest", "Guest Ghost", "Guest", "Companion",
            File("guest/master"), mapOf(0 to File("sakura"), 10 to File("kero")),
            "satori.dll", File("guest/readme.txt"))
        val stage = mutableStateOf(StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "", false), SpeakerFrame(10, true, "", false), false),
            "Nanidroid", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")),
            installedGhosts = listOf(candidate),
        ))
        var confirmed = 0
        compose.setContent {
            GhostStage(stage.value, images,
                onSelectGhost = { id -> stage.value = stage.value.copy(
                    switchPrompt = com.cattailsw.nanidroid.runtime.SwitchPrompt(id, "Guest Ghost", "Read me first")) },
                onConfirmSwitch = { confirmed++; stage.value = stage.value.copy(ghostName = "Guest Ghost", switchPrompt = null) },
                onDismissSwitch = { stage.value = stage.value.copy(switchPrompt = null) },
                onCharacterClick = { _, _, _ -> })
        }
        compose.onNodeWithContentDescription("Ghosts").performClick()
        compose.onNodeWithText("Guest Ghost").performClick()
        compose.onNodeWithText("Read me first").assertExists()
        compose.onNodeWithContentDescription("Cancel ghost switch").performClick()
        assertEquals(0, confirmed)
        assertEquals("Nanidroid", stage.value.ghostName)
        compose.onNodeWithContentDescription("Ghosts").performClick()
        compose.onNodeWithText("Guest Ghost").performClick()
        compose.onNodeWithContentDescription("Confirm ghost switch").performClick()
        assertEquals(1, confirmed)
        assertEquals("Guest Ghost", stage.value.ghostName)
    }

    @Test fun activationErrorIsVisibleAndAccessible() {
        val stage = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "", false), SpeakerFrame(10, true, "", false), false),
            "Nanidroid", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")),
            activationError = "Native load failed: 0")
        compose.setContent { GhostStage(stage, images) { _, _, _ -> } }
        compose.onNodeWithContentDescription("Ghost error: Native load failed: 0").assertExists()
    }

    @Test fun nonstandardShellSurfaceIdsStillRenderBothCharacters() {
        val stage = StageState.Ready(
            PlaybackFrame(SpeakerFrame(100, true, "", false), SpeakerFrame(101, true, "", false), false),
            "LOBO", "Lobo", "Companion", mapOf(100 to File("sakura"), 101 to File("kero")))
        compose.setContent { GhostStage(stage, images) { _, _, _ -> } }
        compose.waitUntil(3_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Lobo"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Lobo").assertExists()
        compose.onNodeWithContentDescription("Companion").assertExists()
    }

    @Test fun ghostsActionDoesNotCoverVisibleDialogue() {
        val stage = StageState.Ready(
            PlaybackFrame(SpeakerFrame(100, true, "Visible dialogue", true),
                SpeakerFrame(101, true, "", false), false),
            "LOBO", "Lobo", "Companion", mapOf(100 to File("sakura"), 101 to File("kero")))
        compose.setContent { GhostStage(stage, images) { _, _, _ -> } }
        compose.waitUntil(3_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("balloon-Lobo"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val action = compose.onNodeWithContentDescription("Ghosts").fetchSemanticsNode().boundsInRoot
        val balloon = compose.onNodeWithTag("balloon-Lobo").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Ghosts action overlaps dialogue: $action vs $balloon",
            action.bottom <= balloon.top)
    }

    private fun runtime(engine: suspend (ShioriEvent) -> ShioriReply): GhostRuntime = GhostRuntime(
        loadGhost = {
            BundledGhost("nanidroid", "Nanidroid", "Sakura", "Kero",
                mapOf(0 to File("sakura"), 10 to File("kero")), "")
        },
        engineFactory = { object : ShioriEngine { override suspend fun request(event: ShioriEvent) = engine(event) } },
        bootState = object : BootStateStore {
            override suspend fun recordActivation(directoryId: String) = true
            override suspend fun consumeOnboarding() = false
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        elapsedRealtime = { 0L },
    )

    private fun show(runtime: GhostRuntime, loader: SurfaceImageLoader, waitForImages: Boolean = true) {
        shownRuntime = runtime
        compose.setContent {
            val state by runtime.state.collectAsState()
            GhostStage(state, loader, runtime::click)
        }
        compose.runOnIdle {
            runtime.setResumed(true)
            CoroutineScope(Dispatchers.Main.immediate).launch { runtime.start("en") }
        }
        compose.waitUntil(3_000) { runtime.state.value is StageState.Ready }
        if (waitForImages) compose.waitUntil(3_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("kero")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
