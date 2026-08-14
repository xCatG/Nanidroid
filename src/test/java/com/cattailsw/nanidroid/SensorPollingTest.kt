package com.cattailsw.nanidroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.LinkedList

class SensorPollingTest {
    @Test
    fun `fetches and queues SSTP before bottle log`() {
        val events = mutableListOf<String>()

        val succeeded = fetchAndQueueSensorMessages(
            fetchSstp = { listOf("sstp") },
            fetchBottleLog = { listOf("bottle") },
            enqueue = { events += it.single() },
        )

        assertTrue(succeeded)
        assertEquals(listOf("sstp", "bottle"), events)
    }

    @Test
    fun `isolates sensor failures without queuing later sources`() {
        val events = mutableListOf<String>()

        val succeeded = fetchAndQueueSensorMessages(
            fetchSstp = { throw IllegalStateException("offline") },
            fetchBottleLog = { listOf("bottle") },
            enqueue = { events += it.single() },
        )

        assertFalse(succeeded)
        assertTrue(events.isEmpty())
    }
}
