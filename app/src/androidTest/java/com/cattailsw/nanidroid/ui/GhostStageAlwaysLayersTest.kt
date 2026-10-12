package com.cattailsw.nanidroid.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cattailsw.nanidroid.ghost.SurfaceImageLoader
import com.cattailsw.nanidroid.ghost.SurfaceLayer
import com.cattailsw.nanidroid.runtime.PlaybackFrame
import com.cattailsw.nanidroid.runtime.SpeakerFrame
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.runtime.SurfaceVisual
import java.io.File
import java.util.Collections
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import com.cattailsw.nanidroid.testing.OwnedFixtureDirectoryRule
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GhostStageAlwaysLayersTest {
    private val fixtures = OwnedFixtureDirectoryRule()
    val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixtures).around(compose)
    private val dueKero = SurfaceVisual(10, listOf(SurfaceLayer(1001, "overlay", 0, 0)), 0, 0)

    @Test fun stageLoadsAlwaysLayerOnceWhileDialogueChanges() {
        val shell = fixtures.directory("stage-layers-${System.nanoTime()}")
        File(shell, "surfaces.txt").writeText("""
            surface10
            {
            0interval,always
            0pattern0,1001,0,overlay,0,0
            }
        """.trimIndent())
        val files = listOf(0, 10, 1001).associateWith { id ->
            File(shell, "surface$id.png").apply { writeBytes(byteArrayOf(1)) }
        }
        val loaded = Collections.synchronizedList(mutableListOf<String>())
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }.asImageBitmap()
        val loader = SurfaceImageLoader { file -> loaded += file.name; image }
        val initial = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "a", true), SpeakerFrame(10, true, "b", true, dueKero), false),
            "Ghost", "Mantle", "Ridge", files)
        val stage = androidx.compose.runtime.mutableStateOf(initial)
        compose.setContent { GhostStage(stage.value, loader) { _, _, _ -> } }
        compose.waitUntil(5_000) { loaded.count { it == "surface1001.png" } == 1 }
        compose.runOnUiThread {
            stage.value = initial.copy(frame = initial.frame.copy(
                kero = initial.frame.kero.copy(text = "more dialogue")))
        }
        compose.waitForIdle()
        assertTrue(loaded.count { it == "surface1001.png" } == 1)
    }

    @Test fun stageIgnoresLayerFileOutsideSurfaceIndex() {
        val shell = fixtures.directory("stage-index-${System.nanoTime()}")
        File(shell, "surfaces.txt").writeText("""
            surface0
            {
            0interval,always
            0pattern0,1001,0,overlay,0,0
            }
        """.trimIndent())
        File(shell, "surface1001.png").writeBytes(byteArrayOf(1))
        val files = listOf(0, 10).associateWith { id ->
            File(shell, "surface$id.png").apply { writeBytes(byteArrayOf(1)) }
        }
        val loaded = Collections.synchronizedList(mutableListOf<String>())
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }.asImageBitmap()
        val stage = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "a", true), SpeakerFrame(10, true, "b", true, dueKero), false),
            "Ghost", "Mantle", "Ridge", files)
        compose.setContent { GhostStage(stage, SurfaceImageLoader { file -> loaded += file.name; image }) { _, _, _ -> } }
        compose.waitUntil(5_000) { loaded.size >= 2 }
        assertTrue("Unindexed layer was loaded: $loaded", "surface1001.png" !in loaded)
    }

    @Test fun stageReadsDefinitionsWhateverTheirNameCase() {
        val shell = fixtures.directory("stage-case-${System.nanoTime()}")
        File(shell, "Surfaces.txt").writeText("surface0\n{\n0interval,always\n0pattern0,1001,0,overlay,0,0\n}")
        val files = listOf(0, 10, 1001).associateWith { id ->
            File(shell, "surface$id.png").apply { writeBytes(byteArrayOf(1)) }
        }
        val loaded = Collections.synchronizedList(mutableListOf<String>())
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }.asImageBitmap()
        val stage = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "a", true), SpeakerFrame(10, true, "b", true, dueKero), false),
            "Ghost", "Mantle", "Ridge", files)
        compose.setContent { GhostStage(stage, SurfaceImageLoader { file -> loaded += file.name; image }) { _, _, _ -> } }
        compose.waitUntil(5_000) { loaded.count { it == "surface1001.png" } == 1 }
    }

    @Test fun unreadableDefinitionsStillShowBaseImages() {
        val shell = fixtures.directory("stage-unreadable-${System.nanoTime()}")
        val definitions = File(shell, "surfaces.txt").apply {
            writeText("surface0\n{\n0interval,always\n0pattern0,1001,0,overlay,0,0\n}")
            setReadable(false, false)
        }
        assumeFalse("Fixture stayed readable", definitions.canRead())
        val files = listOf(0, 10).associateWith { id ->
            File(shell, "surface$id.png").apply { writeBytes(byteArrayOf(1)) }
        }
        val loaded = Collections.synchronizedList(mutableListOf<String>())
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }.asImageBitmap()
        val stage = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "a", true), SpeakerFrame(10, true, "b", true), false),
            "Ghost", "Mantle", "Ridge", files)
        try {
            compose.setContent { GhostStage(stage, SurfaceImageLoader { file -> loaded += file.name; image }) { _, _, _ -> } }
            compose.waitUntil(5_000) { loaded.size == 2 }
            compose.onNodeWithTag("sakura").assertExists()
        } finally {
            definitions.setReadable(true, false)
        }
    }
}
