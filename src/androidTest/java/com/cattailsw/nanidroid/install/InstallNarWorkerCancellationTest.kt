package com.cattailsw.nanidroid.install

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.durable.AttemptId
import com.cattailsw.nanidroid.durable.DurableOperationSupervisor
import com.cattailsw.nanidroid.durable.ExternalJobBinding
import com.cattailsw.nanidroid.durable.OperationHandle
import com.cattailsw.nanidroid.durable.OperationId
import com.cattailsw.nanidroid.durable.OperationKind
import com.cattailsw.nanidroid.durable.SharedPreferencesDurableOperationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class InstallNarWorkerCancellationTest {
    @Test
    fun cancellingInstallAttemptCancelsOnlyItsExactWorkAndPersistsCancelled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        val workManager = WorkManager.getInstance(context)
        val itemId = "cancel-${UUID.randomUUID()}"
        val request = delayedInstallRequest(itemId, 1L)
        val unrelated = delayedInstallRequest("unrelated-${UUID.randomUUID()}", 1L)
        workManager.enqueueUniqueWork(
            NarDownloadRepository.workName(itemId),
            ExistingWorkPolicy.KEEP,
            request,
        ).result.get(5, TimeUnit.SECONDS)
        workManager.enqueueUniqueWork(
            NarDownloadRepository.workName("unrelated"),
            ExistingWorkPolicy.KEEP,
            unrelated,
        ).result.get(5, TimeUnit.SECONDS)

        val store = NarDownloadStore(NarDownloadStore.MemoryStorage())
        val record = store.create(
            NarDownload(
                id = itemId,
                source = NarDownloadSource.Local("file:///owned/archive.nar"),
                attemptId = 1L,
                workManagerId = request.id.toString(),
                state = NarDownloadState.Installing,
            ),
        )
        val supervisor = DurableOperationSupervisor(
            SharedPreferencesDurableOperationStore(
                SharedPreferencesDurableOperationStore.MemoryStorage(),
            ),
            MonotonicClock { 0L },
        ) { _, _, binding ->
            if (binding is ExternalJobBinding.WorkManager) {
                workManager.cancelWorkById(UUID.fromString(binding.uuid))
            }
        }
        supervisor.start(
            OperationHandle(OperationId(itemId), AttemptId(record.attemptId)),
            OperationKind.NAR_INSTALL,
            "Installing archive",
            0L,
            ExternalJobBinding.WorkManager(request.id.toString()),
        )
        val repository = repository(store, supervisor, workManager)

        repository.stop(itemId)

        val cancelled = workManager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS)
        val untouched = workManager.getWorkInfoById(unrelated.id).get(5, TimeUnit.SECONDS)
        assertEquals(WorkInfo.State.CANCELLED, cancelled!!.state)
        assertNotEquals(WorkInfo.State.CANCELLED, untouched!!.state)
        repository.reconcile()
        assertEquals(NarDownloadState.Cancelled, repository.observeDownloads().value.single().state)
    }

    private fun delayedInstallRequest(itemId: String, attemptId: Long) =
        OneTimeWorkRequestBuilder<InstallNarWorker>()
            .setInputData(
                Data.Builder()
                    .putString(InstallNarWorker.INPUT_ITEM_ID, itemId)
                    .putLong(InstallNarWorker.INPUT_ATTEMPT_ID, attemptId)
                    .build(),
            )
            .setInitialDelay(1L, TimeUnit.DAYS)
            .build()

    private fun repository(
        store: NarDownloadStore,
        supervisor: DurableOperationSupervisor,
        workManager: WorkManager,
    ) = NarDownloadRepository(
        store = store,
        downloads = object : NarDownloadGateway {
            override fun intendedRetainedUri(itemId: String) = "file:///owned/$itemId.nar"
            override fun enqueue(itemId: String, normalizedHttpsUrl: String) =
                NarRemoteEnqueue(1L, intendedRetainedUri(itemId))
            override fun findDownloadId(retainedUri: String): Long? = null
            override fun remove(downloadManagerId: Long) = Unit
            override fun status(downloadManagerId: Long): NarRemoteDownloadStatus? = null
        },
        work = object : NarInstallWorkScheduler {
            override fun enqueue(itemId: String) = Unit
            override fun cancel(itemId: String) = Unit
            override fun ensureInstallEnqueued(
                itemId: String,
                attemptId: Long,
                workManagerId: String,
                recreateIfMissing: Boolean,
            ) = when (
                workManager.getWorkInfoById(UUID.fromString(workManagerId))
                    .get(5, TimeUnit.SECONDS)
                    ?.state
            ) {
                WorkInfo.State.SUCCEEDED -> NarInstallWorkRecovery.SUCCEEDED
                WorkInfo.State.FAILED -> NarInstallWorkRecovery.FAILED
                WorkInfo.State.CANCELLED -> NarInstallWorkRecovery.CANCELLED
                null -> NarInstallWorkRecovery.MISSING
                else -> NarInstallWorkRecovery.ACTIVE
            }
            override fun ensureStageEnqueued(
                itemId: String,
                attemptId: Long,
                workManagerId: String,
                recreateIfMissing: Boolean,
            ) = NarStageWorkRecovery.ACTIVE
        },
        installer = object : NarArchiveInstaller {
            override fun install(
                download: NarDownload,
                stagingDirectory: File,
                isStopped: () -> Boolean,
            ) = ArchiveInstallResult.Cancelled
        },
        ownedData = object : NarOwnedDownloadData {
            override fun delete(download: NarDownload) = Unit
        },
        attemptPaths = object : NarInstallAttemptPaths {
            override fun create(itemId: String) = contextFile(itemId)
        },
        supervisor = supervisor,
        nextId = { "unused" },
    )

    private fun contextFile(itemId: String) =
        File(ApplicationProvider.getApplicationContext<Context>().cacheDir, itemId)
}
