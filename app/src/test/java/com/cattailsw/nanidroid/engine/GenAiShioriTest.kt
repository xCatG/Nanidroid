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
        var baseCalls = 0
        val base = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                baseCalls++
                return ShioriReply(204)
            }
        }
        val engine = GenAiShiori(base) { userText ->
            assertEquals(1, baseCalls)
            assertEquals("hello", userText)
            "\\![open,x] %username"
        }
        assertEquals(
            ShioriReply(200, """\0\s[0]\\!\[open\,x\] \%username\e"""),
            engine.request(ShioriEvent("OnUserInput", listOf("input", "hello")))
        )
        assertEquals(1, baseCalls)
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
    fun cancellationPropagatesAfterOneBaseCall() = runTest {
        val cancellation = CancellationException("session closed")
        var baseCalls = 0
        val base = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                baseCalls++
                return ShioriReply(204)
            }
        }
        try {
            GenAiShiori(base) {
                assertEquals(1, baseCalls)
                throw cancellation
            }.request(ShioriEvent("OnUserInput", listOf("input", "hello")))
            error("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
            assertEquals(1, baseCalls)
        }
    }

    @Test
    fun authoredReplyAndNonUserCommunicationNeverGenerate() = runTest {
        for (event in listOf(
            ShioriEvent("OnUserInput", listOf("box", "hi")),
            ShioriEvent("OnCommunicate", listOf("user", "hi")),
            ShioriEvent("OnCommunicate", listOf("other ghost", "hi"))
        )) {
            val authored = ShioriReply(200, "authored", "Shift_JIS")
            var calls = 0
            val base = object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply { calls++; return authored }
            }
            assertSame(authored, GenAiShiori(base) { error("Must not generate") }.request(event))
            assertEquals(1, calls)
        }
    }

    @Test
    fun userCommunicationFillsOnlyMissingReplyAndFailurePreservesBase() = runTest {
        for (reply in listOf(ShioriReply(204), ShioriReply(200, "  ", "Shift_JIS"))) {
            val event = ShioriEvent("OnCommunicate", listOf("user", "hello"))
            val calls = mutableListOf<String>()
            val base = object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply { calls += "base"; return reply }
            }
            val engine = GenAiShiori(base) { text -> calls += text; "answer" }
            assertEquals(ShioriReply(200, """\0\s[0]answer\e"""), engine.request(event))
            assertEquals(listOf("base", "hello"), calls)
            calls.clear()
            val failing = GenAiShiori(base) { calls += "generator"; throw IllegalStateException("busy") }
            assertSame(reply, failing.request(event))
            assertEquals(listOf("base", "generator"), calls)
        }
    }

    @Test
    fun errorStatusesMissingTextAndOtherSendersPassThrough() = runTest {
        val cases = listOf(
            ShioriEvent("OnUserInput", listOf("box", "hi")) to ShioriReply(500),
            ShioriEvent("OnUserInput", listOf("box")) to ShioriReply(204),
            ShioriEvent("OnCommunicate", listOf("ghost", "hi")) to ShioriReply(204)
        )
        for ((event, reply) in cases) {
            val base = object : ShioriEngine {
                override suspend fun request(event: ShioriEvent) = reply
            }
            assertSame(reply, GenAiShiori(base) { error("Must not generate") }.request(event))
        }
    }
}
