package com.cattailsw.nanidroid.durable

import com.cattailsw.nanidroid.di.MonotonicClock

class DurableOperationSupervisor(
    private val store: DurableOperationStore,
    private val clock: MonotonicClock,
    private val cancellation: OperationCancellation,
) {
    private val operationLock = Any()
    private val lastProgressAt = mutableMapOf<OperationHandle, Long>()
    private val cancellationIssued = mutableSetOf<BoundCancellation>()

    init {
        val now = clock.nowMillis()
        store.read().filter { it.status.isActive() }.forEach { restored ->
            val handle = restored.handle()
            lastProgressAt[handle] = now
            if (restored.showStallPrompt) {
                store.compareAndSet(
                    restored,
                    restored.copy(showStallPrompt = false),
                )
            }
            if (restored.status == OperationStatus.CANCEL_REQUESTED && restored.externalJob != null) {
                issueCancellation(handle, restored.externalJob)
            }
        }
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
    ): Boolean = synchronized(operationLock) {
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
                ?: return@synchronized false
            if (
                !previous.status.isTerminal() ||
                !previous.kind.canRetryAs(kind) && !(
                    allowRemoteNarReacquisition &&
                        previous.kind == OperationKind.NAR_INSTALL &&
                        kind == OperationKind.REMOTE_NAR
                    ) ||
                handle.attemptId.value <= previous.attemptId.value
            ) {
                return@synchronized false
            }
            val history = previous.externalJobHistory + listOfNotNull(previous.externalJob)
            if (externalJob != null && externalJob in history) return@synchronized false
            accepted = accepted.copy(externalJobHistory = history + listOfNotNull(externalJob))
            if (!store.compareAndSet(previous, accepted)) {
                return@synchronized false
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
        synchronized(operationLock) {
            val current = activeRecord(handle) ?: return@synchronized false
            if (current.status != OperationStatus.RUNNING) return@synchronized false
            if (current.externalJob != binding) return@synchronized false
            val changedPhase = phase != current.progress.phase
            val advanced = completed > current.progress.completed
            if (!changedPhase && !advanced) return@synchronized false
            val updated = current.copy(
                progress = OperationProgress(phase, completed),
                showStallPrompt = false,
            )
            if (!store.compareAndSet(current, updated)) {
                return@synchronized false
            }
            lastProgressAt[handle] = clock.nowMillis()
            true
        }

    fun bindExternalJob(handle: OperationHandle, binding: ExternalJobBinding): Boolean =
        synchronized(operationLock) {
            val current = activeRecord(handle) ?: return@synchronized false
            if (current.externalJob != null) return@synchronized current.externalJob == binding
            if (binding in current.externalJobHistory) return@synchronized false
            if (
                !store.compareAndSet(
                    current,
                    current.copy(
                        externalJob = binding,
                        externalJobHistory = current.externalJobHistory + binding,
                    ),
                )
            ) {
                return@synchronized false
            }
            if (current.status == OperationStatus.CANCEL_REQUESTED) {
                issueCancellation(handle, binding)
            }
            true
        }

    fun keepWaiting(handle: OperationHandle): Boolean = synchronized(operationLock) {
        val current = activeRecord(handle) ?: return@synchronized false
        if (!store.compareAndSet(current, current.copy(showStallPrompt = false))) {
            return@synchronized false
        }
        lastProgressAt[handle] = clock.nowMillis()
        true
    }

    fun requestStop(handle: OperationHandle): Boolean = synchronized(operationLock) {
        val current = activeRecord(handle) ?: return@synchronized false
        if (current.status == OperationStatus.CANCEL_REQUESTED) {
            current.externalJob?.let { issueCancellation(handle, it) }
            return@synchronized true
        }
        val updated = current.copy(
            progress = current.progress.copy(phase = STOPPING_PHASE),
            status = OperationStatus.CANCEL_REQUESTED,
            showStallPrompt = false,
        )
        if (!store.compareAndSet(current, updated)) {
            return@synchronized false
        }
        lastProgressAt[handle] = clock.nowMillis()
        current.externalJob?.let { issueCancellation(handle, it) }
        true
    }

    fun reconcileUnboundCancellation(handle: OperationHandle): Boolean = synchronized(operationLock) {
        val current = activeRecord(handle) ?: return@synchronized false
        if (current.status != OperationStatus.CANCEL_REQUESTED || current.externalJob != null) {
            return@synchronized false
        }
        if (
            !store.compareAndSet(
                current,
                current.copy(status = OperationStatus.CANCELLED, showStallPrompt = false),
            )
        ) {
            return@synchronized false
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
        synchronized(operationLock) {
            val current = activeRecord(handle) ?: return@synchronized false
            if (current.status != OperationStatus.RUNNING || current.externalJob != null) {
                return@synchronized false
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
                return@synchronized false
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
    ): Boolean = synchronized(operationLock) {
        val current = store.read().singleOrNull { it.id == handle.operationId }
            ?: return@synchronized false
        if (
            current.attemptId != handle.attemptId ||
            current.kind != kind ||
            current.externalJob != binding
        ) {
            return@synchronized false
        }
        if (current.status == OperationStatus.FAILED) {
            return@synchronized current.diagnostics == diagnostics
        }
        if (!current.status.isActive()) return@synchronized false
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
            return@synchronized false
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
    ): Boolean = synchronized(operationLock) {
        require(status.isTerminal()) { "finish requires a terminal status" }
        val current = activeRecord(handle) ?: return@synchronized false
        if (current.externalJob != binding) return@synchronized false
        val updated = current.copy(
            status = status,
            showStallPrompt = false,
            diagnostics = diagnostics,
        )
        if (!store.compareAndSet(current, updated)) return@synchronized false
        lastProgressAt.remove(handle)
        cancellationIssued.removeAll { it.handle == handle }
        true
    }

    fun snapshot(): List<DurableOperationRecord> = synchronized(operationLock) {
        val now = clock.nowMillis()
        store.read().filter { it.status.isActive() }.forEach { record ->
            val handle = record.handle()
            val observedAt = lastProgressAt.getOrPut(handle) { now }
            if (!record.showStallPrompt && now - observedAt >= STALL_MILLIS) {
                val diagnostics = if (
                    record.status == OperationStatus.CANCEL_REQUESTED && record.diagnostics == null
                ) {
                    STOPPING_DIAGNOSTIC
                } else {
                    record.diagnostics
                }
                store.compareAndSet(
                    record,
                    record.copy(showStallPrompt = true, diagnostics = diagnostics),
                )
            }
        }
        store.read()
            .filter { it.status.isActive() }
            .sortedWith(compareBy({ it.id.value }, { it.attemptId.value }))
    }

    private fun activeRecord(handle: OperationHandle): DurableOperationRecord? = store.read().singleOrNull {
        it.id == handle.operationId && it.attemptId == handle.attemptId && it.status.isActive()
    }

    private fun DurableOperationRecord.handle() = OperationHandle(id, attemptId)

    private fun issueCancellation(handle: OperationHandle, binding: ExternalJobBinding) {
        val request = BoundCancellation(handle, binding)
        if (request in cancellationIssued) return
        cancellation.cancel(handle, binding)
        cancellationIssued.add(request)
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
        const val STOPPING_PHASE = "Stopping..."
        const val STOPPING_DIAGNOSTIC = "Cancellation has not completed after 30 seconds."
    }

    private data class BoundCancellation(
        val handle: OperationHandle,
        val binding: ExternalJobBinding,
    )
}
