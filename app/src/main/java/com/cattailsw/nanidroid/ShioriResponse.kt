package com.cattailsw.nanidroid

import java.io.BufferedReader
import java.util.Hashtable
import java.util.regex.Matcher

class ShioriResponse {
    var header: String? = null
    var response: Hashtable<String, String>? = null
    var protocolVersion: ProtocolVersion? = null
        private set
    var statusCode: Int = 500
        private set

    constructor(h: String?) {
        header = h
        response = Hashtable<String, String>(0)
        parseHeader()
    }

    constructor(h: String?, res: Hashtable<String, String>?) {
        header = h
        response = res
        parseHeader()
    }

    constructor(br: BufferedReader) {
        try {
            header = br.readLine()
        } catch (e: Exception) {
            // ignore
        }
        parseHeader()

        response = Hashtable<String, String>()
        var line: String?
        while (true) {
            try {
                line = br.readLine()
            } catch (e: Exception) {
                line = null
            }
            if (line == null) break

            val idx = line.indexOf(":")
            if (idx == -1) continue

            val key = line.substring(0, idx).trim()
            val valStr = line.substring(idx + 1).trim()
            response?.put(key, valStr)
        }
    }

    private fun parseHeader() {
        val h = header ?: return

        val m: Matcher = PatternHolders.shiori_res_header_ptrn.matcher(h)
        if (m.matches()) {
            try {
                val proto = m.group(1) ?: "SHIORI"
                val mj = m.group(2)?.toInt() ?: 3
                val mi = m.group(3)?.toInt() ?: 0
                statusCode = m.group(4)?.toInt() ?: 500
                protocolVersion = ProtocolVersion(proto, mj, mi)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun getKey(key: String): String? {
        return response?.get(key)
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Response:$protocolVersion $statusCode\n")
        response?.let {
            for (k in it.keys) {
                sb.append("$k: ${it[k]}\n")
            }
        }
        return sb.toString()
    }
}
