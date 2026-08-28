package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.unit.dp
import org.junit.Assert
import org.junit.Test

/** Compose stage contracts that supersede the legacy View-era pixel facade formulas. */
class GhostStageLayoutPolicyTest {
    @Test
    fun migrationInvariant_zeroSafeBoundsPublishesTinyFallbackWithoutInteractiveRects() {
        val layout = GhostStageLayoutPolicy.calculate(environment(0, 720))

        Assert.assertEquals(StageMode.TINY, layout.mode)
        Assert.assertTrue(layout.tinyFallback)
        Assert.assertNull(layout.keroLane)
        Assert.assertNull(layout.sakuraLane)
        Assert.assertNull(layout.keroBubble)
        Assert.assertNull(layout.sakuraBubble)
        Assert.assertNull(layout.keroSurfaceRegion)
        Assert.assertNull(layout.sakuraSurfaceRegion)
    }

    @Test
    fun migrationInvariant_wideStageUsesPhysicalSpeakerLanesAndAdaptiveBubbleBands() {
        val layout = GhostStageLayoutPolicy.calculate(environment(800, 600))

        Assert.assertEquals(StageMode.STANDARD, layout.mode)
        assertRect(0f, 0f, 400f, 600f, requireNotNull(layout.keroLane))
        assertRect(400f, 0f, 800f, 600f, requireNotNull(layout.sakuraLane))
        assertRect(0f, 0f, 400f, 216f, requireNotNull(layout.keroBubble))
        assertRect(400f, 0f, 800f, 216f, requireNotNull(layout.sakuraBubble))
    }

    @Test
    fun migrationInvariant_shortKeroRetainsAdaptiveBubbleAndSurfaceRegions() {
        val layout = GhostStageLayoutPolicy.calculate(environment(800, 600))

        assertRect(0f, 0f, 400f, 216f, requireNotNull(layout.keroBubble))
        assertRect(0f, 216f, 400f, 600f, requireNotNull(layout.keroSurfaceRegion))
        assertRect(400f, 216f, 800f, 600f, requireNotNull(layout.sakuraSurfaceRegion))
    }

    @Test
    fun `standard stage uses physical equal lanes and fixed bubble surface bands`() {
        val layout = GhostStageLayoutPolicy.calculate(environment(360, 720))

        Assert.assertEquals(StageMode.STANDARD, layout.mode)
        assertRect(0f, 0f, 180f, 720f, requireNotNull(layout.keroLane))
        assertRect(180f, 0f, 360f, 720f, requireNotNull(layout.sakuraLane))
        assertRect(0f, 0f, 180f, 259.2f, requireNotNull(layout.keroBubble))
        assertRect(180f, 0f, 360f, 259.2f, requireNotNull(layout.sakuraBubble))
        assertRect(0f, 259.2f, 180f, 720f, requireNotNull(layout.keroSurfaceRegion))
        assertRect(180f, 259.2f, 360f, 720f, requireNotNull(layout.sakuraSurfaceRegion))
    }

    @Test
    fun `compact stage uses center bubbles and physical outer speaker lanes`() {
        val narrow = GhostStageLayoutPolicy.calculate(environment(500, 300))
        val thirds = GhostStageLayoutPolicy.calculate(environment(540, 300))

        assertRect(0f, 0f, 160f, 300f, requireNotNull(narrow.keroLane))
        assertRect(160f, 0f, 340f, 150f, requireNotNull(narrow.keroBubble))
        assertRect(160f, 150f, 340f, 300f, requireNotNull(narrow.sakuraBubble))
        assertRect(340f, 0f, 500f, 300f, requireNotNull(narrow.sakuraLane))
        assertRect(0f, 0f, 180f, 300f, requireNotNull(thirds.keroLane))
        assertRect(180f, 0f, 360f, 150f, requireNotNull(thirds.keroBubble))
        assertRect(360f, 0f, 540f, 300f, requireNotNull(thirds.sakuraLane))
    }

    @Test
    fun `content cap centers at 960 in standard and compact stages`() {
        val standard = GhostStageLayoutPolicy.calculate(environment(1280, 800))
        val compact = GhostStageLayoutPolicy.calculate(environment(1280, 400))

        assertRect(160f, 0f, 1120f, 800f, standard.content)
        assertRect(160f, 0f, 1120f, 400f, compact.content)
    }

    @Test
    fun `compact center transitions exactly below at and above 540`() {
        val below = GhostStageLayoutPolicy.calculate(environment(539, 300))
        val at = GhostStageLayoutPolicy.calculate(environment(540, 300))
        val above = GhostStageLayoutPolicy.calculate(environment(541, 300))

        Assert.assertEquals(180f, requireNotNull(below.keroBubble).width.value, 0.001f)
        Assert.assertEquals(180f, requireNotNull(at.keroBubble).width.value, 0.001f)
        Assert.assertEquals(541f / 3f, requireNotNull(above.keroBubble).width.value, 0.001f)
    }

    @Test
    fun `content width transitions exactly below at and above 960`() {
        val below = GhostStageLayoutPolicy.calculate(environment(959, 800))
        val at = GhostStageLayoutPolicy.calculate(environment(960, 800))
        val above = GhostStageLayoutPolicy.calculate(environment(961, 800))

        Assert.assertEquals(959f, below.content.width.value, 0.001f)
        Assert.assertEquals(960f, at.content.width.value, 0.001f)
        Assert.assertEquals(960f, above.content.width.value, 0.001f)
        Assert.assertEquals(0.5f, above.content.left.value, 0.001f)
    }

    @Test
    fun `physical speaker lanes never mirror in RTL`() {
        val ltr = GhostStageLayoutPolicy.calculate(environment(720, 360, StageLayoutDirection.LTR))
        val rtl = GhostStageLayoutPolicy.calculate(environment(720, 360, StageLayoutDirection.RTL))

        Assert.assertEquals(ltr.keroLane, rtl.keroLane)
        Assert.assertEquals(ltr.sakuraLane, rtl.sakuraLane)
        Assert.assertTrue(requireNotNull(rtl.keroLane).left < requireNotNull(rtl.sakuraLane).left)
    }

    @Test
    fun `tiny layout publishes no invisible interactive rectangles`() {
        val layout = GhostStageLayoutPolicy.calculate(environment(480, 230))

        Assert.assertEquals(StageMode.TINY, layout.mode)
        Assert.assertTrue(layout.tinyFallback)
        Assert.assertNull(layout.keroLane)
        Assert.assertNull(layout.sakuraLane)
        Assert.assertNull(layout.keroBubble)
        Assert.assertNull(layout.sakuraBubble)
        Assert.assertNull(layout.keroSurface)
        Assert.assertNull(layout.sakuraSurface)
    }

    private fun environment(
        width: Int,
        height: Int,
        direction: StageLayoutDirection = StageLayoutDirection.LTR,
    ) = StageEnvironment(
        safeBounds = StageDpRect(0.dp, 0.dp, width.dp, height.dp),
        density = 1f,
        fontScale = 1f,
        canonicalAppBarHeight = 0.dp,
        posture = StagePosture.FLAT,
        displayFeatures = emptyList<StageDisplayFeature>(),
        inputCapabilities = StageInputCapabilities(true, true, true, true),
        layoutDirection = direction,
    )

    private fun assertRect(left: Float, top: Float, right: Float, bottom: Float, actual: StageDpRect) {
        Assert.assertEquals(left, actual.left.value, 0.001f)
        Assert.assertEquals(top, actual.top.value, 0.001f)
        Assert.assertEquals(right, actual.right.value, 0.001f)
        Assert.assertEquals(bottom, actual.bottom.value, 0.001f)
    }
}
