package com.cattailsw.nanidroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Characterizes the legacy stage geometry before Compose becomes its owner. */
public final class GhostStageLayoutPolicyTest {
    @Test
    public void requiredMigrationInvariant_unmeasuredStageDoesNotProduceLayout() {
        assertNull(GhostStageLayoutPolicy.calculate(
                new GhostStageSize(0, 600),
                new GhostStageSize(300, 400),
                new GhostStageSize(200, 200)));
    }

    @Test
    public void requiredMigrationInvariant_wideStageKeepsOriginalSurfaceSizesAndSplitBalloons() {
        GhostStageLayout layout = GhostStageLayoutPolicy.calculate(
                new GhostStageSize(800, 600),
                new GhostStageSize(300, 400),
                new GhostStageSize(200, 200));

        assertEquals(300, layout.sakura.size.width);
        assertEquals(400, layout.sakura.size.height);
        assertEquals(GhostStagePlacement.Horizontal.END, layout.sakura.horizontal);
        assertEquals(200, layout.kero.size.width);
        assertEquals(GhostStagePlacement.Horizontal.START, layout.kero.horizontal);
        assertEquals(400, layout.sakuraBalloon.size.width);
        assertEquals(200, layout.sakuraBalloon.size.height);
        assertEquals(400, layout.keroBalloon.size.width);
        assertEquals(200, layout.keroBalloon.size.height);
    }

    @Test
    public void requiredMigrationInvariant_shortKeroUsesTallBalloonRule() {
        GhostStageLayout layout = GhostStageLayoutPolicy.calculate(
                new GhostStageSize(800, 600),
                new GhostStageSize(300, 500),
                new GhostStageSize(100, 100));

        assertEquals(800, layout.sakuraBalloon.size.width);
        assertEquals(100, layout.sakuraBalloon.size.height);
        assertEquals(500, layout.keroBalloon.size.width);
        assertEquals(400, layout.keroBalloon.size.height);
        assertEquals(100, layout.keroBalloon.bottomMargin);
    }

    @Test
    public void requiredMigrationInvariant_surfacesScaleToFitWidthBeforePlacement() {
        GhostStageLayout layout = GhostStageLayoutPolicy.calculate(
                new GhostStageSize(300, 500),
                new GhostStageSize(400, 200),
                new GhostStageSize(200, 100));

        assertEquals(200, layout.sakura.size.width);
        assertEquals(100, layout.sakura.size.height);
        assertEquals(100, layout.kero.size.width);
        assertEquals(50, layout.kero.size.height);
    }
}
