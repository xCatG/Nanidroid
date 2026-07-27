package com.cattailsw.nanidroid

import android.content.Context
import android.util.Log
import com.cattailsw.nanidroid.shiori.Shiori
import com.cattailsw.nanidroid.util.PrefUtil
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

/** Kotlin domain owner for one installed ghost and its SHIORI session. */
open class Ghost @JvmOverloads constructor(ghostPath: String, ctx: Context? = null) {
    @JvmField protected var mgr: SurfaceManager? = null
    @JvmField protected var shiori: Shiori? = null
    @JvmField protected var rootPath: String = ghostPath
    @JvmField protected var ghostDirName: String = File(ghostPath).name
    @JvmField protected var ghostDesc: Map<String, String>? = null
    @JvmField protected var shellDesc: Map<String, String>? = null
    @JvmField protected var error: Boolean = false
    @JvmField protected var mCtx: Context? = ctx

    init {
        Log.d(TAG, "gdname=$ghostDirName")
        mgr = SurfaceManager(ghostDirName)
        loadGhostInfo()
        incrementCreateCount()
    }

    fun ghostError(): Boolean = error

    protected open fun incrementCreateCount() {
        val count = getCreateCount()
        PrefUtil.setKey(mCtx, KEY_CREATE_COUNT_PREFIX + ghostDirName, count + 1)
    }

    open fun getCreateCount(): Long =
        PrefUtil.getKeyValueLong(mCtx, KEY_CREATE_COUNT_PREFIX + ghostDirName)

    protected open fun loadGhostInfo() {
        val masterGhost = "$rootPath/ghost/master/"
        val ghostReader = DescReader(masterGhost + "descript.txt")
        val masterShell = "$rootPath/shell/master/"
        val shellReader = DescReader(masterShell + "descript.txt")

        try {
            ghostDesc = ghostReader.parse()
        } catch (error: Exception) {
            Log.d(TAG, "desc parsing error")
            error.printStackTrace()
            this.error = true
            return
        }
        try {
            shellDesc = shellReader.parse()
        } catch (error: Exception) {
            Log.d(TAG, "shell desc parse error, but we will continue")
            error.printStackTrace()
        }

        val surfaceReader = SurfaceReader(mgr, masterShell, masterShell + "surfaces.txt")
        if (!error) error = surfaceReader.error
        shiori = ShioriFactory.getInstance().getShiori(masterGhost, ghostDesc, mCtx)
    }

    open fun unload() {
        shiori!!.unloadShiori()
    }

    open fun getGhostId(): String = ghostDirName
    fun getGhostDirName(): String = ghostDirName
    fun getGhostPath(): String = rootPath
    open fun getGhostName(): String? = ghostDesc!!["name"]
    fun getShellName(): String = shellDesc?.get("name") ?: "master"
    fun getCrafterName(): String? = ghostDesc!!["craftmanw"] ?: ghostDesc!!["craftman"]
    open fun getSakuraName(): String? = ghostDesc!!["sakura.name"]
    open fun getKeroName(): String? = ghostDesc!!["kero.name"]
    open fun getUsername(): String = "User"

    fun sendOnSecondChange(hour: Int): ShioriResponse =
        doShioriEvent("OnSecondChange", arrayOf("$hour", "0", "0", "1"))

    fun sendOnMinuteChange(hour: Int): ShioriResponse =
        doShioriEvent("OnMinuteChange", arrayOf("$hour", "0", "0", "1"))

    fun getStringFromShiori(id: String): String? {
        if (shiori == null) return null
        val response = doShioriEvent(id, null)
        return if (response.statusCode == 200) response.getKey("Value") else null
    }

    open fun doShioriEvent(event: String, ref: Array<String>?): ShioriResponse {
        if (shiori == null) return ShioriResponse("SHIORI/2.0 500 Internal Server Error")
        val request = StringBuilder()
        request.append("GET SHIORI/3.0\r\nSender: ").append(Setup.NANIDROID).append("\r\n")
        request.append("ID: ").append(event).append("\r\n")
        request.append("SecurityLevel: local\r\n")
        ref?.forEachIndexed { index, value ->
            request.append("Reference").append(index).append(": ").append(value).append("\r\n")
        }
        request.append("\r\n")
        val reader = BufferedReader(StringReader(shiori!!.request(request.toString())))
        val response = ShioriResponse(reader)
        try {
            reader.close()
        } catch (_: Exception) {
            // The parsed response remains authoritative.
        }
        return response
    }

    private companion object {
        const val TAG = "Ghost"
        const val KEY_CREATE_COUNT_PREFIX = "createcount_ghost"
    }
}
