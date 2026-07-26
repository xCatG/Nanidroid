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
        sakuraBalloon.setVisibility(View.INVISIBLE);
        keroBalloon.setVisibility(View.INVISIBLE);
        composeHost.render(frame);
    }
}
