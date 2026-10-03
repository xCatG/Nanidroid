package com.cattailsw.nanidroid.ghost

import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceCollisionHitTestTest {
    private val collisions = listOf(
        SurfaceCollision(3, -20, 10, 20, 40, "first"),
        SurfaceCollision(1, 0, 20, 30, 50, "second"),
    )

    @Test fun firstDeclaredRectangleWinsOverlap() {
        assertEquals("first", SurfaceCollisionHitTest.find(collisions, 5, 25))
    }

    @Test fun leftTopInclusiveAndRightBottomExclusive() {
        assertEquals("first", SurfaceCollisionHitTest.find(collisions, -20, 10))
        assertEquals("", SurfaceCollisionHitTest.find(collisions, 20, 15))
        assertEquals("", SurfaceCollisionHitTest.find(collisions, -10, 40))
        assertEquals("second", SurfaceCollisionHitTest.find(collisions, 20, 25))
    }

    @Test fun missingRectangleReturnsEmptyName() {
        assertEquals("", SurfaceCollisionHitTest.find(collisions, -21, 10))
        assertEquals("", SurfaceCollisionHitTest.find(emptyList(), 0, 0))
    }
}
