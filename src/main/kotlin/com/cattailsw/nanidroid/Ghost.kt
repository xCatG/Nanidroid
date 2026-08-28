package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.shiori.Shiori
import com.cattailsw.nanidroid.shiori.ShioriLoadResult
import com.cattailsw.nanidroid.shiori.ShioriUnloadResult
import com.cattailsw.nanidroid.runtime.dialogue.GhostEventCapabilityDiscovery
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities
import com.cattailsw.nanidroid.runtime.dialogue.ShioriMethod
import com.cattailsw.nanidroid.util.PrefUtil
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

/** Kotlin domain owner for one installed ghost and its SHIORI session. */
open class Ghost @JvmOverloads constructor(ghostPath: String, ctx: Context? = null) {
    // This was package-visible to the Activity in the Java runtime.  Kotlin's
    // protected visibility is narrower, so retain that established package API.
    @JvmField var mgr: SurfaceManager? = null
    @JvmField protected var shiori: Shiori? = null
    @JvmField protected var rootPath: String = ghostPath
    @JvmField protected var ghostDirName: String = File(ghostPath).name
    @JvmField protected var ghostDesc: Map<String, String>? = null
    @JvmField protected var shellDesc: Map<String, String>? = null
    @JvmField protected var error: Boolean = false
    @JvmField protected var mCtx: Context? = ctx
    private var eventCapabilities = PointerEventCapabilities()

    init {
        LegacyPlatform.debug(TAG, "gdname=$ghostDirName")
        mgr = SurfaceManager(ghostDirName)
        loadGhostInfo()
    }

    fun ghostError(): Boolean = error

    protected open fun incrementCreateCount() {
        val count = getCreateCount()
        persistActivationCount(count + 1)
    }

    protected open fun persistActivationCount(count: Long) {
        PrefUtil.setKeyAsync(mCtx, KEY_CREATE_COUNT_PREFIX + ghostDirName, count)
    }

    internal fun recordActivation() = incrementCreateCount()

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
            LegacyPlatform.debug(TAG, "desc parsing error")
            error.printStackTrace()
            this.error = true
            return
        }
        try {
            shellDesc = shellReader.parse()
        } catch (error: Exception) {
            LegacyPlatform.debug(TAG, "shell desc parse error, but we will continue")
            error.printStackTrace()
        }

        val surfaceReader = SurfaceReader(
            mgr!!,
            masterShell,
            masterShell + "surfaces.txt",
            SurfaceTransparencyPolicy.fromShellDescriptor(shellDesc),
        )
        if (!error) error = surfaceReader.error
        val selectedShiori = ShioriFactory.getInstance().getShiori(masterGhost, ghostDesc, mCtx)
        when (val result = selectedShiori.load()) {
            ShioriLoadResult.Loaded -> shiori = selectedShiori
            is ShioriLoadResult.Failed -> throw result.cause
        }
        refreshPointerEventCapabilities()
    }

    open fun unload() {
        try {
            when (val result = shiori!!.unloadShiori()) {
                ShioriUnloadResult.Unloaded -> Unit
                is ShioriUnloadResult.Failed -> throw result.cause
            }
        } finally {
            eventCapabilities = PointerEventCapabilities()
        }
    }

    open fun getGhostId(): String = ghostDirName
    fun getGhostDirName(): String = ghostDirName
    fun getGhostPath(): String = rootPath
    open fun getGhostName(): String? = ghostDesc!!["name"]
    fun getShellName(): String? = shellDesc?.get("name") ?: if (shellDesc == null) "master" else null
    fun getCrafterName(): String? = ghostDesc!!["craftmanw"] ?: ghostDesc!!["craftman"]
    open fun getSakuraName(): String? = ghostDesc!!["sakura.name"]
    open fun getKeroName(): String? = ghostDesc!!["kero.name"]
    open fun getUsername(): String = "User"

    fun sendOnSecondChange(hour: Int): ShioriResponse =
        doShioriEvent("OnSecondChange", arrayOf("$hour", "0", "0", "1"))

    fun sendOnMinuteChange(hour: Int): ShioriResponse =
        doShioriEvent("OnMinuteChange", arrayOf("$hour", "0", "0", "1"))

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
        return sendRequest(request)
    }

    open fun requestRaw(
        method: ShioriMethod,
        eventId: String,
        references: List<String> = emptyList(),
    ): ShioriResponse {
        if (shiori == null) return ShioriResponse("SHIORI/2.0 500 Internal Server Error")
        val request = StringBuilder()
        request.append(method.name).append(" SHIORI/3.0\r\nSender: ").append(Setup.NANIDROID).append("\r\n")
        request.append("SecurityLevel: local\r\n")
        request.append("ID: ").append(eventId).append("\r\n")
        references.forEachIndexed { index, value ->
            request.append("Reference").append(index).append(": ").append(value).append("\r\n")
        }
        request.append("\r\n")
        return sendRequest(request)
    }

    private fun sendRequest(request: StringBuilder): ShioriResponse {
        val requestText = request.toString()
        val responseText = shiori!!.request(requestText)
        val reader = BufferedReader(StringReader(responseText))
        val response = ShioriResponse(reader)
        try {
            reader.close()
        } catch (_: Exception) {
            // The parsed response remains authoritative.
        }
        return response
    }

    internal fun setShioriForTesting(value: Shiori) {
        shiori = value
    }

    open fun pointerEventCapabilities(): PointerEventCapabilities = eventCapabilities

    private fun refreshPointerEventCapabilities() {
        eventCapabilities = PointerEventCapabilities()
        eventCapabilities = runCatching {
            GhostEventCapabilityDiscovery.discover(::requestRaw)
        }.getOrDefault(PointerEventCapabilities())
    }

    private companion object {
        const val TAG = "Ghost"
        const val KEY_CREATE_COUNT_PREFIX = "createcount_ghost"
    }
}
