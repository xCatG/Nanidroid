package com.cattailsw.nanidroid.runtime

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
}