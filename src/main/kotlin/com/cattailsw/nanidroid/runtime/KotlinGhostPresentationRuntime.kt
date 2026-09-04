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

/** Projects each legacy renderer call into the complete Compose stage state. */
object GhostPresentationRuntimeReducer {
    @JvmStatic
    fun reduce(
        previous: GhostPresentationRuntimeState,
        frame: GhostPresentationFrame,
    ): GhostPresentationRuntimeState {
        val sakura = frame.sakura.toPresentation(GhostSpeaker.SAKURA)
        val kero = frame.kero.toPresentation(GhostSpeaker.KERO)
        return GhostPresentationRuntimeState(
            presentation = GhostPresentationState(sakura, kero),
            talkingAnimationEnabled = frame.talkingAnimationEnabled,
            revision = previous.revision + 1,
        )
    }

    private fun GhostPresentationFrame.Speaker.toPresentation(speaker: GhostSpeaker) =
        GhostSpeakerPresentation(
            text = text,
            surfaceId = requireNotNull(surfaceId) { "$speaker frame requires a surface id" },
            animationId = animationId,
            balloonVisible = balloonVisible,
        )
}

/**
 * Compose-observable renderer adapter. It owns declarative snapshots only and
 * never receives or retains a
 * SakuraView, Balloon, LayoutManager, or any other legacy View.
 */
class KotlinGhostPresentationRuntime(
    private val onState: (GhostPresentationRuntimeState) -> Unit = {},
) : GhostPresentationRenderer {
    var state: GhostPresentationRuntimeState by mutableStateOf(GhostPresentationRuntimeState.Initial)
        private set

    override fun render(frame: GhostPresentationFrame) {
        val next = GhostPresentationRuntimeReducer.reduce(state, frame)
        state = next
        onState(next)
    }
}
