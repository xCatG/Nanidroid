package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.install.NarTransactionalInstaller
import com.cattailsw.nanidroid.util.PrefUtil
import java.io.File

/** Kotlin owner for ghost discovery, selection, and fresh installation. */
class GhostMgr(ctx: Context) {
    private val context = ctx.applicationContext
    private var ghosts: List<InfoOnlyGhost>? = DirList.parseDataDir(context)
    private var lastInstallError: String? = null

    fun getGhostId(name: String): Int {
        ghosts?.forEachIndexed { index, ghost ->
            if (ghost.ghostDirName.equals(name, ignoreCase = true)) return index
        }
        return -1
    }

    fun hasSameGhostId(id: String): Boolean =
        !ghosts.isNullOrEmpty() && getGhostId(id) != -1

    fun getGhostPath(id: Int): String = ghosts!![id].ghostPath

    fun createGhost(name: String): Ghost? {
        val id = getGhostId(name)
        return if (id == -1) null else Ghost(getGhostPath(id), context)
    }

    fun getLastRunGhostId(): String? =
        if (PrefUtil.hasKey(context, PREF_LAST_RUN_GHOST)) {
            PrefUtil.getKeyValue(context, PREF_LAST_RUN_GHOST)
        } else {
            null
        }

    fun setLastRunGhost(ghost: Ghost) {
        PrefUtil.setKey(context, PREF_LAST_RUN_GHOST, ghost.ghostDirName)
    }

    fun installFirstGhost(gid: String, narPath: String): String? =
        installGhost(gid, narPath, true)

    fun installGhost(gid: String, narPath: String): String? =
        installGhost(gid, narPath, false)

    fun installGhost(ghostId: String, narPath: String, usegid: Boolean): String? {
        val externalFiles = context.getExternalFilesDir(null)
        if (externalFiles == null) {
            lastInstallError =
                "Nanidroid cannot access the selected ghost archive or storage."
            return null
        }
        val dataDir = File(externalFiles, "ghost")
        if ((!dataDir.exists() && !dataDir.mkdirs()) || !dataDir.isDirectory) {
            lastInstallError = "Nanidroid cannot prepare its ghost storage."
            return null
        }
        val installed = NarTransactionalInstaller.install(
            File(narPath), dataDir, if (usegid) ghostId else null
        )
        if (!installed.isSuccess) {
            lastInstallError = installed.message
            return null
        }
        refreshGhost()
        val id = getGhostId(installed.targetId)
        if (id == -1) {
            lastInstallError =
                "The installed archive does not contain a usable ghost."
            return null
        }
        lastInstallError = null
        return getGhostPath(id)
    }

    fun getLastInstallError(): String? = lastInstallError

    fun refreshGhost() {
        ghosts = DirList.parseDataDir(context)
    }

    fun getGnames(): Array<String>? =
        ghosts?.takeIf { it.isNotEmpty() }?.map { it.ghostDirName }?.toTypedArray()

    fun getGhostCount(): Int = ghosts?.size ?: 0

    fun getGhostReadMe(ghostId: String): File =
        File(getGhostPath(getGhostId(ghostId)), "readme.txt")

    fun getGhostSakuraName(id: String): String? =
        getGhostId(id).takeIf { it != -1 }?.let { ghosts!![it].sakuraName }

    fun getGhostDispName(id: String): String? =
        getGhostId(id).takeIf { it != -1 }?.let { ghosts!![it].ghostName }

    fun getGDispNames(): Array<String>? =
        ghosts?.takeIf { it.isNotEmpty() }?.map { it.ghostName }?.toTypedArray()

    fun getGhostPath(id: String): String = getGhostPath(getGhostId(id))

    fun getGhostLaunchCount(order: Int): Int = 0

    private companion object {
        const val PREF_LAST_RUN_GHOST = "lastrunghost"
    }
}
