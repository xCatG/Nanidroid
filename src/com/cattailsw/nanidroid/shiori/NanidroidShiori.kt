package com.cattailsw.nanidroid.shiori

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.Hashtable
import java.util.Locale

open class NanidroidShiori() : EchoShiori() {
    private var evtTable: Hashtable<String, String>? = null
    private var mCtx: Context? = null
    private var rootpath: String? = null

    constructor(ctx: Context?, path: String) : this() {
        mCtx = ctx
        rootpath = path

        val userLocale = Locale.getDefault().language
        var locDir = File(rootpath, userLocale)
        Log.d(TAG, "loc dir=${locDir.absolutePath}")
        if (!locDir.exists()) {
            Log.d(TAG, "loc dir=${locDir.absolutePath} not found")
            locDir = File(rootpath, "ja")
        }

        try {
            readContent(File(locDir, CONTENT_FILE_NAME))
        } catch (_: IOException) {
            // Legacy behavior intentionally ignores unreadable content files.
        } catch (exception: Exception) {
            exception.printStackTrace()
        }
    }

    @Throws(IOException::class)
    private fun readContent(contentFile: File) {
        if (contentFile.exists()) {
            evtTable = Hashtable()

            val input = FileInputStream(contentFile)
            val reader = BufferedReader(InputStreamReader(input, Charset.forName("UTF-8")))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith(";")) continue
                val index = line.indexOf(',')
                if (index == -1) continue

                evtTable!![line.substring(0, index)] = line.substring(index + 1)
            }
        }
    }

    override fun terminate() = Unit

    override fun getModuleName(): String = "NanidroidShiori"

    override fun genResponse(): String {
        val values = reqTable ?: return super.genResponse()
        if (mCtx == null) return super.genResponse()

        // The Java implementation throws when a request is missing ID. Keep that
        // observable parser behavior instead of silently treating it as 204.
        val requestId = values["id"]!!
        return when {
            requestId.equals("OnGhostChanging", ignoreCase = true) -> handleGhostChanging()
            requestId.equals("OnGhostChanged", ignoreCase = true) -> handleGhostChanged()
            requestId.equals("OnClose", ignoreCase = true) -> handleOnClose()
            requestId.equals("OnBoot", ignoreCase = true) ||
                requestId.equals("OnFirstBoot", ignoreCase = true) -> handleOnBoot()
            requestId.equals("OnInstallFailure", ignoreCase = true) -> handleInstallFail()
            evtTable!![requestId] != null -> RES_HEADER + evtTable!![requestId] + RES_END
            else -> RES_NO_CONTENT
        }
    }

    private fun handleGhostChanging(): String {
        val script = evtTable!!["OnGhostChanging"] ?: return RES_NO_CONTENT
        return RES_HEADER + String.format(script, reqTable!!["reference0"]) + RES_END
    }

    private fun handleGhostChanged(): String {
        val script = evtTable!!["OnGhostChanged"] ?: return RES_NO_CONTENT
        return RES_HEADER + String.format(script, reqTable!!["reference0"]) + RES_END
    }

    private fun handleOnClose(): String {
        val script = evtTable!!["OnClose"] ?: return RES_HEADER + "OnClose" + RES_END
        return RES_HEADER + script + RES_END
    }

    private fun handleInstallFail(): String {
        val script = evtTable!!["OnInstallFailure"] ?: return RES_NO_CONTENT
        return RES_HEADER + script + RES_END
    }

    private fun handleOnBoot(): String {
        val script = evtTable!!["OnBoot"] ?: return RES_NO_CONTENT
        return RES_HEADER + script + RES_END
    }

    companion object {
        private const val TAG = "NanidroidShiori"

        @JvmField
        val RES_NO_CONTENT = "SHIORI/3.0 204 NO CONTENT"

        private const val RES_HEADER = "SHIORI/3.0 200 OK\r\nSender: $TAG\r\nValue: "
        private const val RES_END = "\r\nCharset: UTF-8\r\n"
        private const val CONTENT_FILE_NAME = "content.txt"
    }
}
