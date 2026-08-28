package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.dialogue.ShioriMethod
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GhostShioriTrafficTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun structuredAndRawRequestsPreserveExactProtocolAndParsedResponses() = RuntimeFixture(
        response = { RESPONSE },
    ).use { fixture ->
        val generation = fixture.requireHandle().generation

        val raw = assertIs<RuntimeResult.Success<TaggedShioriResponse>>(
            fixture.runtime.request(
                generation,
                ShioriRequestIntent.raw(ShioriMethod.GET, "OnMouseClick", listOf("1", "2")),
            ),
        ).value.response
        val structured = assertIs<RuntimeResult.Success<TaggedShioriResponse>>(
            fixture.runtime.request(
                generation,
                ShioriRequestIntent.event("OnTest", listOf("R1", "R2")),
            ),
        ).value.response

        assertEquals(2, fixture.trace.requests.size)
        assertEquals(
            "GET SHIORI/3.0\r\nSender: Nanidroid\r\nSecurityLevel: local\r\nID: OnMouseClick\r\nReference0: 1\r\nReference1: 2\r\n\r\n",
            fixture.trace.requests[0],
        )
        assertEquals(
            "GET SHIORI/3.0\r\nSender: Nanidroid\r\nID: OnTest\r\nSecurityLevel: local\r\nReference0: R1\r\nReference1: R2\r\n\r\n",
            fixture.trace.requests[1],
        )
        assertEquals("ok", raw.getKey("Value"))
        assertEquals("ok", structured.getKey("Value"))
    }

    private companion object {
        const val RESPONSE = "SHIORI/3.0 200 OK\r\nSender: test\r\nValue: ok\r\n\r\n"
    }
}
