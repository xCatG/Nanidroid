package com.cattailsw.nanidroid

/**
 * Immutable, UI-free render input emitted by the Sakura Script runtime.
 *
 * The fields remain directly accessible from the legacy Java renderer while
 * Compose consumes the same presentation facts without View types.
 */
data class GhostPresentationFrame(
    @JvmField val sakura: Speaker,
    @JvmField val kero: Speaker,
    @JvmField val talkingAnimationEnabled: Boolean,
) {
    class Speaker(
        @JvmField val text: String,
        @JvmField val surfaceId: String?,
        @JvmField val animationId: String?,
        private val balloonId: String,
    ) {
        @JvmField
        val balloonVisible: Boolean =
            !balloonId.equals("-1", ignoreCase = true) || text.isNotEmpty()
    }
}
