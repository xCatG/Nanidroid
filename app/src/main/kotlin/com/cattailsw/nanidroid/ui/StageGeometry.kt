package com.cattailsw.nanidroid.ui

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

object StageGeometry {
    data class Pixel(val x: Int, val y: Int)

    /** Authored pixels are logical artwork units at the 320 dpi (2 px/dp) reference. */
    fun logicalArtCeiling(windowWidthDp: Int, windowHeightDp: Int, density: Float): Float {
        val shortSideDp = min(windowWidthDp, windowHeightDp)
        val bucket = when {
            shortSideDp < 400 -> 1f
            shortSideDp < 600 -> 1.5f
            else -> 2f
        }
        return bucket * density / 2f
    }

    data class SurfaceRectangle(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val surfaceWidth: Int,
        val surfaceHeight: Int,
    ) {
        fun toAuthored(x: Float, y: Float, originX: Int, originY: Int): Pixel? =
            toLocal(x, y)?.let { Pixel(it.x + originX, it.y + originY) }

        fun toLocal(x: Float, y: Float): Pixel? {
            if (width <= 0f || height <= 0f || x < left || y < top || x >= left + width || y >= top + height) return null
            return Pixel(
                floor((x - left) * surfaceWidth / width).toInt().coerceIn(0, surfaceWidth - 1),
                floor((y - top) * surfaceHeight / height).toInt().coerceIn(0, surfaceHeight - 1),
            )
        }
    }

    fun fittedRectangle(surfaceWidth: Int, surfaceHeight: Int, viewWidth: Int, viewHeight: Int): SurfaceRectangle {
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return SurfaceRectangle(0f, 0f, 0f, 0f, surfaceWidth, surfaceHeight)
        }
        val scale = min(viewWidth.toFloat() / surfaceWidth, viewHeight.toFloat() / surfaceHeight)
        val width = (surfaceWidth * scale).roundToInt()
        val height = (surfaceHeight * scale).roundToInt()
        return SurfaceRectangle(((viewWidth - width) / 2).toFloat(), ((viewHeight - height) / 2).toFloat(),
            width.toFloat(), height.toFloat(), surfaceWidth, surfaceHeight)
    }

    fun scaleFor(
        sakuraWidth: Int, sakuraHeight: Int, keroWidth: Int, keroHeight: Int,
        width: Int, height: Int, desiredCeiling: Float,
    ): Float {
        if (sakuraWidth <= 0 || sakuraHeight <= 0 || keroWidth <= 0 || keroHeight <= 0 || width <= 0 || height <= 0 ||
            !desiredCeiling.isFinite() || desiredCeiling <= 0f) return 0f
        return min(desiredCeiling,
            min(width.toFloat() / (sakuraWidth + keroWidth), height.toFloat() / maxOf(sakuraHeight, keroHeight)))
    }
}
