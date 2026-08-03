package com.cattailsw.nanidroid.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.SurfaceTransparencyPolicy
import com.cattailsw.nanidroid.runtime.stage.ComposedSurfaceMetrics
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey

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

/** One immutable image/geometry/collision snapshot consumed by adaptive sizing. */
data class ComposedSurface(
    val image: SurfacePixelImage,
    val canvasSize: IntSize,
    val visiblePixelBounds: IntRect?,
    val effectiveCollisions: List<SurfaceCollision>,
    val surfaceKey: SurfaceKey,
    val revision: Long,
    val explicitlyHidden: Boolean,
) {
    fun metrics(): ComposedSurfaceMetrics = ComposedSurfaceMetrics(
        canvasSize = canvasSize,
        visiblePixelBounds = visiblePixelBounds,
        collisions = effectiveCollisions,
        explicitlyHidden = explicitlyHidden,
        surfaceKey = surfaceKey,
        revision = revision,
    )
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
    /** Compatibility image facade retained until the Task 11 host cut-over. */
    fun normal(plan: SurfaceRenderPlan): SurfacePixelImage = composeNormal(plan).image

    fun composeNormal(
        plan: SurfaceRenderPlan,
        explicitlyHidden: Boolean = false,
        revision: Long = 0,
    ): ComposedSurface {
        val image = renderNormal(plan)
        return composed(
            image = image,
            surfaceId = plan.surfaceId,
            collisions = plan.collisions,
            explicitlyHidden = explicitlyHidden,
            revision = revision,
        )
    }

    private fun renderNormal(plan: SurfaceRenderPlan): SurfacePixelImage {
        if (!plan.hasPositiveCanvas()) return SurfacePixelImage.Empty
        return when (val base = plan.base) {
        SurfaceRenderBase.Missing -> SurfacePixelImage.Empty
        is SurfaceRenderBase.Layers -> canvas(plan.width, plan.height).apply {
            base.layers.forEach { layer ->
                layer.imagePath?.let(assets::load)?.withTransparency(plan.transparencyPolicy)?.let { image ->
                    draw(image, layer.x, layer.y)
                }
            }
        }.toImage()
        }
    }

    /** Compatibility image facade retained until the Task 11 host cut-over. */
    fun frame(plan: SurfaceRenderPlan, frame: SurfaceRenderFrame): SurfacePixelImage =
        if (frame is SurfaceRenderFrame.Base) renderLegacyBase(frame) else composeFrame(plan, frame).image

    fun composeFrame(
        plan: SurfaceRenderPlan,
        frame: SurfaceRenderFrame,
        explicitlyHidden: Boolean = false,
        revision: Long = 0,
    ): ComposedSurface {
        if (frame is SurfaceRenderFrame.Base) {
            frame.sourceSurfaceId?.let(plans::find)?.let { source ->
                return composeNormal(source, explicitlyHidden, revision)
            }
            val replacement = frame.imagePath?.let(assets::load)?.withTransparency(plan.transparencyPolicy)
            val image = replacement?.let { source ->
                val width = frame.width.takeIf { it > 0 } ?: source.width
                val height = frame.height.takeIf { it > 0 } ?: source.height
                canvas(width, height).apply { draw(source, 0, 0) }.toImage()
            } ?: SurfacePixelImage.Empty
            return composed(
                image = image,
                surfaceId = frame.sourceSurfaceId?.toIntOrNull(),
                collisions = emptyList(),
                explicitlyHidden = explicitlyHidden,
                revision = revision,
            )
        }
        if (!plan.hasPositiveCanvas()) return composed(
            SurfacePixelImage.Empty,
            plan.surfaceId,
            plan.collisions,
            explicitlyHidden,
            revision,
        )
        val image = when (frame) {
        is SurfaceRenderFrame.Base -> error("base frames are handled before normal-plan rendering")
        is SurfaceRenderFrame.Overlay -> overlay(plan, frame)
        is SurfaceRenderFrame.Reset -> renderNormal(plan)
        is SurfaceRenderFrame.Move -> renderNormal(plan) // SurfaceRenderPlan preserves legacy fallback semantics.
        is SurfaceRenderFrame.Unknown -> renderNormal(plan) // Keep the stage visible until a future policy supports it.
        }
        return composed(image, plan.surfaceId, plan.collisions, explicitlyHidden, revision)
    }

    private fun overlay(plan: SurfaceRenderPlan, frame: SurfaceRenderFrame.Overlay): SurfacePixelImage {
        val overlay = frame.sourceSurfaceId
            ?.let(plans::find)
            ?.let(::renderNormal)
            ?: frame.fallbackImagePath?.let(assets::load)?.withTransparency(plan.transparencyPolicy)
            ?: return renderNormal(plan)
        return canvas(plan.width, plan.height).apply {
            draw(renderNormal(plan), 0, 0)
            draw(overlay, frame.x, frame.y)
        }.toImage()
    }

    /**
     * The current host consumes only an image while retaining selected-surface
     * geometry and collisions. Keep its pre-Task10 BASE behavior until Task 11
     * switches it to the atomic [ComposedSurface] result.
     */
    private fun renderLegacyBase(frame: SurfaceRenderFrame.Base): SurfacePixelImage =
        frame.imagePath?.let(assets::load)?.colorKeyed()?.let { image ->
            val width = frame.width.takeIf { it > 0 } ?: image.width
            val height = frame.height.takeIf { it > 0 } ?: image.height
            canvas(width, height).apply { draw(image, 0, 0) }.toImage()
        } ?: SurfacePixelImage.Empty

    private fun composed(
        image: SurfacePixelImage,
        surfaceId: Int?,
        collisions: List<SurfaceCollision>,
        explicitlyHidden: Boolean,
        revision: Long,
    ): ComposedSurface {
        val canvasSize = IntSize(image.width, image.height)
        return ComposedSurface(
            image = image,
            canvasSize = canvasSize,
            visiblePixelBounds = image.visibleBounds(),
            effectiveCollisions = collisions.toList(),
            surfaceKey = SurfaceKey(surfaceId, canvasSize),
            revision = revision,
            explicitlyHidden = explicitlyHidden,
        )
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

private fun SurfacePixelImage.withTransparency(policy: SurfaceTransparencyPolicy): SurfacePixelImage =
    when (policy) {
        SurfaceTransparencyPolicy.LEGACY_COLOR_KEY -> colorKeyed()
        SurfaceTransparencyPolicy.AUTHORED_ALPHA -> this
    }

private fun SurfacePixelImage.visibleBounds(): IntRect? {
    if (width <= 0 || height <= 0) return null
    var left = width
    var top = height
    var right = 0
    var bottom = 0
    for (y in 0 until height) for (x in 0 until width) {
        if (pixelAt(x, y) ushr 24 == 0) continue
        left = minOf(left, x)
        top = minOf(top, y)
        right = maxOf(right, x + 1)
        bottom = maxOf(bottom, y + 1)
    }
    return if (left < right && top < bottom) IntRect(left, top, right, bottom) else null
}

// Compositing makes several simultaneous ARGB copies (decoded bitmap, keyed
// pixels, canvas, and Compose bitmap), so source assets need the same
// heap-safe ceiling as the production stage rather than a final-image-only cap.
private const val MAX_SURFACE_PIXEL_COUNT = 1 * 1024 * 1024

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
