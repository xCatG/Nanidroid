package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.ShioriResponse
import java.nio.charset.StandardCharsets.UTF_8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedShioriLogTest {
    @Test
    fun startsEmptyAndSupportsZeroCapacity() {
        val log = BoundedShioriLog(maxEvents = 0)
        log.record("OnBoot", null, response("value"))
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun recordsSingleEntry() {
        val log = BoundedShioriLog(maxEvents = 1)
        log.record("OnBoot", arrayOf("A", "B"), response("value"))

        val entry = log.snapshot().single()
        assertEquals("OnBoot", entry.event)
        assertEquals("OnBoot\nReference0:A\nReference1:B", entry.request)
        assertEquals(200, entry.responseStatus)
        assertEquals("value", entry.responseValue)
    }

    @Test
    fun retainsExactlyOneHundredEntriesInOrder() {
        val log = BoundedShioriLog()
        repeat(100) { index -> log.record("Event$index", arrayOf("$index"), response("value$index")) }

        val entries = log.snapshot()
        assertEquals(100, entries.size)
        assertEquals("Event0", entries.first().event)
        assertEquals("Event99", entries.last().event)
    }

    @Test
    fun dropsOldestEntryAtOneHundredAndOne() {
        val log = BoundedShioriLog()
        repeat(101) { index -> log.record("Event$index", arrayOf("$index"), response("value$index")) }

        val entries = log.snapshot()
        assertEquals(100, entries.size)
        assertEquals("Event1", entries.first().event)
        assertEquals("Event100", entries.last().event)
    }

    @Test
    fun truncatesRequestAndResponseIndependentlyWithinByteLimit() {
        val log = BoundedShioriLog(maxPayloadBytes = 24)
        log.record("Short", arrayOf("A".repeat(40)), response("ok"))
        log.record("Long", arrayOf("ok"), response("😀".repeat(20)))

        val first = log.snapshot()[0]
        val second = log.snapshot()[1]
        assertTrue(first.request.endsWith("… (truncated)"))
        assertEquals("ok", first.responseValue)
        assertEquals("Long\nReference0:ok", second.request)
        assertTrue(second.responseValue.endsWith("… (truncated)"))
        listOf(first.request, first.responseValue, second.request, second.responseValue).forEach {
            assertTrue(it.toByteArray(UTF_8).size <= 24)
        }
        assertFalse(first.responseValue.endsWith("… (truncated)"))
        assertFalse(second.request.endsWith("… (truncated)"))
    }

    @Test
    fun neverSplitsUnicodeCodePointAtTruncationBoundary() {
        val log = BoundedShioriLog(maxPayloadBytes = 20)
        log.record("E", null, response("😀".repeat(20)))

        val value = log.snapshot().single().responseValue
        assertTrue(value.endsWith("… (truncated)"))
        assertTrue(value.toByteArray(UTF_8).size <= 20)
        assertFalse(value.substringBefore("…").endsWith('\uD83D'))
        assertFalse(value.substringBefore("…").startsWith('\uDE00'))
    }

    @Test
    fun verySmallLimitStillRemainsWithinLimit() {
        val log = BoundedShioriLog(maxPayloadBytes = 4)
        log.record("Long event", null, response("long response"))

        val entry = log.snapshot().single()
        assertTrue(entry.request.toByteArray(UTF_8).size <= 4)
        assertTrue(entry.responseValue.toByteArray(UTF_8).size <= 4)
    }

    @Test
    fun snapshotsDoNotChangeWhenLogIsCleared() {
        val log = BoundedShioriLog(maxEvents = 1)
        log.record("First", null, response("value"))

        val snapshot = log.snapshot()
        log.clear()

        assertTrue(log.snapshot().isEmpty())
        assertEquals("First", snapshot.single().event)
    }

    private fun response(value: String): ShioriResponse = ShioriResponse("SHIORI/3.0 200 OK").also {
        it.resp["Value"] = value
    }
}
