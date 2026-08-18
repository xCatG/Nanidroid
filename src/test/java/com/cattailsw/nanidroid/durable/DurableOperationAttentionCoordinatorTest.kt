package com.cattailsw.nanidroid.durable

import com.cattailsw.nanidroid.di.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DurableOperationAttentionCoordinatorTest {
    private val clock = MutableClock()
    private val storage = SharedPreferencesDurableOperationStore.MemoryStorage()
    private val store = SharedPreferencesDurableOperationStore(storage)
    private val supervisor = DurableOperationSupervisor(store, clock) { _, _, _ -> }
    private val scheduler = FakeAttentionScheduler()
    private val notifier = RecordingAttentionNotifier()
    private val coordinator = DurableOperationAttentionCoordinator(
        supervisor,
        scheduler,
        notifier,
    )
    private val handle = OperationHandle(OperationId("archive/Aa"), AttemptId(1L))
    private val binding = ExternalJobBinding.DownloadManager(41L)

    @Test fun sleepsWhenEmptyAndSchedulesExactThirtySecondBoundary() {
        coordinator.start()
        scheduler.runPending()
        assertNull(scheduler.delayMillis)
        assertEquals(emptyList<DurableOperationRecord>(), notifier.last)

        assertTrue(supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding))
        assertEquals(0L, scheduler.delayMillis)
        scheduler.runPending()

        assertEquals(30_000L, scheduler.delayMillis)
        assertTrue(coordinator.observeStalledOperations().value.isEmpty())

        clock.value = 29_999L
        scheduler.runPending()
        assertEquals(1L, scheduler.delayMillis)
        assertTrue(notifier.last.isEmpty())

        clock.value = 30_000L
        scheduler.runPending()
        assertEquals(30_000L, scheduler.delayMillis)
        assertEquals(listOf(handle), notifier.last.map { OperationHandle(it.id, it.attemptId) })
        assertTrue(coordinator.observeStalledOperations().value.single().showStallPrompt)
    }

    @Test fun realProgressImmediatelyClearsNotificationAndRestartsWindow() {
        coordinator.start()
        scheduler.runPending()
        supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding)
        scheduler.runPending()
        clock.value = 30_000L
        scheduler.runPending()
        assertEquals(listOf(handle), notifier.last.map { OperationHandle(it.id, it.attemptId) })

        assertTrue(supervisor.reportProgress(handle, binding, "Downloading archive", 1L))
        assertEquals(0L, scheduler.delayMillis)
        scheduler.runPending()

        assertTrue(notifier.last.isEmpty())
        assertEquals(30_000L, scheduler.delayMillis)
    }

    @Test fun progressFromAnotherSupervisorImmediatelyReconcilesVisibleAttention() {
        val otherSupervisor = DurableOperationSupervisor(store, clock) { _, _, _ -> }
        coordinator.start()
        scheduler.runPending()
        assertTrue(supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding))
        scheduler.runPending()
        clock.value = 30_000L
        scheduler.runPending()
        assertEquals(listOf(handle), notifier.last.map { OperationHandle(it.id, it.attemptId) })

        assertTrue(otherSupervisor.reportProgress(handle, binding, "Downloading archive", 1L))

        assertEquals(0L, scheduler.delayMillis)
        scheduler.runPending()
        assertTrue(notifier.last.isEmpty())
        assertEquals(30_000L, scheduler.delayMillis)
    }

    @Test fun completionFromAnotherSupervisorImmediatelyReconcilesAttentionPublishedByAReturnedSnapshot() {
        val otherSupervisor = DurableOperationSupervisor(
            SharedPreferencesDurableOperationStore(storage),
            clock,
        ) { _, _, _ -> }
        coordinator.start()
        scheduler.runPending()
        assertTrue(supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding))
        scheduler.runPending()
        clock.value = 30_000L
        scheduler.runPending()
        assertEquals(listOf(handle), notifier.last.map { OperationHandle(it.id, it.attemptId) })
        assertEquals(30_000L, scheduler.delayMillis)

        assertTrue(otherSupervisor.finish(handle, binding, OperationStatus.COMPLETED))

        assertEquals(0L, scheduler.delayMillis)
        scheduler.runPending()
        assertTrue(notifier.last.isEmpty())
        assertTrue(coordinator.observeStalledOperations().value.isEmpty())
    }

    @Test fun externallyClearedPromptStartsANewObservationWindow() {
        val otherSupervisor = DurableOperationSupervisor(store, clock) { _, _, _ -> }
        coordinator.start()
        scheduler.runPending()
        assertTrue(supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding))
        scheduler.runPending()
        clock.value = 30_000L
        scheduler.runPending()
        assertEquals(listOf(handle), notifier.last.map { OperationHandle(it.id, it.attemptId) })

        assertTrue(otherSupervisor.keepWaiting(handle))
        clock.value = 60_000L
        scheduler.runPending()

        assertTrue(notifier.last.isEmpty())
        assertTrue(coordinator.observeStalledOperations().value.isEmpty())
        assertEquals(30_000L, scheduler.delayMillis)

        clock.value = 89_999L
        scheduler.runPending()
        assertTrue(notifier.last.isEmpty())
        assertEquals(1L, scheduler.delayMillis)

        clock.value = 90_000L
        scheduler.runPending()
        assertEquals(listOf(handle), notifier.last.map { OperationHandle(it.id, it.attemptId) })
    }

    @Test fun externallyClearedAndRepublishedPromptStartsANewObservationWindow() {
        val otherSupervisor = DurableOperationSupervisor(store, clock) { _, _, _ -> }
        coordinator.start()
        scheduler.runPending()
        assertTrue(supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding))
        scheduler.runPending()
        clock.value = 30_000L
        scheduler.runPending()
        assertEquals(listOf(handle), notifier.last.map { OperationHandle(it.id, it.attemptId) })
        otherSupervisor.attentionSnapshot()

        clock.value = 31_000L
        assertTrue(otherSupervisor.keepWaiting(handle))
        clock.value = 61_000L
        assertTrue(otherSupervisor.attentionSnapshot().records.single().showStallPrompt)

        scheduler.runPending()
        assertTrue(notifier.last.isEmpty())
        assertTrue(coordinator.observeStalledOperations().value.isEmpty())
        assertEquals(30_000L, scheduler.delayMillis)

        clock.value = 91_000L
        scheduler.runPending()
        assertEquals(listOf(handle), notifier.last.map { OperationHandle(it.id, it.attemptId) })
    }

    @Test fun localKeepWaitingDoesNotExtendTheWindowWhenReconciliationIsDelayed() {
        coordinator.start()
        scheduler.runPending()
        assertTrue(supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding))
        scheduler.runPending()
        clock.value = 30_000L
        scheduler.runPending()
        assertEquals(listOf(handle), notifier.last.map { OperationHandle(it.id, it.attemptId) })

        clock.value = 31_000L
        assertTrue(supervisor.keepWaiting(handle))

        clock.value = 59_999L
        scheduler.runPending()
        assertTrue(notifier.last.isEmpty())
        assertEquals(1_001L, scheduler.delayMillis)

        clock.value = 60_999L
        scheduler.runPending()
        assertTrue(notifier.last.isEmpty())
        assertEquals(1L, scheduler.delayMillis)

        clock.value = 61_000L
        scheduler.runPending()
        assertEquals(listOf(handle), notifier.last.map { OperationHandle(it.id, it.attemptId) })
    }

    @Test fun attentionKeepWaitingDoesNotExtendTheWindowWhenReconciliationIsDelayed() {
        coordinator.start()
        scheduler.runPending()
        assertTrue(supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding))
        scheduler.runPending()
        clock.value = 30_000L
        scheduler.runPending()
        assertEquals(listOf(handle), notifier.last.map { OperationHandle(it.id, it.attemptId) })

        clock.value = 31_000L
        assertTrue(supervisor.performAttentionAction(handle, DurableAttentionAction.KEEP_WAITING))

        clock.value = 59_999L
        scheduler.runPending()
        assertTrue(notifier.last.isEmpty())
        assertEquals(1_001L, scheduler.delayMillis)

        clock.value = 60_999L
        scheduler.runPending()
        assertTrue(notifier.last.isEmpty())
        assertEquals(1L, scheduler.delayMillis)

        clock.value = 61_000L
        scheduler.runPending()
        assertEquals(listOf(handle), notifier.last.map { OperationHandle(it.id, it.attemptId) })
    }

    @Test fun crossSupervisorRetryStopStartsANewStoppingObservationWindow() {
        val retrySupervisor = DurableOperationSupervisor(store, clock) { _, _, _ ->
            error("cancellation dispatch failed")
        }
        coordinator.start()
        scheduler.runPending()
        assertTrue(supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding))
        scheduler.runPending()
        clock.value = 30_000L
        scheduler.runPending()

        assertTrue(retrySupervisor.performAttentionAction(handle, DurableAttentionAction.STOP))
        clock.value = 60_000L
        scheduler.runPending()
        clock.value = 90_000L
        scheduler.runPending()
        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING, DurableAttentionAction.RETRY_STOP),
            DurableAttentionNotificationPolicy.actions(coordinator.observeStalledOperations().value.single()),
        )

        assertTrue(retrySupervisor.performAttentionAction(handle, DurableAttentionAction.RETRY_STOP))
        clock.value = 120_000L
        scheduler.runPending()

        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING),
            DurableAttentionNotificationPolicy.actions(coordinator.observeStalledOperations().value.single()),
        )
        assertEquals(30_000L, scheduler.delayMillis)
    }

    @Test fun restoredAttentionKeepsExactNotificationWhileVisuallySuppressedThenRepublishes() {
        assertTrue(supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding))
        clock.value = 30_000L
        assertTrue(supervisor.snapshot().single().showStallPrompt)
        val restoredSupervisor = DurableOperationSupervisor(store, clock) { _, _, _ -> }
        val restoredScheduler = FakeAttentionScheduler()
        val restoredNotifier = RecordingAttentionNotifier()
        val restoredCoordinator = DurableOperationAttentionCoordinator(
            restoredSupervisor,
            restoredScheduler,
            restoredNotifier,
        )

        restoredCoordinator.start()
        restoredScheduler.runPending()

        assertTrue(restoredCoordinator.observeStalledOperations().value.isEmpty())
        assertEquals(
            listOf(handle),
            restoredNotifier.last.map { OperationHandle(it.id, it.attemptId) },
        )
        assertEquals(30_000L, restoredScheduler.delayMillis)

        clock.value = 59_999L
        restoredScheduler.runPending()
        assertEquals(1L, restoredScheduler.delayMillis)
        assertEquals(
            listOf(handle),
            restoredNotifier.last.map { OperationHandle(it.id, it.attemptId) },
        )

        clock.value = 60_000L
        restoredScheduler.runPending()
        assertEquals(listOf(handle), restoredNotifier.last.map { OperationHandle(it.id, it.attemptId) })
        assertTrue(restoredCoordinator.observeStalledOperations().value.single().showStallPrompt)
    }

    @Test fun restoredFailedStopKeepsOnlyKeepWaitingNotificationUntilFreshStoppingWindowExpires() {
        val failedCancellation = object : OperationCancellation {
            override fun cancel(
                handle: OperationHandle,
                kind: OperationKind,
                binding: ExternalJobBinding,
            ) = error("cancellation dispatch failed")
        }
        val stoppingSupervisor = DurableOperationSupervisor(store, clock, failedCancellation)
        assertTrue(
            stoppingSupervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding),
        )
        clock.value = 30_000L
        assertTrue(stoppingSupervisor.snapshot().single().showStallPrompt)
        assertTrue(stoppingSupervisor.performAttentionAction(handle, DurableAttentionAction.STOP))
        assertTrue(store.read().single().showStallPrompt)
        assertEquals(CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX, store.read().single().diagnostics)

        val restoredSupervisor = DurableOperationSupervisor(store, clock, failedCancellation)
        val restoredScheduler = FakeAttentionScheduler()
        val restoredNotifier = RecordingAttentionNotifier()
        val restoredCoordinator = DurableOperationAttentionCoordinator(
            restoredSupervisor,
            restoredScheduler,
            restoredNotifier,
        )

        restoredCoordinator.start()
        restoredScheduler.runPending()

        assertTrue(restoredCoordinator.observeStalledOperations().value.isEmpty())
        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING),
            DurableAttentionNotificationPolicy.actions(restoredNotifier.last.single()),
        )
        assertNull(restoredNotifier.last.single().diagnostics)
        assertEquals(30_000L, restoredScheduler.delayMillis)

        clock.value = 60_000L
        restoredScheduler.runPending()

        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING, DurableAttentionAction.RETRY_STOP),
            DurableAttentionNotificationPolicy.actions(restoredNotifier.last.single()),
        )
        assertEquals(CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX, restoredNotifier.last.single().diagnostics)
    }

    @Test fun restartCancellationFailureRetainsAnAlreadyActionableStoppingNotification() {
        assertTrue(supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding))
        clock.value = 30_000L
        assertTrue(supervisor.snapshot().single().showStallPrompt)
        assertTrue(supervisor.performAttentionAction(handle, DurableAttentionAction.STOP))
        assertTrue(store.read().single().showStallPrompt)
        assertNull(store.read().single().diagnostics)

        val failedCancellation = object : OperationCancellation {
            override fun cancel(
                handle: OperationHandle,
                kind: OperationKind,
                binding: ExternalJobBinding,
            ) = error("cancellation dispatch failed")
        }
        val restoredSupervisor = DurableOperationSupervisor(store, clock, failedCancellation)
        val restoredScheduler = FakeAttentionScheduler()
        val restoredNotifier = RecordingAttentionNotifier()
        val restoredCoordinator = DurableOperationAttentionCoordinator(
            restoredSupervisor,
            restoredScheduler,
            restoredNotifier,
        )

        restoredCoordinator.start()
        restoredScheduler.runPending()

        assertTrue(restoredCoordinator.observeStalledOperations().value.isEmpty())
        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING),
            DurableAttentionNotificationPolicy.actions(restoredNotifier.last.single()),
        )
        assertNull(restoredNotifier.last.single().diagnostics)
        assertEquals(30_000L, restoredScheduler.delayMillis)

        clock.value = 60_000L
        restoredScheduler.runPending()

        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING, DurableAttentionAction.RETRY_STOP),
            DurableAttentionNotificationPolicy.actions(restoredNotifier.last.single()),
        )
        assertEquals(CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX, restoredNotifier.last.single().diagnostics)
    }

    @Test fun restoredStoppingDelayKeepsOnlyKeepWaitingUntilFreshStoppingWindowExpires() {
        assertTrue(supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding))
        clock.value = 30_000L
        assertTrue(supervisor.snapshot().single().showStallPrompt)
        assertTrue(supervisor.performAttentionAction(handle, DurableAttentionAction.STOP))
        clock.value = 60_000L
        assertEquals(STOPPING_DELAY_DIAGNOSTIC, supervisor.snapshot().single().diagnostics)

        val restoredSupervisor = DurableOperationSupervisor(store, clock) { _, _, _ -> }
        val restoredScheduler = FakeAttentionScheduler()
        val restoredNotifier = RecordingAttentionNotifier()
        val restoredCoordinator = DurableOperationAttentionCoordinator(
            restoredSupervisor,
            restoredScheduler,
            restoredNotifier,
        )

        restoredCoordinator.start()
        restoredScheduler.runPending()

        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING),
            DurableAttentionNotificationPolicy.actions(restoredNotifier.last.single()),
        )
        assertNull(restoredNotifier.last.single().diagnostics)
        assertEquals(30_000L, restoredScheduler.delayMillis)

        clock.value = 90_000L
        restoredScheduler.runPending()

        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING),
            DurableAttentionNotificationPolicy.actions(restoredNotifier.last.single()),
        )
        assertEquals(STOPPING_DELAY_DIAGNOSTIC, restoredNotifier.last.single().diagnostics)
    }

    @Test fun repeatedReconstructionKeepsTheSameExactPromptHandle() {
        assertTrue(supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding))
        clock.value = 30_000L
        assertTrue(supervisor.snapshot().single().showStallPrompt)

        val firstRestart = DurableOperationSupervisor(store, clock) { _, _, _ -> }
        val firstNotifier = RecordingAttentionNotifier()
        val firstScheduler = FakeAttentionScheduler()
        DurableOperationAttentionCoordinator(firstRestart, firstScheduler, firstNotifier).start()
        firstScheduler.runPending()
        assertEquals(
            listOf(handle),
            firstNotifier.last.map { OperationHandle(it.id, it.attemptId) },
        )

        val secondRestart = DurableOperationSupervisor(store, clock) { _, _, _ -> }
        val secondNotifier = RecordingAttentionNotifier()
        val secondScheduler = FakeAttentionScheduler()
        DurableOperationAttentionCoordinator(secondRestart, secondScheduler, secondNotifier).start()
        secondScheduler.runPending()
        assertEquals(
            listOf(handle),
            secondNotifier.last.map { OperationHandle(it.id, it.attemptId) },
        )
    }

    @Test fun reconstructionDoesNotReconcileStaleAttemptNotification() {
        assertTrue(supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding))
        clock.value = 30_000L
        assertTrue(supervisor.snapshot().single().showStallPrompt)
        assertTrue(supervisor.finish(handle, binding, OperationStatus.CANCELLED))

        val currentHandle = handle.copy(attemptId = AttemptId(2L))
        val currentBinding = ExternalJobBinding.DownloadManager(42L)
        assertTrue(
            supervisor.start(
                currentHandle,
                OperationKind.REMOTE_NAR,
                "Downloading archive",
                0L,
                currentBinding,
            ),
        )
        clock.value = 60_000L
        assertTrue(supervisor.snapshot().single().showStallPrompt)

        val restoredNotifier = RecordingAttentionNotifier()
        val restoredScheduler = FakeAttentionScheduler()
        DurableOperationAttentionCoordinator(
            DurableOperationSupervisor(store, clock) { _, _, _ -> },
            restoredScheduler,
            restoredNotifier,
        ).start()
        restoredScheduler.runPending()

        assertEquals(
            listOf(currentHandle),
            restoredNotifier.last.map { OperationHandle(it.id, it.attemptId) },
        )
    }

    @Test fun terminalMutationClearsAttentionAndReturnsToSleep() {
        coordinator.start()
        scheduler.runPending()
        supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding)
        scheduler.runPending()
        clock.value = 30_000L
        scheduler.runPending()

        assertTrue(supervisor.finish(handle, binding, OperationStatus.COMPLETED))
        scheduler.runPending()

        assertTrue(notifier.last.isEmpty())
        assertNull(scheduler.delayMillis)
    }

    @Test fun externalPermissionOrChannelChangeRefreshesAnAlreadyStalledRecord() {
        coordinator.start()
        scheduler.runPending()
        supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding)
        scheduler.runPending()
        clock.value = 30_000L
        scheduler.runPending()
        val reconciliationsBeforeRefresh = notifier.reconciliationCount

        coordinator.refresh()
        assertEquals(0L, scheduler.delayMillis)
        scheduler.runPending()

        assertEquals(reconciliationsBeforeRefresh + 1, notifier.reconciliationCount)
        assertEquals(1, notifier.last.size)
    }

    @Test fun staleAndMismatchedNotificationActionsAreNoOps() {
        supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding)
        assertFalse(supervisor.performAttentionAction(handle, DurableAttentionAction.STOP))
        clock.value = 30_000L
        supervisor.snapshot()

        val stale = OperationHandle(handle.operationId, AttemptId(2L))
        assertFalse(supervisor.performAttentionAction(stale, DurableAttentionAction.STOP))
        assertFalse(supervisor.performAttentionAction(handle, DurableAttentionAction.RETRY_STOP))
        assertTrue(supervisor.performAttentionAction(handle, DurableAttentionAction.STOP))
        assertEquals(OperationStatus.CANCEL_REQUESTED, store.read().single().status)
        assertFalse(supervisor.performAttentionAction(handle, DurableAttentionAction.STOP))
    }

    @Test fun stopActionKeepsTheSameAttentionVisibleWhileCancellationIsPending() {
        coordinator.start()
        scheduler.runPending()
        supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding)
        scheduler.runPending()
        clock.value = 30_000L
        scheduler.runPending()

        assertTrue(supervisor.performAttentionAction(handle, DurableAttentionAction.STOP))
        scheduler.runPending()

        val stopping = coordinator.observeStalledOperations().value.single()
        assertEquals(handle, OperationHandle(stopping.id, stopping.attemptId))
        assertEquals(OperationStatus.CANCEL_REQUESTED, stopping.status)
        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING),
            DurableAttentionNotificationPolicy.actions(stopping),
        )
        assertEquals(30_000L, scheduler.delayMillis)

        clock.value = 59_999L
        scheduler.runPending()
        assertEquals(1L, scheduler.delayMillis)
        assertNull(coordinator.observeStalledOperations().value.single().diagnostics)

        clock.value = 60_000L
        scheduler.runPending()
        assertEquals(30_000L, scheduler.delayMillis)
        assertEquals(
            STOPPING_DELAY_DIAGNOSTIC,
            coordinator.observeStalledOperations().value.single().diagnostics,
        )
    }

    @Test fun failedStopStaysVisibleAndOnlyOffersRetryAfterTheSecondStallWindow() {
        var cancellationAttempts = 0
        val retrySupervisor = DurableOperationSupervisor(store, clock) { _, _, _ ->
            cancellationAttempts += 1
            if (cancellationAttempts == 1) error("first dispatch failed")
        }
        val retryCoordinator = DurableOperationAttentionCoordinator(
            retrySupervisor,
            scheduler,
            notifier,
        )
        retryCoordinator.start()
        scheduler.runPending()
        retrySupervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding)
        scheduler.runPending()
        clock.value = 30_000L
        scheduler.runPending()
        assertTrue(retrySupervisor.performAttentionAction(handle, DurableAttentionAction.STOP))
        scheduler.runPending()
        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING),
            DurableAttentionNotificationPolicy.actions(
                retryCoordinator.observeStalledOperations().value.single(),
            ),
        )
        assertNull(retryCoordinator.observeStalledOperations().value.single().diagnostics)
        assertEquals(30_000L, scheduler.delayMillis)

        clock.value = 60_000L
        scheduler.runPending()
        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING, DurableAttentionAction.RETRY_STOP),
            DurableAttentionNotificationPolicy.actions(
                retryCoordinator.observeStalledOperations().value.single(),
            ),
        )
        assertEquals(
            CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX,
            retryCoordinator.observeStalledOperations().value.single().diagnostics,
        )

        assertTrue(retrySupervisor.performAttentionAction(handle, DurableAttentionAction.RETRY_STOP))
        scheduler.runPending()

        val stopping = retryCoordinator.observeStalledOperations().value.single()
        assertEquals(OperationStatus.CANCEL_REQUESTED, stopping.status)
        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING),
            DurableAttentionNotificationPolicy.actions(stopping),
        )
        assertEquals(30_000L, scheduler.delayMillis)
    }

    @Test fun malformedBindingRepairFailurePreservesVisibleStoppingAttention() {
        val malformedBinding = ExternalJobBinding.WorkManager("not-a-uuid")
        var rejectRepair = true
        val throwingStore = object : DurableOperationStore {
            private val delegate = SharedPreferencesDurableOperationStore(
                SharedPreferencesDurableOperationStore.MemoryStorage(),
            )

            override fun read(): List<DurableOperationRecord> = delegate.read()

            override fun putIfAbsent(record: DurableOperationRecord): Boolean =
                delegate.putIfAbsent(record)

            override fun compareAndSet(
                expected: DurableOperationRecord,
                updated: DurableOperationRecord,
            ): Boolean {
                if (
                    rejectRepair &&
                    expected.externalJob == malformedBinding &&
                    updated.externalJob != malformedBinding
                ) {
                    rejectRepair = false
                    error("repair persistence failed")
                }
                return delegate.compareAndSet(expected, updated)
            }
        }
        val repairSupervisor = DurableOperationSupervisor(throwingStore, clock) { _, _, _ -> }
        val repairCoordinator = DurableOperationAttentionCoordinator(
            repairSupervisor,
            scheduler,
            notifier,
        )
        repairCoordinator.start()
        scheduler.runPending()
        repairSupervisor.start(
            handle,
            OperationKind.NAR_INSTALL,
            "Updating ghost",
            0L,
            malformedBinding,
        )
        scheduler.runPending()
        clock.value = 30_000L
        scheduler.runPending()

        assertTrue(repairSupervisor.performAttentionAction(handle, DurableAttentionAction.STOP))
        scheduler.runPending()

        val stopping = repairCoordinator.observeStalledOperations().value.single()
        assertEquals(OperationStatus.CANCEL_REQUESTED, stopping.status)
        assertTrue(stopping.showStallPrompt)
        assertNull(stopping.diagnostics)
        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING),
            DurableAttentionNotificationPolicy.actions(stopping),
        )
        assertEquals(30_000L, scheduler.delayMillis)
    }

    @Test fun mutationListenerRunsAfterSupervisorLockIsReleased() {
        val callbackCompleted = AtomicBoolean(false)
        supervisor.setMutationListener {
            val completed = CountDownLatch(1)
            Thread {
                supervisor.snapshot()
                completed.countDown()
            }.start()
            callbackCompleted.set(completed.await(2, TimeUnit.SECONDS))
        }
        callbackCompleted.set(false)

        supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding)

        assertTrue(callbackCompleted.get())
    }

    @Test fun exactIntentCodecPreservesEncodedAndHashCollidingOperationIds() {
        val handles = listOf(
            OperationHandle(OperationId("Aa"), AttemptId(1L)),
            OperationHandle(OperationId("BB"), AttemptId(1L)),
            OperationHandle(OperationId("folder/日本 語"), AttemptId(Long.MAX_VALUE)),
        )

        handles.forEach { exact ->
            assertEquals(exact, DurableAttentionIntentCodec.parse(DurableAttentionIntentCodec.encode(exact)))
        }
        assertTrue(DurableAttentionIntentCodec.encode(handles[0]) != DurableAttentionIntentCodec.encode(handles[1]))
        assertTrue(
            DurableAttentionNotificationPolicy.notificationTag(handles[0]) !=
                DurableAttentionNotificationPolicy.notificationTag(handles[1]),
        )
        assertNull(DurableAttentionIntentCodec.parse("nanidroid://durable-operation/Aa/01"))
        assertNull(DurableAttentionIntentCodec.parse("nanidroid://durable-operation/Aa/1?extra=true"))
        assertNull(DurableAttentionIntentCodec.parse("nanidroid://durable-operation//1"))
        assertNull(DurableAttentionIntentCodec.parse("https://durable-operation/Aa/1"))
    }

    @Test fun notificationPolicyUsesOnlyActionsValidForCurrentStoppingVariant() {
        val running = record(OperationStatus.RUNNING)
        val stopping = record(OperationStatus.CANCEL_REQUESTED)
        val retryStop = stopping.copy(diagnostics = CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX)

        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING, DurableAttentionAction.STOP),
            DurableAttentionNotificationPolicy.actions(running),
        )
        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING),
            DurableAttentionNotificationPolicy.actions(stopping),
        )
        assertEquals(
            listOf(DurableAttentionAction.KEEP_WAITING, DurableAttentionAction.RETRY_STOP),
            DurableAttentionNotificationPolicy.actions(retryStop),
        )
    }

    @Test fun permissionAndChannelStateNeverAffectDurableActionability() {
        assertTrue(DurableAttentionNotificationPolicy.canPost(32, false, true))
        assertTrue(DurableAttentionNotificationPolicy.canPost(37, true, true))
        assertFalse(DurableAttentionNotificationPolicy.canPost(37, false, true))
        assertFalse(DurableAttentionNotificationPolicy.canPost(37, true, false))
        assertFalse(DurableAttentionNotificationPolicy.canPost(37, true, true, false))
        assertTrue(
            DurableAttentionNotificationPolicy.shouldRequestPermission(37, false, true, true),
        )
        assertFalse(
            DurableAttentionNotificationPolicy.shouldRequestPermission(37, false, false, true),
        )
        assertFalse(
            DurableAttentionNotificationPolicy.shouldRequestPermission(37, false, true, false),
        )
        assertFalse(
            DurableAttentionNotificationPolicy.shouldRequestPermission(32, false, true, true),
        )
    }

    private fun record(status: OperationStatus) = DurableOperationRecord(
        id = handle.operationId,
        attemptId = handle.attemptId,
        kind = OperationKind.REMOTE_NAR,
        externalJob = binding,
        progress = OperationProgress("Stopping...", 0L),
        status = status,
        showStallPrompt = true,
    )

    private class MutableClock(var value: Long = 0L) : MonotonicClock {
        override fun nowMillis() = value
    }

    private class FakeAttentionScheduler : DurableAttentionScheduler {
        var delayMillis: Long? = null
            private set
        private var pending: Runnable? = null

        override fun schedule(delayMillis: Long, task: Runnable) {
            this.delayMillis = delayMillis
            pending = task
        }

        override fun cancel() {
            delayMillis = null
            pending = null
        }

        fun runPending() {
            val task = pending ?: return
            pending = null
            delayMillis = null
            task.run()
        }
    }

    private class RecordingAttentionNotifier : DurableAttentionNotifier {
        var last = emptyList<DurableOperationRecord>()
        var reconciliationCount = 0
        override fun reconcile(stalled: List<DurableOperationRecord>) {
            reconciliationCount += 1
            last = stalled
        }
    }
}
