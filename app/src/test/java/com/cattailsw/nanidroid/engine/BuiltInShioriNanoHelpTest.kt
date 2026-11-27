package com.cattailsw.nanidroid.engine

import com.cattailsw.nanidroid.runtime.HostUiCommand
import com.cattailsw.nanidroid.runtime.ScriptPlayer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInShioriNanoHelpTest {
    @Test
    fun asksFromTheDefaultGhostAndShowsTheAuthoredImportAnswer() = runTest {
        val questions = mutableListOf<String>()
        val engine = BuiltInShiori.bundled(
            dictionary,
            debugToolsEnabled = true,
            classifyHelpQuestion = { question ->
                questions += question
                HelpClassification.Topic("IMPORT")
            }
        )
        val menu = engine.request(ShioriEvent("OnMouseClick", listOf("1", "2", "0", "0")))
        val ask = playback(menu).sakura.choices.single { it.label == "Ask Nanidroid" }
        val input = checkNotNull(playback(engine.request(ask.event)).input)
        val reply = engine.request(ShioriEvent("OnUserInput", listOf(input.boxId, "How do I import a ghost?")))
        val answer = playback(reply)
        val open = answer.sakura.choices.single { it.label == "Open Ghosts" }

        assertEquals(listOf("How do I import a ghost?"), questions)
        assertEquals("Pick a NAR archive.", answer.sakura.text.trimEnd())
        assertEquals(listOf(HostUiCommand.GhostExplorer), playback(engine.request(open.event)).hostCommands)
    }

    @Test
    fun unsupportedRepliesReturnToHelpAndReleaseDoesNotOfferThePrompt() = runTest {
        val debug = BuiltInShiori.bundled(
            dictionary,
            debugToolsEnabled = true,
            classifyHelpQuestion = { HelpClassification.Topic("OnNanidroidOpenGhosts") }
        )
        val release = BuiltInShiori.bundled(
            dictionary,
            debugToolsEnabled = false,
            classifyHelpQuestion = { error("unexpected") }
        )
        val menuEvent = ShioriEvent("OnNanidroidHelp")
        val ask = playback(debug.request(menuEvent)).sakura.choices.single { it.label == "Ask Nanidroid" }
        val input = checkNotNull(playback(debug.request(ask.event)).input)

        val fallback = playback(debug.request(ShioriEvent("OnUserInput", listOf(input.boxId, "Hello"))))
        assertNotNull(fallback.sakura.choices.firstOrNull { it.event.id == "OnNanidroidImportHelp" })
        assertFalse(playback(release.request(menuEvent)).sakura.choices.any { it.label == "Ask Nanidroid" })
        assertEquals(204, release.request(ask.event).status)
    }

    @Test
    fun downloadingAndTimedOutClassificationsShowBalloonFeedbackWithRetryChoice() = runTest {
        val responses = ArrayDeque(
            listOf(
                HelpClassification.Downloading(percent = null, startedNow = true),
                HelpClassification.Downloading(percent = 42, startedNow = false),
                HelpClassification.Downloading(percent = null, startedNow = false),
                HelpClassification.TimedOut
            )
        )
        val engine = BuiltInShiori.bundled(
            dictionary,
            debugToolsEnabled = true,
            classifyHelpQuestion = { responses.removeFirst() }
        )
        val ask = playback(engine.request(ShioriEvent("OnNanidroidHelp")))
            .sakura.choices.single { it.label == "Ask Nanidroid" }

        val started = playback(engine.request(ShioriEvent("OnUserInput", listOf("nanidroid-ask-help", "q1"))))
        assertTrue(started.sakura.text.contains("Started downloading Gemini Nano"))
        assertTrue(started.sakura.choices.any { it.label == "Ask Nanidroid" && it.event == ask.event })

        val percent = playback(engine.request(ShioriEvent("OnUserInput", listOf("nanidroid-ask-help", "q2"))))
        assertTrue(percent.sakura.text.contains("42%"))
        assertTrue(percent.sakura.choices.any { it.label == "Ask Nanidroid" && it.event == ask.event })

        val still = playback(engine.request(ShioriEvent("OnUserInput", listOf("nanidroid-ask-help", "q3"))))
        assertTrue(still.sakura.text.contains("still downloading"))
        assertTrue(still.sakura.choices.any { it.label == "Ask Nanidroid" && it.event == ask.event })

        val timedOut = playback(engine.request(ShioriEvent("OnUserInput", listOf("nanidroid-ask-help", "q4"))))
        assertTrue(timedOut.sakura.text.contains("too long"))
        assertTrue(timedOut.sakura.choices.any { it.label == "Ask Nanidroid" && it.event == ask.event })
    }

    @Test
    fun busyClassificationShowsCalmFeedbackWithRetryChoice() = runTest {
        val engine = BuiltInShiori.bundled(
            dictionary,
            debugToolsEnabled = true,
            classifyHelpQuestion = { HelpClassification.Busy }
        )
        val ask = playback(engine.request(ShioriEvent("OnNanidroidHelp")))
            .sakura.choices.single { it.label == "Ask Nanidroid" }

        val busy = playback(engine.request(ShioriEvent("OnUserInput", listOf("nanidroid-ask-help", "q"))))
        assertTrue(busy.sakura.text.contains("busy or cooling down"))
        assertTrue(busy.sakura.choices.any { it.label == "Ask Nanidroid" && it.event == ask.event })
    }

    @Test
    fun unavailableAndFailedFallBackToHelpMenu() = runTest {
        val responses = ArrayDeque(listOf(HelpClassification.Unavailable, HelpClassification.Failed))
        val engine = BuiltInShiori.bundled(
            dictionary,
            debugToolsEnabled = true,
            classifyHelpQuestion = { responses.removeFirst() }
        )

        repeat(2) {
            val frame = playback(engine.request(ShioriEvent("OnUserInput", listOf("nanidroid-ask-help", "q"))))
            assertNotNull(frame.sakura.choices.firstOrNull { it.event.id == "OnNanidroidImportHelp" })
            assertTrue(frame.sakura.choices.any { it.label == "Ask Nanidroid" })
        }
    }

    private fun playback(reply: ShioriReply) = ScriptPlayer(checkNotNull(reply.value)).advanceBy(20_000)

    private val dictionary = """
        OnNanidroidHelp,\0Help\n\q[Import and switch ghosts,OnNanidroidImportHelp]\e
        OnMouseClick@r3=0,\0Help\n\q[Import and switch ghosts,OnNanidroidImportHelp]\e
        OnNanidroidImportHelp,\0Pick a NAR archive.\e
        OnNanidroidOpenGhosts,\![open,ghostexplorer]\e
    """.trimIndent()
}
