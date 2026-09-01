package com.cattailsw.nanidroid.compose

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey

internal fun opaqueStageTestSurface(surfaceId: Int, size: IntSize): ComposedSurface {
    require(size.width > 0 && size.height > 0)
    return ComposedSurface(
        image = SurfacePixelImage.of(
            size.width,
            size.height,
            IntArray(size.width * size.height) { 0xff404040.toInt() },
        ),
        canvasSize = size,
        visiblePixelBounds = IntRect(0, 0, size.width, size.height),
        effectiveCollisions = emptyList(),
        surfaceKey = SurfaceKey(surfaceId, size),
        revision = 0,
        explicitlyHidden = false,
    )
}
