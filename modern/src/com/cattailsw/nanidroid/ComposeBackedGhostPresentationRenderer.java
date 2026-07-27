package com.cattailsw.nanidroid;

import android.view.View;

import com.cattailsw.nanidroid.compose.GhostPresentationComposeHost;

/**
 * Keeps the legacy surface engine authoritative while Compose renders balloons.
 */
public final class ComposeBackedGhostPresentationRenderer implements GhostPresentationRenderer {
    private final LegacyGhostPresentationRenderer surfaceRenderer;
    private final Balloon sakuraBalloon;
    private final Balloon keroBalloon;
    private final GhostPresentationComposeHost composeHost;

    public ComposeBackedGhostPresentationRenderer(
            SakuraView sakura,
            KeroView kero,
            Balloon sakuraBalloon,
            Balloon keroBalloon,
            LayoutManager layoutManager,
            GhostPresentationComposeHost composeHost) {
        surfaceRenderer = new LegacyGhostPresentationRenderer(
                sakura, kero, sakuraBalloon, keroBalloon, layoutManager);
        this.sakuraBalloon = sakuraBalloon;
        this.keroBalloon = keroBalloon;
        this.composeHost = composeHost;
    }

    @Override
    public void render(GhostPresentationFrame frame) {
        surfaceRenderer.render(frame);
        boolean sakuraUsesLegacyInteraction = requiresLegacyInteraction(sakuraBalloon);
        boolean keroUsesLegacyInteraction = requiresLegacyInteraction(keroBalloon);
        if (!sakuraUsesLegacyInteraction) {
            sakuraBalloon.setVisibility(View.INVISIBLE);
        }
        if (!keroUsesLegacyInteraction) {
            keroBalloon.setVisibility(View.INVISIBLE);
        }
        composeHost.render(frame, !sakuraUsesLegacyInteraction, !keroUsesLegacyInteraction);
    }

    /**
     * Compose owns static presentation only.  The retained TextView remains
     * authoritative when it has a link or overflow scrolling behavior that
     * Compose has not migrated yet.
     */
    private static boolean requiresLegacyInteraction(Balloon balloon) {
        return balloon.getVisibility() == View.VISIBLE
                && (balloon.getUrls().length != 0 || balloon.getMovementMethod() != null);
    }
}
