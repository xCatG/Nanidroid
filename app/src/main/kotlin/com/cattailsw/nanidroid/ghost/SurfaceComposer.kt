package com.cattailsw.nanidroid.ghost

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ComposedSurface(val image: ImageBitmap, val originX: Int, val originY: Int)

object SurfaceComposer {
    private data class Part(val image: ImageBitmap, val method: String, val x: Int, val y: Int)

    suspend fun composeBase(catalog: ShellCatalog, surfaceId: Int, loader: SurfaceImageLoader,
        numberedBase: ImageBitmap? = null): ComposedSurface? {
        suspend fun loadElement(element: SurfaceElement): ImageBitmap? =
            withContext(Dispatchers.IO) { ShellFiles.resolve(catalog.shellDirectory, element.fileName) }
                ?.let { load(loader, it) }
        val elements = catalog.definitions.definition(surfaceId)?.elements.orEmpty().sortedBy { it.id }
        val parts = mutableListOf<Part>()
        val elementZero = elements.firstOrNull { it.id == 0 }
        val zeroImage = elementZero?.let { loadElement(it) }
        if (zeroImage != null && elementZero != null) {
            parts += Part(zeroImage, "base", elementZero.x, elementZero.y)
        } else {
            val baseFile = withContext(Dispatchers.IO) {
                catalog.numbered[surfaceId]?.takeIf { ShellFiles.isDirectChild(catalog.shellDirectory, it) }
            }
            if (baseFile != null) (numberedBase ?: load(loader, baseFile))?.let { parts += Part(it, "base", 0, 0) }
        }
        for (element in elements) {
            if (element.id == 0) continue
            val image = loadElement(element) ?: continue
            parts += Part(image, if (parts.isEmpty()) "base" else element.method, element.x, element.y)
        }
        if (parts.isEmpty()) return null
        if (parts.size == 1) {
            val part = parts.single()
            if (!validBounds(part.image.width.toLong(), part.image.height.toLong())) return null
            return ComposedSurface(part.image, part.x, part.y)
        }
        return withContext(Dispatchers.Default) {
            val left = parts.minOf { it.x.toLong() }
            val top = parts.minOf { it.y.toLong() }
            val right = parts.maxOf { it.x.toLong() + it.image.width }
            val bottom = parts.maxOf { it.y.toLong() + it.image.height }
            if (!fitsCanvas(left, top, right, bottom)) return@withContext null
            val w = (right - left).toInt()
            val h = (bottom - top).toInt()
            val pixels = IntArray(w * h)
            for (part in parts) {
                val source = IntArray(part.image.width * part.image.height)
                part.image.asAndroidBitmap().getPixels(source, 0, part.image.width, 0, 0,
                    part.image.width, part.image.height)
                for (y in 0 until part.image.height) for (x in 0 until part.image.width) {
                    val dst = ((part.y.toLong() - top + y) * w + part.x - left + x).toInt()
                    val src = source[y * part.image.width + x]
                    pixels[dst] = if (part.method == "interpolate") sourceOver(src, pixels[dst])
                    else sourceOver(pixels[dst], src)
                }
            }
            ComposedSurface(Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap(),
                left.toInt(), top.toInt())
        }
    }

    private suspend fun load(loader: SurfaceImageLoader, file: File): ImageBitmap? = try {
        loader.load(file)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    internal val INT_RANGE = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()

    /** Canvas limit shared by static composition and animated drawing. */
    internal fun validBounds(width: Long, height: Long): Boolean =
        width in 1..8192 && height in 1..8192 && width * height <= 16_777_216L

    internal fun fitsCanvas(left: Long, top: Long, right: Long, bottom: Long): Boolean =
        left in INT_RANGE && top in INT_RANGE && validBounds(right - left, bottom - top)

    private fun sourceOver(below: Int, above: Int): Int {
        val sourceAlpha = above ushr 24
        val destAlpha = below ushr 24
        val outAlpha = sourceAlpha + destAlpha * (255 - sourceAlpha) / 255
        if (outAlpha == 0) return 0
        fun channel(shift: Int): Int {
            val src = (above ushr shift) and 255
            val dst = (below ushr shift) and 255
            return (src * sourceAlpha + dst * destAlpha * (255 - sourceAlpha) / 255) / outAlpha
        }
        return (outAlpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
