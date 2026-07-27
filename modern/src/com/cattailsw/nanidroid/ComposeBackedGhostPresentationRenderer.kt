package com.cattailsw.nanidroid

import android.view.View
import com.cattailsw.nanidroid.compose.GhostPresentationComposeHost

/** Keeps the retained surface engine authoritative while Compose renders balloons. */
class ComposeBackedGhostPresentationRenderer(
    sakura: SakuraView,
    kero: KeroView,
    private val sakuraBalloon: Balloon,
    private val keroBalloon: Balloon,
    layoutManager: LayoutManager?,
    private val composeHost: GhostPresentationComposeHost,
) : GhostPresentationRenderer {
    private val surfaceRenderer = LegacyGhostPresentationRenderer(
        sakura, kero, sakuraBalloon, keroBalloon, layoutManager,
    )

    override fun render(frame: GhostPresentationFrame) {
        surfaceRenderer.render(frame)
        val sakuraUsesLegacyInteraction = requiresLegacyInteraction(sakuraBalloon)
        val keroUsesLegacyInteraction = requiresLegacyInteraction(keroBalloon)
        if (!sakuraUsesLegacyInteraction) sakuraBalloon.visibility = View.INVISIBLE
        if (!keroUsesLegacyInteraction) keroBalloon.visibility = View.INVISIBLE
        composeHost.render(frame, !sakuraUsesLegacyInteraction, !keroUsesLegacyInteraction)
    }

    private fun requiresLegacyInteraction(balloon: Balloon): Boolean =
        balloon.visibility == View.VISIBLE && (balloon.urls.isNotEmpty() || balloon.movementMethod != null)
}
