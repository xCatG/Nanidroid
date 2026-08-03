package com.cattailsw.nanidroid.compose.stage

import org.junit.Assert.assertTrue
import org.junit.Test

class CollisionOverlayGeometryTest {
    @Test
    fun `label baseline is clipped for every surface height from zero through twenty three`() {
        for (height in 0..23) {
            val baseline = collisionLabelBaselinePx(
                anchorTop = -40f,
                textSize = 24f,
                canvasHeight = height.toFloat(),
            )
            assertTrue("height=$height baseline=$baseline", baseline in 0f..height.toFloat())

            val lowerBaseline = collisionLabelBaselinePx(
                anchorTop = 100f,
                textSize = 24f,
                canvasHeight = height.toFloat(),
            )
            assertTrue("height=$height lowerBaseline=$lowerBaseline", lowerBaseline in 0f..height.toFloat())
        }
    }
}
