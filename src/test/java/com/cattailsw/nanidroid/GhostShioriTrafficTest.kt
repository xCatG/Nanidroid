package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.dialogue.ShioriMethod
import com.cattailsw.nanidroid.shiori.LoadFailureState
import com.cattailsw.nanidroid.shiori.Shiori
import com.cattailsw.nanidroid.shiori.ShioriLoadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class GhostShioriTrafficTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun structuredAndRawRequestsPreserveExactProtocolAndParsedResponses() {
        val shiori = RecordingShiori()
        val ghost = RecordingGhost(shiori)

        val raw = ghost.requestRaw(ShioriMethod.GET, "OnMouseClick", listOf("1", "2"))
        val structured = ghost.doShioriEvent("OnTest", arrayOf("R1", "R2"))

        assertEquals(2, shiori.requests.size)
        assertEquals(
            "GET SHIORI/3.0\r\nSender: Nanidroid\r\nSecurityLevel: local\r\nID: OnMouseClick\r\nReference0: 1\r\nReference1: 2\r\n\r\n",
            shiori.requests[0],
        )
        assertEquals(
            "GET SHIORI/3.0\r\nSender: Nanidroid\r\nID: OnTest\r\nSecurityLevel: local\r\nReference0: R1\r\nReference1: R2\r\n\r\n",
            shiori.requests[1],
        )
        assertEquals("ok", raw.getKey("Value"))
        assertEquals("ok", structured.getKey("Value"))
    }

    @Test
    fun loadedShioriIsUnloadedWhenPostLoadInitializationFails() {
        val shiori = RecordingShiori()
        val failure = IllegalStateException("post-load initialization failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            initializeLoadedShiori(shiori) { throw failure }
        }

        assertSame(failure, thrown)
        assertEquals(1, shiori.unloadCount)
    }

    @Test
    fun cleanupRequiredLoadFailureUnloadsBeforePropagating() {
        val shiori = RecordingShiori()
        val failure = IllegalStateException("native load ownership is uncertain")

        val thrown = assertThrows(IllegalStateException::class.java) {
            throwShioriLoadFailure(
                shiori,
                ShioriLoadResult.Failed(failure, LoadFailureState.CleanupRequired),
            )
        }

        assertSame(failure, thrown)
        assertEquals(1, shiori.unloadCount)
    }

    private class RecordingGhost(recordingShiori: Shiori) : Ghost("traffic-ghost") {
        init {
            shiori = recordingShiori
        }

        override fun loadGhostInfo() {
            mgr = null
            shellDesc = emptyMap()
            ghostDesc = mapOf("name" to "Traffic")
        }

        override fun incrementCreateCount() = Unit
    }

    private class RecordingShiori : Shiori {
        val requests = mutableListOf<String>()
        var unloadCount = 0

        override fun getModuleName(): String = "test"
        override fun request(request: String): String {
            requests += request
            return RESPONSE
        }
        override fun load() = com.cattailsw.nanidroid.shiori.ShioriLoadResult.Loaded
        override fun unloadShiori(): com.cattailsw.nanidroid.shiori.ShioriUnloadResult {
            unloadCount++
            return com.cattailsw.nanidroid.shiori.ShioriUnloadResult.Unloaded
        }

        companion object {
            const val RESPONSE = "SHIORI/3.0 200 OK\r\nSender: test\r\nValue: ok\r\n\r\n"
        }
    }
}
