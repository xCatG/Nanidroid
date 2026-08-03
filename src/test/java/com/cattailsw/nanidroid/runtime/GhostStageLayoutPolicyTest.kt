package com.cattailsw.nanidroid.runtime

import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.runtime.stage.GhostStageLayoutPolicy as AdaptiveStageLayoutPolicy
import com.cattailsw.nanidroid.runtime.stage.StageDisplayFeature
import com.cattailsw.nanidroid.runtime.stage.StageDpRect
import com.cattailsw.nanidroid.runtime.stage.StageEnvironment
import com.cattailsw.nanidroid.runtime.stage.StageInputCapabilities
import com.cattailsw.nanidroid.runtime.stage.StageLayoutDirection
import com.cattailsw.nanidroid.runtime.stage.StageMode
import com.cattailsw.nanidroid.runtime.stage.StagePosture
import org.junit.Assert
import org.junit.Test

/** Characterizes the legacy stage geometry before Compose becomes its owner.  */
class GhostStageLayoutPolicyTest {
    @Test
    fun requiredMigrationInvariant_unmeasuredStageDoesNotProduceLayout() {
        Assert.assertNull(
            GhostStageLayoutPolicy.calculate(
                GhostStageSize(0, 600),
                GhostStageSize(300, 400),
                GhostStageSize(200, 200)
            )
        )
    }

    @Test
    fun requiredMigrationInvariant_wideStageKeepsOriginalSurfaceSizesAndSplitBalloons() {
        val layout = requireNotNull(
            GhostStageLayoutPolicy.calculate(
                GhostStageSize(800, 600),
                GhostStageSize(300, 400),
                GhostStageSize(200, 200)
            )
        )

        Assert.assertEquals(300, layout.sakura.size.width)
        Assert.assertEquals(400, layout.sakura.size.height)
        Assert.assertEquals(GhostStagePlacement.Horizontal.END, layout.sakura.horizontal)
        Assert.assertEquals(200, layout.kero.size.width)
        Assert.assertEquals(GhostStagePlacement.Horizontal.START, layout.kero.horizontal)
        Assert.assertEquals(400, layout.sakuraBalloon.size.width)
        Assert.assertEquals(200, layout.sakuraBalloon.size.height)
        Assert.assertEquals(400, layout.keroBalloon.size.width)
        Assert.assertEquals(200, layout.keroBalloon.size.height)
    }

    @Test
    fun requiredMigrationInvariant_shortKeroUsesTallBalloonRule() {
        val layout = requireNotNull(
            GhostStageLayoutPolicy.calculate(
                GhostStageSize(800, 600),
                GhostStageSize(300, 500),
                GhostStageSize(100, 100)
            )
        )

        Assert.assertEquals(800, layout.sakuraBalloon.size.width)
        Assert.assertEquals(100, layout.sakuraBalloon.size.height)
        Assert.assertEquals(500, layout.keroBalloon.size.width)
        Assert.assertEquals(400, layout.keroBalloon.size.height)
        Assert.assertEquals(100, layout.keroBalloon.bottomMargin)
    }

    @Test
    fun requiredMigrationInvariant_surfacesScaleToFitWidthBeforePlacement() {
        val layout = requireNotNull(
            GhostStageLayoutPolicy.calculate(
                GhostStageSize(300, 500),
                GhostStageSize(400, 200),
                GhostStageSize(200, 100)
            )
        )

        Assert.assertEquals(200, layout.sakura.size.width)
        Assert.assertEquals(100, layout.sakura.size.height)
        Assert.assertEquals(100, layout.kero.size.width)
        Assert.assertEquals(50, layout.kero.size.height)
    }

    @Test
    fun `standard stage uses physical equal lanes and fixed bubble surface bands`() {
        val layout = AdaptiveStageLayoutPolicy.calculate(environment(360, 720))

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
        val narrow = AdaptiveStageLayoutPolicy.calculate(environment(500, 300))
        val thirds = AdaptiveStageLayoutPolicy.calculate(environment(540, 300))

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
        val standard = AdaptiveStageLayoutPolicy.calculate(environment(1280, 800))
        val compact = AdaptiveStageLayoutPolicy.calculate(environment(1280, 400))

        assertRect(160f, 0f, 1120f, 800f, standard.content)
        assertRect(160f, 0f, 1120f, 400f, compact.content)
    }

    @Test
    fun `compact center transitions exactly below at and above 540`() {
        val below = AdaptiveStageLayoutPolicy.calculate(environment(539, 300))
        val at = AdaptiveStageLayoutPolicy.calculate(environment(540, 300))
        val above = AdaptiveStageLayoutPolicy.calculate(environment(541, 300))

        Assert.assertEquals(180f, requireNotNull(below.keroBubble).width.value, 0.001f)
        Assert.assertEquals(180f, requireNotNull(at.keroBubble).width.value, 0.001f)
        Assert.assertEquals(541f / 3f, requireNotNull(above.keroBubble).width.value, 0.001f)
    }

    @Test
    fun `content width transitions exactly below at and above 960`() {
        val below = AdaptiveStageLayoutPolicy.calculate(environment(959, 800))
        val at = AdaptiveStageLayoutPolicy.calculate(environment(960, 800))
        val above = AdaptiveStageLayoutPolicy.calculate(environment(961, 800))

        Assert.assertEquals(959f, below.content.width.value, 0.001f)
        Assert.assertEquals(960f, at.content.width.value, 0.001f)
        Assert.assertEquals(960f, above.content.width.value, 0.001f)
        Assert.assertEquals(0.5f, above.content.left.value, 0.001f)
    }

    @Test
    fun `physical speaker lanes never mirror in RTL`() {
        val ltr = AdaptiveStageLayoutPolicy.calculate(environment(720, 360, StageLayoutDirection.LTR))
        val rtl = AdaptiveStageLayoutPolicy.calculate(environment(720, 360, StageLayoutDirection.RTL))

        Assert.assertEquals(ltr.keroLane, rtl.keroLane)
        Assert.assertEquals(ltr.sakuraLane, rtl.sakuraLane)
        Assert.assertTrue(requireNotNull(rtl.keroLane).left < requireNotNull(rtl.sakuraLane).left)
    }

    @Test
    fun `tiny layout publishes no invisible interactive rectangles`() {
        val layout = AdaptiveStageLayoutPolicy.calculate(environment(480, 230))

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
