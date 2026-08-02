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
        assertTrue(cancellation.requests.isEmpty())
    }

    @Test fun onlyRealProgressResetsTheObservationWindow() {
        val handle = handle("update-1", 1)
        supervisor.start(
            handle,
            OperationKind.GHOST_UPDATE,
            "Downloading",
            8,
            ExternalJobBinding.WorkManager("worker-1"),
        )
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
        supervisor.start(
            handle,
            OperationKind.NAR_INSTALL,
            "Extracting",
            8,
            ExternalJobBinding.WorkManager("worker-1"),
        )
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
        assertTrue(cancellation.requests.isEmpty())
    }

    @Test fun stopIsIdempotentAndOperationSpecific() {
        val selected = handle("nar-1", 1)
        val other = handle("nar-2", 1)
        val selectedJob = ExternalJobBinding.DownloadManager(101)
        supervisor.start(selected, OperationKind.REMOTE_NAR, "Downloading", 1, selectedJob)
        supervisor.start(other, OperationKind.REMOTE_NAR, "Downloading", 2, ExternalJobBinding.DownloadManager(202))

        assertTrue(supervisor.requestStop(selected))
        assertTrue(supervisor.requestStop(selected))

        val records = supervisor.snapshot().associateBy { it.id }
        assertEquals(OperationStatus.CANCEL_REQUESTED, records.getValue(selected.operationId).status)
        assertEquals("Stopping...", records.getValue(selected.operationId).progress.phase)
        assertEquals(OperationStatus.RUNNING, records.getValue(other.operationId).status)
        assertEquals(listOf(CancellationRequest(selected, selectedJob)), cancellation.requests)
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
        val binding = ExternalJobBinding.WorkManager("worker-1")
        supervisor.start(handle, OperationKind.GHOST_UPDATE, "Committing", 0, binding)
        supervisor.requestStop(handle)
        cancellation.requests.clear()

        DurableOperationSupervisor(store, clock, cancellation)

        assertEquals(listOf(CancellationRequest(handle, binding)), cancellation.requests)
        assertEquals(OperationStatus.CANCEL_REQUESTED, store.read().single().status)
    }

    @Test fun stoppingGetsASecondObservationWindowAndDiagnostics() {
        val handle = handle("update-1", 1)
        val binding = ExternalJobBinding.WorkManager("worker-1")
        supervisor.start(handle, OperationKind.GHOST_UPDATE, "Committing", 0, binding)
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
        assertEquals(listOf(CancellationRequest(handle, binding)), cancellation.requests)
    }

    @Test fun terminalCallbackIsPersistedAndCleanedFromActiveSnapshot() {
        val handle = handle("copy-1", 1)
        supervisor.start(
            handle,
            OperationKind.LOCAL_NAR,
            "Copying",
            4,
            ExternalJobBinding.WorkManager("worker-1"),
        )

        assertTrue(supervisor.finish(handle, OperationStatus.COMPLETED))

        assertTrue(supervisor.snapshot().isEmpty())
        assertEquals(OperationStatus.COMPLETED, store.read().single().status)
        assertFalse(supervisor.finish(handle, OperationStatus.COMPLETED))
    }

    @Test fun duplicateAcceptanceIsRejectedAndHigherAttemptReplacesOnlyTerminalWork() {
        val first = handle("nar-1", 1)
        val retry = handle("nar-1", 2)
        assertTrue(
            supervisor.start(
                first,
                OperationKind.REMOTE_NAR,
                "Downloading",
                0,
                ExternalJobBinding.DownloadManager(101),
            ),
        )
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

    @Test fun staleWorkerReplayAndExternalIdentityMutationAreRejected() {
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
        assertFalse(
            supervisor.bindExternalJob(
                current,
                ExternalJobBinding.WorkManager("replacement-work"),
            ),
        )

        val record = supervisor.snapshot().single()
        assertEquals(OperationProgress("Queued", 0), record.progress)
        assertEquals(ExternalJobBinding.WorkManager("current-work"), record.externalJob)
    }

    @Test fun retryCannotReusePreviousAttemptsExternalJob() {
        val first = handle("nar-1", 1)
        val retry = handle("nar-1", 2)
        val reusedJob = ExternalJobBinding.DownloadManager(101)
        assertTrue(supervisor.start(first, OperationKind.REMOTE_NAR, "Downloading", 0, reusedJob))
        assertTrue(supervisor.finish(first, OperationStatus.CANCELLED))

        assertFalse(supervisor.start(retry, OperationKind.REMOTE_NAR, "Downloading", 0, reusedJob))

        val record = store.read().single()
        assertEquals(AttemptId(1), record.attemptId)
        assertEquals(OperationStatus.CANCELLED, record.status)
        assertEquals(reusedJob, record.externalJob)
    }

    @Test fun pendingRetryCannotLaterBindPreviousAttemptsExternalJob() {
        val first = handle("nar-1", 1)
        val retry = handle("nar-1", 2)
        val previousJob = ExternalJobBinding.DownloadManager(101)
        val replacementJob = ExternalJobBinding.DownloadManager(202)
        assertTrue(supervisor.start(first, OperationKind.REMOTE_NAR, "Downloading", 0, previousJob))
        assertTrue(supervisor.finish(first, OperationStatus.CANCELLED))
        assertTrue(supervisor.start(retry, OperationKind.REMOTE_NAR, "Queued", 0))

        assertFalse(supervisor.bindExternalJob(retry, previousJob))
        assertTrue(supervisor.bindExternalJob(retry, replacementJob))

        assertEquals(replacementJob, supervisor.snapshot().single().externalJob)
    }

    @Test fun thirdAttemptCannotReuseFirstAttemptsExternalJob() {
        val first = handle("nar-1", 1)
        val second = handle("nar-1", 2)
        val third = handle("nar-1", 3)
        val jobA = ExternalJobBinding.DownloadManager(101)
        val jobB = ExternalJobBinding.DownloadManager(202)
        assertTrue(supervisor.start(first, OperationKind.REMOTE_NAR, "Downloading", 0, jobA))
        assertTrue(supervisor.finish(first, OperationStatus.CANCELLED))
        assertTrue(supervisor.start(second, OperationKind.REMOTE_NAR, "Downloading", 0, jobB))
        assertTrue(supervisor.finish(second, OperationStatus.CANCELLED))

        assertFalse(supervisor.start(third, OperationKind.REMOTE_NAR, "Downloading", 0, jobA))

        assertEquals(AttemptId(2), store.read().single().attemptId)
        assertEquals(jobB, store.read().single().externalJob)
    }

    @Test fun completeBindingHistorySurvivesStoreAndSupervisorRecreation() {
        val storage = SharedPreferencesDurableOperationStore.MemoryStorage()
        val persistedStore = SharedPreferencesDurableOperationStore(storage)
        val firstSupervisor = DurableOperationSupervisor(persistedStore, clock, cancellation)
        val first = handle("update-1", 1)
        val second = handle("update-1", 2)
        val third = handle("update-1", 3)
        val jobA = ExternalJobBinding.WorkManager("worker-a")
        val jobB = ExternalJobBinding.WorkManager("worker-b")
        val jobC = ExternalJobBinding.WorkManager("worker-c")
        assertTrue(firstSupervisor.start(first, OperationKind.GHOST_UPDATE, "Queued", 0, jobA))
        assertTrue(firstSupervisor.finish(first, OperationStatus.CANCELLED))
        assertTrue(firstSupervisor.start(second, OperationKind.GHOST_UPDATE, "Queued", 0, jobB))
        assertTrue(firstSupervisor.finish(second, OperationStatus.CANCELLED))
        assertTrue(firstSupervisor.start(third, OperationKind.GHOST_UPDATE, "Queued", 0))

        val restored = DurableOperationSupervisor(
            SharedPreferencesDurableOperationStore(storage),
            clock,
            cancellation,
        )
        assertFalse(restored.bindExternalJob(third, jobA))
        assertTrue(restored.bindExternalJob(third, jobC))

        assertEquals(jobC, restored.snapshot().single().externalJob)
    }

    @Test fun progressBeforeExternalBindingIsRejected() {
        val handle = handle("update-1", 1)
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))

        assertFalse(supervisor.reportProgress(handle, "Downloading", 1))

        assertEquals(OperationProgress("Queued", 0), store.read().single().progress)
        assertTrue(supervisor.bindExternalJob(handle, ExternalJobBinding.WorkManager("worker-1")))
        assertTrue(supervisor.reportProgress(handle, "Downloading", 1))
    }

    @Test fun terminalCallbackBeforeExternalBindingIsRejected() {
        val handle = handle("update-1", 1)
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))

        assertFalse(supervisor.finish(handle, OperationStatus.COMPLETED))

        assertEquals(OperationStatus.RUNNING, store.read().single().status)
        assertTrue(supervisor.bindExternalJob(handle, ExternalJobBinding.WorkManager("worker-1")))
        assertTrue(supervisor.finish(handle, OperationStatus.COMPLETED))
    }

    @Test fun bindingAfterStopReissuesCancellationForTheNewlyIdentifiedJob() {
        val handle = handle("update-1", 1)
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))
        assertTrue(supervisor.requestStop(handle))

        assertTrue(
            supervisor.bindExternalJob(
                handle,
                ExternalJobBinding.WorkManager("worker-1"),
            ),
        )

        assertEquals(
            listOf(
                CancellationRequest(
                    handle,
                    ExternalJobBinding.WorkManager("worker-1"),
                ),
            ),
            cancellation.requests,
        )
    }

    @Test fun recreationCanReconcileUnboundCancellationWhenAdapterConfirmsNoJob() {
        val handle = handle("update-1", 1)
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))
        assertTrue(supervisor.requestStop(handle))
        val restored = DurableOperationSupervisor(store, clock, cancellation)

        assertTrue(restored.reconcileUnboundCancellation(handle))

        assertTrue(restored.snapshot().isEmpty())
        assertEquals(OperationStatus.CANCELLED, store.read().single().status)
        assertTrue(cancellation.requests.isEmpty())
    }

    @Test fun unboundCancellationReconciliationRejectsBoundAttempt() {
        val handle = handle("update-1", 1)
        val binding = ExternalJobBinding.WorkManager("worker-1")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))
        assertTrue(supervisor.requestStop(handle))

        assertFalse(supervisor.reconcileUnboundCancellation(handle))

        assertEquals(OperationStatus.CANCEL_REQUESTED, store.read().single().status)
        assertEquals(listOf(CancellationRequest(handle, binding)), cancellation.requests)
    }

    @Test fun unboundCancellationReconciliationRejectsRunningStaleAndTerminalAttempts() {
        val handle = handle("update-1", 2)
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))
        assertFalse(supervisor.reconcileUnboundCancellation(handle))
        assertFalse(supervisor.reconcileUnboundCancellation(handle("update-1", 1)))
        assertEquals(OperationStatus.RUNNING, store.read().single().status)

        store.compareAndSet(
            handle,
            OperationStatus.RUNNING,
            store.read().single().copy(status = OperationStatus.CANCELLED),
        )
        assertFalse(supervisor.reconcileUnboundCancellation(handle))
        assertTrue(cancellation.requests.isEmpty())
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
            externalJobHistory = setOf(
                ExternalJobBinding.WorkManager("worker-4"),
                ExternalJobBinding.DownloadManager(12),
            ),
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

    private data class CancellationRequest(
        val handle: OperationHandle,
        val binding: ExternalJobBinding,
    )

    private class RecordingCancellation : OperationCancellation {
        val requests = mutableListOf<CancellationRequest>()

        override fun cancel(handle: OperationHandle, binding: ExternalJobBinding) {
            requests += CancellationRequest(handle, binding)
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
