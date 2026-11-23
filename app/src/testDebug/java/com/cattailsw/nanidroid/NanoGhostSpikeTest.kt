package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.engine.BuiltInShiori
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.runtime.ScriptPlayer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NanoGhostSpikeTest {
    @Test
    fun playsTheChosenSpeakerThroughShioriAndRendersCommandsAsText() = runTest {
        val line = "Hello, %username! \\![open,developer] \\q[Fake choice,OnClose]"
        val engine = BuiltInShiori.bundled("", debugToolsEnabled = true) {
            checkNotNull(compileNanoGhostLine(line, "kero", setOf(0, 10)))
        }

        val reply = engine.request(ShioriEvent(BuiltInShiori.NANO_SPIKE_EVENT))
        val script = ScriptPlayer.substituteNames(checkNotNull(reply.value), mapOf("username" to "User"))
        val frame = ScriptPlayer(script).advanceBy(5_000)

        assertEquals(200, reply.status)
        assertEquals(line, frame.kero.text)
        assertEquals(10, frame.kero.surfaceId)
        assertTrue(frame.hostCommands.isEmpty())
        assertTrue(frame.kero.choices.isEmpty())
    }

    @Test
    fun rejectsUnknownSpeakersMissingSurfacesAndUnboundedDialogue() {
        assertNull(compileNanoGhostLine("Hello", "unknown", setOf(0, 10)))
        assertNull(compileNanoGhostLine("Hello", "kero", setOf(0)))
        assertNull(compileNanoGhostLine("a".repeat(241), "sakura", setOf(0)))
        assertNull(compileNanoGhostLine("first\nsecond", "sakura", setOf(0)))
    }

    @Test
    fun playsWrappedDialogueWithAnAllowedSpeakerAndSurface() {
        val line = "Hello there. What would you like to discuss?"
        val modelText = "sakura\nHello there.\nWhat would you like to discuss?"
        val reply = checkNotNull(compileNanoGhostText(modelText, setOf(0, 10)))
        val frame = ScriptPlayer(checkNotNull(reply.value)).advanceBy(5_000)

        assertEquals(line, frame.sakura.text)
        assertNull(compileNanoGhostText(line, setOf(0, 10)))
        assertNull(compileNanoGhostText("Narrator\n$line", setOf(0, 10)))
        assertNull(compileNanoGhostText("sakura\n", setOf(0, 10)))
        assertNull(compileNanoGhostText("kero\n$line", setOf(0)))
    }

    @Test
    fun normalShioriEventsAndNonDebugBundledModeDoNotRunTheSpike() = runTest {
        val debug = BuiltInShiori.bundled("OnBoot,Welcome", debugToolsEnabled = true) { error("unrequested") }
        val release = BuiltInShiori.bundled("", debugToolsEnabled = false) { error("unrequested") }

        assertEquals("Welcome", debug.request(ShioriEvent("OnBoot")).value)
        assertEquals(204, release.request(ShioriEvent(BuiltInShiori.NANO_SPIKE_EVENT)).status)
    }
}
