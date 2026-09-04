package com.cattailsw.nanidroid

const val NO_COLLISION = -1

/** No Android/Compose types cross this boundary. */
sealed interface SurfaceHitTarget {
    data class Collision(val id: Int, val identifier: String) : SurfaceHitTarget
    data object OpaquePixel : SurfaceHitTarget
    data object TransparentPixel : SurfaceHitTarget
    data object PixelUnavailable : SurfaceHitTarget
}
