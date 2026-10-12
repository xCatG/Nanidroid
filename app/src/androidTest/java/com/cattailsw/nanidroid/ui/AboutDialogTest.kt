package com.cattailsw.nanidroid.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import android.os.SystemClock
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import com.cattailsw.nanidroid.ghost.SurfaceImageLoader
import com.cattailsw.nanidroid.MainActivity
import com.cattailsw.nanidroid.NanidroidApplication
import com.cattailsw.nanidroid.engine.NativeAvailability
import com.cattailsw.nanidroid.engine.NativeShioriHost
import com.cattailsw.nanidroid.runtime.PlaybackFrame
import com.cattailsw.nanidroid.runtime.SpeakerFrame
import com.cattailsw.nanidroid.runtime.StageState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class AboutDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun suppliedVersionAndEveryBundledNoticeAreVisibleAndLastIsReachableAtLargeFontsInLandscape() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val notices = NoticeCatalog.load(assets)
        assertEquals(NoticeCatalog.entries.size, notices.size)
        assertTrue(notices.all { it.text.isNotBlank() })
        assertTrue(notices.single { it.title == "Bundled Nanidroid ghost and artwork" }.text
            .contains("https://creativecommons.org/licenses/by/3.0/"))
        assertTrue(notices.any { it.title == "Kotlin standard library ThreeTenBP terms" &&
            it.text.contains("Stephen Colebourne") })
        assertTrue(notices.any { it.title == "Kotlin standard library Boost terms" &&
            it.text.contains("Boost Software License") })
        val originalActivity = compose.activity
        val originalOrientation = originalActivity.requestedOrientation
        val originalConfiguration = originalActivity.resources.configuration.orientation
        try {
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            awaitOrientation(Configuration.ORIENTATION_LANDSCAPE,
                replaced = originalActivity.takeIf { originalConfiguration != Configuration.ORIENTATION_LANDSCAPE })
            println("ABOUT_CONTENT_INSTALL elapsedRealtime=${SystemClock.elapsedRealtime()}")
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    AboutDialog("test-version-42", notices) {}
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("about-version").fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .singleOrNull()?.boundsInRoot?.let { it.width > 0f && it.height > 0f } == true
            }
            compose.onNodeWithTag("about-version").assertTextEquals("Version test-version-42")
            for (index in notices.indices) {
                compose.onNodeWithTag("about-notices").performScrollToIndex(index)
                compose.onNodeWithTag("notice-title-$index").assertIsDisplayed()
                compose.onNodeWithTag("notice-text-$index").assertTextEquals(notices[index].text)
            }
            compose.onNodeWithTag("about-notices").performScrollToIndex(notices.size)
            compose.onNodeWithTag("notice-end").assertIsDisplayed()
            compose.onNodeWithTag("about-close").assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
            awaitOrientation(originalConfiguration)
        }
    }

    private fun awaitOrientation(orientation: Int, replaced: ComponentActivity? = null) {
        compose.waitUntil(10_000) {
            var ready = false
            compose.activityRule.scenario.onActivity { activity ->
                val decor = activity.window.decorView
                ready = activity !== replaced &&
                    activity.resources.configuration.orientation == orientation &&
                    activity.lifecycle.currentState == Lifecycle.State.RESUMED && activity.hasWindowFocus() &&
                    decor.width > 0 && decor.height > 0 &&
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) decor.width > decor.height
                    else decor.height > decor.width
            }
            ready
        }
        compose.activityRule.scenario.onActivity { activity ->
            println("ABOUT_ORIENTATION elapsedRealtime=${SystemClock.elapsedRealtime()} " +
                "activity=${System.identityHashCode(activity)} orientation=$orientation " +
                "lifecycle=${activity.lifecycle.currentState} focus=${activity.hasWindowFocus()} " +
                "window=${activity.window.decorView.width}x${activity.window.decorView.height} " +
                "replaced=${replaced != null}")
        }
    }

    @Test fun aboutFromControlsDismissesToSameReadyStageWithoutClose() {
        val image = Bitmap.createBitmap(160, 200, Bitmap.Config.ARGB_8888).asImageBitmap()
        val loader = SurfaceImageLoader { image }
        val ready = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "Still here", true),
                SpeakerFrame(10, true, "", false), false),
            "Test", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")),
        )
        var closes = 0
        compose.setContent {
            BackHandler { closes++ }
            GhostStage(ready, loader, {}, {}, {}, onClose = { closes++ },
                versionName = "live-version", notices = listOf(Notice("Live notice", "offline text")))
        }
        compose.onNodeWithTag("about-action").performClick()
        compose.onNodeWithTag("about-version").assertTextEquals("Version live-version")
        compose.onNodeWithTag("about-close").performClick()
        compose.onNodeWithTag("ghosts-action").assertIsDisplayed()
        assertEquals(0, closes)
        compose.onNodeWithTag("about-action").performClick()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("about-dialog").assertDoesNotExist()
        compose.onNodeWithTag("ghosts-action").assertIsDisplayed()
        assertEquals(0, closes)
    }
}

/** Host-staged original Earthquake fixture; the dialogs must retain its pending native choice. */
class NativeDialogSessionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun dialogsDismissOnRotationWithoutReplacingNativeChoiceSession() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Run host-staged verified Earthquake dialog probe; required arguments: fixtureId, fixtureSha256",
            listOf("fixtureId", "fixtureSha256").any { args.containsKey(it) })
        require(listOf("fixtureId", "fixtureSha256").all { !args.getString(it).isNullOrBlank() }) {
            "Provide all nonblank host arguments: fixtureId, fixtureSha256"
        }
        require(args.getString("fixtureId") == "earthquake_duo") { "fixtureId must be earthquake_duo" }
        require(args.getString("fixtureSha256")?.equals(
            "06db71e7e8293b4af0b5127dd73402d4ed90fecc5fdcebf4f0d34337ccb66538", true) == true) { "fixtureSha256 must match the original Earthquake archive" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NanidroidApplication
        check(File(app.filesDir, "ghost/earthquake_duo/ghost/master/descript.txt").isFile)
        check(File(app.filesDir, "ghost/earthquake_duo/shell/master/surfaces.txt").isFile)
        check(app.getSharedPreferences("last_ghost", 0).edit()
            .putString("last_ghost", "earthquake_duo").commit())
        val runtime = app.runtime
        val host = NativeShioriHost.process
        host.trackEventsForTest(setOf("OnClose", "OnAiTalk")).use {
        check(host.availability.value == NativeAvailability.Available)
        val before = host.observationForTest()
        var scenario: ActivityScenario<MainActivity>? = null
        var failure: Throwable? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val activity = requireNotNull(scenario)
            compose.waitUntil(45_000) { (runtime.state.value as? StageState.Ready)
                ?.ghostName == "Earthquake Duo!" && runtime.activeNativeLeaseForTest() != null }
            val lease = requireNotNull(runtime.activeNativeLeaseForTest())
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("sakura"))
                .fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("sakura").performTouchInput { doubleClick(center) }
            compose.waitUntil(25_000) { (runtime.state.value as? StageState.Ready)
                ?.choices?.values?.flatten()?.any { it.label == "Say something" } == true }
            val pending = (runtime.state.value as StageState.Ready).dialogueToken
            val boot = host.observationForTest()
            assertEquals(before.loads + 1, boot.loads)

            for (dialog in listOf("ghosts", "about")) {
                compose.onNodeWithTag("$dialog-action").performClick()
                if (dialog == "about") compose.onNodeWithTag("about-dialog").assertIsDisplayed()
                else compose.onNodeWithContentDescription("Close Ghosts").assertIsDisplayed()
                activity.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(20_000) { activityActivityOrientation(activity) == Configuration.ORIENTATION_LANDSCAPE }
                // The approved policy permits dismissal on recreation, then reopening.
                if (dialog == "about") compose.onNodeWithTag("about-dialog").assertDoesNotExist()
                else compose.onNodeWithContentDescription("Close Ghosts").assertDoesNotExist()
                assertTrue("Dialog rotation replaced native lease", runtime.activeNativeLeaseForTest() === lease)
                assertEquals(pending, (runtime.state.value as StageState.Ready).dialogueToken)
                assertEquals(before.loads + 1, host.observationForTest().loads)
                assertEquals(boot.eventCount("OnClose"),
                    host.observationForTest().eventCount("OnClose"))
                compose.onNodeWithTag("$dialog-action").performClick()
                if (dialog == "about") compose.onNodeWithTag("about-close").performClick()
                else compose.onNodeWithContentDescription("Close Ghosts").performClick()
                activity.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                compose.waitUntil(20_000) { activityActivityOrientation(activity) == Configuration.ORIENTATION_PORTRAIT }
            }

            val choice = (runtime.state.value as StageState.Ready).choices.values.flatten()
                .single { it.label == "Say something" }
            val choiceTag = "choice-${choice.token.itemKey}"
            compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag(choiceTag))
                .fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(choiceTag).performScrollTo().performClick()
            compose.waitUntil(25_000) { host.observationForTest().eventCount("OnAiTalk") == boot.eventCount("OnAiTalk") + 1 }
            assertEquals(before.loads + 1, host.observationForTest().loads)
            assertTrue(runtime.activeNativeLeaseForTest() === lease)
            println("M5_TASK4_DIALOGS token=$pending loads=${host.observationForTest().loads} " +
                "OnClose=${host.observationForTest().eventCount("OnClose")}")
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            var cleanup: Throwable? = null
            try {
                runtime.close()
                compose.waitUntil(45_000) { runtime.state.value is StageState.Finished }
                compose.waitUntil(45_000) { host.availability.value == NativeAvailability.Available &&
                    runtime.activeNativeLeaseForTest() == null }
            } catch (caught: Throwable) { cleanup = caught }
            try { scenario?.close() } catch (caught: Throwable) {
                if (cleanup == null) cleanup = caught else cleanup.addSuppressed(caught)
            }
            if (cleanup != null && failure != null) failure.addSuppressed(cleanup)
            else if (cleanup != null) throw cleanup
        }
        }
    }

    private fun activityActivityOrientation(activity: ActivityScenario<MainActivity>): Int {
        var orientation = Configuration.ORIENTATION_UNDEFINED
        activity.onActivity { orientation = it.resources.configuration.orientation }
        return orientation
    }
}
