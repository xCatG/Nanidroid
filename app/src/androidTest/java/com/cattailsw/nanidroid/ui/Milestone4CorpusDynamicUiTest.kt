package com.cattailsw.nanidroid.ui

import android.graphics.Bitmap
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.ghost.CachedSurfaceImageLoader
import com.cattailsw.nanidroid.ghost.loadShellCatalog
import com.cattailsw.nanidroid.runtime.PlaybackFrame
import com.cattailsw.nanidroid.runtime.SpeakerFrame
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.runtime.SurfaceAnimator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only StageState driver over a disposable copy of unchanged Earthquake shell files. */
@RunWith(AndroidJUnit4::class)
class Milestone4CorpusDynamicUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun authoredMoveChangesRenderedEarthquakeScene() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = InstrumentationRegistry.getArguments().getString("fixtureId") ?: "task7-earthquake"
        val shell = File(context.filesDir, "ghost/$id/shell/master")
        assumeTrue("Earthquake disposable corpus copy not staged", File(shell, "surfaces.txt").isFile)
        val numbered = shell.listFiles().orEmpty().mapNotNull { file ->
            Regex("surface(\\d+)\\.png", RegexOption.IGNORE_CASE).matchEntire(file.name)
                ?.groupValues?.get(1)?.toIntOrNull()?.let { it to file }
        }.toMap()
        assertTrue("Earthquake default surfaces missing", 0 in numbered && 10 in numbered)
        val catalog = loadShellCatalog(shell, numbered)
        val animator = SurfaceAnimator(catalog.definitions) { 0L }
        animator.setSurface(0, 1)
        val initial = animator.advanceBy(0, emptySet())!!.getValue(0)
        assertEquals(0, initial.offsetX)
        fun frame(visual: com.cattailsw.nanidroid.runtime.SurfaceVisual) = StageState.Ready(
            PlaybackFrame(SpeakerFrame(1, true, "", false, visual),
                SpeakerFrame(10, true, "", false), false),
            "Earthquake Rescue Duo", "Mantle", "Ridge", numbered, shell = catalog,
        )
        val state = mutableStateOf(frame(initial))
        compose.setContent { GhostStage(state.value, CachedSurfaceImageLoader()) { _, _, _ -> } }
        compose.waitUntil(30_000) {
            try { compose.onNodeWithTag("sakura").fetchSemanticsNode(); true }
            catch (_: AssertionError) { false }
        }
        compose.waitForIdle()
        val beforeLeft = compose.onNodeWithTag("sakura").fetchSemanticsNode().boundsInRoot.left
        val before = compose.onRoot().captureToImage().asAndroidBitmap()
        save(context, before, "task7-earthquake-before.png")
        println("M4_DYNAMIC before offset=${initial.offsetX},${initial.offsetY} " +
            "sakuraLeft=$beforeLeft root=${before.width}x${before.height}")
        Thread.sleep(1_500)

        val moved = animator.advanceBy(100, emptySet())!!.getValue(0)
        assertEquals(10, moved.offsetX)
        compose.runOnIdle { state.value = frame(moved) }
        compose.waitForIdle()
        val afterLeft = compose.onNodeWithTag("sakura").fetchSemanticsNode().boundsInRoot.left
        val after = compose.onRoot().captureToImage().asAndroidBitmap()
        save(context, after, "task7-earthquake-after.png")
        val changed = changedPixels(before, after)
        println("M4_DYNAMIC after offset=${moved.offsetX},${moved.offsetY} " +
            "sakuraLeft=$afterLeft changedRootPixels=$changed")
        assertTrue("authored +10 move did not shift rendered Canvas right", afterLeft - beforeLeft > 2f)
        assertTrue("rendered Compose root pixels did not change with move", changed > 100)
        Thread.sleep(1_500)
    }

    @Test fun authoredStaticPartsChangeBothRenderedEarthquakeCharacters() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = InstrumentationRegistry.getArguments().getString("fixtureId") ?: "task7-earthquake"
        val shell = File(context.filesDir, "ghost/$id/shell/master")
        assumeTrue("Earthquake disposable corpus copy not staged", File(shell, "surfaces.txt").isFile)
        val numbered = shell.listFiles().orEmpty().mapNotNull { file ->
            Regex("surface(\\d+)\\.png", RegexOption.IGNORE_CASE).matchEntire(file.name)
                ?.groupValues?.get(1)?.toIntOrNull()?.let { it to file }
        }.toMap()
        assertTrue("Earthquake default surfaces missing", 0 in numbered && 10 in numbered)
        val catalog = loadShellCatalog(shell, numbered)
        val animator = SurfaceAnimator(catalog.definitions) { 0L }
        animator.setSurface(0, 0)
        animator.setSurface(1, 10)
        val static = animator.advanceBy(50, emptySet())!!
        assertEquals(setOf(1501, 1601), static.getValue(0).layers.map { it.surfaceId }.toSet())
        assertEquals(setOf(1101, 1001, 1403, 1201),
            static.getValue(1).layers.map { it.surfaceId }.toSet())

        fun frame(sakura: com.cattailsw.nanidroid.runtime.SurfaceVisual?,
            kero: com.cattailsw.nanidroid.runtime.SurfaceVisual?) = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "", false, sakura),
                SpeakerFrame(10, true, "", false, kero), false),
            "Earthquake Rescue Duo", "Mantle", "Ridge", numbered, shell = catalog,
        )
        val state = mutableStateOf(frame(null, null))
        val loader = CachedSurfaceImageLoader()
        compose.setContent { GhostStage(state.value, loader) { _, _, _ -> } }
        compose.waitUntil(30_000) {
            try {
                compose.onNodeWithTag("sakura").fetchSemanticsNode()
                compose.onNodeWithTag("kero").fetchSemanticsNode()
                true
            }
            catch (_: AssertionError) { false }
        }
        compose.waitForIdle()
        val mantleBase = compose.onNodeWithTag("sakura").captureToImage().asAndroidBitmap()
        val ridgeBase = compose.onNodeWithTag("kero").captureToImage().asAndroidBitmap()
        compose.runOnIdle { state.value = frame(static.getValue(0), static.getValue(1)) }
        compose.waitUntil(30_000) {
            try {
                val mantle = compose.onNodeWithTag("sakura").captureToImage().asAndroidBitmap()
                val ridge = compose.onNodeWithTag("kero").captureToImage().asAndroidBitmap()
                changedPixels(mantleBase, mantle) > 100 && changedPixels(ridgeBase, ridge) > 100
            } catch (_: AssertionError) { false }
        }
        val mantleRendered = compose.onNodeWithTag("sakura").captureToImage().asAndroidBitmap()
        val ridgeRendered = compose.onNodeWithTag("kero").captureToImage().asAndroidBitmap()
        val mantleChanged = changedPixels(mantleBase, mantleRendered)
        val ridgeChanged = changedPixels(ridgeBase, ridgeRendered)
        println("M4_CORPUS EARTHQUAKE staticChangedPixels mantle=$mantleChanged ridge=$ridgeChanged " +
            "layers=${static.getValue(0).layers.map { it.surfaceId }}," +
            "${static.getValue(1).layers.map { it.surfaceId }}")
        assertTrue("Mantle authored parts did not change rendered pixels", mantleChanged > 100)
        assertTrue("Ridge authored parts did not change rendered pixels", ridgeChanged > 100)
    }

    private fun save(context: Context, bitmap: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NanidroidTask7")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore could not create $name")
        context.contentResolver.openOutputStream(uri)!!.use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun changedPixels(a: Bitmap, b: Bitmap): Int {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        var changed = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a.getPixel(x, y) != b.getPixel(x, y)) changed++
        }
        return changed
    }
}
