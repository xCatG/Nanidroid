package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.util.NarUtil
import com.cattailsw.nanidroid.util.PrefUtil
import java.io.File

class GhostMgr(ctx: Context) {
    companion object {
        private const val TAG = "GhostMgr"
        private const val PREF_LAST_RUN_GHOST = "lastrunghost"
    }

    private val mCtx: Context = ctx.applicationContext
    private var iglist: List<InfoOnlyGhost>? = null

    init {
        iglist = DirList.parseDataDir(mCtx)
    }

    fun getGhostId(name: String): Int {
        val list = iglist ?: return -1
        for (i in list.indices) {
            if (list[i].getGhostId().equals(name, ignoreCase = true)) {
                return i
            }
        }
        return -1
    }

    fun hasSameGhostId(id: String): Boolean {
        val list = iglist
        if (list == null || list.isEmpty()) return false
        return getGhostId(id) != -1
    }

    fun getGhostPath(id: Int): String {
        return iglist!![id].getGhostPath()
    }

    fun createGhost(name: String): Ghost? {
        val id = getGhostId(name)
        if (id == -1) return null
        return Ghost(getGhostPath(id), mCtx)
    }

    fun getLastRunGhostId(): String? {
        return if (PrefUtil.hasKey(mCtx, PREF_LAST_RUN_GHOST)) {
            PrefUtil.getKeyValue(mCtx, PREF_LAST_RUN_GHOST)
        } else {
            null
        }
    }

    fun setLastRunGhost(g: Ghost) {
        val gid = g.getGhostId()
        PrefUtil.setKey(mCtx, PREF_LAST_RUN_GHOST, gid)
    }

    fun installFirstGhost(gid: String, narPath: String): String? {
        return installGhost(gid, narPath, true)
    }

    fun installGhost(gid: String, narPath: String): String? {
        return installGhost(gid, narPath, false)
    }

    fun installGhost(ghostId: String, narPath: String, usegid: Boolean): String? {
        val dataDir = File(mCtx.getExternalFilesDir(null), "ghost")
        val success = NarUtil.readNarArchive(
            narPath,
            dataDir.absolutePath,
            if (usegid) ghostId else null
        )
        if (!success) return null

        refreshGhost()
        val gid = getGhostId(ghostId)
        if (gid == -1) return null
        return getGhostPath(gid)
    }

    fun refreshGhost() {
        iglist = DirList.parseDataDir(mCtx)
    }

    fun getGnames(): Array<String>? {
        val list = iglist
        if (list == null || list.isEmpty()) return null
        return Array(list.size) { list[it].getGhostId() }
    }

    fun getGhostCount(): Int {
        val list = iglist
        return list?.size ?: 0
    }

    fun getGhostReadMe(ghostId: String): File {
        val gPath = getGhostPath(getGhostId(ghostId))
        return File(gPath, "readme.txt")
    }

    fun getGhostSakuraName(id: String): String? {
        val gid = getGhostId(id)
        if (gid == -1) return null
        return iglist!![gid].getSakuraName()
    }

    fun getGhostDispName(id: String): String? {
        val gid = getGhostId(id)
        if (gid == -1) return null
        return iglist!![gid].getGhostName()
    }

    fun getGDispNames(): Array<String>? {
        val list = iglist
        if (list == null || list.isEmpty()) return null
        return Array(list.size) { list[it].getGhostName() ?: "" }
    }

    fun getGhostPath(id: String): String {
        val gid = getGhostId(id)
        return getGhostPath(gid)
    }

    fun getGhostLaunchCount(order: Int): Int {
        // TODO return actual launch count
        return 0
    }
}
