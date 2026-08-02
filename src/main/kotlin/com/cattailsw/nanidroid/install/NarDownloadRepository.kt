package com.cattailsw.nanidroid.install

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.cattailsw.nanidroid.GhostMgr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

internal data class NarRemoteEnqueue(
    val downloadManagerId: Long,
    val retainedUri: String,
)

internal sealed interface NarRemoteDownloadStatus {
    data object InProgress : NarRemoteDownloadStatus
    data class Successful(val localUri: String?) : NarRemoteDownloadStatus
    data object Failed : NarRemoteDownloadStatus
}

internal interface NarDownloadGateway {
    fun intendedRetainedUri(itemId: String): String
    fun enqueue(itemId: String, normalizedHttpsUrl: String): NarRemoteEnqueue
    fun findDownloadId(retainedUri: String): Long?
    fun remove(downloadManagerId: Long)
    fun status(downloadManagerId: Long): NarRemoteDownloadStatus?
}

internal interface NarInstallWorkScheduler {
    fun enqueue(itemId: String)
    fun cancel(itemId: String)
}

internal interface NarArchiveInstaller {
    fun install(
        download: NarDownload,
        stagingDirectory: File,
        isStopped: () -> Boolean,
    ): ArchiveInstallResult
}

internal interface NarOwnedDownloadData {
    fun delete(download: NarDownload)
    fun releasePersistedGrant(download: NarDownload) = Unit
}

internal interface NarInstallAttemptPaths {
    fun create(itemId: String): File
    fun delete(directory: File) = Unit
}

/** Durable source of truth for remote transfers and one-at-a-time archive installation work. */
class NarDownloadRepository internal constructor(
    private val store: NarDownloadStore,
    private val downloads: NarDownloadGateway,
    private val work: NarInstallWorkScheduler,
    private val installer: NarArchiveInstaller,
    private val ownedData: NarOwnedDownloadData,
    private val attemptPaths: NarInstallAttemptPaths,
    private val nextId: () -> String,
) {
    private val observedDownloads = MutableStateFlow(store.getAll())

    fun observeDownloads(): StateFlow<List<NarDownload>> = observedDownloads.asStateFlow()

    @Synchronized
    fun enqueueRemote(url: String): NarDownload {
        val item = store.create(
            NarDownload(
                id = nextId(),
                source = NarDownloadSource.Remote(url),
                state = NarDownloadState.Downloading,
            ),
        )
        try {
            store.update(item.id) { it.copy(retainedUri = downloads.intendedRetainedUri(item.id)) }
            val enqueued = downloads.enqueue(item.id, normalizeHttpsUrl(url))
            store.update(item.id) {
                it.copy(
                    retainedUri = enqueued.retainedUri,
                    downloadManagerId = enqueued.downloadManagerId,
                    state = NarDownloadState.Downloading,
                )
            }
        } catch (_: Exception) {
            markNeedsAttention(item.id, DOWNLOAD_START_FAILURE)
        }
        publish()
        return store.get(item.id)!!
    }

    @Synchronized
    fun enqueueLocal(uri: String, retainedUri: String? = null): NarDownload {
        val item = store.create(
            NarDownload(
                id = nextId(),
                source = NarDownloadSource.Local(uri),
                retainedUri = retainedUri,
                state = NarDownloadState.Queued,
            ),
        )
        scheduleInstall(item.id)
        publish()
        return store.get(item.id)!!
    }

    @Synchronized
    fun retainLocalSourceForCopy(uri: String): NarDownload {
        val item = store.create(
            NarDownload(
                id = nextId(),
                source = NarDownloadSource.Local(uri),
                retainedUri = uri,
                state = NarDownloadState.NeedsAttention(
                    NarDownloadState.Failure("Select the archive again to continue."),
                ),
            ),
        )
        publish()
        return item
    }

    @Synchronized
    fun retry(itemId: String): NarDownload? {
        val item = store.get(itemId) ?: return null
        runCatching { work.cancel(itemId) }
        when (val source = item.source) {
            is NarDownloadSource.Remote -> {
                item.downloadManagerId?.let { runCatching { downloads.remove(it) } }
                runCatching { ownedData.delete(item) }
                store.update(itemId) {
                    it.copy(
                        retainedUri = downloads.intendedRetainedUri(itemId),
                        downloadManagerId = null,
                        state = NarDownloadState.Downloading,
                    )
                }
                try {
                    val enqueued = downloads.enqueue(itemId, normalizeHttpsUrl(source.uri))
                    store.update(itemId) {
                        it.copy(
                            retainedUri = enqueued.retainedUri,
                            downloadManagerId = enqueued.downloadManagerId,
                            state = NarDownloadState.Downloading,
                        )
                    }
                } catch (_: Exception) {
                    markNeedsAttention(itemId, DOWNLOAD_START_FAILURE)
                }
            }
            is NarDownloadSource.Local -> {
                store.update(itemId) { it.copy(state = NarDownloadState.Queued) }
                scheduleInstall(itemId)
            }
        }
        publish()
        return store.get(itemId)
    }

    @Synchronized
    fun replaceLocalSource(itemId: String, uri: String): NarDownload? {
        val item = store.get(itemId) ?: return null
        if (item.source !is NarDownloadSource.Local) return null
        runCatching { work.cancel(itemId) }
        runCatching { ownedData.delete(item) }
        store.update(itemId) {
            it.copy(
                source = NarDownloadSource.Local(uri),
                retainedUri = uri,
                state = NarDownloadState.Queued,
            )
        }
        if (item.retainedUri != uri) releasePersistedGrantIfUnused(item)
        scheduleInstall(itemId)
        publish()
        return store.get(itemId)
    }

    @Synchronized
    fun delete(itemId: String): Boolean {
        val item = store.get(itemId) ?: return false
        runCatching { work.cancel(itemId) }
        item.downloadManagerId?.let { runCatching { downloads.remove(it) } }
        runCatching { ownedData.delete(item) }
        store.delete(itemId)
        releasePersistedGrantIfUnused(item)
        publish()
        return true
    }

    @Synchronized
    fun onDownloadComplete(downloadManagerId: Long) {
        val item = store.getAll().firstOrNull {
            it.downloadManagerId == downloadManagerId && it.state.isNonterminal()
        } ?: return
        scheduleInstall(item.id)
        publish()
    }

    @Synchronized
    fun reconcile() {
        store.getAll()
            .filter { it.state == NarDownloadState.Complete }
            .forEach(::cleanupCompletedInstall)
        store.getAll()
            .filter { it.source is NarDownloadSource.Remote && it.state.isNonterminal() }
            .forEach { item ->
                val downloadManagerId = item.downloadManagerId ?: item.retainedUri?.let { retainedUri ->
                    runCatching { downloads.findDownloadId(retainedUri) }.getOrNull()?.also { recoveredId ->
                        store.update(item.id) { it.copy(downloadManagerId = recoveredId) }
                    }
                }
                val status = downloadManagerId?.let { id ->
                    try {
                        downloads.status(id)
                    } catch (_: Exception) {
                        null
                    }
                }
                when (status) {
                    NarRemoteDownloadStatus.InProgress -> {
                        store.update(item.id) { it.copy(state = NarDownloadState.Downloading) }
                    }
                    is NarRemoteDownloadStatus.Successful -> {
                        store.update(item.id) {
                            it.copy(retainedUri = status.localUri ?: it.retainedUri)
                        }
                        scheduleInstall(item.id)
                    }
                    NarRemoteDownloadStatus.Failed,
                    null -> markNeedsAttention(item.id, DOWNLOAD_RECOVERY_FAILURE)
                }
            }
        store.getAll()
            .filter { it.source is NarDownloadSource.Local && it.state.isNonterminal() }
            .forEach { item ->
                scheduleInstall(item.id)
            }
        publish()
    }

    fun install(itemId: String, isStopped: () -> Boolean) {
        val item = store.get(itemId) ?: return
        val recoveringPublishedInstall = item.state is NarDownloadState.Installing
        val installing = store.update(itemId) {
            it.copy(state = NarDownloadState.Installing)
        } ?: return
        publish()
        var stagingDirectory: File? = null
        val result = try {
            stagingDirectory = attemptPaths.create(itemId)
            installer.install(installing, stagingDirectory, isStopped)
        } catch (_: SecurityException) {
            sourceUnavailable(item)
        } catch (_: FileNotFoundException) {
            sourceUnavailable(item)
        } catch (_: Exception) {
            ArchiveInstallResult.Failed(INSTALL_FAILURE, ArchiveInstallFailure.StagingFailed)
        } finally {
            stagingDirectory?.let { runCatching { attemptPaths.delete(it) } }
        }
        when (result) {
            is ArchiveInstallResult.Installed -> completeInstall(itemId, item)
            is ArchiveInstallResult.Failed -> {
                if (recoveringPublishedInstall && result.failure is ArchiveInstallFailure.TargetExists) {
                    completeInstall(itemId, item)
                } else {
                    val message = if (
                        item.source is NarDownloadSource.Local &&
                        result.failure is ArchiveInstallFailure.SourceUnavailable
                    ) {
                        RESELECT_SOURCE_FAILURE
                    } else {
                        result.message
                    }
                    markNeedsAttention(itemId, message)
                }
            }
            ArchiveInstallResult.Cancelled -> markNeedsAttention(itemId, INSTALL_INTERRUPTED)
        }
        publish()
    }

    private fun sourceUnavailable(item: NarDownload) = ArchiveInstallResult.Failed(
        if (item.source is NarDownloadSource.Local) RESELECT_SOURCE_FAILURE else REMOTE_SOURCE_FAILURE,
        ArchiveInstallFailure.SourceUnavailable,
    )

    private fun completeInstall(itemId: String, item: NarDownload) {
        store.update(itemId) { it.copy(state = NarDownloadState.Complete) }
        cleanupCompletedInstall(item)
    }

    private fun cleanupCompletedInstall(item: NarDownload) {
        item.downloadManagerId?.let { runCatching { downloads.remove(it) } }
        runCatching { ownedData.delete(item) }
        releasePersistedGrantIfUnused(item)
    }

    private fun markNeedsAttention(itemId: String, message: String) {
        store.update(itemId) {
            it.copy(
                state = NarDownloadState.NeedsAttention(
                    NarDownloadState.Failure(message),
                ),
            )
        }
    }

    private fun scheduleInstall(itemId: String) {
        try {
            work.enqueue(itemId)
        } catch (_: Exception) {
            markNeedsAttention(itemId, INSTALL_SCHEDULE_FAILURE)
        }
    }

    private fun releasePersistedGrantIfUnused(item: NarDownload) {
        val location = item.retainedUri ?: return
        val isStillReferenced = store.getAll().any { other ->
            other.id != item.id && (
                other.retainedUri == location ||
                    (other.source as? NarDownloadSource.Local)?.uri == location
                )
        }
        if (!isStillReferenced) runCatching { ownedData.releasePersistedGrant(item) }
    }

    private fun publish() {
        observedDownloads.value = store.getAll()
    }

    private fun NarDownloadState.isNonterminal() =
        this is NarDownloadState.Queued ||
            this is NarDownloadState.Downloading ||
            this is NarDownloadState.Installing

    companion object {
        private const val DOWNLOAD_START_FAILURE =
            "Nanidroid could not start this archive download. Check storage and retry."
        private const val DOWNLOAD_RECOVERY_FAILURE =
            "This archive download is no longer available. Retry the download."
        private const val INSTALL_SCHEDULE_FAILURE =
            "Nanidroid could not schedule this archive install. Retry it."
        private const val RESELECT_SOURCE_FAILURE =
            "Select the archive again to continue."
        private const val REMOTE_SOURCE_FAILURE =
            "The downloaded archive is no longer available. Retry the download."
        private const val INSTALL_FAILURE =
            "Nanidroid could not install this archive. Retry or delete it."
        private const val INSTALL_INTERRUPTED =
            "The archive install was interrupted. Retry it."

        @Volatile private var instance: NarDownloadRepository? = null

        @JvmStatic
        fun get(context: Context): NarDownloadRepository {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: create(context.applicationContext).also {
                    instance = it
                    it.reconcile()
                }
            }
        }

        internal fun workName(itemId: String) = "install-nar-$itemId"

        private fun create(context: Context): NarDownloadRepository {
            val managedFiles = AndroidNarManagedFiles(context)
            return NarDownloadRepository(
                store = NarDownloadStore(context),
                downloads = AndroidNarDownloadGateway(context),
                work = AndroidNarInstallWorkScheduler(context),
                installer = AndroidNarArchiveInstaller(context),
                ownedData = managedFiles,
                attemptPaths = managedFiles,
                nextId = { UUID.randomUUID().toString() },
            )
        }

        private fun normalizeHttpsUrl(value: String): String {
            val parsed = URI(value)
            require(parsed.isAbsolute && parsed.scheme.equals("https", ignoreCase = true))
            require(!parsed.host.isNullOrBlank() && parsed.rawAuthority != null)
            val normalized = URI("https" + value.substring(parsed.scheme.length)).normalize()
            require(normalized.scheme == "https" && !normalized.host.isNullOrBlank())
            return normalized.toASCIIString()
        }
    }
}

private class AndroidNarDownloadGateway(context: Context) : NarDownloadGateway {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(DownloadManager::class.java)
        ?: throw IllegalStateException("DownloadManager unavailable")

    override fun intendedRetainedUri(itemId: String): String = destination(itemId).toURI().toString()

    override fun enqueue(itemId: String, normalizedHttpsUrl: String): NarRemoteEnqueue {
        val destination = destination(itemId)
        val request = DownloadManager.Request(Uri.parse(normalizedHttpsUrl))
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationUri(Uri.fromFile(destination))
        return NarRemoteEnqueue(manager.enqueue(request), destination.toURI().toString())
    }

    override fun findDownloadId(retainedUri: String): Long? {
        manager.query(DownloadManager.Query()).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) == retainedUri) {
                    return cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                }
            }
        }
        return null
    }

    private fun destination(itemId: String): File {
        val externalRoot = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("external download storage unavailable")
        val directory = File(externalRoot, DOWNLOAD_DIRECTORY)
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IOException("external download directory unavailable")
        }
        return File(directory, "$itemId.nar")
    }

    override fun remove(downloadManagerId: Long) {
        manager.remove(downloadManagerId)
    }

    override fun status(downloadManagerId: Long): NarRemoteDownloadStatus? {
        manager.query(DownloadManager.Query().setFilterById(downloadManagerId)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> NarRemoteDownloadStatus.Successful(
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
                )
                DownloadManager.STATUS_FAILED -> NarRemoteDownloadStatus.Failed
                else -> NarRemoteDownloadStatus.InProgress
            }
        }
    }

    private companion object {
        const val DOWNLOAD_DIRECTORY = "nar-downloads"
    }
}

private class AndroidNarArchiveInstaller(context: Context) : NarArchiveInstaller {
    private val appContext = context.applicationContext

    override fun install(
        download: NarDownload,
        stagingDirectory: File,
        isStopped: () -> Boolean,
    ): ArchiveInstallResult {
        val location = download.retainedUri ?: when (val source = download.source) {
            is NarDownloadSource.Local -> source.uri
            is NarDownloadSource.Remote -> return ArchiveInstallResult.Failed(
                "The downloaded archive is no longer available.",
                ArchiveInstallFailure.SourceUnavailable,
            )
        }
        return NarContentUriImport.importContent(
            scheme = "content",
            cacheDir = stagingDirectory,
            open = { open(location) },
            install = { staged -> GhostMgr(appContext).installGhost("", staged.path, isStopped) },
            isCancelled = isStopped,
        )
    }

    private fun open(location: String) = when (URI(location).scheme?.lowercase()) {
        "content" -> appContext.contentResolver.openInputStream(Uri.parse(location))
        "file" -> FileInputStream(File(URI(location)))
        null -> FileInputStream(File(location))
        else -> throw FileNotFoundException("unsupported archive source")
    }
}

private class AndroidNarManagedFiles(context: Context) :
    NarOwnedDownloadData,
    NarInstallAttemptPaths {
    private val appContext = context.applicationContext
    private val attemptsRoot = File(appContext.cacheDir, "nar-install-attempts")

    override fun create(itemId: String): File {
        val itemRoot = File(attemptsRoot, safeName(itemId))
        val attempt = File(itemRoot, UUID.randomUUID().toString())
        if (!attempt.mkdirs() || !attempt.isDirectory) {
            throw IOException("cannot create archive attempt directory")
        }
        return attempt
    }

    override fun delete(directory: File) {
        directory.deleteRecursively()
        directory.parentFile?.delete()
    }

    override fun delete(download: NarDownload) {
        managedFile(download.retainedUri)?.deleteRecursively()
        File(attemptsRoot, safeName(download.id)).deleteRecursively()
    }

    override fun releasePersistedGrant(download: NarDownload) {
        val location = download.retainedUri ?: return
        val uri = runCatching { Uri.parse(location) }.getOrNull() ?: return
        if (!uri.scheme.equals("content", ignoreCase = true)) return
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun managedFile(value: String?): File? {
        if (value == null) return null
        val candidate = try {
            val uri = URI(value)
            when (uri.scheme?.lowercase()) {
                "file" -> File(uri)
                null -> File(value)
                else -> return null
            }.canonicalFile
        } catch (_: Exception) {
            return null
        }
        val ownedRoots = listOfNotNull(
            appContext.filesDir,
            appContext.cacheDir,
            appContext.getExternalFilesDir(null),
        ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        return candidate.takeIf { file ->
            ownedRoots.any { root ->
                file == root || file.path.startsWith(root.path + File.separator)
            }
        }
    }

    private fun safeName(itemId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(itemId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
