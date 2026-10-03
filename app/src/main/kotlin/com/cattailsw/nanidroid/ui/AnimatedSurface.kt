package com.cattailsw.nanidroid.ui

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import com.cattailsw.nanidroid.ghost.ComposedSurface
import com.cattailsw.nanidroid.runtime.MoveCoordinateSpace
import com.cattailsw.nanidroid.runtime.MoveGeometryResolver
import com.cattailsw.nanidroid.runtime.SurfaceImageBounds
import com.cattailsw.nanidroid.runtime.SurfaceVisual

/** Immutable draw instructions; images are decoded once when the catalog is loaded. */
internal data class AnimatedSurface(
    val parts: List<Part>,
    val coordinates: MoveCoordinateSpace,
) {
    data class Part(val image: ImageBitmap, val x: Int, val y: Int, val blendMode: BlendMode)

    val originX get() = coordinates.originX
    val originY get() = coordinates.originY
    val width get() = coordinates.width
    val height get() = coordinates.height
    val collisionSurfaceId get() = coordinates.displayedBaseId
    val offsetX get() = coordinates.offsetX
    val offsetY get() = coordinates.offsetY

    companion object {
        fun resolve(logicalId: Int, visual: SurfaceVisual?, images: Map<Int, ComposedSurface>,
            fallbackId: Int, bounds: Map<Int, SurfaceImageBounds> = imageBounds(images)): AnimatedSurface? {
            val geometry = MoveGeometryResolver.resolve(logicalId, visual, bounds, fallbackId)
                ?: return null
            val base = images.getValue(geometry.coordinates.displayedBaseId)
            val parts = mutableListOf(Part(base.image, base.originX, base.originY, BlendMode.SrcOver))
            geometry.layers.forEach { layer ->
                parts += Part(images.getValue(layer.surfaceId).image, layer.x, layer.y,
                    if (layer.method == "interpolate") BlendMode.DstOver else BlendMode.SrcOver)
            }
            return AnimatedSurface(parts, geometry.coordinates)
        }

        fun imageBounds(images: Map<Int, ComposedSurface>): Map<Int, SurfaceImageBounds> =
            images.mapValues { (_, image) -> SurfaceImageBounds(image.originX, image.originY,
                image.image.width, image.image.height) }
    }
}
