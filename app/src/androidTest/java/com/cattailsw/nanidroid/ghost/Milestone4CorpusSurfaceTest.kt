package com.cattailsw.nanidroid.ghost

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.runtime.SurfaceAnimator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only with separately hash-verified corpus copies staged in app-private storage. */
@RunWith(AndroidJUnit4::class)
class Milestone4CorpusSurfaceTest {
    @Test fun loboElementOnlyZeroAndTenUseTheirAuthoredImages() = runBlocking {
        val shell = shell("task7-lobo")
        val body = File(shell, "body1.png")
        val menu = File(shell, "menu_foreground.png")
        assumeTrue("LOBO corpus copy not staged", File(shell, "surfaces.txt").isFile)
        assertFalse(File(shell, "surface0.png").exists())
        assertFalse(File(shell, "surface10.png").exists())
        val catalog = loadShellCatalog(shell, emptyMap())
        val loader = CachedSurfaceImageLoader()
        val zero = SurfaceComposer.composeBase(catalog, 0, loader)
        val ten = SurfaceComposer.composeBase(catalog, 10, loader)
        assertNotNull(zero)
        assertNotNull(ten)
        assertTrue(zero!!.image.asAndroidBitmap().sameAs(loader.load(body).asAndroidBitmap()))
        assertTrue(ten!!.image.asAndroidBitmap().sameAs(loader.load(menu).asAndroidBitmap()))
        println("M4_CORPUS LOBO zero=${zero.image.width}x${zero.image.height} " +
            "ten=${ten.image.width}x${ten.image.height} origins=${zero.originX},${zero.originY};${ten.originX},${ten.originY}")
    }

    @Test fun earthquakeStaticPartsAndAuthoredMoveSequence() = runBlocking {
        val shell = shell("task7-earthquake")
        assumeTrue("Earthquake corpus copy not staged", File(shell, "surfaces.txt").isFile)
        val definitions = SurfaceDefinitions.read(File(shell, "surfaces.txt"))
        val loader = CachedSurfaceImageLoader()
        val static = SurfaceAnimator(definitions) { 0L }
        static.setSurface(0, 0)
        val parts = static.advanceBy(50, emptySet())!!.getValue(0).layers
        assertTrue("Earthquake static parts are not animated", parts.isNotEmpty())

        val animator = SurfaceAnimator(definitions) { 0L }
        animator.setSurface(0, 1)
        assertEquals(0, animator.advanceBy(0, emptySet())!!.getValue(0).offsetX)
        val first = animator.advanceBy(100, emptySet())!!.getValue(0)
        val second = animator.advanceBy(100, emptySet())!!.getValue(0)
        assertEquals(10, first.offsetX)
        assertEquals(12, second.offsetX)
        assertNotNull(SurfaceComposer.composeBase(
            loadShellCatalog(shell, mapOf(1303 to File(shell, "surface1303.png"))), 1303, loader))
        println("M4_CORPUS EARTHQUAKE staticLayers=${parts.size} move=${first.offsetX},${second.offsetX}; rendered pixels checked by Milestone4CorpusDynamicUiTest")
    }

    private fun shell(id: String): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        "ghost/${InstrumentationRegistry.getArguments().getString("fixtureId") ?: id}/shell/master",
    )
}
