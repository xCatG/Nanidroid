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
    private val lastObservedRevisions = mutableMapOf<OperationHandle, ObservationRevision>()
    private val cancellationIssued = mutableSetOf<BoundCancellation>()
    private val revealedStoppingAttention = mutableSetOf<OperationHandle>()
    private val restartSuppressedAttention = mutableMapOf<OperationHandle, DurableOperationRecord>()
    private val keepWaitingSuppressedAttention = mutableMapOf<OperationHandle, KeepWaitingSuppression>()
    @Volatile private var mutationListener: (() -> Unit)? = null

    init {
        val now = clock.nowMillis()
        store.read().filter { it.status.isActive() }.forEach { restored ->
            val handle = restored.handle()
            lastProgressAt[handle] = now
            lastObservedRevisions[handle] = restored.observationRevision()
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

    internal fun addStoreChangeListener(listener: () -> Unit): () -> Unit =
        store.addChangeListener(listener)

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

    fun startRemoteNarReacquisition(
        handle: OperationHandle,
        phase: String,
        completed: Long,
    ): Boolean = start(
        handle = handle,
        kind = OperationKind.REMOTE_NAR,
        phase = phase,
        completed = completed,
        externalJob = null,
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
                previous.pendingGhostUpdateEvent != null ||
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
            lastObservedRevisions.remove(previous.handle())
            cancellationIssued.removeAll { it.handle == previous.handle() }
        }
        lastProgressAt[handle] = clock.nowMillis()
        lastObservedRevisions[handle] = accepted.observationRevision()
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
                progressGeneration = current.progressGeneration + 1L,
            )
            if (!store.compareAndSet(current, updated)) {
                return@mutate false
            }
            lastProgressAt[handle] = clock.nowMillis()
            lastObservedRevisions[handle] = updated.observationRevision()
            true
        }

    fun bindExternalJob(handle: OperationHandle, binding: ExternalJobBinding): Boolean =
        mutate {
            val current = activeRecord(handle) ?: return@mutate false
            if (current.externalJob != null) return@mutate current.externalJob == binding
            if (binding in current.externalJobHistory) return@mutate false
            val updated = current.copy(
                externalJob = binding,
                externalJobHistory = current.externalJobHistory + binding,
                showStallPrompt = current.status != OperationStatus.RUNNING && current.showStallPrompt,
            )
            if (!store.compareAndSet(current, updated)) {
                return@mutate false
            }
            if (current.status == OperationStatus.RUNNING) {
                restartSuppressedAttention.remove(handle)
            }
            lastProgressAt[handle] = clock.nowMillis()
            lastObservedRevisions[handle] = updated.observationRevision()
            if (current.status == OperationStatus.CANCEL_REQUESTED) {
                issueCancellation(
                    handle,
                    current.kind,
                    binding,
                    preserveAttention = current.showStallPrompt,
                )
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
        if (current.pendingGhostUpdateEvent != null) return@mutate false
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
        val updated = current.copy(
            showStallPrompt = false,
            attentionKeepWaitingGeneration = current.attentionKeepWaitingGeneration + 1L,
        )
        if (!store.compareAndSet(current, updated)) {
            return@mutate false
        }
        val now = clock.nowMillis()
        lastProgressAt[handle] = now
        lastObservedRevisions[handle] = updated.observationRevision()
        keepWaitingSuppressedAttention[handle] = KeepWaitingSuppression(
            generation = updated.attentionKeepWaitingGeneration,
            expiresAt = now + STALL_MILLIS,
        )
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
        lastObservedRevisions[handle] = updated.observationRevision()
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
                val updated = current.copy(
                    showStallPrompt = false,
                    attentionKeepWaitingGeneration = current.attentionKeepWaitingGeneration + 1L,
                )
                if (!store.compareAndSet(current, updated)) {
                    return@mutate false
                }
                restartSuppressedAttention.remove(handle)
                val now = clock.nowMillis()
                lastProgressAt[handle] = now
                lastObservedRevisions[handle] = updated.observationRevision()
                keepWaitingSuppressedAttention[handle] = KeepWaitingSuppression(
                    generation = updated.attentionKeepWaitingGeneration,
                    expiresAt = now + STALL_MILLIS,
                )
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
                lastObservedRevisions[handle] = updated.observationRevision()
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
                val updated = current.copy(
                    attentionRetryGeneration = current.attentionRetryGeneration + 1L,
                )
                if (!store.compareAndSet(current, updated)) return@mutate false
                restartSuppressedAttention.remove(handle)
                revealedStoppingAttention.remove(handle)
                lastProgressAt[handle] = clock.nowMillis()
                lastObservedRevisions[handle] = updated.observationRevision()
                updated.externalJob?.let {
                    issueCancellation(handle, updated.kind, it, preserveAttention = true)
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
        lastObservedRevisions.remove(handle)
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

    internal fun bindingForExactAttempt(
        handle: OperationHandle,
        kind: OperationKind,
    ): ExternalJobBinding? = synchronized(operationLock) {
        store.read().singleOrNull {
            it.id == handle.operationId &&
                it.attemptId == handle.attemptId &&
                it.kind == kind
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

    internal fun wasExternalJobUsedBefore(
        handle: OperationHandle,
        binding: ExternalJobBinding,
    ): Boolean = synchronized(operationLock) {
        val records = store.read().filter { it.id == handle.operationId }
        val currentOrPredecessor = records.singleOrNull {
            it.id == handle.operationId && it.attemptId == handle.attemptId
        } ?: records
            .filter { it.attemptId.value < handle.attemptId.value }
            .maxByOrNull { it.attemptId.value }
        currentOrPredecessor?.externalJobHistory?.contains(binding) == true
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
            lastObservedRevisions.remove(handle)
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
        lastObservedRevisions.remove(handle)
        cancellationIssued.removeAll { it.handle == handle }
        true
    }

    internal fun failOrConfirmMissingUnboundAttempt(
        handle: OperationHandle,
        kind: OperationKind,
        diagnostics: String,
    ): Boolean = mutate {
        val current = store.read().singleOrNull {
            it.id == handle.operationId &&
                it.attemptId == handle.attemptId &&
                it.kind == kind
        }
        if (current == null) {
            val previous = store.read().singleOrNull { it.id == handle.operationId }
                ?: return@mutate true
            if (
                kind != OperationKind.REMOTE_NAR ||
                (previous.kind != OperationKind.NAR_INSTALL &&
                    previous.kind != OperationKind.REMOTE_NAR) ||
                previous.status !in setOf(OperationStatus.FAILED, OperationStatus.CANCELLED) ||
                handle.attemptId.value <= previous.attemptId.value
            ) {
                return@mutate false
            }
            val history = previous.externalJobHistory + listOfNotNull(previous.externalJob)
            val failedReacquisition = DurableOperationRecord(
                id = handle.operationId,
                attemptId = handle.attemptId,
                kind = OperationKind.REMOTE_NAR,
                externalJob = null,
                progress = OperationProgress("Downloading archive", 0L),
                status = OperationStatus.FAILED,
                showStallPrompt = false,
                diagnostics = diagnostics,
                externalJobHistory = history,
            )
            if (!store.compareAndSet(previous, failedReacquisition)) return@mutate false
            lastProgressAt.remove(previous.handle())
            lastObservedRevisions.remove(previous.handle())
            cancellationIssued.removeAll { it.handle == previous.handle() }
            return@mutate true
        }
        if (current.externalJob != null) return@mutate false
        if (current.status == OperationStatus.FAILED) return@mutate true
        if (current.status != OperationStatus.RUNNING) return@mutate false
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
        lastObservedRevisions.remove(handle)
        cancellationIssued.removeAll { it.handle == handle }
        true
    }

    internal fun isUnboundCancellationConfirmed(
        handle: OperationHandle,
        kind: OperationKind,
    ): Boolean = synchronized(operationLock) {
        store.read().singleOrNull {
            it.id == handle.operationId &&
                it.attemptId == handle.attemptId &&
                it.kind == kind &&
                it.externalJob == null
        }?.status == OperationStatus.CANCELLED
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
        lastObservedRevisions.remove(handle)
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
        lastObservedRevisions.remove(handle)
        cancellationIssued.removeAll { it.handle == handle }
        true
    }

    /** Atomically terminalizes an exact ghost-update attempt and retains its terminal payload. */
    fun finishWithTerminalEvent(
        handle: OperationHandle,
        binding: ExternalJobBinding.WorkManager,
        status: OperationStatus,
        event: GhostUpdateTerminalEvent,
        diagnostics: String? = null,
    ): Boolean = mutate {
        require(status.isTerminal()) { "finish requires a terminal status" }
        val current = exactRecord(handle) ?: return@mutate false
        if (current.kind != OperationKind.GHOST_UPDATE || current.externalJob != binding) return@mutate false
        if (current.status.isTerminal()) {
            return@mutate current.status == status && current.pendingGhostUpdateEvent == event
        }
        if (!store.compareAndSet(
                current,
                current.copy(
                    status = status,
                    showStallPrompt = false,
                    diagnostics = diagnostics,
                    pendingGhostUpdateEvent = event,
                ),
            )
        ) return@mutate false
        lastProgressAt.remove(handle)
        cancellationIssued.removeAll { it.handle == handle }
        true
    }

    fun deferTerminalEvent(
        handle: OperationHandle,
        binding: ExternalJobBinding.WorkManager,
        event: GhostUpdateTerminalEvent,
    ): Boolean = mutate {
        val current = exactRecord(handle) ?: return@mutate false
        if (current.kind != OperationKind.GHOST_UPDATE || current.externalJob != binding) return@mutate false
        store.compareAndSet(current, current.copy(pendingGhostUpdateEvent = event))
    }

    fun clearTerminalEvent(
        handle: OperationHandle,
        binding: ExternalJobBinding.WorkManager,
        event: GhostUpdateTerminalEvent,
    ): Boolean = mutate {
        val current = exactRecord(handle) ?: return@mutate false
        if (
            current.kind != OperationKind.GHOST_UPDATE ||
            current.externalJob != binding ||
            current.pendingGhostUpdateEvent != event
        ) return@mutate false
        store.compareAndSet(current, current.copy(pendingGhostUpdateEvent = null))
    }

    /**
     * Claims an exact terminal-event payload before dispatching it without the operation lock.
     * A later reconciliation clears only the same exact attempt and payload, so an intervening
     * retry remains intact.
     */
    internal fun deliverTerminalEvent(
        handle: OperationHandle,
        binding: ExternalJobBinding.WorkManager,
        event: GhostUpdateTerminalEvent,
        dispatch: (GhostUpdateTerminalEvent) -> Boolean,
    ): Boolean {
        val claimed = synchronized(operationLock) {
            val current = exactRecord(handle)
            current != null &&
                current.kind == OperationKind.GHOST_UPDATE &&
                current.externalJob == binding &&
                current.pendingGhostUpdateEvent == event
        }
        if (!claimed || !dispatch(event)) return false
        // A failed durable clear is retried after process restart, but never re-dispatches here.
        try {
            clearTerminalEvent(handle, binding, event)
        } catch (_: Exception) {
            // The terminal callback has already succeeded; preserve the retained payload for retry.
        }
        return true
    }

    /**
     * Runs [block] while holding the operation transition lock as the outermost lock.
     *
     * Terminal delivery ([deliverTerminalEvent]) only holds this lock while claiming or
     * reconciling its payload; the synchronous SHIORI callback runs after it is released. This
     * keeps attention actions responsive when ghost code stalls.
     */
    internal fun <T> withOperationLock(block: () -> T): T = synchronized(operationLock, block)

    fun snapshot(): List<DurableOperationRecord> = attentionSnapshot().records

    internal fun records(): List<DurableOperationRecord> = synchronized(operationLock) { store.read() }

    internal fun attentionSnapshot(): DurableAttentionSnapshot = synchronized(operationLock) {
        val now = clock.nowMillis()
        var promptWriteFailed = false
        val storedRecords = store.read()
            .filter { it.status.isActive() }
            .sortedWith(compareBy({ it.id.value }, { it.attemptId.value }))
        storedRecords.forEach { record ->
            reconcileAttentionObservation(record, now)
        }
        storedRecords.forEach { record ->
            var presentedRecord = record
            val handle = record.handle()
            val observedAt = lastProgressAt.getOrPut(handle) { now }
            if (
                record.isRestartSuppressed() &&
                now - observedAt >= STALL_MILLIS
            ) {
                restartSuppressedAttention.remove(handle)
            }
            val keepWaitingSuppressionExpired =
                record.isKeepWaitingSuppressed() &&
                    now >= keepWaitingSuppressedAttention.getValue(handle).expiresAt
            if (keepWaitingSuppressionExpired) {
                keepWaitingSuppressedAttention.remove(handle)
                // A same-generation cancellation failure can restore the prompt while Keep
                // waiting remains active. Its observation timestamp is intentionally reset,
                // but the user chose a fixed suppression deadline; once that deadline expires,
                // expose Retry stop immediately instead of sanitizing it for another window.
                if (record.showStallPrompt && record.isCancellationDispatchFailure()) {
                    revealedStoppingAttention += handle
                }
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
                    val published = record.copy(showStallPrompt = true, diagnostics = diagnostics)
                    val updated = runCatching {
                        store.compareAndSet(record, published)
                    }.getOrDefault(false)
                    if (updated) {
                        lastObservedRevisions[handle] = published.observationRevision()
                        presentedRecord = published
                        if (record.status == OperationStatus.CANCEL_REQUESTED) {
                            revealedStoppingAttention += handle
                        }
                    }
                    promptWriteFailed = !updated || promptWriteFailed
                }
            }
            presentedRecord
        }
        // Processing can write prompts, and another supervisor can independently advance or
        // finish an operation after the initial read. Present a fresh, linearizable view rather
        // than retaining an active or prompt record that was already superseded while this
        // snapshot was being prepared.
        val presentationRecords = store.read()
            .filter { it.status.isActive() }
            .sortedWith(compareBy({ it.id.value }, { it.attemptId.value }))
        presentationRecords.forEach { record ->
            reconcileAttentionObservation(record, now)
        }
        revealedStoppingAttention.retainAll(presentationRecords.mapTo(mutableSetOf()) { it.handle() })
        restartSuppressedAttention.keys.retainAll(
            presentationRecords.mapTo(mutableSetOf()) { it.handle() },
        )
        keepWaitingSuppressedAttention.keys.retainAll(
            presentationRecords.mapTo(mutableSetOf()) { it.handle() },
        )
        lastObservedRevisions.keys.retainAll(
            presentationRecords.mapTo(mutableSetOf()) { it.handle() },
        )
        val nextDelay = presentationRecords
            .minOfOrNull { record ->
                val observedAt = lastProgressAt.getOrPut(record.handle()) { now }
                if (
                    record.isRestartSuppressed() ||
                    record.isKeepWaitingSuppressed() ||
                    !record.showStallPrompt ||
                    record.awaitingStoppingEscalation(record.handle())
                ) {
                    var delay = (STALL_MILLIS - (now - observedAt).coerceAtLeast(0L)).coerceAtLeast(0L)
                    if (record.isKeepWaitingSuppressed()) {
                        // The suppression deadline is tracked independently of observedAt so
                        // that a same-generation write cannot push it out; wake up no later
                        // than that deadline even if observedAt was reset by such a write.
                        val expiresAt = keepWaitingSuppressedAttention.getValue(record.handle()).expiresAt
                        delay = minOf(delay, (expiresAt - now).coerceAtLeast(0L))
                    }
                    delay
                } else {
                    // Another supervisor can clear an already published prompt without waking us.
                    STALL_MILLIS
                }
            }
            ?.let { delay ->
                if (promptWriteFailed && delay == 0L) {
                    PROMPT_WRITE_RETRY_MILLIS
                } else if (promptWriteFailed) {
                    minOf(delay, PROMPT_WRITE_RETRY_MILLIS)
                } else {
                    delay
                }
            }
        val presentedRecords = presentationRecords.map { record ->
            if (record.isRestartSuppressed() || record.isKeepWaitingSuppressed()) {
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
            notificationRecords = presentationRecords
                .filter { record ->
                    record.showStallPrompt && !record.isKeepWaitingSuppressed()
                }
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

    private fun reconcileAttentionObservation(record: DurableOperationRecord, now: Long) {
        val handle = record.handle()
        val revision = record.observationRevision()
        val previousRevision = lastObservedRevisions.put(handle, revision)
        if (previousRevision == null || previousRevision == revision) return

        lastProgressAt[handle] = now
        if (
            !previousRevision.showStallPrompt &&
            record.showStallPrompt &&
            record.isCancellationDispatchFailure()
        ) {
            // A different supervisor can publish the already-due cancellation failure. Its
            // visible durable prompt proves Retry stop is actionable, rather than representing
            // fresh work that should sanitize the diagnostic again.
            revealedStoppingAttention += handle
        } else {
            revealedStoppingAttention.remove(handle)
        }
        if (
            record.attentionKeepWaitingGeneration > previousRevision.attentionKeepWaitingGeneration
        ) {
            // Only a genuine generation advance (re)installs suppression and its deadline. A
            // later same-generation write (for example an in-flight cancellation restoring the
            // prompt) must not push the deadline out past the original 30 second window.
            keepWaitingSuppressedAttention[handle] = KeepWaitingSuppression(
                generation = record.attentionKeepWaitingGeneration,
                expiresAt = now + STALL_MILLIS,
            )
        }
    }

    private fun DurableOperationRecord.isKeepWaitingSuppressed(): Boolean =
        keepWaitingSuppressedAttention[handle()]?.generation == attentionKeepWaitingGeneration

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

    private fun exactRecord(handle: OperationHandle): DurableOperationRecord? = store.read().singleOrNull {
        it.id == handle.operationId && it.attemptId == handle.attemptId
    }

    private fun DurableOperationRecord.handle() = OperationHandle(id, attemptId)

    private fun DurableOperationRecord.observationRevision() = ObservationRevision(
        progress = progress,
        status = status,
        externalJob = externalJob,
        showStallPrompt = showStallPrompt,
        attentionRetryGeneration = attentionRetryGeneration,
        attentionKeepWaitingGeneration = attentionKeepWaitingGeneration,
        progressGeneration = progressGeneration,
    )

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
            repairMalformedWorkManagerBinding(handle, kind, binding) ?: return
        } catch (_: Exception) {
            storeCancellationFailure(handle, binding, preserveAttention)
            return
        }
        val request = BoundCancellation(handle, exactBinding)
        if (!cancellationIssued.add(request)) return
        try {
            cancellation.cancel(handle, kind, exactBinding)
        } catch (_: Exception) {
            storeCancellationFailure(handle, exactBinding, preserveAttention)
            cancellationIssued.remove(request)
            return
        }
        // The external cancellation has been accepted at this point. A best-effort failure to
        // persist its observation generation must not relabel it as a dispatch failure or make
        // a later duplicate action issue the external cancellation again.
        runCatching {
            recordSuccessfulCancellationDispatch(handle, exactBinding, preserveAttention)
        }
        lastProgressAt[handle] = clock.nowMillis()
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
        // Another supervisor can advance an attention generation between our retry action and
        // this legacy-binding repair. Only record the repaired revision when this input is one
        // we already observed; otherwise the next reconciliation must see the concurrent
        // change and install its suppression window rather than absorbing it here.
        val purelyLocalRepair = lastObservedRevisions[handle] == current.observationRevision()
        val repaired = ExternalJobBinding.WorkManager(durableWorkManagerId(handle, kind).toString())
        val updated = current.copy(
            externalJob = repaired,
            externalJobHistory = current.externalJobHistory + binding + repaired,
        )
        return if (store.compareAndSet(current, updated)) {
            if (purelyLocalRepair) {
                recordObservationRevision(handle, updated)
            }
            repaired
        } else {
            null
        }
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
            val updated = current.copy(showStallPrompt = preserveAttention, diagnostics = failure)
            store.compareAndSet(
                current,
                updated,
            )
        } catch (_: Exception) {
        }
    }

    /** Records a cancellation failure outside [OperationCancellation] so a later retry reissues it. */
    internal fun recordCancellationDispatchFailure(
        handle: OperationHandle,
        binding: ExternalJobBinding,
    ) = synchronized(operationLock) {
        storeCancellationFailure(handle, binding)
        cancellationIssued.remove(BoundCancellation(handle, binding))
    }

    private fun recordSuccessfulCancellationDispatch(
        handle: OperationHandle,
        binding: ExternalJobBinding,
        preserveAttention: Boolean = false,
    ) {
        var current = activeRecord(handle) ?: return
        while (
            current.status == OperationStatus.CANCEL_REQUESTED &&
                current.externalJob == binding
        ) {
            val purelyLocalDispatch = lastObservedRevisions[handle] == current.observationRevision()
            // Advance an observable generation for every successful dispatch, including a
            // duplicate requestStop() when there is no prior failure diagnostic to clear.
            // On a CAS loss, rebuild from the concurrent winner so its prompt or Keep waiting
            // state survives while this successful dispatch is still observable.
            val clearsDispatchFailure = current.isCancellationDispatchFailure()
            val clearsDelayedStoppingAttention =
                !preserveAttention && current.diagnostics == STOPPING_DELAY_DIAGNOSTIC
            val updated = current.copy(
                showStallPrompt = when {
                    clearsDispatchFailure -> preserveAttention
                    clearsDelayedStoppingAttention -> false
                    else -> current.showStallPrompt
                },
                diagnostics = if (clearsDispatchFailure || clearsDelayedStoppingAttention) {
                    null
                } else {
                    current.diagnostics
                },
                attentionRetryGeneration = current.attentionRetryGeneration + 1L,
            )
            if (store.compareAndSet(current, updated)) {
                // Record this locally produced mutation immediately so a later, possibly delayed,
                // poll doesn't mistake it for a concurrent mutation and push lastProgressAt out
                // to reconciliation time instead of this write's own time.
                if (purelyLocalDispatch) {
                    recordObservationRevision(handle, updated)
                }
                return
            }
            current = activeRecord(handle) ?: return
        }
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

    private data class KeepWaitingSuppression(
        val generation: Long,
        val expiresAt: Long,
    )

    private data class ObservationRevision(
        val progress: OperationProgress,
        val status: OperationStatus,
        val externalJob: ExternalJobBinding?,
        val showStallPrompt: Boolean,
        val attentionRetryGeneration: Long,
        val attentionKeepWaitingGeneration: Long,
        val progressGeneration: Long,
    )

    private fun recordObservationRevision(handle: OperationHandle) {
        val current = activeRecord(handle) ?: return
        lastObservedRevisions[handle] = current.observationRevision()
    }

    private fun recordObservationRevision(
        handle: OperationHandle,
        record: DurableOperationRecord,
    ) {
        lastObservedRevisions[handle] = record.observationRevision()
    }
}

internal fun DurableOperationRecord.isCancellationDispatchFailure(): Boolean =
    status == OperationStatus.CANCEL_REQUESTED &&
        diagnostics == CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX
