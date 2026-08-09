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
import com.cattailsw.nanidroid.durable.SharedDurableOperationSupervisor
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

/** The durable enqueue outcome needed by a user-facing permission prompt. */
internal data class NarUserEnqueueResult(
    val download: NarDownload,
    val acceptedActive: Boolean,
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

    fun ensureStageEnqueued(
        itemId: String,
        attemptId: Long,
        workManagerId: String,
        recreateIfMissing: Boolean = true,
    ): NarStageWorkRecovery

    fun ensureInstallEnqueued(
        itemId: String,
        attemptId: Long,
        workManagerId: String,
        recreateIfMissing: Boolean = true,
    ): NarInstallWorkRecovery
}

internal enum class NarStageWorkRecovery {
    ACTIVE,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    MISSING,
}

internal enum class NarInstallWorkRecovery {
    ACTIVE,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    MISSING,
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
    fun retainedArchiveAvailable(download: NarDownload): Boolean = false
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
    private val installProgress: ThrottledNarInstallProgressReporter =
        ThrottledNarInstallProgressReporter(
            supervisor,
            MonotonicClock { System.nanoTime() / 1_000_000L },
        ),
    private val remoteProgress: NarRemoteProgressObserver = DownloadManagerProgressObserver(
        downloads,
        supervisor,
    ),
    private val stopReconciliation: NarStopReconciliationScheduler =
        NarStopReconciliationScheduler.None,
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
    internal fun enqueueRemoteForUser(url: String): NarUserEnqueueResult =
        userEnqueueResult(enqueueRemote(url))

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
    internal fun enqueueLocalCopyForUser(uri: String): NarUserEnqueueResult =
        userEnqueueResult(enqueueLocalCopy(uri))

    @Synchronized
    internal fun enqueueLiveLocalCopy(uri: String): NarDownload =
        enqueueLocalCopy(uri, liveGrant = true)

    @Synchronized
    internal fun replaceLocalSourceForUser(itemId: String, uri: String): NarUserEnqueueResult? {
        val previous = store.get(itemId) ?: return null
        return replaceLocalSource(itemId, uri)?.let { replacement ->
            userEnqueueResult(replacement, accepted = replacement.handle() != previous.handle())
        }
    }

    @Synchronized
    internal fun userEnqueueResult(
        download: NarDownload,
        accepted: Boolean = true,
    ): NarUserEnqueueResult = NarUserEnqueueResult(
        download = download,
        acceptedActive = accepted && when (download.state) {
            NarDownloadState.Downloading -> download.downloadManagerId != null
            NarDownloadState.Copying,
            NarDownloadState.Queued -> download.workManagerId != null
            else -> false
        },
    )

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
        var acceptedBinding: ExternalJobBinding.WorkManager? = null
        try {
            val enqueued = work.enqueueStage(item.id, item.attemptId) { workManagerId ->
                val binding = ExternalJobBinding.WorkManager(workManagerId)
                if (!supervisor.bindExternalJob(handle, binding)) {
                    return@enqueueStage false
                }
                acceptedBinding = binding
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
            if (failSchedulingAttempt(handle, acceptedBinding, COPY_INTERRUPTED)) {
                markNeedsAttention(item.id, COPY_INTERRUPTED)
            }
        }
    }

    fun stageLocal(
        itemId: String,
        attemptId: Long,
        workManagerId: String,
        isStopped: () -> Boolean,
        stage: (
            download: NarDownload,
            isCancelled: () -> Boolean,
            onProgress: (phase: String, completed: Long) -> Unit,
        ) -> NarLocalArchiveStager.Result,
    ) = stageLocalAttempt(
        itemId,
        attemptId,
        workManagerId,
        isStopped,
        liveGrant = false,
        stage,
    )

    internal fun stageLiveLocal(
        itemId: String,
        attemptId: Long,
        workManagerId: String,
        isStopped: () -> Boolean,
        stage: (
            download: NarDownload,
            isCancelled: () -> Boolean,
            onProgress: (phase: String, completed: Long) -> Unit,
        ) -> NarLocalArchiveStager.Result,
    ) = stageLocalAttempt(
        itemId,
        attemptId,
        workManagerId,
        isStopped,
        liveGrant = true,
        stage,
    )

    private fun stageLocalAttempt(
        itemId: String,
        attemptId: Long,
        workManagerId: String,
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
            if (
                item.attemptId != attemptId ||
                item.workManagerId != workManagerId ||
                item.state != NarDownloadState.Copying
            ) {
                return true
            }
            val binding = ExternalJobBinding.WorkManager(workManagerId)
            val result = stage(
                item,
                {
                    isStopped() ||
                        cancellationRequested(handle) ||
                        (liveGrant && !isLiveCopyAttemptActive(handle))
                },
                { phase, completed -> supervisor.reportProgress(handle, binding, phase, completed) },
            )
            val current = store.get(itemId)
            if (current?.attemptId != attemptId || current.state != NarDownloadState.Copying) {
                if (result is NarLocalArchiveStager.Result.Staged) {
                    NarLocalArchiveStager.discard(result.location)
                }
                return true
            }
            if (
                result !is NarLocalArchiveStager.Result.Staged &&
                isStopped() &&
                !cancellationRequested(handle)
            ) {
                publish()
                return false
            }
            when (result) {
                is NarLocalArchiveStager.Result.Staged -> {
                    val installAttempt = store.update(itemId) { latest ->
                        if (
                            latest.attemptId == attemptId &&
                            latest.state == NarDownloadState.Copying
                        ) {
                            latest.copy(
                                attemptId = latest.attemptId + 1L,
                                retainedUri = result.location,
                                workManagerId = null,
                                state = NarDownloadState.Queued,
                            )
                        } else {
                            latest
                        }
                    }?.takeIf {
                        it.attemptId == attemptId + 1L &&
                            it.state == NarDownloadState.Queued &&
                            it.retainedUri == result.location
                    }
                    if (installAttempt == null) {
                        NarLocalArchiveStager.discard(result.location)
                        return true
                    }
                    supervisor.finish(handle, binding, OperationStatus.COMPLETED)
                    scheduleInstall(itemId)
                }
                is NarLocalArchiveStager.Result.Failed -> {
                    if (supervisor.finish(handle, binding, OperationStatus.FAILED, result.message)) {
                        markNeedsAttention(itemId, result.message)
                    }
                }
                NarLocalArchiveStager.Result.Cancelled -> {
                    if (supervisor.finish(handle, binding, OperationStatus.CANCELLED)) {
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
        if (isStopping(item)) return item
        if (item.state == NarDownloadState.Copying) return item
        runCatching { work.cancel(itemId) }
        when (val source = item.source) {
            is NarDownloadSource.Remote -> {
                val failedInstallAttempt = supervisor.isFailedAttempt(
                    item.handle(),
                    OperationKind.NAR_INSTALL,
                )
                val retryRetainedArchive = failedInstallAttempt && runCatching {
                    ownedData.retainedArchiveAvailable(item)
                }.getOrDefault(false)
                if (retryRetainedArchive) {
                    store.update(itemId) {
                        it.copy(
                            attemptId = it.attemptId + 1L,
                            workManagerId = null,
                            state = NarDownloadState.Queued,
                        )
                    }
                    scheduleInstall(itemId)
                } else {
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
                    startRemoteDownload(
                        itemId,
                        source.uri,
                        reacquiringAfterInstall = failedInstallAttempt,
                    )
                }
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
        if (isStopping(item)) return item
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
        if (isStopping(item)) return item
        if (!delete(itemId)) return null
        return enqueueLocalCopy(uri)
    }

    @Synchronized
    internal fun replaceLocalSourceForLiveCopy(itemId: String, uri: String): NarDownload? {
        val item = store.get(itemId) ?: return null
        if (item.source !is NarDownloadSource.Local) return null
        if (isStopping(item)) return item
        if (!delete(itemId)) return null
        return enqueueLiveLocalCopy(uri)
    }

    @Synchronized
    internal fun replaceLocalSourceForLiveCopyForUser(
        itemId: String,
        uri: String,
    ): NarUserEnqueueResult? {
        val previous = store.get(itemId) ?: return null
        return replaceLocalSourceForLiveCopy(itemId, uri)?.let { replacement ->
            userEnqueueResult(replacement, accepted = replacement.handle() != previous.handle())
        }
    }

    @Synchronized
    fun delete(itemId: String): Boolean {
        val item = store.get(itemId) ?: return false
        if (isStopping(item)) return false
        runCatching { work.cancel(itemId) }
        item.downloadManagerId?.let { runCatching { downloads.remove(it) } }
        runCatching { ownedData.delete(item) }
        store.delete(itemId)
        liveCopyAttempts -= item.handle()
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
        val binding = item.externalJobBinding()
        if (!supervisor.requestStop(handle)) return false
        if (item.state == NarDownloadState.Downloading) remoteProgress.stop(handle)
        if (binding == null) {
            if (supervisor.reconcileUnboundCancellation(handle)) {
                markCancelledIfCurrent(item)
            }
        } else {
            scheduleStopReconciliation(handle)
        }
        publish()
        return true
    }

    @Synchronized
    fun workerStopped(itemId: String, attemptId: Long, workManagerId: String) {
        val item = store.get(itemId) ?: return
        if (
            item.attemptId != attemptId ||
            item.workManagerId != workManagerId ||
            !item.state.isNonterminal() && item.state != NarDownloadState.Copying
        ) {
            return
        }
        if (!cancellationRequested(item.handle())) return
        if (
            !supervisor.finish(
                item.handle(),
                ExternalJobBinding.WorkManager(workManagerId),
                OperationStatus.CANCELLED,
            )
        ) {
            return
        }
        liveCopyAttempts -= item.handle()
        stopReconciliation.cancel(item.handle())
        store.update(itemId) { current ->
            if (current.attemptId == attemptId) current.copy(state = NarDownloadState.Cancelled)
            else current
        }
        publish()
    }

    @Synchronized
    fun reconcile() {
        store.getAll()
            .filter { it.state == NarDownloadState.Copying }
            .forEach { captured ->
                val item = if (captured.workManagerId == null) {
                    persistExactActiveWorkManagerBinding(captured, OperationKind.LOCAL_NAR)
                        ?: run {
                            if (store.get(captured.id) == captured) {
                                supervisor.failUnboundAttempt(captured.handle(), COPY_INTERRUPTED)
                                markNeedsAttentionIfCurrent(captured, COPY_INTERRUPTED)
                            }
                            return@forEach
                        }
                } else {
                    captured
                }
                val workManagerId = item.workManagerId
                val stopping = cancellationRequested(item.handle())
                val recovery = workManagerId?.let {
                    runCatching {
                        work.ensureStageEnqueued(
                            item.id,
                            item.attemptId,
                            workManagerId,
                            recreateIfMissing = !stopping,
                        )
                    }.getOrNull()
                }
                if (stopping) {
                    if (recovery == null) return@forEach
                    reconcileStoppingWork(item, OperationKind.LOCAL_NAR, recovery)
                } else if (recovery == null) {
                    markNeedsAttentionIfCurrent(item, COPY_INTERRUPTED)
                } else if (recovery != NarStageWorkRecovery.ACTIVE) {
                    failAndMarkNeedsAttentionIfCurrent(
                        item,
                        OperationKind.LOCAL_NAR,
                        COPY_INTERRUPTED,
                    )
                }
            }
        store.getAll()
            .filter { it.state == NarDownloadState.Complete }
            .forEach { item ->
                item.externalJobBinding()?.let { binding ->
                    supervisor.finish(item.handle(), binding, OperationStatus.COMPLETED)
                }
                cleanupCompletedInstall(item)
            }
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
                var statusQueryFailed = false
                val status = downloadManagerId?.let { id ->
                    try {
                        downloads.status(id)
                    } catch (_: Exception) {
                        statusQueryFailed = true
                        null
                    }
                }
                if (statusQueryFailed && cancellationRequested(item.handle())) {
                    return@forEach
                }
                when (status) {
                    NarRemoteDownloadStatus.InProgress -> {
                        store.update(item.id) { it.copy(state = NarDownloadState.Downloading) }
                        if (!cancellationRequested(item.handle())) {
                            remoteProgress.start(item.handle(), downloadManagerId)
                        }
                    }
                    is NarRemoteDownloadStatus.Successful -> {
                        store.update(item.id) {
                            it.copy(retainedUri = status.localUri ?: it.retainedUri)
                        }
                        val updated = store.get(item.id)
                        if (updated != null) advanceToInstall(updated)
                    }
                    NarRemoteDownloadStatus.Failed,
                    null -> {
                        remoteProgress.stop(item.handle())
                        if (cancellationRequested(item.handle()) && status == null) {
                            downloadManagerId?.let { bindingId ->
                                if (
                                    supervisor.finish(
                                        item.handle(),
                                        ExternalJobBinding.DownloadManager(bindingId),
                                        OperationStatus.CANCELLED,
                                    )
                                ) {
                                    markCancelledIfCurrent(item)
                                }
                            }
                        } else {
                            downloadManagerId?.let { bindingId ->
                                supervisor.finish(
                                    item.handle(),
                                    ExternalJobBinding.DownloadManager(bindingId),
                                    OperationStatus.FAILED,
                                    DOWNLOAD_RECOVERY_FAILURE,
                                )
                            }
                            markNeedsAttention(item.id, DOWNLOAD_RECOVERY_FAILURE)
                        }
                    }
                }
            }
        store.getAll()
            .filter {
                it.state == NarDownloadState.Queued ||
                    it.state == NarDownloadState.Installing
            }
            .forEach { item ->
                if (item.state == NarDownloadState.Queued && item.attemptId > 1L) {
                    val predecessor = OperationHandle(
                        OperationId(item.id),
                        AttemptId(item.attemptId - 1L),
                    )
                    val predecessorKind = when (item.source) {
                        is NarDownloadSource.Remote -> OperationKind.REMOTE_NAR
                        is NarDownloadSource.Local -> OperationKind.LOCAL_NAR
                    }
                    supervisor.activeBindingForExactAttempt(predecessor, predecessorKind)
                        ?.let { binding ->
                            supervisor.finish(predecessor, binding, OperationStatus.COMPLETED)
                        }
                }
                scheduleInstall(item.id)
            }
        store.getAll().filter(::isStopping).forEach { scheduleStopReconciliation(it.handle()) }
        publish()
    }

    fun install(itemId: String, workManagerId: String, isStopped: () -> Boolean): Boolean {
        val attemptId = store.get(itemId)?.attemptId ?: return true
        return install(itemId, attemptId, workManagerId, isStopped)
    }

    fun install(
        itemId: String,
        attemptId: Long,
        workManagerId: String,
        isStopped: () -> Boolean,
    ): Boolean {
        val item = store.get(itemId) ?: return true
        if (item.attemptId != attemptId || item.workManagerId != workManagerId) return true
        if (
            item.state is NarDownloadState.NeedsAttention ||
            item.state is NarDownloadState.Copying ||
            item.state is NarDownloadState.Complete
        ) return true
        if (item.state == NarDownloadState.Cancelled) return true
        val recoveringPublishedInstall = item.state is NarDownloadState.Installing
        val binding = ExternalJobBinding.WorkManager(workManagerId)
        val installing = store.update(itemId) {
            it.copy(state = NarDownloadState.Installing)
        } ?: return true
        publish()
        var stagingDirectory: File? = null
        val result = try {
            stagingDirectory = attemptPaths.create(itemId)
            installer.install(
                installing,
                stagingDirectory,
                { isStopped() || cancellationRequested(installing.handle()) },
            ) { phase, completed ->
                installProgress.report(installing.handle(), binding, phase, completed)
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
        installProgress.complete(installing.handle(), binding)
        when (result) {
            is ArchiveInstallResult.Installed -> {
                if (persistInstallComplete(itemId, installing.attemptId)) {
                    supervisor.finish(installing.handle(), binding, OperationStatus.COMPLETED)
                    cleanupCompletedInstall(item)
                }
            }
            is ArchiveInstallResult.Failed -> {
                if (recoveringPublishedInstall && result.failure is ArchiveInstallFailure.TargetExists) {
                    if (persistInstallComplete(itemId, installing.attemptId)) {
                        supervisor.finish(installing.handle(), binding, OperationStatus.COMPLETED)
                        cleanupCompletedInstall(item)
                    }
                } else {
                    if (isStopped() && !cancellationRequested(installing.handle())) {
                        publish()
                        return false
                    }
                    if (
                        !supervisor.finish(
                            installing.handle(),
                            binding,
                            OperationStatus.FAILED,
                            result.message,
                        )
                    ) {
                        publish()
                        return true
                    }
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
                if (!cancellationRequested(installing.handle())) {
                    publish()
                    return false
                }
                if (supervisor.finish(installing.handle(), binding, OperationStatus.CANCELLED)) {
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
        return true
    }

    private fun sourceUnavailable(item: NarDownload) = ArchiveInstallResult.Failed(
        if (item.source is NarDownloadSource.Local) RESELECT_SOURCE_FAILURE else REMOTE_SOURCE_FAILURE,
        ArchiveInstallFailure.SourceUnavailable,
    )

    private fun advanceToInstall(item: NarDownload): NarDownload? {
        remoteProgress.stop(item.handle())
        val installAttempt = store.update(item.id) { current ->
            if (
                current.attemptId == item.attemptId &&
                current.state == NarDownloadState.Downloading
            ) {
                current.copy(
                    attemptId = current.attemptId + 1L,
                    workManagerId = null,
                    state = NarDownloadState.Queued,
                )
            } else {
                current
            }
        }?.takeIf { it.attemptId == item.attemptId + 1L }
        if (installAttempt != null) {
            item.downloadManagerId?.let { downloadManagerId ->
                supervisor.finish(
                    item.handle(),
                    ExternalJobBinding.DownloadManager(downloadManagerId),
                    OperationStatus.COMPLETED,
                )
            }
        }
        return installAttempt
    }

    private fun persistInstallComplete(itemId: String, attemptId: Long): Boolean {
        val completed = store.update(itemId) { current ->
            if (current.attemptId == attemptId) current.copy(state = NarDownloadState.Complete)
            else current
        }
        return completed?.attemptId == attemptId && completed.state == NarDownloadState.Complete
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
        if (cancellationRequested(handle)) {
            val workManagerId = item.workManagerId ?: return
            val recovery = runCatching {
                work.ensureInstallEnqueued(
                    item.id,
                    item.attemptId,
                    workManagerId,
                    recreateIfMissing = false,
                )
            }.getOrNull() ?: return
            reconcileStoppingWork(item, OperationKind.NAR_INSTALL, recovery)
            return
        }
        val started = supervisor.start(
            handle,
            OperationKind.NAR_INSTALL,
            "Installing archive",
            0L,
        )
        if (!started) {
            val recovered = if (item.workManagerId == null) {
                persistExactActiveWorkManagerBinding(item, OperationKind.NAR_INSTALL)
            } else {
                item
            }
            val workManagerId = recovered?.workManagerId
            if (workManagerId == null) {
                if (store.get(item.id) != item) return
                enqueueInstallAttempt(item, handle)
            } else {
                val recovery = runCatching {
                    work.ensureInstallEnqueued(recovered.id, recovered.attemptId, workManagerId)
                }.getOrNull()
                if (recovery != NarInstallWorkRecovery.ACTIVE) {
                    failAndMarkNeedsAttentionIfCurrent(
                        recovered,
                        OperationKind.NAR_INSTALL,
                        INSTALL_SCHEDULE_FAILURE,
                    )
                }
            }
            return
        }
        enqueueInstallAttempt(item, handle)
    }

    private fun enqueueInstallAttempt(item: NarDownload, handle: OperationHandle) {
        var acceptedBinding: ExternalJobBinding.WorkManager? = null
        try {
            val enqueued = work.enqueue(item.id, item.attemptId) { workManagerId ->
                val binding = ExternalJobBinding.WorkManager(workManagerId)
                if (!supervisor.bindExternalJob(handle, binding)) return@enqueue false
                acceptedBinding = binding
                val updated = store.update(item.id) { current ->
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
            if (failSchedulingAttempt(handle, acceptedBinding, INSTALL_SCHEDULE_FAILURE)) {
                markNeedsAttention(item.id, INSTALL_SCHEDULE_FAILURE)
            }
        }
    }

    private fun persistExactActiveWorkManagerBinding(
        expected: NarDownload,
        kind: OperationKind,
    ): NarDownload? {
        val binding = supervisor.activeBindingForExactAttempt(expected.handle(), kind)
            as? ExternalJobBinding.WorkManager
            ?: return null
        var persisted: NarDownload? = null
        store.update(expected.id) { current ->
            if (current == expected) {
                current.copy(workManagerId = binding.uuid).also { persisted = it }
            } else {
                current
            }
        }
        return persisted
    }

    private fun failSchedulingAttempt(
        handle: OperationHandle,
        acceptedBinding: ExternalJobBinding.WorkManager?,
        diagnostics: String,
    ): Boolean = if (acceptedBinding == null) {
        supervisor.failUnboundAttempt(handle, diagnostics)
    } else {
        supervisor.finish(handle, acceptedBinding, OperationStatus.FAILED, diagnostics)
    }

    private fun failAndMarkNeedsAttentionIfCurrent(
        expected: NarDownload,
        kind: OperationKind,
        diagnostics: String,
    ): Boolean {
        if (store.get(expected.id) != expected) return false
        val workManagerId = expected.workManagerId ?: return false
        if (
            !supervisor.failOrConfirmExactAttempt(
                expected.handle(),
                kind,
                ExternalJobBinding.WorkManager(workManagerId),
                diagnostics,
            )
        ) {
            return false
        }
        return markNeedsAttentionIfCurrent(expected, diagnostics)
    }

    private fun markNeedsAttentionIfCurrent(
        expected: NarDownload,
        diagnostics: String,
    ): Boolean {
        var transitioned = false
        store.update(expected.id) { current ->
            if (current == expected) {
                transitioned = true
                current.copy(
                    state = NarDownloadState.NeedsAttention(
                        NarDownloadState.Failure(diagnostics),
                    ),
                )
            } else {
                current
            }
        }
        return transitioned
    }

    private fun markCancelledIfCurrent(expected: NarDownload): Boolean {
        var transitioned = false
        store.update(expected.id) { current ->
            if (current.attemptId == expected.attemptId) {
                transitioned = true
                current.copy(state = NarDownloadState.Cancelled)
            } else {
                current
            }
        }
        return transitioned
    }

    private fun scheduleStopReconciliation(handle: OperationHandle) {
        stopReconciliation.schedule(
            handle,
            STOP_RECONCILIATION_MILLIS,
            Runnable {
                synchronized(this) {
                    reconcile()
                    val current = store.get(handle.operationId.value)
                    if (current == null || current.attemptId != handle.attemptId.value || !isStopping(current)) {
                        stopReconciliation.cancel(handle)
                    }
                }
            },
        )
    }

    private fun reconcileStoppingWork(
        expected: NarDownload,
        kind: OperationKind,
        recovery: NarStageWorkRecovery,
    ) = reconcileStoppingWork(
        expected,
        kind,
        when (recovery) {
            NarStageWorkRecovery.ACTIVE -> ExactWorkRecovery.ACTIVE
            NarStageWorkRecovery.SUCCEEDED -> ExactWorkRecovery.SUCCEEDED
            NarStageWorkRecovery.FAILED -> ExactWorkRecovery.FAILED
            NarStageWorkRecovery.CANCELLED -> ExactWorkRecovery.CANCELLED
            NarStageWorkRecovery.MISSING -> ExactWorkRecovery.MISSING
        },
    )

    private fun reconcileStoppingWork(
        expected: NarDownload,
        kind: OperationKind,
        recovery: NarInstallWorkRecovery,
    ) = reconcileStoppingWork(
        expected,
        kind,
        when (recovery) {
            NarInstallWorkRecovery.ACTIVE -> ExactWorkRecovery.ACTIVE
            NarInstallWorkRecovery.SUCCEEDED -> ExactWorkRecovery.SUCCEEDED
            NarInstallWorkRecovery.FAILED -> ExactWorkRecovery.FAILED
            NarInstallWorkRecovery.CANCELLED -> ExactWorkRecovery.CANCELLED
            NarInstallWorkRecovery.MISSING -> ExactWorkRecovery.MISSING
        },
    )

    private fun reconcileStoppingWork(
        expected: NarDownload,
        kind: OperationKind,
        recovery: ExactWorkRecovery,
    ) {
        val binding = expected.workManagerId?.let(ExternalJobBinding::WorkManager) ?: return
        when (recovery) {
            ExactWorkRecovery.ACTIVE -> Unit
            ExactWorkRecovery.CANCELLED,
            ExactWorkRecovery.MISSING -> {
                if (supervisor.finish(expected.handle(), binding, OperationStatus.CANCELLED)) {
                    markCancelledIfCurrent(expected)
                }
            }
            ExactWorkRecovery.FAILED -> {
                if (
                    supervisor.finish(
                        expected.handle(),
                        binding,
                        OperationStatus.FAILED,
                        STOP_RECOVERY_FAILURE,
                    )
                ) {
                    markNeedsAttentionIfCurrent(expected, STOP_RECOVERY_FAILURE)
                }
            }
            ExactWorkRecovery.SUCCEEDED -> {
                // The worker's own exact-attempt callback owns any successful handoff.
                // A surviving queue row means the handoff was not durably published.
                if (
                    supervisor.finish(
                        expected.handle(),
                        binding,
                        OperationStatus.FAILED,
                        STOP_RECOVERY_FAILURE,
                    )
                ) {
                    markNeedsAttentionIfCurrent(expected, STOP_RECOVERY_FAILURE)
                }
            }
        }
    }

    private fun startRemoteDownload(
        itemId: String,
        url: String,
        reacquiringAfterInstall: Boolean = false,
    ) {
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
            val binding = ExternalJobBinding.DownloadManager(enqueued.downloadManagerId)
            val started = if (reacquiringAfterInstall) {
                supervisor.startRemoteNarReacquisition(
                    handle,
                    "Downloading archive",
                    0L,
                    binding,
                )
            } else {
                supervisor.start(
                    handle,
                    OperationKind.REMOTE_NAR,
                    "Downloading archive",
                    0L,
                    binding,
                )
            }
            if (!started) {
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

    @Synchronized
    private fun isLiveCopyAttemptActive(handle: OperationHandle): Boolean =
        handle in liveCopyAttempts

    private fun cancellationRequested(handle: OperationHandle): Boolean =
        supervisor.snapshot().any {
            it.id == handle.operationId &&
                it.attemptId == handle.attemptId &&
                it.status == OperationStatus.CANCEL_REQUESTED
        } || store.get(handle.operationId.value)?.let {
            it.attemptId == handle.attemptId.value && it.state == NarDownloadState.Cancelled
        } == true

    private fun isStopping(item: NarDownload): Boolean =
        supervisor.exactStatusForAttempt(item.handle(), item.operationKind()) ==
            OperationStatus.CANCEL_REQUESTED

    private fun NarDownload.operationKind() = when (state) {
        NarDownloadState.Copying -> OperationKind.LOCAL_NAR
        NarDownloadState.Downloading -> OperationKind.REMOTE_NAR
        else -> OperationKind.NAR_INSTALL
    }

    private fun NarDownload.handle() = OperationHandle(OperationId(id), AttemptId(attemptId))

    private fun NarDownload.externalJobBinding(): ExternalJobBinding? =
        workManagerId?.let(ExternalJobBinding::WorkManager)
            ?: downloadManagerId?.let(ExternalJobBinding::DownloadManager)

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
        private const val STOP_RECOVERY_FAILURE =
            "Nanidroid could not confirm that this archive operation stopped. Retry it."
        private const val STOP_RECONCILIATION_MILLIS = 500L

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
            val clock = MonotonicClock { android.os.SystemClock.elapsedRealtime() }
            val supervisor = SharedDurableOperationSupervisor.get(context)
            return NarDownloadRepository(
                store = NarDownloadStore(context),
                downloads = downloadGateway,
                work = workScheduler,
                installer = AndroidNarArchiveInstaller(context),
                ownedData = managedFiles,
                attemptPaths = managedFiles,
                supervisor = supervisor,
                installProgress = ThrottledNarInstallProgressReporter(supervisor, clock),
                remoteProgress = DownloadManagerProgressObserver(downloadGateway, supervisor),
                stopReconciliation = BackgroundStopReconciliationScheduler(),
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

private enum class ExactWorkRecovery {
    ACTIVE,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    MISSING,
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

    override fun retainedArchiveAvailable(download: NarDownload): Boolean =
        managedFile(download.retainedUri)?.isFile == true

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
