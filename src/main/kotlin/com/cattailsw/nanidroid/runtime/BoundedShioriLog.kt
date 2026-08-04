package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.ShioriResponse
import java.nio.charset.StandardCharsets.UTF_8

/** Keeps a bounded, in-memory history of recent SHIORI traffic for debug UI. */
class BoundedShioriLog(
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
) {
    init {
        require(maxEvents >= 0) { "maxEvents must not be negative" }
        require(maxPayloadBytes >= 0) { "maxPayloadBytes must not be negative" }
    }

    data class Entry(
        val event: String,
        val request: String,
        val responseStatus: Int,
        val responseValue: String,
    )

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(
        event: String,
        request: Array<out String?>?,
        response: ShioriResponse?,
    ) {
        if (maxEvents == 0) return
        entries.addLast(
            Entry(
                event = event,
                request = truncate(requestText(event, request)),
                responseStatus = response?.getStatusCode() ?: 500,
                responseValue = truncate(response?.getKey("Value") ?: ""),
            ),
        )
        while (entries.size > maxEvents) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    private fun requestText(event: String, request: Array<out String?>?): String = buildString {
        append(event)
        request.orEmpty().forEachIndexed { index, value ->
            append("\nReference")
            append(index)
            append(':')
            append(value.orEmpty())
        }
    }

    private fun truncate(value: String): String {
        if (value.toByteArray(UTF_8).size <= maxPayloadBytes) return value

        val indicatorBytes = TRUNCATION_INDICATOR.toByteArray(UTF_8).size
        if (maxPayloadBytes < indicatorBytes) {
            return utf8Prefix(TRUNCATION_INDICATOR, maxPayloadBytes)
        }
        return utf8Prefix(value, maxPayloadBytes - indicatorBytes) + TRUNCATION_INDICATOR
    }

    private fun utf8Prefix(value: String, byteLimit: Int): String {
        val result = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val segment = String(Character.toChars(codePoint))
            val segmentBytes = segment.toByteArray(UTF_8).size
            if (bytes + segmentBytes > byteLimit) break
            result.append(segment)
            bytes += segmentBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    companion object {
        const val DEFAULT_MAX_EVENTS = 100
        const val DEFAULT_MAX_PAYLOAD_BYTES = 64 * 1024
        private const val TRUNCATION_INDICATOR = "… (truncated)"
    }
}
