package com.cattailsw.nanidroid.shiori

import android.util.Log
import java.io.BufferedReader
import java.io.StringReader
import java.util.HashSet
import java.util.Hashtable

open class EchoShiori : Shiori {
    companion object {
        private const val TAG = "EchoShiori"
        private val ignoreId = arrayOf("OnSecondChange")
    }

    protected val igTable = HashSet<String>()
    protected var header: String? = null
    protected var reqTable: Hashtable<String, String>? = null

    init {
        for (s in ignoreId) {
            igTable.add(s)
        }
    }

    override fun getModuleName(): String {
        return "EchoShiori"
    }

    override fun request(req: String): String {
        parseRequest(req)
        return genResponse()
    }

    protected open fun genResponse(): String {
        val table = reqTable
        if (table != null) {
            val id = table["id"]
            if (id != null) {
                return if (!matchIgnoreId(id)) {
                    "SHIORI/3.0 200 OK\r\nSender: EchoShiori\r\nValue: $id\\e"
                } else {
                    "SHIORI/3.0 204 NO CONTENT"
                }
            }
        }
        return "SHIORI/3.0 400 BAD REQUEST"
    }

    override fun terminate() {}

    private fun matchIgnoreId(inVal: String): Boolean {
        return igTable.contains(inVal)
    }

    private fun parseRequest(req: String) {
        val br = BufferedReader(StringReader(req))
        header = try {
            br.readLine()
        } catch (e: Exception) {
            return
        }
        reqTable = Hashtable()
        while (true) {
            val line = try {
                br.readLine()
            } catch (e: Exception) {
                null
            } ?: break

            val colonIdx = line.indexOf(": ")
            if (colonIdx == -1) {
                if (line.isNotEmpty()) {
                    Log.d(TAG, "got a non recognized line\n$line")
                }
                continue
            }

            val key = line.substring(0, colonIdx).lowercase()
            val valStr = line.substring(colonIdx + 2)
            reqTable?.put(key, valStr)
        }
    }

    override fun unloadShiori() {}
}
