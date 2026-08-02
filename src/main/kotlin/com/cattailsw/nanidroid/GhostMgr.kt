package com.cattailsw.nanidroid

import android.content.Context
import android.util.Log
import com.cattailsw.nanidroid.durable.GhostUpdateRepository
import com.cattailsw.nanidroid.durable.RecoveryResult
import com.cattailsw.nanidroid.durable.GhostUpdateWorker
import com.cattailsw.nanidroid.install.ArchiveInstallFailure
import com.cattailsw.nanidroid.install.ArchiveInstallResult
import com.cattailsw.nanidroid.install.NarTransactionalInstaller
import com.cattailsw.nanidroid.util.PrefUtil
import java.io.File

internal fun shouldInstallBundledGhost(
    usableGhostCount: Int,
    storageEntries: Array<out File>,
): Boolean = usableGhostCount == 0 && storageEntries.all { entry ->
    when {
        entry.isDirectory -> entry.name == ".nanidroid-install-staging"
        entry.isFile -> entry.name.startsWith(".nanidroid-update-lock-")
        else -> false
    }
}

/** Kotlin owner for ghost discovery, selection, and fresh installation. */
class GhostMgr(ctx: Context) {
    private val context = ctx.applicationContext
    private var ghosts: List<InfoOnlyGhost>? = loadGhostsAfterRecovery()
    private var lastInstallError: String? = null

    fun getGhostId(name: String): Int {
        ghosts?.forEachIndexed { index, ghost ->
            if (ghost.getGhostDirName().equals(name, ignoreCase = true)) return index
        }
        return -1
    }

    fun hasSameGhostId(id: String): Boolean =
        !ghosts.isNullOrEmpty() && getGhostId(id) != -1

    fun getGhostPath(id: Int): String = ghosts!![id].getGhostPath()

    fun createGhost(name: String): Ghost? {
        val id = getGhostId(name)
        if (id == -1) return null
        val (recovery, ghost) = GhostUpdateRepository.withRecoveredGhostRoot(File(getGhostPath(id))) {
            Ghost(getGhostPath(id), context)
        }
        if (recovery is RecoveryResult.Failed) {
            Log.e(TAG, "Ghost update recovery failed before construction: ${recovery.diagnostic}")
        }
        return ghost
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

    fun installFirstGhost(gid: String, narPath: String): String? =
        installGhost(gid, narPath, true)

    fun installGhost(gid: String, narPath: String): String? =
        installGhost(gid, narPath, false)

    fun installGhost(ghostId: String, narPath: String, usegid: Boolean): String? {
        return when (val result = installGhost(ghostId, narPath, usegid, { false })) {
            is ArchiveInstallResult.Installed -> result.installedPath
            is ArchiveInstallResult.Failed -> {
                lastInstallError = result.message
                null
            }
            ArchiveInstallResult.Cancelled -> {
                lastInstallError = "The selected ghost archive install was cancelled."
                null
            }
        }
    }

    fun installFirstGhost(
        gid: String,
        narPath: String,
        isCancelled: () -> Boolean,
    ): ArchiveInstallResult = installGhost(gid, narPath, true, isCancelled)

    fun installGhost(
        ghostId: String,
        narPath: String,
        isCancelled: () -> Boolean,
    ): ArchiveInstallResult = installGhost(ghostId, narPath, false, isCancelled)

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
        lastInstallError = null
        return ArchiveInstallResult.Installed(getGhostPath(id), installed.targetId)
    }

    fun getLastInstallError(): String? = lastInstallError

    fun refreshGhost() {
        ghosts = loadGhostsAfterRecovery()
    }

    private fun loadGhostsAfterRecovery(): List<InfoOnlyGhost>? {
        val externalFiles = context.getExternalFilesDir(null) ?: return null
        val storageRoot = File(externalFiles, "ghost")
        when (val recovery = GhostUpdateWorker.recoverBeforeGhostLoad(context, storageRoot)) {
            is RecoveryResult.Failed -> {
                Log.e(TAG, "Ghost update recovery failed before discovery: ${recovery.diagnostic}")
            }
            is RecoveryResult.CommitPending,
            is RecoveryResult.PublishPending,
            is RecoveryResult.RollbackPending,
            -> {
                Log.w(TAG, "Ghost update awaits asynchronous durable reconciliation")
            }
            else -> Unit
        }
        val recoveryTargets = GhostUpdateRepository.recoveryTargets(storageRoot) +
            GhostUpdateWorker.pendingAttemptRecoveryTargets(context, storageRoot)
        recoveryTargets.forEach { target ->
            try {
                GhostUpdateWorker.enqueueRecovery(context, storageRoot, target)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Could not schedule ghost update recovery for $target", e)
            }
        }
        val blocked = GhostUpdateRepository.blockedGhostRoots(storageRoot)
        return DirList.parseDataDir(context)?.filterNot { ghost ->
            File(ghost.getGhostPath()).canonicalFile in blocked
        }
    }

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

    fun getGhostLaunchCount(order: Int): Int = 0

    private companion object {
        const val TAG = "GhostMgr"
        const val PREF_LAST_RUN_GHOST = "lastrunghost"
    }
}
