package com.cattailsw.nanidroid

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.engine.BuiltInShiori
import com.cattailsw.nanidroid.engine.ShioriEngine
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.ghost.BundledGhostRepository
import com.cattailsw.nanidroid.ghost.CachedSurfaceImageLoader
import com.cattailsw.nanidroid.runtime.GhostRuntime
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.ui.GhostStage
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Test-owned Activity composition using the production runtime and real bundled archive. */
@RunWith(AndroidJUnit4::class)
class WalkingSkeletonInstrumentationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun bundledKeroTapDispatchesOnceWithSurfaceCoordinatesAndNoReply() = withStage { scenario ->
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        awaitOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
        scenario.onActivity { probe(it).releaseLoading() }
        await(scenario) { it is StageState.Ready }
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("kero")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("kero").performTouchInput { click(center) }
        compose.waitUntil(3_000) {
            var count = 0
            scenario.onActivity { count = probe(it).eventCount("OnMouseClick") }
            count == 1
        }
        scenario.onActivity {
            val p = probe(it)
            assertEquals(1, p.eventCount("OnMouseClick"))
            assertEquals(listOf("117", "100", "0", "1", "", "0", "touch"), p.mouseClick().first.references)
            assertEquals(204, p.mouseClick().second)
            val frame = (p.runtime.state.value as StageState.Ready).frame
            assertEquals("", frame.sakura.text + frame.kero.text)
            assertTrue(!frame.sakura.balloonVisible && !frame.kero.balloonVisible)
        }
    }

    @Test fun recreationWhileLoadingStartsGhostOnlyOnce() = withStage { scenario ->
        assertTrue(state(scenario) is StageState.Loading)
        scenario.recreate()
        assertTrue(state(scenario) is StageState.Loading)
        scenario.onActivity { probe(it).releaseLoading() }
        await(scenario) { it is StageState.Ready }
        scenario.onActivity {
            val p = probe(it)
            assertEquals(1, p.eventCount("OnFirstBoot"))
            assertEquals(0, p.eventCount("OnBoot"))
            assertTrue((p.runtime.state.value as StageState.Ready).surfaces.keys.containsAll(listOf(0, 10)))
            assertTrue(it.findViewById<android.view.ViewGroup>(android.R.id.content).childCount > 0)
        }
    }

    @Test fun recreationMidDialogueKeepsPlaybackAndDoesNotBootAgain() = withStage { scenario ->
        scenario.onActivity { probe(it).releaseLoading() }
        await(scenario) { state ->
            state is StageState.Ready && (state.frame.sakura.text + state.frame.kero.text).length >= 4
        }
        var before = ""
        scenario.onActivity { before = probe(it).text() }
        scenario.recreate()
        await(scenario) { state ->
            state is StageState.Ready && (state.frame.sakura.text + state.frame.kero.text).length > before.length
        }
        scenario.onActivity {
            val p = probe(it)
            assertTrue("Dialogue restarted: $before -> ${p.text()}", p.text().startsWith(before))
            assertEquals(1, p.eventCount("OnFirstBoot"))
            assertEquals(0, p.eventCount("OnBoot"))
        }
    }

    @Test fun rotationMidDialogueKeepsPlaybackAndDoesNotBootAgain() = withStage { scenario ->
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        awaitOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
        scenario.onActivity { probe(it).releaseLoading() }
        try {
            await(scenario) { state ->
                state is StageState.Ready && (state.frame.sakura.text + state.frame.kero.text).length >= 4
            }
            var before = ""
            scenario.onActivity {
                before = probe(it).text()
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            awaitOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
            await(scenario) { state ->
                state is StageState.Ready && (state.frame.sakura.text + state.frame.kero.text).length > before.length
            }
            scenario.onActivity {
                val p = probe(it)
                assertTrue("Dialogue restarted after rotation", p.text().startsWith(before))
                assertEquals(1, p.eventCount("OnFirstBoot"))
            }
        } finally {
            // A requested rotation can recreate an Activity after the state assertion.
            // Finish this test's configuration transaction before the next tap fixture.
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            awaitOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
        }
    }

    private fun awaitOrientation(scenario: ActivityScenario<ComponentActivity>, orientation: Int) {
        compose.waitUntil(10_000) {
            var settled = false
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val dimensionsMatch = decor.width > 0 && decor.height > 0 &&
                    if (orientation == Configuration.ORIENTATION_PORTRAIT) decor.height > decor.width
                    else decor.width > decor.height
                settled = activity.resources.configuration.orientation == orientation &&
                    activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    activity.hasWindowFocus() && dimensionsMatch
            }
            settled
        }
        compose.waitForIdle()
        scenario.onActivity { activity ->
            assertEquals("Activity orientation changed after settling", orientation,
                activity.resources.configuration.orientation)
            assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            assertTrue("Settled Activity lost input focus", activity.hasWindowFocus())
            println("WALKING_STAGE_ORIENTATION elapsedRealtime=${SystemClock.elapsedRealtime()} " +
                "orientation=$orientation lifecycle=${activity.lifecycle.currentState} " +
                "window=${activity.window.decorView.width}x${activity.window.decorView.height}")
        }
    }

    private fun withStage(block: (ActivityScenario<ComponentActivity>) -> Unit) {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val callbacks = TestStageCallbacks()
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use(block)
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    private fun state(scenario: ActivityScenario<ComponentActivity>): StageState {
        lateinit var result: StageState
        scenario.onActivity { result = probe(it).runtime.state.value }
        return result
    }

    private fun await(scenario: ActivityScenario<ComponentActivity>, predicate: (StageState) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate(state(scenario))) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out awaiting stage state; last state: ${state(scenario)}")
    }

    private fun probe(activity: ComponentActivity) = ViewModelProvider(activity)[RuntimeProbe::class.java]
}

private class TestStageCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (activity !is ComponentActivity) return
        val probe = ViewModelProvider(activity)[RuntimeProbe::class.java]
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> probe.runtime.setResumed(true)
                Lifecycle.Event.ON_PAUSE -> probe.runtime.setResumed(false)
                else -> Unit
            }
        })
        activity.lifecycleScope.launch { probe.runtime.start("en") }
        activity.setContent {
            val state by probe.runtime.state.collectAsStateWithLifecycle()
            GhostStage(state, probe.images, probe.runtime::click)
        }
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

class RuntimeProbe : ViewModel() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loadGate = CompletableDeferred<Unit>()
    private val replies = mutableListOf<Pair<ShioriEvent, Int>>()
    val images = CachedSurfaceImageLoader()
    val runtime = GhostRuntime(
        loadGhost = {
            loadGate.await()
            withContext(Dispatchers.IO) {
                val repository = BundledGhostRepository(
                    openAsset = { context.assets.open("nanidroid.zip") },
                    ghostRoot = File(context.filesDir, "ghost"),
                )
                repository.load(repository.ensureInstalled(), "en")
            }
        },
        engineFactory = { ghost ->
            val engine = BuiltInShiori(ghost.content)
            object : ShioriEngine {
                override suspend fun request(event: ShioriEvent) = engine.request(event).also { replies += event to it.status }
            }
        },
        bootState = object : BootStateStore {
            override suspend fun recordActivation(directoryId: String) = true
            override suspend fun consumeOnboarding() = false
        },
        scope = viewModelScope,
    )
    fun releaseLoading() { loadGate.complete(Unit) }
    fun eventCount(id: String) = replies.count { it.first.id == id }
    fun mouseClick() = replies.single { it.first.id == "OnMouseClick" }
    fun text(): String = (runtime.state.value as? StageState.Ready)?.frame?.let {
        it.sakura.text + it.kero.text
    }.orEmpty()
}
