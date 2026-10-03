package com.cattailsw.nanidroid.ghost

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurfaceImageLoaderTest {
    @Test fun indexedPnaControlsAlphaIncludingOpaqueTopLeftColor() = withImages { directory ->
        val surface = File(directory, "surface0000.png")
        surface.writeBytes(indexedPng(intArrayOf(0xfe00dd, 0x0a141e), intArrayOf(0, 1, 0, 1)))
        File(directory, "surface0000.pna").writeBytes(
            indexedPng(intArrayOf(0x000000, 0x808080, 0xffffff), intArrayOf(0, 1, 2, 2))
        )

        val loader = CachedSurfaceImageLoader()
        val image = runBlocking { loader.load(surface) }
        val bitmap = image.asAndroidBitmap()
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals(128, Color.alpha(bitmap.getPixel(1, 0)))
        assertEquals(255, Color.alpha(bitmap.getPixel(0, 1)))
        assertEquals(0xfe00dd, bitmap.getPixel(0, 1) and 0xffffff)
        assertSame(image, runBlocking { loader.load(surface) })
    }

    @Test fun opaqueIndexedPngUsesTopLeftColorKeyWithoutPna() = withImages { directory ->
        val surface = File(directory, "surface0000.png")
        surface.writeBytes(indexedPng(intArrayOf(0xfe00dd, 0x0a141e), intArrayOf(0, 1, 0, 1)))

        val bitmap = runBlocking { CachedSurfaceImageLoader().load(surface) }.asAndroidBitmap()
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 1)))
        assertEquals(255, Color.alpha(bitmap.getPixel(1, 0)))
    }

    @Test fun pngAlphaSurvivesEvenWhenTopLeftMatchesAnotherPixel() = withImages { directory ->
        val surface = File(directory, "surface0000.png")
        val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.argb(255, 254, 0, 221))
        source.setPixel(1, 0, Color.argb(128, 254, 0, 221))
        surface.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val bitmap = runBlocking { CachedSurfaceImageLoader().load(surface) }.asAndroidBitmap()
        assertEquals(255, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals(128, Color.alpha(bitmap.getPixel(1, 0)))
    }

    @Test fun companionPnaSymlinkOutsideShellIsRejected() = withImages { directory ->
        val surface = File(directory, "surface1001.png")
        surface.writeBytes(indexedPng(intArrayOf(0xfe00dd, 0x0a141e), intArrayOf(0, 1, 0, 1)))
        val outside = File(directory.parentFile, "outside-mask-${System.nanoTime()}.pna")
        outside.writeBytes(indexedPng(intArrayOf(0x000000, 0xffffff), intArrayOf(0, 1, 0, 1)))
        try {
            Files.createSymbolicLink(File(directory, "surface1001.pna").toPath(), outside.toPath())
            val error = runCatching { runBlocking { CachedSurfaceImageLoader().load(surface) } }.exceptionOrNull()
            assertTrue("PNA symlink must be rejected before decoding", error is SecurityException)
        } finally {
            outside.delete()
        }
    }

    private fun withImages(block: (File) -> Unit) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "surface-test-${System.nanoTime()}")
        directory.mkdirs()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private fun indexedPng(palette: IntArray, pixels: IntArray): ByteArray {
        val output = ByteArrayOutputStream()
        val png = DataOutputStream(output)
        png.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(2); writeInt(2); writeByte(8); writeByte(3)
            writeByte(0); writeByte(0); writeByte(0)
        }
        chunk(png, "IHDR", header.toByteArray())
        val colors = ByteArrayOutputStream()
        palette.forEach { color ->
            colors.write(color shr 16 and 255)
            colors.write(color shr 8 and 255)
            colors.write(color and 255)
        }
        chunk(png, "PLTE", colors.toByteArray())
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(byteArrayOf(0, pixels[0].toByte(), pixels[1].toByte(), 0, pixels[2].toByte(), pixels[3].toByte())) }
        chunk(png, "IDAT", compressed.toByteArray())
        chunk(png, "IEND", byteArrayOf())
        return output.toByteArray()
    }

    private fun chunk(output: DataOutputStream, type: String, data: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        output.writeInt(data.size)
        output.write(name)
        output.write(data)
        CRC32().apply {
            update(name)
            update(data)
            output.writeInt(value.toInt())
        }
    }
}
