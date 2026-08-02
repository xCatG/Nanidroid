package com.cattailsw.nanidroid.install

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.cattailsw.nanidroid.GhostMgr
import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.durable.AttemptId
import com.cattailsw.nanidroid.durable.DurableOperationSupervisor
import com.cattailsw.nanidroid.durable.ExternalJobBinding
import com.cattailsw.nanidroid.durable.OperationCancellation
import com.cattailsw.nanidroid.durable.OperationHandle
import com.cattailsw.nanidroid.durable.OperationId
import com.cattailsw.nanidroid.durable.OperationKind
import com.cattailsw.nanidroid.durable.OperationStatus
import com.cattailsw.nanidroid.durable.SharedPreferencesDurableOperationStore
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
    fun downloadedBytes(downloadManagerId: Long): Long? = null
}

internal interface NarInstallWorkScheduler {
    fun enqueue(itemId: String)
    fun cancel(itemId: String)

    fun enqueue(
        itemId: String,
        attemptId: Long,
        onPrepared: (workManagerId: String) -> Boolean,
    ): Boolean {
        if (!onPrepared("${NarDownloadRepository.workName(itemId)}-$attemptId")) return false
        enqueue(itemId)
        return true
    }

    fun enqueueStage(
        itemId: String,
        attemptId: Long,
        onPrepared: (workManagerId: String) -> Boolean,
    ): Boolean {
        if (!onPrepared("stage-local-nar-$itemId-$attemptId")) return false
        return true
    }
}

internal interface NarArchiveInstaller {
    fun install(
        download: NarDownload,
        stagingDirectory: File,
        isStopped: () -> Boolean,
    ): ArchiveInstallResult

    fun install(
        download: NarDownload,
        stagingDirectory: File,
        isStopped: () -> Boolean,
        onProgress: (phase: String, completed: Long) -> Unit,
    ): ArchiveInstallResult = install(download, stagingDirectory, isStopped)
}

internal interface NarOwnedDownloadData {
    fun delete(download: NarDownload)
    fun releasePersistedGrant(download: NarDownload) = Unit
    fun deleteAbandonedLocalArchives(retainedUris: Set<String>) = Unit
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
    private val supervisor: DurableOperationSupervisor,
    private val remoteProgress: NarRemoteProgressObserver = DownloadManagerProgressObserver(
        downloads,
        supervisor,
    ),
    private val nextId: () -> String,
) {
    private val observedDownloads = MutableStateFlow(store.getAll())
    private val liveCopyAttempts = mutableSetOf<OperationHandle>()

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
        startRemoteDownload(item.id, url)
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
                state = NarDownloadState.Copying,
            ),
        )
        publish()
        return item
    }

    @Synchronized
    fun enqueueLocalCopy(uri: String): NarDownload = enqueueLocalCopy(uri, liveGrant = false)

    @Synchronized
    internal fun enqueueLiveLocalCopy(uri: String): NarDownload =
        enqueueLocalCopy(uri, liveGrant = true)

    private fun enqueueLocalCopy(uri: String, liveGrant: Boolean): NarDownload {
        val item = store.create(
            NarDownload(
                id = nextId(),
                source = NarDownloadSource.Local(uri),
                retainedUri = uri,
                state = NarDownloadState.Copying,
            ),
        )
        if (liveGrant) liveCopyAttempts += item.handle()
        scheduleLocalCopy(item)
        if (store.get(item.id)?.state != NarDownloadState.Copying) {
            liveCopyAttempts -= item.handle()
        }
        publish()
        return store.get(item.id)!!
    }

    private fun scheduleLocalCopy(item: NarDownload) {
        val handle = item.handle()
        if (!supervisor.start(handle, OperationKind.LOCAL_NAR, "Copying archive", 0L)) {
            markNeedsAttention(item.id, COPY_INTERRUPTED)
            return
        }
        try {
            val enqueued = work.enqueueStage(item.id, item.attemptId) { workManagerId ->
                if (!supervisor.bindExternalJob(
                        handle,
                        ExternalJobBinding.WorkManager(workManagerId),
                    )
                ) {
                    return@enqueueStage false
                }
                val updated = store.update(item.id) { current ->
                    if (current.attemptId == item.attemptId) {
                        current.copy(workManagerId = workManagerId)
                    } else {
                        current
                    }
                }
                updated?.attemptId == item.attemptId
            }
            if (!enqueued) throw IllegalStateException("local stage work was not accepted")
        } catch (_: Exception) {
            supervisor.finish(handle, OperationStatus.FAILED, COPY_INTERRUPTED)
            markNeedsAttention(item.id, COPY_INTERRUPTED)
        }
    }

    fun stageLocal(
        itemId: String,
        attemptId: Long,
        isStopped: () -> Boolean,
        stage: (
            download: NarDownload,
            isCancelled: () -> Boolean,
            onProgress: (phase: String, completed: Long) -> Unit,
        ) -> NarLocalArchiveStager.Result,
    ) = stageLocalAttempt(itemId, attemptId, isStopped, liveGrant = false, stage)

    internal fun stageLiveLocal(
        itemId: String,
        attemptId: Long,
        isStopped: () -> Boolean,
        stage: (
            download: NarDownload,
            isCancelled: () -> Boolean,
            onProgress: (phase: String, completed: Long) -> Unit,
        ) -> NarLocalArchiveStager.Result,
    ) = stageLocalAttempt(itemId, attemptId, isStopped, liveGrant = true, stage)

    private fun stageLocalAttempt(
        itemId: String,
        attemptId: Long,
        isStopped: () -> Boolean,
        liveGrant: Boolean,
        stage: (
            download: NarDownload,
            isCancelled: () -> Boolean,
            onProgress: (phase: String, completed: Long) -> Unit,
        ) -> NarLocalArchiveStager.Result,
    ): Boolean {
        val handle = OperationHandle(OperationId(itemId), AttemptId(attemptId))
        if (liveGrant) {
            synchronized(this) {
                if (handle !in liveCopyAttempts) return true
            }
        } else {
            synchronized(this) {
                if (handle in liveCopyAttempts) return false
            }
        }
        try {
            val item = store.get(itemId) ?: return true
            if (item.attemptId != attemptId || item.state != NarDownloadState.Copying) return true
            val result = stage(
                item,
                { isStopped() || cancellationRequested(handle) },
                { phase, completed -> supervisor.reportProgress(handle, phase, completed) },
            )
            val current = store.get(itemId)
            if (current?.attemptId != attemptId || current.state != NarDownloadState.Copying) {
                if (result is NarLocalArchiveStager.Result.Staged) {
                    NarLocalArchiveStager.discard(result.location)
                }
                return true
            }
            when (result) {
                is NarLocalArchiveStager.Result.Staged -> {
                    if (!supervisor.finish(handle, OperationStatus.COMPLETED)) {
                        NarLocalArchiveStager.discard(result.location)
                        return true
                    }
                    store.update(itemId) { latest ->
                        if (latest.attemptId == attemptId) {
                            latest.copy(
                                attemptId = latest.attemptId + 1L,
                                retainedUri = result.location,
                                workManagerId = null,
                                state = NarDownloadState.Queued,
                            )
                        } else {
                            latest
                        }
                    }
                    scheduleInstall(itemId)
                }
                is NarLocalArchiveStager.Result.Failed -> {
                    if (supervisor.finish(handle, OperationStatus.FAILED, result.message)) {
                        markNeedsAttention(itemId, result.message)
                    }
                }
                NarLocalArchiveStager.Result.Cancelled -> {
                    if (supervisor.finish(handle, OperationStatus.CANCELLED)) {
                        store.update(itemId) { latest ->
                            if (latest.attemptId == attemptId) {
                                latest.copy(state = NarDownloadState.Cancelled)
                            } else {
                                latest
                            }
                        }
                    }
                }
            }
            publish()
            return true
        } finally {
            if (liveGrant) synchronized(this) { liveCopyAttempts -= handle }
        }
    }

    internal fun abandonLiveLocalCopy(itemId: String, attemptId: Long) {
        synchronized(this) {
            liveCopyAttempts -= OperationHandle(OperationId(itemId), AttemptId(attemptId))
        }
    }

    @Synchronized
    fun copyFailed(itemId: String) {
        val item = store.get(itemId) ?: return
        if (item.state == NarDownloadState.Copying) {
            markNeedsAttention(itemId, COPY_INTERRUPTED)
            publish()
        }
    }

    @Synchronized
    fun retry(itemId: String): NarDownload? {
        val item = store.get(itemId) ?: return null
        if (item.state == NarDownloadState.Copying) return item
        runCatching { work.cancel(itemId) }
        when (val source = item.source) {
            is NarDownloadSource.Remote -> {
                if (item.state != NarDownloadState.Cancelled) {
                    item.downloadManagerId?.let { runCatching { downloads.remove(it) } }
                }
                runCatching { ownedData.delete(item) }
                store.update(itemId) {
                    it.copy(
                        attemptId = it.attemptId + 1L,
                        retainedUri = null,
                        downloadManagerId = null,
                        workManagerId = null,
                        state = NarDownloadState.Downloading,
                    )
                }
                startRemoteDownload(itemId, source.uri)
            }
            is NarDownloadSource.Local -> {
                store.update(itemId) {
                    it.copy(
                        attemptId = it.attemptId + 1L,
                        workManagerId = null,
                        state = NarDownloadState.Queued,
                    )
                }
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
                attemptId = it.attemptId + 1L,
                source = NarDownloadSource.Local(uri),
                retainedUri = uri,
                downloadManagerId = null,
                workManagerId = null,
                state = NarDownloadState.Queued,
            )
        }
        if (item.retainedUri != uri) releasePersistedGrantIfUnused(item)
        scheduleInstall(itemId)
        publish()
        return store.get(itemId)
    }

    @Synchronized
    fun replaceLocalSourceForCopy(itemId: String, uri: String): NarDownload? {
        val item = store.get(itemId) ?: return null
        if (item.source !is NarDownloadSource.Local) return null
        if (!delete(itemId)) return null
        return enqueueLocalCopy(uri)
    }

    @Synchronized
    internal fun replaceLocalSourceForLiveCopy(itemId: String, uri: String): NarDownload? {
        val item = store.get(itemId) ?: return null
        if (item.source !is NarDownloadSource.Local) return null
        if (!delete(itemId)) return null
        return enqueueLiveLocalCopy(uri)
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
    fun isSourceReferenced(location: String): Boolean = hasSourceReference(location)

    @Synchronized
    fun onDownloadComplete(downloadManagerId: Long) {
        val item = store.getAll().firstOrNull {
            it.downloadManagerId == downloadManagerId && it.state == NarDownloadState.Downloading
        } ?: return
        if (advanceToInstall(item) == null) return
        scheduleInstall(item.id)
        publish()
    }

    @Synchronized
    fun observeRemoteProgress(itemId: String): Boolean {
        val item = store.get(itemId) ?: return false
        val downloadManagerId = item.downloadManagerId ?: return false
        if (item.state != NarDownloadState.Downloading) return false
        return remoteProgress.observeOnce(
            item.handle(),
            downloadManagerId,
        )
    }

    @Synchronized
    fun stop(itemId: String): Boolean {
        val item = store.get(itemId) ?: return false
        if (!item.state.isNonterminal() && item.state != NarDownloadState.Copying) return false
        val handle = item.handle()
        if (!supervisor.requestStop(handle)) return false
        if (item.state == NarDownloadState.Downloading) remoteProgress.stop(handle)
        val updated = store.update(itemId) { current ->
            if (current.attemptId == item.attemptId) {
                current.copy(state = NarDownloadState.Cancelled)
            } else {
                current
            }
        }
        if (updated?.attemptId != item.attemptId) return false
        supervisor.finish(handle, OperationStatus.CANCELLED)
        publish()
        return true
    }

    @Synchronized
    fun workerStopped(itemId: String, attemptId: Long) {
        val item = store.get(itemId) ?: return
        if (item.attemptId != attemptId || !item.state.isNonterminal() && item.state != NarDownloadState.Copying) {
            return
        }
        if (!supervisor.finish(item.handle(), OperationStatus.CANCELLED)) return
        store.update(itemId) { current ->
            if (current.attemptId == attemptId) current.copy(state = NarDownloadState.Cancelled)
            else current
        }
        publish()
    }

    @Synchronized
    fun reconcile() {
        store.getAll()
            .filter { it.state == NarDownloadState.Copying && it.workManagerId == null }
            .forEach { markNeedsAttention(it.id, COPY_INTERRUPTED) }
        store.getAll()
            .filter { it.state == NarDownloadState.Complete }
            .forEach(::cleanupCompletedInstall)
        ownedData.deleteAbandonedLocalArchives(
            store.getAll()
                .filter { it.state != NarDownloadState.Complete }
                .mapNotNull(NarDownload::retainedUri)
                .toSet(),
        )
        store.getAll()
            .filter {
                it.source is NarDownloadSource.Remote &&
                    it.state == NarDownloadState.Downloading
            }
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
                        remoteProgress.start(item.handle(), downloadManagerId)
                    }
                    is NarRemoteDownloadStatus.Successful -> {
                        store.update(item.id) {
                            it.copy(retainedUri = status.localUri ?: it.retainedUri)
                        }
                        val updated = store.get(item.id)
                        if (updated != null) advanceToInstall(updated)
                    }
                    NarRemoteDownloadStatus.Failed,
                    null -> markNeedsAttention(item.id, DOWNLOAD_RECOVERY_FAILURE)
                }
            }
        store.getAll()
            .filter {
                it.state == NarDownloadState.Queued ||
                    it.state == NarDownloadState.Installing
            }
            .forEach { item ->
                scheduleInstall(item.id)
            }
        publish()
    }

    fun install(itemId: String, isStopped: () -> Boolean) {
        val attemptId = store.get(itemId)?.attemptId ?: return
        install(itemId, attemptId, isStopped)
    }

    fun install(itemId: String, attemptId: Long, isStopped: () -> Boolean) {
        val item = store.get(itemId) ?: return
        if (item.attemptId != attemptId) return
        if (
            item.state is NarDownloadState.NeedsAttention ||
            item.state is NarDownloadState.Copying ||
            item.state is NarDownloadState.Complete
        ) return
        if (item.state == NarDownloadState.Cancelled) return
        if (item.source is NarDownloadSource.Remote && item.state == NarDownloadState.Downloading) {
            val installItem = advanceToInstall(item) ?: return
            scheduleInstall(itemId)
            install(itemId, installItem.attemptId, isStopped)
            return
        }
        val recoveringPublishedInstall = item.state is NarDownloadState.Installing
        val installing = store.update(itemId) {
            it.copy(state = NarDownloadState.Installing)
        } ?: return
        publish()
        var stagingDirectory: File? = null
        val result = try {
            stagingDirectory = attemptPaths.create(itemId)
            installer.install(
                installing,
                stagingDirectory,
                { isStopped() || cancellationRequested(installing.handle()) },
            ) { phase, completed ->
                supervisor.reportProgress(installing.handle(), phase, completed)
            }
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
            is ArchiveInstallResult.Installed -> {
                val current = store.get(itemId)
                if (current?.attemptId == installing.attemptId) {
                    supervisor.finish(installing.handle(), OperationStatus.COMPLETED)
                    completeInstall(itemId, item, installing.attemptId)
                }
            }
            is ArchiveInstallResult.Failed -> {
                if (!supervisor.finish(installing.handle(), OperationStatus.FAILED, result.message)) {
                    publish()
                    return
                }
                if (recoveringPublishedInstall && result.failure is ArchiveInstallFailure.TargetExists) {
                    completeInstall(itemId, item, installing.attemptId)
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
            ArchiveInstallResult.Cancelled -> {
                if (supervisor.finish(installing.handle(), OperationStatus.CANCELLED)) {
                    store.update(itemId) { current ->
                        if (current.attemptId == installing.attemptId) {
                            current.copy(state = NarDownloadState.Cancelled)
                        } else {
                            current
                        }
                    }
                }
            }
        }
        publish()
    }

    private fun sourceUnavailable(item: NarDownload) = ArchiveInstallResult.Failed(
        if (item.source is NarDownloadSource.Local) RESELECT_SOURCE_FAILURE else REMOTE_SOURCE_FAILURE,
        ArchiveInstallFailure.SourceUnavailable,
    )

    private fun advanceToInstall(item: NarDownload): NarDownload? {
        remoteProgress.stop(item.handle())
        if (!supervisor.finish(item.handle(), OperationStatus.COMPLETED)) return null
        return store.update(item.id) { current ->
            if (current.attemptId == item.attemptId) {
                current.copy(
                    attemptId = current.attemptId + 1L,
                    workManagerId = null,
                    state = NarDownloadState.Queued,
                )
            } else {
                current
            }
        }?.takeIf { it.attemptId == item.attemptId + 1L }
    }

    private fun completeInstall(itemId: String, item: NarDownload, attemptId: Long) {
        store.update(itemId) { current ->
            if (current.attemptId == attemptId) current.copy(state = NarDownloadState.Complete)
            else current
        }
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
        val item = store.get(itemId) ?: return
        val handle = item.handle()
        val started = supervisor.start(
            handle,
            OperationKind.NAR_INSTALL,
            "Installing archive",
            0L,
        )
        if (!started) {
            if (item.workManagerId != null) runCatching { work.enqueue(itemId) }
            return
        }
        try {
            val enqueued = work.enqueue(itemId, item.attemptId) { workManagerId ->
                val binding = ExternalJobBinding.WorkManager(workManagerId)
                if (!supervisor.bindExternalJob(handle, binding)) return@enqueue false
                val updated = store.update(itemId) { current ->
                    if (current.attemptId == item.attemptId) {
                        current.copy(workManagerId = workManagerId)
                    } else {
                        current
                    }
                }
                updated?.attemptId == item.attemptId
            }
            if (!enqueued) throw IllegalStateException("install work was not accepted")
        } catch (_: Exception) {
            supervisor.finish(handle, OperationStatus.FAILED, INSTALL_SCHEDULE_FAILURE)
            markNeedsAttention(itemId, INSTALL_SCHEDULE_FAILURE)
        }
    }

    private fun startRemoteDownload(itemId: String, url: String) {
        try {
            store.update(itemId) {
                it.copy(
                    retainedUri = downloads.intendedRetainedUri(itemId),
                    downloadManagerId = null,
                    state = NarDownloadState.Downloading,
                )
            }
            val enqueued = downloads.enqueue(itemId, normalizeHttpsUrl(url))
            val updated = store.update(itemId) {
                it.copy(
                    retainedUri = enqueued.retainedUri,
                    downloadManagerId = enqueued.downloadManagerId,
                    workManagerId = null,
                    state = NarDownloadState.Downloading,
                )
            } ?: return
            val handle = updated.handle()
            if (!supervisor.start(
                    handle,
                    OperationKind.REMOTE_NAR,
                    "Downloading archive",
                    0L,
                    ExternalJobBinding.DownloadManager(enqueued.downloadManagerId),
                )
            ) {
                downloads.remove(enqueued.downloadManagerId)
                markNeedsAttention(itemId, DOWNLOAD_START_FAILURE)
            } else {
                remoteProgress.start(handle, enqueued.downloadManagerId)
            }
        } catch (_: Exception) {
            markNeedsAttention(itemId, DOWNLOAD_START_FAILURE)
        }
    }

    private fun releasePersistedGrantIfUnused(item: NarDownload) {
        val location = item.retainedUri ?: return
        if (!hasSourceReference(location, item.id)) {
            runCatching { ownedData.releasePersistedGrant(item) }
        }
    }

    private fun hasSourceReference(location: String, excludedItemId: String? = null) =
        store.getAll().any { other ->
            other.id != excludedItemId && other.state != NarDownloadState.Complete && (
                other.retainedUri == location ||
                    (other.source as? NarDownloadSource.Local)?.uri == location
                )
        }

    private fun publish() {
        observedDownloads.value = store.getAll()
    }

    private fun cancellationRequested(handle: OperationHandle): Boolean =
        supervisor.snapshot().any {
            it.id == handle.operationId &&
                it.attemptId == handle.attemptId &&
                it.status == OperationStatus.CANCEL_REQUESTED
        } || store.get(handle.operationId.value)?.let {
            it.attemptId == handle.attemptId.value && it.state == NarDownloadState.Cancelled
        } == true

    private fun NarDownload.handle() = OperationHandle(OperationId(id), AttemptId(attemptId))

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
        private const val COPY_INTERRUPTED =
            "The archive copy was interrupted. Select the archive again to continue."

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
        internal fun stageWorkName(itemId: String) = "stage-local-nar-$itemId"

        private fun create(context: Context): NarDownloadRepository {
            val managedFiles = AndroidNarManagedFiles(context)
            val workScheduler = AndroidNarInstallWorkScheduler(context)
            val downloadGateway = AndroidNarDownloadGateway(context)
            val supervisor = DurableOperationSupervisor(
                SharedPreferencesDurableOperationStore(context),
                MonotonicClock { android.os.SystemClock.elapsedRealtime() },
                AndroidNarOperationCancellation(context),
            )
            return NarDownloadRepository(
                store = NarDownloadStore(context),
                downloads = downloadGateway,
                work = workScheduler,
                installer = AndroidNarArchiveInstaller(context),
                ownedData = managedFiles,
                attemptPaths = managedFiles,
                supervisor = supervisor,
                remoteProgress = DownloadManagerProgressObserver(downloadGateway, supervisor),
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

    override fun intendedRetainedUri(itemId: String): String = Uri.fromFile(destination(itemId)).toString()

    override fun enqueue(itemId: String, normalizedHttpsUrl: String): NarRemoteEnqueue {
        val destination = destination(itemId)
        val request = DownloadManager.Request(Uri.parse(normalizedHttpsUrl))
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationUri(Uri.fromFile(destination))
        return NarRemoteEnqueue(manager.enqueue(request), Uri.fromFile(destination).toString())
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

    override fun downloadedBytes(downloadManagerId: Long): Long? {
        manager.query(DownloadManager.Query().setFilterById(downloadManagerId)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
        }
    }

    private companion object {
        const val DOWNLOAD_DIRECTORY = "nar-downloads"
    }
}

private class AndroidNarOperationCancellation(context: Context) : OperationCancellation {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val workManager by lazy { androidx.work.WorkManager.getInstance(appContext) }

    override fun cancel(handle: OperationHandle, binding: ExternalJobBinding) {
        when (binding) {
            is ExternalJobBinding.DownloadManager -> downloadManager?.remove(binding.id)
            is ExternalJobBinding.WorkManager -> runCatching {
                workManager.cancelWorkById(UUID.fromString(binding.uuid))
            }
        }
    }
}

private class AndroidNarArchiveInstaller(context: Context) : NarArchiveInstaller {
    private val appContext = context.applicationContext

    override fun install(
        download: NarDownload,
        stagingDirectory: File,
        isStopped: () -> Boolean,
    ): ArchiveInstallResult = install(download, stagingDirectory, isStopped) { _, _ -> }

    override fun install(
        download: NarDownload,
        stagingDirectory: File,
        isStopped: () -> Boolean,
        onProgress: (phase: String, completed: Long) -> Unit,
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
            install = { staged ->
                installStaged(staged, isStopped, onProgress)
            },
            isCancelled = isStopped,
        )
    }

    private fun installStaged(
        archive: File,
        isStopped: () -> Boolean,
        onProgress: (phase: String, completed: Long) -> Unit,
    ): ArchiveInstallResult {
        val externalFiles = appContext.getExternalFilesDir(null)
            ?: return ArchiveInstallResult.Failed(
                "Nanidroid cannot access the selected ghost archive or storage.",
                ArchiveInstallFailure.StorageUnavailable,
            )
        val installRoot = File(externalFiles, "ghost")
        if ((!installRoot.exists() && !installRoot.mkdirs()) || !installRoot.isDirectory) {
            return ArchiveInstallResult.Failed(
                "Nanidroid cannot prepare its ghost storage.",
                ArchiveInstallFailure.StorageUnavailable,
            )
        }
        val installed = NarTransactionalInstaller.install(
            archive,
            installRoot,
            null,
            isStopped,
            onProgress,
        )
        if (installed !is ArchiveInstallResult.Installed) return installed
        val manager = GhostMgr(appContext)
        manager.refreshGhost()
        val id = installed.targetId?.let(manager::getGhostId) ?: -1
        if (id == -1) {
            return ArchiveInstallResult.Failed(
                "The installed archive does not contain a usable ghost.",
                ArchiveInstallFailure.InvalidArchive,
            )
        }
        return ArchiveInstallResult.Installed(manager.getGhostPath(id), installed.targetId)
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
    private val localImportsRoot = File(appContext.filesDir, "nar-local-imports")

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

    override fun deleteAbandonedLocalArchives(retainedUris: Set<String>) {
        localImportsRoot.listFiles()
            ?.filter { it.isFile && it.toURI().toString() !in retainedUris }
            ?.forEach { it.delete() }
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
