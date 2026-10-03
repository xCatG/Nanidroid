package com.cattailsw.nanidroid.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.ghost.ShellCatalog
import com.cattailsw.nanidroid.ghost.ComposedSurface
import com.cattailsw.nanidroid.ghost.SurfaceDefinitions
import com.cattailsw.nanidroid.ghost.SurfaceImageLoader
import com.cattailsw.nanidroid.ghost.SurfaceLayer
import com.cattailsw.nanidroid.runtime.PlaybackFrame
import com.cattailsw.nanidroid.runtime.SpeakerFrame
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.runtime.SurfaceVisual
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class AnimatedSurfaceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullyOffCanvasLayerCannotHideAuthoredBase() {
        val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).asImageBitmap()
        val surfaces = mapOf(0 to ComposedSurface(image, -5, -2),
            1001 to ComposedSurface(image, 0, 0))
        val visual = SurfaceVisual(0, listOf(SurfaceLayer(1001, "overlay", 9000, 0)), 0, 0)
        val rendered = AnimatedSurface.resolve(0, visual, surfaces, 0)
        assertNotNull(rendered)
        assertEquals(-5, rendered!!.originX)
        assertEquals(1, rendered.parts.size)
    }

    @Test fun protrudingOverlayChangesTheAuthoredTransform() {
        val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).asImageBitmap()
        val images = mapOf(0 to ComposedSurface(image, 0, 0),
            1001 to ComposedSurface(image, 0, 0))
        val before = AnimatedSurface.resolve(0, null, images, 0)!!
        val after = AnimatedSurface.resolve(0,
            SurfaceVisual(0, listOf(SurfaceLayer(1001, "overlay", -10, 0)), 0, 0), images, 0)!!
        assertEquals(0, before.originX)
        assertEquals(20, before.width)
        assertEquals(-10, after.originX)
        assertEquals(30, after.width)
        val oldPoint = StageGeometry.fittedRectangle(before.width, before.height, 20, 20)
            .toAuthored(5f, 5f, before.originX, before.originY)
        val newPoint = StageGeometry.fittedRectangle(after.width, after.height, 30, 20)
            .toAuthored(5f, 5f, after.originX, after.originY)
        assertEquals(5, oldPoint?.x)
        assertEquals(-5, newPoint?.x)
    }

    @Test fun moveKeepsUnshiftedAuthoredCoordinatesForPlacementAndTouch() {
        val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).asImageBitmap()
        val rendered = AnimatedSurface.resolve(0, SurfaceVisual(0, emptyList(), 4, -3),
            mapOf(0 to ComposedSurface(image, -5, -2)), 0)!!
        assertEquals(-5, rendered.originX)
        assertEquals(-2, rendered.originY)
        assertEquals(4, rendered.offsetX)
        assertEquals(-3, rendered.offsetY)
    }

    @Test fun missingAnimationBaseKeepsLogicalBaseAndCollisions() {
        val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).asImageBitmap()
        val rendered = AnimatedSurface.resolve(5, SurfaceVisual(1001, emptyList(), 0, 0),
            mapOf(0 to ComposedSurface(image, 0, 0), 5 to ComposedSurface(image, -5, -2)), 0)!!
        assertEquals(-5, rendered.originX)
        assertEquals(5, rendered.collisionSurfaceId)
    }

    @Test fun authoredElementAndDueStaticOverlayBothRender() {
        val shell = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "animated-combined-${System.nanoTime()}").apply { mkdirs() }
        val files = listOf(0, 10, 1001).associateWith { id ->
            File(shell, "surface$id.png").apply { writeBytes(byteArrayOf(1)) }
        }
        File(shell, "badge.png").writeBytes(byteArrayOf(1))
        val catalog = ShellCatalog(files, SurfaceDefinitions.parse("surface0\n{\nelement0,base,badge.png,0,0\n}"), shell)
        val visual = SurfaceVisual(0, listOf(SurfaceLayer(1001, "overlay", 0, 0)), 0, 0)
        val state = StageState.Ready(PlaybackFrame(
            SpeakerFrame(0, true, "", false, visual), SpeakerFrame(10, true, "", false), false),
            "Ghost", "Sakura", "Kero", files, shell = catalog)
        compose.setContent { GhostStage(state, SurfaceImageLoader { file ->
            val color = when (file.name) {
                "badge.png" -> Color.GREEN
                "surface1001.png" -> Color.BLUE
                else -> Color.RED
            }
            Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()
        }) { _, _, _ -> } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("sakura", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val captured = compose.onNodeWithTag("sakura", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        assertEquals(Color.BLUE, captured.getPixel(captured.width / 2, captured.height / 2))
    }

    @Test fun elementOnlyBaseAndDueOverlayRemainVisibleTogether() {
        val shell = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "element-only-animated-${System.nanoTime()}").apply { mkdirs() }
        val files = listOf(10, 1001).associateWith { id ->
            File(shell, "surface$id.png").apply { writeBytes(byteArrayOf(1)) }
        }
        File(shell, "body.png").writeBytes(byteArrayOf(1))
        val catalog = ShellCatalog(files, SurfaceDefinitions.parse("surface0\n{\nelement1,base,body.png,0,0\n}"), shell)
        val due = SurfaceVisual(0, listOf(SurfaceLayer(1001, "overlay", 10, 0)), 0, 0)
        val state = StageState.Ready(PlaybackFrame(
            SpeakerFrame(0, true, "", false, due), SpeakerFrame(10, true, "", false), false),
            "Ghost", "Sakura", "Kero", files, shell = catalog)
        compose.setContent { GhostStage(state, SurfaceImageLoader { file ->
            val color = if (file.name == "surface1001.png") Color.BLUE else Color.GREEN
            Bitmap.createBitmap(if (file.name == "surface1001.png") 10 else 20, 20,
                Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()
        }) { _, _, _ -> } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("sakura", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val bitmap = compose.onNodeWithTag("sakura", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        assertEquals(Color.GREEN, bitmap.getPixel(bitmap.width / 4, bitmap.height / 2))
        assertEquals(Color.BLUE, bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2))
    }

    @Test fun translucentOverlayfastMatchesExistingStaticBlend() = compareStaticBlend("overlayfast")

    @Test fun translucentInterpolateMatchesExistingStaticBlend() = compareStaticBlend("interpolate")

    private fun compareStaticBlend(method: String) {
        val shell = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "animated-blend-$method-${System.nanoTime()}").apply { mkdirs() }
        val files = listOf(0, 10, 1001).associateWith { id ->
            File(shell, "surface$id.png").apply { writeBytes(byteArrayOf(1)) }
        }
        val loader = SurfaceImageLoader { file ->
            Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (file.name == "surface1001.png") Color.argb(128, 0, 0, 255)
                    else Color.argb(128, 255, 0, 0))
            }.asImageBitmap()
        }
        val base = Color.argb(128, 255, 0, 0)
        val layer = Color.argb(128, 0, 0, 255)
        val expected = if (method == "interpolate") sourceOver(layer, base) else sourceOver(base, layer)
        val visual = SurfaceVisual(0, listOf(SurfaceLayer(1001, method, 0, 0)), 0, 0)
        val state = StageState.Ready(PlaybackFrame(
            SpeakerFrame(0, true, "", false, visual), SpeakerFrame(10, false, "", false), false),
            "Ghost", "Sakura", "Kero", files)
        compose.setContent { GhostStage(state, loader) { _, _, _ -> } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("sakura", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val captured = compose.onNodeWithTag("sakura", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val actual = captured.getPixel(captured.width / 2, captured.height / 2)
        fun onWhite(channel: Int) = (channel * Color.alpha(expected) +
            255 * (255 - Color.alpha(expected))) / 255
        val expectedOnWhite = Color.rgb(onWhite(Color.red(expected)),
            onWhite(Color.green(expected)), onWhite(Color.blue(expected)))
        for (shift in listOf(16, 8, 0)) {
            val expectedChannel = (expectedOnWhite ushr shift) and 255
            val actualChannel = (actual ushr shift) and 255
            org.junit.Assert.assertTrue("$method channel $shift expected $expectedChannel actual $actualChannel",
                kotlin.math.abs(expectedChannel - actualChannel) <= 2)
        }
    }

    /** Independent Porter-Duff source-over oracle for 8-bit ARGB. */
    private fun sourceOver(below: Int, above: Int): Int {
        val sa = above ushr 24
        val da = below ushr 24
        val oa = sa + da * (255 - sa) / 255
        fun channel(shift: Int) = (((above ushr shift) and 255) * sa +
            ((below ushr shift) and 255) * da * (255 - sa) / 255) / oa
        return (oa shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
