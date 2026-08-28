package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.install.ArchiveInstallFailure
import com.cattailsw.nanidroid.install.ArchiveInstallResult
import com.cattailsw.nanidroid.install.NarTransactionalInstaller
import java.io.File

internal fun shouldInstallBundledGhost(
    usableGhostCount: Int,
    storageEntries: Array<out File>,
): Boolean = usableGhostCount == 0 && storageEntries.all { entry ->
    entry.isDirectory && entry.name == ".nanidroid-install-staging"
}

/** Kotlin owner for ghost discovery, selection, and fresh installation. */
internal class GhostMgr(ctx: Context) {
    private val context = ctx.applicationContext
    private var ghosts: List<InstalledGhostMetadata> = loadGhosts()

    fun getGhostId(name: String): Int {
        ghosts.forEachIndexed { index, ghost ->
            if (ghost.id.equals(name, ignoreCase = true)) return index
        }
        return -1
    }

    fun getGhostPath(id: Int): String = ghosts[id].canonicalRoot.path

    internal fun findGhost(id: String): InstalledGhostMetadata? =
        ghosts.firstOrNull { it.id.equals(id, ignoreCase = true) }

    internal fun launchCandidates(preferredId: String?): List<InstalledGhostMetadata> {
        val preferred = preferredId?.let(::findGhost)
        return buildList {
            if (preferred != null) add(preferred)
            ghosts.forEach { ghost -> if (ghost !== preferred) add(ghost) }
        }
    }

    fun installFirstGhost(gid: String, narPath: String): String? =
        (installGhost(gid, narPath, true, { false }) as? ArchiveInstallResult.Installed)
            ?.installedPath

    fun installFirstGhost(
        gid: String,
        narPath: String,
        isCancelled: () -> Boolean,
    ): ArchiveInstallResult = installGhost(gid, narPath, true, isCancelled)

    private fun installGhost(
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

    private fun loadGhosts(): List<InstalledGhostMetadata> = InstalledGhostCatalog.scan(context)

    fun getGnames(): Array<String>? =
        ghosts.takeIf { it.isNotEmpty() }?.map { it.id }?.toTypedArray()

    fun getGhostCount(): Int = ghosts.size

    fun shouldInstallFirstGhost(): Boolean {
        val externalFiles = context.getExternalFilesDir(null) ?: return false
        val storageRoot = File(externalFiles, "ghost")
        return shouldInstallBundledGhost(getGhostCount(), storageRoot.listFiles().orEmpty())
    }

    fun getGhostReadMe(ghostId: String): File =
        ghosts[getGhostId(ghostId)].readme

    fun getGhostSakuraName(id: String): String? =
        getGhostId(id).takeIf { it != -1 }?.let { ghosts[it].sakuraName }

    fun getGhostDispName(id: String): String? =
        getGhostId(id).takeIf { it != -1 }?.let { ghosts[it].name }

    fun getGDispNames(): Array<String?>? =
        ghosts.takeIf { it.isNotEmpty() }?.map { it.name }?.toTypedArray()

    fun getGhostPath(id: String): String = getGhostPath(getGhostId(id))

}
