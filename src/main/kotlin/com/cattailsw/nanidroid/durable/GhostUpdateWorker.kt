package com.cattailsw.nanidroid.durable

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.cattailsw.nanidroid.SScriptRunner
import com.cattailsw.nanidroid.util.HttpStatusException
import com.cattailsw.nanidroid.util.NetworkUtil
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface GhostUpdateEventSink {
    fun send(name: String, references: List<String>)
}

internal class GhostBoundEventSink(
    private val expectedGhostId: String,
    private val dispatch: (expectedGhostId: String, name: String, references: List<String>) -> Boolean,
    private val onUndelivered: (name: String, references: List<String>) -> Unit = { _, _ -> },
) : GhostUpdateEventSink {
    override fun send(name: String, references: List<String>) {
        if (!dispatch(expectedGhostId, name, references)) onUndelivered(name, references)
    }
}

internal class ShioriGhostUpdateEvents(
    private val sink: GhostUpdateEventSink,
) : GhostUpdateEvents {
    override fun ready(files: List<String>) {
        sink.send("OnUpdateReady", listOf(files.lastIndex.coerceAtLeast(0).toString(), files.csv()))
    }

    override fun downloadBegin(file: String, index: Int, total: Int) {
        sink.send("OnUpdate.OnDownloadBegin", listOf(file, index.toString(), total.toString()))
    }

    override fun digestCompareBegin(file: String, expected: String, actual: String) {
        sink.send("OnUpdate.OnMD5CompareBegin", listOf(file, expected, actual))
    }

    override fun digestCompareComplete(file: String, expected: String, actual: String) {
        sink.send("OnUpdate.OnMD5CompareComplete", listOf(file, expected, actual))
    }

    override fun digestCompareFailure(file: String, expected: String, actual: String) {
        sink.send("OnUpdate.OnMD5CompareFailure", listOf(file, expected, actual))
    }

    override fun complete(files: List<String>) {
        sink.send("OnUpdateComplete", listOf("changed", files.csv()))
    }

    override fun noChanges() {
        sink.send("OnUpdateComplete", listOf("none", ""))
    }

    override fun failure(reason: String, files: List<String>) {
        sink.send("OnUpdateFailure", listOf(reason, files.csv()))
    }

    private fun List<String>.csv() = joinToString(",")
}

internal fun isRetryableGhostUpdateStatus(statusCode: Int): Boolean =
    statusCode == 408 || statusCode == 429 || statusCode in 500..599

internal class AndroidGhostUpdateNetwork(private val context: Context) : GhostUpdateNetwork {
    override fun open(baseUri: Uri, relativePath: String) = try {
        val builder = baseUri.buildUpon()
        relativePath.split('/').forEach(builder::appendPath)
        GhostUpdateOpenResult.Found(NetworkUtil.getURLStream(context, builder.build().toString()))
    } catch (error: HttpStatusException) {
        if (error.statusCode == 404) GhostUpdateOpenResult.NotFound
        else if (isRetryableGhostUpdateStatus(error.statusCode)) GhostUpdateOpenResult.RetryableFailure(error)
        else GhostUpdateOpenResult.NotFound
    } catch (error: IOException) {
        GhostUpdateOpenResult.RetryableFailure(error)
    }
}

internal class GhostUpdateRecoveryWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val storageRoot = inputData.getString(INPUT_STORAGE_ROOT)?.let(::File)
            ?: return Result.failure()
        val targetRoot = inputData.getString(INPUT_TARGET_ROOT)?.let(::File)
        if (targetRoot != null && !GhostUpdateWorker.journalExists(targetRoot)) {
            return when (GhostUpdateWorker.reconcileNoJournalAttempt(applicationContext, targetRoot)) {
                GhostUpdateWorker.Companion.NoJournalRecovery.RETRY -> Result.retry()
                GhostUpdateWorker.Companion.NoJournalRecovery.COMPLETE -> Result.success()
                GhostUpdateWorker.Companion.NoJournalRecovery.FAILED -> Result.failure()
            }
        }
        return when (GhostUpdateWorker.recoverBeforeGhostLoadWithWorkQuery(
            applicationContext,
            storageRoot,
            targetRoot,
        )) {
            is RecoveryResult.CommitPending,
            is RecoveryResult.PublishPending,
            is RecoveryResult.RollbackPending,
            -> Result.retry()
            is RecoveryResult.Failed -> Result.failure()
            RecoveryResult.NoJournal,
            RecoveryResult.RolledBack,
            RecoveryResult.CompletedCommit,
            -> Result.success()
        }
    }

    companion object {
        internal const val INPUT_STORAGE_ROOT = "ghost-update-recovery-storage-root"
        internal const val INPUT_TARGET_ROOT = "ghost-update-recovery-target-root"
    }
}

class GhostUpdateWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    private val workStarted = AtomicBoolean(false)
    private val handle: OperationHandle?
        get() {
            val operationId = inputData.getString(INPUT_OPERATION_ID) ?: return null
            val attemptId = inputData.getLong(INPUT_ATTEMPT_ID, NO_ATTEMPT)
            if (attemptId == NO_ATTEMPT) return null
            return OperationHandle(OperationId(operationId), AttemptId(attemptId))
        }

    override fun doWork(): Result {
        workStarted.set(true)
        val handle = handle ?: return Result.failure()
        val ghostId = inputData.getString(INPUT_GHOST_ID) ?: return Result.failure()
        val ghostRoot = inputData.getString(INPUT_GHOST_ROOT)?.let(::File) ?: return Result.failure()
        val baseUri = inputData.getString(INPUT_BASE_URI)?.let(Uri::parse) ?: return Result.failure()
        val binding = ExternalJobBinding.WorkManager(id.toString())
        val store = SharedPreferencesDurableOperationStore(applicationContext)
        val supervisor = supervisor(applicationContext)
        return execute(supervisor, handle, binding, { isStopped }) {
            val runner = SScriptRunner.getInstance(applicationContext)
            val events = ShioriGhostUpdateEvents(
                GhostBoundEventSink(
                    expectedGhostId = ghostId,
                    dispatch = { expected, event, references ->
                        runner.doShioriEventForGhost(expected, ghostRoot, event, references.toTypedArray())
                    },
                    onUndelivered = { event, references ->
                        if (event in TERMINAL_SHIORI_EVENTS) {
                            supervisor.deferTerminalEvent(
                                handle,
                                binding,
                                GhostUpdateTerminalEvent(
                                    ghostId,
                                    ghostRoot.canonicalFile.path,
                                    event,
                                    references,
                                ),
                            )
                        }
                    },
                ),
            )
            val repository = GhostUpdateRepository(
                network = AndroidGhostUpdateNetwork(applicationContext),
                events = events,
                onProgress = { phase, completed ->
                    supervisor.reportProgress(handle, binding, phase, completed)
                },
                onCommitClassified = {
                    supervisor.finish(handle, binding, OperationStatus.COMPLETED) ||
                        store.read().any { record ->
                            record.id == handle.operationId &&
                                record.kind == OperationKind.GHOST_UPDATE &&
                                record.attemptId == handle.attemptId &&
                                record.externalJob == binding &&
                                record.status == OperationStatus.COMPLETED
                        }
                },
                onRollbackClassified = { status ->
                    supervisor.finish(handle, binding, status) ||
                        store.read().any { record ->
                            record.id == handle.operationId &&
                                record.kind == OperationKind.GHOST_UPDATE &&
                                record.attemptId == handle.attemptId &&
                                record.externalJob == binding &&
                                record.status == status
                        }
                },
                commitGuard = object : GhostUpdateCommitGuard {
                    override fun commit(
                        ghostId: String,
                        ghostRoot: File,
                        onFailure: (Throwable) -> GhostUpdateResult,
                        action: () -> GhostUpdateResult,
                    ) = runner.withGhostUpdateCommitQuiesced(
                        ghostId, ghostRoot, onFailure = onFailure, action = action,
                    )

                    override fun commit(
                        ghostId: String,
                        ghostRoot: File,
                        onFailure: (Throwable) -> GhostUpdateResult,
                        shouldStop: () -> Boolean,
                        onStopped: () -> GhostUpdateResult,
                        action: () -> GhostUpdateResult,
                    ) = runner.withGhostUpdateCommitQuiesced(
                        ghostId, ghostRoot, onFailure, shouldStop, onStopped, action,
                    )
                },
                recoveryGuard = object : GhostUpdateRecoveryGuard {
                    override fun recover(
                        ghostId: String,
                        ghostRoot: File,
                        onFailure: (Throwable) -> RecoveryResult,
                        action: () -> RecoveryResult,
                    ) = SScriptRunner.withProductionGhostMutation(ghostId, ghostRoot, onFailure, action)

                    override fun recover(
                        ghostId: String,
                        ghostRoot: File,
                        onFailure: (Throwable) -> RecoveryResult,
                        shouldStop: () -> Boolean,
                        onStopped: () -> RecoveryResult,
                        action: () -> RecoveryResult,
                    ) = runner.withGhostUpdateCommitQuiesced(
                        ghostId, ghostRoot, onFailure, shouldStop, onStopped, action,
                    )
                },
            )
            repository.runInterruptible(
                GhostUpdateRequest(handle.operationId, ghostId, ghostRoot, baseUri, handle.attemptId, binding.uuid),
            ) { stopReason(supervisor, handle, binding) { isStopped } }
        }
    }

    override fun onStopped() {
        val handle = handle
        if (handle != null) {
            workerStopped(
                supervisor(applicationContext),
                handle,
                ExternalJobBinding.WorkManager(id.toString()),
                workStarted.get(),
            )
        }
        super.onStopped()
    }

    companion object {
        internal const val INPUT_OPERATION_ID = "ghost-update-operation-id"
        internal const val INPUT_ATTEMPT_ID = "ghost-update-attempt-id"
        internal const val INPUT_GHOST_ID = "ghost-update-ghost-id"
        internal const val INPUT_GHOST_ROOT = "ghost-update-ghost-root"
        internal const val INPUT_BASE_URI = "ghost-update-base-uri"
        private const val NO_ATTEMPT = -1L
        private const val WORK_QUERY_TIMEOUT_MILLIS = 1_000L
        private val TERMINAL_SHIORI_EVENTS = setOf("OnUpdateComplete", "OnUpdateFailure")

        internal fun deliverPendingTerminalEvent(
            supervisor: DurableOperationSupervisor,
            ghostId: String,
            ghostRoot: File,
            dispatch: (GhostUpdateTerminalEvent) -> Boolean,
        ): Boolean {
            val canonicalRoot = ghostRoot.canonicalFile
            val record = supervisor.records().singleOrNull {
                it.id == GhostUpdateRepository.canonicalOperationIdFor(canonicalRoot) &&
                    it.kind == OperationKind.GHOST_UPDATE &&
                    it.pendingGhostUpdateEvent?.ghostId == ghostId &&
                    it.pendingGhostUpdateEvent.canonicalRoot == canonicalRoot.path
            } ?: return false
            val event = record.pendingGhostUpdateEvent ?: return false
            val binding = record.externalJob as? ExternalJobBinding.WorkManager ?: return false
            if (!dispatch(event)) return false
            return supervisor.clearTerminalEvent(
                OperationHandle(record.id, record.attemptId),
                binding,
                event,
            )
        }

        internal fun deferRecoveredTerminalEvent(
            supervisor: DurableOperationSupervisor,
            journal: GhostUpdateJournal,
            status: OperationStatus,
        ) {
            val root = File(journal.ghostRoot).canonicalFile
            val ghostId = journal.ghostId ?: return
            val event = when (status) {
                OperationStatus.COMPLETED -> GhostUpdateTerminalEvent(
                    ghostId,
                    root.path,
                    "OnUpdateComplete",
                    listOf("changed", journal.files.joinToString(",")),
                )
                OperationStatus.FAILED -> GhostUpdateTerminalEvent(
                    ghostId,
                    root.path,
                    "OnUpdateFailure",
                    listOf("ghost update recovery failed", journal.files.joinToString(",")),
                )
                else -> return
            }
            val attempt = journal.attemptId ?: return
            val uuid = journal.workManagerUuid ?: return
            supervisor.deferTerminalEvent(
                OperationHandle(journal.operationId, attempt),
                ExternalJobBinding.WorkManager(uuid),
                event,
            )
        }

        internal fun execute(
            supervisor: DurableOperationSupervisor,
            handle: OperationHandle,
            binding: ExternalJobBinding.WorkManager,
            @Suppress("UNUSED_PARAMETER")
            isStopped: () -> Boolean,
            run: () -> GhostUpdateResult,
        ): ListenableWorker.Result {
            if (!supervisor.reportProgress(handle, binding, "Fetching update manifest", 0)) {
                when (supervisor.exactStatusForAttempt(handle, OperationKind.GHOST_UPDATE, binding)) {
                    OperationStatus.RUNNING -> Unit
                    OperationStatus.CANCEL_REQUESTED -> {
                        supervisor.finish(handle, binding, OperationStatus.CANCELLED)
                        return ListenableWorker.Result.success()
                    }
                    else -> return ListenableWorker.Result.success()
                }
            }
            val result = run()
            if (result is GhostUpdateResult.Interrupted) {
                if (supervisor.cancellationRequestedForExactAttempt(
                        handle,
                        OperationKind.GHOST_UPDATE,
                        binding,
                    )
                ) {
                    supervisor.finish(handle, binding, OperationStatus.CANCELLED)
                    return ListenableWorker.Result.success()
                }
                return ListenableWorker.Result.retry()
            }
            if (result is GhostUpdateResult.PublishPending) {
                return ListenableWorker.Result.retry()
            }
            if (result is GhostUpdateResult.RollbackPending) {
                return ListenableWorker.Result.failure()
            }
            val status = when (result) {
                is GhostUpdateResult.Completed -> OperationStatus.COMPLETED
                GhostUpdateResult.NoChanges -> OperationStatus.COMPLETED
                GhostUpdateResult.Cancelled -> OperationStatus.CANCELLED
                GhostUpdateResult.Interrupted -> error("interrupted result handled above")
                is GhostUpdateResult.PublishPending,
                is GhostUpdateResult.RollbackPending,
                -> error("classification pending result handled above")
                is GhostUpdateResult.Failed -> OperationStatus.FAILED
            }
            val diagnostic = (result as? GhostUpdateResult.Failed)?.diagnostic
            supervisor.finish(handle, binding, status, diagnostic)
            return if (result is GhostUpdateResult.Failed) {
                ListenableWorker.Result.failure()
            } else {
                ListenableWorker.Result.success()
            }
        }

        internal fun stopReason(
            supervisor: DurableOperationSupervisor,
            handle: OperationHandle,
            binding: ExternalJobBinding.WorkManager,
            isStopped: () -> Boolean,
        ): GhostUpdateStopReason = when {
            supervisor.cancellationRequestedForExactAttempt(
                handle,
                OperationKind.GHOST_UPDATE,
                binding,
            ) -> GhostUpdateStopReason.USER_CANCELLED
            isStopped() -> if (supervisor.cancellationRequestedForExactAttempt(
                    handle,
                    OperationKind.GHOST_UPDATE,
                    binding,
                )
            ) GhostUpdateStopReason.USER_CANCELLED else GhostUpdateStopReason.SYSTEM_INTERRUPTED
            else -> GhostUpdateStopReason.NONE
        }

        internal fun workerStopped(
            supervisor: DurableOperationSupervisor,
            handle: OperationHandle,
            binding: ExternalJobBinding.WorkManager,
            hasStarted: Boolean,
        ) {
            if (supervisor.activeBindingForExactAttempt(handle, OperationKind.GHOST_UPDATE) != binding) {
                return
            }
            if (!supervisor.cancellationRequestedForExactAttempt(
                    handle,
                    OperationKind.GHOST_UPDATE,
                    binding,
                )
            ) return
            if (!hasStarted) {
                supervisor.finish(handle, binding, OperationStatus.CANCELLED)
            }
        }

        internal fun enqueue(
            context: Context,
            baseUri: Uri,
            ghostId: String,
            ghostRoot: File,
        ): Boolean = synchronized(enqueueLock) {
            val canonicalRoot = ghostRoot.canonicalFile
            val storageRoot = canonicalRoot.parentFile ?: return@synchronized false
            val store = SharedPreferencesDurableOperationStore(context)
            val operationId = GhostUpdateRepository.canonicalOperationIdFor(canonicalRoot)
            val previous = readAttemptForEnqueue(store, operationId).getOrElse {
                return@synchronized false
            }
            if (previous != null && previous.status in setOf(OperationStatus.RUNNING, OperationStatus.CANCEL_REQUESTED)) {
                val previousBinding = previous.externalJob as? ExternalJobBinding.WorkManager
                    ?: return@synchronized false
                val journalExists = File(
                    GhostUpdateRepository.transactionRootFor(canonicalRoot, operationId),
                    GhostUpdateJournalStore.FILE_NAME,
                ).isFile
                var observedForRecovery: RecoveryWorkState? = null
                return@synchronized reconcileActiveAttempt(
                    previous.status,
                    previousBinding.uuid,
                    journalExists,
                    query = { uuid ->
                        val observed = observeRecoveryWork(
                            WorkManager.getInstance(context.applicationContext).getWorkInfoById(uuid),
                        )
                        observedForRecovery = observed
                        when (observed) {
                            RecoveryWorkState.MISSING -> ObservedWork.MISSING
                            RecoveryWorkState.ACTIVE -> ObservedWork.ACTIVE
                            RecoveryWorkState.SUCCEEDED -> ObservedWork.SUCCEEDED
                            RecoveryWorkState.FAILED -> ObservedWork.FAILED
                            RecoveryWorkState.CANCELLED -> ObservedWork.CANCELLED
                            RecoveryWorkState.QUERY_ERROR -> throw IOException("WorkManager query unavailable")
                        }
                    },
                    reenqueue = { previousUuid ->
                            val recoveredRequest = request(
                                previousUuid, operationId, previous.attemptId, ghostId, canonicalRoot, baseUri,
                            )
                            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                                workName(canonicalRoot), ExistingWorkPolicy.KEEP, recoveredRequest,
                            )
                    },
                    terminalize = { status, diagnostic ->
                        supervisor(context).finish(previous.handle(), previousBinding, status, diagnostic)
                    },
                    recoverJournal = {
                        canonicalRoot.parentFile?.let { storageRoot ->
                            recoverBeforeGhostLoad(
                                context,
                                storageRoot,
                                canonicalRoot,
                                observedForRecovery,
                            )
                        } ?: RecoveryResult.Failed("ghost root has no storage parent")
                    },
                )
            }
            val expectedJournal = File(
                GhostUpdateRepository.transactionRootFor(canonicalRoot, operationId),
                GhostUpdateJournalStore.FILE_NAME,
            ).isFile
            if (!reconcileBeforeAttemptRollover(previous?.status, expectedJournal) {
                    recoverBeforeGhostLoad(context, storageRoot, canonicalRoot)
                }
            ) return@synchronized false
            val attempt = AttemptId((previous?.attemptId?.value ?: 0L) + 1L)
            val handle = OperationHandle(operationId, attempt)
            val request = request(
                handle,
                ghostId,
                canonicalRoot,
                baseUri,
            )
            val binding = ExternalJobBinding.WorkManager(request.id.toString())
            val supervisor = supervisor(context)
            if (!supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding)) {
                return@synchronized false
            }
            return@synchronized try {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    workName(canonicalRoot),
                    ExistingWorkPolicy.KEEP,
                    request,
                )
                true
            } catch (e: RuntimeException) {
                supervisor.finish(handle, binding, OperationStatus.FAILED, e.message)
                false
            }
        }

        internal fun workName(ghostRoot: File): String =
            "ghost-update-${canonicalDigest(ghostRoot.canonicalPath).take(32)}"

        internal fun enqueueRecovery(
            context: Context,
            ghostStorageRoot: File,
            targetGhostRoot: File? = null,
        ) {
            val canonicalStorage = ghostStorageRoot.canonicalFile
            val canonicalTarget = targetGhostRoot?.canonicalFile
            val request = OneTimeWorkRequestBuilder<GhostUpdateRecoveryWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(GhostUpdateRecoveryWorker.INPUT_STORAGE_ROOT, canonicalStorage.path)
                        .apply {
                            if (canonicalTarget != null) {
                                putString(GhostUpdateRecoveryWorker.INPUT_TARGET_ROOT, canonicalTarget.path)
                            }
                        }
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                recoveryWorkName(canonicalStorage, canonicalTarget),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        internal fun recoveryWorkName(storageRoot: File, targetGhostRoot: File?): String {
            val identity = targetGhostRoot?.canonicalPath ?: storageRoot.canonicalPath
            return "ghost-update-recovery-${canonicalDigest(identity).take(32)}"
        }

        internal fun pendingAttemptRecoveryTargets(
            ghostStorageRoot: File,
            records: List<DurableOperationRecord>,
        ): Set<File> {
            val activeIds = records.asSequence()
                .filter {
                    it.kind == OperationKind.GHOST_UPDATE &&
                        it.status in setOf(OperationStatus.RUNNING, OperationStatus.CANCEL_REQUESTED) &&
                        it.externalJob is ExternalJobBinding.WorkManager
                }
                .map(DurableOperationRecord::id)
                .toSet()
            return ghostStorageRoot.canonicalFile.listFiles().orEmpty()
                .filter(File::isDirectory)
                .map(File::getCanonicalFile)
                .filter { canonicalOperationIdForTarget(it) in activeIds }
                .toSet()
        }

        internal fun pendingAttemptRecoveryTargets(
            context: Context,
            ghostStorageRoot: File,
        ): Set<File> = try {
            pendingAttemptRecoveryTargets(
                ghostStorageRoot,
                SharedPreferencesDurableOperationStore(context.applicationContext).read(),
            )
        } catch (_: DurableOperationStoreCorruptionException) {
            emptySet()
        }

        internal fun journalExists(targetGhostRoot: File): Boolean {
            val operationId = canonicalOperationIdForTarget(targetGhostRoot)
            return File(
                GhostUpdateRepository.transactionRootFor(targetGhostRoot, operationId),
                GhostUpdateJournalStore.FILE_NAME,
            ).isFile
        }

        internal fun reconcileNoJournalAttempt(
            context: Context,
            targetGhostRoot: File,
        ): NoJournalRecovery = try {
            val store = SharedPreferencesDurableOperationStore(context.applicationContext)
            reconcileNoJournalAttempt(
                store,
                supervisor(context),
                canonicalOperationIdForTarget(targetGhostRoot),
            ) { uuid ->
                observeRecoveryWork(
                    WorkManager.getInstance(context.applicationContext).getWorkInfoById(uuid),
                )
            }
        } catch (_: DurableOperationStoreCorruptionException) {
            NoJournalRecovery.FAILED
        }

        internal fun reconcileNoJournalAttempt(
            store: DurableOperationStore,
            durableSupervisor: DurableOperationSupervisor,
            operationId: OperationId,
            observe: (UUID) -> RecoveryWorkState,
        ): NoJournalRecovery {
            val record = try {
                store.read().singleOrNull {
                    it.id == operationId &&
                        it.kind == OperationKind.GHOST_UPDATE &&
                        it.status in setOf(OperationStatus.RUNNING, OperationStatus.CANCEL_REQUESTED)
                }
            } catch (_: DurableOperationStoreCorruptionException) {
                return NoJournalRecovery.FAILED
            } ?: return NoJournalRecovery.COMPLETE
            val binding = record.externalJob
            if (binding !is ExternalJobBinding.WorkManager) {
                val finished = if (binding == null) {
                    durableSupervisor.failUnboundAttempt(
                        record.handle(),
                        "ghost update has no recoverable WorkManager binding",
                    )
                } else {
                    durableSupervisor.finish(
                        record.handle(),
                        binding,
                        OperationStatus.FAILED,
                        "ghost update has a non-WorkManager binding",
                    )
                }
                return if (finished) NoJournalRecovery.COMPLETE else NoJournalRecovery.FAILED
            }
            val uuid = try {
                UUID.fromString(binding.uuid)
            } catch (_: IllegalArgumentException) {
                durableSupervisor.finish(
                    record.handle(),
                    binding,
                    OperationStatus.FAILED,
                    "invalid WorkManager binding",
                )
                return NoJournalRecovery.FAILED
            }
            val observed = observe(uuid)
            if (observed in setOf(RecoveryWorkState.ACTIVE, RecoveryWorkState.QUERY_ERROR)) {
                return NoJournalRecovery.RETRY
            }
            val terminal = when {
                record.status == OperationStatus.CANCEL_REQUESTED &&
                    observed in setOf(
                        RecoveryWorkState.MISSING,
                        RecoveryWorkState.CANCELLED,
                    ) -> OperationStatus.CANCELLED
                else -> OperationStatus.FAILED
            }
            val diagnostic = when {
                observed == RecoveryWorkState.MISSING && terminal == OperationStatus.FAILED ->
                    "missing ghost update request cannot be recreated without persisted source data"
                observed == RecoveryWorkState.SUCCEEDED ->
                    "worker succeeded without durable repository classification"
                terminal == OperationStatus.FAILED -> "worker failed without terminal result"
                else -> null
            }
            return if (durableSupervisor.finish(record.handle(), binding, terminal, diagnostic)) {
                NoJournalRecovery.COMPLETE
            } else {
                NoJournalRecovery.RETRY
            }
        }

        private fun canonicalOperationIdForTarget(targetGhostRoot: File): OperationId =
            GhostUpdateRepository.canonicalOperationIdFor(targetGhostRoot.canonicalFile)

        internal fun reconcileBeforeAttemptRollover(
            previousStatus: OperationStatus?,
            journalExists: Boolean,
            recover: () -> RecoveryResult,
        ): Boolean {
            if (!journalExists) return true
            if (previousStatus == null || previousStatus in setOf(
                    OperationStatus.RUNNING,
                    OperationStatus.CANCEL_REQUESTED,
                )
            ) return false
            return recover() in setOf(
                RecoveryResult.NoJournal,
                RecoveryResult.RolledBack,
                RecoveryResult.CompletedCommit,
            )
        }

        internal enum class ObservedWork { MISSING, ACTIVE, SUCCEEDED, FAILED, CANCELLED }

        internal enum class NoJournalRecovery { RETRY, COMPLETE, FAILED }

        internal enum class RecoveryWorkState {
            MISSING,
            ACTIVE,
            SUCCEEDED,
            FAILED,
            CANCELLED,
            QUERY_ERROR,
        }

        internal enum class RecoveryTransition {
            WAIT,
            ROLL_FORWARD_COMPLETED,
            ROLL_BACK_FAILED,
            ROLL_BACK_CANCELLED,
            FAIL_CLOSED,
        }

        internal fun recoveryTransition(
            phase: CommitPhase,
            topology: GhostTreeTopology,
            durableStatus: OperationStatus?,
            exactIdentity: Boolean,
            workState: RecoveryWorkState,
        ): RecoveryTransition {
            if (!exactIdentity || durableStatus == null) return RecoveryTransition.FAIL_CLOSED
            val validTopology = when (phase) {
                CommitPhase.PREPARED -> topology in setOf(
                    GhostTreeTopology.LIVE_CANDIDATE,
                    GhostTreeTopology.CANDIDATE_BACKUP,
                    GhostTreeTopology.LIVE_ONLY,
                )
                CommitPhase.BACKED_UP -> topology in setOf(
                    GhostTreeTopology.CANDIDATE_BACKUP,
                    GhostTreeTopology.LIVE_BACKUP,
                )
                CommitPhase.PUBLISHED -> topology in setOf(
                    GhostTreeTopology.LIVE_BACKUP,
                    GhostTreeTopology.LIVE_ONLY,
                )
                CommitPhase.CLEANED -> topology == GhostTreeTopology.LIVE_ONLY
                CommitPhase.ROLLBACK_CLASSIFIED -> topology in setOf(
                    GhostTreeTopology.LIVE_CANDIDATE,
                    GhostTreeTopology.CANDIDATE_BACKUP,
                    GhostTreeTopology.LIVE_BACKUP,
                    GhostTreeTopology.LIVE_ONLY,
                )
            }
            if (!validTopology) return RecoveryTransition.FAIL_CLOSED
            if (durableStatus in setOf(OperationStatus.RUNNING, OperationStatus.CANCEL_REQUESTED) &&
                phase in setOf(CommitPhase.CLEANED, CommitPhase.ROLLBACK_CLASSIFIED)
            ) return RecoveryTransition.FAIL_CLOSED
            return when (durableStatus) {
                OperationStatus.COMPLETED -> if (
                    phase == CommitPhase.PREPARED && topology == GhostTreeTopology.LIVE_CANDIDATE
                ) {
                    RecoveryTransition.FAIL_CLOSED
                } else {
                    RecoveryTransition.ROLL_FORWARD_COMPLETED
                }
                OperationStatus.FAILED -> if (
                    phase == CommitPhase.CLEANED ||
                    (phase == CommitPhase.PUBLISHED && topology == GhostTreeTopology.LIVE_ONLY)
                ) {
                    RecoveryTransition.FAIL_CLOSED
                } else {
                    RecoveryTransition.ROLL_BACK_FAILED
                }
                OperationStatus.CANCELLED -> if (
                    phase == CommitPhase.CLEANED ||
                    (phase == CommitPhase.PUBLISHED && topology == GhostTreeTopology.LIVE_ONLY)
                ) {
                    RecoveryTransition.FAIL_CLOSED
                } else {
                    RecoveryTransition.ROLL_BACK_CANCELLED
                }
                OperationStatus.RUNNING, OperationStatus.CANCEL_REQUESTED -> if (
                    topology == GhostTreeTopology.CANDIDATE_BACKUP ||
                    topology == GhostTreeTopology.LIVE_BACKUP ||
                    (phase == CommitPhase.PUBLISHED && topology == GhostTreeTopology.LIVE_ONLY)
                ) {
                    RecoveryTransition.ROLL_FORWARD_COMPLETED
                } else if (
                    durableStatus == OperationStatus.CANCEL_REQUESTED &&
                    workState == RecoveryWorkState.MISSING &&
                    phase == CommitPhase.PREPARED &&
                    topology in setOf(
                        GhostTreeTopology.LIVE_CANDIDATE,
                        GhostTreeTopology.LIVE_ONLY,
                    )
                ) {
                    RecoveryTransition.ROLL_BACK_CANCELLED
                } else when (workState) {
                    RecoveryWorkState.MISSING,
                    RecoveryWorkState.ACTIVE,
                    RecoveryWorkState.QUERY_ERROR,
                    -> RecoveryTransition.WAIT
                    RecoveryWorkState.SUCCEEDED,
                    RecoveryWorkState.FAILED,
                    RecoveryWorkState.CANCELLED,
                    -> when (phase) {
                        CommitPhase.PREPARED -> if (
                            durableStatus == OperationStatus.CANCEL_REQUESTED ||
                            workState == RecoveryWorkState.CANCELLED
                        ) {
                            RecoveryTransition.ROLL_BACK_CANCELLED
                        } else {
                            RecoveryTransition.ROLL_BACK_FAILED
                        }
                        CommitPhase.BACKED_UP,
                        CommitPhase.PUBLISHED,
                        -> RecoveryTransition.ROLL_FORWARD_COMPLETED
                        CommitPhase.CLEANED,
                        CommitPhase.ROLLBACK_CLASSIFIED,
                        -> RecoveryTransition.FAIL_CLOSED
                    }
                }
            }
        }

        internal fun recoverBeforeGhostLoad(
            context: Context,
            ghostStorageRoot: File,
            targetGhostRoot: File? = null,
            knownWorkState: RecoveryWorkState? = null,
        ): RecoveryResult = try {
            val store = SharedPreferencesDurableOperationStore(context.applicationContext)
            val durableSupervisor = supervisor(context)
            recoverBeforeGhostLoad(
                ghostStorageRoot,
                targetGhostRoot,
                store,
                queryWork = knownWorkState?.let { state -> { _ -> state } },
                finish = { journal, status ->
                    val attempt = journal.attemptId ?: return@recoverBeforeGhostLoad false
                    val uuid = journal.workManagerUuid ?: return@recoverBeforeGhostLoad false
                    durableSupervisor.finish(
                        OperationHandle(journal.operationId, attempt),
                        ExternalJobBinding.WorkManager(uuid),
                        status,
                    )
                },
                onClassified = { journal, status ->
                    deferRecoveredTerminalEvent(durableSupervisor, journal, status)
                },
            )
        } catch (e: DurableOperationStoreCorruptionException) {
            RecoveryResult.Failed(e.message ?: "corrupt durable operation store")
        }

        internal fun recoverBeforeGhostLoadWithWorkQuery(
            context: Context,
            ghostStorageRoot: File,
            targetGhostRoot: File? = null,
        ): RecoveryResult = try {
            val store = SharedPreferencesDurableOperationStore(context.applicationContext)
            val durableSupervisor = supervisor(context)
            recoverBeforeGhostLoad(
                ghostStorageRoot,
                targetGhostRoot,
                store,
                queryWork = { uuid ->
                    observeRecoveryWork(
                        WorkManager.getInstance(context.applicationContext).getWorkInfoById(uuid),
                    )
                },
                finish = finish@{ journal, status ->
                    val attempt = journal.attemptId ?: return@finish false
                    val uuid = journal.workManagerUuid ?: return@finish false
                    durableSupervisor.finish(
                        OperationHandle(journal.operationId, attempt),
                        ExternalJobBinding.WorkManager(uuid),
                        status,
                    )
                },
                onClassified = { journal, status ->
                    deferRecoveredTerminalEvent(durableSupervisor, journal, status)
                },
            )
        } catch (e: DurableOperationStoreCorruptionException) {
            RecoveryResult.Failed(e.message ?: "corrupt durable operation store")
        }

        internal fun recoverBeforeGhostLoad(
            ghostStorageRoot: File,
            targetGhostRoot: File?,
            store: DurableOperationStore,
            queryWork: ((UUID) -> RecoveryWorkState)?,
            finish: (GhostUpdateJournal, OperationStatus) -> Boolean,
            onClassified: (GhostUpdateJournal, OperationStatus) -> Unit = { _, _ -> },
        ): RecoveryResult = try {
            GhostUpdateRepository.recoverAllBeforeGhostLoad(
                ghostStorageRoot,
                targetGhostRoot,
                authorize = { journal, topology ->
                val attempt = journal.attemptId
                val uuidText = journal.workManagerUuid
                val uuid = try {
                    uuidText?.let(UUID::fromString)
                } catch (_: IllegalArgumentException) {
                    null
                }
                val record = if (attempt != null && uuid != null) {
                    store.read().singleOrNull {
                        it.id == journal.operationId &&
                            it.kind == OperationKind.GHOST_UPDATE &&
                            it.attemptId == attempt &&
                            it.externalJob == ExternalJobBinding.WorkManager(uuid.toString())
                    }
                } else {
                    null
                }
                val exact = record != null
                val withoutWork = recoveryTransition(
                    journal.phase,
                    topology,
                    record?.status,
                    exact,
                    RecoveryWorkState.QUERY_ERROR,
                )
                val workState = if (withoutWork != RecoveryTransition.WAIT) {
                    null
                } else if (uuid == null) {
                    RecoveryWorkState.QUERY_ERROR
                } else {
                    queryWork?.invoke(uuid) ?: RecoveryWorkState.QUERY_ERROR
                }
                val transition = workState?.let {
                    recoveryTransition(journal.phase, topology, record?.status, exact, it)
                } ?: withoutWork
                when (transition) {
                    RecoveryTransition.WAIT -> RecoveryAuthorization.WAIT
                    RecoveryTransition.FAIL_CLOSED -> RecoveryAuthorization.FAIL_CLOSED
                    RecoveryTransition.ROLL_FORWARD_COMPLETED -> RecoveryAuthorization.ROLL_FORWARD
                    RecoveryTransition.ROLL_BACK_FAILED -> RecoveryAuthorization.ROLL_BACK_FAILED
                    RecoveryTransition.ROLL_BACK_CANCELLED -> RecoveryAuthorization.ROLL_BACK_CANCELLED
                }
                },
                classify = { journal, status ->
                    val attempt = journal.attemptId ?: return@recoverAllBeforeGhostLoad false
                    val uuid = journal.workManagerUuid ?: return@recoverAllBeforeGhostLoad false
                    val binding = ExternalJobBinding.WorkManager(uuid)
                    val handle = OperationHandle(journal.operationId, attempt)
                    val record = store.read().singleOrNull {
                        it.id == journal.operationId &&
                            it.kind == OperationKind.GHOST_UPDATE &&
                            it.attemptId == attempt &&
                            it.externalJob == binding
                    } ?: return@recoverAllBeforeGhostLoad false
                    if (record.status == status || finish(journal, status)) {
                        onClassified(journal, status)
                        true
                    } else false
                },
            )
        } catch (e: DurableOperationStoreCorruptionException) {
            RecoveryResult.Failed(e.message ?: "corrupt durable operation store")
        }

        internal fun readAttemptForEnqueue(
            store: DurableOperationStore,
            operationId: OperationId,
        ): kotlin.Result<DurableOperationRecord?> = try {
            val record = store.read().singleOrNull { it.id == operationId }
            if (record != null && record.kind != OperationKind.GHOST_UPDATE) {
                kotlin.Result.failure(IllegalStateException("operation id belongs to another durable workflow"))
            } else {
                kotlin.Result.success(record)
            }
        } catch (e: DurableOperationStoreCorruptionException) {
            kotlin.Result.failure(e)
        }

        internal fun observeRecoveryWork(
            future: Future<WorkInfo?>,
            timeoutMillis: Long = WORK_QUERY_TIMEOUT_MILLIS,
        ): RecoveryWorkState = try {
            val info = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            when {
                info == null -> RecoveryWorkState.MISSING
                !info.state.isFinished -> RecoveryWorkState.ACTIVE
                info.state == WorkInfo.State.SUCCEEDED -> RecoveryWorkState.SUCCEEDED
                info.state == WorkInfo.State.CANCELLED -> RecoveryWorkState.CANCELLED
                else -> RecoveryWorkState.FAILED
            }
        } catch (_: Exception) {
            RecoveryWorkState.QUERY_ERROR
        }

        internal fun reconcileActiveAttempt(
            status: OperationStatus,
            bindingUuid: String,
            journalExists: Boolean,
            query: (UUID) -> ObservedWork,
            reenqueue: (UUID) -> Unit,
            terminalize: (OperationStatus, String?) -> Unit,
            recoverJournal: () -> RecoveryResult = { RecoveryResult.CommitPending(emptyList()) },
        ): Boolean {
            val uuid = try {
                UUID.fromString(bindingUuid)
            } catch (_: IllegalArgumentException) {
                terminalize(OperationStatus.FAILED, "invalid WorkManager binding")
                return false
            }
            val observed = try {
                query(uuid)
            } catch (_: Exception) {
                return false
            }
            return when (observed) {
                    ObservedWork.MISSING -> when {
                        status == OperationStatus.CANCEL_REQUESTED && !journalExists -> {
                            terminalize(OperationStatus.CANCELLED, null)
                            false
                        }
                        status == OperationStatus.CANCEL_REQUESTED -> {
                            recoverJournal()
                            false
                        }
                        else -> try {
                            reenqueue(uuid)
                            true
                        } catch (e: Exception) {
                            terminalize(OperationStatus.FAILED, e.message)
                            false
                        }
                    }
                    ObservedWork.CANCELLED -> {
                        if (journalExists) recoverJournal()
                        else terminalize(OperationStatus.CANCELLED, null)
                        false
                    }
                    ObservedWork.SUCCEEDED -> {
                        if (journalExists) recoverJournal()
                        else terminalize(
                            OperationStatus.FAILED,
                            "worker succeeded without durable repository classification",
                        )
                        false
                    }
                    ObservedWork.FAILED -> {
                        if (journalExists) recoverJournal()
                        else terminalize(OperationStatus.FAILED, "worker failed without terminal result")
                        false
                    }
                    ObservedWork.ACTIVE -> false
            }
        }

        private fun canonicalDigest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun request(
            id: UUID,
            operationId: OperationId,
            attempt: AttemptId,
            ghostId: String,
            ghostRoot: File,
            baseUri: Uri,
        ) = OneTimeWorkRequestBuilder<GhostUpdateWorker>()
            .setId(id)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setInputData(
                Data.Builder()
                    .putString(INPUT_OPERATION_ID, operationId.value)
                    .putLong(INPUT_ATTEMPT_ID, attempt.value)
                    .putString(INPUT_GHOST_ID, ghostId)
                    .putString(INPUT_GHOST_ROOT, ghostRoot.path)
                    .putString(INPUT_BASE_URI, baseUri.toString())
                    .build(),
            )
            .build()

        internal fun request(
            handle: OperationHandle,
            ghostId: String,
            ghostRoot: File,
            baseUri: Uri,
        ) = request(
            durableWorkManagerId(handle, OperationKind.GHOST_UPDATE),
            handle.operationId,
            handle.attemptId,
            ghostId,
            ghostRoot,
            baseUri,
        )

        private fun DurableOperationRecord.handle() = OperationHandle(id, attemptId)

        private val enqueueLock = Any()
        private fun supervisor(context: Context): DurableOperationSupervisor =
            SharedDurableOperationSupervisor.get(context)
    }
}
