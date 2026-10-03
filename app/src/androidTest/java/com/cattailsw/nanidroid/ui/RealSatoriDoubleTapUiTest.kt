package com.cattailsw.nanidroid.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.NanidroidApplication
import com.cattailsw.nanidroid.data.PreferencesLastGhostStore
import com.cattailsw.nanidroid.ghost.CachedSurfaceImageLoader
import com.cattailsw.nanidroid.ghost.SurfaceCollisionHitTest
import com.cattailsw.nanidroid.runtime.StageState
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the user-provided 2elf data staged only in app-private files. */
@RunWith(AndroidJUnit4::class)
class RealSatoriDoubleTapUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun doubleTapDisplaysNativeResponse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val master = File(context.filesDir, "ghost/satori/ghost/master/descript.txt")
        assumeTrue("2elf fixture unavailable: $master", master.isFile)
        PreferencesLastGhostStore(context.getSharedPreferences("last_ghost", 0)).write("satori")
        val runtime = (context.applicationContext as NanidroidApplication).runtime
        val loader = CachedSurfaceImageLoader()
        val dispatched = AtomicReference<String?>(null)
        compose.setContent {
            val state by runtime.state.collectAsState()
            GhostStage(state, loader,
                onSelectGhost = runtime::selectGhost,
                onConfirmSwitch = runtime::confirmSwitch,
                onDismissSwitch = runtime::dismissSwitch,
                onCharacterDoubleClick = runtime::doubleClick,
                onCharacterClick = runtime::click,
                onCharacterDoubleClickResolved = { speaker, x, y, surfaceId, origin ->
                    val ready = runtime.state.value as? StageState.Ready
                    val collision = SurfaceCollisionHitTest.find(
                        ready?.shell?.definitions?.definition(surfaceId)?.collisions.orEmpty(), x, y)
                    dispatched.set("$x,$y,0,$speaker,$collision,0,touch; surface=$surfaceId")
                    runtime.doubleClick(speaker, x, y, surfaceId, origin)
                })
        }
        compose.runOnIdle {
            runtime.setResumed(true)
            CoroutineScope(Dispatchers.Main.immediate).launch { runtime.start("ja") }
        }
        compose.waitUntil(15_000) {
            (runtime.state.value as? StageState.Ready)?.surfaces?.values
                ?.any { it.path.contains("satori") } == true
        }
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("sakura")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("sakura").performTouchInput { doubleClick(center) }
        compose.waitUntil(7_000) {
            val text = (runtime.state.value as? StageState.Ready)?.frame?.sakura?.text.orEmpty()
            text.contains("薬草よ") || text.contains("いっぱい入っているけど")
        }
        val ready = runtime.state.value as StageState.Ready
        assertTrue("Expected the real 2elf kusa collision: ${dispatched.get()}",
            dispatched.get()?.contains(",kusa,0,touch") == true)
        assertTrue(ready.frame.sakura.balloonVisible)
        assertTrue(ready.activationError == null)
        println("REAL_UI_SATORI doubleTap refs=${dispatched.get()} text=${ready.frame.sakura.text.take(120)}")
        runtime.setResumed(false)
    }
}
