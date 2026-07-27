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
