package com.cattailsw.nanidroid.engine

import com.cattailsw.nanidroid.runtime.HostUiCommand
import com.cattailsw.nanidroid.runtime.ScriptPlayer
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BuiltInDefaultShioriTest {
    @Test fun authoredReferenceSelectorOverridesPlainEvent() = runTest {
        val engine = BuiltInShiori("OnMouseClick,plain\nOnMouseClick@r3=1,tools")
        assertEquals("tools", engine.request(ShioriEvent("OnMouseClick", listOf("0", "0", "0", "1"))).value)
    }

    @Test fun selectorKeysCannotBeInvokedAsEvents() = runTest {
        val engine = BuiltInShiori("OnMouseClick@r3=1,tools")
        assertEquals(204, engine.request(ShioriEvent("OnMouseClick@r3=1")).status)
    }

    @Test fun selectorPriorityAndCanonicalReferences() = runTest {
        val table = "Event,plain\nEvent@debug,debug\nEvent@r3=1,one\nEvent@r3=1@debug,one-debug\nEvent@r3=2147483647,max"
        fun event(reference: String) = ShioriEvent("Event", listOf("", "", "", reference))
        assertEquals("one-debug", BuiltInShiori.bundled(table, true).request(event("1")).value)
        assertEquals("one", BuiltInShiori.bundled(table, false).request(event("1")).value)
        assertEquals("one", BuiltInShiori(table).request(event("1")).value)
        assertEquals("max", BuiltInShiori(table).request(event("2147483647")).value)
        for (bad in listOf("01", "+1", "-1", " 1", "1 ", "2147483648", "")) {
            assertEquals("plain", BuiltInShiori(table).request(event(bad)).value)
            assertEquals("debug", BuiltInShiori.bundled(table, true).request(event(bad)).value)
        }
        assertEquals("plain", BuiltInShiori(table).request(ShioriEvent("Event")).value)
    }

    @Test fun directSelectorsDeniedAndUnknownKeysLiteralInBothModes() = runTest {
        val table = "Event@r3=0,zero\nEvent@debug,debug\nEvent@r3=0@debug,both\nOnCustom@something,custom"
        for (engine in listOf(BuiltInShiori(table), BuiltInShiori.bundled(table, true))) {
            for (key in listOf("Event@r3=0", "Event@debug", "Event@r3=0@debug")) {
                assertEquals(204, engine.request(ShioriEvent(key)).status)
            }
            assertEquals("custom", engine.request(ShioriEvent("OnCustom@something")).value)
        }
    }

    @Test fun mouseSelectorsRequireFirstThreeIntReferencesButPlainLookupIsCompatible() = runTest {
        for (id in listOf("OnMouseClick", "OnMouseDoubleClick", "OnMouseMove")) {
            val selector = BuiltInShiori("$id@r3=0,selected")
            for (references in listOf(
                emptyList(), listOf("0", "0", "0"),
                listOf("bad", "0", "0", "0"), listOf("0", "2147483648", "0", "0")
            )) {
                assertEquals(204, selector.request(ShioriEvent(id, references)).status)
                assertEquals("plain", BuiltInShiori("$id,plain").request(ShioriEvent(id, references)).value)
            }
            assertEquals("selected", selector.request(ShioriEvent(id, listOf("-1", "+2", "0", "0"))).value)
        }
    }

    @Test fun speakerMappingIsAuthoredAndCloseFallbackDependsOnlyOnConstruction() = runTest {
        val engine = BuiltInShiori.bundled("OnMouseClick@r3=0,\\1Tools\\e\nOnMouseClick@r3=1,\\0Help\\e", true)
        assertEquals("\\1Tools\\e", engine.request(ShioriEvent("OnMouseClick", listOf("0", "0", "0", "0"))).value)
        assertEquals("\\0Help\\e", engine.request(ShioriEvent("OnMouseClick", listOf("0", "0", "0", "1"))).value)
        assertEquals(204, engine.request(ShioriEvent("OnClose")).status)
        assertEquals(200, BuiltInShiori("").request(ShioriEvent("OnClose")).status)
    }

    @Test fun allArchivedLocalesOfferAuthoredHelpAndToolsAndExactHostCommands() = runTest {
        val dictionaries = mutableMapOf<String, String>()
        ZipInputStream(javaClass.getResourceAsStream("/nanidroid.zip")!!).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.endsWith("content.txt")) dictionaries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(3, dictionaries.size)
        for (content in dictionaries.values) {
            assertFalse(
                content.lineSequence().any {
                    it.startsWith("DebugToolsTitle,") || it.startsWith("ShowTouchBounds,") || it.startsWith("Close,")
                }
            )
            assertFalse(content.lineSequence().any { it.startsWith("OnMouseClick,") })
            for (debug in listOf(false, true)) {
                val engine = BuiltInShiori.bundled(content, debug)
                val tap = engine.request(ShioriEvent("OnMouseClick", listOf("0", "0", "0", "1")))
                val tools = engine.request(ShioriEvent("OnNanidroidTools"))
                for (reply in listOf(tap, tools)) {
                    val frame = ScriptPlayer(reply.value!!).advanceBy(100_000)
                    assertEquals(debug, frame.kero.choices.any { it.event.id == "OnNanidroidOpenDebug" })
                    assertTrue(frame.kero.choices.any { it.event.id == "OnNanidroidOpenGhosts" })
                }
                assertTrue(engine.request(ShioriEvent("OnFirstBoot")).value!!.contains("OnNanidroidTools"))
                assertTrue(engine.request(ShioriEvent("OnMouseClick", listOf("0", "0", "0", "0"))).value!!.contains("OnNanidroidIntro"))
                for ((id, command) in mapOf(
                    "OnNanidroidOpenGhosts" to HostUiCommand.GhostExplorer,
                    "OnNanidroidOpenAbout" to HostUiCommand.About, "OnNanidroidOpenDebug" to HostUiCommand.Developer
                )) {
                    assertEquals(listOf(command), ScriptPlayer(engine.request(ShioriEvent(id)).value!!).advanceBy(0).hostCommands)
                }
                assertEquals(204, engine.request(ShioriEvent("OnMouseClick", listOf("x", "0", "0", "0"))).status)
            }
        }
    }
}
