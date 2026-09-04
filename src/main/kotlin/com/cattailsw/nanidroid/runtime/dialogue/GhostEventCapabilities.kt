package com.cattailsw.nanidroid.runtime.dialogue

import com.cattailsw.nanidroid.ShioriResponse
import com.cattailsw.nanidroid.shiori.ShioriRequestException

enum class Support { SUPPORTED, UNSUPPORTED, UNKNOWN }

enum class ShioriMethod { GET, NOTIFY }

data class PointerEventCapabilities(
    val click: Support = Support.UNKNOWN,
    val doubleClick: Support = Support.UNKNOWN,
)

/** Parses only explicit local capability declarations from raw SHIORI responses. */
object GhostEventCapabilityDiscovery {
    private const val GET_SUPPORTED_EVENTS = "Get_Supported_Events"
    private const val HAS_EVENT = "Has_Event"
    private const val LOCAL_SUPPORTED_EVENTS_HEADER = "X-SSTP-PassThru-local"
    private const val HAS_EVENT_RESULT_HEADER = "X-SSTP-PassThru-Result"
    private val supportedEventName = Regex("""[A-Za-z_][A-Za-z0-9_.-]*""")

    fun discover(request: (ShioriMethod, String, List<String>) -> ShioriResponse): PointerEventCapabilities {
        optionalProbe {
            request(ShioriMethod.GET, GET_SUPPORTED_EVENTS, emptyList())
        }?.let(::fromSupportedEvents)?.let { return it }
        return PointerEventCapabilities(
            click = probeHasEvent(request, "OnMouseClick"),
            doubleClick = probeHasEvent(request, "OnMouseDoubleClick"),
        )
    }

    private fun probeHasEvent(
        request: (ShioriMethod, String, List<String>) -> ShioriResponse,
        eventId: String,
    ): Support = optionalProbe {
        fromHasEvent(request(ShioriMethod.GET, HAS_EVENT, listOf(eventId)))
    } ?: Support.UNKNOWN

    private inline fun <T> optionalProbe(block: () -> T): T? = try {
        block()
    } catch (failure: ShioriRequestException) {
        if (!failure.ownershipCertain) throw failure
        null
    } catch (_: Throwable) {
        null
    }

    /** Returns null when the resource is unavailable or malformed, so callers can use Has_Event. */
    fun fromSupportedEvents(response: ShioriResponse): PointerEventCapabilities? {
        if (response.getStatusCode() != 204) return null
        val declaration = response.getKeyIgnoreCase(LOCAL_SUPPORTED_EVENTS_HEADER) ?: return null
        val names = if (declaration.isEmpty()) emptySet() else declaration.split(',').map { it.trim() }.also {
            if (it.any { name -> !supportedEventName.matches(name) }) return null
        }.toSet()
        return PointerEventCapabilities(
            click = if ("OnMouseClick" in names) Support.SUPPORTED else Support.UNSUPPORTED,
            doubleClick = if ("OnMouseDoubleClick" in names) Support.SUPPORTED else Support.UNSUPPORTED,
        )
    }

    fun fromHasEvent(response: ShioriResponse): Support = if (response.getStatusCode() == 204) {
        when (response.getKeyIgnoreCase(HAS_EVENT_RESULT_HEADER)) {
            "1" -> Support.SUPPORTED
            "0" -> Support.UNSUPPORTED
            else -> Support.UNKNOWN
        }
    } else Support.UNKNOWN
}
