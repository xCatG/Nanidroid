package com.cattailsw.nanidroid.ghost

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurfaceComposerTest {
    @Test fun elementZeroReplacesNumberedAndLaterElementsLayerInNumericOrder() = runBlocking {
        val shell = freshShell()
        val numbered = file(shell, "surface0.png")
        file(shell, "body.png")
        file(shell, "top.png")
        val catalog = catalog(shell, mapOf(0 to numbered), """
            surface0 {
            element2,overlay,top.png,1,0
            element0,base,body.png,0,0
            }
        """)
        val loaded = mutableListOf<String>()
        val result = SurfaceComposer.composeBase(catalog, 0, SurfaceImageLoader { f ->
            loaded += f.name
            image(2, 1, if (f.name == "top.png") Color.BLUE else Color.RED)
        })!!
        assertEquals(listOf("body.png", "top.png"), loaded)
        assertEquals(Color.BLUE, result.image.asAndroidBitmap().getPixel(1, 0))
        assertEquals(3, result.image.width)
    }

    @Test fun elementOnlyBaseKeepsNegativeOriginAndSkipsUnsafeParts() = runBlocking {
        val shell = freshShell()
        val nested = File(shell, "parts").apply { mkdirs() }
        file(nested, "body.png")
        val catalog = catalog(shell, emptyMap(), """
            surface10 {
            element0,base,parts\body.png,-5,2
            element1,overlay,..\escape.png,0,0
            element2,overlay,missing.png,0,0
            }
        """)
        val result = SurfaceComposer.composeBase(catalog, 10, SurfaceImageLoader { image(2, 3, Color.GREEN) })!!
        assertEquals(-5, result.originX)
        assertEquals(2, result.originY)
        assertEquals(2, result.image.width)
        assertEquals(3, result.image.height)
    }

    @Test fun unknownAndOversizedSurfacesReturnNull() = runBlocking {
        val shell = freshShell()
        file(shell, "huge.png")
        val catalog = catalog(shell, emptyMap(), "surface0 {\nelement0,base,huge.png,0,0\n}")
        assertNull(SurfaceComposer.composeBase(catalog, 99, SurfaceImageLoader { image(1, 1, Color.RED) }))
        assertNull(SurfaceComposer.composeBase(catalog, 0, SurfaceImageLoader { image(8193, 1, Color.RED) }))
    }

    @Test fun linkedNestedPnaPreventsLoadingElement() = runBlocking {
        val shell = freshShell()
        val nested = File(shell, "parts").apply { mkdirs() }
        file(nested, "body.png")
        val outside = file(shell, "outside.pna")
        val mask = File(nested, "body.pna")
        Files.createSymbolicLink(mask.toPath(), outside.toPath())
        try {
            val catalog = catalog(shell, emptyMap(), "surface0 {\nelement0,base,parts/body.png,0,0\n}")
            assertNull(SurfaceComposer.composeBase(catalog, 0, SurfaceImageLoader {
                throw AssertionError("Linked companion mask must stop element loading")
            }))
        } finally {
            Files.delete(mask.toPath())
        }
    }

    private fun catalog(shell: File, numbered: Map<Int, File>, text: String) =
        ShellCatalog(numbered, SurfaceDefinitions.parse(text.trimIndent()), shell)
    private fun freshShell(): File = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "authored-${System.nanoTime()}").apply { mkdirs() }
    private fun file(parent: File, name: String) = File(parent, name).apply { writeBytes(byteArrayOf(1)) }
    private fun image(width: Int, height: Int, color: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()
}
