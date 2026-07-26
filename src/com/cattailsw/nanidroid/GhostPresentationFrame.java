package com.cattailsw.nanidroid;

/**
 * Immutable render input emitted by the Sakura Script runtime.
 *
 * <p>This deliberately contains no Android view types. The legacy renderer is
 * one consumer; a Compose renderer will consume the same presentation facts
 * after the runtime migration.</p>
 */
public final class GhostPresentationFrame {
    public static final class Speaker {
        public final String text;
        public final String surfaceId;
        public final String animationId;
        public final boolean balloonVisible;

        public Speaker(String text, String surfaceId, String animationId, String balloonId) {
            this.text = text;
            this.surfaceId = surfaceId;
            this.animationId = animationId;
            this.balloonVisible = !"-1".equalsIgnoreCase(balloonId) || text.length() > 0;
        }
    }

    public final Speaker sakura;
    public final Speaker kero;
    public final boolean talkingAnimationEnabled;

    public GhostPresentationFrame(
            Speaker sakura, Speaker kero, boolean talkingAnimationEnabled) {
        this.sakura = sakura;
        this.kero = kero;
        this.talkingAnimationEnabled = talkingAnimationEnabled;
    }
}
