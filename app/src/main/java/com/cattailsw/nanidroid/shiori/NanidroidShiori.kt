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

class NanidroidShiori : EchoShiori {
    companion object {
        private const val TAG = "NanidroidShiori"
        const val RES_NO_CONTENT = "SHIORI/3.0 204 NO CONTENT"
        private const val RES_HEADER = "SHIORI/3.0 200 OK\r\nSender: $TAG\r\nValue: "
        private const val RES_END = "\r\nCharset: UTF-8\r\n"
        private const val CONTENT_FILE_NAME = "content.txt"
    }

    private var evtTable: Hashtable<String, String>? = null
    private var mCtx: Context? = null
    private var rootpath: String? = null

    constructor() : super()

    constructor(ctx: Context, path: String) : super() {
        mCtx = ctx
        rootpath = path

        val userLocale = Locale.getDefault().language
        var locDir = File(rootpath!!, userLocale)
        Log.d(TAG, "loc dir=${locDir.absolutePath}")
        if (!locDir.exists()) {
            Log.d(TAG, "loc dir=${locDir.absolutePath} not found")
            locDir = File(rootpath!!, "ja")
        }

        try {
            readContent(File(locDir, CONTENT_FILE_NAME))
        } catch (e: IOException) {
            // ignore
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun readContent(contentFile: File) {
        if (contentFile.exists()) {
            evtTable = Hashtable()
            val isStream = FileInputStream(contentFile)
            val br = BufferedReader(InputStreamReader(isStream, Charset.forName("UTF-8")))
            while (true) {
                val line = br.readLine() ?: break
                if (line.startsWith(";")) continue
                val idx = line.indexOf(',')
                if (idx == -1) continue // bad line

                val key = line.substring(0, idx)
                val valStr = line.substring(idx + 1)
                evtTable?.put(key, valStr)
            }
        }
    }

    override fun terminate() {}

    override fun getModuleName(): String {
        return "NanidroidShiori"
    }

    override fun genResponse(): String {
        val table = reqTable
        if (table == null || mCtx == null) {
            return super.genResponse()
        }

        val reqId = table["id"] ?: return RES_NO_CONTENT
        if (reqId.equals("OnGhostChanging", ignoreCase = true)) {
            return handleGhostChanging()
        } else if (reqId.equals("OnGhostChanged", ignoreCase = true)) {
            return handleGhostChanged()
        } else if (reqId.equals("OnClose", ignoreCase = true)) {
            return handleOnClose()
        } else if (reqId.equals("OnBoot", ignoreCase = true) || reqId.equals("OnFirstBoot", ignoreCase = true)) {
            return handleOnBoot()
        } else if (reqId.equals("OnInstallFailure", ignoreCase = true)) {
            return handleInstallFail()
        }

        val evtVal = evtTable?.get(reqId)
        if (evtVal != null) {
            return RES_HEADER + evtVal + RES_END
        }

        return RES_NO_CONTENT
    }

    private fun handleGhostChanging(): String {
        val s = evtTable?.get("OnGhostChanging")
        if (s != null) {
            val ref0 = reqTable?.get("reference0") ?: ""
            return RES_HEADER + String.format(s, ref0) + RES_END
        }
        return RES_NO_CONTENT
    }

    private fun handleGhostChanged(): String {
        val s = evtTable?.get("OnGhostChanged")
        if (s != null) {
            val ref0 = reqTable?.get("reference0") ?: ""
            return RES_HEADER + String.format(s, ref0) + RES_END
        }
        return RES_NO_CONTENT
    }

    private fun handleOnClose(): String {
        val s = evtTable?.get("OnClose")
        if (s != null) {
            return RES_HEADER + s + RES_END
        }
        return RES_HEADER + "OnClose" + RES_END
    }

    private fun handleInstallFail(): String {
        val s = evtTable?.get("OnInstallFailure")
        if (s != null) {
            return RES_HEADER + s + RES_END
        }
        return RES_NO_CONTENT
    }

    private fun handleOnBoot(): String {
        val s = evtTable?.get("OnBoot")
        if (s != null) {
            return RES_HEADER + s + RES_END
        }
        return RES_NO_CONTENT
    }
}
