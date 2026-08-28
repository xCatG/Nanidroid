package com.cattailsw.nanidroid.runtime.dialogue

import com.cattailsw.nanidroid.RuntimeFixture
import com.cattailsw.nanidroid.RuntimeResult
import com.cattailsw.nanidroid.ShioriRequestIntent
import com.cattailsw.nanidroid.ShioriResponse
import com.cattailsw.nanidroid.assertIs
import com.cattailsw.nanidroid.shiori.ShioriRequestException
import java.io.BufferedReader
import java.io.StringReader
import java.util.Hashtable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class GhostEventCapabilitiesTest {
    @Rule @JvmField val androidStubs = com.cattailsw.nanidroid.HostAndroidStubRule()

    @Test
    fun `supported-events pass-through declares both pointer events`() {
        val capabilities = GhostEventCapabilityDiscovery.fromSupportedEvents(
            rawResponse("X-SSTP-PassThru-local: OnMouseClick,OnMouseDoubleClick"),
        )

        assertEquals(PointerEventCapabilities(Support.SUPPORTED, Support.SUPPORTED), capabilities)
    }

    @Test
    fun `supported-events accepts heterogeneous resource identifiers while matching pointer events`() {
        assertEquals(
            PointerEventCapabilities(Support.SUPPORTED, Support.SUPPORTED),
            GhostEventCapabilityDiscovery.fromSupportedEvents(
                rawResponse("X-SSTP-PassThru-local: sakura.defaultx,OnMouseClick,OnMouseDoubleClick"),
            ),
        )
    }

    @Test
    fun `supported-events absent or malformed pass-through remains unknown`() {
        assertNull(GhostEventCapabilityDiscovery.fromSupportedEvents(response()))
        assertNull(
            GhostEventCapabilityDiscovery.fromSupportedEvents(
                rawResponse("X-SSTP-PassThru-local: not a supported-event payload"),
            ),
        )
    }

    @Test
    fun `supported-events ignores external declarations for local requests`() {
        assertNull(
            GhostEventCapabilityDiscovery.fromSupportedEvents(
                response("X-SSTP-PassThru-external" to "OnMouseClick,OnMouseDoubleClick"),
            ),
        )
    }

    @Test
    fun `an explicit empty local supported-events list declares both pointer events unsupported`() {
        assertEquals(
            PointerEventCapabilities(Support.UNSUPPORTED, Support.UNSUPPORTED),
            GhostEventCapabilityDiscovery.fromSupportedEvents(rawResponse("X-SSTP-PassThru-local:")),
        )
    }

    @Test
    fun `local has-event result maps only exact binary values`() {
        assertEquals(Support.SUPPORTED, GhostEventCapabilityDiscovery.fromHasEvent(response("X-SSTP-PassThru-Result" to "1")))
        assertEquals(Support.UNSUPPORTED, GhostEventCapabilityDiscovery.fromHasEvent(response("X-SSTP-PassThru-Result" to "0")))
        assertEquals(Support.UNKNOWN, GhostEventCapabilityDiscovery.fromHasEvent(response("X-SSTP-PassThru-Result" to "true")))
        assertEquals(Support.UNKNOWN, GhostEventCapabilityDiscovery.fromHasEvent(response()))
    }

    @Test
    fun `non-204 capability responses never declare support`() {
        val nonContent = ShioriResponse(
            "SHIORI/3.0 200 OK",
            Hashtable<String, String>().apply {
                put("X-SSTP-PassThru-local", "OnMouseClick,OnMouseDoubleClick")
                put("X-SSTP-PassThru-Result", "1")
            },
        )

        assertNull(GhostEventCapabilityDiscovery.fromSupportedEvents(nonContent))
        assertEquals(Support.UNKNOWN, GhostEventCapabilityDiscovery.fromHasEvent(nonContent))
    }

    @Test
    fun `discovery falls back to local has-event with exact event id in reference zero`() {
        val queries = mutableListOf<Pair<ShioriMethod, Pair<String, List<String>>>>()
        val capabilities = GhostEventCapabilityDiscovery.discover { method, eventId, references ->
            queries += method to (eventId to references)
            when (eventId) {
                "Get_Supported_Events" -> response()
                "Has_Event" -> when (references.single()) {
                    "OnMouseClick" -> response("X-SSTP-PassThru-Result" to "0")
                    else -> response("X-SSTP-PassThru-Result" to "1")
                }
                else -> error("unexpected event $eventId")
            }
        }

        assertEquals(PointerEventCapabilities(Support.UNSUPPORTED, Support.SUPPORTED), capabilities)
        assertEquals(
            listOf(
                ShioriMethod.GET to ("Get_Supported_Events" to emptyList()),
                ShioriMethod.GET to ("Has_Event" to listOf("OnMouseClick")),
                ShioriMethod.GET to ("Has_Event" to listOf("OnMouseDoubleClick")),
            ),
            queries,
        )
    }

    @Test
    fun `a failed click probe does not prevent double click discovery`() {
        val probedEvents = mutableListOf<String>()
        val capabilities = GhostEventCapabilityDiscovery.discover { _, eventId, references ->
            when (eventId) {
                "Get_Supported_Events" -> response()
                "Has_Event" -> when (references.single().also(probedEvents::add)) {
                    "OnMouseClick" -> throw IllegalStateException("click probe failed")
                    "OnMouseDoubleClick" -> response("X-SSTP-PassThru-Result" to "1")
                    else -> error("unexpected probe")
                }
                else -> error("unexpected event")
            }
        }

        assertEquals(PointerEventCapabilities(Support.UNKNOWN, Support.SUPPORTED), capabilities)
        assertEquals(listOf("OnMouseClick", "OnMouseDoubleClick"), probedEvents)
    }

    @Test
    fun `a failed double click probe does not prevent click discovery`() {
        val probedEvents = mutableListOf<String>()
        val capabilities = GhostEventCapabilityDiscovery.discover { _, eventId, references ->
            when (eventId) {
                "Get_Supported_Events" -> response()
                "Has_Event" -> when (references.single().also(probedEvents::add)) {
                    "OnMouseClick" -> response("X-SSTP-PassThru-Result" to "1")
                    "OnMouseDoubleClick" -> throw IllegalStateException("double click probe failed")
                    else -> error("unexpected probe")
                }
                else -> error("unexpected event")
            }
        }

        assertEquals(PointerEventCapabilities(Support.SUPPORTED, Support.UNKNOWN), capabilities)
        assertEquals(listOf("OnMouseClick", "OnMouseDoubleClick"), probedEvents)
    }

    @Test
    fun `ownership-certain optional probe failure remains unknown and discovery continues`() {
        val probedEvents = mutableListOf<String>()
        val capabilities = GhostEventCapabilityDiscovery.discover { _, eventId, references ->
            when (eventId) {
                "Get_Supported_Events" -> response()
                "Has_Event" -> when (references.single().also(probedEvents::add)) {
                    "OnMouseClick" -> throw ShioriRequestException(
                        "known request failure",
                        ownershipCertain = true,
                    )
                    "OnMouseDoubleClick" -> response("X-SSTP-PassThru-Result" to "1")
                    else -> error("unexpected probe")
                }
                else -> error("unexpected event")
            }
        }

        assertEquals(PointerEventCapabilities(Support.UNKNOWN, Support.SUPPORTED), capabilities)
        assertEquals(listOf("OnMouseClick", "OnMouseDoubleClick"), probedEvents)
    }

    @Test
    fun `ownership-uncertain optional probe failure escapes discovery immediately`() {
        val failure = ShioriRequestException("ownership lost", ownershipCertain = false)

        val thrown = assertThrows(ShioriRequestException::class.java) {
            GhostEventCapabilityDiscovery.discover { _, _, _ -> throw failure }
        }

        assertSame(failure, thrown)
    }

    @Test
    fun `ordinary click response never changes capability state`() {
        val response = response("X-SSTP-PassThru-Result" to "1")

        assertEquals(Support.SUPPORTED, GhostEventCapabilityDiscovery.fromHasEvent(response))
        assertNull(GhostEventCapabilityDiscovery.fromSupportedEvents(response))
    }

    @Test
    fun `raw requests preserve notify method and an empty positional reference`() = RuntimeFixture().use { fixture ->
        assertIs<RuntimeResult.Success<*>>(
            fixture.runtime.request(
                fixture.requireHandle().generation,
                ShioriRequestIntent.raw(
                    ShioriMethod.NOTIFY,
                    "OnSecondChange",
                    listOf("123", "", "0"),
                ),
            ),
        )

        assertEquals(
            "NOTIFY SHIORI/3.0\r\n" +
                "Sender: Nanidroid\r\n" +
                "SecurityLevel: local\r\n" +
                "ID: OnSecondChange\r\n" +
                "Reference0: 123\r\n" +
                "Reference1: \r\n" +
                "Reference2: 0\r\n\r\n",
            fixture.trace.requests.single(),
        )
    }

    private fun response(vararg headers: Pair<String, String>) = ShioriResponse(
        "SHIORI/3.0 204 No Content",
        Hashtable<String, String>().apply { headers.forEach { (key, value) -> put(key, value) } },
    )

    private fun rawResponse(vararg headers: String) = ShioriResponse(
        BufferedReader(StringReader(buildString {
            append("SHIORI/3.0 204 No Content\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("\r\n")
        })),
    )

}
