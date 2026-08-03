@file:JvmName("SurfaceHitTest")

package com.cattailsw.nanidroid

/** Platform-neutral collision lookup with Android Rect-compatible edge semantics. */
fun findCollisionId(definition: SurfaceDefinition?, x: Int, y: Int): Int =
    definition?.collisions
        ?.firstOrNull { collision ->
            collision.shape.contains(androidx.compose.ui.unit.IntOffset(x, y))
        }
        ?.id
        ?: NO_COLLISION

const val NO_COLLISION = -1

/**
 * Pure semantic target lookup for a point which has already been transformed
 * into source-pixel coordinates. Collision rectangles deliberately win before
 * pixel opacity: the legacy view sent an OnMouseDoubleClick for a declared
 * collision even when that part of the bitmap was color-key transparent.
 */
fun findSurfaceHit(
    definition: SurfaceDefinition?,
    x: Int,
    y: Int,
    isOpaque: (x: Int, y: Int) -> Boolean?,
): SurfaceHitTarget {
    val collision = definition?.collisions?.firstOrNull {
        it.shape.contains(androidx.compose.ui.unit.IntOffset(x, y))
    }
    if (collision != null) return SurfaceHitTarget.Collision(collision.id, collision.identifier)
    return when (isOpaque(x, y)) {
        true -> SurfaceHitTarget.OpaquePixel
        false -> SurfaceHitTarget.TransparentPixel
        null -> SurfaceHitTarget.PixelUnavailable
    }
}

/** No Android/Compose types cross this boundary. */
sealed interface SurfaceHitTarget {
    data class Collision(val id: Int, val identifier: String) : SurfaceHitTarget
    data object OpaquePixel : SurfaceHitTarget
    data object TransparentPixel : SurfaceHitTarget
    data object PixelUnavailable : SurfaceHitTarget
}
