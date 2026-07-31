package com.cattailsw.nanidroid

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Characterizes deterministic surface pixels through the real Android graphics
 * framework. The fixtures are original synthetic color matrices, encoded once
 * as tiny PNG byte arrays so the decoder input is stable across platform versions.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceRenderingCharacterizationTest {
    private var fixtureRoot: File? = null

    @Before
    @Throws(Exception::class)
    fun setUp() {
        fixtureRoot = File(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
            "d7a-surface-rendering-" + System.nanoTime()
        )
        Assert.assertTrue("Could not create fixture directory", fixtureRoot!!.mkdirs())
    }

    @After
    fun tearDown() {
        deleteRecursively(fixtureRoot)
    }

    @Test
    @Throws(Exception::class)
    fun testRequiredMigrationInvariant_baseSurfaceUsesUpperLeftColorKeyAndPaddedFallback() {
        val paddedSurface = writeFixture(
            "surface0007.png", PADDED_PNG_BASE64, PADDED_PNG_SHA256
        )
        val surface = ShellSurface(rootPath(), 7)

        Assert.assertEquals(paddedSurface.getAbsolutePath(), surface.selfFilename)
        Assert.assertEquals(3, surface.origW.toLong())
        Assert.assertEquals(2, surface.origH.toLong())
        assertPixelMatrix(
            PADDED_RENDERED_PIXELS,
            renderPixels(surface.getSurfaceDrawable(resources()!!), 3, 2)
        )
    }

    @Test
    @Throws(Exception::class)
    fun testRequiredMigrationInvariant_elementSurfaceComposesDeclaredLayersAtOffsets() {
        writeFixture("base.png", ELEMENT_BASE_PNG_BASE64, ELEMENT_BASE_PNG_SHA256)
        writeFixture(
            "overlay.png",
            ELEMENT_OVERLAY_PNG_BASE64,
            ELEMENT_OVERLAY_PNG_SHA256
        )
        val surface = ShellSurface(
            rootPath(),
            8,
            mutableListOf<String?>(
                "element0,base,base.png,0,0",
                "element1,overlay,overlay.png,1,1"
            )
        )

        Assert.assertEquals(4, surface.origW.toLong())
        Assert.assertEquals(3, surface.origH.toLong())
        assertPixelMatrix(
            ELEMENT_RENDERED_PIXELS,
            renderPixels(surface.getSurfaceDrawable(resources()!!), 4, 3)
        )
    }

    private fun resources(): Resources? {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getResources()
    }

    private fun rootPath(): String {
        return fixtureRoot!!.getAbsolutePath() + File.separator
    }

    @Throws(Exception::class)
    private fun writeFixture(filename: String, encoded: String?, expectedSha256: String?): File {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        Assert.assertEquals("Synthetic fixture bytes changed", expectedSha256, sha256(bytes))
        val fixture = File(fixtureRoot, filename)
        val output = FileOutputStream(fixture)
        try {
            output.write(bytes)
        } finally {
            output.close()
        }
        return fixture
    }

    companion object {
        private val PADDED_PNG_BASE64 =
            ("iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAG0lEQVR42mP4z/D/"
                    + "/3+G/w0gmuE/wz8QARYBANdBEHDHkUDcAAAAAElFTkSuQmCC")
        private const val PADDED_PNG_SHA256 =
            "deae9e08f1ecaafd205e7c93085912b17d30a748a322671220762623d1de8073"
        private val PADDED_RENDERED_PIXELS = intArrayOf(
            Color.TRANSPARENT, -0x7f00ff01, Color.TRANSPARENT,
            -0xff02, -0xff0100, -0x100,
        )

        private val ELEMENT_BASE_PNG_BASE64 =
            ("iVBORw0KGgoAAAANSUhEUgAAAAQAAAADCAYAAAC09K7GAAAAGklEQVR42mP4z/D/"
                    + "v4JDAxiD2AwwDgxjCAAAjIUYsb01IzkAAAAASUVORK5CYII=")
        private const val ELEMENT_BASE_PNG_SHA256 =
            "57d054fb3911ecf5a663fcf7cb7181e61d05a492cc71d5b0597fa672f5f8aa53"
        private val ELEMENT_OVERLAY_PNG_BASE64 =
            ("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFUlEQVR42mNg+P//"
                    + "/38GBjACsf4DAFe6Cffw59yNAAAAAElFTkSuQmCC")
        private const val ELEMENT_OVERLAY_PNG_SHA256 =
            "b2584b961c275a89922cb32f00e11b6c9f637d9761738e097d37ca9d26c4ac19"
        private const val BLUE = -0xdfbf80
        private val ELEMENT_RENDERED_PIXELS = intArrayOf(
            Color.TRANSPARENT, BLUE, BLUE, Color.TRANSPARENT,
            BLUE, BLUE, -0x10000, BLUE,
            BLUE, -0xff0100, -0x100, BLUE,
        )

        private fun renderPixels(drawable: Drawable?, width: Int, height: Int): IntArray {
            Assert.assertNotNull("Production returned no drawable", drawable)
            val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(rendered)
            drawable!!.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            val pixels = IntArray(width * height)
            rendered.getPixels(pixels, 0, width, 0, 0, width, height)
            rendered.recycle()
            return pixels
        }

        private fun assertPixelMatrix(expected: IntArray, actual: IntArray) {
            Assert.assertEquals("Pixel count changed", expected.size.toLong(), actual.size.toLong())
            for (index in expected.indices) {
                Assert.assertEquals(
                    "ARGB pixel changed at row-major index " + index,
                    expected[index].toLong(),
                    actual[index].toLong()
                )
            }
        }

        @Throws(Exception::class)
        private fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val value = StringBuilder(digest.size * 2)
            for (part in digest) {
                value.append(String.format("%02x", part.toInt() and 0xff))
            }
            return value.toString()
        }

        private fun deleteRecursively(file: File?) {
            if (file == null || !file.exists()) {
                return
            }
            if (file.isDirectory()) {
                val children = file.listFiles()
                if (children != null) {
                    for (child in children) {
                        deleteRecursively(child)
                    }
                }
            }
            Assert.assertTrue("Could not delete " + file, file.delete())
        }
    }
}
