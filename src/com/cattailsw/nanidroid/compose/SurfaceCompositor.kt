package com.cattailsw.nanidroid.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap

/** Immutable ARGB_8888 pixels, independent of Android drawables and Views. */
class SurfacePixelImage private constructor(
    val width: Int,
    val height: Int,
    private val pixels: IntArray,
) {
    init {
        require(width >= 0 && height >= 0)
        require(pixels.size.toLong() == width.toLong() * height.toLong())
    }

    fun pixelAt(x: Int, y: Int): Int = pixels[y * width + x]

    fun copyPixels(): IntArray = pixels.copyOf()

    /** Matches ShellSurface: every pixel equal to the top-left pixel is clear. */
    fun colorKeyed(): SurfacePixelImage {
        if (pixels.isEmpty()) return this
        val key = pixels.first()
        // Do not use IntArray.map(): a large ghost surface would box every
        // pixel into a temporary List before allocating the output array.
        val keyedPixels = pixels.copyOf()
        for (index in keyedPixels.indices) {
            if (keyedPixels[index] == key) keyedPixels[index] = TRANSPARENT
        }
        return SurfacePixelImage(width, height, keyedPixels)
    }

    companion object {
        val Empty = SurfacePixelImage(0, 0, intArrayOf())
        fun of(width: Int, height: Int, pixels: IntArray): SurfacePixelImage {
            requireSurfacePixelCount(width, height)
            return SurfacePixelImage(width, height, pixels.copyOf())
        }

        /**
         * Transfers an array created solely for this image. Kept internal so
         * callers of [of] retain the public copy-on-input immutability rule.
         */
        internal fun fromOwnedPixels(width: Int, height: Int, pixels: IntArray): SurfacePixelImage {
            requireSurfacePixelCount(width, height)
            return SurfacePixelImage(width, height, pixels)
        }
    }
}

/** Opens raw pixels. Color-key transformation remains owned by [SurfaceCompositor]. */
fun interface SurfacePixelAssets {
    fun load(path: String): SurfacePixelImage?
}

/** Android implementation for the later Compose-host cut-over; it embeds no legacy View. */
object AndroidSurfacePixelAssets : SurfacePixelAssets {
    override fun load(path: String): SurfacePixelImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        requireSurfacePixelCount(bounds.outWidth, bounds.outHeight)
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        try {
            // The decoded bitmap is independently checked in case its dimensions
            // differ from the bounds result on an unusual decoder implementation.
            val pixelCount = requireSurfacePixelCount(bitmap.width, bitmap.height)
            val pixels = IntArray(pixelCount)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return SurfacePixelImage.fromOwnedPixels(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }
}

/** Stable lookup for manager surface ids used by overlay frames. */
class SurfacePlanRegistry(plans: Iterable<SurfaceRenderPlan>) {
    private val plansById = plans.mapNotNull { plan -> plan.surfaceId?.let { it.toString() to plan } }.toMap()
    fun find(surfaceId: String): SurfaceRenderPlan? = plansById[surfaceId]
}

/**
 * Pixel compositor for [SurfaceRenderPlan]. It intentionally owns both legacy
 * color-keying and source-surface precedence so the eventual Compose stage
 * cannot accidentally reintroduce the old Drawable/LayerDrawable renderer.
 */
class SurfaceCompositor(
    private val assets: SurfacePixelAssets,
    private val plans: SurfacePlanRegistry = SurfacePlanRegistry(emptyList()),
) {
    fun normal(plan: SurfaceRenderPlan): SurfacePixelImage {
        if (!plan.hasPositiveCanvas()) return SurfacePixelImage.Empty
        return when (val base = plan.base) {
        SurfaceRenderBase.Missing -> SurfacePixelImage.Empty
        is SurfaceRenderBase.Layers -> canvas(plan.width, plan.height).apply {
            base.layers.forEach { layer ->
                layer.imagePath?.let(assets::load)?.colorKeyed()?.let { image -> draw(image, layer.x, layer.y) }
            }
        }.toImage()
        }
    }

    fun frame(plan: SurfaceRenderPlan, frame: SurfaceRenderFrame): SurfacePixelImage {
        if (frame is SurfaceRenderFrame.Base) {
            return frame.imagePath?.let(assets::load)?.colorKeyed()?.let { image ->
                val width = frame.width.takeIf { it > 0 } ?: image.width
                val height = frame.height.takeIf { it > 0 } ?: image.height
                canvas(width, height).apply { draw(image, 0, 0) }.toImage()
            } ?: SurfacePixelImage.Empty
        }
        if (!plan.hasPositiveCanvas()) return SurfacePixelImage.Empty
        return when (frame) {
        is SurfaceRenderFrame.Base -> error("base frames are handled before normal-plan rendering")
        is SurfaceRenderFrame.Overlay -> overlay(plan, frame)
        is SurfaceRenderFrame.Reset -> normal(plan)
        is SurfaceRenderFrame.Move -> normal(plan) // SurfaceRenderPlan preserves legacy fallback semantics.
        is SurfaceRenderFrame.Unknown -> normal(plan) // Keep the stage visible until a future behavior policy supports it.
        }
    }

    private fun overlay(plan: SurfaceRenderPlan, frame: SurfaceRenderFrame.Overlay): SurfacePixelImage {
        val overlay = frame.sourceSurfaceId
            ?.let(plans::find)
            ?.let(::normal)
            ?: frame.fallbackImagePath?.let(assets::load)?.colorKeyed()
            ?: return normal(plan)
        return canvas(plan.width, plan.height).apply {
            draw(normal(plan), 0, 0)
            draw(overlay, frame.x, frame.y)
        }.toImage()
    }
}

/** Compose bridge for the pure compositor output. Production wiring is intentionally deferred. */
@Composable
fun SurfaceCompositorImage(image: SurfacePixelImage, modifier: Modifier = Modifier) {
    if (image.width == 0 || image.height == 0) return
    val bitmap = remember(image) {
        Bitmap.createBitmap(image.copyPixels(), image.width, image.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
}

private const val TRANSPARENT = 0x00000000

private const val MAX_SURFACE_PIXEL_COUNT = 16 * 1024 * 1024

/** Validates dimensions before Canvas, Bitmap, or IntArray allocation. */
private fun requireSurfacePixelCount(width: Int, height: Int): Int {
    require(width > 0 && height > 0) { "surface dimensions must be positive" }
    val pixelCount = width.toLong() * height.toLong()
    require(pixelCount <= MAX_SURFACE_PIXEL_COUNT.toLong()) {
        "surface pixel count $pixelCount exceeds supported limit $MAX_SURFACE_PIXEL_COUNT"
    }
    return pixelCount.toInt()
}

private fun canvas(width: Int, height: Int): SurfacePixelCanvas {
    requireSurfacePixelCount(width, height)
    return SurfacePixelCanvas(width, height)
}

private class SurfacePixelCanvas(private val width: Int, private val height: Int) {
    private val pixels = IntArray(width * height)

    fun draw(image: SurfacePixelImage, left: Int, top: Int) {
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val destinationX = left + x
            val destinationY = top + y
            if (destinationX !in 0 until width || destinationY !in 0 until height) continue
            val source = image.pixelAt(x, y)
            val sourceAlpha = source ushr 24
            if (sourceAlpha == 0) continue
            val index = destinationY * width + destinationX
            pixels[index] = sourceOver(source, pixels[index])
        }
    }

    /**
     * The canvas has exclusive ownership of [pixels] and is never drawn again
     * after this hand-off, so avoid a second full-size pixel-array copy.
     */
    fun toImage(): SurfacePixelImage = SurfacePixelImage.fromOwnedPixels(width, height, pixels)
}

private fun SurfaceRenderPlan.hasPositiveCanvas(): Boolean = width > 0 && height > 0

private fun sourceOver(source: Int, destination: Int): Int {
    val sourceAlpha = source ushr 24
    if (sourceAlpha == 255) return source
    val destinationAlpha = destination ushr 24
    val inverseSource = 255 - sourceAlpha
    // Keep alpha at 255x precision until the channels have been normalized.
    // Rounding alpha first changes the denominator for low-alpha overlaps and
    // can create a channel greater than 255 (which then corrupts ARGB bits).
    val outputAlpha255 = sourceAlpha.toLong() * 255L + destinationAlpha.toLong() * inverseSource
    if (outputAlpha255 == 0L) return TRANSPARENT
    fun channel(shift: Int): Int {
        val sourceChannel = source shr shift and 0xff
        val destinationChannel = destination shr shift and 0xff
        val premultiplied = sourceChannel.toLong() * sourceAlpha * 255L +
            destinationChannel.toLong() * destinationAlpha * inverseSource
        return ((premultiplied + outputAlpha255 / 2L) / outputAlpha255).toInt().coerceIn(0, 255)
    }
    val outputAlpha = ((outputAlpha255 + 127L) / 255L).toInt().coerceIn(0, 255)
    return outputAlpha shl 24 or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
