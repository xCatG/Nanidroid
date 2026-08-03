package com.cattailsw.nanidroid.compose

import com.cattailsw.nanidroid.NO_COLLISION
import com.cattailsw.nanidroid.SurfaceDefinition
import com.cattailsw.nanidroid.SurfaceHitTarget
import com.cattailsw.nanidroid.findSurfaceHit
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import kotlin.math.floor

/** Raw local coordinates accepted by a future Compose pointer modifier. */
data class SurfacePointerPosition(val x: Float, val y: Float)

/**
 * Maps a Compose-stage rectangle to intrinsic surface pixels. Bounds are
 * half-open, matching both Compose layout coordinates and Android Rect.
 */
data class SurfacePointerTransform(
    val left: Float,
    val top: Float,
    val renderedWidth: Float,
    val renderedHeight: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
) {
    fun sourcePosition(position: SurfacePointerPosition): SurfaceSourcePosition? {
        if (!position.x.isFinite() || !position.y.isFinite() ||
            !left.isFinite() || !top.isFinite() ||
            !renderedWidth.isFinite() || !renderedHeight.isFinite() ||
            renderedWidth <= 0f || renderedHeight <= 0f || sourceWidth <= 0 || sourceHeight <= 0
        ) return null
        val localX = position.x - left
        val localY = position.y - top
        if (localX < 0f || localY < 0f || localX >= renderedWidth || localY >= renderedHeight) return null
        val x = floor(localX * sourceWidth / renderedWidth).toInt()
        val y = floor(localY * sourceHeight / renderedHeight).toInt()
        return SurfaceSourcePosition(x, y).takeIf { it.x in 0 until sourceWidth && it.y in 0 until sourceHeight }
    }
}

data class SurfaceSourcePosition(val x: Int, val y: Int)

enum class SurfaceSpeaker(val legacyReference: String) {
    SAKURA("0"),
    KERO("1"),
}

/** Host boundary; the future runtime maps typed effects to SHIORI events. */
fun interface SurfaceInteractionPort {
    fun dispatch(effect: SurfaceInteractionEffect)
}

sealed interface SurfacePointerResolution {
    data object OutsideSurface : SurfacePointerResolution
    data object UnsupportedPointerSource : SurfacePointerResolution

    data class Hit(
        val target: SurfaceHitTarget,
        val effect: SurfaceInteractionEffect,
    ) : SurfacePointerResolution
}

/**
 * Platform-free adapter for pointer input. It preserves the legacy contract:
 * a delivered in-bounds touch emits a double-click effect even on a transparent
 * pixel; collision rectangles merely supply a stronger collision id.
 */
object SurfacePointerInteractionMapper {
    fun map(
        speaker: SurfaceSpeaker,
        definition: SurfaceDefinition?,
        image: SurfacePixelImage?,
        transform: SurfacePointerTransform,
        position: SurfacePointerPosition,
        source: PointerSource?,
    ): SurfacePointerResolution {
        source ?: return SurfacePointerResolution.UnsupportedPointerSource
        val intrinsic = transform.sourcePosition(position) ?: return SurfacePointerResolution.OutsideSurface
        val target = findSurfaceHit(definition, intrinsic.x, intrinsic.y) { x, y ->
            image?.takeIf { x in 0 until it.width && y in 0 until it.height }
                ?.pixelAt(x, y)
                ?.ushr(24)
                ?.let { alpha -> alpha != 0 }
        }
        val collision = target as? SurfaceHitTarget.Collision
        return SurfacePointerResolution.Hit(
            target,
            SurfaceInteractionEffect(
                kind = PointerEventKind.CLICK,
                speaker = speaker,
                intrinsic = androidx.compose.ui.unit.IntOffset(intrinsic.x, intrinsic.y),
                button = 0,
                source = source,
                collisionIdentifier = collision?.identifier,
                diagnosticCollisionId = collision?.id ?: NO_COLLISION,
            ),
        )
    }
}

/** Dispatches only a resolved in-bounds pointer effect; it owns no UI state. */
class SurfacePointerInteractionDispatcher(private val port: SurfaceInteractionPort) {
    fun dispatch(resolution: SurfacePointerResolution): SurfacePointerResolution {
        if (resolution is SurfacePointerResolution.Hit) port.dispatch(resolution.effect)
        return resolution
    }
}
