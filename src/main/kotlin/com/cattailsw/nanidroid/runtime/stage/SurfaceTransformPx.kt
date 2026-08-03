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
