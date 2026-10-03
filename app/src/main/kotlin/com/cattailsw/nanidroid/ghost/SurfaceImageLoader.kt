package com.cattailsw.nanidroid.ghost

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface SurfaceImageLoader {
    suspend fun load(file: File): ImageBitmap
}

class CachedSurfaceImageLoader : SurfaceImageLoader {
    private val cache = mutableMapOf<String, ImageBitmap>()

    override suspend fun load(file: File): ImageBitmap = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[file.absolutePath] } ?: run {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                ?: throw IllegalStateException("Unable to decode ${file.name}")
            val maskFile = File(file.parentFile, "${file.nameWithoutExtension}.pna")
            if (Files.isSymbolicLink(maskFile.toPath()) ||
                (maskFile.isFile && maskFile.canonicalFile.parentFile != file.parentFile.canonicalFile)) {
                throw SecurityException("PNA mask escapes shell directory")
            }
            val mask = if (maskFile.isFile) {
                BitmapFactory.decodeFile(maskFile.absolutePath)
                    ?: throw IllegalStateException("Unable to decode ${maskFile.name}")
            } else null
            val image = when {
                mask != null -> {
                    require(mask.width == bitmap.width && mask.height == bitmap.height) {
                        "Mask dimensions differ from ${file.name}"
                    }
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    val maskPixels = IntArray(pixels.size)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    mask.getPixels(maskPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    for (index in pixels.indices) {
                        pixels[index] = (Color.red(maskPixels[index]) shl 24) or (pixels[index] and 0xffffff)
                    }
                    Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
                !bitmap.hasAlpha() -> {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val key = pixels[0] and 0xffffff
                    for (index in pixels.indices) {
                        if (pixels[index] and 0xffffff == key) pixels[index] = 0
                    }
                    Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
                else -> bitmap.asImageBitmap()
            }
            synchronized(cache) { cache.getOrPut(file.absolutePath) { image } }
        }
    }
}
