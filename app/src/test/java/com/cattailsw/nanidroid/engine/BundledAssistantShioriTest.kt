package com.cattailsw.nanidroid.engine

import com.cattailsw.nanidroid.ghost.BundledAssistantContent
import com.cattailsw.nanidroid.runtime.PlaybackFrame
import com.cattailsw.nanidroid.runtime.ScriptPlayer
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BundledAssistantShioriTest {
    @Test fun sakuraTapOpensHelp() = runTest {
        for (language in listOf("en", "ja")) {
            val reply = assistant(language).request(tap("0"))
            assertEquals(200, reply.status)
            assertNull(reply.appAction)
            val frame = play(reply)
            assertEquals(setOf("OnNanidroidIntro", "OnNanidroidImportHelp",
                "OnNanidroidInteractionHelp", "OnNanidroidDismiss"),
                frame.sakura.choices.map { it.event.id }.toSet())
            assertTrue(frame.kero.choices.isEmpty())
            assertEquals(if (language == "en") "Dismiss" else "閉じる",
                frame.sakura.choices.last().label)
        }
    }

    @Test fun droidTapOpensTools() = runTest {
        for (language in listOf("en", "ja")) {
            // Coordinates and Reference2 deliberately differ from the speaker.
            val reply = assistant(language).request(tap("1"))
            assertEquals(200, reply.status)
            assertNull(reply.appAction)
            val frame = play(reply)
            assertEquals(setOf("OnNanidroidOpenGhosts", "OnNanidroidOpenAbout",
                "OnNanidroidOpenDebug", "OnNanidroidDismiss"),
                frame.kero.choices.map { it.event.id }.toSet())
            assertTrue(frame.sakura.choices.isEmpty())
            assertEquals(if (language == "en") "Dismiss" else "閉じる",
                frame.kero.choices.last().label)
        }
    }

    @Test fun invalidSpeakerHasNoAction() = runTest {
        val engine = assistant()
        val malformed = listOf(emptyList(), listOf("15", "20", "0"),
            listOf("x", "20", "0", "1"), listOf("15", "y", "0", "1"),
            listOf("15", "20", "button", "1")) +
            listOf("", "-1", "2", "speaker", "01").map { tap(it).references }
        for (references in malformed) {
            assertEquals(ShioriReply(204), engine.request(ShioriEvent("OnMouseClick", references)))
        }
        for (id in listOf("OnNanidroidHelp", "OnNanidroidIntro", "OnNanidroidImportHelp",
            "OnNanidroidInteractionHelp", "OnNanidroidTools", "OnNanidroidOpenGhosts",
            "OnNanidroidOpenAbout", "OnNanidroidOpenDebug", "OnNanidroidDismiss")) {
            assertEquals(ShioriReply(204), engine.request(ShioriEvent(id, listOf("unexpected"))))
        }
    }

    @Test fun helpTopicsReturnBackChoices() = runTest {
        for (language in listOf("en", "ja")) {
            val engine = assistant(language)
            val menu = play(engine.request(ShioriEvent("OnNanidroidHelp")))
            for (choice in menu.sakura.choices.filter { it.event.id != "OnNanidroidDismiss" }) {
                val reply = engine.request(choice.event)
                assertEquals(200, reply.status)
                assertNull(reply.appAction)
                val frame = play(reply)
                assertTrue(frame.sakura.text.isNotBlank())
                assertEquals(listOf("OnNanidroidHelp", "OnNanidroidDismiss"),
                    frame.sakura.choices.map { it.event.id })
                assertEquals(if (language == "en") "Back" else "戻る", frame.sakura.choices.first().label)
                assertTrue(frame.kero.choices.isEmpty())
                val back = play(engine.request(frame.sakura.choices.first().event))
                assertEquals(menu.sakura.choices.map { it.event.id }, back.sakura.choices.map { it.event.id })
            }
            val dismissed = play(engine.request(ShioriEvent("OnNanidroidDismiss")))
            assertTrue(dismissed.ended)
            assertEquals("", dismissed.sakura.text)
            assertEquals("", dismissed.kero.text)
            assertTrue(dismissed.sakura.choices.isEmpty() && dismissed.kero.choices.isEmpty())
            assertFalse(dismissed.sakura.balloonVisible || dismissed.kero.balloonVisible)
        }
    }

    @Test fun toolsReturnTypedActions() = runTest {
        val expected = mapOf("OnNanidroidOpenGhosts" to BuiltInAppAction.Ghosts,
            "OnNanidroidOpenAbout" to BuiltInAppAction.About,
            "OnNanidroidOpenDebug" to BuiltInAppAction.DebugTools)
        for (language in listOf("en", "ja")) {
            val engine = assistant(language)
            val menu = play(engine.request(ShioriEvent("OnNanidroidTools")))
            assertEquals(expected.keys + "OnNanidroidDismiss", menu.kero.choices.map { it.event.id }.toSet())
            for (choice in menu.kero.choices.filter { it.event.id != "OnNanidroidDismiss" }) {
                assertTrue(choice.event.references.isEmpty())
                assertEquals(ShioriReply(200, appAction = expected.getValue(choice.event.id)),
                    engine.request(choice.event))
            }
        }
    }

    @Test fun releaseHidesAndRejectsDebug() = runTest {
        for (language in listOf("en", "ja")) {
            val engine = assistant(language, debug = false)
            for (event in listOf(tap("1"), ShioriEvent("OnNanidroidTools"))) {
                val menu = play(engine.request(event))
                assertEquals(setOf("OnNanidroidOpenGhosts", "OnNanidroidOpenAbout", "OnNanidroidDismiss"),
                    menu.kero.choices.map { it.event.id }.toSet())
                assertFalse(menu.kero.text.contains("Debug") || menu.kero.text.contains("デバッグ"))
            }
            assertEquals(ShioriReply(204), engine.request(ShioriEvent("OnNanidroidOpenDebug")))
            assertEquals(ShioriReply(204), engine.request(ShioriEvent("OnNanidroidToolsDebug")))
            assertEquals(BuiltInAppAction.Ghosts,
                engine.request(ShioriEvent("OnNanidroidOpenGhosts")).appAction)
        }
    }

    @Test fun firstBootExplainsBothRoles() = runTest {
        for (language in listOf("en", "ja")) {
            val engine = assistant(language)
            val first = play(engine.request(ShioriEvent("OnFirstBoot", listOf("0"))))
            assertTrue(first.sakura.text.isNotBlank())
            assertTrue(first.kero.text.isNotBlank())
            assertTrue(first.sakura.text.contains("郭嘉榆"))
            assertTrue(first.kero.text.contains("ドロイド君"))
            assertEquals(setOf("OnNanidroidHelp", "OnNanidroidTools", "OnNanidroidDismiss"),
                (first.sakura.choices + first.kero.choices).map { it.event.id }.toSet())
            val later = play(engine.request(ShioriEvent("OnBoot", listOf("master"))))
            assertTrue(later.sakura.text.isNotBlank())
            assertTrue(later.sakura.text.length + later.kero.text.length <
                first.sakura.text.length + first.kero.text.length)
        }
    }

    @Test fun unhandledEventsDelegate() = runTest {
        val table = BuiltInShiori("OnMouseDoubleClick,Double\nOnMouseMove,Move\n" +
            "OnGhostChanging,Going to %1\$s\nOnGhostChanged,Came from %1\$s\n" +
            "OnCustom,Custom\nOnChoiceSelect,Ordinary choice")
        val engine = assistant(delegate = table)
        for (id in listOf("OnMouseDoubleClick", "OnMouseMove", "OnGhostChanging",
            "OnGhostChanged", "OnClose", "OnCustom", "OnChoiceSelect", "Unknown")) {
            val event = ShioriEvent(id, listOf("A\$B\\C"))
            assertEquals(table.request(event), engine.request(event))
            assertNull(engine.request(event).appAction)
        }
    }

    private fun assistant(language: String = "en", debug: Boolean = true,
        delegate: ShioriEngine = BuiltInShiori("")) = BundledAssistantShiori(
        delegate, BundledAssistantContent.load({ File("src/main/assets", it).inputStream() }, language), debug)

    private fun tap(speaker: String) = ShioriEvent("OnMouseClick", listOf("15", "20", "0", speaker, "", "0", "touch"))

    private fun play(reply: ShioriReply): PlaybackFrame {
        assertEquals(200, reply.status)
        val script = ScriptPlayer.substituteNames(requireNotNull(reply.value),
            mapOf("selfname" to "郭嘉榆", "keroname" to "ドロイド君"))
        return ScriptPlayer(script).advanceBy(100_000)
    }
}
