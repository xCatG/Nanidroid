package com.cattailsw.nanidroid

import java.io.BufferedReader
import java.util.Hashtable

class ShioriResponse {
    private val response: Hashtable<String, String>
    private var statusCode: Int = 500

    constructor(header: String?) : this(header, Hashtable())

    constructor(header: String?, response: Hashtable<String, String>) {
        this.response = response
        parseHeader(header)
    }

    constructor(reader: BufferedReader) {
        val header = try {
            reader.readLine()
        } catch (_: Exception) {
            null
        }
        parseHeader(header)

        response = Hashtable()
        while (true) {
            val line = try {
                reader.readLine()
            } catch (_: Exception) {
                null
            } ?: break

            if (line.isEmpty()) break

            val separator = line.indexOf(":")
            if (separator == -1) continue

            val valueStart = (separator + 1).let { start ->
                if (line.getOrNull(start) == ' ') start + 1 else start
            }
            response[line.substring(0, separator)] = line.substring(valueStart)
        }
    }

    private fun parseHeader(header: String?) {
        val matcher = PatternHolders.shiori_res_header_ptrn.matcher(header ?: return)
        if (!matcher.matches()) return

        try {
            statusCode = matcher.group(4)!!.toInt()
        } catch (_: Exception) {
            // Preserve the legacy parser's default 500 response on malformed headers.
        }
    }

    fun getStatusCode(): Int = statusCode

    fun getKey(key: String): String? = response[key]

    fun getKeyIgnoreCase(key: String): String? = response.entries.firstOrNull {
        it.key.equals(key, ignoreCase = true)
    }?.value
}
