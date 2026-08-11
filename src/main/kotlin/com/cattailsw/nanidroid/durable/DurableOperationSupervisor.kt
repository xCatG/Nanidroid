package com.cattailsw.nanidroid.durable

import com.cattailsw.nanidroid.di.MonotonicClock

internal const val CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX = "Cancellation request failed"
internal const val STOPPING_DELAY_DIAGNOSTIC = "Cancellation has not completed after 30 seconds."

class DurableOperationSupervisor(
    private val store: DurableOperationStore,
    private val clock: MonotonicClock,
    private val cancellation: OperationCancellation,
) {
    private val operationLock = Any()
    private val lastProgressAt = mutableMapOf<OperationHandle, Long>()
    private val cancellationIssued = mutableSetOf<BoundCancellation>()
    private val revealedStoppingAttention = mutableSetOf<OperationHandle>()
    private val restartSuppressedAttention = mutableMapOf<OperationHandle, DurableOperationRecord>()
    @Volatile private var mutationListener: (() -> Unit)? = null

    init {
        val now = clock.nowMillis()
        store.read().filter { it.status.isActive() }.forEach { restored ->
            val handle = restored.handle()
            lastProgressAt[handle] = now
            if (restored.status == OperationStatus.CANCEL_REQUESTED && restored.externalJob != null) {
                issueCancellation(
                    handle,
                    restored.kind,
                    restored.externalJob,
                    preserveAttention = restored.showStallPrompt,
                )
            }
        }
        store.read()
            .filter { it.status.isActive() && it.showStallPrompt }
            .forEach { restored -> restartSuppressedAttention[restored.handle()] = restored }
    }

    internal fun setMutationListener(listener: (() -> Unit)?) {
        mutationListener = listener
        listener?.let { runCatching(it) }
    }

    fun start(
        handle: OperationHandle,
        kind: OperationKind,
        phase: String,
        completed: Long,
        externalJob: ExternalJobBinding? = null,
    ): Boolean = start(
        handle = handle,
        kind = kind,
        phase = phase,
        completed = completed,
        externalJob = externalJob,
        allowRemoteNarReacquisition = false,
    )

    fun startRemoteNarReacquisition(
        handle: OperationHandle,
        phase: String,
        completed: Long,
        externalJob: ExternalJobBinding.DownloadManager,
    ): Boolean = start(
        handle = handle,
        kind = OperationKind.REMOTE_NAR,
        phase = phase,
        completed = completed,
        externalJob = externalJob,
        allowRemoteNarReacquisition = true,
    )

    private fun start(
        handle: OperationHandle,
        kind: OperationKind,
        phase: String,
        completed: Long,
        externalJob: ExternalJobBinding?,
        allowRemoteNarReacquisition: Boolean,
    ): Boolean = mutate {
        var accepted = DurableOperationRecord(
            id = handle.operationId,
            attemptId = handle.attemptId,
            kind = kind,
            externalJob = externalJob,
            progress = OperationProgress(phase, completed),
            status = OperationStatus.RUNNING,
            showStallPrompt = false,
            externalJobHistory = externalJob?.let(::setOf).orEmpty(),
        )
        val inserted = store.putIfAbsent(accepted)
        if (!inserted) {
            val previous = store.read().singleOrNull { it.id == handle.operationId }
                ?: return@mutate false
            if (
                !previous.status.isTerminal() ||
                !previous.kind.canRetryAs(kind) && !(
                    allowRemoteNarReacquisition &&
                        previous.kind == OperationKind.NAR_INSTALL &&
                        kind == OperationKind.REMOTE_NAR
                    ) ||
                handle.attemptId.value <= previous.attemptId.value
            ) {
                return@mutate false
            }
            val history = previous.externalJobHistory + listOfNotNull(previous.externalJob)
            if (externalJob != null && externalJob in history) return@mutate false
            accepted = accepted.copy(externalJobHistory = history + listOfNotNull(externalJob))
            if (!store.compareAndSet(previous, accepted)) {
                return@mutate false
            }
            lastProgressAt.remove(previous.handle())
            cancellationIssued.removeAll { it.handle == previous.handle() }
        }
        lastProgressAt[handle] = clock.nowMillis()
        true
    }

    fun reportProgress(
        handle: OperationHandle,
        binding: ExternalJobBinding,
        phase: String,
        completed: Long,
    ): Boolean =
        mutate {
            val current = activeRecord(handle) ?: return@mutate false
            if (current.status != OperationStatus.RUNNING) return@mutate false
            if (current.externalJob != binding) return@mutate false
            val changedPhase = phase != current.progress.phase
            val advanced = completed > current.progress.completed
            if (!changedPhase && !advanced) return@mutate false
            val updated = current.copy(
                progress = OperationProgress(phase, completed),
                showStallPrompt = false,
            )
            if (!store.compareAndSet(current, updated)) {
                return@mutate false
            }
            lastProgressAt[handle] = clock.nowMillis()
            true
        }

    fun bindExternalJob(handle: OperationHandle, binding: ExternalJobBinding): Boolean =
        mutate {
            val current = activeRecord(handle) ?: return@mutate false
            if (current.externalJob != null) return@mutate current.externalJob == binding
            if (binding in current.externalJobHistory) return@mutate false
            if (
                !store.compareAndSet(
                    current,
                    current.copy(
                        externalJob = binding,
                        externalJobHistory = current.externalJobHistory + binding,
                    ),
                )
            ) {
                return@mutate false
            }
            if (current.status == OperationStatus.CANCEL_REQUESTED) {
                issueCancellation(handle, current.kind, binding)
            }
            true
        }

    /** Replaces an obsolete durable binding after recovery identifies the retained external job. */
    fun rebindExternalJob(
        handle: OperationHandle,
        expectedBinding: ExternalJobBinding,
        replacementBinding: ExternalJobBinding,
    ): Boolean = mutate {
        val current = activeRecord(handle) ?: return@mutate false
        if (current.externalJob != expectedBinding) return@mutate false
        if (expectedBinding == replacementBinding) return@mutate true
        if (replacementBinding in current.externalJobHistory) return@mutate false
        if (
            !store.compareAndSet(
                current,
                current.copy(
                    externalJob = replacementBinding,
                    externalJobHistory = current.externalJobHistory + replacementBinding,
                ),
            )
        ) {
            return@mutate false
        }
        if (current.status == OperationStatus.CANCEL_REQUESTED) {
            issueCancellation(handle, current.kind, replacementBinding)
        }
        true
    }

    fun keepWaiting(handle: OperationHandle): Boolean = mutate {
        val current = activeRecord(handle) ?: return@mutate false
        if (!store.compareAndSet(current, current.copy(showStallPrompt = false))) {
            return@mutate false
        }
        lastProgressAt[handle] = clock.nowMillis()
        true
    }

    fun requestStop(handle: OperationHandle): Boolean = mutate {
        val current = activeRecord(handle) ?: return@mutate false
        if (current.status == OperationStatus.CANCEL_REQUESTED) {
            current.externalJob?.let { issueCancellation(handle, current.kind, it) }
            return@mutate true
        }
        val updated = current.copy(
            progress = current.progress.copy(phase = STOPPING_PHASE),
            status = OperationStatus.CANCEL_REQUESTED,
            showStallPrompt = false,
        )
        if (!store.compareAndSet(current, updated)) {
            return@mutate false
        }
        lastProgressAt[handle] = clock.nowMillis()
        current.externalJob?.let { issueCancellation(handle, current.kind, it) }
        true
    }

    internal fun performAttentionAction(
        handle: OperationHandle,
        action: DurableAttentionAction,
    ): Boolean = mutate {
        val current = activeRecord(handle) ?: return@mutate false
        if (!current.showStallPrompt) return@mutate false
        when (action) {
            DurableAttentionAction.KEEP_WAITING -> {
                if (!store.compareAndSet(current, current.copy(showStallPrompt = false))) {
                    return@mutate false
                }
                restartSuppressedAttention.remove(handle)
                lastProgressAt[handle] = clock.nowMillis()
            }
            DurableAttentionAction.STOP -> {
                if (current.status != OperationStatus.RUNNING) return@mutate false
                val updated = current.copy(
                    progress = current.progress.copy(phase = STOPPING_PHASE),
                    status = OperationStatus.CANCEL_REQUESTED,
                    showStallPrompt = true,
                )
                if (!store.compareAndSet(current, updated)) return@mutate false
                restartSuppressedAttention.remove(handle)
                revealedStoppingAttention.remove(handle)
                lastProgressAt[handle] = clock.nowMillis()
                current.externalJob?.let {
                    issueCancellation(handle, current.kind, it, preserveAttention = true)
                }
            }
            DurableAttentionAction.RETRY_STOP -> {
                if (
                    current.status != OperationStatus.CANCEL_REQUESTED ||
                    !current.isCancellationDispatchFailure()
                ) {
                    return@mutate false
                }
                restartSuppressedAttention.remove(handle)
                revealedStoppingAttention.remove(handle)
                lastProgressAt[handle] = clock.nowMillis()
                current.externalJob?.let {
                    issueCancellation(handle, current.kind, it, preserveAttention = true)
                }
            }
        }
        true
    }

    fun reconcileUnboundCancellation(handle: OperationHandle): Boolean = mutate {
        val current = activeRecord(handle) ?: return@mutate false
        if (current.status != OperationStatus.CANCEL_REQUESTED || current.externalJob != null) {
            return@mutate false
        }
        if (
            !store.compareAndSet(
                current,
                current.copy(status = OperationStatus.CANCELLED, showStallPrompt = false),
            )
        ) {
            return@mutate false
        }
        lastProgressAt.remove(handle)
        cancellationIssued.removeAll { it.handle == handle }
        true
    }

    internal fun activeBindingForExactAttempt(
        handle: OperationHandle,
        kind: OperationKind,
    ): ExternalJobBinding? = synchronized(operationLock) {
        store.read().singleOrNull {
            it.id == handle.operationId &&
                it.attemptId == handle.attemptId &&
                it.kind == kind &&
                it.status.isActive()
        }?.externalJob
    }

    internal fun exactStatusForAttempt(
        handle: OperationHandle,
        kind: OperationKind,
        binding: ExternalJobBinding,
    ): OperationStatus? = synchronized(operationLock) {
        store.read().singleOrNull {
            it.id == handle.operationId &&
                it.attemptId == handle.attemptId &&
                it.kind == kind &&
                it.externalJob == binding
        }?.status
    }

    internal fun exactStatusForAttempt(
        handle: OperationHandle,
        kind: OperationKind,
    ): OperationStatus? = synchronized(operationLock) {
        store.read().singleOrNull {
            it.id == handle.operationId &&
                it.attemptId == handle.attemptId &&
                it.kind == kind
        }?.status
    }

    internal fun cancellationRequestedForExactAttempt(
        handle: OperationHandle,
        kind: OperationKind,
        binding: ExternalJobBinding,
    ): Boolean = synchronized(operationLock) {
        store.read().singleOrNull {
            it.id == handle.operationId &&
                it.attemptId == handle.attemptId &&
                it.kind == kind &&
                it.externalJob == binding
        }?.status == OperationStatus.CANCEL_REQUESTED
    }

    internal fun isFailedAttempt(
        handle: OperationHandle,
        kind: OperationKind,
    ): Boolean = synchronized(operationLock) {
        store.read().singleOrNull {
            it.id == handle.operationId &&
                it.attemptId == handle.attemptId &&
                it.kind == kind
        }?.status == OperationStatus.FAILED
    }

    fun failUnboundAttempt(handle: OperationHandle, diagnostics: String): Boolean =
        mutate {
            val current = activeRecord(handle) ?: return@mutate false
            if (current.status != OperationStatus.RUNNING || current.externalJob != null) {
                return@mutate false
            }
            if (
                !store.compareAndSet(
                    current,
                    current.copy(
                        status = OperationStatus.FAILED,
                        showStallPrompt = false,
                        diagnostics = diagnostics,
                    ),
                )
            ) {
                return@mutate false
            }
            lastProgressAt.remove(handle)
            cancellationIssued.removeAll { it.handle == handle }
            true
        }

    /**
     * Atomically terminalizes an active exact attempt, preserving a binding that
     * may have arrived while its caller was deciding whether one existed.
     */
    fun terminalizeExactAttempt(
        handle: OperationHandle,
        kind: OperationKind,
        diagnostics: String,
    ): Boolean = mutate {
        val current = store.read().singleOrNull {
            it.id == handle.operationId &&
                it.attemptId == handle.attemptId &&
                it.kind == kind &&
                it.status.isActive()
        } ?: return@mutate false
        val status = if (current.externalJob == null) OperationStatus.FAILED else OperationStatus.CANCELLED
        if (
            !store.compareAndSet(
                current,
                current.copy(
                    status = status,
                    showStallPrompt = false,
                    diagnostics = if (status == OperationStatus.FAILED) diagnostics else null,
                ),
            )
        ) {
            return@mutate false
        }
        lastProgressAt.remove(handle)
        cancellationIssued.removeAll { it.handle == handle }
        true
    }

    fun failOrConfirmExactAttempt(
        handle: OperationHandle,
        kind: OperationKind,
        binding: ExternalJobBinding,
        diagnostics: String,
    ): Boolean = mutate {
        val current = store.read().singleOrNull { it.id == handle.operationId }
            ?: return@mutate false
        if (
            current.attemptId != handle.attemptId ||
            current.kind != kind ||
            current.externalJob != binding
        ) {
            return@mutate false
        }
        if (current.status == OperationStatus.FAILED) {
            return@mutate current.diagnostics == diagnostics
        }
        if (!current.status.isActive()) return@mutate false
        if (
            !store.compareAndSet(
                current,
                current.copy(
                    status = OperationStatus.FAILED,
                    showStallPrompt = false,
                    diagnostics = diagnostics,
                ),
            )
        ) {
            return@mutate false
        }
        lastProgressAt.remove(handle)
        cancellationIssued.removeAll { it.handle == handle }
        true
    }

    fun finish(
        handle: OperationHandle,
        binding: ExternalJobBinding,
        status: OperationStatus,
        diagnostics: String? = null,
    ): Boolean = mutate {
        require(status.isTerminal()) { "finish requires a terminal status" }
        val current = activeRecord(handle) ?: return@mutate false
        if (current.externalJob != binding) return@mutate false
        val updated = current.copy(
            status = status,
            showStallPrompt = false,
            diagnostics = diagnostics,
        )
        if (!store.compareAndSet(current, updated)) return@mutate false
        lastProgressAt.remove(handle)
        cancellationIssued.removeAll { it.handle == handle }
        true
    }

    fun snapshot(): List<DurableOperationRecord> = attentionSnapshot().records

    internal fun attentionSnapshot(): DurableAttentionSnapshot = synchronized(operationLock) {
        val now = clock.nowMillis()
        var promptWriteFailed = false
        store.read().filter { it.status.isActive() }.forEach { record ->
            val handle = record.handle()
            val observedAt = lastProgressAt.getOrPut(handle) { now }
            if (
                record.isRestartSuppressed() &&
                now - observedAt >= STALL_MILLIS
            ) {
                restartSuppressedAttention.remove(handle)
            }
            if (record.attentionEscalationDue(now - observedAt, handle)) {
                if (record.showStallPrompt && record.isCancellationDispatchFailure()) {
                    revealedStoppingAttention += handle
                } else {
                    val diagnostics = if (
                        record.status == OperationStatus.CANCEL_REQUESTED &&
                        !record.isCancellationDispatchFailure()
                    ) {
                        STOPPING_DELAY_DIAGNOSTIC
                    } else {
                        record.diagnostics
                    }
                    val updated = runCatching {
                        store.compareAndSet(
                            record,
                            record.copy(showStallPrompt = true, diagnostics = diagnostics),
                        )
                    }.getOrDefault(false)
                    if (updated && record.status == OperationStatus.CANCEL_REQUESTED) {
                        revealedStoppingAttention += handle
                    }
                    promptWriteFailed = !updated || promptWriteFailed
                }
            }
        }
        val storedRecords = store.read()
            .filter { it.status.isActive() }
            .sortedWith(compareBy({ it.id.value }, { it.attemptId.value }))
        revealedStoppingAttention.retainAll(storedRecords.mapTo(mutableSetOf()) { it.handle() })
        restartSuppressedAttention.keys.retainAll(
            storedRecords.mapTo(mutableSetOf()) { it.handle() },
        )
        val nextDelay = storedRecords
            .filter {
                it.isRestartSuppressed() ||
                    !it.showStallPrompt ||
                    it.awaitingStoppingEscalation(it.handle())
            }
            .minOfOrNull { record ->
                val observedAt = lastProgressAt.getOrPut(record.handle()) { now }
                (STALL_MILLIS - (now - observedAt).coerceAtLeast(0L)).coerceAtLeast(0L)
            }
            ?.let { delay ->
                if (promptWriteFailed && delay == 0L) PROMPT_WRITE_RETRY_MILLIS else delay
            }
        val presentedRecords = storedRecords.map { record ->
            if (record.isRestartSuppressed()) {
                record.copy(showStallPrompt = false)
            } else if (
                record.showStallPrompt &&
                record.isCancellationDispatchFailure() &&
                record.handle() !in revealedStoppingAttention
            ) {
                record.copy(diagnostics = null)
            } else {
                record
            }
        }
        DurableAttentionSnapshot(
            records = presentedRecords,
            notificationRecords = storedRecords
                .filter(DurableOperationRecord::showStallPrompt)
                .map { record ->
                    if (
                        record.isRestartSuppressed() ||
                            (
                                record.isCancellationDispatchFailure() &&
                                    record.handle() !in revealedStoppingAttention
                            )
                    ) {
                        record.copy(diagnostics = null)
                    } else {
                        record
                    }
                },
            nextCheckDelayMillis = nextDelay,
        )
    }

    private fun DurableOperationRecord.isRestartSuppressed(): Boolean =
        restartSuppressedAttention[handle()] == this

    private fun DurableOperationRecord.attentionEscalationDue(
        elapsedMillis: Long,
        handle: OperationHandle,
    ): Boolean =
        elapsedMillis >= STALL_MILLIS &&
            (!showStallPrompt || awaitingStoppingEscalation(handle))

    private fun DurableOperationRecord.awaitingStoppingEscalation(handle: OperationHandle): Boolean =
        showStallPrompt &&
            status == OperationStatus.CANCEL_REQUESTED &&
            handle !in revealedStoppingAttention

    private fun activeRecord(handle: OperationHandle): DurableOperationRecord? = store.read().singleOrNull {
        it.id == handle.operationId && it.attemptId == handle.attemptId && it.status.isActive()
    }

    private fun DurableOperationRecord.handle() = OperationHandle(id, attemptId)

    private inline fun mutate(block: () -> Boolean): Boolean {
        val changed = synchronized(operationLock, block)
        if (changed) mutationListener?.let { runCatching(it) }
        return changed
    }

    private fun issueCancellation(
        handle: OperationHandle,
        kind: OperationKind,
        binding: ExternalJobBinding,
        preserveAttention: Boolean = false,
    ) {
        val exactBinding = try {
            repairMalformedWorkManagerBinding(handle, kind, binding)
        } catch (_: Exception) {
            storeCancellationFailure(handle, binding, preserveAttention)
            return
        } ?: return
        val request = BoundCancellation(handle, exactBinding)
        if (!cancellationIssued.add(request)) return
        try {
            cancellation.cancel(handle, kind, exactBinding)
            clearCancellationFailureDiagnostic(handle, exactBinding, preserveAttention)
            lastProgressAt[handle] = clock.nowMillis()
        } catch (error: Exception) {
            storeCancellationFailure(handle, exactBinding, preserveAttention)
            cancellationIssued.remove(request)
        }
    }

    private fun repairMalformedWorkManagerBinding(
        handle: OperationHandle,
        kind: OperationKind,
        binding: ExternalJobBinding,
    ): ExternalJobBinding? {
        if (binding !is ExternalJobBinding.WorkManager || canonicalUuidOrNull(binding.uuid) != null) {
            return binding
        }
        if (kind !in WORK_MANAGER_KINDS) return binding
        val current = activeRecord(handle) ?: return null
        if (current.kind != kind || current.externalJob != binding) return null
        val repaired = ExternalJobBinding.WorkManager(durableWorkManagerId(handle, kind).toString())
        val updated = current.copy(
            externalJob = repaired,
            externalJobHistory = current.externalJobHistory + binding + repaired,
        )
        return if (store.compareAndSet(current, updated)) repaired else null
    }

    private fun storeCancellationFailure(
        handle: OperationHandle,
        binding: ExternalJobBinding,
        preserveAttention: Boolean = false,
    ) {
        try {
            val current = activeRecord(handle) ?: return
            if (current.status != OperationStatus.CANCEL_REQUESTED || current.externalJob != binding) return
            val failure = CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX
            if (current.diagnostics == failure) return
            store.compareAndSet(
                current,
                current.copy(showStallPrompt = preserveAttention, diagnostics = failure),
            )
        } catch (_: Exception) {
        }
    }

    private fun clearCancellationFailureDiagnostic(
        handle: OperationHandle,
        binding: ExternalJobBinding,
        preserveAttention: Boolean = false,
    ) {
        val current = activeRecord(handle) ?: return
        if (
            current.status != OperationStatus.CANCEL_REQUESTED ||
            current.externalJob != binding ||
            current.diagnostics == null ||
            !current.isCancellationDispatchFailure()
        ) return
        store.compareAndSet(
            current,
            current.copy(showStallPrompt = preserveAttention, diagnostics = null),
        )
    }

    private fun OperationStatus.isActive() =
        this == OperationStatus.RUNNING || this == OperationStatus.CANCEL_REQUESTED

    private fun OperationStatus.isTerminal() =
        this == OperationStatus.COMPLETED ||
            this == OperationStatus.FAILED ||
            this == OperationStatus.CANCELLED

    private fun OperationKind.canRetryAs(next: OperationKind) =
        this == next ||
            next == OperationKind.NAR_INSTALL &&
            (this == OperationKind.REMOTE_NAR || this == OperationKind.LOCAL_NAR)

    private companion object {
        const val STALL_MILLIS = 30_000L
        const val PROMPT_WRITE_RETRY_MILLIS = 1_000L
        const val STOPPING_PHASE = "Stopping..."
        val WORK_MANAGER_KINDS = setOf(
            OperationKind.LOCAL_NAR,
            OperationKind.NAR_INSTALL,
            OperationKind.GHOST_UPDATE,
        )
    }

    private data class BoundCancellation(
        val handle: OperationHandle,
        val binding: ExternalJobBinding,
    )
}

internal fun DurableOperationRecord.isCancellationDispatchFailure(): Boolean =
    status == OperationStatus.CANCEL_REQUESTED &&
        diagnostics == CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX
