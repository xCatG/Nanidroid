package com.cattailsw.nanidroid.durable

import com.cattailsw.nanidroid.di.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SharedDurableOperationSupervisorTest {
    @Test fun createForTestingRetriesTransientResetFailureAndResolvesSameProcess() {
        val raw = "v1\n${"x".repeat(40_000)}"
        val storage = RecoveringStorage(raw)
        val store = SharedPreferencesDurableOperationStore(storage)
        val state = SharedDurableOperationSupervisor.createForTesting(
            store,
            MonotonicClock { 0L },
            NoopOperationCancellation,
        )

        assertTrue(state.isRecoveryRequired())
        assertEquals(emptyList<DurableOperationRecord>(), state.store!!.read())
        val record = DurableOperationRecord(
            id = OperationId("resolved"),
            attemptId = AttemptId(1),
            kind = OperationKind.GHOST_UPDATE,
            externalJob = null,
            progress = OperationProgress("Queued", 0),
            status = OperationStatus.RUNNING,
            showStallPrompt = false,
        )
        assertFalse(state.store.putIfAbsent(record))
        assertTrue(state.resolveRecovery())
        assertFalse(state.isRecoveryRequired())
        assertEquals("v2", storage.value)
        assertEquals(raw.take(16_384), storage.lastQuarantinedPayload)
        assertNull(storage.quarantine)

        assertTrue(state.store.putIfAbsent(record))
        assertTrue(state.store.compareAndSet(record, record.copy(status = OperationStatus.COMPLETED)))
    }

    @Test fun pureFactoryCatchesCorruptionAndPublishesRecoveryState() {
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage("v1\nwyg\n"),
        )

        val state = SharedDurableOperationSupervisor.createForTesting(
            store,
            MonotonicClock { 0L },
            NoopOperationCancellation,
        )

        assertTrue(state.isRecoveryRequired())
        assertEquals(emptyList<DurableOperationRecord>(), state.store!!.read())
        assertEquals(emptyList<DurableOperationRecord>(), state.supervisor.snapshot())
    }

    @Test fun pureFactoryRecoveryResolutionReopensTheOwnedStore() {
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage("v1\nwyg\n"),
        )
        val state = SharedDurableOperationSupervisor.createForTesting(
            store,
            MonotonicClock { 0L },
            NoopOperationCancellation,
        )

        assertTrue(state.resolveRecovery())
        assertFalse(state.isRecoveryRequired())
        val record = DurableOperationRecord(
            id = OperationId("supervisor-1"),
            attemptId = AttemptId(1),
            kind = OperationKind.GHOST_UPDATE,
            externalJob = null,
            progress = OperationProgress("Queued", 0),
            status = OperationStatus.RUNNING,
            showStallPrompt = false,
        )
        assertTrue(store.putIfAbsent(record))
        assertEquals(listOf(record), state.supervisor.snapshot())
    }

    @Test fun pureFactoryLeavesHealthyStoreHealthy() {
        val state = SharedDurableOperationSupervisor.createForTesting(
            SharedPreferencesDurableOperationStore(
                SharedPreferencesDurableOperationStore.MemoryStorage("v2"),
            ),
            MonotonicClock { 0L },
            NoopOperationCancellation,
        )

        assertFalse(state.isRecoveryRequired())
        assertEquals(emptyList<DurableOperationRecord>(), state.supervisor.snapshot())
    }

    @Test fun androidCancellationUsesCanonicalWorkManagerUuidForValidBinding() {
        val downloads = RecordingDownloadManagerCancellation()
        val works = RecordingWorkManagerCancellation()
        val cancellation = SharedDurableOperationSupervisor.AndroidDurableOperationCancellation(
            downloads,
            works,
        )
        val id = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val handle = OperationHandle(OperationId("ghost-update-target"), AttemptId(1))

        cancellation.cancel(
            handle,
            OperationKind.GHOST_UPDATE,
            ExternalJobBinding.WorkManager(id.toString()),
        )

        assertEquals(listOf(id), works.byId)
    }

    @Test fun androidCancellationTreatsNonCanonicalUuidTextAsMalformed() {
        val works = RecordingWorkManagerCancellation()
        val cancellation = SharedDurableOperationSupervisor.AndroidDurableOperationCancellation(
            RecordingDownloadManagerCancellation(),
            works,
        )

        assertThrows(IllegalArgumentException::class.java) {
            cancellation.cancel(
                OperationHandle(OperationId("install-item"), AttemptId(1)),
                OperationKind.NAR_INSTALL,
                ExternalJobBinding.WorkManager("1-1-1-1-1"),
            )
        }

        assertTrue(works.byId.isEmpty())
    }

    @Test fun durableWorkManagerIdsAreStableAndExactAttemptScoped() {
        val handle = OperationHandle(OperationId("same-item"), AttemptId(7))
        val expected = durableWorkManagerId(handle, OperationKind.NAR_INSTALL)

        assertEquals(expected, durableWorkManagerId(handle, OperationKind.NAR_INSTALL))
        assertTrue(expected != durableWorkManagerId(handle.copy(attemptId = AttemptId(8)), OperationKind.NAR_INSTALL))
        assertTrue(expected != durableWorkManagerId(handle, OperationKind.LOCAL_NAR))
        assertTrue(
            expected != durableWorkManagerId(
                handle.copy(operationId = OperationId("other-item")),
                OperationKind.NAR_INSTALL,
            ),
        )
    }

    @Test fun androidCancellationRejectsMalformedWorkManagerBindingForInvalidKinds() {
        val cancellation = SharedDurableOperationSupervisor.AndroidDurableOperationCancellation(
            RecordingDownloadManagerCancellation(),
            RecordingWorkManagerCancellation(),
        )

        assertThrows(
            IllegalArgumentException::class.java,
        ) {
            cancellation.cancel(
                OperationHandle(OperationId("remote-item"), AttemptId(1)),
                OperationKind.REMOTE_NAR,
                ExternalJobBinding.WorkManager("not-a-valid-uuid"),
            )
        }
        assertThrows(
            IllegalArgumentException::class.java,
        ) {
            cancellation.cancel(
                OperationHandle(OperationId("ghost-update-not-valid"), AttemptId(1)),
                OperationKind.GHOST_UPDATE,
                ExternalJobBinding.WorkManager("not-a-valid-uuid"),
            )
        }
    }
}

private object NoopOperationCancellation : OperationCancellation {
    override fun cancel(handle: OperationHandle, kind: OperationKind, binding: ExternalJobBinding) = Unit
}

private class RecordingDownloadManagerCancellation : SharedDurableOperationSupervisor.DownloadManagerCancellationGateway {
    override fun cancel(downloadManagerId: Long): Unit = Unit
}

private class RecordingWorkManagerCancellation : SharedDurableOperationSupervisor.WorkManagerCancellationGateway {
    val byId = mutableListOf<UUID>()

    override fun cancel(workManagerId: UUID) {
        byId += workManagerId
    }
}

private class RecoveringStorage(initialValue: String) : SharedPreferencesDurableOperationStore.Storage {
    var value = initialValue
    var quarantine: String? = null
    var recoveryMarker = false
    var lastQuarantinedPayload: String? = null
    private var quarantineAndResetAttempts = 0

    override fun read() = value

    override fun write(value: String) {
        this.value = value
    }

    override fun readQuarantine() = quarantine

    override fun hasRecoveryMarker() = recoveryMarker

    override fun writeQuarantine(value: String) {
        quarantine = value
    }

    override fun writeQuarantineAndReset(value: String) {
        if (quarantineAndResetAttempts++ == 0) throw RuntimeException("transient failure")
        quarantine = value.take(16_384)
        lastQuarantinedPayload = quarantine
        recoveryMarker = true
        this.value = "v2"
    }

    override fun clearQuarantine() {
        quarantine = null
        recoveryMarker = false
    }
}
