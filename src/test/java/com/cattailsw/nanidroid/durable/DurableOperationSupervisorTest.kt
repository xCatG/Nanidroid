package com.cattailsw.nanidroid.durable

import com.cattailsw.nanidroid.di.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableOperationSupervisorTest {
    private val clock = FakeMonotonicClock()
    private val store = MemoryDurableOperationStore()
    private val cancellation = RecordingCancellation()
    private val supervisor = DurableOperationSupervisor(store, clock, cancellation)

    @Test fun promptsAt30000WithoutCancelling() {
        supervisor.start(handle("nar-1", 1), OperationKind.NAR_INSTALL, "Extracting", 8)

        clock.value = 29_999
        assertFalse(supervisor.snapshot().single().showStallPrompt)
        clock.value = 30_000

        assertTrue(supervisor.snapshot().single().showStallPrompt)
        assertEquals(OperationStatus.RUNNING, supervisor.snapshot().single().status)
        assertTrue(cancellation.handles.isEmpty())
    }

    @Test fun onlyRealProgressResetsTheObservationWindow() {
        val handle = handle("update-1", 1)
        supervisor.start(handle, OperationKind.GHOST_UPDATE, "Downloading", 8)
        clock.value = 20_000
        assertFalse(supervisor.reportProgress(handle, "Downloading", 8))
        clock.value = 30_000
        assertTrue(supervisor.snapshot().single().showStallPrompt)

        assertTrue(supervisor.keepWaiting(handle))
        clock.value = 50_000
        assertTrue(supervisor.reportProgress(handle, "Downloading", 9))
        clock.value = 79_999
        assertFalse(supervisor.snapshot().single().showStallPrompt)
        clock.value = 80_000
        assertTrue(supervisor.snapshot().single().showStallPrompt)
    }

    @Test fun phaseChangeCountsAsProgressButARegressingCountDoesNot() {
        val handle = handle("install-1", 1)
        supervisor.start(handle, OperationKind.NAR_INSTALL, "Extracting", 8)
        clock.value = 20_000
        assertTrue(supervisor.reportProgress(handle, "Verifying", 0))
        clock.value = 40_000
        assertFalse(supervisor.reportProgress(handle, "Verifying", 0))
        assertFalse(supervisor.reportProgress(handle, "Verifying", -1))
        clock.value = 49_999
        assertFalse(supervisor.snapshot().single().showStallPrompt)
        clock.value = 50_000
        assertTrue(supervisor.snapshot().single().showStallPrompt)
    }

    @Test fun keepWaitingStartsANewWindowWithoutRestartingOrCancelling() {
        val handle = handle("copy-1", 1)
        supervisor.start(handle, OperationKind.LOCAL_NAR, "Copying", 0)
        clock.value = 30_000
        assertTrue(supervisor.snapshot().single().showStallPrompt)

        assertTrue(supervisor.keepWaiting(handle))
        assertFalse(supervisor.snapshot().single().showStallPrompt)
        clock.value = 59_999
        assertFalse(supervisor.snapshot().single().showStallPrompt)
        clock.value = 60_000
        assertTrue(supervisor.snapshot().single().showStallPrompt)
        assertTrue(cancellation.handles.isEmpty())
    }

    @Test fun stopIsIdempotentAndOperationSpecific() {
        val selected = handle("nar-1", 1)
        val other = handle("nar-2", 1)
        supervisor.start(selected, OperationKind.REMOTE_NAR, "Downloading", 1)
        supervisor.start(other, OperationKind.REMOTE_NAR, "Downloading", 2)

        assertTrue(supervisor.requestStop(selected))
        assertTrue(supervisor.requestStop(selected))

        val records = supervisor.snapshot().associateBy { it.id }
        assertEquals(OperationStatus.CANCEL_REQUESTED, records.getValue(selected.operationId).status)
        assertEquals("Stopping...", records.getValue(selected.operationId).progress.phase)
        assertEquals(OperationStatus.RUNNING, records.getValue(other.operationId).status)
        assertEquals(listOf(selected), cancellation.handles)
    }

    @Test fun recreationStartsAFreshWindowForRunningWork() {
        val handle = handle("nar-1", 1)
        supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading", 1)
        clock.value = 29_000

        val restored = DurableOperationSupervisor(store, clock, cancellation)
        clock.value = 58_999
        assertFalse(restored.snapshot().single().showStallPrompt)
        clock.value = 59_000
        assertTrue(restored.snapshot().single().showStallPrompt)
    }

    @Test fun recreationImmediatelyResumesPersistedCancellation() {
        val handle = handle("update-1", 1)
        supervisor.start(handle, OperationKind.GHOST_UPDATE, "Committing", 0)
        supervisor.requestStop(handle)
        cancellation.handles.clear()

        DurableOperationSupervisor(store, clock, cancellation)

        assertEquals(listOf(handle), cancellation.handles)
        assertEquals(OperationStatus.CANCEL_REQUESTED, store.read().single().status)
    }

    @Test fun stoppingGetsASecondObservationWindowAndDiagnostics() {
        val handle = handle("update-1", 1)
        supervisor.start(handle, OperationKind.GHOST_UPDATE, "Committing", 0)
        clock.value = 30_000
        supervisor.snapshot()

        supervisor.requestStop(handle)
        clock.value = 59_999
        assertFalse(supervisor.snapshot().single().showStallPrompt)
        clock.value = 60_000

        val stopping = supervisor.snapshot().single()
        assertEquals(OperationStatus.CANCEL_REQUESTED, stopping.status)
        assertTrue(stopping.showStallPrompt)
        assertNotNull(stopping.diagnostics)
        assertEquals(listOf(handle), cancellation.handles)
    }

    @Test fun terminalCallbackIsPersistedAndCleanedFromActiveSnapshot() {
        val handle = handle("copy-1", 1)
        supervisor.start(handle, OperationKind.LOCAL_NAR, "Copying", 4)

        assertTrue(supervisor.finish(handle, OperationStatus.COMPLETED))

        assertTrue(supervisor.snapshot().isEmpty())
        assertEquals(OperationStatus.COMPLETED, store.read().single().status)
        assertFalse(supervisor.finish(handle, OperationStatus.COMPLETED))
    }

    @Test fun duplicateAcceptanceIsRejectedAndHigherAttemptReplacesOnlyTerminalWork() {
        val first = handle("nar-1", 1)
        val retry = handle("nar-1", 2)
        assertTrue(supervisor.start(first, OperationKind.REMOTE_NAR, "Downloading", 0))
        assertFalse(supervisor.start(first, OperationKind.REMOTE_NAR, "Downloading", 0))
        assertFalse(supervisor.start(retry, OperationKind.REMOTE_NAR, "Downloading", 0))

        assertTrue(supervisor.finish(first, OperationStatus.CANCELLED))
        assertTrue(
            supervisor.start(
                retry,
                OperationKind.REMOTE_NAR,
                "Downloading",
                0,
                ExternalJobBinding.DownloadManager(202),
            ),
        )
        assertFalse(supervisor.finish(first, OperationStatus.COMPLETED))

        val current = supervisor.snapshot().single()
        assertEquals(AttemptId(2), current.attemptId)
        assertEquals(ExternalJobBinding.DownloadManager(202), current.externalJob)
        assertEquals(OperationStatus.RUNNING, current.status)
    }

    @Test fun staleWorkerReplayAndStaleExternalRebindingAreRejected() {
        val current = handle("update-1", 2)
        supervisor.start(
            current,
            OperationKind.GHOST_UPDATE,
            "Queued",
            0,
            ExternalJobBinding.WorkManager("current-work"),
        )

        assertFalse(supervisor.reportProgress(handle("update-1", 1), "Committing", 1))
        assertFalse(
            supervisor.bindExternalJob(
                handle("update-1", 1),
                ExternalJobBinding.WorkManager("stale-work"),
            ),
        )
        assertTrue(
            supervisor.bindExternalJob(
                current,
                ExternalJobBinding.WorkManager("replacement-work"),
            ),
        )

        val record = supervisor.snapshot().single()
        assertEquals(OperationProgress("Queued", 0), record.progress)
        assertEquals(ExternalJobBinding.WorkManager("replacement-work"), record.externalJob)
    }

    @Test fun sharedPreferencesAdapterRoundTripsAndEnforcesHandleCas() {
        val storage = SharedPreferencesDurableOperationStore.MemoryStorage()
        val firstStore = SharedPreferencesDurableOperationStore(storage)
        val record = DurableOperationRecord(
            id = OperationId("update-1"),
            attemptId = AttemptId(4),
            kind = OperationKind.GHOST_UPDATE,
            externalJob = ExternalJobBinding.WorkManager("worker-4"),
            progress = OperationProgress("Verifying", 12),
            status = OperationStatus.CANCEL_REQUESTED,
            showStallPrompt = true,
            diagnostics = "still stopping",
        )
        assertTrue(firstStore.putIfAbsent(record))
        assertFalse(firstStore.putIfAbsent(record))

        val restoredStore = SharedPreferencesDurableOperationStore(storage)
        assertEquals(record, restoredStore.read().single())
        assertFalse(
            restoredStore.compareAndSet(
                handle("update-1", 3),
                OperationStatus.CANCEL_REQUESTED,
                record.copy(status = OperationStatus.CANCELLED),
            ),
        )
        assertTrue(
            restoredStore.compareAndSet(
                handle("update-1", 4),
                OperationStatus.CANCEL_REQUESTED,
                record.copy(status = OperationStatus.CANCELLED),
            ),
        )
        assertEquals(OperationStatus.CANCELLED, firstStore.read().single().status)
    }

    private fun handle(id: String, attempt: Long) = OperationHandle(OperationId(id), AttemptId(attempt))

    private class FakeMonotonicClock(var value: Long = 0L) : MonotonicClock {
        override fun nowMillis(): Long = value
    }

    private class RecordingCancellation : OperationCancellation {
        val handles = mutableListOf<OperationHandle>()

        override fun cancel(handle: OperationHandle) {
            handles += handle
        }
    }

    private class MemoryDurableOperationStore : DurableOperationStore {
        private val records = linkedMapOf<OperationId, DurableOperationRecord>()

        override fun read(): List<DurableOperationRecord> = records.values.toList()

        override fun putIfAbsent(record: DurableOperationRecord): Boolean {
            if (record.id in records) return false
            records[record.id] = record
            return true
        }

        override fun compareAndSet(
            handle: OperationHandle,
            expected: OperationStatus,
            updated: DurableOperationRecord,
        ): Boolean {
            val current = records[handle.operationId] ?: return false
            if (current.attemptId != handle.attemptId || current.status != expected) return false
            records[handle.operationId] = updated
            return true
        }
    }
}
