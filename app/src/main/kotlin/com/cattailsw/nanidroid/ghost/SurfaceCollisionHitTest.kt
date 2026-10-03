package com.cattailsw.nanidroid.ghost

object SurfaceCollisionHitTest {
    fun find(collisions: List<SurfaceCollision>, x: Int, y: Int): String =
        collisions.firstOrNull {
            x >= it.left && x < it.right && y >= it.top && y < it.bottom
        }?.name.orEmpty()
}
