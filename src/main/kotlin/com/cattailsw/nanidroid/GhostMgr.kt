package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.install.ArchiveInstallFailure
import com.cattailsw.nanidroid.install.ArchiveInstallResult
import com.cattailsw.nanidroid.install.NarTransactionalInstaller
import com.cattailsw.nanidroid.util.PrefUtil
import java.io.File

internal fun shouldInstallBundledGhost(
    usableGhostCount: Int,
    storageEntries: Array<out File>,
): Boolean = usableGhostCount == 0 && storageEntries.all { entry ->
    entry.isDirectory && entry.name == ".nanidroid-install-staging"
}

/** Kotlin owner for ghost discovery, selection, and fresh installation. */
internal class GhostMgr(
    ctx: Context,
    private val ghostRuntime: GhostRuntime,
) {
    private val context = ctx.applicationContext
    private var ghosts: List<InfoOnlyGhost>? = loadGhosts()

    fun getGhostId(name: String): Int {
        ghosts?.forEachIndexed { index, ghost ->
            if (ghost.getGhostDirName().equals(name, ignoreCase = true)) return index
        }
        return -1
    }

    fun getGhostPath(id: Int): String = ghosts!![id].getGhostPath()

    internal fun createGhost(name: String): ReservedGhost? {
        val id = getGhostId(name)
        if (id == -1) return null
        val root = File(getGhostPath(id)).canonicalFile
        return ghostRuntime.reuseActiveGhost(root.name, root) ?: run {
            val construction = ghostRuntime.beginGhostConstruction(root.name, root)
            try {
                construction.bind(Ghost(root.path, context))
            } catch (error: Exception) {
                construction.failConstruction()
                throw error
            } catch (error: LinkageError) {
                construction.failConstruction()
                throw error
            }
        }
    }

    fun getLastRunGhostId(): String? =
        if (PrefUtil.hasKey(context, PREF_LAST_RUN_GHOST)) {
            PrefUtil.getKeyValue(context, PREF_LAST_RUN_GHOST)
        } else {
            null
        }

    fun setLastRunGhost(ghost: Ghost) {
        PrefUtil.setKey(context, PREF_LAST_RUN_GHOST, ghost.getGhostDirName())
    }

    fun installFirstGhost(
        gid: String,
        narPath: String,
        isCancelled: () -> Boolean,
    ): ArchiveInstallResult = installGhost(gid, narPath, true, isCancelled)

    fun installGhost(
        ghostId: String,
        narPath: String,
        usegid: Boolean,
        isCancelled: () -> Boolean,
    ): ArchiveInstallResult {
        val externalFiles = context.getExternalFilesDir(null)
        if (externalFiles == null) {
            return ArchiveInstallResult.Failed(
                "Nanidroid cannot access the selected ghost archive or storage.",
                ArchiveInstallFailure.StorageUnavailable,
            )
        }
        val dataDir = File(externalFiles, "ghost")
        if ((!dataDir.exists() && !dataDir.mkdirs()) || !dataDir.isDirectory) {
            return ArchiveInstallResult.Failed(
                "Nanidroid cannot prepare its ghost storage.",
                ArchiveInstallFailure.StorageUnavailable,
            )
        }
        val installed = NarTransactionalInstaller.install(
            File(narPath), dataDir, if (usegid) ghostId else null, isCancelled
        )
        if (installed !is ArchiveInstallResult.Installed) {
            return installed
        }
        refreshGhost()
        val id = getGhostId(installed.targetId!!)
        if (id == -1) {
            return ArchiveInstallResult.Failed(
                "The installed archive does not contain a usable ghost.",
                ArchiveInstallFailure.InvalidArchive,
            )
        }
        return ArchiveInstallResult.Installed(getGhostPath(id), installed.targetId)
    }

    fun refreshGhost() {
        ghosts = loadGhosts()
    }

    private fun loadGhosts(): List<InfoOnlyGhost>? = DirList.parseDataDir(context)

    fun getGnames(): Array<String>? =
        ghosts?.takeIf { it.isNotEmpty() }?.map { it.getGhostDirName() }?.toTypedArray()

    fun getGhostCount(): Int = ghosts?.size ?: 0

    fun shouldInstallFirstGhost(): Boolean {
        val externalFiles = context.getExternalFilesDir(null) ?: return false
        val storageRoot = File(externalFiles, "ghost")
        return shouldInstallBundledGhost(getGhostCount(), storageRoot.listFiles().orEmpty())
    }

    fun getGhostReadMe(ghostId: String): File =
        File(getGhostPath(getGhostId(ghostId)), "readme.txt")

    fun getGhostSakuraName(id: String): String? =
        getGhostId(id).takeIf { it != -1 }?.let { ghosts!![it].getSakuraName() }

    fun getGhostDispName(id: String): String? =
        getGhostId(id).takeIf { it != -1 }?.let { ghosts!![it].getGhostName() }

    fun getGDispNames(): Array<String?>? =
        ghosts?.takeIf { it.isNotEmpty() }?.map { it.getGhostName() }?.toTypedArray()

    fun getGhostPath(id: String): String = getGhostPath(getGhostId(id))

    private companion object {
        const val PREF_LAST_RUN_GHOST = "lastrunghost"
    }
}
