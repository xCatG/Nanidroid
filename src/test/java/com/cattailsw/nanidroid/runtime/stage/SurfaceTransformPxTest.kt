package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.surface.CollisionShape
import com.cattailsw.nanidroid.compose.stage.GhostStageMeasureState
import com.cattailsw.nanidroid.compose.stage.StageMeasuredSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceTransformPxTest {
    @Test
    fun `inverse mapping floors against final integer bounds at every edge`() {
        val transform = SurfaceTransformPx(
            intrinsicSize = IntSize(7, 5),
            renderedBounds = IntRect(11, 17, 25, 27),
            scale = 2f,
            stageToRoot = IntOffset(101, 203),
        )

        assertEquals(IntOffset(0, 0), transform.toIntrinsic(Offset(11f, 17f)))
        assertEquals(IntOffset(0, 0), transform.toIntrinsic(Offset(12.999f, 18.999f)))
        assertEquals(IntOffset(6, 4), transform.toIntrinsic(Offset(24.999f, 26.999f)))
        assertNull(transform.toIntrinsic(Offset(25f, 20f)))
        assertNull(transform.toIntrinsic(Offset(20f, 27f)))
        assertNull(transform.toIntrinsic(Offset(10.999f, 20f)))
        assertNull(transform.toIntrinsic(Offset(20f, 16.999f)))
    }

    @Test
    fun `root inverse mapping applies stage translation exactly once`() {
        val transform = SurfaceTransformPx(
            intrinsicSize = IntSize(10, 20),
            renderedBounds = IntRect(7, 9, 27, 49),
            scale = 2f,
            stageToRoot = IntOffset(100, 200),
        )

        assertEquals(IntRect(107, 209, 127, 249), transform.rootBounds)
        assertEquals(IntOffset(4, 9), transform.rootToIntrinsic(Offset(116.999f, 228.999f)))
        assertNull(transform.rootToIntrinsic(Offset(127f, 228f)))
    }

    @Test
    fun `invalid nonfinite zero and overflow-prone transforms reject input`() {
        val invalid = listOf(
            SurfaceTransformPx(IntSize.Zero, IntRect(0, 0, 10, 10), 1f, IntOffset.Zero),
            SurfaceTransformPx(IntSize(10, 10), IntRect(2, 2, 2, 12), 1f, IntOffset.Zero),
            SurfaceTransformPx(IntSize(10, 10), IntRect(2, 2, 12, 2), 1f, IntOffset.Zero),
            SurfaceTransformPx(IntSize(10, 10), IntRect(0, 0, 10, 10), Float.NaN, IntOffset.Zero),
            SurfaceTransformPx(IntSize(10, 10), IntRect(0, 0, 10, 10), Float.POSITIVE_INFINITY, IntOffset.Zero),
        )

        invalid.forEach { assertNull(it.toIntrinsic(Offset(2f, 2f))) }
        assertNull(invalid.last().toIntrinsic(Offset(Float.MAX_VALUE, Float.MAX_VALUE)))
        assertNull(invalid.last().toIntrinsic(Offset(Float.NaN, 1f)))

        val huge = SurfaceTransformPx(
            IntSize(Int.MAX_VALUE, Int.MAX_VALUE),
            IntRect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
            1f,
            IntOffset.Zero,
        )
        assertEquals(IntOffset(1_073_741_823, 1_073_741_823), huge.toIntrinsic(Offset(-0.5f, -0.5f)))
    }

    @Test
    fun `stage layout rounds every final edge once at supported densities`() {
        val layout = layout(
            content = StageDpRect(0.25.dp, 1.25.dp, 10.75.dp, 20.75.dp),
            keroBubble = StageDpRect(0.25.dp, 1.25.dp, 5.5.dp, 7.75.dp),
            keroSurface = StageDpRect(1.25.dp, 7.75.dp, 4.75.dp, 20.75.dp),
        )
        val cases = listOf(
            1f to IntRect(0, 1, 11, 21),
            1.5f to IntRect(0, 2, 16, 31),
            2f to IntRect(1, 3, 22, 42),
            3f to IntRect(1, 4, 32, 62),
        )

        cases.forEach { (density, expectedContent) ->
            val measured = StageLayoutPx.from(layout, density)
            assertEquals("density=$density", expectedContent, measured.content)
            assertTrue(measured.keroBubble!!.bottom <= measured.keroSurface!!.top)
        }
    }

    @Test
    fun `materialized transform uses the same final rectangle and implied uniform scale`() {
        val measured = StageLayoutPx.from(
            layout(keroSurface = StageDpRect(10.25.dp, 20.25.dp, 110.25.dp, 220.25.dp)),
            density = 1.5f,
            stageToRoot = IntOffset(3, 7),
        )

        val transform = measured.transformFor(SurfaceScope.KERO, IntSize(50, 100))!!
        assertEquals(measured.keroSurface, transform.renderedBounds)
        assertEquals(3f, transform.scale, 0.0001f)
        assertEquals(IntOffset(3, 7), transform.stageToRoot)
    }

    @Test
    fun `resize and rotation create fresh transforms without mutating prior input geometry`() {
        val first = StageLayoutPx.from(
            layout(keroSurface = StageDpRect(0.dp, 100.dp, 100.dp, 300.dp)),
            density = 1f,
        ).transformFor(SurfaceScope.KERO, IntSize(50, 100))!!
        val resized = StageLayoutPx.from(
            layout(keroSurface = StageDpRect(200.dp, 0.dp, 300.dp, 200.dp)),
            density = 1f,
        ).transformFor(SurfaceScope.KERO, IntSize(50, 100))!!

        assertNotEquals(first, resized)
        assertEquals(IntOffset(25, 50), first.toIntrinsic(Offset(50f, 200f)))
        assertNull(resized.toIntrinsic(Offset(50f, 200f)))
        assertEquals(IntOffset(25, 50), resized.toIntrinsic(Offset(250f, 100f)))
    }

    @Test
    fun `rectangle ellipse circle and polygon retain authored geometry in stage pixels`() {
        val transform = SurfaceTransformPx(
            IntSize(20, 20),
            IntRect(10, 30, 50, 70),
            2f,
            IntOffset(100, 200),
        )

        assertEquals(
            CollisionShapePx.Rectangle(FloatRect(12f, 34f, 20f, 42f)),
            transform.toStage(CollisionShape.Rectangle(IntRect(1, 2, 5, 6))),
        )
        assertEquals(
            CollisionShapePx.Ellipse(FloatRect(12f, 34f, 20f, 42f)),
            transform.toStage(CollisionShape.Ellipse(IntRect(1, 2, 5, 6))),
        )
        assertEquals(
            CollisionShapePx.Circle(center = Offset(18f, 40f), radius = 6f),
            transform.toStage(CollisionShape.Circle(IntOffset(4, 5), 3)),
        )
        assertEquals(
            CollisionShapePx.Polygon(listOf(Offset(10f, 30f), Offset(18f, 30f), Offset(14f, 38f))),
            transform.toStage(
                CollisionShape.Polygon(listOf(IntOffset(0, 0), IntOffset(4, 0), IntOffset(2, 4))),
            ),
        )
    }

    @Test
    fun `shape mapping uses exact final axis ratios after asymmetric rounding`() {
        val transform = SurfaceTransformPx(
            intrinsicSize = IntSize(7, 5),
            renderedBounds = IntRect(10, 30, 25, 41),
            scale = 2.15f,
            stageToRoot = IntOffset.Zero,
        )

        val rectangle = transform.toStage(CollisionShape.Rectangle(IntRect(1, 1, 6, 4)))
            as CollisionShapePx.Rectangle

        assertEquals(12.142857f, rectangle.bounds.left, 0.0001f)
        assertEquals(32.2f, rectangle.bounds.top, 0.0001f)
        assertEquals(22.857143f, rectangle.bounds.right, 0.0001f)
        assertEquals(38.8f, rectangle.bounds.bottom, 0.0001f)
    }

    @Test
    fun `collision regions exactly match inverse hit membership for every shape`() {
        val transform = SurfaceTransformPx(
            intrinsicSize = IntSize(7, 5),
            renderedBounds = IntRect(11, 17, 26, 28),
            scale = 2.15f,
            stageToRoot = IntOffset(101, 203),
        )
        val shapes = listOf(
            CollisionShape.Rectangle(IntRect(-3, 1, 4, 7)),
            CollisionShape.Ellipse.fromAuthored(-2, -1, 6, 4),
            CollisionShape.Circle.fromAuthored(0, 0, 0),
            CollisionShape.Circle.fromAuthored(3, 2, 3),
            CollisionShape.Polygon(
                listOf(
                    IntOffset(-2, -1),
                    IntOffset(8, 5),
                    IntOffset(-2, 5),
                    IntOffset(8, -1),
                ),
            ),
        )

        shapes.forEach { shape ->
            val stageRegion = transform.toStageRegion(shape)
            val rootRegion = transform.toRootRegion(shape)
            probeCoordinates(transform).forEach { stagePoint ->
                val expected = transform.toIntrinsic(stagePoint)?.let(shape::contains) == true
                assertEquals("stage shape=$shape point=$stagePoint", expected, stageRegion.contains(stagePoint))
                val rootPoint = Offset(
                    stagePoint.x + transform.stageToRoot.x,
                    stagePoint.y + transform.stageToRoot.y,
                )
                val expectedRoot = transform.rootToIntrinsic(rootPoint)?.let(shape::contains) == true
                assertEquals("root shape=$shape point=$rootPoint", expectedRoot, rootRegion.contains(rootPoint))
            }
        }
    }

    @Test
    fun `collision region retains holes and disjoint runs without filling its bounds`() {
        val transform = SurfaceTransformPx(
            intrinsicSize = IntSize(7, 7),
            renderedBounds = IntRect(3, 5, 20, 24),
            scale = 2.5f,
            stageToRoot = IntOffset.Zero,
        )
        val crossing = CollisionShape.Polygon(
            listOf(
                IntOffset(0, 0),
                IntOffset(6, 6),
                IntOffset(0, 6),
                IntOffset(6, 0),
            ),
        )
        val region = transform.toStageRegion(crossing)

        assertTrue(region.rects.size > 1)
        probeCoordinates(transform).forEach { point ->
            val expected = transform.toIntrinsic(point)?.let(crossing::contains) == true
            assertEquals("point=$point", expected, region.contains(point))
        }
        assertFalse(region.contains(Offset(3.1f, 14.5f)))
        assertFalse(region.contains(Offset(19.999f, 14.5f)))
    }

    @Test
    fun `collision boundary cancels internal cell edges and outlines a radius zero circle`() {
        val transform = SurfaceTransformPx(
            intrinsicSize = IntSize(4, 3),
            renderedBounds = IntRect(7, 11, 19, 17),
            scale = 2f,
            stageToRoot = IntOffset.Zero,
        )
        val twoCellEllipse = CollisionShape.Ellipse.fromAuthored(0, 0, 1, 0)
        val twoCellRegion = transform.toStageRegion(twoCellEllipse)
        val internalX = 10f

        assertFalse(
            twoCellRegion.boundarySegments.any { segment ->
                segment.start.x == internalX && segment.end.x == internalX &&
                    setOf(segment.start.y, segment.end.y) == setOf(11f, 13f)
            },
        )
        assertEquals(6, twoCellRegion.boundarySegments.size)

        val pointRegion = transform.toStageRegion(CollisionShape.Circle.fromAuthored(2, 1, 0))
        assertEquals(1, pointRegion.rects.size)
        assertEquals(4, pointRegion.boundarySegments.size)
        assertTrue(pointRegion.contains(Offset(13.1f, 13.1f)))
        assertFalse(pointRegion.contains(Offset(16f, 13.1f)))
    }

    @Test
    fun `root collision and hit coordinates share one translation`() {
        val transform = SurfaceTransformPx(
            IntSize(8, 8),
            IntRect(11, 13, 27, 29),
            2f,
            IntOffset(100, 200),
        )
        val rootShape = transform.toRoot(CollisionShape.Rectangle(IntRect(2, 3, 5, 7)))

        assertEquals(CollisionShapePx.Rectangle(FloatRect(115f, 219f, 121f, 227f)), rootShape)
        assertEquals(IntOffset(2, 3), transform.rootToIntrinsic(Offset(115f, 219f)))
        assertEquals(IntOffset(4, 6), transform.rootToIntrinsic(Offset(120.999f, 226.999f)))
        assertNull(transform.rootToIntrinsic(Offset(121f + 6f, 227f + 2f)))
    }

    @Test
    fun `shared rounding keeps a logically separated bubble outside mapped peer footprints`() {
        val layout = layout(
            keroBubble = StageDpRect(0.dp, 0.dp, 100.48.dp, 20.48.dp),
            sakuraSurface = StageDpRect(100.49.dp, 20.49.dp, 200.dp, 100.dp),
        )

        val measured = StageLayoutPx.from(layout, density = 2f)
        val peer = measured.transformFor(SurfaceScope.SAKURA, IntSize(100, 80))!!
        val collision = peer.toStage(CollisionShape.Rectangle(IntRect(0, 0, 20, 20))).bounds

        assertFalse(measured.keroBubble!!.positiveIntersection(measured.sakuraSurface!!))
        assertTrue(collision.left >= measured.keroBubble.right)
    }

    @Test
    fun `measure baseline survives the same owner and resets atomically for a replacement owner`() {
        val state = GhostStageMeasureState()
        val firstOwner = Any()
        val replacementOwner = Any()
        val layoutDp = layout(keroSurface = StageDpRect(0.dp, 10.dp, 20.dp, 40.dp))
        val snapshot = StageMeasuredSnapshot(
            layoutDp = layoutDp,
            layoutPx = StageLayoutPx.from(layoutDp, 1f),
            kero = null,
            sakura = null,
        )

        state.resetFor(firstOwner)
        state.commit(snapshot)
        state.resetFor(firstOwner)
        assertSame(snapshot, state.latest)
        assertEquals(layoutDp.sizingBaseline, state.baseline)

        state.resetFor(replacementOwner)
        assertNull(state.latest)
        assertNull(state.baseline)
    }

    private fun layout(
        content: StageDpRect = StageDpRect(0.dp, 0.dp, 300.dp, 400.dp),
        keroBubble: StageDpRect? = null,
        keroSurface: StageDpRect? = null,
        sakuraSurface: StageDpRect? = null,
    ): StageLayoutDp {
        val geometry = StageGeometryKey(
            ghostKey = "fixture",
            windowKey = StageWindowKey(content, 1f, 0.dp, StagePosture.FLAT, emptyList()),
            mode = StageMode.STANDARD,
            content = content,
            keroRegion = keroSurface,
            sakuraRegion = sakuraSurface,
        )
        return StageLayoutDp(
            mode = StageMode.STANDARD,
            content = content,
            keroLane = null,
            sakuraLane = null,
            keroBubble = keroBubble,
            sakuraBubble = null,
            keroSurfaceRegion = keroSurface,
            sakuraSurfaceRegion = sakuraSurface,
            keroSurface = keroSurface,
            sakuraSurface = sakuraSurface,
            sizingBaseline = StageSizingBaseline(geometry, 1f, null, null),
            tinyFallback = false,
        )
    }

    private fun probeCoordinates(transform: SurfaceTransformPx): List<Offset> {
        val xs = mutableSetOf<Float>()
        val ys = mutableSetOf<Float>()
        fun addBoundarySamples(target: MutableSet<Float>, value: Double) {
            val boundary = value.toFloat()
            target += boundary
            target += Math.nextAfter(boundary, Double.NEGATIVE_INFINITY)
            target += Math.nextAfter(boundary, Double.POSITIVE_INFINITY)
        }
        for (x in 0..transform.intrinsicSize.width) {
            addBoundarySamples(
                xs,
                transform.renderedBounds.left.toDouble() +
                    x.toDouble() * transform.renderedBounds.width.toDouble() / transform.intrinsicSize.width,
            )
        }
        for (y in 0..transform.intrinsicSize.height) {
            addBoundarySamples(
                ys,
                transform.renderedBounds.top.toDouble() +
                    y.toDouble() * transform.renderedBounds.height.toDouble() / transform.intrinsicSize.height,
            )
        }
        xs += transform.renderedBounds.left - 1f
        xs += transform.renderedBounds.right + 1f
        ys += transform.renderedBounds.top - 1f
        ys += transform.renderedBounds.bottom + 1f
        return xs.flatMap { x -> ys.map { y -> Offset(x, y) } }
    }
}
