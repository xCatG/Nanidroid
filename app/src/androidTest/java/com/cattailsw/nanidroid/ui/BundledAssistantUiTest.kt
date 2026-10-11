package com.cattailsw.nanidroid.ui

import android.view.KeyEvent
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.lifecycleScope
import com.cattailsw.nanidroid.MainActivity
import com.cattailsw.nanidroid.NanidroidApplication
import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.engine.BuiltInShiori
import com.cattailsw.nanidroid.engine.BundledAssistantShiori
import com.cattailsw.nanidroid.engine.BuiltInAppAction
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.ghost.BundledAssistantContent
import com.cattailsw.nanidroid.ghost.BundledGhostRepository
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import com.cattailsw.nanidroid.ghost.CachedSurfaceImageLoader
import com.cattailsw.nanidroid.runtime.GhostRuntime
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.runtime.PresentedAppAction
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real bundled archive, assistant, runtime, ViewModel and MainActivity; fixtures stay in cache. */
class BundledAssistantUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun sakuraTapShowsHelpAndTopicNavigation() = withAssistant { _, runtime ->
        compose.onNodeWithTag("sakura").performTouchInput { click() }
        choice("What is Nanidroid?")
        choose("What is Nanidroid?")
        choice("Back")
        assertTrue(ready(runtime).frame.sakura.text.contains("ghosts on Android"))
        choose("Back")
        choose("Import and switch ghosts")
        choice("Back")
        assertTrue(ready(runtime).frame.sakura.text.contains("existing destinations are refused"))
        choose("Back")
        choose("Touch and dialogue controls")
        choice("Back")
        assertTrue(ready(runtime).frame.sakura.text.contains("depend on the ghost"))
        choose("Back")
        choose("Dismiss")
        compose.waitUntil(5_000) { ready(runtime).choices.values.all { it.isEmpty() } }
    }

    @Test fun droidChoicesOpenGhostsAndAbout() = withAssistant { _, runtime ->
        tools("Ghosts")
        compose.onNodeWithContentDescription("Close Ghosts").assertIsDisplayed().performClick()
        assertNull(ready(runtime).appAction)
        tools("About and licenses")
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        compose.onNodeWithTag("about-close").performClick()
        compose.onNodeWithTag("about-dialog").assertDoesNotExist()
    }

    @Test fun debugDialogSharesBoundsToggle() = withAssistant { _, _ ->
        tools("Debug tools")
        compose.onNodeWithTag("debug-tools-dialog").assertIsDisplayed()
        compose.onNodeWithText("Show touch bounds").assertIsDisplayed()
        compose.onNodeWithTag("debug-touch-bounds").assertIsDisplayed().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("debug-tools-close").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Hide touch bounds").assertExists()
        compose.onNodeWithTag("touch-bounds-action").performClick()
        tools("Debug tools")
        compose.onNodeWithTag("debug-touch-bounds").assertIsOff()
    }

    /** Models cleared FLAG_DEBUGGABLE on this debug-only emulator; this is not a release APK run. */
    @Test fun releasePolicyRejectsDebug() = withAssistant(debugEnabled = false) { scenario, runtime ->
        compose.onNodeWithTag("kero").performClick()
        choice("Dismiss")
        compose.onNode(assistantChoice("Debug tools")).assertDoesNotExist()
        val app = ApplicationProvider.getApplicationContext<NanidroidApplication>()
        val content = BundledAssistantContent.load({ app.assets.open(it) }, "en")
        val reply = runBlocking {
            BundledAssistantShiori(BuiltInShiori(""), content, false)
                .request(ShioriEvent("OnNanidroidOpenDebug"))
        }
        assertNull("Handler accepted debug under non-debug policy", reply.appAction)
        val snapshot = ready(runtime)
        val stage = mutableStateOf(snapshot)
        lateinit var releaseView: View
        lateinit var actualView: View
        val policyView = mutableStateOf<View?>(null)
        scenario.onActivity { activity ->
            releaseView = View(object : ContextWrapper(activity) {
                override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply {
                    flags = flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
                }
            })
            activity.setContent {
                actualView = LocalView.current
                CompositionLocalProvider(LocalView provides (policyView.value ?: actualView)) {
                    NanidroidTheme {
                        GhostStage(stage.value, CachedSurfaceImageLoader(), {}, {}, {},
                            assistantLabels = content, onClaimAppAction = { BuiltInAppAction.DebugTools })
                    }
                }
            }
        }
        waitForStage()
        compose.onNodeWithTag("touch-bounds-action").performClick()
        assertTrue("Debug fixture did not show the bounds border", magentaBorderPixels() > 0)
        compose.runOnIdle {
            policyView.value = releaseView
            stage.value = snapshot.copy(appAction = PresentedAppAction(999,
                snapshot.dialogueToken!!.sessionId, 1, 1, BuiltInAppAction.DebugTools))
        }
        compose.onNodeWithTag("debug-tools-dialog").assertDoesNotExist()
        compose.onNodeWithTag("touch-bounds-action").assertDoesNotExist()
        assertEquals("Non-debug policy leaked saved touch bounds", 0, magentaBorderPixels())
        // Rejected execution must not merely leave a hidden dialog queued in local state.
        compose.runOnIdle { policyView.value = actualView }
        compose.onNodeWithTag("debug-tools-dialog").assertDoesNotExist()
    }

    @Test fun oneSelectionOpensOnceAcrossRecomposition() = withAssistant { scenario, runtime ->
        tools("About and licenses")
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        val consumed = ready(runtime).dialogueToken
        assertNull(ready(runtime).appAction)
        // Character semantics must not dispatch behind the modal, even when activated directly.
        compose.onNodeWithTag("sakura").performClick()
        assertEquals(consumed, ready(runtime).dialogueToken)
        compose.onNodeWithTag("about-close").performClick()
        // Force a state publication and UI recomposition without creating another action.
        scenario.onActivity { activity -> activity.lifecycleScope.launch { runtime.refreshInstalledChoices() } }
        compose.waitForIdle()
        compose.onNodeWithTag("about-dialog").assertDoesNotExist()
        compose.onNodeWithTag("stage-controls-toggle").performClick()
        compose.onNodeWithTag("about-dialog").assertDoesNotExist()
    }

    @Test fun rotationDoesNotReplayConsumedAction() = withAssistant { scenario, runtime ->
        tools("About and licenses")
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        assertNull(ready(runtime).appAction)
        val session = ready(runtime).dialogueToken!!.sessionId
        lateinit var original: MainActivity
        scenario.onActivity {
            original = it
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10_000) {
            var rotated = false
            scenario.onActivity { rotated = it !== original &&
                it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            rotated
        }
        waitForStage()
        compose.onNodeWithTag("about-dialog").assertDoesNotExist()
        assertEquals(session, ready(runtime).dialogueToken!!.sessionId)
        tools("About and licenses")
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test fun inactiveStageDoesNotClaimUntilActive() = withAssistant { scenario, runtime ->
        val active = mutableStateOf(false)
        val app = ApplicationProvider.getApplicationContext<NanidroidApplication>()
        scenario.onActivity { activity ->
            activity.setContent {
                NanidroidTheme {
                    GhostStage(runtime.state.collectAsState().value, CachedSurfaceImageLoader(), {}, {}, {},
                        interactionActive = active.value, assistantLabels = app.assistantContent,
                        onClaimAppAction = runtime::claimAppAction)
                }
            }
            runtime.click(1, 0, 0)
        }
        compose.waitUntil(10_000) { ready(runtime).choices[1].orEmpty().any { it.label == "About and licenses" } }
        scenario.onActivity { runtime.choose(ready(runtime).choices[1]!!.single { it.label == "About and licenses" }.token) }
        compose.waitUntil(5_000) { ready(runtime).appAction != null ||
            compose.onAllNodes(hasTestTag("about-dialog")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("about-dialog").assertDoesNotExist()
        assertTrue("Inactive UI consumed the action", ready(runtime).appAction != null)
        compose.runOnIdle { active.value = true }
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        assertNull(ready(runtime).appAction)
    }

    @Test fun modalBackDismissesWithoutClosingStage() = withAssistant { _, runtime ->
        for (label in listOf("Ghosts", "About and licenses", "Debug tools")) {
            tools(label)
            when (label) {
                "Ghosts" -> compose.onNodeWithContentDescription("Close Ghosts").assertIsDisplayed()
                "About and licenses" -> compose.onNodeWithTag("about-dialog").assertIsDisplayed()
                else -> compose.onNodeWithTag("debug-tools-dialog").assertIsDisplayed()
            }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            assertTrue("Back closed runtime behind $label", runtime.state.value is StageState.Ready)
            compose.onNodeWithTag("about-dialog").assertDoesNotExist()
            compose.onNodeWithTag("debug-tools-dialog").assertDoesNotExist()
            compose.onNodeWithContentDescription("Close Ghosts").assertDoesNotExist()
        }
    }

    @Test fun switchDismissesOldTools() = withAssistant { scenario, runtime ->
        tools("Debug tools")
        compose.onNodeWithTag("debug-tools-dialog").assertIsDisplayed()
        val oldSession = ready(runtime).dialogueToken!!.sessionId
        scenario.onActivity { runtime.selectGhost("other") }
        compose.waitUntil(5_000) { ready(runtime).switchPrompt != null }
        scenario.onActivity { runtime.confirmSwitch() }
        compose.waitUntil(10_000) { ready(runtime).dialogueToken?.sessionId != oldSession }
        waitForStage()
        compose.onNodeWithTag("debug-tools-dialog").assertDoesNotExist()
        assertEquals("Other", ready(runtime).ghostName)
        compose.onNodeWithTag("ghosts-action").performClick()
        compose.onNodeWithContentDescription("Close Ghosts").assertIsDisplayed()
    }

    private fun ready(runtime: GhostRuntime) = runtime.state.value as StageState.Ready

    private fun waitForStage() = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("kero")).fetchSemanticsNodes().isNotEmpty()
    }

    private fun choice(label: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(assistantChoice(label)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assistantChoice(label: String) = hasText(label) and SemanticsMatcher("Assistant choice") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("choice-") == true
    }

    private fun choose(label: String) {
        choice(label)
        compose.onNode(assistantChoice(label)).performScrollTo().performClick()
    }

    private fun tools(label: String) {
        compose.onNodeWithTag("kero").performTouchInput { click() }
        choose(label)
        compose.waitForIdle()
    }

    private fun magentaBorderPixels(): Int {
        val pixels = compose.onNodeWithTag("sakura").captureToImage().toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (pixel.red > 0.95f && pixel.blue > 0.95f && pixel.green < 0.05f) count++
        }
        return count
    }

    private fun withAssistant(debugEnabled: Boolean = true,
        block: (ActivityScenario<MainActivity>, GhostRuntime) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<NanidroidApplication>()
        val root = File(app.cacheDir, "assistant-ui-${System.nanoTime()}").apply { mkdirs() }
        val repository = BundledGhostRepository({ app.assets.open("nanidroid.zip") }, root)
        val base = repository.load(repository.ensureInstalled(), "en")
        val content = BundledAssistantContent.load({ app.assets.open(it) }, "en")
        val master = File(root, "other/ghost/master").apply { mkdirs() }
        File(master, "descript.txt").writeText("name,Other\nsakura.name,Other guide\nkero.name,Other companion\nshiori,Nanidroid")
        File(master, "en").mkdirs()
        File(master, "en/content.txt").writeText("OnFirstBoot,\\_q\\0Other ghost\\e\nOnBoot,\\_q\\0Other ghost\\e")
        val shell = File(root, "other/shell/master").apply { mkdirs() }
        base.surfaces.forEach { (id, image) -> image.copyTo(File(shell, "surface$id.png")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val runtime = GhostRuntime({ base }, { BuiltInShiori(it.content) }, object : BootStateStore {
            override suspend fun recordActivation(directoryId: String) = false
            override suspend fun consumeOnboarding() = false
        }, scope, installedGhosts = InstalledGhostRepository(root),
            bundledEngineFactory = { BundledAssistantShiori(BuiltInShiori(it.content), content, debugEnabled) },
            // Advance the real script player promptly; assets, routes and dialogue tokens remain real.
            elapsedRealtime = { android.os.SystemClock.elapsedRealtime() * 20 })
        val runtimeField = NanidroidApplication::class.java.getDeclaredField("runtime\$delegate").apply { isAccessible = true }
        val labelsField = NanidroidApplication::class.java.getDeclaredField("assistantContent\$delegate").apply { isAccessible = true }
        val priorRuntime = runtimeField.get(app)
        val priorLabels = labelsField.get(app)
        runtimeField.set(app, lazy { runtime })
        labelsField.set(app, lazy { content })
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForStage()
                compose.waitUntil(10_000) { ready(runtime).frame.ended }
                block(scenario, runtime)
            }
        } finally {
            scope.cancel()
            runtimeField.set(app, priorRuntime)
            labelsField.set(app, priorLabels)
            root.deleteRecursively()
        }
    }
}
