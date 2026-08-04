package com.cattailsw.nanidroid.durable

import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.install.NarDownloadRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SharedDurableOperationSupervisorTest {
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
        assertTrue(works.byUniqueName.isEmpty())
    }

    @Test fun androidCancellationTreatsNonCanonicalUuidTextAsMalformed() {
        val works = RecordingWorkManagerCancellation()
        val cancellation = SharedDurableOperationSupervisor.AndroidDurableOperationCancellation(
            RecordingDownloadManagerCancellation(),
            works,
        )

        cancellation.cancel(
            OperationHandle(OperationId("install-item"), AttemptId(1)),
            OperationKind.NAR_INSTALL,
            ExternalJobBinding.WorkManager("1-1-1-1-1"),
        )

        assertTrue(works.byId.isEmpty())
        assertEquals(listOf(NarDownloadRepository.workName("install-item")), works.byUniqueName)
    }

    @Test fun androidCancellationFallsBackToKindWorkNameOnMalformedWorkManagerBinding() {
        val downloads = RecordingDownloadManagerCancellation()
        val works = RecordingWorkManagerCancellation()
        val cancellation = SharedDurableOperationSupervisor.AndroidDurableOperationCancellation(
            downloads,
            works,
        )

        cancellation.cancel(
            OperationHandle(OperationId("install-item"), AttemptId(1)),
            OperationKind.NAR_INSTALL,
            ExternalJobBinding.WorkManager("not-a-valid-uuid"),
        )
        assertEquals(listOf(NarDownloadRepository.workName("install-item")), works.byUniqueName)

        cancellation.cancel(
            OperationHandle(OperationId("copy-item"), AttemptId(2)),
            OperationKind.LOCAL_NAR,
            ExternalJobBinding.WorkManager("also-not-a-uuid"),
        )
        assertEquals(
            listOf(
                NarDownloadRepository.workName("install-item"),
                NarDownloadRepository.stageWorkName("copy-item"),
            ),
            works.byUniqueName,
        )

        val ghostId = OperationId("ghost-update-${"f".repeat(64)}")
        cancellation.cancel(
            OperationHandle(ghostId, AttemptId(3)),
            OperationKind.GHOST_UPDATE,
            ExternalJobBinding.WorkManager("still-not-a-valid-uuid"),
        )
        assertEquals(
            GhostUpdateWorker.recoveryWorkName(ghostId),
            works.byUniqueName.last(),
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
    val byUniqueName = mutableListOf<String>()

    override fun cancel(workManagerId: UUID) {
        byId += workManagerId
    }

    override fun cancel(uniqueWorkName: String) {
        byUniqueName += uniqueWorkName
    }
}
