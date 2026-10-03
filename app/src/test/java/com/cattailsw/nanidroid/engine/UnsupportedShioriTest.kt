package com.cattailsw.nanidroid.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UnsupportedShioriTest {
    @Test fun bootAndChangeAndCloseRemainUsable() = runTest {
        val engine = UnsupportedShiori()
        for (id in listOf("OnBoot", "OnFirstBoot", "OnGhostChanged")) {
            val reply = engine.request(ShioriEvent(id))
            assertEquals(200, reply.status)
            assertTrue(reply.value!!.contains("SHIORI"))
        }
        val changing = engine.request(ShioriEvent("OnGhostChanging", listOf("Next Ghost")))
        assertEquals(200, changing.status)
        assertTrue(changing.value!!.contains("Next Ghost"))
        val close = engine.request(ShioriEvent("OnClose"))
        assertEquals(200, close.status)
        assertTrue(close.value!!.contains("Thank"))
        assertEquals(204, engine.request(ShioriEvent("OnMouseClick")).status)
    }
}
