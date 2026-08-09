package com.cattailsw.nanidroid.durable

import com.cattailsw.nanidroid.di.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @Test fun promptWriteFailureUsesPositiveRetryDelay() {
        var rejectPromptWrite = false
        val retryStore = object : DurableOperationStore {
            private val delegate = MemoryDurableOperationStore()

            override fun read(): List<DurableOperationRecord> = delegate.read()

            override fun putIfAbsent(record: DurableOperationRecord): Boolean =
                delegate.putIfAbsent(record)

            override fun compareAndSet(
                expected: DurableOperationRecord,
                updated: DurableOperationRecord,
            ): Boolean =
                if (rejectPromptWrite && !expected.showStallPrompt && updated.showStallPrompt) {
                    false
                } else {
                    delegate.compareAndSet(expected, updated)
                }
        }
        val retrySupervisor = DurableOperationSupervisor(retryStore, clock, RecordingCancellation())
        retrySupervisor.start(handle("prompt-write-retry", 1), OperationKind.GHOST_UPDATE, "Queued", 0)

        clock.value = 30_000
        rejectPromptWrite = true

        assertEquals(1_000L, retrySupervisor.attentionSnapshot().nextCheckDelayMillis)
    }

    @Test fun onlyRealProgressResetsTheObservationWindow() {
        val handle = handle("update-1", 1)
        val binding = workManager("worker-1")
        supervisor.start(
            handle,
            OperationKind.GHOST_UPDATE,
            "Downloading",
            8,
            binding,
        )
        clock.value = 20_000
        assertFalse(supervisor.reportProgress(handle, binding, "Downloading", 8))
        clock.value = 30_000
        assertTrue(supervisor.snapshot().single().showStallPrompt)

        assertTrue(supervisor.keepWaiting(handle))
        clock.value = 50_000
        assertTrue(supervisor.reportProgress(handle, binding, "Downloading", 9))
        clock.value = 79_999
        assertFalse(supervisor.snapshot().single().showStallPrompt)
        clock.value = 80_000
        assertTrue(supervisor.snapshot().single().showStallPrompt)
    }

    @Test fun phaseChangeCountsAsProgressButARegressingCountDoesNot() {
        val handle = handle("install-1", 1)
        val binding = workManager("worker-1")
        supervisor.start(
            handle,
            OperationKind.NAR_INSTALL,
            "Extracting",
            8,
            binding,
        )
        clock.value = 20_000
        assertTrue(supervisor.reportProgress(handle, binding, "Verifying", 0))
        clock.value = 40_000
        assertFalse(supervisor.reportProgress(handle, binding, "Verifying", 0))
        assertFalse(supervisor.reportProgress(handle, binding, "Verifying", -1))
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

    @Test fun requestStopRetriesCancellationIfPlatformCancellationFails() {
        val selected = handle("nar-3", 1)
        val selectedJob = workManager("worker-fails")
        val failing = RecordingCancellation(failing = true)
        val failingSupervisor = DurableOperationSupervisor(
            store,
            clock,
            failing,
        )

        assertTrue(failingSupervisor.start(selected, OperationKind.REMOTE_NAR, "Downloading", 0, selectedJob))

        assertTrue(failingSupervisor.requestStop(selected))
        val requested = store.read().single()
        assertEquals(OperationStatus.CANCEL_REQUESTED, requested.status)
        assertEquals(1, failing.requestCount)
        assertTrue(failing.requests.isEmpty())

        assertTrue(failingSupervisor.requestStop(selected))
        assertEquals(2, failing.requestCount)
        assertEquals("Stopping...", requested.progress.phase)
    }

    @Test fun repeatedFailedCancellationRetriesDoNotRestartObservationWindow() {
        val stopHandle = handle("nar-4", 1)
        val stopBinding = workManager("worker-failing-window")
        val throwingCancellation = ThrowingCancellation()
        val stopSupervisor = DurableOperationSupervisor(store, clock, throwingCancellation)

        assertTrue(stopSupervisor.start(stopHandle, OperationKind.REMOTE_NAR, "Downloading", 0, stopBinding))
        clock.value = 0

        assertTrue(stopSupervisor.requestStop(stopHandle))
        assertEquals(
            1,
            throwingCancellation.requests.size,
        )

        clock.value = 29_999
        assertTrue(stopSupervisor.requestStop(stopHandle))
        assertFalse(stopSupervisor.snapshot().single().showStallPrompt)
        val stalled = store.read().single()
        val cleared = store.compareAndSet(
            stalled,
            stalled.copy(showStallPrompt = false, diagnostics = null),
        )
        assertTrue(cleared)
        assertEquals(2, throwingCancellation.requests.size)

        clock.value = 44_999
        assertEquals(
            listOf(CancellationRequest(stopHandle, stopBinding), CancellationRequest(stopHandle, stopBinding)),
            throwingCancellation.requests,
        )
        assertTrue(stopSupervisor.snapshot().single().showStallPrompt)
    }

    @Test fun concurrentKeepWaitingIsObservedAfterAnInFlightCancellationRetryFails() {
        val handle = handle("concurrent-cancellation-observation", 1)
        val binding = workManager("concurrent-cancellation-observation-worker")
        val cancellation = BlockingThrowingCancellation()
        val firstSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val secondSupervisor = DurableOperationSupervisor(store, clock, RecordingCancellation())

        assertTrue(firstSupervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))
        assertTrue(firstSupervisor.requestStop(handle))
        clock.value = 30_000
        assertTrue(firstSupervisor.snapshot().single().showStallPrompt)

        val retry = Thread { assertTrue(firstSupervisor.requestStop(handle)) }
        retry.start()
        assertTrue(cancellation.awaitRetryStarted())
        clock.value = 30_001
        assertTrue(secondSupervisor.keepWaiting(handle))
        cancellation.finishRetry()
        retry.join(5_000)
        assertFalse(retry.isAlive)

        assertFalse(firstSupervisor.snapshot().single().showStallPrompt)
        clock.value = 60_000
        assertFalse(firstSupervisor.snapshot().single().showStallPrompt)
        clock.value = 60_001
        assertTrue(firstSupervisor.snapshot().single().showStallPrompt)
    }

    @Test fun concurrentKeepWaitingDuringBoundCancellationFailureStartsANewObservationWindow() {
        val handle = handle("bound-cancellation-observation", 1)
        val binding = workManager("bound-cancellation-observation-worker")
        val cancellation = BlockingCancellationFailure()
        val firstSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val secondSupervisor = DurableOperationSupervisor(store, clock, RecordingCancellation())

        assertTrue(firstSupervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))
        assertTrue(firstSupervisor.requestStop(handle))
        clock.value = 30_000
        assertTrue(firstSupervisor.snapshot().single().showStallPrompt)

        val bind = Thread { assertTrue(firstSupervisor.bindExternalJob(handle, binding)) }
        bind.start()
        assertTrue(cancellation.awaitCancellationStarted())
        clock.value = 30_001
        assertTrue(secondSupervisor.keepWaiting(handle))
        cancellation.failCancellation()
        bind.join(5_000)
        assertFalse(bind.isAlive)

        clock.value = 60_000
        assertFalse(firstSupervisor.snapshot().single().showStallPrompt)
        clock.value = 90_000
        assertTrue(firstSupervisor.snapshot().single().showStallPrompt)
    }

    @Test fun requestStopRetriesAfterPlatformCancellationThrows() {
        val cancellation = ThrowingCancellation()
        val stopHandle = handle("update-1", 1)
        val stopSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val binding = workManager("worker-1")
        assertTrue(stopSupervisor.start(stopHandle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

        assertTrue(stopSupervisor.requestStop(stopHandle))
        assertEquals(OperationStatus.CANCEL_REQUESTED, store.read().single().status)
        assertTrue(stopSupervisor.requestStop(stopHandle))
        assertEquals(
            listOf(
                CancellationRequest(stopHandle, binding),
                CancellationRequest(stopHandle, binding),
            ),
            cancellation.requests,
        )
    }

    @Test fun requestStopRecordsBoundedSanitizedCancellationFailureDiagnostic() {
        val cancellation = ThrowingCancellation(
            "cancellation failed at ${"x".repeat(9_000)}\nwith newline",
        )
        val stopHandle = handle("update-1", 99)
        val stopSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val binding = workManager("worker-1")
        assertTrue(stopSupervisor.start(stopHandle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

        assertTrue(stopSupervisor.requestStop(stopHandle))
        val record = store.read().single()
        assertEquals(OperationStatus.CANCEL_REQUESTED, record.status)
        assertFalse(record.showStallPrompt)
        assertNotNull(record.diagnostics)
        assertEquals("Cancellation request failed", record.diagnostics)

        clock.value = 29_999
        assertFalse(stopSupervisor.snapshot().single().showStallPrompt)

        clock.value = 30_000
        assertTrue(stopSupervisor.snapshot().single().showStallPrompt)
    }

    @Test fun requestStopClearsFailureDiagnosticAfterSuccessfulPlatformRetry() {
        val failing = FailingThenSucceedingCancellation()
        val stopHandle = handle("update-2", 1)
        val stopSupervisor = DurableOperationSupervisor(store, clock, failing)
        val binding = workManager("worker-2")
        assertTrue(stopSupervisor.start(stopHandle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

        assertTrue(stopSupervisor.requestStop(stopHandle))
        assertEquals("Cancellation request failed", store.read().single().diagnostics)
        assertFalse(store.read().single().showStallPrompt)

        clock.value = 29_999
        assertFalse(stopSupervisor.snapshot().single().showStallPrompt)

        clock.value = 30_000
        assertTrue(stopSupervisor.snapshot().single().showStallPrompt)

        clock.value = 30_001
        assertTrue(stopSupervisor.requestStop(stopHandle))

        assertNull(store.read().single().diagnostics)
        assertFalse(store.read().single().showStallPrompt)
        assertFalse(stopSupervisor.snapshot().single().showStallPrompt)
        clock.value = 60_000
        assertFalse(stopSupervisor.snapshot().single().showStallPrompt)
        clock.value = 60_001
        assertTrue(stopSupervisor.snapshot().single().showStallPrompt)
        assertEquals(
            listOf(
                CancellationRequest(stopHandle, binding),
                CancellationRequest(stopHandle, binding),
            ),
            failing.requests,
        )
    }

    @Test fun failedCancellationRetryDoesNotCrashWhenDiagnosticPersistenceFails() {
        val failingCancellation = ThrowingCancellation()
        val throwingStore = object : DurableOperationStore {
            private val delegate = MemoryDurableOperationStore()

            override fun read(): List<DurableOperationRecord> = delegate.read()

            override fun putIfAbsent(record: DurableOperationRecord): Boolean {
                return delegate.putIfAbsent(record)
            }

            override fun compareAndSet(
                expected: DurableOperationRecord,
                updated: DurableOperationRecord,
            ): Boolean {
                if (
                    expected.status == OperationStatus.CANCEL_REQUESTED &&
                    updated.diagnostics == "Cancellation request failed"
                ) {
                    throw IllegalStateException("diagnostic persistence failed")
                }
                return delegate.compareAndSet(expected, updated)
            }
        }
        val restored = handle("update-5", 1)
        val restoredBinding = workManager("worker-5")
        assertTrue(
            throwingStore.putIfAbsent(
                DurableOperationRecord(
                    id = restored.operationId,
                    attemptId = restored.attemptId,
                    kind = OperationKind.GHOST_UPDATE,
                    externalJob = restoredBinding,
                    progress = OperationProgress("Committing", 0),
                    status = OperationStatus.CANCEL_REQUESTED,
                    showStallPrompt = false,
                    diagnostics = null,
                    externalJobHistory = setOf(restoredBinding),
                ),
            ),
        )

        DurableOperationSupervisor(throwingStore, clock, failingCancellation)

        assertEquals(
            listOf(
                CancellationRequest(restored, restoredBinding),
            ),
            failingCancellation.requests,
        )
        assertEquals(OperationStatus.CANCEL_REQUESTED, throwingStore.read().single().status)
    }

    @Test fun successfulCancellationRetryCanBeRetriedWhenDiagnosticClearFails() {
        val clearingFailureStore = object : DurableOperationStore {
            private val delegate = MemoryDurableOperationStore()

            override fun read(): List<DurableOperationRecord> = delegate.read()

            override fun putIfAbsent(record: DurableOperationRecord): Boolean {
                return delegate.putIfAbsent(record)
            }

            override fun compareAndSet(
                expected: DurableOperationRecord,
                updated: DurableOperationRecord,
            ): Boolean {
                if (
                    expected.status == OperationStatus.CANCEL_REQUESTED &&
                    expected.diagnostics == CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX &&
                    updated.diagnostics == null
                ) {
                    throw IllegalStateException("clear failed")
                }
                return delegate.compareAndSet(expected, updated)
            }
        }
        val cancellation = RecordingCancellation()
        val staleStopHandle = handle("update-6", 1)
        val staleBinding = workManager("worker-6")
        assertTrue(
            clearingFailureStore.putIfAbsent(
                DurableOperationRecord(
                    id = staleStopHandle.operationId,
                    attemptId = staleStopHandle.attemptId,
                    kind = OperationKind.GHOST_UPDATE,
                    externalJob = staleBinding,
                    progress = OperationProgress("Committing", 0),
                    status = OperationStatus.CANCEL_REQUESTED,
                    showStallPrompt = false,
                    diagnostics = CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX,
                    externalJobHistory = setOf(staleBinding),
                ),
            ),
        )

        val stopSupervisor = DurableOperationSupervisor(clearingFailureStore, clock, cancellation)

        assertEquals(
            listOf(
                CancellationRequest(staleStopHandle, staleBinding),
            ),
            cancellation.requests,
        )

        assertTrue(stopSupervisor.requestStop(staleStopHandle))
        assertEquals(
            listOf(
                CancellationRequest(staleStopHandle, staleBinding),
                CancellationRequest(staleStopHandle, staleBinding),
            ),
            cancellation.requests,
        )
    }

    @Test fun requestStopDoesNotDuplicateAfterSuccessfulCancellation() {
        val cancellation = RecordingCancellation()
        val stopSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val stopHandle = handle("update-2", 2)
        val binding = workManager("worker-2")
        assertTrue(stopSupervisor.start(stopHandle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

        assertTrue(stopSupervisor.requestStop(stopHandle))
        assertTrue(stopSupervisor.requestStop(stopHandle))
        assertEquals(
            listOf(
                CancellationRequest(stopHandle, binding),
            ),
            cancellation.requests,
        )
    }

    @Test fun requestStopRetryUsesExactBindingInstance() {
        val binding = workManager("worker-3")
        val cancellation = ThrowingCancellation()
        val bindingSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val stopHandle = handle("update-3", 1)
        assertTrue(
            bindingSupervisor.start(
                stopHandle,
                OperationKind.GHOST_UPDATE,
                "Queued",
                0,
                binding,
            ),
        )

        assertTrue(bindingSupervisor.requestStop(stopHandle))
        assertTrue(bindingSupervisor.requestStop(stopHandle))
        assertEquals(2, cancellation.requests.size)
        assertTrue(binding === cancellation.requests[1].binding)
    }

    @Test fun requestStopReissuesPersistedCancelRequestAfterPreviousThrow() {
        val cancellation = ThrowingCancellation()
        val firstSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val stopHandle = handle("update-4", 1)
        val binding = workManager("worker-4")
        assertTrue(firstSupervisor.start(stopHandle, OperationKind.GHOST_UPDATE, "Queued", 0, binding))

        assertTrue(firstSupervisor.requestStop(stopHandle))
        assertEquals(
            listOf(
                CancellationRequest(stopHandle, binding),
            ),
            cancellation.requests,
        )

        DurableOperationSupervisor(store, clock, cancellation)

        assertEquals(
            listOf(
                CancellationRequest(stopHandle, binding),
                CancellationRequest(stopHandle, binding),
            ),
            cancellation.requests,
        )
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

    @Test fun progressFromAnotherSupervisorStartsANewObservationWindow() {
        val handle = handle("shared-progress", 1)
        val binding = workManager("shared-progress-worker")
        val firstSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val secondSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        assertTrue(
            firstSupervisor.start(
                handle,
                OperationKind.GHOST_UPDATE,
                "Downloading",
                0,
                binding,
            ),
        )
        assertFalse(secondSupervisor.snapshot().single().showStallPrompt)

        clock.value = 29_000
        assertTrue(firstSupervisor.reportProgress(handle, binding, "Downloading", 1))
        assertFalse(secondSupervisor.snapshot().single().showStallPrompt)

        clock.value = 58_999
        assertFalse(secondSupervisor.snapshot().single().showStallPrompt)
        clock.value = 59_000
        assertTrue(secondSupervisor.snapshot().single().showStallPrompt)
    }

    @Test fun phaseCyclingProgressFromAnotherSupervisorStartsANewObservationWindow() {
        val handle = handle("shared-phase-cycle", 1)
        val binding = workManager("shared-phase-cycle-worker")
        val firstSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val secondSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        assertTrue(
            firstSupervisor.start(
                handle,
                OperationKind.GHOST_UPDATE,
                "Downloading",
                0,
                binding,
            ),
        )
        assertFalse(secondSupervisor.snapshot().single().showStallPrompt)

        clock.value = 29_000
        assertTrue(firstSupervisor.reportProgress(handle, binding, "Verifying", 0))
        assertTrue(firstSupervisor.reportProgress(handle, binding, "Downloading", 0))

        clock.value = 30_000
        assertFalse(secondSupervisor.snapshot().single().showStallPrompt)
    }

    @Test fun stopFromAnotherSupervisorStartsANewObservationWindow() {
        val handle = handle("shared-stop", 1)
        val binding = workManager("shared-stop-worker")
        val firstSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val secondSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        assertTrue(firstSupervisor.start(handle, OperationKind.GHOST_UPDATE, "Downloading", 0, binding))
        assertFalse(secondSupervisor.snapshot().single().showStallPrompt)

        clock.value = 29_000
        assertTrue(firstSupervisor.requestStop(handle))
        assertFalse(secondSupervisor.snapshot().single().showStallPrompt)

        clock.value = 58_999
        assertFalse(secondSupervisor.snapshot().single().showStallPrompt)
        clock.value = 59_000
        assertTrue(secondSupervisor.snapshot().single().showStallPrompt)
        assertEquals(OperationStatus.CANCEL_REQUESTED, secondSupervisor.snapshot().single().status)
    }

    @Test fun bindingFromAnotherSupervisorStartsANewObservationWindow() {
        val handle = handle("shared-binding", 1)
        val binding = workManager("shared-binding-worker")
        val firstSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val secondSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        assertTrue(firstSupervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))
        assertFalse(secondSupervisor.snapshot().single().showStallPrompt)

        clock.value = 29_000
        assertTrue(firstSupervisor.bindExternalJob(handle, binding))
        assertFalse(secondSupervisor.snapshot().single().showStallPrompt)

        clock.value = 58_999
        assertFalse(secondSupervisor.snapshot().single().showStallPrompt)
        clock.value = 59_000
        assertTrue(secondSupervisor.snapshot().single().showStallPrompt)
    }

    @Test fun bindingAnUnboundStalledRunningAttemptHidesPromptAndStartsANewWindow() {
        val handle = handle("stalled-unbound-binding", 1)
        val binding = workManager("stalled-unbound-binding-worker")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))

        clock.value = 30_000
        assertTrue(supervisor.snapshot().single().showStallPrompt)

        assertTrue(supervisor.bindExternalJob(handle, binding))
        assertFalse(supervisor.snapshot().single().showStallPrompt)
        assertTrue(cancellation.requests.isEmpty())

        clock.value = 59_999
        assertFalse(supervisor.snapshot().single().showStallPrompt)
        clock.value = 60_000
        assertTrue(supervisor.snapshot().single().showStallPrompt)
    }

    @Test fun identicalSnapshotsFromAnotherSupervisorDoNotRestartObservationWindow() {
        val handle = handle("shared-unchanged", 1)
        val firstSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val secondSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        assertTrue(firstSupervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))
        assertFalse(secondSupervisor.snapshot().single().showStallPrompt)

        clock.value = 29_000
        assertFalse(secondSupervisor.snapshot().single().showStallPrompt)
        clock.value = 30_000
        assertTrue(secondSupervisor.snapshot().single().showStallPrompt)
    }

    @Test fun recreationSuppressesPersistedPromptWithoutRevokingNotificationAction() {
        val handle = handle("stalled-restore", 1)
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))
        clock.value = 30_000
        assertTrue(supervisor.snapshot().single().showStallPrompt)

        val restored = DurableOperationSupervisor(store, clock, cancellation)

        assertTrue(store.read().single().showStallPrompt)
        assertFalse(restored.snapshot().single().showStallPrompt)
        assertFalse(
            restored.performAttentionAction(
                handle.copy(attemptId = AttemptId(2)),
                DurableAttentionAction.KEEP_WAITING,
            ),
        )
        assertTrue(restored.performAttentionAction(handle, DurableAttentionAction.KEEP_WAITING))
        assertFalse(store.read().single().showStallPrompt)
    }

    @Test fun recreationKeepsExactNotificationActionValidAcrossRepeatedProcessDeaths() {
        val handle = handle("stalled-twice", 1)
        val binding = workManager("stalled-twice-worker")
        assertTrue(
            supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding),
        )
        clock.value = 30_000
        assertTrue(supervisor.snapshot().single().showStallPrompt)

        DurableOperationSupervisor(store, clock, cancellation)
        val restoredAgain = DurableOperationSupervisor(store, clock, cancellation)

        assertFalse(restoredAgain.snapshot().single().showStallPrompt)
        assertTrue(restoredAgain.performAttentionAction(handle, DurableAttentionAction.STOP))
        assertEquals(OperationStatus.CANCEL_REQUESTED, store.read().single().status)
        assertEquals(listOf(CancellationRequest(handle, binding)), cancellation.requests)
    }

    @Test fun realProgressRevokesSuppressedRestoredNotificationAction() {
        val handle = handle("stalled-progress", 1)
        val binding = workManager("stalled-progress-worker")
        assertTrue(
            supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0, binding),
        )
        clock.value = 30_000
        assertTrue(supervisor.snapshot().single().showStallPrompt)
        val restored = DurableOperationSupervisor(store, clock, cancellation)

        assertTrue(restored.reportProgress(handle, binding, "Downloading", 1))

        assertFalse(restored.performAttentionAction(handle, DurableAttentionAction.STOP))
        assertEquals(OperationStatus.RUNNING, store.read().single().status)
        assertTrue(cancellation.requests.isEmpty())
    }

    @Test fun restoredPromptRepublishesAfterFreshObservationWindow() {
        val handle = handle("stalled-republish", 1)
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))
        clock.value = 30_000
        assertTrue(supervisor.snapshot().single().showStallPrompt)
        val restored = DurableOperationSupervisor(store, clock, cancellation)

        clock.value = 59_999
        val beforeDeadline = restored.attentionSnapshot()
        assertFalse(beforeDeadline.records.single().showStallPrompt)
        assertEquals(1L, beforeDeadline.nextCheckDelayMillis)

        clock.value = 60_000
        val atDeadline = restored.attentionSnapshot()
        assertTrue(atDeadline.records.single().showStallPrompt)
    }

    @Test fun recreationImmediatelyResumesPersistedCancellation() {
        val handle = handle("update-1", 1)
        val binding = workManager("worker-1")
        supervisor.start(handle, OperationKind.GHOST_UPDATE, "Committing", 0, binding)
        supervisor.requestStop(handle)
        cancellation.requests.clear()

        DurableOperationSupervisor(store, clock, cancellation)

        assertEquals(listOf(CancellationRequest(handle, binding)), cancellation.requests)
        assertEquals(OperationStatus.CANCEL_REQUESTED, store.read().single().status)
    }

    @Test fun recreationCancelsMalformedWorkManagerBindingWithoutFailureDiagnostic() {
        val handle = handle("install-1", 1)
        val malformedBinding = ExternalJobBinding.WorkManager("not-a-valid-uuid")
        assertTrue(
            store.putIfAbsent(
                DurableOperationRecord(
                    id = handle.operationId,
                    attemptId = handle.attemptId,
                    kind = OperationKind.NAR_INSTALL,
                    externalJob = malformedBinding,
                    progress = OperationProgress("Preparing", 0),
                    status = OperationStatus.CANCEL_REQUESTED,
                    showStallPrompt = false,
                    externalJobHistory = setOf(malformedBinding),
                ),
            ),
        )

        val first = DurableOperationSupervisor(store, clock, cancellation)
        val repairedBinding = ExternalJobBinding.WorkManager(
            durableWorkManagerId(handle, OperationKind.NAR_INSTALL).toString(),
        )
        assertEquals(OperationKind.NAR_INSTALL, cancellation.lastKind)
        assertEquals(listOf(CancellationRequest(handle, repairedBinding)), cancellation.requests)
        assertEquals(repairedBinding, store.read().single().externalJob)
        assertEquals(
            setOf(malformedBinding, repairedBinding),
            store.read().single().externalJobHistory,
        )
        assertNull(first.snapshot().single().diagnostics)

        val second = DurableOperationSupervisor(store, clock, cancellation)
        assertNull(second.snapshot().single().diagnostics)
    }

    @Test fun retryStopBindingRepairDoesNotExtendTheActionObservationWindow() {
        val handle = handle("retry-repair", 1)
        val malformedBinding = ExternalJobBinding.WorkManager("not-a-valid-uuid")
        val repairedBinding = ExternalJobBinding.WorkManager(
            durableWorkManagerId(handle, OperationKind.NAR_INSTALL).toString(),
        )
        assertTrue(
            store.putIfAbsent(
                DurableOperationRecord(
                    id = handle.operationId,
                    attemptId = handle.attemptId,
                    kind = OperationKind.NAR_INSTALL,
                    externalJob = malformedBinding,
                    progress = OperationProgress("Stopping...", 0),
                    status = OperationStatus.CANCEL_REQUESTED,
                    showStallPrompt = true,
                    diagnostics = CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX,
                    externalJobHistory = setOf(malformedBinding),
                ),
            ),
        )
        assertTrue(supervisor.snapshot().single().showStallPrompt)

        clock.value = 30_000
        assertTrue(supervisor.performAttentionAction(handle, DurableAttentionAction.RETRY_STOP))
        assertEquals(repairedBinding, store.read().single().externalJob)

        clock.value = 59_999
        assertEquals(1L, supervisor.attentionSnapshot().nextCheckDelayMillis)
    }

    @Test fun malformedBindingRepairCasLossDoesNotCancelReplacementAttempt() {
        val oldHandle = handle("install-race", 1)
        val malformedBinding = ExternalJobBinding.WorkManager("not-a-valid-uuid")
        val oldRecord = DurableOperationRecord(
            id = oldHandle.operationId,
            attemptId = oldHandle.attemptId,
            kind = OperationKind.NAR_INSTALL,
            externalJob = malformedBinding,
            progress = OperationProgress("Stopping...", 0),
            status = OperationStatus.CANCEL_REQUESTED,
            showStallPrompt = false,
            externalJobHistory = setOf(malformedBinding),
        )
        assertTrue(store.putIfAbsent(oldRecord))
        val replacementHandle = oldHandle.copy(attemptId = AttemptId(2))
        val replacementBinding = ExternalJobBinding.WorkManager(
            durableWorkManagerId(replacementHandle, OperationKind.NAR_INSTALL).toString(),
        )
        val replacement = oldRecord.copy(
            attemptId = replacementHandle.attemptId,
            externalJob = replacementBinding,
            status = OperationStatus.RUNNING,
            externalJobHistory = oldRecord.externalJobHistory + replacementBinding,
        )
        store.beforeNextCompareAndSet = {
            assertTrue(store.compareAndSet(oldRecord, replacement))
        }

        DurableOperationSupervisor(store, clock, cancellation)

        assertTrue(cancellation.requests.isEmpty())
        assertEquals(replacement, store.read().single())
    }

    @Test fun malformedBindingRepairPersistenceFailureDoesNotCrashRestore() {
        val handle = handle("install-repair-failure", 1)
        val malformedBinding = ExternalJobBinding.WorkManager("not-a-valid-uuid")
        val delegate = MemoryDurableOperationStore()
        val throwingStore = object : DurableOperationStore {
            override fun read(): List<DurableOperationRecord> = delegate.read()

            override fun putIfAbsent(record: DurableOperationRecord): Boolean =
                delegate.putIfAbsent(record)

            override fun compareAndSet(
                expected: DurableOperationRecord,
                updated: DurableOperationRecord,
            ): Boolean {
                if (expected.externalJob == malformedBinding && updated.externalJob != malformedBinding) {
                    throw IllegalStateException("repair persistence failed")
                }
                return delegate.compareAndSet(expected, updated)
            }
        }
        assertTrue(
            throwingStore.putIfAbsent(
                DurableOperationRecord(
                    id = handle.operationId,
                    attemptId = handle.attemptId,
                    kind = OperationKind.NAR_INSTALL,
                    externalJob = malformedBinding,
                    progress = OperationProgress("Stopping...", 0),
                    status = OperationStatus.CANCEL_REQUESTED,
                    showStallPrompt = false,
                    externalJobHistory = setOf(malformedBinding),
                ),
            ),
        )

        DurableOperationSupervisor(throwingStore, clock, cancellation)

        assertTrue(cancellation.requests.isEmpty())
        assertEquals(CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX, throwingStore.read().single().diagnostics)
    }

    @Test fun stoppingGetsASecondObservationWindowAndDiagnostics() {
        val handle = handle("update-1", 1)
        val binding = workManager("worker-1")
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
        val binding = workManager("worker-1")
        supervisor.start(
            handle,
            OperationKind.LOCAL_NAR,
            "Copying",
            4,
            binding,
        )

        assertTrue(supervisor.finish(handle, binding, OperationStatus.COMPLETED))

        assertTrue(supervisor.snapshot().isEmpty())
        assertEquals(OperationStatus.COMPLETED, store.read().single().status)
        assertFalse(supervisor.finish(handle, binding, OperationStatus.COMPLETED))
    }

    @Test fun activeBindingLookupRequiresExactActiveHandleAndKind() {
        val handle = handle("copy-1", 2)
        val binding = workManager("stage-worker")
        assertTrue(supervisor.start(handle, OperationKind.LOCAL_NAR, "Copying", 0, binding))

        assertEquals(binding, supervisor.activeBindingForExactAttempt(handle, OperationKind.LOCAL_NAR))
        assertEquals(
            null,
            supervisor.activeBindingForExactAttempt(handle("copy-1", 1), OperationKind.LOCAL_NAR),
        )
        assertEquals(null, supervisor.activeBindingForExactAttempt(handle, OperationKind.NAR_INSTALL))

        assertTrue(supervisor.finish(handle, binding, OperationStatus.CANCELLED))
        assertEquals(null, supervisor.activeBindingForExactAttempt(handle, OperationKind.LOCAL_NAR))
    }

    @Test fun failedAttemptLookupRequiresExactFailedHandleAndKind() {
        val failed = handle("failed-install", 2)
        assertTrue(supervisor.start(failed, OperationKind.NAR_INSTALL, "Queued", 0))
        assertTrue(supervisor.failUnboundAttempt(failed, "scheduler unavailable"))

        assertTrue(supervisor.isFailedAttempt(failed, OperationKind.NAR_INSTALL))
        assertFalse(supervisor.isFailedAttempt(handle("failed-install", 1), OperationKind.NAR_INSTALL))
        assertFalse(supervisor.isFailedAttempt(failed, OperationKind.REMOTE_NAR))

        val completed = handle("completed-install", 1)
        val completedBinding = workManager("completed-worker")
        assertTrue(
            supervisor.start(
                completed,
                OperationKind.NAR_INSTALL,
                "Installing",
                0,
                completedBinding,
            ),
        )
        assertTrue(supervisor.finish(completed, completedBinding, OperationStatus.COMPLETED))
        assertFalse(supervisor.isFailedAttempt(completed, OperationKind.NAR_INSTALL))

        val cancelled = handle("cancelled-install", 1)
        val cancelledBinding = workManager("cancelled-worker")
        assertTrue(
            supervisor.start(
                cancelled,
                OperationKind.NAR_INSTALL,
                "Installing",
                0,
                cancelledBinding,
            ),
        )
        assertTrue(supervisor.finish(cancelled, cancelledBinding, OperationStatus.CANCELLED))
        assertFalse(supervisor.isFailedAttempt(cancelled, OperationKind.NAR_INSTALL))

        val running = handle("running-install", 1)
        assertTrue(supervisor.start(running, OperationKind.NAR_INSTALL, "Queued", 0))
        assertFalse(supervisor.isFailedAttempt(running, OperationKind.NAR_INSTALL))
    }

    @Test fun duplicateAcceptanceIsRejectedAndHigherAttemptReplacesOnlyTerminalWork() {
        val first = handle("nar-1", 1)
        val retry = handle("nar-1", 2)
        val firstJob = ExternalJobBinding.DownloadManager(101)
        val retryJob = ExternalJobBinding.DownloadManager(202)
        assertTrue(
            supervisor.start(
                first,
                OperationKind.REMOTE_NAR,
                "Downloading",
                0,
                firstJob,
            ),
        )
        assertFalse(supervisor.start(first, OperationKind.REMOTE_NAR, "Downloading", 0))
        assertFalse(supervisor.start(retry, OperationKind.REMOTE_NAR, "Downloading", 0))

        assertTrue(supervisor.finish(first, firstJob, OperationStatus.CANCELLED))
        assertTrue(
            supervisor.start(
                retry,
                OperationKind.REMOTE_NAR,
                "Downloading",
                0,
                retryJob,
            ),
        )
        assertFalse(supervisor.finish(first, firstJob, OperationStatus.COMPLETED))

        val current = supervisor.snapshot().single()
        assertEquals(AttemptId(2), current.attemptId)
        assertEquals(retryJob, current.externalJob)
        assertEquals(OperationStatus.RUNNING, current.status)
    }

    @Test fun staleWorkerReplayAndExternalIdentityMutationAreRejected() {
        val current = handle("update-1", 2)
        val currentJob = workManager("current-work")
        val staleJob = workManager("stale-work")
        supervisor.start(
            current,
            OperationKind.GHOST_UPDATE,
            "Queued",
            0,
            currentJob,
        )

        assertFalse(supervisor.reportProgress(handle("update-1", 1), staleJob, "Committing", 1))
        assertFalse(
            supervisor.bindExternalJob(
                handle("update-1", 1),
                staleJob,
            ),
        )
        assertFalse(
            supervisor.bindExternalJob(
                current,
                workManager("replacement-work"),
            ),
        )

        val record = supervisor.snapshot().single()
        assertEquals(OperationProgress("Queued", 0), record.progress)
        assertEquals(currentJob, record.externalJob)
    }

    @Test fun retryCannotReusePreviousAttemptsExternalJob() {
        val first = handle("nar-1", 1)
        val retry = handle("nar-1", 2)
        val reusedJob = ExternalJobBinding.DownloadManager(101)
        assertTrue(supervisor.start(first, OperationKind.REMOTE_NAR, "Downloading", 0, reusedJob))
        assertTrue(supervisor.finish(first, reusedJob, OperationStatus.CANCELLED))

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
        assertTrue(supervisor.finish(first, previousJob, OperationStatus.CANCELLED))
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
        assertTrue(supervisor.finish(first, jobA, OperationStatus.CANCELLED))
        assertTrue(supervisor.start(second, OperationKind.REMOTE_NAR, "Downloading", 0, jobB))
        assertTrue(supervisor.finish(second, jobB, OperationStatus.CANCELLED))

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
        val jobA = workManager("worker-a")
        val jobB = workManager("worker-b")
        val jobC = workManager("worker-c")
        assertTrue(firstSupervisor.start(first, OperationKind.GHOST_UPDATE, "Queued", 0, jobA))
        assertTrue(firstSupervisor.finish(first, jobA, OperationStatus.CANCELLED))
        assertTrue(firstSupervisor.start(second, OperationKind.GHOST_UPDATE, "Queued", 0, jobB))
        assertTrue(firstSupervisor.finish(second, jobB, OperationStatus.CANCELLED))
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

    @Test fun archiveAcquisitionCanAdvanceToInstallationAfterRecreation() {
        val cases = listOf(
            ArchivePipelineCase(
                sourceKind = OperationKind.REMOTE_NAR,
                providerBinding = ExternalJobBinding.DownloadManager(101),
                installBinding = workManager("install-remote"),
            ),
            ArchivePipelineCase(
                sourceKind = OperationKind.LOCAL_NAR,
                providerBinding = workManager("copy-local"),
                installBinding = workManager("install-local"),
            ),
        )

        val observed = cases.map { case ->
            val storage = SharedPreferencesDurableOperationStore.MemoryStorage()
            val persistedStore = SharedPreferencesDurableOperationStore(storage)
            val sourceSupervisor = DurableOperationSupervisor(persistedStore, clock, cancellation)
            val sourceHandle = handle("archive-${case.sourceKind.name}", 1)
            val installHandle = handle(sourceHandle.operationId.value, 2)
            assertTrue(
                sourceSupervisor.start(
                    sourceHandle,
                    case.sourceKind,
                    "Acquiring",
                    1,
                    case.providerBinding,
                ),
            )
            assertTrue(
                sourceSupervisor.finish(
                    sourceHandle,
                    case.providerBinding,
                    OperationStatus.COMPLETED,
                ),
            )
            val restoredStore = SharedPreferencesDurableOperationStore(storage)
            val restored = DurableOperationSupervisor(restoredStore, clock, cancellation)
            val installAccepted = restored.start(
                installHandle,
                OperationKind.NAR_INSTALL,
                "Installing",
                0,
            )
            if (!installAccepted) {
                return@map ArchivePipelineObservation(
                    sourceKind = case.sourceKind,
                    installAccepted = false,
                )
            }
            val providerReuseAccepted = restored.bindExternalJob(
                installHandle,
                case.providerBinding,
            )
            val installBindingAccepted = restored.bindExternalJob(
                installHandle,
                case.installBinding,
            )
            val staleProgressAccepted = restored.reportProgress(
                sourceHandle,
                case.providerBinding,
                "Late",
                2,
            )
            val staleFinishAccepted = restored.finish(
                sourceHandle,
                case.providerBinding,
                OperationStatus.FAILED,
            )
            val staleBindingAccepted = restored.bindExternalJob(
                sourceHandle,
                workManager("stale-${case.sourceKind.name}"),
            )
            val persisted = restoredStore.read().single()
            ArchivePipelineObservation(
                sourceKind = case.sourceKind,
                installAccepted = true,
                providerReuseAccepted = providerReuseAccepted,
                installBindingAccepted = installBindingAccepted,
                staleProgressAccepted = staleProgressAccepted,
                staleFinishAccepted = staleFinishAccepted,
                staleBindingAccepted = staleBindingAccepted,
                persistedKind = persisted.kind,
                persistedAttempt = persisted.attemptId,
                persistedBinding = persisted.externalJob,
                bindingHistory = persisted.externalJobHistory,
            )
        }

        assertEquals(
            listOf(
                ArchivePipelineObservation(
                    sourceKind = OperationKind.REMOTE_NAR,
                    installAccepted = true,
                    providerReuseAccepted = false,
                    installBindingAccepted = true,
                    staleProgressAccepted = false,
                    staleFinishAccepted = false,
                    staleBindingAccepted = false,
                    persistedKind = OperationKind.NAR_INSTALL,
                    persistedAttempt = AttemptId(2),
                    persistedBinding = workManager("install-remote"),
                    bindingHistory = setOf(
                        ExternalJobBinding.DownloadManager(101),
                        workManager("install-remote"),
                    ),
                ),
                ArchivePipelineObservation(
                    sourceKind = OperationKind.LOCAL_NAR,
                    installAccepted = true,
                    providerReuseAccepted = false,
                    installBindingAccepted = true,
                    staleProgressAccepted = false,
                    staleFinishAccepted = false,
                    staleBindingAccepted = false,
                    persistedKind = OperationKind.NAR_INSTALL,
                    persistedAttempt = AttemptId(2),
                    persistedBinding = workManager("install-local"),
                    bindingHistory = setOf(
                        workManager("copy-local"),
                        workManager("install-local"),
                    ),
                ),
            ),
            observed,
        )
    }

    @Test fun otherCrossKindTransitionsRemainForbidden() {
        val cases = listOf(
            KindTransitionCase("install-to-remote", OperationKind.NAR_INSTALL, OperationKind.REMOTE_NAR),
            KindTransitionCase("install-to-local", OperationKind.NAR_INSTALL, OperationKind.LOCAL_NAR),
            KindTransitionCase("remote-to-local", OperationKind.REMOTE_NAR, OperationKind.LOCAL_NAR),
            KindTransitionCase("local-to-remote", OperationKind.LOCAL_NAR, OperationKind.REMOTE_NAR),
            KindTransitionCase("ghost-to-install", OperationKind.GHOST_UPDATE, OperationKind.NAR_INSTALL),
            KindTransitionCase("install-to-ghost", OperationKind.NAR_INSTALL, OperationKind.GHOST_UPDATE),
            KindTransitionCase("remote-to-ghost", OperationKind.REMOTE_NAR, OperationKind.GHOST_UPDATE),
            KindTransitionCase("local-to-ghost", OperationKind.LOCAL_NAR, OperationKind.GHOST_UPDATE),
            KindTransitionCase("ghost-to-remote", OperationKind.GHOST_UPDATE, OperationKind.REMOTE_NAR),
            KindTransitionCase("ghost-to-local", OperationKind.GHOST_UPDATE, OperationKind.LOCAL_NAR),
        )

        val observed = cases.map { case ->
            val caseStore = MemoryDurableOperationStore()
            val caseSupervisor = DurableOperationSupervisor(caseStore, clock, cancellation)
            val source = handle(case.name, 1)
            val sourceBinding = workManager("${case.name}-source")
            assertTrue(
                caseSupervisor.start(
                    source,
                    case.sourceKind,
                    "Source",
                    0,
                    sourceBinding,
                ),
            )
            assertTrue(caseSupervisor.finish(source, sourceBinding, OperationStatus.COMPLETED))
            case.name to caseSupervisor.start(
                handle(case.name, 2),
                case.targetKind,
                "Target",
                0,
                workManager("${case.name}-target"),
            )
        }

        assertEquals(
            listOf(
                "install-to-remote" to false,
                "install-to-local" to false,
                "remote-to-local" to false,
                "local-to-remote" to false,
                "ghost-to-install" to false,
                "install-to-ghost" to false,
                "remote-to-ghost" to false,
                "local-to-ghost" to false,
                "ghost-to-remote" to false,
                "ghost-to-local" to false,
            ),
            observed,
        )
    }

    @Test fun completedInstallCanUseOnlyExplicitRemoteReacquisitionTransition() {
        val source = handle("remote-install", 2)
        val installBinding = workManager("install-work")
        assertTrue(
            supervisor.start(
                source,
                OperationKind.NAR_INSTALL,
                "Installing",
                0,
                installBinding,
            ),
        )
        assertTrue(supervisor.finish(source, installBinding, OperationStatus.FAILED))
        val reacquisition = handle("remote-install", 3)
        val download = ExternalJobBinding.DownloadManager(81L)

        assertFalse(
            supervisor.start(
                reacquisition,
                OperationKind.REMOTE_NAR,
                "Downloading",
                0,
                download,
            ),
        )
        assertTrue(
            supervisor.startRemoteNarReacquisition(
                reacquisition,
                "Downloading",
                0,
                download,
            ),
        )

        val current = store.read().single()
        assertEquals(OperationKind.REMOTE_NAR, current.kind)
        assertEquals(AttemptId(3), current.attemptId)
        assertEquals(download, current.externalJob)
    }

    @Test fun archiveKindChangeStillRequiresTerminalStateAndGreaterAttempt() {
        val cases = listOf(
            ArchiveTransitionFenceCase(
                "remote-active",
                OperationKind.REMOTE_NAR,
                terminal = false,
                targetAttempt = 2,
            ),
            ArchiveTransitionFenceCase(
                "local-active",
                OperationKind.LOCAL_NAR,
                terminal = false,
                targetAttempt = 2,
            ),
            ArchiveTransitionFenceCase(
                "remote-same",
                OperationKind.REMOTE_NAR,
                terminal = true,
                targetAttempt = 1,
            ),
            ArchiveTransitionFenceCase(
                "local-same",
                OperationKind.LOCAL_NAR,
                terminal = true,
                targetAttempt = 1,
            ),
            ArchiveTransitionFenceCase(
                "remote-lower",
                OperationKind.REMOTE_NAR,
                terminal = true,
                targetAttempt = 0,
            ),
            ArchiveTransitionFenceCase(
                "local-lower",
                OperationKind.LOCAL_NAR,
                terminal = true,
                targetAttempt = 0,
            ),
        )

        val observed = cases.map { case ->
            val caseStore = MemoryDurableOperationStore()
            val caseSupervisor = DurableOperationSupervisor(caseStore, clock, cancellation)
            val source = handle(case.name, 1)
            val sourceBinding = workManager("${case.name}-provider")
            assertTrue(
                caseSupervisor.start(
                    source,
                    case.sourceKind,
                    "Acquiring",
                    0,
                    sourceBinding,
                ),
            )
            if (case.terminal) {
                assertTrue(caseSupervisor.finish(source, sourceBinding, OperationStatus.COMPLETED))
            }
            case.name to caseSupervisor.start(
                handle(case.name, case.targetAttempt),
                OperationKind.NAR_INSTALL,
                "Installing",
                0,
                workManager("${case.name}-installer"),
            )
        }

        assertEquals(
            listOf(
                "remote-active" to false,
                "local-active" to false,
                "remote-same" to false,
                "local-same" to false,
                "remote-lower" to false,
                "local-lower" to false,
            ),
            observed,
        )
    }

    @Test fun progressBeforeExternalBindingIsRejected() {
        val handle = handle("update-1", 1)
        val binding = workManager("worker-1")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))

        assertFalse(supervisor.reportProgress(handle, binding, "Downloading", 1))

        assertEquals(OperationProgress("Queued", 0), store.read().single().progress)
        assertTrue(supervisor.bindExternalJob(handle, binding))
        assertTrue(supervisor.reportProgress(handle, binding, "Downloading", 1))
    }

    @Test fun terminalCallbackBeforeExternalBindingIsRejected() {
        val handle = handle("update-1", 1)
        val binding = workManager("worker-1")
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))

        assertFalse(supervisor.finish(handle, binding, OperationStatus.COMPLETED))

        assertEquals(OperationStatus.RUNNING, store.read().single().status)
        assertTrue(supervisor.bindExternalJob(handle, binding))
        assertTrue(supervisor.finish(handle, binding, OperationStatus.COMPLETED))
    }

    @Test fun exactActiveUnboundAttemptCanBeFailedDuringSchedulerRecovery() {
        val handle = handle("install-1", 2)
        assertTrue(supervisor.start(handle, OperationKind.NAR_INSTALL, "Queued", 0))

        assertFalse(
            supervisor.failUnboundAttempt(
                handle("install-1", 1),
                "scheduler unavailable",
            ),
        )
        assertTrue(supervisor.failUnboundAttempt(handle, "scheduler unavailable"))

        val failed = store.read().single()
        assertEquals(OperationStatus.FAILED, failed.status)
        assertEquals("scheduler unavailable", failed.diagnostics)
        assertFalse(supervisor.bindExternalJob(handle, workManager("late-worker")))
    }

    @Test fun exactBoundFailureTransitionIsReplayIdempotent() {
        val handle = handle("install-1", 2)
        val binding = workManager("worker-2")
        val diagnostics = "scheduler unavailable"
        assertTrue(
            supervisor.start(
                handle,
                OperationKind.NAR_INSTALL,
                "Queued",
                0,
                binding,
            ),
        )

        assertTrue(
            supervisor.failOrConfirmExactAttempt(
                handle,
                OperationKind.NAR_INSTALL,
                binding,
                diagnostics,
            ),
        )
        assertTrue(
            supervisor.failOrConfirmExactAttempt(
                handle,
                OperationKind.NAR_INSTALL,
                binding,
                diagnostics,
            ),
        )

        val failed = store.read().single()
        assertEquals(OperationStatus.FAILED, failed.status)
        assertEquals(diagnostics, failed.diagnostics)
    }

    @Test fun exactBoundFailureReplayRejectsUnrelatedTerminalRecords() {
        val expectedHandle = handle("install-1", 2)
        val expectedBinding = workManager("worker-2")
        val expectedDiagnostics = "scheduler unavailable"
        val expected = DurableOperationRecord(
            id = expectedHandle.operationId,
            attemptId = expectedHandle.attemptId,
            kind = OperationKind.NAR_INSTALL,
            externalJob = expectedBinding,
            progress = OperationProgress("Queued", 0),
            status = OperationStatus.FAILED,
            showStallPrompt = false,
            diagnostics = expectedDiagnostics,
            externalJobHistory = setOf(expectedBinding),
        )
        val cases = listOf(
            expected.copy(status = OperationStatus.COMPLETED, diagnostics = null),
            expected.copy(status = OperationStatus.CANCELLED, diagnostics = null),
            expected.copy(kind = OperationKind.LOCAL_NAR),
            expected.copy(externalJob = workManager("other-worker")),
            expected.copy(diagnostics = "other failure"),
            expected.copy(attemptId = AttemptId(3)),
        )

        val accepted = cases.map { persisted ->
            val caseStore = MemoryDurableOperationStore()
            assertTrue(caseStore.putIfAbsent(persisted))
            DurableOperationSupervisor(caseStore, FakeMonotonicClock(), RecordingCancellation())
                .failOrConfirmExactAttempt(
                    expectedHandle,
                    OperationKind.NAR_INSTALL,
                    expectedBinding,
                    expectedDiagnostics,
                )
        }

        assertEquals(listOf(false, false, false, false, false, false), accepted)
    }

    @Test fun unboundFailureRecoveryRejectsBoundAttempt() {
        val handle = handle("install-1", 2)
        val binding = workManager("worker-2")
        assertTrue(supervisor.start(handle, OperationKind.NAR_INSTALL, "Queued", 0, binding))

        assertFalse(supervisor.failUnboundAttempt(handle, "scheduler unavailable"))

        val running = store.read().single()
        assertEquals(OperationStatus.RUNNING, running.status)
        assertEquals(binding, running.externalJob)
    }

    @Test fun bindingAfterStopReissuesCancellationForTheNewlyIdentifiedJob() {
        val handle = handle("update-1", 1)
        assertTrue(supervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))
        assertTrue(supervisor.requestStop(handle))

        assertTrue(
            supervisor.bindExternalJob(
                handle,
                workManager("worker-1"),
            ),
        )

        assertEquals(
            listOf(
                CancellationRequest(
                    handle,
                    workManager("worker-1"),
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
        val binding = workManager("worker-1")
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

        val running = store.read().single()
        store.compareAndSet(running, running.copy(status = OperationStatus.CANCELLED))
        assertFalse(supervisor.reconcileUnboundCancellation(handle))
        assertTrue(cancellation.requests.isEmpty())
    }

    @Test fun competingSupervisorsPersistOnlyOneExternalJob() {
        val firstSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val secondSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val handle = handle("update-1", 1)
        val winningBinding = workManager("worker-winner")
        val losingBinding = workManager("worker-loser")
        assertTrue(firstSupervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))
        var winnerAccepted = false
        store.beforeNextCompareAndSet = {
            winnerAccepted = secondSupervisor.bindExternalJob(handle, winningBinding)
        }

        val loserAccepted = firstSupervisor.bindExternalJob(handle, losingBinding)

        assertTrue(winnerAccepted)
        assertFalse(loserAccepted)
        assertEquals(winningBinding, store.read().single().externalJob)
    }

    @Test fun rejectedDuplicateExternalJobCannotReportProgressForTheWinner() {
        val firstSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val secondSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val handle = handle("update-1", 1)
        val winningBinding = workManager("worker-winner")
        val losingBinding = workManager("worker-loser")
        assertTrue(firstSupervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))
        store.beforeNextCompareAndSet = {
            assertTrue(secondSupervisor.bindExternalJob(handle, winningBinding))
        }
        assertFalse(firstSupervisor.bindExternalJob(handle, losingBinding))

        assertFalse(firstSupervisor.reportProgress(handle, losingBinding, "Downloading", 1))
        assertEquals(OperationProgress("Queued", 0), store.read().single().progress)
        assertTrue(secondSupervisor.reportProgress(handle, winningBinding, "Downloading", 2))

        assertEquals(winningBinding, store.read().single().externalJob)
        assertEquals(OperationProgress("Downloading", 2), store.read().single().progress)
        assertEquals(OperationStatus.RUNNING, store.read().single().status)
    }

    @Test fun rejectedDuplicateExternalJobCannotFinishTheWinner() {
        val firstSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val secondSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val handle = handle("update-1", 1)
        val winningBinding = workManager("worker-winner")
        val losingBinding = workManager("worker-loser")
        assertTrue(firstSupervisor.start(handle, OperationKind.GHOST_UPDATE, "Queued", 0))
        store.beforeNextCompareAndSet = {
            assertTrue(secondSupervisor.bindExternalJob(handle, winningBinding))
        }
        assertFalse(firstSupervisor.bindExternalJob(handle, losingBinding))

        assertFalse(firstSupervisor.finish(handle, losingBinding, OperationStatus.COMPLETED))
        assertEquals(OperationStatus.RUNNING, store.read().single().status)
        assertTrue(secondSupervisor.finish(handle, winningBinding, OperationStatus.COMPLETED))

        val terminal = store.read().single()
        assertEquals(winningBinding, terminal.externalJob)
        assertEquals(OperationProgress("Queued", 0), terminal.progress)
        assertEquals(OperationStatus.COMPLETED, terminal.status)
    }

    @Test fun competingProgressCannotRegressOrOverwriteTheWinner() {
        val firstSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val secondSupervisor = DurableOperationSupervisor(store, clock, cancellation)
        val handle = handle("update-1", 1)
        val binding = workManager("worker-1")
        assertTrue(
            firstSupervisor.start(
                handle,
                OperationKind.GHOST_UPDATE,
                "Downloading",
                0,
                binding,
            ),
        )
        var winnerAccepted = false
        store.beforeNextCompareAndSet = {
            winnerAccepted = secondSupervisor.reportProgress(handle, binding, "Downloading", 10)
        }

        val loserAccepted = firstSupervisor.reportProgress(handle, binding, "Downloading", 5)

        assertTrue(winnerAccepted)
        assertFalse(loserAccepted)
        assertEquals(OperationProgress("Downloading", 10), store.read().single().progress)
    }

    @Test fun sharedPreferencesAdapterRoundTripsAndEnforcesHandleCas() {
        val storage = SharedPreferencesDurableOperationStore.MemoryStorage()
        val firstStore = SharedPreferencesDurableOperationStore(storage)
        val record = DurableOperationRecord(
            id = OperationId("update-1"),
            attemptId = AttemptId(4),
            kind = OperationKind.GHOST_UPDATE,
            externalJob = workManager("worker-4"),
            progress = OperationProgress("Verifying", 12),
            status = OperationStatus.CANCEL_REQUESTED,
            showStallPrompt = true,
            diagnostics = "still stopping",
            externalJobHistory = setOf(
                workManager("worker-4"),
                ExternalJobBinding.DownloadManager(12),
            ),
        )
        assertTrue(firstStore.putIfAbsent(record))
        assertFalse(firstStore.putIfAbsent(record))

        val restoredStore = SharedPreferencesDurableOperationStore(storage)
        assertEquals(record, restoredStore.read().single())
        assertFalse(
            restoredStore.compareAndSet(
                record.copy(attemptId = AttemptId(3)),
                record.copy(status = OperationStatus.CANCELLED),
            ),
        )
        assertFalse(
            restoredStore.compareAndSet(
                record.copy(status = OperationStatus.RUNNING),
                record.copy(status = OperationStatus.CANCELLED),
            ),
        )
        assertTrue(
            restoredStore.compareAndSet(
                record,
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

    private data class ArchivePipelineCase(
        val sourceKind: OperationKind,
        val providerBinding: ExternalJobBinding,
        val installBinding: ExternalJobBinding,
    )

    private data class ArchivePipelineObservation(
        val sourceKind: OperationKind,
        val installAccepted: Boolean,
        val providerReuseAccepted: Boolean? = null,
        val installBindingAccepted: Boolean? = null,
        val staleProgressAccepted: Boolean? = null,
        val staleFinishAccepted: Boolean? = null,
        val staleBindingAccepted: Boolean? = null,
        val persistedKind: OperationKind? = null,
        val persistedAttempt: AttemptId? = null,
        val persistedBinding: ExternalJobBinding? = null,
        val bindingHistory: Set<ExternalJobBinding>? = null,
    )

    private data class KindTransitionCase(
        val name: String,
        val sourceKind: OperationKind,
        val targetKind: OperationKind,
    )

    private data class ArchiveTransitionFenceCase(
        val name: String,
        val sourceKind: OperationKind,
        val terminal: Boolean,
        val targetAttempt: Long,
    )

    private class RecordingCancellation(
        private val failing: Boolean = false,
    ) : OperationCancellation {
        var requests = mutableListOf<CancellationRequest>()
        var requestCount = 0
        var lastKind: OperationKind? = null

        override fun cancel(handle: OperationHandle, kind: OperationKind, binding: ExternalJobBinding) {
            lastKind = kind
            requestCount++
            if (failing) throw IllegalStateException("platform cancellation failed")
            requests.add(CancellationRequest(handle, binding))
        }
    }

    private class FailingThenSucceedingCancellation : OperationCancellation {
        var requestCount = 0
        val requests = mutableListOf<CancellationRequest>()

        override fun cancel(handle: OperationHandle, kind: OperationKind, binding: ExternalJobBinding) {
            requestCount++
            requests.add(CancellationRequest(handle, binding))
            if (requestCount == 1) throw IllegalStateException("platform cancellation failed")
        }
    }

    private class ThrowingCancellation(
        private val message: String = "platform cancellation failed",
    ) : OperationCancellation {
        val requests = mutableListOf<CancellationRequest>()

        override fun cancel(handle: OperationHandle, kind: OperationKind, binding: ExternalJobBinding) {
            requests.add(CancellationRequest(handle, binding))
            throw IllegalStateException(message)
        }
    }

    private class BlockingThrowingCancellation : OperationCancellation {
        private val retryStarted = CountDownLatch(1)
        private val finishRetry = CountDownLatch(1)
        private var calls = 0

        override fun cancel(handle: OperationHandle, kind: OperationKind, binding: ExternalJobBinding) {
            calls++
            if (calls == 2) {
                retryStarted.countDown()
                check(finishRetry.await(5, TimeUnit.SECONDS))
            }
            throw IllegalStateException("platform cancellation failed")
        }

        fun awaitRetryStarted(): Boolean = retryStarted.await(5, TimeUnit.SECONDS)

        fun finishRetry() {
            finishRetry.countDown()
        }
    }

    private class BlockingCancellationFailure : OperationCancellation {
        private val cancellationStarted = CountDownLatch(1)
        private val finishCancellation = CountDownLatch(1)

        override fun cancel(handle: OperationHandle, kind: OperationKind, binding: ExternalJobBinding) {
            cancellationStarted.countDown()
            check(finishCancellation.await(5, TimeUnit.SECONDS))
            throw IllegalStateException("platform cancellation failed")
        }

        fun awaitCancellationStarted(): Boolean = cancellationStarted.await(5, TimeUnit.SECONDS)

        fun failCancellation() {
            finishCancellation.countDown()
        }
    }

    private fun workManager(label: String) = ExternalJobBinding.WorkManager(
        UUID.nameUUIDFromBytes(label.toByteArray()).toString(),
    )

    private class MemoryDurableOperationStore : DurableOperationStore {
        private val records = linkedMapOf<OperationId, DurableOperationRecord>()
        var beforeNextCompareAndSet: (() -> Unit)? = null

        override fun read(): List<DurableOperationRecord> = records.values.toList()

        override fun putIfAbsent(record: DurableOperationRecord): Boolean {
            if (record.id in records) return false
            records[record.id] = record
            return true
        }

        override fun compareAndSet(
            expected: DurableOperationRecord,
            updated: DurableOperationRecord,
        ): Boolean {
            beforeNextCompareAndSet?.also {
                beforeNextCompareAndSet = null
                it()
            }
            val current = records[expected.id] ?: return false
            if (current != expected) return false
            records[expected.id] = updated
            return true
        }
    }
}
