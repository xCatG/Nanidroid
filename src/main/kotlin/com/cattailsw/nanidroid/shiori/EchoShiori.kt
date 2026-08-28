package com.cattailsw.nanidroid.shiori

import android.util.Log
import java.io.BufferedReader
import java.io.StringReader
import java.util.Hashtable

open class EchoShiori : Shiori {
    private val ignoredIds = hashSetOf(*IGNORE_IDS)
    private var loaded = false

    @JvmField
    protected var header: String? = null

    @JvmField
    protected var reqTable: Hashtable<String, String>? = null

    override fun getModuleName(): String = "EchoShiori"

    override fun load(): ShioriLoadResult {
        if (loaded) {
            return ShioriLoadResult.Failed(
                IllegalStateException("${getModuleName()} is already loaded"),
                LoadFailureState.OwnerAlreadyPresent,
            )
        }
        loaded = true
        return ShioriLoadResult.Loaded
    }

    override fun request(request: String): String {
        if (!loaded) {
            throw ShioriRequestException(
                "${getModuleName()} is not loaded",
                ownershipCertain = true,
            )
        }
        parseRequest(request)
        return genResponse()
    }

    protected open fun genResponse(): String {
        val values = reqTable
        return if (values != null) {
            if (!matchIgnoreId(values["id"])) {
                "SHIORI/3.0 200 OK\r\nSender: EchoShiori\r\nValue: ${values["id"]}\\e"
            } else {
                "SHIORI/3.0 204 NO CONTENT"
            }
        } else {
            "SHIORI/3.0 400 BAD REQUEST"
        }
    }

    private fun matchIgnoreId(value: String?): Boolean = ignoredIds.contains(value)

    private fun parseRequest(request: String) {
        val reader = BufferedReader(StringReader(request))
        header = try {
            reader.readLine()
        } catch (_: Exception) {
            return
        }

        reqTable = Hashtable()
        while (true) {
            val line = try {
                reader.readLine()
            } catch (_: Exception) {
                null
            } ?: break
            val separator = line.indexOf(": ")
            if (separator == -1) {
                if (line.isNotEmpty()) Log.d(TAG, "got a non recognized line\n$line")
                continue
            }
            reqTable!![line.substring(0, separator).lowercase()] = line.substring(separator + 2)
        }
    }

    override fun unloadShiori(): ShioriUnloadResult {
        loaded = false
        return ShioriUnloadResult.Unloaded
    }

    private companion object {
        const val TAG = "EchoShiori"
        val IGNORE_IDS = arrayOf("OnSecondChange")
    }
}
