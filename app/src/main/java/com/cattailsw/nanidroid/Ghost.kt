package com.cattailsw.nanidroid

import android.content.Context
import android.util.Log
import com.cattailsw.nanidroid.shiori.Shiori
import com.cattailsw.nanidroid.util.PrefUtil
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.util.HashMap

open class Ghost {
    companion object {
        private const val TAG = "Ghost"
        private const val KEY_CREATE_COUNT_PREFIX = "createcount_ghost"
    }

    var mgr: SurfaceManager? = null
    var shiori: Shiori? = null

    var rootPath: String
    var ghostDirName: String
    var ghostDesc: MutableMap<String, String> = HashMap()
    var shellDesc: MutableMap<String, String> = HashMap()
    var error = false
    var mCtx: Context?

    constructor(ghostPath: String, ctx: Context?) {
        rootPath = ghostPath
        ghostDirName = File(ghostPath).name
        Log.d(TAG, "gdname=$ghostDirName")
        mgr = SurfaceManager(ghostDirName)
        mCtx = ctx
        loadGhostInfo()
        incrementCreateCount()
    }

    constructor(ghostPath: String) : this(ghostPath, null)

    fun ghostError(): Boolean {
        return error
    }

    protected open fun incrementCreateCount() {
        val cCount = getCreateCount()
        val context = mCtx
        if (context != null) {
            PrefUtil.setKey(context, KEY_CREATE_COUNT_PREFIX + ghostDirName, cCount + 1)
        }
    }

    open fun getCreateCount(): Long {
        val context = mCtx ?: return 0L
        return PrefUtil.getKeyValueLong(context, KEY_CREATE_COUNT_PREFIX + ghostDirName)
    }

    protected open fun loadGhostInfo() {
        val masterGhost = "$rootPath/ghost/master/"
        val masterGhostDesc = "${masterGhost}descript.txt"
        val ghostDr = DescReader(masterGhostDesc)

        val masterShell = "$rootPath/shell/master/"
        val masterShellDesc = "${masterShell}descript.txt"
        val shellDr = DescReader(masterShellDesc)

        val masterShellSurface = "${masterShell}surfaces.txt"

        try {
            ghostDesc = ghostDr.parse()
        } catch (e: Exception) {
            Log.d(TAG, "desc parsing error")
            e.printStackTrace()
            error = true
            return
        }

        try {
            shellDesc = shellDr.parse()
        } catch (e: Exception) {
            Log.d(TAG, "shell desc parse error, but we will continue")
            e.printStackTrace()
        }

        val sr = SurfaceReader(mgr!!, masterShell, masterShellSurface)
        if (!error) {
            error = sr.error
        }

        shiori = ShioriFactory.getInstance().getShiori(masterGhost, ghostDesc, mCtx)
    }

    open fun unload() {
        shiori?.unloadShiori()
    }

    fun getGhostId(): String {
        return ghostDirName
    }

    fun getGhostPath(): String {
        return rootPath
    }

    fun getGhostName(): String? {
        return ghostDesc["name"]
    }

    fun getShellName(): String {
        return shellDesc["name"] ?: "master"
    }

    fun getCrafterName(): String? {
        return ghostDesc["craftmanw"] ?: ghostDesc["craftman"]
    }

    fun getSakuraName(): String? {
        return ghostDesc["sakura.name"]
    }

    fun getKeroName(): String? {
        return ghostDesc["kero.name"]
    }

    fun getUsername(): String {
        return "User"
    }

    fun sendOnSecondChange(hour: Int): ShioriResponse {
        return doShioriEvent("OnSecondChange", arrayOf(hour.toString(), "0", "0", "1"))
    }

    fun sendOnMinuteChange(hour: Int): ShioriResponse {
        return doShioriEvent("OnMinuteChange", arrayOf(hour.toString(), "0", "0", "1"))
    }

    fun getStringFromShiori(id: String): String? {
        val s = shiori ?: return null
        val r = doShioriEvent(id, null)
        if (r.statusCode != 200) {
            return null
        }
        return r.getKey("Value")
    }

    fun doShioriEvent(event: String, ref: Array<String>?): ShioriResponse {
        val s = shiori ?: return ShioriResponse("SHIORI/2.0 500 Internal Server Error")

        val sb = StringBuilder()
        sb.append("GET SHIORI/3.0\r\nSender: ").append(Setup.NANIDROID).append("\r\n")
        sb.append("ID: ").append(event).append("\r\n")
        sb.append("SecurityLevel: local\r\n")
        if (ref != null) {
            for (i in ref.indices) {
                sb.append("Reference").append(i).append(": ").append(ref[i]).append("\r\n")
            }
        }
        sb.append("\r\n")

        val reqResult = s.request(sb.toString())
        val br = BufferedReader(StringReader(reqResult))
        val res = ShioriResponse(br)
        try {
            br.close()
        } catch (e: Exception) {
            // ignore
        }
        return res
    }
}
