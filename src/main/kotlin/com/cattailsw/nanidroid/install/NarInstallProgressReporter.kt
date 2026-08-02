package com.cattailsw.nanidroid.install

import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.durable.DurableOperationSupervisor
import com.cattailsw.nanidroid.durable.OperationHandle

internal class ThrottledNarInstallProgressReporter(
    private val supervisor: DurableOperationSupervisor,
    private val clock: MonotonicClock,
) {
    private val progress = mutableMapOf<OperationHandle, ProgressState>()

    @Synchronized
    fun report(handle: OperationHandle, phase: String, completed: Long): Boolean {
        if (completed < 0L) return false
        val previous = progress[handle]
        if (
            previous != null &&
            previous.observedPhase == phase &&
            completed <= previous.observedCompleted
        ) {
            return false
        }
        val now = clock.nowMillis()
        val observed = if (previous == null) {
            ProgressState(
                observedPhase = phase,
                observedCompleted = completed,
            )
        } else {
            previous.copy(
                observedPhase = phase,
                observedCompleted = completed,
            )
        }
        progress[handle] = observed
        val phaseChanged = observed.persistedPhase != phase
        val bytesAdvanced = if (phaseChanged) 0L else completed - observed.persistedCompleted
        val heartbeatDue = observed.persistedAtMillis?.let { now - it >= HEARTBEAT_MILLIS } ?: true
        if (!phaseChanged && bytesAdvanced < BYTE_THRESHOLD && !heartbeatDue) return false
        if (!supervisor.reportProgress(handle, phase, completed)) return false
        progress[handle] = observed.copy(
            persistedPhase = phase,
            persistedCompleted = completed,
            persistedAtMillis = now,
        )
        return true
    }

    @Synchronized
    fun complete(handle: OperationHandle): Boolean {
        val final = progress.remove(handle) ?: return false
        if (
            final.observedPhase == final.persistedPhase &&
            final.observedCompleted == final.persistedCompleted
        ) {
            return false
        }
        return supervisor.reportProgress(
            handle,
            final.observedPhase,
            final.observedCompleted,
        )
    }

    private data class ProgressState(
        val observedPhase: String,
        val observedCompleted: Long,
        val persistedPhase: String? = null,
        val persistedCompleted: Long = 0L,
        val persistedAtMillis: Long? = null,
    )

    private companion object {
        const val BYTE_THRESHOLD = 1024L * 1024L
        const val HEARTBEAT_MILLIS = 20_000L
    }
}
