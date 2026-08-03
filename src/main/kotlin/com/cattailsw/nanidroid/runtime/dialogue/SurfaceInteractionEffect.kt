package com.cattailsw.nanidroid.runtime.dialogue

import androidx.compose.ui.unit.IntOffset
import com.cattailsw.nanidroid.compose.SurfaceSpeaker

enum class PointerSource(val shioriReference: String) {
    TOUCH("touch"),
    MOUSE("mouse"),
    PEN("pen"),
    ERASER("eraser"),
}

enum class PointerEventKind { CLICK, DOUBLE_CLICK, MOVE, ENTER, LEAVE, WHEEL, DRAG }

/** A source-neutral surface interaction before it crosses the SHIORI boundary. */
data class SurfaceInteractionEffect(
    val kind: PointerEventKind,
    val speaker: SurfaceSpeaker,
    val intrinsic: IntOffset,
    val button: Int,
    val source: PointerSource,
    val collisionIdentifier: String?,
    val diagnosticCollisionId: Int?,
    val wheelDelta: Int = 0,
)

/** Resolves one interaction to at most one mouse event; deferred kinds remain undispatched. */
object SurfaceInteractionProtocol {
    fun eventFor(effect: SurfaceInteractionEffect, capabilities: PointerEventCapabilities): String? {
        if (effect.button != 0) return null
        return when (effect.source) {
            PointerSource.TOUCH -> if (effect.kind == PointerEventKind.CLICK) touchEvent(capabilities) else null
            PointerSource.MOUSE, PointerSource.PEN, PointerSource.ERASER -> physicalEvent(effect.kind, capabilities)
        }
    }

    fun references(effect: SurfaceInteractionEffect): List<String> = listOf(
        effect.intrinsic.x.toString(),
        effect.intrinsic.y.toString(),
        "0",
        effect.speaker.legacyReference,
        effect.collisionIdentifier.orEmpty(),
        effect.button.toString(),
        effect.source.shioriReference,
    )

    private fun touchEvent(capabilities: PointerEventCapabilities): String? = when (capabilities.click) {
        Support.SUPPORTED -> "OnMouseClick"
        Support.UNSUPPORTED -> when (capabilities.doubleClick) {
            Support.SUPPORTED -> "OnMouseDoubleClick"
            Support.UNSUPPORTED -> null
            Support.UNKNOWN -> "OnMouseDoubleClick"
        }
        Support.UNKNOWN -> when (capabilities.doubleClick) {
            Support.SUPPORTED -> "OnMouseDoubleClick"
            Support.UNSUPPORTED -> "OnMouseClick"
            Support.UNKNOWN -> "OnMouseDoubleClick"
        }
    }

    private fun physicalEvent(kind: PointerEventKind, capabilities: PointerEventCapabilities): String? = when (kind) {
        PointerEventKind.CLICK -> "OnMouseClick".takeUnless { capabilities.click == Support.UNSUPPORTED }
        PointerEventKind.DOUBLE_CLICK -> "OnMouseDoubleClick".takeUnless { capabilities.doubleClick == Support.UNSUPPORTED }
        PointerEventKind.MOVE,
        PointerEventKind.ENTER,
        PointerEventKind.LEAVE,
        PointerEventKind.WHEEL,
        PointerEventKind.DRAG,
        -> null
    }
}
