package com.cattailsw.nanidroid.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GenAiShioriTest {
    @Test
    fun otherEventsPassThroughWithoutGeneration() = runTest {
        val event = ShioriEvent("OnBoot", listOf("reference"))
        val reply = ShioriReply(204)
        val base = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                assertEquals(ShioriEvent("OnBoot", listOf("reference")), event)
                return reply
            }
        }
        val engine = GenAiShiori(base) { error("Must not generate") }
        assertSame(reply, engine.request(event))
    }

    @Test
    fun userInputGeneratesEscapedPlainText() = runTest {
        val base = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply = error("Must not delegate")
        }
        val engine = GenAiShiori(base) { userText ->
            assertEquals("hello", userText)
            "\\![open,x] %username"
        }
        assertEquals(
            ShioriReply(200, """\0\s[0]\\!\[open\,x\] \%username\e"""),
            engine.request(ShioriEvent("OnUserInput", listOf("input", "hello")))
        )
    }

    @Test
    fun generatorFailureFallsBackToOriginalEvent() = runTest {
        val original = ShioriEvent("OnUserInput", listOf("input", "hello"))
        val fallback = ShioriReply(200, "authored reply", "Shift_JIS")
        val base = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                assertSame(original, event)
                return fallback
            }
        }
        assertSame(fallback, GenAiShiori(base) { throw IllegalStateException("unavailable") }.request(original))
    }

    @Test
    fun cancellationPropagatesWithoutCallingBase() = runTest {
        val cancellation = CancellationException("session closed")
        val base = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply = error("Must not delegate")
        }
        try {
            GenAiShiori(base) { throw cancellation }.request(ShioriEvent("OnUserInput", listOf("input", "hello")))
            error("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
