package com.cattailsw.nanidroid.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cattailsw.nanidroid.ghost.ShellCatalog
import com.cattailsw.nanidroid.ghost.SurfaceDefinitions
import com.cattailsw.nanidroid.ghost.SurfaceImageLoader
import com.cattailsw.nanidroid.ghost.SurfaceLayer
import com.cattailsw.nanidroid.runtime.PlaybackFrame
import com.cattailsw.nanidroid.runtime.SpeakerFrame
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.runtime.SurfaceVisual
import java.io.File
import java.util.Collections
import org.junit.Assert.assertEquals
import com.cattailsw.nanidroid.testing.OwnedFixtureDirectoryRule
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthoredSurfaceUiTest {
    private val fixtures = OwnedFixtureDirectoryRule()
    val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixtures).around(compose)

    @Test fun missingNumberedStaticAssetDoesNotSuppressValidLaterElement() {
        val shell = fixtures.directory("missing-static-elements-${System.nanoTime()}")
        val numbered = listOf(0, 10).associateWith { id ->
            File(shell, "surface$id.png").apply { writeBytes(byteArrayOf(1)) }
        }
        File(shell, "badge.png").writeBytes(byteArrayOf(1))
        val catalog = ShellCatalog(numbered, SurfaceDefinitions.parse("""
            surface0 {
            element1,overlay,badge.png,0,0
            0interval,always
            0pattern0,1001,0,overlay,0,0
            }
        """.trimIndent()), shell)
        val state = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "", false), SpeakerFrame(10, true, "", false), false),
            "Ghost", "Sakura", "Kero", numbered, shell = catalog)
        val loaded = Collections.synchronizedList(mutableListOf<String>())
        compose.setContent {
            GhostStage(state, SurfaceImageLoader { file ->
                loaded += file.name
                val color = if (file.name == "badge.png") Color.GREEN else Color.RED
                Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                    .asImageBitmap()
            }) { _, _, _ -> }
        }
        compose.onNodeWithTag("sakura").assertExists()
        val displayed = compose.onNodeWithTag("sakura").captureToImage().asAndroidBitmap()
        assertEquals(Color.GREEN, displayed.getPixel(displayed.width / 2, displayed.height / 2))
        assertEquals(1, loaded.count { it == "surface0.png" })
    }

    @Test fun undecodableStaticAssetDoesNotSuppressElementZero() =
        assertInvalidIndexedStatic("undecodable", 0)

    @Test fun unsafeStaticAssetDoesNotSuppressLaterElement() =
        assertInvalidIndexedStatic("unsafe", 1)

    @Test fun offCanvasStaticAssetDoesNotSuppressLaterElement() =
        assertInvalidIndexedStatic("offscreen", 1)

    private fun assertInvalidIndexedStatic(kind: String, elementId: Int) {
        val shell = fixtures.directory("invalid-static-$kind-${System.nanoTime()}")
        val base = File(shell, "surface0.png").apply { writeBytes(byteArrayOf(1)) }
        val kero = File(shell, "surface10.png").apply { writeBytes(byteArrayOf(1)) }
        val static = File(if (kind == "unsafe") shell.parentFile else shell,
            if (kind == "unsafe") "outside-${System.nanoTime()}.png" else "surface1001.png")
            .apply { writeBytes(byteArrayOf(1)) }
        File(shell, "badge.png").writeBytes(byteArrayOf(1))
        val numbered = mapOf(0 to base, 10 to kero, 1001 to static)
        val catalog = ShellCatalog(numbered, SurfaceDefinitions.parse("""
            surface0 {
            element$elementId,overlay,badge.png,0,0
            0interval,always
            0pattern0,1001,0,overlay,${if (kind == "offscreen") 1000 else 0},0
            }
        """.trimIndent()), shell)
        val state = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "", false), SpeakerFrame(10, true, "", false), false),
            "Ghost", "Sakura", "Kero", numbered, shell = catalog)
        val loaded = Collections.synchronizedList(mutableListOf<String>())
        compose.setContent {
            GhostStage(state, SurfaceImageLoader { file ->
                loaded += file.name
                if (kind == "undecodable" && file.name == "surface1001.png")
                    throw IllegalStateException("Undecodable static image")
                val color = when (file.name) {
                    "badge.png" -> Color.GREEN
                    "surface1001.png" -> Color.BLUE
                    else -> Color.RED
                }
                Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                    .asImageBitmap()
            }) { _, _, _ -> }
        }
        compose.onNodeWithTag("sakura").assertExists()
        val displayed = compose.onNodeWithTag("sakura").captureToImage().asAndroidBitmap()
        assertEquals(Color.GREEN, displayed.getPixel(displayed.width / 2, displayed.height / 2))
        if (kind == "unsafe") assertEquals(0, loaded.count { it == static.name })
    }

    @Test fun numberedStaticAlwaysRemainsVisibleWhenLaterElementIsAuthored() =
        assertValidStaticWins(1)

    @Test fun numberedStaticAlwaysRemainsVisibleWhenElementZeroIsAuthored() =
        assertValidStaticWins(0)

    private fun assertValidStaticWins(elementId: Int) {
        val shell = fixtures.directory("numbered-static-elements-${System.nanoTime()}")
        val numbered = listOf(0, 10, 1001).associateWith { id ->
            File(shell, "surface$id.png").apply { writeBytes(byteArrayOf(1)) }
        }
        File(shell, "badge.png").writeBytes(byteArrayOf(1))
        val catalog = ShellCatalog(numbered, SurfaceDefinitions.parse("""
            surface0 {
            element$elementId,overlay,badge.png,0,0
            0interval,always
            0pattern0,1001,0,overlay,10,0
            }
        """.trimIndent()), shell)
        // The authored wait has elapsed on the runtime clock; the due layer is an immutable visual.
        val due = SurfaceVisual(0, listOf(SurfaceLayer(1001, "overlay", 10, 0)), 0, 0)
        val state = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "", false, due), SpeakerFrame(10, true, "", false), false),
            "Ghost", "Sakura", "Kero", numbered, shell = catalog)
        compose.setContent {
            GhostStage(state, SurfaceImageLoader { file ->
                val color = when (file.name) {
                    "surface1001.png" -> Color.BLUE
                    "badge.png" -> Color.GREEN
                    else -> Color.RED
                }
                Bitmap.createBitmap(if (file.name == "surface1001.png") 10 else 20, 20,
                    Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                    .asImageBitmap()
            }) { _, _, _ -> }
        }
        compose.onNodeWithTag("sakura").assertExists()
        val displayed = compose.onNodeWithTag("sakura").captureToImage().asAndroidBitmap()
        assertEquals(Color.GREEN, displayed.getPixel(displayed.width / 4, displayed.height / 2))
        assertEquals(Color.BLUE, displayed.getPixel(displayed.width * 3 / 4, displayed.height / 2))
    }

    @Test fun numberedBaseAlsoLoadsLaterAuthoredElements() =
        assertNumberedAuthoredWithoutStatic(1)

    @Test fun numberedElementZeroReplacesBaseWithoutStaticLayer() =
        assertNumberedAuthoredWithoutStatic(0)

    private fun assertNumberedAuthoredWithoutStatic(elementId: Int) {
        val shell = fixtures.directory("numbered-elements-${System.nanoTime()}")
        val base = File(shell, "surface0.png").apply { writeBytes(byteArrayOf(1)) }
        val kero = File(shell, "surface10.png").apply { writeBytes(byteArrayOf(1)) }
        File(shell, "badge.png").writeBytes(byteArrayOf(1))
        val numbered = mapOf(0 to base, 10 to kero)
        val catalog = ShellCatalog(numbered, SurfaceDefinitions.parse("""
            surface0 {
            element$elementId,overlay,badge.png,0,0
            }
        """.trimIndent()), shell)
        val loaded = Collections.synchronizedList(mutableListOf<String>())
        val state = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "", false), SpeakerFrame(10, true, "", false), false),
            "Ghost", "Sakura", "Kero", numbered, shell = catalog)
        compose.setContent {
            GhostStage(state, SurfaceImageLoader { file ->
                loaded += file.name
                Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(if (file.name == "badge.png") Color.GREEN else Color.RED)
                }.asImageBitmap()
            }) { _, _, _ -> }
        }
        compose.waitUntil(5_000) { "badge.png" in loaded }
        compose.onNodeWithTag("sakura").assertExists()
        assertEquals(1, loaded.count { it == "badge.png" })
        val displayed = compose.onNodeWithTag("sakura").captureToImage().asAndroidBitmap()
        assertEquals(Color.GREEN, displayed.getPixel(displayed.width / 2, displayed.height / 2))
        assertEquals(if (elementId == 0) 0 else 1, loaded.count { it == "surface0.png" })
    }

    @Test fun elementOnlySakuraAndKeroAreRenderedWithoutNumberedFallback() {
        val shell = fixtures.directory("authored-ui-${System.nanoTime()}")
        File(shell, "body.png").writeBytes(byteArrayOf(1))
        File(shell, "menu.png").writeBytes(byteArrayOf(1))
        val other = File(shell, "surface100.png").apply { writeBytes(byteArrayOf(1)) }
        val catalog = ShellCatalog(mapOf(100 to other), SurfaceDefinitions.parse("""
            surface0 {\nelement0,base,body.png,0,0\n}
            surface10 {\nelement0,base,menu.png,0,0\n}
        """.trimIndent().replace("\\n", "\n")), shell)
        val loaded = Collections.synchronizedList(mutableListOf<String>())
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }.asImageBitmap()
        val state = StageState.Ready(
            PlaybackFrame(SpeakerFrame(0, true, "", false), SpeakerFrame(10, true, "", false), false),
            "Ghost", "Sakura", "Kero", mapOf(100 to other), shell = catalog)
        compose.setContent {
            GhostStage(state, SurfaceImageLoader { file -> loaded += file.name; bitmap }) { _, _, _ -> }
        }
        compose.waitUntil(5_000) { loaded.containsAll(listOf("body.png", "menu.png")) }
        compose.onNodeWithTag("sakura").assertExists()
        compose.onNodeWithTag("kero").assertExists()
        assertEquals(1, loaded.count { it == "body.png" })
        assertEquals(1, loaded.count { it == "menu.png" })
    }
}
