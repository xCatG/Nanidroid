package com.cattailsw.nanidroid.runtime

/**
 * UI-technology-neutral result of one Sakura Script render pass.
 *
 * The legacy `ImageView`/`TextView` adapter consumes this today. Compose will
 * consume the same immutable state when it replaces that adapter.
 */
data class GhostSpeakerPresentation(
    val text: String,
    val surfaceId: String,
    val animationId: String?,
    val balloonVisible: Boolean,
)

data class GhostPresentationState(
    val sakura: GhostSpeakerPresentation,
    val kero: GhostSpeakerPresentation,
)

object GhostPresentationReducer {
    /**
     * Keeps the legacy balloon rule in one pure, deterministic boundary:
     * a speaker is visible when explicitly selected or when it has text.
     */
    @JvmStatic
    fun snapshot(
        sakuraText: String,
        sakuraSurfaceId: String,
        sakuraAnimationId: String?,
        sakuraBalloonId: String,
        keroText: String,
        keroSurfaceId: String,
        keroAnimationId: String?,
        keroBalloonId: String,
    ): GhostPresentationState = GhostPresentationState(
        sakura = speaker(
            sakuraText,
            sakuraSurfaceId,
            sakuraAnimationId,
            sakuraBalloonId,
        ),
        kero = speaker(keroText, keroSurfaceId, keroAnimationId, keroBalloonId),
    )

    private fun speaker(
        text: String,
        surfaceId: String,
        animationId: String?,
        balloonId: String,
    ): GhostSpeakerPresentation = GhostSpeakerPresentation(
        text = text,
        surfaceId = surfaceId,
        animationId = animationId,
        balloonVisible = balloonId != "-1" || text.isNotEmpty(),
    )
}
