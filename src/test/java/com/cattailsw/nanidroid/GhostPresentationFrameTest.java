package com.cattailsw.nanidroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Characterizes the UI-free visibility policy used by presentation renderers. */
public class GhostPresentationFrameTest {
    @Test
    public void retainsLegacyBalloonVisibilityPolicyForBothSpeakers() {
        GhostPresentationFrame.Speaker hidden =
                new GhostPresentationFrame.Speaker("", "0", null, "-1");
        GhostPresentationFrame.Speaker visibleFromBalloon =
                new GhostPresentationFrame.Speaker("", "10", null, "0");
        GhostPresentationFrame.Speaker visibleFromText =
                new GhostPresentationFrame.Speaker("hello", "10", null, "-1");

        assertFalse(hidden.balloonVisible);
        assertTrue(visibleFromBalloon.balloonVisible);
        assertTrue(visibleFromText.balloonVisible);
    }
}
