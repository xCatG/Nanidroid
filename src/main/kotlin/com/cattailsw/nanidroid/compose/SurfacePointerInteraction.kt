package com.cattailsw.nanidroid.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.cattailsw.nanidroid.NO_COLLISION
import com.cattailsw.nanidroid.SurfaceHitTarget
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx
import kotlin.math.roundToInt

enum class SurfaceSpeaker(val legacyReference: String) {
    SAKURA("0"),
    KERO("1"),
}

/** Boundary for delivering routed surface effects. */
fun interface SurfaceInteractionPort {
    fun dispatch(effect: SurfaceInteractionEffect)
}

internal data class SurfacePointerHit(
    val target: SurfaceHitTarget,
    val effect: SurfaceInteractionEffect,
)

/**
 * Platform-free adapter for pointer input. It preserves the legacy contract:
 * a delivered in-bounds touch emits a double-click effect even on a transparent
 * pixel; collision rectangles merely supply a stronger collision id.
 */
internal object SurfacePointerInteractionMapper {
    fun map(
        speaker: SurfaceSpeaker,
        surface: ComposedSurface?,
        transform: SurfaceTransformPx,
        position: Offset,
        source: PointerSource,
        button: Int = 0,
    ): SurfacePointerHit? {
        val intrinsic = transform.toIntrinsic(position) ?: return null
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
        return SurfacePointerHit(
            target,
            SurfaceInteractionEffect(
                kind = PointerEventKind.CLICK,
                speaker = speaker,
                intrinsic = IntOffset(intrinsic.x, intrinsic.y),
                button = button,
                source = source,
                collisionIdentifier = collision?.identifier,
                diagnosticCollisionId = collision?.id ?: NO_COLLISION,
                viewportPosition = IntOffset(
                    position.x.roundToInt(),
                    position.y.roundToInt(),
                ),
            ),
        )
    }
}
