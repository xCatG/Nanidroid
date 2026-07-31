package com.cattailsw.nanidroid.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cattailsw.nanidroid.GhostPresentationFrame
import com.cattailsw.nanidroid.GhostPresentationRenderer

/** A complete, UI-free snapshot suitable for the Compose ghost stage. */
data class GhostPresentationRuntimeState(
    val presentation: GhostPresentationState,
    val talkingAnimationEnabled: Boolean,
    /** Changes for every render pass, including passes with equal visible data. */
    val revision: Long,
) {
    companion object {
        val Initial = GhostPresentationRuntimeState(
            presentation = GhostPresentationReducer.snapshot(
                sakuraText = "",
                sakuraSurfaceId = "0",
                sakuraAnimationId = null,
                sakuraBalloonId = "-1",
                keroText = "",
                keroSurfaceId = "10",
                keroAnimationId = null,
                keroBalloonId = "-1",
            ),
            talkingAnimationEnabled = false,
            revision = 0,
        )
    }
}

sealed interface GhostPresentationRuntimeEvent {
    /** Exactly one legacy [GhostPresentationRenderer.render] call. */
    data class Render(val frame: GhostPresentationFrame) : GhostPresentationRuntimeEvent
}

/**
 * Ordered, UI-technology-neutral actions performed by the legacy renderer.
 *
 * These trace actions are deliberately not View operations. The eventual
 * Compose cut-over consumes [GhostPresentationRuntimeState] and routes any
 * imperative work (scheduling and interaction) through typed ports.
 */
sealed interface GhostPresentationRuntimeEffect {
    data class SurfaceChanged(val speaker: GhostSpeaker, val surfaceId: String) : GhostPresentationRuntimeEffect
    data class BalloonVisibilityChanged(val speaker: GhostSpeaker, val visible: Boolean) : GhostPresentationRuntimeEffect
    data class BalloonTextChanged(val speaker: GhostSpeaker, val text: String) : GhostPresentationRuntimeEffect
    data class TalkingAnimationRequested(val speaker: GhostSpeaker) : GhostPresentationRuntimeEffect
    data object LayoutRefreshRequested : GhostPresentationRuntimeEffect
    data class OneShotAnimationRequested(val speaker: GhostSpeaker, val animationId: String) : GhostPresentationRuntimeEffect
}

data class GhostPresentationRuntimeTransition(
    val state: GhostPresentationRuntimeState,
    val effects: List<GhostPresentationRuntimeEffect>,
)

/**
 * Differentially-characterized reducer for [LegacyGhostPresentationRenderer].
 *
 * The effect order mirrors the legacy renderer: both surfaces, each balloon
 * (and implicit talking), the layout refresh, then explicit animations. This
 * protects the production Compose cut-over from quietly changing script-runner
 * semantics while keeping this layer free of retained Views and Android UI
 * objects.
 */
object GhostPresentationRuntimeReducer {
    @JvmStatic
    fun reduce(
        state: GhostPresentationRuntimeState,
        event: GhostPresentationRuntimeEvent,
    ): GhostPresentationRuntimeTransition = when (event) {
        is GhostPresentationRuntimeEvent.Render -> render(state, event.frame)
    }

    private fun render(
        previous: GhostPresentationRuntimeState,
        frame: GhostPresentationFrame,
    ): GhostPresentationRuntimeTransition {
        val sakura = frame.sakura.toPresentation(GhostSpeaker.SAKURA)
        val kero = frame.kero.toPresentation(GhostSpeaker.KERO)
        val next = GhostPresentationRuntimeState(
            presentation = GhostPresentationState(sakura.presentation, kero.presentation),
            talkingAnimationEnabled = frame.talkingAnimationEnabled,
            revision = previous.revision + 1,
        )
        val effects = buildList {
            add(GhostPresentationRuntimeEffect.SurfaceChanged(GhostSpeaker.SAKURA, sakura.presentation.surfaceId))
            add(GhostPresentationRuntimeEffect.SurfaceChanged(GhostSpeaker.KERO, kero.presentation.surfaceId))
            addBalloonEffects(GhostSpeaker.SAKURA, sakura.presentation, frame.talkingAnimationEnabled)
            addBalloonEffects(GhostSpeaker.KERO, kero.presentation, frame.talkingAnimationEnabled)
            add(GhostPresentationRuntimeEffect.LayoutRefreshRequested)
            sakura.presentation.animationId?.let {
                add(GhostPresentationRuntimeEffect.OneShotAnimationRequested(GhostSpeaker.SAKURA, it))
            }
            kero.presentation.animationId?.let {
                add(GhostPresentationRuntimeEffect.OneShotAnimationRequested(GhostSpeaker.KERO, it))
            }
        }
        return GhostPresentationRuntimeTransition(next, effects)
    }

    private data class SpeakerInput(val presentation: GhostSpeakerPresentation)

    private fun GhostPresentationFrame.Speaker.toPresentation(speaker: GhostSpeaker): SpeakerInput = SpeakerInput(
        GhostSpeakerPresentation(
            text = text,
            // LegacyGhostPresentationRenderer uses !! here. Retaining that
            // contract lets the not-yet-wired adapter fail at the same API
            // boundary rather than inventing a hidden fallback surface.
            surfaceId = requireNotNull(surfaceId) { "$speaker frame requires a surface id" },
            animationId = animationId,
            balloonVisible = balloonVisible,
        ),
    )

    private fun MutableList<GhostPresentationRuntimeEffect>.addBalloonEffects(
        speaker: GhostSpeaker,
        presentation: GhostSpeakerPresentation,
        talkingEnabled: Boolean,
    ) {
        add(GhostPresentationRuntimeEffect.BalloonVisibilityChanged(speaker, presentation.balloonVisible))
        if (!presentation.balloonVisible) return
        add(GhostPresentationRuntimeEffect.BalloonTextChanged(speaker, presentation.text))
        if (presentation.animationId == null && talkingEnabled) {
            add(GhostPresentationRuntimeEffect.TalkingAnimationRequested(speaker))
        }
    }
}

/**
 * ABI-compatible, Compose-observable renderer adapter for the later activity
 * cut-over. It owns declarative snapshots only; it never receives or retains a
 * SakuraView, Balloon, LayoutManager, or any other legacy View.
 */
class KotlinGhostPresentationRuntime(
    private val onTransition: (GhostPresentationRuntimeTransition) -> Unit = {},
) : GhostPresentationRenderer {
    var state: GhostPresentationRuntimeState by mutableStateOf(GhostPresentationRuntimeState.Initial)
        private set

    override fun render(frame: GhostPresentationFrame) {
        val transition = GhostPresentationRuntimeReducer.reduce(state, GhostPresentationRuntimeEvent.Render(frame))
        state = transition.state
        onTransition(transition)
    }
}
