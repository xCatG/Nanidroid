package com.cattailsw.nanidroid.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SurfaceCompositorTest {
    @Test
    fun `color key returns copied primitive pixels without changing its input`() {
        val raw = intArrayOf(RED, BLUE, RED, GREEN)
        val image = SurfacePixelImage.of(2, 2, raw)

        val keyed = image.colorKeyed()

        assertEquals(RED, image.pixelAt(0, 0))
        assertEquals(RED, image.pixelAt(0, 1))
        assertEquals(TRANSPARENT, keyed.pixelAt(0, 0))
        assertEquals(TRANSPARENT, keyed.pixelAt(0, 1))
        assertEquals(BLUE, keyed.pixelAt(1, 0))
    }

    @Test
    fun `public pixel factory defensively copies caller owned storage`() {
        val callerPixels = intArrayOf(RED, BLUE)
        val image = SurfacePixelImage.of(2, 1, callerPixels)

        callerPixels[1] = GREEN

        assertEquals(BLUE, image.pixelAt(1, 0))
    }

    @Test
    fun `normal composition uses the first pixel as a transparent color key`() {
        val image = SurfacePixelImage.of(
            width = 2,
            height = 2,
            pixels = intArrayOf(RED, BLUE, RED, GREEN),
        )
        val output = SurfaceCompositor(assets = assets("base.png" to image)).normal(plan(layers = listOf(layer("base.png", 1, 1, 2, 2))))

        assertEquals(4, output.width)
        assertEquals(4, output.height)
        assertEquals(TRANSPARENT, output.pixelAt(1, 1))
        assertEquals(BLUE, output.pixelAt(2, 1))
        assertEquals(TRANSPARENT, output.pixelAt(1, 2))
        assertEquals(GREEN, output.pixelAt(2, 2))
    }

    @Test
    fun `overlay resolves a source surface before consulting its fallback asset`() {
        val source = plan(surfaceId = 12, layers = listOf(layer("source.png", 0, 0, 1, 1)))
        val loader = RecordingAssets(mapOf(
            "base.png" to solid(BLUE),
            "source.png" to solid(GREEN),
            "fallback.png" to solid(RED),
        ))
        val output = SurfaceCompositor(loader, SurfacePlanRegistry(listOf(source))).frame(
            plan(layers = listOf(layer("base.png", 0, 0, 2, 2))),
            SurfaceRenderFrame.Overlay("12", "fallback.png", 1, 1, 1, 1, 1),
        )

        assertEquals(GREEN, output.pixelAt(2, 1))
        assertFalse(loader.requests.contains("fallback.png"))
    }

    @Test
    fun `missing source surface falls back to the declared overlay image`() {
        val loader = RecordingAssets(mapOf("base.png" to solid(BLUE), "fallback.png" to solid(GREEN)))
        val output = SurfaceCompositor(loader).frame(
            plan(layers = listOf(layer("base.png", 0, 0, 2, 2))),
            SurfaceRenderFrame.Overlay("12", "fallback.png", 1, 1, 1, 1, 1),
        )

        assertEquals(GREEN, output.pixelAt(2, 1))
        assertTrue(loader.requests.contains("fallback.png"))
    }

    @Test
    fun `base replacement is color keyed while reset and legacy move retain normal pixels`() {
        val compositor = SurfaceCompositor(assets("base.png" to solid(BLUE), "replacement.png" to solid(RED)))
        val renderPlan = plan(layers = listOf(layer("base.png", 0, 0, 2, 2)))

        assertEquals(BLUE, compositor.frame(renderPlan, SurfaceRenderFrame.Reset(1)).pixelAt(1, 0))
        assertEquals(BLUE, compositor.frame(renderPlan, SurfaceRenderFrame.Move(8, -2, 1)).pixelAt(1, 0))
        assertEquals(TRANSPARENT, compositor.frame(renderPlan, SurfaceRenderFrame.Base("replacement.png", 2, 2, 1)).pixelAt(0, 0))
    }

    @Test
    fun `source over retains precision for alpha one full-channel overlap`() {
        val faintBlue = SurfacePixelImage.of(2, 1, intArrayOf(TRANSPARENT, argb(1, 0, 0, 255)))
        val faintRed = SurfacePixelImage.of(2, 1, intArrayOf(TRANSPARENT, argb(1, 255, 0, 0)))

        val output = SurfaceCompositor(assets("blue.png" to faintBlue, "red.png" to faintRed)).normal(
            plan(layers = listOf(layer("blue.png", 0, 0, 2, 1), layer("red.png", 0, 0, 2, 1))),
        )

        // Alpha is rounded only after high-precision premultiplied blending:
        // source red ~= 128, destination blue ~= 127, and no channel spills.
        assertEquals(argb(2, 128, 0, 127), output.pixelAt(1, 0))
    }

    @Test
    fun `canvas rejects overflowing and unsupported pixel dimensions deterministically`() {
        val compositor = SurfaceCompositor(assets())
        val overflow = plan(layers = emptyList()).copy(width = Int.MAX_VALUE, height = 2)
        val oversized = plan(layers = emptyList()).copy(width = 4097, height = 4096)

        assertEquals(
            "surface pixel count 4294967294 exceeds supported limit 16777216",
            assertThrows(IllegalArgumentException::class.java) { compositor.normal(overflow) }.message,
        )
        assertEquals(
            "surface pixel count 16781312 exceeds supported limit 16777216",
            assertThrows(IllegalArgumentException::class.java) { compositor.normal(oversized) }.message,
        )
    }

    private fun plan(surfaceId: Int? = 7, layers: List<SurfaceRenderLayer>) = SurfaceRenderPlan(
        surfaceId = surfaceId,
        width = 4,
        height = 4,
        base = SurfaceRenderBase.Layers(layers),
        animations = emptyList(),
    )

    private fun layer(path: String, x: Int, y: Int, width: Int, height: Int) =
        SurfaceRenderLayer(path, x, y, width, height)

    private fun solid(color: Int) = SurfacePixelImage.of(2, 1, intArrayOf(TRANSPARENT, color))

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int) =
        alpha shl 24 or (red shl 16) or (green shl 8) or blue

    private fun assets(vararg values: Pair<String, SurfacePixelImage>) = RecordingAssets(mapOf(*values))

    private class RecordingAssets(private val values: Map<String, SurfacePixelImage>) : SurfacePixelAssets {
        val requests = mutableListOf<String>()
        override fun load(path: String): SurfacePixelImage? {
            requests += path
            return values[path]
        }
    }

    private companion object {
        const val TRANSPARENT = 0x00000000
        const val RED = 0xffff0000.toInt()
        const val GREEN = 0xff00ff00.toInt()
        const val BLUE = 0xff0000ff.toInt()
    }
}
