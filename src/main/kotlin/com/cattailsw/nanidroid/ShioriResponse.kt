package com.cattailsw.nanidroid

import java.io.BufferedReader
import java.util.Hashtable

class ShioriResponse {
    @JvmField
    var header: String?

    @JvmField
    var resp: Hashtable<String, String>

    @JvmField
    var _ver: ShioriProtocolVersion? = null

    @JvmField
    var stat_code: Int = 500

    constructor(header: String?) : this(header, Hashtable())

    constructor(header: String?, response: Hashtable<String, String>) {
        this.header = header
        resp = response
        parseHeader()
    }

    constructor(reader: BufferedReader) {
        header = try {
            reader.readLine()
        } catch (_: Exception) {
            null
        }
        parseHeader()

        resp = Hashtable()
        while (true) {
            val line = try {
                reader.readLine()
            } catch (_: Exception) {
                null
            } ?: break

            val separator = line.indexOf(":")
            if (separator == -1) continue

            resp[line.substring(0, separator)] = line.substring(separator + 2)
        }
    }

    fun getProtocolVersion(): ShioriProtocolVersion? = _ver

    private fun parseHeader() {
        val matcher = PatternHolders.shiori_res_header_ptrn.matcher(header ?: return)
        if (!matcher.matches()) return

        try {
            stat_code = matcher.group(4)!!.toInt()
            _ver = ShioriProtocolVersion(
                matcher.group(1)!!,
                matcher.group(2)!!.toInt(),
                matcher.group(3)!!.toInt(),
            )
        } catch (_: Exception) {
            // Preserve the legacy parser's default 500 response on malformed headers.
        }
    }

    fun getHeader(): String? = header

    fun getStatusCode(): Int = stat_code

    fun getResponse(): Hashtable<String, String> = resp

    fun getKey(key: String): String? = resp[key]

    override fun toString(): String = buildString {
        append("Response:").append(_ver).append(' ').append(stat_code).append('\n')
        for (key in resp.keys) {
            append(key).append(": ").append(resp[key]).append('\n')
        }
    }
}
