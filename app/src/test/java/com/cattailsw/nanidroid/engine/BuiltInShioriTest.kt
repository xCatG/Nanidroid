package com.cattailsw.nanidroid.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.test.runTest

class BuiltInShioriTest {
    @Test fun bomBeforeCommentIsNotAnEvent() = runTest {
        val engine = BuiltInShiori("\uFEFF; event,string\r\nOnFirstBoot,\\0\\s0Hello World\\e\r\n")
        assertEquals(ShioriReply(200, "\\0\\s0Hello World\\e"), engine.request(ShioriEvent("OnFirstBoot")))
        assertEquals(204, engine.request(ShioriEvent("OnMouseClick")).status)
    }

    @Test fun ghostSwitchSubstitutionIsLiteral() = runTest {
        val engine = BuiltInShiori("OnGhostChanging,Going to %1\$s\nOnGhostChanged,Came from %1\$s")
        val reference = "A\$B\\C"
        assertEquals("Going to A\$B\\C", engine.request(ShioriEvent("OnGhostChanging", listOf(reference))).value)
        assertEquals("Came from A\$B\\C", engine.request(ShioriEvent("OnGhostChanged", listOf(reference))).value)
    }

    @Test fun missingCloseGetsDefaultResponse() = runTest {
        assertEquals(200, BuiltInShiori("").request(ShioriEvent("OnClose")).status)
    }
}
