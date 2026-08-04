package com.cattailsw.nanidroid.compose

import com.cattailsw.nanidroid.NO_COLLISION
import com.cattailsw.nanidroid.SurfaceHitTarget
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx
import kotlin.math.roundToInt

/** Raw local coordinates accepted by a future Compose pointer modifier. */
data class SurfacePointerPosition(val x: Float, val y: Float)

/** Adds a surface node's stage origin exactly once to its local pointer point. */
fun SurfaceTransformPx.stagePositionFromLocal(local: androidx.compose.ui.geometry.Offset) = SurfacePointerPosition(
    renderedBounds.left + local.x,
    renderedBounds.top + local.y,
)

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
        surface: ComposedSurface?,
        transform: SurfaceTransformPx,
        position: SurfacePointerPosition,
        source: PointerSource?,
        button: Int = 0,
    ): SurfacePointerResolution {
        source ?: return SurfacePointerResolution.UnsupportedPointerSource
        val intrinsic = transform.toIntrinsic(androidx.compose.ui.geometry.Offset(position.x, position.y))
            ?: return SurfacePointerResolution.OutsideSurface
        val collisionHit = surface?.effectiveCollisions?.firstOrNull { collision ->
            collision.shape.contains(intrinsic)
        }
        val target = collisionHit?.let { collision ->
            SurfaceHitTarget.Collision(collision.id, collision.identifier)
        } ?: when (
            surface?.image?.takeIf {
                intrinsic.x in 0 until it.width && intrinsic.y in 0 until it.height
            }
                ?.pixelAt(intrinsic.x, intrinsic.y)
                ?.ushr(24)
                ?.let { alpha -> alpha != 0 }
        ) {
            true -> SurfaceHitTarget.OpaquePixel
            false -> SurfaceHitTarget.TransparentPixel
            null -> SurfaceHitTarget.PixelUnavailable
        }
        val collision = target as? SurfaceHitTarget.Collision
        return SurfacePointerResolution.Hit(
            target,
            SurfaceInteractionEffect(
                kind = PointerEventKind.CLICK,
                speaker = speaker,
                intrinsic = androidx.compose.ui.unit.IntOffset(intrinsic.x, intrinsic.y),
                button = button,
                source = source,
                collisionIdentifier = collision?.identifier,
                diagnosticCollisionId = collision?.id ?: NO_COLLISION,
                viewportPosition = androidx.compose.ui.unit.IntOffset(
                    position.x.roundToInt(),
                    position.y.roundToInt(),
                ),
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
