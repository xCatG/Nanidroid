package com.cattailsw.nanidroid.surface

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import com.cattailsw.nanidroid.SurfaceCollision
import java.math.BigInteger

/** A platform-neutral path description which can be rendered by a debug overlay. */
sealed interface CollisionPath {
    val bounds: IntRect
    fun contains(point: IntOffset): Boolean

    data class Rectangle(override val bounds: IntRect) : CollisionPath {
        override fun contains(point: IntOffset): Boolean = containsRectangle(bounds, point)
    }

    data class Ellipse(override val bounds: IntRect) : CollisionPath {
        override fun contains(point: IntOffset): Boolean = containsEllipse(bounds, point)
    }

    data class Circle(val center: IntOffset, val radius: Int) : CollisionPath {
        override val bounds: IntRect = circleBounds(center, radius)
        override fun contains(point: IntOffset): Boolean = containsCircle(center, radius, point)
    }

    data class Polygon(
        val points: List<IntOffset>,
        val fillRule: CollisionFillRule,
    ) : CollisionPath {
        override val bounds: IntRect = polygonBounds(points)
        override fun contains(point: IntOffset): Boolean =
            containsRectangle(bounds, point) && containsPolygon(points, point)
    }
}

enum class CollisionFillRule { EVEN_ODD }

/** One canonical model used by both hit testing and collision overlays. */
sealed interface CollisionShape {
    val bounds: IntRect
    val path: CollisionPath
    fun contains(point: IntOffset): Boolean
    fun representativePoint(): IntOffset?

    data class Rectangle(override val bounds: IntRect) : CollisionShape {
        override val path: CollisionPath = CollisionPath.Rectangle(bounds)
        override fun contains(point: IntOffset): Boolean = path.contains(point)
        override fun representativePoint(): IntOffset? =
            IntOffset(bounds.left, bounds.top).takeIf(::contains)

        companion object {
            fun fromAuthored(x1: Int, y1: Int, x2: Int, y2: Int): Rectangle =
                requireNotNull(fromAuthoredOrNull(x1, y1, x2, y2))

            fun fromAuthoredOrNull(x1: Int, y1: Int, x2: Int, y2: Int): Rectangle? =
                inclusiveBoundsOrNull(x1, y1, x2, y2)?.let(::Rectangle)
        }
    }

    data class Ellipse(override val bounds: IntRect) : CollisionShape {
        override val path: CollisionPath = CollisionPath.Ellipse(bounds)
        override fun contains(point: IntOffset): Boolean = path.contains(point)
        override fun representativePoint(): IntOffset? = IntOffset(
            midpoint(bounds.left, bounds.right - 1),
            midpoint(bounds.top, bounds.bottom - 1),
        ).takeIf(::contains)

        companion object {
            fun fromAuthored(x1: Int, y1: Int, x2: Int, y2: Int): Ellipse =
                requireNotNull(fromAuthoredOrNull(x1, y1, x2, y2))

            fun fromAuthoredOrNull(x1: Int, y1: Int, x2: Int, y2: Int): Ellipse? =
                inclusiveBoundsOrNull(x1, y1, x2, y2)?.let(::Ellipse)
        }
    }

    data class Circle(val center: IntOffset, val radius: Int) : CollisionShape {
        init {
            require(radius >= 0)
            circleBounds(center, radius)
        }

        override val bounds: IntRect = circleBounds(center, radius)
        override val path: CollisionPath = CollisionPath.Circle(center, radius)
        override fun contains(point: IntOffset): Boolean = path.contains(point)
        override fun representativePoint(): IntOffset = center

        companion object {
            fun fromAuthored(centerX: Int, centerY: Int, radius: Int): Circle =
                requireNotNull(fromAuthoredOrNull(centerX, centerY, radius))

            fun fromAuthoredOrNull(centerX: Int, centerY: Int, radius: Int): Circle? =
                if (radius < 0) null else runCatching {
                    Circle(IntOffset(centerX, centerY), radius)
                }.getOrNull()
        }
    }

    data class Polygon(val points: List<IntOffset>) : CollisionShape {
        init {
            require(points.size >= 3)
            require(points.size <= MAX_POLYGON_VERTICES)
        }

        override val bounds: IntRect = polygonBounds(points)
        override val path: CollisionPath = CollisionPath.Polygon(points, CollisionFillRule.EVEN_ODD)
        override fun contains(point: IntOffset): Boolean = path.contains(point)
        override fun representativePoint(): IntOffset? = points.firstOrNull()?.takeIf(::contains)
    }

    companion object {
        const val MAX_POLYGON_VERTICES = 256
    }
}

internal sealed interface ParsedCollision {
    data object NotCollision : ParsedCollision
    data class Valid(val collision: SurfaceCollision) : ParsedCollision
    data class Invalid(val reason: SurfaceDiagnosticReason) : ParsedCollision
}

/** Parses exactly one collision declaration. It is intentionally independent of surface fan-out. */
internal object CollisionGeometryParser {
    @Volatile
    internal var parseCount: Int = 0

    fun parse(text: String, authoredOrder: Int): ParsedCollision {
        val trimmed = text.trim()
        if (ANIMATION_COLLISION.matches(trimmed)) return ParsedCollision.Invalid(SurfaceDiagnosticReason.UNSUPPORTED)
        val fields = trimmed.split(',')
        val key = fields.firstOrNull()?.trim().orEmpty()
        val collisionEx = COLLISION_EX.matchEntire(key)
        val collision = COLLISION.matchEntire(key)
        if (collisionEx == null && collision == null) return ParsedCollision.NotCollision
        parseCount++
        val id = (collisionEx ?: collision)!!.groupValues[1].toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: return ParsedCollision.Invalid(SurfaceDiagnosticReason.ENTRY)
        return if (collisionEx != null) parseExtended(id, fields, authoredOrder) else parseLegacy(id, fields, authoredOrder)
    }

    private fun parseLegacy(id: Int, fields: List<String>, authoredOrder: Int): ParsedCollision {
        if (fields.size < 6) return ParsedCollision.Invalid(SurfaceDiagnosticReason.ENTRY)
        val coordinates = fields.subList(1, 5).map { it.trim().toIntOrNull() }
        if (coordinates.any { it == null }) return ParsedCollision.Invalid(SurfaceDiagnosticReason.ENTRY)
        val shape = CollisionShape.Rectangle.fromAuthoredOrNull(
            coordinates[0]!!,
            coordinates[1]!!,
            coordinates[2]!!,
            coordinates[3]!!,
        ) ?: return ParsedCollision.Invalid(SurfaceDiagnosticReason.ENTRY)
        return ParsedCollision.Valid(
            SurfaceCollision(id, fields.drop(5).joinToString(",").trim(), shape, authoredOrder),
        )
    }

    private fun parseExtended(id: Int, fields: List<String>, authoredOrder: Int): ParsedCollision {
        if (fields.size < 3) return ParsedCollision.Invalid(SurfaceDiagnosticReason.ENTRY)
        val identifier = fields[1].trim()
        val kind = fields[2].trim().lowercase()
        val arguments = fields.drop(3).map { it.trim() }
        val shape = when (kind) {
            "rectangle", "rect" -> rectangle(arguments)
            "ellipse" -> ellipse(arguments)
            "circle" -> circle(arguments)
            "polygon" -> polygon(arguments)
            "region" -> return ParsedCollision.Invalid(SurfaceDiagnosticReason.UNSUPPORTED)
            else -> return ParsedCollision.Invalid(SurfaceDiagnosticReason.UNSUPPORTED)
        } ?: return ParsedCollision.Invalid(SurfaceDiagnosticReason.ENTRY)
        return ParsedCollision.Valid(SurfaceCollision(id, identifier, shape, authoredOrder))
    }

    private fun rectangle(arguments: List<String>): CollisionShape.Rectangle? =
        arguments.takeIf { it.size == 4 }?.map { it.toIntOrNull() }?.let { values ->
            if (values.any { it == null }) null else CollisionShape.Rectangle.fromAuthoredOrNull(
                values[0]!!, values[1]!!, values[2]!!, values[3]!!,
            )
        }

    private fun ellipse(arguments: List<String>): CollisionShape.Ellipse? =
        arguments.takeIf { it.size == 4 }?.map { it.toIntOrNull() }?.let { values ->
            if (values.any { it == null }) null else CollisionShape.Ellipse.fromAuthoredOrNull(
                values[0]!!, values[1]!!, values[2]!!, values[3]!!,
            )
        }

    private fun circle(arguments: List<String>): CollisionShape.Circle? =
        arguments.takeIf { it.size == 3 }?.map { it.toIntOrNull() }?.let { values ->
            if (values.any { it == null }) null else CollisionShape.Circle.fromAuthoredOrNull(
                values[0]!!, values[1]!!, values[2]!!,
            )
        }

    private fun polygon(arguments: List<String>): CollisionShape.Polygon? {
        if (arguments.size !in 6..(CollisionShape.MAX_POLYGON_VERTICES * 2) || arguments.size % 2 != 0) return null
        val values = arguments.map { it.toIntOrNull() }
        if (values.any { it == null }) return null
        return runCatching {
            CollisionShape.Polygon(values.chunked(2) { IntOffset(it[0]!!, it[1]!!) })
        }.getOrNull()
    }

    private val COLLISION = Regex("collision([+-]?\\d+)", RegexOption.IGNORE_CASE)
    private val COLLISION_EX = Regex("collisionex([+-]?\\d+)", RegexOption.IGNORE_CASE)
    private val ANIMATION_COLLISION = Regex("animation[^,]*\\.collision(?:ex)?[+-]?\\d+.*", RegexOption.IGNORE_CASE)
}

private fun inclusiveBoundsOrNull(x1: Int, y1: Int, x2: Int, y2: Int): IntRect? = runCatching {
    val left = minOf(x1, x2)
    val top = minOf(y1, y2)
    val right = Math.addExact(maxOf(x1, x2), 1)
    val bottom = Math.addExact(maxOf(y1, y2), 1)
    IntRect(left, top, right, bottom)
}.getOrNull()

private fun circleBounds(center: IntOffset, radius: Int): IntRect {
    val left = Math.subtractExact(center.x, radius)
    val top = Math.subtractExact(center.y, radius)
    val right = Math.addExact(Math.addExact(center.x, radius), 1)
    val bottom = Math.addExact(Math.addExact(center.y, radius), 1)
    return IntRect(left, top, right, bottom)
}

private fun polygonBounds(points: List<IntOffset>): IntRect = IntRect(
    points.minOf { it.x },
    points.minOf { it.y },
    Math.addExact(points.maxOf { it.x }, 1),
    Math.addExact(points.maxOf { it.y }, 1),
)

private fun midpoint(low: Int, high: Int): Int = (low.toLong() + high.toLong()).floorDiv(2L).toInt()

private fun containsRectangle(bounds: IntRect, point: IntOffset): Boolean =
    point.x >= bounds.left && point.x < bounds.right && point.y >= bounds.top && point.y < bounds.bottom

private fun containsCircle(center: IntOffset, radius: Int, point: IntOffset): Boolean {
    val dx = point.x.toLong() - center.x.toLong()
    val dy = point.y.toLong() - center.y.toLong()
    return square(dx).add(square(dy)) <= square(radius.toLong())
}

private fun containsEllipse(bounds: IntRect, point: IntOffset): Boolean {
    if (!containsRectangle(bounds, point)) return false
    val left = bounds.left.toLong()
    val top = bounds.top.toLong()
    val right = bounds.right.toLong() - 1L
    val bottom = bounds.bottom.toLong() - 1L
    val width = right - left
    val height = bottom - top
    if (width == 0L && height == 0L) return point.x.toLong() == left && point.y.toLong() == top
    if (width == 0L) return point.x.toLong() == left
    if (height == 0L) return point.y.toLong() == top
    val twiceX = point.x.toLong() * 2L - left - right
    val twiceY = point.y.toLong() * 2L - top - bottom
    return square(twiceX).multiply(square(height))
        .add(square(twiceY).multiply(square(width))) <= square(width).multiply(square(height))
}

private fun containsPolygon(points: List<IntOffset>, point: IntOffset): Boolean {
    if (points.indices.any { index -> pointOnSegment(point, points[index], points[(index + 1) % points.size]) }) return true
    var inside = false
    val px = point.x.toLong()
    val py = point.y.toLong()
    points.indices.forEach { index ->
        val first = points[index]
        val second = points[(index + 1) % points.size]
        val ay = first.y.toLong()
        val by = second.y.toLong()
        if ((ay > py) != (by > py)) {
            val ax = first.x.toLong()
            val bx = second.x.toLong()
            val left = BigInteger.valueOf(px - ax).multiply(BigInteger.valueOf(by - ay))
            val right = BigInteger.valueOf(bx - ax).multiply(BigInteger.valueOf(py - ay))
            val crossesRight = if (by > ay) left < right else left > right
            if (crossesRight) inside = !inside
        }
    }
    return inside
}

private fun pointOnSegment(point: IntOffset, first: IntOffset, second: IntOffset): Boolean {
    val px = point.x.toLong()
    val py = point.y.toLong()
    val ax = first.x.toLong()
    val ay = first.y.toLong()
    val bx = second.x.toLong()
    val by = second.y.toLong()
    val cross = BigInteger.valueOf(px - ax).multiply(BigInteger.valueOf(by - ay))
        .subtract(BigInteger.valueOf(py - ay).multiply(BigInteger.valueOf(bx - ax)))
    return cross == BigInteger.ZERO && px in minOf(ax, bx)..maxOf(ax, bx) && py in minOf(ay, by)..maxOf(ay, by)
}

private fun square(value: Long): BigInteger = BigInteger.valueOf(value).pow(2)
