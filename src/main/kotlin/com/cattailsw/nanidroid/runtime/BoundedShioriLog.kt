package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.ShioriResponse
import java.nio.charset.StandardCharsets.UTF_8
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        val id: Long,
        val event: String,
        val request: String,
        val responseStatus: Int,
        val responseValue: String,
        val response: String = responseValue,
    )

    private val entries = ArrayDeque<Entry>()
    private var nextEntryId = 0L
    private val mutableEntries = MutableStateFlow<List<Entry>>(emptyList())
    val updates: StateFlow<List<Entry>> = mutableEntries.asStateFlow()

    @Synchronized
    fun record(
        event: String,
        request: Array<out String?>?,
        response: ShioriResponse?,
    ) {
        if (maxEvents == 0) return
        append(
            event = event,
            request = requestText(event, request),
            responseStatus = response?.getStatusCode() ?: 500,
            responseValue = response?.getKey("Value") ?: "",
            response = response?.toString().orEmpty(),
        )
    }

    @Synchronized
    fun append(
        event: String,
        request: String,
        responseStatus: Int,
        responseValue: String,
        response: String,
    ) {
        if (maxEvents == 0) return
        entries.addLast(
            Entry(
                id = nextEntryId++,
                event = truncate(event),
                request = truncate(request),
                responseStatus = responseStatus,
                responseValue = truncate(responseValue),
                response = truncate(response),
            ),
        )
        while (entries.size > maxEvents) entries.removeFirst()
        mutableEntries.value = entries.toList()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        mutableEntries.value = emptyList()
    }

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
