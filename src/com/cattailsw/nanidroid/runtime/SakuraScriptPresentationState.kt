package com.cattailsw.nanidroid.runtime

/** The speaker currently selected by Sakura Script commands. */
enum class GhostSpeaker {
    SAKURA,
    KERO,
}

/**
 * Immutable Sakura Script presentation facts, independent of parsing and UI.
 *
 * Surface ids intentionally survive [resetForNextScript], matching the legacy
 * runner. Animation ids are one-shot commands and are cleared only by
 * [consumeAnimations] after a renderer has received a frame.
 */
data class SakuraScriptPresentationState(
    val activeSpeaker: GhostSpeaker,
    val synchronizedText: Boolean,
    val sakuraText: String,
    val keroText: String,
    val sakuraSurfaceId: String,
    val keroSurfaceId: String,
    val sakuraBalloonId: String,
    val keroBalloonId: String,
    val sakuraAnimationId: String?,
    val keroAnimationId: String?,
)

/** Pure transition functions extracted from the mutable Java script runner. */
object SakuraScriptPresentationReducer {
    @JvmStatic
    fun initial(): SakuraScriptPresentationState = SakuraScriptPresentationState(
        activeSpeaker = GhostSpeaker.SAKURA,
        synchronizedText = false,
        sakuraText = "",
        keroText = "",
        sakuraSurfaceId = "0",
        keroSurfaceId = "10",
        sakuraBalloonId = "0",
        keroBalloonId = "-1",
        sakuraAnimationId = null,
        keroAnimationId = null,
    )

    @JvmStatic
    fun resetForNextScript(state: SakuraScriptPresentationState): SakuraScriptPresentationState =
        state.copy(
            activeSpeaker = GhostSpeaker.SAKURA,
            synchronizedText = false,
            sakuraText = "",
            keroText = "",
            sakuraBalloonId = "-1",
            keroBalloonId = "-1",
            sakuraAnimationId = null,
            keroAnimationId = null,
        )

    @JvmStatic
    fun selectSpeaker(
        state: SakuraScriptPresentationState,
        speaker: GhostSpeaker,
    ): SakuraScriptPresentationState = when (speaker) {
        GhostSpeaker.SAKURA -> state.copy(activeSpeaker = speaker, sakuraText = "")
        GhostSpeaker.KERO -> state.copy(activeSpeaker = speaker, keroText = "")
    }

    @JvmStatic
    fun toggleSynchronization(state: SakuraScriptPresentationState): SakuraScriptPresentationState =
        state.copy(synchronizedText = !state.synchronizedText)

    @JvmStatic
    fun append(state: SakuraScriptPresentationState, character: Char): SakuraScriptPresentationState {
        val nextSakura = if (state.synchronizedText || state.activeSpeaker == GhostSpeaker.SAKURA) {
            state.sakuraText + character
        } else {
            state.sakuraText
        }
        val nextKero = if (state.synchronizedText || state.activeSpeaker == GhostSpeaker.KERO) {
            state.keroText + character
        } else {
            state.keroText
        }
        return state.copy(
            sakuraText = nextSakura,
            keroText = nextKero,
            // This is intentionally evaluated after every append in the legacy runner.
            keroBalloonId = if (nextKero.isNotEmpty()) "0" else state.keroBalloonId,
        )
    }

    @JvmStatic
    fun clearActiveText(state: SakuraScriptPresentationState): SakuraScriptPresentationState =
        when (state.activeSpeaker) {
            GhostSpeaker.SAKURA -> state.copy(sakuraText = "")
            GhostSpeaker.KERO -> state.copy(keroText = "")
        }

    @JvmStatic
    fun changeSurface(
        state: SakuraScriptPresentationState,
        surfaceId: String,
    ): SakuraScriptPresentationState = when (state.activeSpeaker) {
        GhostSpeaker.SAKURA -> state.copy(sakuraSurfaceId = surfaceId)
        GhostSpeaker.KERO -> state.copy(keroSurfaceId = surfaceId)
    }

    @JvmStatic
    fun changeBalloon(
        state: SakuraScriptPresentationState,
        balloonId: String,
    ): SakuraScriptPresentationState = when (state.activeSpeaker) {
        GhostSpeaker.SAKURA -> state.copy(sakuraBalloonId = balloonId)
        GhostSpeaker.KERO -> state.copy(keroBalloonId = balloonId)
    }

    @JvmStatic
    fun queueAnimation(
        state: SakuraScriptPresentationState,
        animationId: String,
    ): SakuraScriptPresentationState = when (state.activeSpeaker) {
        GhostSpeaker.SAKURA -> state.copy(sakuraAnimationId = animationId)
        GhostSpeaker.KERO -> state.copy(keroAnimationId = animationId)
    }

    @JvmStatic
    fun consumeAnimations(state: SakuraScriptPresentationState): SakuraScriptPresentationState =
        state.copy(sakuraAnimationId = null, keroAnimationId = null)

    @JvmStatic
    fun snapshot(state: SakuraScriptPresentationState): GhostPresentationState =
        GhostPresentationReducer.snapshot(
            state.sakuraText,
            state.sakuraSurfaceId,
            state.sakuraAnimationId,
            state.sakuraBalloonId,
            state.keroText,
            state.keroSurfaceId,
            state.keroAnimationId,
            state.keroBalloonId,
        )
}
