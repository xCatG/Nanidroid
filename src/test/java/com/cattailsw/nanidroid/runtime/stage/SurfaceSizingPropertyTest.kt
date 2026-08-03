package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.surface.CollisionShape
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceSizingPropertyTest {
    @Test
    fun `all audited intrinsic pairs fit every representative viewport`() {
        val viewports = listOf(
            360 to 720,
            720 to 360,
            400 to 1000,
            610 to 500,
            800 to 1280,
            1280 to 800,
            480 to 230,
            230 to 400,
        )
        val pairs = listOf(
            (250 to 400) to (235 to 200),
            (270 to 378) to (239 to 380),
            (427 to 640) to (1 to 1),
            (210 to 140) to (210 to 140),
            (772 to 535) to (422 to 377),
            (93 to 95) to (200 to 200),
            (450 to 750) to (450 to 750),
            (300 to 501) to (210 to 420),
        )

        viewports.forEach { (width, height) ->
            pairs.forEachIndexed { index, (keroSize, sakuraSize) ->
                val layout = calculate(width, height, metrics(index * 2, keroSize), metrics(index * 2 + 1, sakuraSize))
                if (layout.mode == StageMode.TINY) {
                    assertNull(layout.keroSurface)
                    assertNull(layout.sakuraSurface)
                } else {
                    assertSurfaceFits(requireNotNull(layout.keroSurface), requireNotNull(layout.keroSurfaceRegion), keroSize)
                    assertSurfaceFits(requireNotNull(layout.sakuraSurface), requireNotNull(layout.sakuraSurfaceRegion), sakuraSize)
                }
            }
        }
    }

    @Test
    fun `absence explicit hiding zero and transparent placeholders do not perturb the peer`() {
        val visible = metrics(1, 250 to 400)
        val absent = calculate(360, 720, null, visible)
        val explicit = calculate(360, 720, metrics(2, 8 to 8, hidden = true), visible)
        val zero = calculate(360, 720, metrics(3, 0 to 0), visible)
        val transparent = calculate(360, 720, metrics(4, 200 to 200, visible = null), visible)

        listOf(absent, explicit, zero, transparent).forEach { layout ->
            assertNull(layout.keroSurface)
            assertNotNull(layout.sakuraSurface)
        }
        assertEquals(absent.sakuraSurface, explicit.sakuraSurface)
        assertEquals(absent.sakuraSurface, zero.sakuraSurface)
        assertEquals(absent.sakuraSurface, transparent.sakuraSurface)
    }

    @Test
    fun `Nanika transparent collision canvas remains active while optical bounds drive boost`() {
        val nanikaKero = metrics(
            id = 10,
            size = 200 to 200,
            visible = null,
            collisions = List(7) { index -> collision(index, index, index, index + 1, index + 1) },
        )
        val nanikaSakura = metrics(
            id = 0,
            size = 93 to 95,
            visible = IntRect(33, 73, 59, 92),
        )

        val layout = calculate(360, 720, nanikaKero, nanikaSakura)

        assertNotNull(layout.keroSurface)
        assertNotNull(layout.sakuraSurface)
        val sakuraScale = requireNotNull(layout.sizingBaseline.sakuraAnchor).scale
        val keroScale = requireNotNull(layout.sizingBaseline.keroAnchor).scale
        val sharedScale = layout.sizingBaseline.sharedAuthoredScale
        assertTrue(sakuraScale >= keroScale)
        assertTrue(sakuraScale > sharedScale + EPSILON)
        assertEquals(sharedScale * 2f, sakuraScale, EPSILON)
    }

    @Test
    fun `opaque one and eight pixel surfaces remain content but never exceed two times shared scale`() {
        listOf(1, 8, 9).forEach { side ->
            val layout = calculate(
                360,
                720,
                metrics(10 + side, side to side),
                metrics(0, 427 to 640),
            )
            val anchor = requireNotNull(layout.sizingBaseline.keroAnchor)
            assertNotNull(layout.keroSurface)
            assertTrue(anchor.scale <= layout.sizingBaseline.sharedAuthoredScale * 2f + EPSILON)
        }
    }

    @Test
    fun `extremely elongated canvases remain complete and aspect fitted`() {
        val keroSize = 1 to 800
        val sakuraSize = 800 to 1
        val layout = calculate(
            1280,
            800,
            metrics(81, keroSize),
            metrics(82, sakuraSize),
        )

        assertSurfaceFits(requireNotNull(layout.keroSurface), requireNotNull(layout.keroSurfaceRegion), keroSize)
        assertSurfaceFits(requireNotNull(layout.sakuraSurface), requireNotNull(layout.sakuraSurfaceRegion), sakuraSize)
    }

    @Test
    fun `same geometry preserves unchanged peer when the other surface changes`() {
        val first = calculate(
            720,
            360,
            metrics(10, 235 to 200),
            metrics(0, 250 to 400),
            ghostKey = "ghost-a",
        )
        val second = calculate(
            720,
            360,
            metrics(11, 772 to 535),
            metrics(0, 250 to 400),
            baseline = first.sizingBaseline,
            ghostKey = "ghost-a",
        )

        assertEquals(first.sakuraSurface, second.sakuraSurface)
        assertEquals(first.sizingBaseline.sakuraAnchor, second.sizingBaseline.sakuraAnchor)
    }

    @Test
    fun `animation revision with one surface key moves neither peer`() {
        val originalKero = metrics(10, 235 to 200, revision = 1)
        val originalSakura = metrics(0, 250 to 400, revision = 1)
        val first = calculate(720, 360, originalKero, originalSakura, ghostKey = "ghost-a")
        val second = calculate(
            720,
            360,
            originalKero.copy(revision = 2),
            originalSakura.copy(revision = 2),
            baseline = first.sizingBaseline,
            ghostKey = "ghost-a",
        )

        assertEquals(first.keroSurface, second.keroSurface)
        assertEquals(first.sakuraSurface, second.sakuraSurface)
    }

    @Test
    fun `window lane and ghost changes invalidate the immutable sizing baseline`() {
        val kero = metrics(10, 235 to 200)
        val sakura = metrics(0, 250 to 400)
        val original = calculate(720, 360, kero, sakura, ghostKey = "ghost-a")
        val resized = calculate(800, 360, kero, sakura, original.sizingBaseline, ghostKey = "ghost-a")
        val switched = calculate(720, 360, kero, sakura, original.sizingBaseline, ghostKey = "ghost-b")
        val densityChanged = calculate(
            720,
            360,
            kero,
            sakura,
            original.sizingBaseline,
            ghostKey = "ghost-a",
            density = 2f,
        )

        assertNotEquals(original.sizingBaseline.geometryKey, resized.sizingBaseline.geometryKey)
        assertNotEquals(original.sizingBaseline.geometryKey, switched.sizingBaseline.geometryKey)
        assertNotEquals(original.sizingBaseline.geometryKey, densityChanged.sizingBaseline.geometryKey)
    }

    @Test
    fun `fixed seed sizing properties remain finite contained uncropped bottom aligned and bounded`() {
        val random = Random(0x53414b555241L)
        repeat(300) { sample ->
            val width = 240 + random.nextInt(1200)
            val height = 320 + random.nextInt(1000)
            val keroSize = (1 + random.nextInt(800)) to (1 + random.nextInt(800))
            val sakuraSize = (1 + random.nextInt(800)) to (1 + random.nextInt(800))
            val layout = calculate(width, height, metrics(sample * 2, keroSize), metrics(sample * 2 + 1, sakuraSize))
            if (layout.mode == StageMode.TINY) return@repeat

            listOf(
                Triple(requireNotNull(layout.keroSurface), requireNotNull(layout.keroSurfaceRegion), keroSize),
                Triple(requireNotNull(layout.sakuraSurface), requireNotNull(layout.sakuraSurfaceRegion), sakuraSize),
            ).forEach { (surface, region, intrinsic) ->
                assertSurfaceFits(surface, region, intrinsic)
                assertEquals(region.bottom.value, surface.bottom.value, EPSILON)
            }
            listOfNotNull(layout.sizingBaseline.keroAnchor, layout.sizingBaseline.sakuraAnchor).forEach { anchor ->
                assertTrue(anchor.scale.isFinite() && anchor.scale >= 0f)
                assertTrue(anchor.scale <= layout.sizingBaseline.sharedAuthoredScale * 2f + EPSILON)
            }
        }
    }

    private fun calculate(
        width: Int,
        height: Int,
        kero: ComposedSurfaceMetrics?,
        sakura: ComposedSurfaceMetrics?,
        baseline: StageSizingBaseline? = null,
        ghostKey: String = "fixture",
        density: Float = 1f,
    ) = GhostStageLayoutPolicy.calculate(
        environment = StageEnvironment(
            safeBounds = StageDpRect(0.dp, 0.dp, width.dp, height.dp),
            density = density,
            fontScale = 1f,
            canonicalAppBarHeight = 0.dp,
            posture = StagePosture.FLAT,
            displayFeatures = emptyList(),
            inputCapabilities = StageInputCapabilities(true, true, true, true),
            ghostKey = ghostKey,
        ),
        kero = kero,
        sakura = sakura,
        previousBaseline = baseline,
    )

    private fun metrics(
        id: Int,
        size: Pair<Int, Int>,
        visible: IntRect? = if (size.first > 0 && size.second > 0) IntRect(0, 0, size.first, size.second) else null,
        collisions: List<SurfaceCollision> = emptyList(),
        hidden: Boolean = false,
        revision: Long = 0,
    ) = ComposedSurfaceMetrics(
        canvasSize = IntSize(size.first, size.second),
        visiblePixelBounds = visible,
        collisions = collisions,
        explicitlyHidden = hidden,
        surfaceKey = SurfaceKey(id, IntSize(size.first, size.second)),
        revision = revision,
    )

    private fun collision(id: Int, left: Int, top: Int, right: Int, bottom: Int) = SurfaceCollision(
        id = id,
        identifier = "region$id",
        shape = CollisionShape.Rectangle.fromAuthored(left, top, right - 1, bottom - 1),
        authoredOrder = id,
    )

    private fun assertSurfaceFits(surface: StageDpRect, region: StageDpRect, intrinsic: Pair<Int, Int>) {
        assertTrue(surface.left >= region.left)
        assertTrue(surface.top >= region.top)
        assertTrue(surface.right <= region.right)
        assertTrue(surface.bottom <= region.bottom)
        assertTrue(surface.width.value.isFinite() && surface.width.value >= 0f)
        assertTrue(surface.height.value.isFinite() && surface.height.value >= 0f)
        if (intrinsic.first > 0 && intrinsic.second > 0 && surface.height.value > 0f) {
            val expectedRatio = intrinsic.first.toFloat() / intrinsic.second
            assertEquals(
                expectedRatio,
                surface.width.value / surface.height.value,
                maxOf(0.002f, expectedRatio * 0.0001f),
            )
        }
    }

    private companion object {
        const val EPSILON = 0.001f
    }
}
