package com.cattailsw.nanidroid.compose

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.SurfaceTransparencyPolicy
import com.cattailsw.nanidroid.surface.CollisionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun `self alpha preserves authored opaque top left pixel for Bancho style one pixel Kero`() {
        val opaque = SurfacePixelImage.of(1, 1, intArrayOf(RED))
        val renderPlan = plan(
            layers = listOf(layer("bancho-kero.png", 0, 0, 1, 1)),
            width = 1,
            height = 1,
            transparencyPolicy = SurfaceTransparencyPolicy.AUTHORED_ALPHA,
        )

        val composed = SurfaceCompositor(assets("bancho-kero.png" to opaque)).composeNormal(renderPlan)

        assertEquals(RED, composed.image.pixelAt(0, 0))
        assertEquals(IntSize(1, 1), composed.canvasSize)
        assertEquals(IntRect(0, 0, 1, 1), composed.visiblePixelBounds)
    }

    @Test
    fun `legacy transparency still keys every top left color match`() {
        val image = SurfacePixelImage.of(2, 2, intArrayOf(RED, BLUE, RED, GREEN))

        val composed = SurfaceCompositor(assets("legacy.png" to image)).composeNormal(
            plan(layers = listOf(layer("legacy.png", 0, 0, 2, 2))),
        )

        assertEquals(TRANSPARENT, composed.image.pixelAt(0, 0))
        assertEquals(TRANSPARENT, composed.image.pixelAt(0, 1))
        assertEquals(IntRect(1, 0, 2, 2), composed.visiblePixelBounds)
    }

    @Test
    fun `canvas output owns its completed pixels without exposing a public alias`() {
        val output = SurfaceCompositor(assets("base.png" to solid(BLUE))).normal(
            plan(layers = listOf(layer("base.png", 0, 0, 2, 1))),
        )

        val exportedPixels = output.copyPixels()
        exportedPixels[1] = GREEN

        assertEquals(BLUE, output.pixelAt(1, 0))
    }

    @Test
    fun `nonpositive base dimensions leave a blank stage without loading assets`() {
        val loader = RecordingAssets(mapOf("base.png" to solid(BLUE), "replacement.png" to solid(GREEN)))
        val compositor = SurfaceCompositor(loader)
        val missingDimensions = plan(layers = listOf(layer("base.png", 0, 0, 2, 1))).copy(width = 0)

        assertSame(SurfacePixelImage.Empty, compositor.normal(missingDimensions))
        assertSame(SurfacePixelImage.Empty, compositor.frame(missingDimensions, SurfaceRenderFrame.Reset(1)))
        assertTrue(loader.requests.isEmpty())

        val replacement = compositor.frame(
            missingDimensions,
            SurfaceRenderFrame.Base(null, "replacement.png", 2, 1, 1),
        )
        assertEquals(GREEN, replacement.pixelAt(1, 0))
        assertEquals(listOf("replacement.png"), loader.requests)
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
        assertEquals(TRANSPARENT, compositor.frame(renderPlan, SurfaceRenderFrame.Base(null, "replacement.png", 2, 2, 1)).pixelAt(0, 0))
    }

    @Test
    fun `BASE source atomically replaces image canvas collisions and surface identity`() {
        val selectedCollision = collision(1, "selected")
        val sourceCollision = collision(2, "source")
        val selected = plan(
            surfaceId = 0,
            layers = listOf(layer("selected.png", 0, 0, 4, 4)),
            collisions = listOf(selectedCollision),
        )
        val source = plan(
            surfaceId = 3031,
            layers = listOf(layer("source.png", 0, 0, 2, 1)),
            width = 3,
            height = 2,
            collisions = listOf(sourceCollision),
        )
        val compositor = SurfaceCompositor(
            assets("selected.png" to solid(BLUE), "source.png" to solid(GREEN)),
            SurfacePlanRegistry(listOf(selected, source)),
        )

        val composed = compositor.composeFrame(
            selected,
            SurfaceRenderFrame.Base("3031", null, 0, 0, 100),
            revision = 31,
        )

        assertEquals(GREEN, composed.image.pixelAt(1, 0))
        assertEquals(IntSize(3, 2), composed.canvasSize)
        assertEquals(IntRect(1, 0, 2, 1), composed.visiblePixelBounds)
        assertEquals(listOf(sourceCollision), composed.effectiveCollisions)
        assertEquals(3031, composed.surfaceKey.surfaceId)
        assertEquals(31L, composed.revision)
    }

    @Test
    fun `transparent composed canvas retains active collisions and explicit hidden identity`() {
        val active = List(7) { collision(it, "region$it") }
        val transparent = SurfacePixelImage.of(2, 2, IntArray(4))
        val renderPlan = plan(
            layers = listOf(layer("transparent.png", 0, 0, 2, 2)),
            collisions = active,
        )

        val composed = SurfaceCompositor(assets("transparent.png" to transparent)).composeNormal(
            renderPlan,
            explicitlyHidden = true,
            revision = 9,
        )

        assertEquals(null, composed.visiblePixelBounds)
        assertEquals(active, composed.effectiveCollisions)
        assertTrue(composed.explicitlyHidden)
        assertEquals(9L, composed.revision)
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
        val oversized = plan(layers = emptyList()).copy(width = 1025, height = 1024)

        assertEquals(
            "surface pixel count 4294967294 exceeds supported limit 1048576",
            assertThrows(IllegalArgumentException::class.java) { compositor.normal(overflow) }.message,
        )
        assertEquals(
            "surface pixel count 1049600 exceeds supported limit 1048576",
            assertThrows(IllegalArgumentException::class.java) { compositor.normal(oversized) }.message,
        )
    }

    private fun plan(
        surfaceId: Int? = 7,
        layers: List<SurfaceRenderLayer>,
        width: Int = 4,
        height: Int = 4,
        collisions: List<SurfaceCollision> = emptyList(),
        transparencyPolicy: SurfaceTransparencyPolicy = SurfaceTransparencyPolicy.LEGACY_COLOR_KEY,
    ) = SurfaceRenderPlan(
        surfaceId = surfaceId,
        width = width,
        height = height,
        base = SurfaceRenderBase.Layers(layers),
        animations = emptyList(),
        collisions = collisions,
        transparencyPolicy = transparencyPolicy,
    )

    private fun layer(path: String, x: Int, y: Int, width: Int, height: Int) =
        SurfaceRenderLayer(path, x, y, width, height)

    private fun solid(color: Int) = SurfacePixelImage.of(2, 1, intArrayOf(TRANSPARENT, color))

    private fun collision(id: Int, name: String) = SurfaceCollision(
        id = id,
        identifier = name,
        shape = CollisionShape.Rectangle.fromAuthored(0, 0, 0, 0),
        authoredOrder = id,
    )

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
