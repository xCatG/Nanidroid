package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.cattailsw.nanidroid.surface.CollisionShape
import kotlin.math.floor
import kotlin.math.roundToInt

/** Floating-point geometry produced from one final measured surface rectangle. */
data class FloatRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun translated(offset: IntOffset) = FloatRect(
        left + offset.x,
        top + offset.y,
        right + offset.x,
        bottom + offset.y,
    )
}

/** A platform-neutral half-open rectangle used by exact rendered collision regions. */
data class DoubleRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left <= right && top <= bottom)
    }

    fun contains(point: Offset): Boolean =
        point.x.toDouble() >= left && point.x.toDouble() < right &&
            point.y.toDouble() >= top && point.y.toDouble() < bottom

    fun translated(offset: IntOffset) = DoubleRect(
        left + offset.x.toDouble(),
        top + offset.y.toDouble(),
        right + offset.x.toDouble(),
        bottom + offset.y.toDouble(),
    )
}

/** One exact exterior grid edge of a rendered collision region. */
data class CollisionBoundarySegmentPx(
    val start: Offset,
    val end: Offset,
) {
    fun translated(offset: IntOffset) = CollisionBoundarySegmentPx(
        Offset(start.x + offset.x, start.y + offset.y),
        Offset(end.x + offset.x, end.y + offset.y),
    )
}

/**
 * Exact rendered footprint of an authored collision.
 *
 * Each rectangle is the half-open preimage of one contiguous run of intrinsic
 * pixels accepted by [CollisionShape.contains]. Rows intentionally remain
 * separate, preserving holes and disconnected areas without approximating
 * authored ellipse, circle, or polygon geometry. [boundarySegments] contains
 * only exterior grid edges; shared edges between accepted cells are cancelled.
 */
data class CollisionRegionPx(
    val rects: List<DoubleRect>,
    val boundarySegments: List<CollisionBoundarySegmentPx>,
) {
    fun contains(point: Offset): Boolean =
        point.x.isFinite() && point.y.isFinite() && rects.any { it.contains(point) }

    fun translated(offset: IntOffset) = CollisionRegionPx(
        rects = rects.map { it.translated(offset) },
        boundarySegments = boundarySegments.map { it.translated(offset) },
    )

    companion object {
        val Empty = CollisionRegionPx(emptyList(), emptyList())
    }
}

/** Exact authored shape kind retained for overlay drawing and diagnostics. */
sealed interface CollisionShapePx {
    val bounds: FloatRect
    fun translated(offset: IntOffset): CollisionShapePx

    data class Rectangle(override val bounds: FloatRect) : CollisionShapePx {
        override fun translated(offset: IntOffset) = Rectangle(bounds.translated(offset))
    }

    data class Ellipse(override val bounds: FloatRect) : CollisionShapePx {
        override fun translated(offset: IntOffset) = Ellipse(bounds.translated(offset))
    }

    data class Circle(
        val center: Offset,
        val radius: Float,
        val radiusY: Float = radius,
    ) : CollisionShapePx {
        override val bounds = FloatRect(
            center.x - radius,
            center.y - radiusY,
            center.x + radius,
            center.y + radiusY,
        )

        override fun translated(offset: IntOffset) = copy(
            center = Offset(center.x + offset.x, center.y + offset.y),
        )
    }

    data class Polygon(val points: List<Offset>) : CollisionShapePx {
        override val bounds = FloatRect(
            points.minOf { it.x },
            points.minOf { it.y },
            points.maxOf { it.x },
            points.maxOf { it.y },
        )

        override fun translated(offset: IntOffset) = Polygon(
            points.map { point -> Offset(point.x + offset.x, point.y + offset.y) },
        )
    }
}

/**
 * One immutable measured coordinate contract shared by drawing, input,
 * collision overlays, semantics, and diagnostics.
 *
 * [renderedBounds] is stage-local and half-open. [scale] is the authored
 * uniform scale selected in dp; exact forward/inverse mapping intentionally
 * uses the final per-axis integer ratios because rounding can differ by a pixel.
 */
data class SurfaceTransformPx(
    val intrinsicSize: IntSize,
    val renderedBounds: IntRect,
    val scale: Float,
    val stageToRoot: IntOffset,
) {
    private val renderedWidth: Long get() = renderedBounds.right.toLong() - renderedBounds.left.toLong()
    private val renderedHeight: Long get() = renderedBounds.bottom.toLong() - renderedBounds.top.toLong()
    private val usable: Boolean get() = intrinsicSize.width > 0 && intrinsicSize.height > 0 &&
        renderedWidth > 0L && renderedHeight > 0L && scale.isFinite() && scale > 0f

    val rootBounds: IntRect
        get() = IntRect(
            saturatingAdd(renderedBounds.left, stageToRoot.x),
            saturatingAdd(renderedBounds.top, stageToRoot.y),
            saturatingAdd(renderedBounds.right, stageToRoot.x),
            saturatingAdd(renderedBounds.bottom, stageToRoot.y),
        )

    fun toIntrinsic(stagePoint: IntOffset): IntOffset? =
        map(stagePoint.x.toDouble(), stagePoint.y.toDouble())

    fun toIntrinsic(stagePoint: Offset): IntOffset? =
        map(stagePoint.x.toDouble(), stagePoint.y.toDouble())

    fun rootToIntrinsic(rootPoint: Offset): IntOffset? = map(
        rootPoint.x.toDouble() - stageToRoot.x.toDouble(),
        rootPoint.y.toDouble() - stageToRoot.y.toDouble(),
    )

    fun toStage(shape: CollisionShape): CollisionShapePx {
        val scaleX = renderedWidth.toDouble() / intrinsicSize.width.toDouble()
        val scaleY = renderedHeight.toDouble() / intrinsicSize.height.toDouble()
        fun point(point: IntOffset) = Offset(
            (renderedBounds.left.toDouble() + point.x.toDouble() * scaleX).toFloat(),
            (renderedBounds.top.toDouble() + point.y.toDouble() * scaleY).toFloat(),
        )
        fun rect(rect: IntRect) = FloatRect(
            (renderedBounds.left.toDouble() + rect.left.toDouble() * scaleX).toFloat(),
            (renderedBounds.top.toDouble() + rect.top.toDouble() * scaleY).toFloat(),
            (renderedBounds.left.toDouble() + rect.right.toDouble() * scaleX).toFloat(),
            (renderedBounds.top.toDouble() + rect.bottom.toDouble() * scaleY).toFloat(),
        )
        return when (shape) {
            is CollisionShape.Rectangle -> CollisionShapePx.Rectangle(rect(shape.bounds))
            is CollisionShape.Ellipse -> CollisionShapePx.Ellipse(rect(shape.bounds))
            is CollisionShape.Circle -> CollisionShapePx.Circle(
                center = point(shape.center),
                radius = (shape.radius.toDouble() * scaleX).toFloat(),
                radiusY = (shape.radius.toDouble() * scaleY).toFloat(),
            )
            is CollisionShape.Polygon -> CollisionShapePx.Polygon(shape.points.map(::point))
        }
    }

    fun toRoot(shape: CollisionShape): CollisionShapePx = toStage(shape).translated(stageToRoot)

    /**
     * Materializes the exact visible hit footprint used by [toIntrinsic]. The
     * authored shape remains unchanged; only intrinsic pixels inside the
     * surface canvas participate in this rendered diagnostic region.
     */
    fun toStageRegion(shape: CollisionShape): CollisionRegionPx {
        if (!usable) return CollisionRegionPx.Empty
        val clippedLeft = maxOf(0, shape.bounds.left)
        val clippedTop = maxOf(0, shape.bounds.top)
        val clippedRight = minOf(intrinsicSize.width, shape.bounds.right)
        val clippedBottom = minOf(intrinsicSize.height, shape.bounds.bottom)
        if (clippedLeft >= clippedRight || clippedTop >= clippedBottom) return CollisionRegionPx.Empty

        fun boundaryX(x: Int): Double = renderedBounds.left.toDouble() +
            x.toDouble() * renderedWidth.toDouble() / intrinsicSize.width.toDouble()
        fun boundaryY(y: Int): Double = renderedBounds.top.toDouble() +
            y.toDouble() * renderedHeight.toDouble() / intrinsicSize.height.toDouble()
        fun rect(left: Int, top: Int, right: Int, bottom: Int) = DoubleRect(
            boundaryX(left),
            boundaryY(top),
            boundaryX(right),
            boundaryY(bottom),
        )

        if (shape is CollisionShape.Rectangle) {
            return CollisionRegionPx(
                rects = listOf(rect(clippedLeft, clippedTop, clippedRight, clippedBottom)),
                boundarySegments = listOf(
                    segment(clippedLeft, clippedTop, clippedRight, clippedTop, ::boundaryX, ::boundaryY),
                    segment(clippedRight, clippedTop, clippedRight, clippedBottom, ::boundaryX, ::boundaryY),
                    segment(clippedRight, clippedBottom, clippedLeft, clippedBottom, ::boundaryX, ::boundaryY),
                    segment(clippedLeft, clippedBottom, clippedLeft, clippedTop, ::boundaryX, ::boundaryY),
                ),
            )
        }

        val exteriorEdges = linkedSetOf<IntrinsicEdge>()
        fun toggle(firstX: Int, firstY: Int, secondX: Int, secondY: Int) {
            val edge = IntrinsicEdge.normalized(firstX, firstY, secondX, secondY)
            if (!exteriorEdges.remove(edge)) exteriorEdges.add(edge)
        }
        val runs = buildList {
            for (y in clippedTop until clippedBottom) {
                var runStart: Int? = null
                for (x in clippedLeft until clippedRight) {
                    val accepted = shape.contains(IntOffset(x, y))
                    if (accepted) {
                        if (runStart == null) runStart = x
                        toggle(x, y, x + 1, y)
                        toggle(x + 1, y, x + 1, y + 1)
                        toggle(x + 1, y + 1, x, y + 1)
                        toggle(x, y + 1, x, y)
                    }
                    if (!accepted && runStart != null) {
                        add(rect(runStart, y, x, y + 1))
                        runStart = null
                    }
                }
                runStart?.let { add(rect(it, y, clippedRight, y + 1)) }
            }
        }
        val segments = exteriorEdges
            .sortedWith(compareBy(IntrinsicEdge::firstY, IntrinsicEdge::firstX, IntrinsicEdge::secondY, IntrinsicEdge::secondX))
            .map { edge ->
                segment(
                    edge.firstX,
                    edge.firstY,
                    edge.secondX,
                    edge.secondY,
                    ::boundaryX,
                    ::boundaryY,
                )
            }
        return CollisionRegionPx(runs, segments)
    }

    fun toRootRegion(shape: CollisionShape): CollisionRegionPx =
        toStageRegion(shape).translated(stageToRoot)

    private fun map(stageX: Double, stageY: Double): IntOffset? {
        if (!usable || !stageX.isFinite() || !stageY.isFinite()) return null
        val left = renderedBounds.left.toDouble()
        val top = renderedBounds.top.toDouble()
        val localX = stageX - left
        val localY = stageY - top
        if (localX < 0.0 || localY < 0.0 || localX >= renderedWidth.toDouble() || localY >= renderedHeight.toDouble()) {
            return null
        }
        val intrinsicX = floor(localX * intrinsicSize.width.toDouble() / renderedWidth.toDouble()).toLong()
        val intrinsicY = floor(localY * intrinsicSize.height.toDouble() / renderedHeight.toDouble()).toLong()
        if (intrinsicX !in 0 until intrinsicSize.width.toLong() || intrinsicY !in 0 until intrinsicSize.height.toLong()) {
            return null
        }
        return IntOffset(intrinsicX.toInt(), intrinsicY.toInt())
    }
}

private data class IntrinsicEdge(
    val firstX: Int,
    val firstY: Int,
    val secondX: Int,
    val secondY: Int,
) {
    companion object {
        fun normalized(firstX: Int, firstY: Int, secondX: Int, secondY: Int): IntrinsicEdge =
            if (firstY < secondY || firstY == secondY && firstX <= secondX) {
                IntrinsicEdge(firstX, firstY, secondX, secondY)
            } else {
                IntrinsicEdge(secondX, secondY, firstX, firstY)
            }
    }
}

private fun segment(
    firstX: Int,
    firstY: Int,
    secondX: Int,
    secondY: Int,
    boundaryX: (Int) -> Double,
    boundaryY: (Int) -> Double,
) = CollisionBoundarySegmentPx(
    start = Offset(boundaryX(firstX).toFloat(), boundaryY(firstY).toFloat()),
    end = Offset(boundaryX(secondX).toFloat(), boundaryY(secondY).toFloat()),
)

enum class SurfaceScope { KERO, SAKURA }

/** All policy rectangles after the one and only dp-to-px edge conversion. */
data class StageLayoutPx(
    val mode: StageMode,
    val content: IntRect,
    val keroLane: IntRect?,
    val sakuraLane: IntRect?,
    val keroBubble: IntRect?,
    val sakuraBubble: IntRect?,
    val keroSurfaceRegion: IntRect?,
    val sakuraSurfaceRegion: IntRect?,
    val keroSurface: IntRect?,
    val sakuraSurface: IntRect?,
    val stageToRoot: IntOffset,
) {
    fun transformFor(scope: SurfaceScope, intrinsicSize: IntSize): SurfaceTransformPx? {
        val bounds = when (scope) {
            SurfaceScope.KERO -> keroSurface
            SurfaceScope.SAKURA -> sakuraSurface
        } ?: return null
        if (intrinsicSize.width <= 0 || intrinsicSize.height <= 0 || bounds.width <= 0 || bounds.height <= 0) return null
        val scale = minOf(
            bounds.width.toDouble() / intrinsicSize.width.toDouble(),
            bounds.height.toDouble() / intrinsicSize.height.toDouble(),
        ).toFloat()
        return SurfaceTransformPx(intrinsicSize, bounds, scale, stageToRoot)
    }

    companion object {
        fun from(
            layout: StageLayoutDp,
            density: Float,
            stageToRoot: IntOffset = IntOffset.Zero,
        ): StageLayoutPx {
            require(density.isFinite() && density > 0f) { "density must be finite and positive" }
            fun StageDpRect?.rounded(): IntRect? = this?.let { rect ->
                IntRect(
                    roundDp(rect.left.value, density),
                    roundDp(rect.top.value, density),
                    roundDp(rect.right.value, density),
                    roundDp(rect.bottom.value, density),
                )
            }
            return StageLayoutPx(
                mode = layout.mode,
                content = requireNotNull(layout.content.rounded()),
                keroLane = layout.keroLane.rounded(),
                sakuraLane = layout.sakuraLane.rounded(),
                keroBubble = layout.keroBubble.rounded(),
                sakuraBubble = layout.sakuraBubble.rounded(),
                keroSurfaceRegion = layout.keroSurfaceRegion.rounded(),
                sakuraSurfaceRegion = layout.sakuraSurfaceRegion.rounded(),
                keroSurface = layout.keroSurface.rounded(),
                sakuraSurface = layout.sakuraSurface.rounded(),
                stageToRoot = stageToRoot,
            )
        }
    }
}

fun IntRect.positiveIntersection(other: IntRect): Boolean =
    maxOf(left, other.left) < minOf(right, other.right) &&
        maxOf(top, other.top) < minOf(bottom, other.bottom)

private fun roundDp(value: Float, density: Float): Int {
    val pixels = value.toDouble() * density.toDouble()
    if (!pixels.isFinite()) return if (pixels > 0) Int.MAX_VALUE else Int.MIN_VALUE
    return when {
        pixels >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
        pixels <= Int.MIN_VALUE.toDouble() -> Int.MIN_VALUE
        else -> pixels.roundToInt()
    }
}

private fun saturatingAdd(first: Int, second: Int): Int {
    val sum = first.toLong() + second.toLong()
    return sum.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
