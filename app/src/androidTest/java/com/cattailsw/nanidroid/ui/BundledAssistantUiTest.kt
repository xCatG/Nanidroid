package com.cattailsw.nanidroid.ui

import android.app.Activity
import android.app.Application
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.LocaleList
import android.system.Os
import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.MainActivity
import com.cattailsw.nanidroid.NanidroidApplication
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.engine.BuiltInShiori
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.ghost.BundledGhostRepository
import com.cattailsw.nanidroid.ghost.CachedSurfaceImageLoader
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import com.cattailsw.nanidroid.runtime.GhostRuntime
import com.cattailsw.nanidroid.runtime.HostUiCommand
import com.cattailsw.nanidroid.runtime.HostUiRequest
import com.cattailsw.nanidroid.runtime.ScriptPlayer
import com.cattailsw.nanidroid.runtime.StageState
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        assertNull(ready(runtime).hostUiRequest)
        tools("About and licenses")
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        compose.onNodeWithTag("about-close").performClick()
        compose.onNodeWithTag("about-dialog").assertDoesNotExist()
    }

    @Test fun debugDialogSharesBoundsToggle() = withAssistant { _, _ ->
        val app = ApplicationProvider.getApplicationContext<NanidroidApplication>()
        for (tag in listOf("zh-TW", "zh-Hant", "zh-HK", "zh-MO")) {
            val configuration = Configuration(app.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tag))
            }
            val resources = app.createConfigurationContext(configuration).resources
            assertEquals("開發人員工具", resources.getString(R.string.debug_tools_title))
            assertEquals("顯示觸控範圍", resources.getString(R.string.stage_show_bounds))
            assertEquals("關閉", resources.getString(R.string.action_close))
        }
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
        val reply = runBlocking {
            BuiltInShiori.bundled(repositoryContent(app), false)
                .request(ShioriEvent("OnNanidroidOpenDebug"))
        }
        assertEquals(listOf(HostUiCommand.Developer), ScriptPlayer(reply.value!!).advanceBy(0).hostCommands)
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
                        GhostStage(
                            stage.value, CachedSurfaceImageLoader(), {}, {}, {},
                            onClaimHostUiRequest = { HostUiCommand.Developer }
                        )
                    }
                }
            }
        }
        waitForStage()
        compose.onNodeWithTag("touch-bounds-action").performClick()
        assertTrue("Debug fixture did not show the bounds border", magentaBorderPixels() > 0)
        compose.runOnIdle {
            policyView.value = releaseView
            stage.value = snapshot.copy(hostUiRequest = HostUiRequest(999, HostUiCommand.Developer))
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
        assertNull(ready(runtime).hostUiRequest)
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
        // The runtime owns the only FIFO; closing one modal must expose its next head.
        scenario.onActivity { runtime.beginImportEvents()!!.emit("OnUiBatch") }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("about-dialog")).fetchSemanticsNodes().isNotEmpty() ||
                ready(runtime).hostUiRequest != null
        }
        compose.onNodeWithContentDescription("Close Ghosts").assertIsDisplayed()
        compose.onNodeWithTag("about-dialog").assertDoesNotExist()
        assertEquals(HostUiCommand.About, ready(runtime).hostUiRequest!!.command)
        compose.onNodeWithContentDescription("Close Ghosts").performClick()
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        assertNull(ready(runtime).hostUiRequest)
        compose.onNodeWithTag("about-close").performClick()
        compose.onNodeWithTag("about-dialog").assertDoesNotExist()
    }

    @Test fun rotationDoesNotReplayConsumedAction() = withAssistant { scenario, runtime ->
        tools("About and licenses")
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        assertNull(ready(runtime).hostUiRequest)
        val session = ready(runtime).dialogueToken!!.sessionId
        lateinit var original: MainActivity
        scenario.onActivity {
            original = it
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10_000) {
            var rotated = false
            scenario.onActivity {
                rotated = it !== original &&
                    it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
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
                    GhostStage(
                        runtime.state.collectAsState().value, CachedSurfaceImageLoader(), {}, {}, {},
                        interactionActive = active.value,
                        onClaimHostUiRequest = runtime::claimHostUiRequest
                    )
                }
            }
            runtime.click(1, 0, 0)
        }
        compose.waitUntil(10_000) { ready(runtime).choices[1].orEmpty().any { it.label == "About and licenses" } }
        scenario.onActivity {
            runtime.choose(ready(runtime).choices[1]!!.single { it.label == "About and licenses" }.token)
        }
        compose.waitUntil(5_000) {
            ready(runtime).hostUiRequest != null ||
                compose.onAllNodes(hasTestTag("about-dialog")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("about-dialog").assertDoesNotExist()
        assertTrue("Inactive UI consumed the action", ready(runtime).hostUiRequest != null)
        compose.runOnIdle { active.value = true }
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        assertNull(ready(runtime).hostUiRequest)
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

    @Test fun importedSwitchNameStaysLiteralOnTrustedDefaultReturn() =
        withAssistant(foreignName = "Probe\\![open,developer]") { scenario, runtime ->
            val name = "Probe\\![open,developer]"
            val original = ready(runtime).dialogueToken!!.sessionId
            scenario.onActivity { runtime.selectGhost("other") }
            compose.waitUntil(5_000) { ready(runtime).switchPrompt != null }
            scenario.onActivity { runtime.confirmSwitch() }
            compose.waitUntil(10_000) {
                ready(runtime).ghostName == name && ready(runtime).dialogueToken!!.sessionId != original
            }
            scenario.onActivity { runtime.selectGhost("nanidroid") }
            compose.waitUntil(5_000) { ready(runtime).switchPrompt != null }
            scenario.onActivity { runtime.confirmSwitch() }
            compose.waitUntil(10_000) { ready(runtime).ghostName == "Nanidroid" && ready(runtime).frame.ended }
            assertEquals("Changed From $name", ready(runtime).frame.sakura.text)
            assertNull(ready(runtime).hostUiRequest)
            compose.onNodeWithTag("debug-tools-dialog").assertDoesNotExist()
            tools("Debug tools")
            compose.onNodeWithTag("debug-tools-dialog").assertIsDisplayed()
        }

    @Test fun ghostsThenAboutPresentsAfterDismissal() = withAssistant { scenario, runtime ->
        scenario.onActivity { runtime.beginImportEvents()!!.emit("OnUiBatch") }
        compose.onNodeWithContentDescription("Close Ghosts").assertIsDisplayed()
        compose.onNodeWithTag("about-dialog").assertDoesNotExist()
        assertEquals(HostUiCommand.About, ready(runtime).hostUiRequest!!.command)
        compose.onNodeWithContentDescription("Close Ghosts").performClick()
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        assertNull(ready(runtime).hostUiRequest)
    }

    @Test fun repeatedHostCommandPresentsTwice() = withAssistant { scenario, runtime ->
        scenario.onActivity { runtime.beginImportEvents()!!.emit("OnUiRepeat") }
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        val second = ready(runtime).hostUiRequest!!
        assertEquals(HostUiCommand.About, second.command)
        compose.onNodeWithTag("about-close").performClick()
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        assertNull(ready(runtime).hostUiRequest)
        compose.onNodeWithTag("about-close").performClick()
        compose.onNodeWithTag("about-dialog").assertDoesNotExist()
    }

    @Test fun hostRequestWaitsForExistingInputModal() = withAssistant { scenario, runtime ->
        scenario.onActivity { runtime.beginImportEvents()!!.emit("OnUiInput") }
        compose.waitUntil(5_000) { ready(runtime).input != null && ready(runtime).hostUiRequest != null }
        compose.onNodeWithTag("ghost-input").assertIsDisplayed()
        compose.onNodeWithTag("about-dialog").assertDoesNotExist()
        val beforeCancel = ready(runtime)
        val dialogue = requireNotNull(beforeCancel.dialogueToken)
        assertEquals(HostUiCommand.About, beforeCancel.hostUiRequest!!.command)
        compose.onNodeWithContentDescription("Input Cancel").performClick()
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        assertEquals(dialogue, ready(runtime).dialogueToken)
        assertEquals(dialogue.sessionId, ready(runtime).dialogueToken!!.sessionId)
        assertNull(ready(runtime).hostUiRequest)
    }

    @Test fun ordinaryGhostOpensCommonDialogsAndRejectsDeveloper() = withAssistant { scenario, runtime ->
        scenario.onActivity { runtime.selectGhost("other") }
        compose.waitUntil(5_000) { ready(runtime).switchPrompt != null }
        scenario.onActivity { runtime.confirmSwitch() }
        compose.waitUntil(10_000) { ready(runtime).ghostName == "Other" && ready(runtime).frame.ended }
        scenario.onActivity { runtime.beginImportEvents()!!.emit("OnUiOrdinary") }
        compose.onNodeWithContentDescription("Close Ghosts").assertIsDisplayed().performClick()
        compose.onNodeWithTag("about-dialog").assertIsDisplayed()
        compose.onNodeWithTag("about-close").performClick()
        compose.onNodeWithTag("debug-tools-dialog").assertDoesNotExist()
        assertNull(ready(runtime).hostUiRequest)
        assertEquals("Other", ready(runtime).ghostName)
    }

    @Test fun englishStartupMenusAndDebugResources() {
        assertFixtureSetupFailureCleanup()
        localizedMenus("en-US", "What is Nanidroid?", "Ghosts", "Debug tools", "Show touch bounds", "Close")
    }

    @Test fun japaneseStartupMenusAndDebugResources() = localizedMenus(
        "ja-JP",
        "Nanidroidとは？", "ゴースト管理", "デバッグツール", "タッチ範囲を表示", "閉じる"
    )

    @Test fun traditionalChineseStartupMenusAndDebugResources() = localizedMenus(
        "zh-TW",
        "Nanidroid 是什麼？", "幽靈管理", "開發人員工具", "顯示觸控範圍", "關閉"
    )

    @Test fun androidManagedFileAndParentSymlinksAreRejected() {
        val app = ApplicationProvider.getApplicationContext<NanidroidApplication>()
        val root = File(app.cacheDir, "assistant-links-${System.nanoTime()}").apply { mkdirs() }
        try {
            for (parent in listOf(false, true)) {
                val ghostRoot = File(root, "ghost-$parent")
                val repository = BundledGhostRepository({ app.assets.open("nanidroid.zip") }, ghostRoot)
                val installed = repository.ensureInstalled()
                val target = File(root, "external-$parent").apply { mkdirs() }
                val bytes = "outside target must remain unchanged".toByteArray()
                val outside = File(target, "content.txt").apply { writeBytes(bytes) }
                val link = File(installed, if (parent) "ghost/master/en" else "ghost/master/en/content.txt")
                if (parent) link.deleteRecursively() else assertTrue(link.delete())
                Os.symlink(if (parent) target.path else outside.path, link.path)
                try {
                    repository.ensureInstalled()
                    fail("Managed ${if (parent) "parent" else "file"} symlink accepted")
                } catch (_: IllegalArgumentException) {
                    assertTrue(outside.readBytes().contentEquals(bytes))
                } finally {
                    java.nio.file.Files.delete(link.toPath())
                }
            }
        } finally { root.deleteRecursively() }
    }

    private fun localizedMenus(
        tag: String, help: String, ghosts: String, debug: String,
        bounds: String, close: String
    ) = withAssistant(localeTag = tag) { _, runtime ->
        compose.onNodeWithTag("sakura").performTouchInput { click() }
        choice(help)
        capture("$tag-help", runtime)
        compose.onNodeWithTag("kero").performTouchInput { click() }
        choice(ghosts)
        choice(debug)
        capture("$tag-tools", runtime)
        choose(debug)
        compose.onNodeWithTag("debug-tools-dialog").assertIsDisplayed()
        compose.onNodeWithText(bounds).assertIsDisplayed()
        compose.onNodeWithText(close).assertIsDisplayed()
        capture("$tag-debug", runtime)
    }

    private fun capture(name: String, runtime: GhostRuntime) {
        compose.waitUntil(5_000) { ready(runtime).frame.ended }
        compose.waitForIdle()
        // UiAutomation reads the display compositor, which can lag idle semantics.
        android.os.SystemClock.sleep(100)
        val app = ApplicationProvider.getApplicationContext<NanidroidApplication>()
        val directory = File(app.getExternalFilesDir(null), "cleanup-evidence").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun assertFixtureSetupFailureCleanup() {
        val app = ApplicationProvider.getApplicationContext<NanidroidApplication>()
        val previousLocale = Locale.getDefault()
        val previousLocaleList = LocaleList.getDefault()
        val previousLocales = app.resources.configuration.locales
        val runtimeField = NanidroidApplication::class.java.getDeclaredField("runtime\$delegate")
            .apply { isAccessible = true }
        val priorRuntime = runtimeField.get(app)
        fun ownedCacheRoots() = app.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("assistant-ui-") }.map { it.name }.toSet()
        val priorRoots = ownedCacheRoots()
        try {
            withAssistant(localeTag = "ja-JP", openArchive = { ByteArrayInputStream(byteArrayOf(1)) }) { _, _ ->
                fail("Invalid bundled archive reached Activity startup")
            }
            fail("Invalid bundled archive was accepted")
        } catch (failure: IllegalArgumentException) {
            assertEquals("Bundled ghost hash mismatch", failure.message)
        }
        assertEquals(previousLocale, Locale.getDefault())
        assertEquals(previousLocaleList, LocaleList.getDefault())
        assertEquals(previousLocales, app.resources.configuration.locales)
        assertSame(priorRuntime, runtimeField.get(app))
        assertEquals(priorRoots, ownedCacheRoots())
        // A leaked callback would override the next ordinary Activity to ja-JP.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals(previousLocales, it.resources.configuration.locales) }
        }
    }

    private fun repositoryContent(app: NanidroidApplication): String {
        val root = File(app.cacheDir, "release-dictionary-${System.nanoTime()}").apply { mkdirs() }
        return try {
            val repository = BundledGhostRepository({ app.assets.open("nanidroid.zip") }, root)
            repository.load(repository.ensureInstalled(), "en").content
        } finally { root.deleteRecursively() }
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
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                if (pixel.red > 0.95f && pixel.blue > 0.95f && pixel.green < 0.05f) count++
            }
        }
        return count
    }

    private fun withAssistant(
        debugEnabled: Boolean = true, localeTag: String = "en-US", foreignName: String = "Other",
        openArchive: (NanidroidApplication) -> InputStream = { it.assets.open("nanidroid.zip") },
        block: (ActivityScenario<MainActivity>, GhostRuntime) -> Unit
    ) {
        val app = ApplicationProvider.getApplicationContext<NanidroidApplication>()
        val previousLocale = Locale.getDefault()
        val previousLocaleList = LocaleList.getDefault()
        val previousConfiguration = Configuration(app.resources.configuration)
        val locale = Locale.forLanguageTag(localeTag)
        val root = File(app.cacheDir, "assistant-ui-${System.nanoTime()}")
        val runtimeField = NanidroidApplication::class.java.getDeclaredField("runtime\$delegate")
            .apply { isAccessible = true }
        val priorRuntime = runtimeField.get(app)
        var rootCreated = false
        var runtimeReplaced = false
        var fixtureScope: CoroutineScope? = null
        val localizedActivityResources = mutableSetOf<android.content.res.Resources>()
        // Give each newly attached Activity the startup locale before MainActivity composes.
        // The emulator's global locale and unrelated imported tables remain unchanged.
        val localeCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                val resources = activity.resources
                localizedActivityResources += resources
                @Suppress("DEPRECATION")
                resources.updateConfiguration(
                    Configuration(resources.configuration).apply {
                        setLocales(LocaleList(locale))
                    },
                    resources.displayMetrics
                )
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        try {
            Locale.setDefault(locale)
            LocaleList.setDefault(LocaleList(locale))
            app.registerActivityLifecycleCallbacks(localeCallbacks)
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(
                Configuration(previousConfiguration).apply {
                    setLocales(LocaleList(locale))
                },
                app.resources.displayMetrics
            )
            check(root.mkdir()) { "Cannot create owned assistant fixture directory" }
            rootCreated = true
            val repository = BundledGhostRepository({ openArchive(app) }, root)
            val installed = repository.ensureInstalled()
            File(installed, "ghost/master/en/content.txt").appendText(
                "\nOnUiBatch,\\![open,ghostexplorer]\\![open,configurationdialog,about]\\e\n" +
                    "OnUiRepeat,\\![open,configurationdialog,about]\\![open,configurationdialog,about]\\e\n" +
                    "OnUiInput,\\![open,configurationdialog,about]\\![open,inputbox,answer]\\e\n"
            )
            val base = repository.load(installed, locale)
            val master = File(root, "other/ghost/master").apply { mkdirs() }
            File(master, "descript.txt").writeText(
                "name,$foreignName\nsakura.name,Other guide\nkero.name,Other companion\nshiori,Nanidroid"
            )
            File(master, "en").mkdirs()
            File(master, "en/content.txt").writeText(
                "OnFirstBoot,\\_q\\0Other ghost\\e\nOnBoot,\\_q\\0Other ghost\\e\n" +
                    "OnUiOrdinary,\\![open,ghostexplorer]\\![open,configurationdialog,about]\\![open,developer]\\e\n"
            )
            val shell = File(root, "other/shell/master").apply { mkdirs() }
            base.surfaces.forEach { (id, image) -> image.copyTo(File(shell, "surface$id.png")) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { fixtureScope = it }
            val runtime = GhostRuntime(
                { base }, { BuiltInShiori(it.content) },
                object : BootStateStore {
                    override suspend fun recordActivation(directoryId: String) = false
                    override suspend fun consumeOnboarding() = false
                },
                scope, installedGhosts = InstalledGhostRepository(root),
                bundledEngineFactory = { BuiltInShiori.bundled(it.content, debugEnabled) },
                // Advance the real script player promptly; assets, routes and dialogue tokens remain real.
                elapsedRealtime = { android.os.SystemClock.elapsedRealtime() * 20 }
            )
            runtimeField.set(app, lazy { runtime })
            runtimeReplaced = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForStage()
                compose.waitUntil(10_000) { ready(runtime).frame.ended }
                block(scenario, runtime)
            }
        } finally {
            try {
                fixtureScope?.cancel()
                if (runtimeReplaced) runtimeField.set(app, priorRuntime)
                if (rootCreated) root.deleteRecursively()
            } finally {
                app.unregisterActivityLifecycleCallbacks(localeCallbacks)
                // Activity Resources can be reused on the next launch after this Activity closes.
                localizedActivityResources.forEach { resources ->
                    @Suppress("DEPRECATION")
                    resources.updateConfiguration(
                        Configuration(resources.configuration).apply {
                            setLocales(previousConfiguration.locales)
                        },
                        resources.displayMetrics
                    )
                }
                LocaleList.setDefault(previousLocaleList)
                Locale.setDefault(previousLocale)
                @Suppress("DEPRECATION")
                app.resources.updateConfiguration(previousConfiguration, app.resources.displayMetrics)
            }
        }
    }
}
