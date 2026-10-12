package com.cattailsw.nanidroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.test.runTest
import com.cattailsw.nanidroid.runtime.ScriptPlayer
import com.cattailsw.nanidroid.runtime.HostUiCommand

class BuiltInShioriTest {
    @Test fun bomBeforeCommentIsNotAnEvent() = runTest {
        val engine = BuiltInShiori("\uFEFF; event,string\r\nOnFirstBoot,\\0\\s0Hello World\\e\r\n")
        assertEquals(ShioriReply(200, "\\0\\s0Hello World\\e"), engine.request(ShioriEvent("OnFirstBoot")))
        assertEquals(204, engine.request(ShioriEvent("OnMouseClick")).status)
    }

    @Test fun ghostSwitchSubstitutionIsLiteral() = runTest {
        val engine = BuiltInShiori("OnGhostChanging,Going to %1\$s\nOnGhostChanged,Came from %1\$s")
        val reference = "A\$B\\C"
        assertEquals("Going to A\$B\\\\C", engine.request(ShioriEvent("OnGhostChanging", listOf(reference))).value)
        assertEquals("Came from A\$B\\\\C", engine.request(ShioriEvent("OnGhostChanged", listOf(reference))).value)
    }

    @Test fun missingCloseGetsDefaultResponse() = runTest {
        assertEquals(200, BuiltInShiori("").request(ShioriEvent("OnClose")).status)
    }

    @Test fun ordinaryTableRetainsFirstCommaSyntax() = runTest {
        val engine = BuiltInShiori("OnNanidroidOpenGhosts,\\0A,B\\e\nOnNanidroidOpenDebug,\\1Debug\\e")
        assertEquals(ShioriReply(200, "\\0A,B\\e"), engine.request(ShioriEvent("OnNanidroidOpenGhosts")))
    }
    @Test fun switchReferenceTextSurvivesTagsAndDownstreamNameMacrosLiterally() = runTest {
        val table = "OnGhostChanging,\\_qGoing to %1\$s\\e\nOnGhostChanged,\\_qCame from %1\$s\\e"
        for (engine in listOf(BuiltInShiori(table), BuiltInShiori.bundled(table, true))) {
            for (name in listOf("正常な名前\$値", "%selfname|%username|%keroname",
                "Probe\\![open,developer]", "\\\\![open,developer]", "Trailing\\",
                "\\0\\c\\s[-1]\\q[Choice,OnOther]\\![open,inputbox,x]\\e", "Line\nTab\t\u0001")) {
                for ((id, greeting) in listOf("OnGhostChanging" to "Going to ", "OnGhostChanged" to "Came from ")) {
                    val reply = engine.request(ShioriEvent(id, listOf(name)))
                    val script = ScriptPlayer.substituteNames(reply.value!!, mapOf("selfname" to "Expanded", "username" to "User"))
                    val frame = ScriptPlayer(script).advanceBy(100_000)
                    assertEquals(greeting + name, frame.sakura.text)
                    assertEquals(emptyList<HostUiCommand>(), frame.hostCommands)
                    assertEquals(emptyList<Any>(), frame.sakura.choices)
                    assertNull(frame.input)
                    assertEquals(0, frame.sakura.surfaceId)
                }
            }
        }
    }
    @Test fun ordinaryUnicodeSwitchNamesKeepReplyBytesAndAuthoredCommandsRemainExecutable() = runTest {
        val name = "普通の名前\$値"
        val engine = BuiltInShiori.bundled("OnGhostChanged,\\_qHello %1\$s\\![open,developer]\\e", true)
        val reply = engine.request(ShioriEvent("OnGhostChanged", listOf(name)))
        assertEquals("\\_qHello $name\\![open,developer]\\e", reply.value)
        val frame = ScriptPlayer(reply.value!!).advanceBy(0)
        assertEquals("Hello $name", frame.sakura.text)
        assertEquals(listOf(HostUiCommand.Developer), frame.hostCommands)
    }
    @Test fun descriptorNameMacrosCannotIntroduceScriptOrExpandMacroLookingData() {
        val name = "%keroname\\![open,developer]\\c"
        val serialized = ScriptPlayer.substituteNames("\\_q%selfname\\e", mapOf("selfname" to name, "keroname" to "Wrong"))
        val frame = ScriptPlayer(serialized).advanceBy(0)
        assertEquals(name, frame.sakura.text)
        assertEquals(emptyList<HostUiCommand>(), frame.hostCommands)
    }
}
