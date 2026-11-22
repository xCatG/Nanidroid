package com.cattailsw.nanidroid

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.engine.BuiltInShiori
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.ghost.BundledGhostRepository
import com.cattailsw.nanidroid.runtime.ScriptPlayer
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Run explicitly with -e liveNano true on a device provisioned for Nano 4 Fast. */
@RunWith(AndroidJUnit4::class)
class NanoGhostLiveDeviceTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun realModelToolCallPlaysOneGhostLine() = runBlocking {
        assumeTrue(
            "Live Nano test requires -e liveNano true",
            InstrumentationRegistry.getArguments().getString("liveNano") == "true"
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = BundledGhostRepository(
            openAsset = { context.assets.open("nanidroid.zip") },
            ghostRoot = files.newFolder("ghost")
        )
        val locale = Locale.ENGLISH
        val ghost = repository.load(repository.ensureInstalled(), locale)
        val engine = BuiltInShiori.bundled(
            ghost.content,
            debugToolsEnabled = true,
            nanoSpike = nanoGhostSpike(ghost) { locale }
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            val reply = engine.request(ShioriEvent(BuiltInShiori.NANO_SPIKE_EVENT))
            assertEquals(200, reply.status)
            val script = checkNotNull(reply.value)
            val frame = ScriptPlayer(script).advanceBy(15_000)
            val generatedSpeaker = when {
                script.startsWith("\\0\\s[0]") -> frame.sakura
                script.startsWith("\\1\\s[10]") -> frame.kero
                else -> error("Live Nano generation did not produce a ghost turn: ${frame.sakura.text}")
            }
            assertTrue(generatedSpeaker.text.isNotBlank())
            assertEquals(1, listOf(frame.sakura, frame.kero).count { it.text.isNotBlank() })
            assertTrue(frame.hostCommands.isEmpty())
            assertTrue(frame.sakura.choices.isEmpty() && frame.kero.choices.isEmpty())
        }
    }
}
