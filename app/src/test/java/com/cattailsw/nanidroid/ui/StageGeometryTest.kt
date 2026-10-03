package com.cattailsw.nanidroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StageGeometryTest {
    @Test fun oneCeilingStillFitsCombinedWidthAndTallerHeight() {
        assertEquals(0.5f, StageGeometry.scaleFor(250, 144, 235, 200, 243, 100, 1f), 0.001f)
        assertEquals(0.8f, StageGeometry.scaleFor(250, 144, 235, 200, 388, 1000, 1f), 0.001f)
        assertEquals(1f, StageGeometry.scaleFor(250, 144, 235, 200, 1000, 1000, 1f), 0f)
    }

    @Test fun shortWindowSideChoosesBoundaryBucketsAndSurvivesRotation() {
        assertEquals(1f, StageGeometry.logicalArtCeiling(399, 900, 2f), 0f)
        assertEquals(1.5f, StageGeometry.logicalArtCeiling(400, 900, 2f), 0f)
        assertEquals(1.5f, StageGeometry.logicalArtCeiling(599, 900, 2f), 0f)
        assertEquals(2f, StageGeometry.logicalArtCeiling(600, 900, 2f), 0f)
        assertEquals(1.96875f, StageGeometry.logicalArtCeiling(411, 914, 2.625f), 0f)
        assertEquals(1.96875f, StageGeometry.logicalArtCeiling(914, 411, 2.625f), 0f)
    }

    @Test fun logicalArtworkHasSameDpHeightAtTwoDensitiesWhenUncapped() {
        val at420 = StageGeometry.scaleFor(269, 378, 239, 380, 1080, 1900,
            StageGeometry.logicalArtCeiling(411, 914, 2.625f))
        val at280 = StageGeometry.scaleFor(269, 378, 239, 380, 720, 1267,
            StageGeometry.logicalArtCeiling(411, 914, 1.75f))
        assertEquals(1.96875f, at420, 0.0001f)
        assertEquals(1.3125f, at280, 0.0001f)
        assertEquals(283.5f, 378 * at420 / 2.625f, 0.01f)
        assertEquals(283.5f, 378 * at280 / 1.75f, 0.01f)
    }

    @Test fun twoSpeakerArtUsesCompactTallAndTabletTargets() {
        assertEquals(1f, StageGeometry.scaleFor(269, 378, 239, 380, 720, 880,
            StageGeometry.logicalArtCeiling(360, 640, 2f)), 0f)
        assertEquals(1.96875f, StageGeometry.scaleFor(269, 378, 239, 380, 1080, 1900,
            StageGeometry.logicalArtCeiling(411, 914, 2.625f)), 0f)
        assertEquals(2f, StageGeometry.scaleFor(269, 378, 239, 380, 1600, 2200,
            StageGeometry.logicalArtCeiling(800, 1280, 2f)), 0f)
    }

    @Test fun commonFitCapsEarthquakeAndCompactLargeFontLandscape() {
        assertEquals(1080f / 1194f, StageGeometry.scaleFor(772, 535, 422, 377, 1080, 1900,
            StageGeometry.logicalArtCeiling(411, 914, 2.625f)), 0.0001f)
        assertEquals(1600f / 1194f, StageGeometry.scaleFor(772, 535, 422, 377, 1600, 2200,
            StageGeometry.logicalArtCeiling(800, 1280, 2f)), 0.0001f)
        assertEquals(200f / 380f, StageGeometry.scaleFor(269, 378, 239, 380, 1280, 200,
            StageGeometry.logicalArtCeiling(640, 360, 2f)), 0.0001f)
    }

    @Test fun inverseMapsSurfaceRectangleToLocalPixels() {
        val rectangle = StageGeometry.SurfaceRectangle(100f, 20f, 117.5f, 150f, 235, 200)
        assertEquals(StageGeometry.Pixel(117, 100), rectangle.toLocal(158.75f, 95f))
        assertNull(rectangle.toLocal(99f, 95f))
    }

    @Test fun roundedDrawBoundsAndHitBoundsMatchAtEdges() {
        val widthRoundedUp = StageGeometry.fittedRectangle(235, 200, 118, 100)
        assertEquals(0f, widthRoundedUp.left, 0f)
        assertEquals(118f, widthRoundedUp.width, 0f)
        assertEquals(StageGeometry.Pixel(0, 100), widthRoundedUp.toLocal(0.1f, 50f))
        assertEquals(StageGeometry.Pixel(234, 100), widthRoundedUp.toLocal(117.9f, 50f))
        assertNull(widthRoundedUp.toLocal(118f, 50f))

        val widthInset = StageGeometry.fittedRectangle(235, 200, 119, 100)
        assertEquals(0f, widthInset.left, 0f)
        assertEquals(118f, widthInset.width, 0f)
        assertNull(widthInset.toLocal(118.1f, 50f))

        val heightInset = StageGeometry.fittedRectangle(250, 144, 250, 145)
        assertEquals(0f, heightInset.top, 0f)
        assertEquals(144f, heightInset.height, 0f)
        assertEquals(StageGeometry.Pixel(125, 0), heightInset.toLocal(125f, 0.1f))
        assertNull(heightInset.toLocal(125f, 144.1f))
    }

    @Test fun authoredPixelsIncludeNegativeOriginAfterScalingAndLetterbox() {
        val rectangle = StageGeometry.fittedRectangle(100, 50, 200, 200)
        assertEquals(StageGeometry.Pixel(-20, 10), rectangle.toAuthored(0f, 50f, -20, 10))
        assertEquals(StageGeometry.Pixel(79, 59), rectangle.toAuthored(199.9f, 149.9f, -20, 10))
        assertNull(rectangle.toAuthored(50f, 49.9f, -20, 10))
        assertNull(rectangle.toAuthored(200f, 100f, -20, 10))
    }

    @Test fun movedSurfaceKeepsSameLocalAuthoredCoordinates() {
        val rectangle = StageGeometry.fittedRectangle(100, 50, 200, 100)
        assertEquals(StageGeometry.Pixel(30, 20), rectangle.toAuthored(100f, 20f, -20, 10))
    }
}
