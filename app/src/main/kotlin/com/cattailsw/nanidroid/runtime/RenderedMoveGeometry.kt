package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.ghost.LAYER_METHODS
import com.cattailsw.nanidroid.ghost.SurfaceComposer
import com.cattailsw.nanidroid.ghost.SurfaceLayer

/** The bounds of one already composed image, before animation layers are placed. */
data class SurfaceImageBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** The coordinate and collision owner of a displayed character image. */
data class MoveCoordinateSpace(
    val displayedBaseId: Int,
    val originX: Int,
    val originY: Int,
    val width: Int,
    val height: Int,
    val offsetX: Int,
    val offsetY: Int,
)

/** [layers] are the accepted layers, with x/y already placed in image coordinates. */
data class RenderedMoveGeometry(
    val coordinates: MoveCoordinateSpace,
    val layers: List<SurfaceLayer>,
)

/** Shared by the Canvas renderer and queued-move admission. */
object MoveGeometryResolver {
    fun fallbackSurfaceId(speaker: Int): Int = if (speaker == 0) 0 else 10

    fun resolve(logicalId: Int, visual: SurfaceVisual?, images: Map<Int, SurfaceImageBounds>,
        fallbackId: Int): RenderedMoveGeometry? {
        val animatedId = visual?.baseSurfaceId ?: logicalId
        val baseId = when {
            animatedId in images -> animatedId
            logicalId in images -> logicalId
            fallbackId in images -> fallbackId
            else -> return null
        }
        val base = images.getValue(baseId)
        val baseLeft = base.x.toLong()
        val baseTop = base.y.toLong()
        val baseRight = baseLeft + base.width
        val baseBottom = baseTop + base.height
        var left = baseLeft
        var top = baseTop
        var right = baseRight
        var bottom = baseBottom
        val accepted = buildList {
            for (layer in visual?.layers.orEmpty()) {
                val image = images[layer.surfaceId] ?: continue
                if (layer.method !in LAYER_METHODS) continue
                val x = layer.x.toLong() + image.x
                val y = layer.y.toLong() + image.y
                if (x !in SurfaceComposer.INT_RANGE || y !in SurfaceComposer.INT_RANGE ||
                    x >= baseRight || y >= baseBottom ||
                    x + image.width <= baseLeft || y + image.height <= baseTop) continue
                add(layer.copy(x = x.toInt(), y = y.toInt()))
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + image.width)
                bottom = maxOf(bottom, y + image.height)
            }
        }
        if (!SurfaceComposer.fitsCanvas(left, top, right, bottom)) return null
        return RenderedMoveGeometry(MoveCoordinateSpace(baseId, left.toInt(), top.toInt(),
            (right - left).toInt(), (bottom - top).toInt(), visual?.offsetX ?: 0, visual?.offsetY ?: 0),
            accepted)
    }
}
