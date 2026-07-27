@file:JvmName("SurfaceHitTest")

package com.cattailsw.nanidroid

/** Platform-neutral collision lookup with Android Rect-compatible edge semantics. */
fun findCollisionId(definition: SurfaceDefinition?, x: Int, y: Int): Int =
    definition?.collisions
        ?.firstOrNull { collision ->
            x >= collision.x && x < collision.x + collision.width &&
                y >= collision.y && y < collision.y + collision.height
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
    val collisionId = findCollisionId(definition, x, y)
    if (collisionId != NO_COLLISION) return SurfaceHitTarget.Collision(collisionId)
    return when (isOpaque(x, y)) {
        true -> SurfaceHitTarget.OpaquePixel
        false -> SurfaceHitTarget.TransparentPixel
        null -> SurfaceHitTarget.PixelUnavailable
    }
}

/** No Android/Compose types cross this boundary. */
sealed interface SurfaceHitTarget {
    data class Collision(val id: Int) : SurfaceHitTarget
    data object OpaquePixel : SurfaceHitTarget
    data object TransparentPixel : SurfaceHitTarget
    data object PixelUnavailable : SurfaceHitTarget
}
