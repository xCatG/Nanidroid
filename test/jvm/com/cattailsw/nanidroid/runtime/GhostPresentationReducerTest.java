package com.cattailsw.nanidroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Locks the pure presentation boundary that the legacy view adapter consumes. */
public final class GhostPresentationReducerTest {
    @Test
    public void requiredMigrationInvariant_snapshotPreservesSpeakerTextSurfaceAnimationAndBalloonPolicy() {
        GhostPresentationState state = GhostPresentationReducer.snapshot(
                "Sakura text", "120", "3", "-1",
                "Kero text", "11", null, "0");

        assertEquals("Sakura text", state.getSakura().getText());
        assertEquals("120", state.getSakura().getSurfaceId());
        assertEquals("3", state.getSakura().getAnimationId());
        assertTrue(state.getSakura().getBalloonVisible());

        assertEquals("Kero text", state.getKero().getText());
        assertEquals("11", state.getKero().getSurfaceId());
        assertEquals(null, state.getKero().getAnimationId());
        assertTrue(state.getKero().getBalloonVisible());
    }

    @Test
    public void requiredMigrationInvariant_emptyTextAndDisabledBalloonRemainHidden() {
        GhostPresentationState state = GhostPresentationReducer.snapshot(
                "", "0", null, "-1",
                "", "10", null, "-1");

        assertFalse(state.getSakura().getBalloonVisible());
        assertFalse(state.getKero().getBalloonVisible());
    }
}
