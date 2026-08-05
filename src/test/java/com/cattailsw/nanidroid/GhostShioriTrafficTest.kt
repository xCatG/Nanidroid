package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.dialogue.ShioriMethod
import com.cattailsw.nanidroid.shiori.Shiori
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GhostShioriTrafficTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun structuredAndRawRequestsShareTheExactBoundedTrafficLog() {
        val ghost = RecordingGhost().apply { setShioriForTesting(EchoShiori()) }

        ghost.requestRaw(ShioriMethod.GET, "OnMouseClick", listOf("1", "2"))
        ghost.doShioriEvent("OnTest", arrayOf("R1", "R2"))

        val entries = ghost.shioriLog.updates.value
        assertEquals(listOf("OnMouseClick", "OnTest"), entries.map { it.event })
        assertTrue(entries.first().request.contains("ID: OnMouseClick\r\n"))
        assertTrue(entries.last().request.contains("Reference1: R2\r\n"))
        assertEquals("ok", entries.first().responseValue)
        assertEquals(EchoShiori.RESPONSE, entries.last().response)
    }

    private class RecordingGhost : Ghost("traffic-ghost") {
        override fun loadGhostInfo() {
            mgr = null
            shellDesc = emptyMap()
            ghostDesc = mapOf("name" to "Traffic")
        }

        override fun incrementCreateCount() = Unit
    }

    private class EchoShiori : Shiori {
        override fun getModuleName(): String = "test"
        override fun request(request: String): String = RESPONSE
        override fun terminate() = Unit
        override fun unloadShiori() = Unit

        companion object {
            const val RESPONSE = "SHIORI/3.0 200 OK\r\nSender: test\r\nValue: ok\r\n\r\n"
        }
    }
}
