package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.cattailsw.nanidroid.surface.CollisionShape
import java.math.BigInteger
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
    val isExact: Boolean = true,
    val fallbackReason: String? = null,
) {
    fun contains(point: Offset): Boolean =
        point.x.isFinite() && point.y.isFinite() && rects.any { it.contains(point) }

    fun translated(offset: IntOffset) = CollisionRegionPx(
        rects = rects.map { it.translated(offset) },
        boundarySegments = boundarySegments.map { it.translated(offset) },
        isExact = isExact,
        fallbackReason = fallbackReason,
    )

    companion object {
        val Empty = CollisionRegionPx(emptyList(), emptyList())

        fun complexityFallback() = CollisionRegionPx(
            rects = emptyList(),
            boundarySegments = emptyList(),
            isExact = false,
            fallbackReason = "exact-footprint-complexity-budget",
        )
    }
}

/**
 * Shared hard budget for one visible overlay. Reservation happens before any
 * expensive scan, so legal but adversarial ghost data cannot cause an ANR or
 * unbounded path allocation. A rejected shape receives a truthful authored
 * guide instead of a falsely exact footprint.
 */
class CollisionGeometryBudget(
    val maxWork: Int,
    val maxRects: Int,
    val maxBoundarySegments: Int,
) {
    init {
        require(maxWork >= 0 && maxRects >= 0 && maxBoundarySegments >= 0)
    }

    var consumedWork: Int = 0
        private set
    var consumedRects: Int = 0
        private set
    var consumedBoundarySegments: Int = 0
        private set

    internal fun reserve(work: Long, rects: Long, boundarySegments: Long): Boolean {
        if (work < 0L || rects < 0L || boundarySegments < 0L) return false
        if (consumedWork.toLong() + work > maxWork.toLong()) return false
        if (consumedRects.toLong() + rects > maxRects.toLong()) return false
        if (consumedBoundarySegments.toLong() + boundarySegments > maxBoundarySegments.toLong()) return false
        consumedWork += work.toInt()
        consumedRects += rects.toInt()
        consumedBoundarySegments += boundarySegments.toInt()
        return true
    }

    companion object {
        fun perCollisionDefault() = CollisionGeometryBudget(
            maxWork = 100_000,
            maxRects = 2_048,
            maxBoundarySegments = 8_192,
        )

        fun overlayDefault() = CollisionGeometryBudget(
            maxWork = OVERLAY_MAX_WORK,
            maxRects = OVERLAY_MAX_RECTS,
            maxBoundarySegments = OVERLAY_MAX_BOUNDARY_SEGMENTS,
        )

        // Large enough for a normal collection of maximum-canvas convex shapes,
        // while still rejecting adversarial high-vertex scanlines before work starts.
        const val OVERLAY_MAX_WORK = 2_097_152
        const val OVERLAY_MAX_RECTS = 32_768
        const val OVERLAY_MAX_BOUNDARY_SEGMENTS = 131_072
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

    /** Center of one intrinsic pixel in stage-local coordinates. */
    fun stageCenterForIntrinsic(intrinsic: IntOffset): IntOffset? {
        if (!usable || intrinsic.x !in 0 until intrinsicSize.width || intrinsic.y !in 0 until intrinsicSize.height) {
            return null
        }
        val x = renderedBounds.left.toDouble() +
            (intrinsic.x.toDouble() + 0.5) * renderedWidth.toDouble() / intrinsicSize.width.toDouble()
        val y = renderedBounds.top.toDouble() +
            (intrinsic.y.toDouble() + 0.5) * renderedHeight.toDouble() / intrinsicSize.height.toDouble()
        return IntOffset(
            x.roundToInt().coerceIn(renderedBounds.left, renderedBounds.right - 1),
            y.roundToInt().coerceIn(renderedBounds.top, renderedBounds.bottom - 1),
        )
    }

    /** One exact authored hit pixel inside both [shape] and the intrinsic canvas. */
    fun representativeIntrinsicPoint(
        shape: CollisionShape,
        budget: CollisionGeometryBudget = CollisionGeometryBudget.perCollisionDefault(),
    ): IntOffset? {
        if (!usable) return null
        val clippedLeft = maxOf(0, shape.bounds.left)
        val clippedTop = maxOf(0, shape.bounds.top)
        val clippedRight = minOf(intrinsicSize.width, shape.bounds.right)
        val clippedBottom = minOf(intrinsicSize.height, shape.bounds.bottom)
        if (clippedLeft >= clippedRight || clippedTop >= clippedBottom) return null
        if (shape is CollisionShape.Rectangle) return IntOffset(clippedLeft, clippedTop)

        val width = clippedRight.toLong() - clippedLeft.toLong()
        val height = clippedBottom.toLong() - clippedTop.toLong()
        if (!budget.reserve(collisionWorkEstimate(shape, width, height), rects = 0L, boundarySegments = 0L)) {
            return null
        }
        for (y in clippedTop until clippedBottom) {
            val firstRun = when (shape) {
                is CollisionShape.Ellipse,
                is CollisionShape.Circle,
                -> convexRowRuns(shape, y, clippedLeft, clippedRight)
                is CollisionShape.Polygon -> polygonRowRuns(shape, y, clippedLeft, clippedRight)
                is CollisionShape.Rectangle -> error("rectangle handled above")
            }.firstOrNull()
            if (firstRun != null) return IntOffset(firstRun.left, y)
        }
        return null
    }

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
     * Builds one visible overlay under a shared hard cap. Cheaper definitions
     * are attempted first so a hostile early entry cannot starve later routine
     * collisions, and unused capacity is automatically available to its peers.
     * Results retain authored order for labels and drawing.
     */
    internal fun toStageRegions(
        shapes: List<CollisionShape>,
        budget: CollisionGeometryBudget = CollisionGeometryBudget.overlayDefault(),
    ): List<CollisionRegionPx> {
        val results = arrayOfNulls<CollisionRegionPx>(shapes.size)
        shapes.indices
            .sortedWith(compareBy<Int>({ estimatedRegionWork(shapes[it]) }, { it }))
            .forEach { index ->
                results[index] = toStageRegion(shapes[index], budget)
            }
        return results.map { requireNotNull(it) }
    }

    /**
     * Materializes the exact visible hit footprint used by [toIntrinsic]. The
     * authored shape remains unchanged; only intrinsic pixels inside the
     * surface canvas participate in this rendered diagnostic region.
     */
    fun toStageRegion(
        shape: CollisionShape,
        budget: CollisionGeometryBudget = CollisionGeometryBudget.perCollisionDefault(),
    ): CollisionRegionPx {
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
            if (!budget.reserve(work = 1L, rects = 1L, boundarySegments = 4L)) {
                return CollisionRegionPx.complexityFallback()
            }
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

        val width = clippedRight.toLong() - clippedLeft.toLong()
        val height = clippedBottom.toLong() - clippedTop.toLong()
        val workEstimate = collisionWorkEstimate(shape, width, height)
        if (!budget.reserve(workEstimate, rects = 0L, boundarySegments = 0L)) {
            return CollisionRegionPx.complexityFallback()
        }

        val compact = CompactCollisionBuilder()
        for (y in clippedTop until clippedBottom) {
            val row = when (shape) {
                is CollisionShape.Ellipse -> convexRowRuns(shape, y, clippedLeft, clippedRight)
                is CollisionShape.Circle -> convexRowRuns(shape, y, clippedLeft, clippedRight)
                is CollisionShape.Polygon -> polygonRowRuns(shape, y, clippedLeft, clippedRight)
                is CollisionShape.Rectangle -> error("rectangle handled above")
            }
            compact.appendRow(y, row)
        }
        val geometry = compact.finish(clippedBottom)
        if (!budget.reserve(
                work = 0L,
                rects = geometry.rects.size.toLong(),
                boundarySegments = geometry.segments.size.toLong(),
            )
        ) {
            return CollisionRegionPx.complexityFallback()
        }
        return CollisionRegionPx(
            rects = geometry.rects.map { source ->
                rect(source.left, source.top, source.right, source.bottom)
            },
            boundarySegments = geometry.segments.map { source ->
                segment(
                    source.firstX,
                    source.firstY,
                    source.secondX,
                    source.secondY,
                    ::boundaryX,
                    ::boundaryY,
                )
            },
        )
    }

    private fun estimatedRegionWork(shape: CollisionShape): Long {
        if (!usable) return 0L
        val clippedLeft = maxOf(0, shape.bounds.left)
        val clippedTop = maxOf(0, shape.bounds.top)
        val clippedRight = minOf(intrinsicSize.width, shape.bounds.right)
        val clippedBottom = minOf(intrinsicSize.height, shape.bounds.bottom)
        if (clippedLeft >= clippedRight || clippedTop >= clippedBottom) return 0L
        return collisionWorkEstimate(
            shape = shape,
            width = clippedRight.toLong() - clippedLeft.toLong(),
            height = clippedBottom.toLong() - clippedTop.toLong(),
        )
    }

    private fun collisionWorkEstimate(
        shape: CollisionShape,
        width: Long,
        height: Long,
    ): Long = when (shape) {
            is CollisionShape.Ellipse,
            is CollisionShape.Circle,
            -> height * (2L * ceilLog2(width.coerceAtLeast(1L)) + 3L)
            is CollisionShape.Polygon -> {
                val vertices = shape.points.size.toLong()
                height * (6L * vertices * vertices + 8L * vertices)
            }
            is CollisionShape.Rectangle -> 1L
        }

    fun toRootRegion(
        shape: CollisionShape,
        budget: CollisionGeometryBudget = CollisionGeometryBudget.perCollisionDefault(),
    ): CollisionRegionPx = toStageRegion(shape, budget).translated(stageToRoot)

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

private data class IntrinsicRun(val left: Int, val right: Int)

private data class IntrinsicRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

private data class IntrinsicSegment(
    val firstX: Int,
    val firstY: Int,
    val secondX: Int,
    val secondY: Int,
)

private data class CompactCollisionGeometry(
    val rects: List<IntrinsicRect>,
    val segments: List<IntrinsicSegment>,
)

/** Streaming run compactor: no pixel-edge hash and no per-cell allocation. */
private class CompactCollisionBuilder {
    private var previousRuns: List<IntrinsicRun> = emptyList()
    private val activeRects = linkedMapOf<IntrinsicRun, Int>()
    private val activeVerticalSegments = linkedMapOf<Int, Int>()
    private val rects = mutableListOf<IntrinsicRect>()
    private val segments = mutableListOf<IntrinsicSegment>()

    fun appendRow(y: Int, runs: List<IntrinsicRun>) {
        appendHorizontalDifference(y, previousRuns, runs)

        val currentRunSet = runs.toSet()
        activeRects.keys.filterNot(currentRunSet::contains).forEach { run ->
            val start = activeRects.remove(run) ?: return@forEach
            rects += IntrinsicRect(run.left, start, run.right, y)
        }
        runs.forEach { run -> activeRects.putIfAbsent(run, y) }

        val currentSides = runs.flatMapTo(linkedSetOf()) { run -> listOf(run.left, run.right) }
        activeVerticalSegments.keys.filterNot(currentSides::contains).forEach { x ->
            val start = activeVerticalSegments.remove(x) ?: return@forEach
            segments += IntrinsicSegment(x, start, x, y)
        }
        currentSides.forEach { x -> activeVerticalSegments.putIfAbsent(x, y) }
        previousRuns = runs
    }

    fun finish(bottom: Int): CompactCollisionGeometry {
        appendHorizontalDifference(bottom, previousRuns, emptyList())
        activeRects.forEach { (run, top) ->
            rects += IntrinsicRect(run.left, top, run.right, bottom)
        }
        activeVerticalSegments.forEach { (x, top) ->
            segments += IntrinsicSegment(x, top, x, bottom)
        }
        return CompactCollisionGeometry(rects.toList(), segments.toList())
    }

    private fun appendHorizontalDifference(
        y: Int,
        previous: List<IntrinsicRun>,
        current: List<IntrinsicRun>,
    ) {
        val events = sortedMapOf<Int, Int>()
        fun toggle(x: Int, bit: Int) {
            events[x] = (events[x] ?: 0) xor bit
        }
        previous.forEach { run -> toggle(run.left, 1); toggle(run.right, 1) }
        current.forEach { run -> toggle(run.left, 2); toggle(run.right, 2) }
        var mask = 0
        var priorX: Int? = null
        events.forEach { (x, toggles) ->
            priorX?.let { left ->
                if (left < x && (mask == 1 || mask == 2)) {
                    segments += IntrinsicSegment(left, y, x, y)
                }
            }
            mask = mask xor toggles
            priorX = x
        }
    }
}

private fun convexRowRuns(
    shape: CollisionShape,
    y: Int,
    left: Int,
    right: Int,
): List<IntrinsicRun> {
    if (left >= right) return emptyList()
    val center = ((shape.bounds.left.toLong() + shape.bounds.right.toLong() - 1L).floorDiv(2L))
        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        .toInt()
        .coerceIn(left, right - 1)
    if (!shape.contains(IntOffset(center, y))) return emptyList()

    var low = left
    var high = center
    while (low < high) {
        val middle = low + (high - low) / 2
        if (shape.contains(IntOffset(middle, y))) high = middle else low = middle + 1
    }
    val first = low

    low = center
    high = right - 1
    while (low < high) {
        val middle = low + (high - low + 1) / 2
        if (shape.contains(IntOffset(middle, y))) low = middle else high = middle - 1
    }
    return listOf(IntrinsicRun(first, low + 1))
}

/**
 * Exact polygon scanline using rational edge thresholds. Membership is sampled
 * only between points where even-odd parity or inclusive lattice boundaries
 * can change; it never walks the full canvas width.
 */
private fun polygonRowRuns(
    shape: CollisionShape.Polygon,
    y: Int,
    left: Int,
    right: Int,
): List<IntrinsicRun> {
    if (left >= right) return emptyList()
    val cuts = sortedSetOf(left, right)
    fun addCut(value: BigInteger) {
        val low = BigInteger.valueOf(left.toLong())
        val high = BigInteger.valueOf(right.toLong())
        if (value < low || value > high) return
        cuts += value.toInt()
    }
    fun addNeighborhood(value: BigInteger) {
        addCut(value.subtract(BigInteger.ONE))
        addCut(value)
        addCut(value.add(BigInteger.ONE))
        addCut(value.add(BigInteger.valueOf(2L)))
    }

    shape.points.indices.forEach { index ->
        val first = shape.points[index]
        val second = shape.points[(index + 1) % shape.points.size]
        addNeighborhood(BigInteger.valueOf(first.x.toLong()))
        val minY = minOf(first.y, second.y)
        val maxY = maxOf(first.y, second.y)
        if (y !in minY..maxY) return@forEach
        if (first.y == second.y) {
            if (y == first.y) {
                addNeighborhood(BigInteger.valueOf(minOf(first.x, second.x).toLong()))
                addNeighborhood(BigInteger.valueOf(maxOf(first.x, second.x).toLong()))
            }
            return@forEach
        }
        var denominator = BigInteger.valueOf(second.y.toLong() - first.y.toLong())
        var numerator = BigInteger.valueOf(first.x.toLong()).multiply(denominator)
            .add(
                BigInteger.valueOf(y.toLong() - first.y.toLong())
                    .multiply(BigInteger.valueOf(second.x.toLong() - first.x.toLong())),
            )
        if (denominator.signum() < 0) {
            denominator = denominator.negate()
            numerator = numerator.negate()
        }
        addNeighborhood(floorDivide(numerator, denominator))
    }

    return buildList {
        var activeStart: Int? = null
        cuts.zipWithNext().forEach { (start, end) ->
            if (start >= end) return@forEach
            val accepted = shape.contains(IntOffset(start, y))
            if (accepted && activeStart == null) activeStart = start
            if (!accepted && activeStart != null) {
                add(IntrinsicRun(activeStart, start))
                activeStart = null
            }
            if (accepted && end == right) {
                add(IntrinsicRun(requireNotNull(activeStart), end))
                activeStart = null
            }
        }
    }
}

private fun floorDivide(numerator: BigInteger, positiveDenominator: BigInteger): BigInteger {
    val division = numerator.divideAndRemainder(positiveDenominator)
    return if (numerator.signum() < 0 && division[1] != BigInteger.ZERO) {
        division[0].subtract(BigInteger.ONE)
    } else {
        division[0]
    }
}

private fun ceilLog2(value: Long): Long {
    if (value <= 1L) return 0L
    return 64L - java.lang.Long.numberOfLeadingZeros(value - 1L).toLong()
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
    val keroSurfaceCanRepair: Boolean = false,
    val sakuraSurfaceCanRepair: Boolean = false,
) {
    fun transformFor(scope: SurfaceScope, intrinsicSize: IntSize): SurfaceTransformPx? {
        val (rounded, surfaceRegion, lane, canRepair) = when (scope) {
            SurfaceScope.KERO -> SurfaceBounds(keroSurface, keroSurfaceRegion, keroLane, keroSurfaceCanRepair)
            SurfaceScope.SAKURA -> SurfaceBounds(sakuraSurface, sakuraSurfaceRegion, sakuraLane, sakuraSurfaceCanRepair)
        }
        val bounds = repairCollapsedSurface(
            rounded = rounded,
            content = content,
            surfaceRegion = surfaceRegion,
            lane = lane,
            intrinsicSize = intrinsicSize,
            canRepair = canRepair,
        ) ?: return null
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
            val content = requireNotNull(layout.content.rounded())
            val keroLane = layout.keroLane.rounded()
            val sakuraLane = layout.sakuraLane.rounded()
            val keroBubble = layout.keroBubble.rounded()
            val sakuraBubble = layout.sakuraBubble.rounded()
            val keroSurfaceRegion = layout.keroSurfaceRegion.rounded()
            val sakuraSurfaceRegion = layout.sakuraSurfaceRegion.rounded()
            return StageLayoutPx(
                mode = layout.mode,
                content = content,
                keroLane = keroLane,
                sakuraLane = sakuraLane,
                keroBubble = keroBubble,
                sakuraBubble = sakuraBubble,
                keroSurfaceRegion = keroSurfaceRegion,
                sakuraSurfaceRegion = sakuraSurfaceRegion,
                keroSurface = layout.keroSurface.rounded(),
                sakuraSurface = layout.sakuraSurface.rounded(),
                stageToRoot = stageToRoot,
                keroSurfaceCanRepair = layout.keroSurface.hasPositiveArea(),
                sakuraSurfaceCanRepair = layout.sakuraSurface.hasPositiveArea(),
            )
        }
    }
}

private data class SurfaceBounds(
    val rounded: IntRect?,
    val surfaceRegion: IntRect?,
    val lane: IntRect?,
    val canRepair: Boolean,
)

private fun StageDpRect?.hasPositiveArea(): Boolean =
    this != null && width.value > 0f && height.value > 0f

/**
 * Materializes a rounded positive authored surface only when its intrinsic canvas
 * can still occupy an exact uniform-scale integer rectangle inside every policy
 * constraint. The returned rectangle is the shared renderer/input/overlay transform.
 */
private fun repairCollapsedSurface(
    rounded: IntRect?,
    content: IntRect,
    surfaceRegion: IntRect?,
    lane: IntRect?,
    intrinsicSize: IntSize,
    canRepair: Boolean,
): IntRect? {
    if (rounded == null || rounded.width > 0 && rounded.height > 0) return rounded
    if (!canRepair || intrinsicSize.width <= 0 || intrinsicSize.height <= 0) return rounded
    val constraints = listOfNotNull(content, surfaceRegion, lane)
    val aspectRatio = intrinsicSize.reducedAspectRatio()
    fun IntRect.isContained() = constraints.all { constraint ->
        left >= constraint.left && top >= constraint.top &&
            right <= constraint.right && bottom <= constraint.bottom
    }
    fun multiplier(extent: Int, intrinsic: Int): Long =
        ((extent.coerceAtLeast(0).toLong() + intrinsic - 1L) / intrinsic).coerceAtLeast(1L)

    val multiplier = maxOf(
        multiplier(rounded.width, aspectRatio.width),
        multiplier(rounded.height, aspectRatio.height),
    )
    val width = aspectRatio.width.toLong() * multiplier
    val height = aspectRatio.height.toLong() * multiplier
    if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) return rounded
    fun bounds(left: Long, top: Long): IntRect? {
        val right = left + width
        val bottom = top + height
        if (left < Int.MIN_VALUE || top < Int.MIN_VALUE || right > Int.MAX_VALUE || bottom > Int.MAX_VALUE) return null
        return IntRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }
    return listOfNotNull(
        bounds(rounded.left.toLong(), rounded.top.toLong()),
        bounds(rounded.right.toLong() - width, rounded.top.toLong()),
        bounds(rounded.left.toLong(), rounded.bottom.toLong() - height),
        bounds(rounded.right.toLong() - width, rounded.bottom.toLong() - height),
    ).firstOrNull(IntRect::isContained) ?: run {
        val commonLeft = constraints.maxOf { it.left }.toLong()
        val commonTop = constraints.maxOf { it.top }.toLong()
        val commonRight = constraints.minOf { it.right }.toLong()
        val commonBottom = constraints.minOf { it.bottom }.toLong()
        val maxLeft = commonRight - width
        val maxTop = commonBottom - height
        if (maxLeft < commonLeft || maxTop < commonTop) {
            rounded
        } else {
            bounds(
                rounded.left.toLong().coerceIn(commonLeft, maxLeft),
                rounded.top.toLong().coerceIn(commonTop, maxTop),
            ) ?: rounded
        }
    }
}

private fun IntSize.reducedAspectRatio(): IntSize {
    var dividend = width
    var divisor = height
    while (divisor != 0) {
        val remainder = dividend % divisor
        dividend = divisor
        divisor = remainder
    }
    return IntSize(width / dividend, height / dividend)
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
