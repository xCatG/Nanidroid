package com.cattailsw.nanidroid.install

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.cattailsw.nanidroid.durable.AttemptId
import com.cattailsw.nanidroid.durable.OperationHandle
import com.cattailsw.nanidroid.durable.OperationId
import com.cattailsw.nanidroid.durable.OperationKind
import com.cattailsw.nanidroid.durable.durableWorkManagerId
import java.util.UUID

class InstallNarWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val itemId = inputData.getString(INPUT_ITEM_ID) ?: return Result.success()
        val attemptId = inputData.getLong(INPUT_ATTEMPT_ID, NO_ATTEMPT)
        val workManagerId = id.toString()
        return if (attemptId == NO_ATTEMPT) {
            execute(NarDownloadRepository.get(applicationContext), itemId, workManagerId) { isStopped }
        } else {
            execute(
                NarDownloadRepository.get(applicationContext),
                itemId,
                attemptId,
                workManagerId,
            ) { isStopped }
        }
    }

    override fun onStopped() {
        val itemId = inputData.getString(INPUT_ITEM_ID)
        val attemptId = inputData.getLong(INPUT_ATTEMPT_ID, NO_ATTEMPT)
        if (itemId != null && attemptId != NO_ATTEMPT) {
            NarDownloadRepository.get(applicationContext).workerStopped(
                itemId,
                attemptId,
                id.toString(),
            )
        }
        super.onStopped()
    }

    companion object {
        internal const val INPUT_ITEM_ID = "nar-download-item-id"
        internal const val INPUT_ATTEMPT_ID = "nar-download-attempt-id"
        private const val NO_ATTEMPT = -1L
        internal const val ATTEMPT_TAG_PREFIX = "nar-install-attempt:"

        internal fun execute(
            repository: NarDownloadRepository,
            itemId: String,
            workManagerId: String,
            isStopped: () -> Boolean,
        ): ListenableWorker.Result {
            return if (repository.install(itemId, workManagerId, isStopped)) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.retry()
            }
        }

        internal fun execute(
            repository: NarDownloadRepository,
            itemId: String,
            attemptId: Long,
            workManagerId: String,
            isStopped: () -> Boolean,
        ): ListenableWorker.Result {
            return if (repository.install(itemId, attemptId, workManagerId, isStopped)) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.retry()
            }
        }
    }
}

internal class AndroidNarInstallWorkScheduler(context: Context) : NarInstallWorkScheduler {
    private val applicationContext = context.applicationContext
    private val workManager by lazy { WorkManager.getInstance(applicationContext) }

    override fun enqueue(itemId: String) {
        val request = OneTimeWorkRequestBuilder<InstallNarWorker>()
            .setInputData(
                Data.Builder()
                    .putString(InstallNarWorker.INPUT_ITEM_ID, itemId)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(
            NarDownloadRepository.workName(itemId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun findActiveLegacyInstallWork(itemId: String): String? =
        workManager.getWorkInfosForUniqueWork(NarDownloadRepository.workName(itemId)).get()
            .firstOrNull { workInfo ->
                workInfo.tags.none { it.startsWith(InstallNarWorker.ATTEMPT_TAG_PREFIX) } &&
                    when (workInfo.state) {
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.RUNNING,
                        WorkInfo.State.BLOCKED,
                        -> true
                        WorkInfo.State.SUCCEEDED,
                        WorkInfo.State.FAILED,
                        WorkInfo.State.CANCELLED,
                        -> false
                    }
            }
            ?.id
            ?.toString()

    override fun enqueue(
        itemId: String,
        attemptId: Long,
        onPrepared: (workManagerId: String) -> Boolean,
    ): Boolean {
        val request = installRequest(itemId, attemptId)
        if (!onPrepared(request.id.toString())) return false
        workManager.enqueueUniqueWork(
            NarDownloadRepository.workName(itemId),
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    override fun ensureInstallEnqueued(
        itemId: String,
        attemptId: Long,
        workManagerId: String,
        recreateIfMissing: Boolean,
    ): NarInstallWorkRecovery {
        val requestId = UUID.fromString(workManagerId)
        val workInfo = workManager.getWorkInfoById(requestId).get()
        if (workInfo != null) {
            return when (workInfo.state) {
                WorkInfo.State.SUCCEEDED -> NarInstallWorkRecovery.SUCCEEDED
                WorkInfo.State.FAILED -> NarInstallWorkRecovery.FAILED
                WorkInfo.State.CANCELLED -> NarInstallWorkRecovery.CANCELLED
                else -> NarInstallWorkRecovery.ACTIVE
            }
        }
        if (!recreateIfMissing) return NarInstallWorkRecovery.MISSING
        workManager.enqueueUniqueWork(
            NarDownloadRepository.workName(itemId),
            ExistingWorkPolicy.KEEP,
            installRequest(itemId, attemptId, requestId),
        )
        return NarInstallWorkRecovery.ACTIVE
    }

    private fun installRequest(
        itemId: String,
        attemptId: Long,
        requestId: UUID? = null,
    ) = OneTimeWorkRequestBuilder<InstallNarWorker>()
        .setId(
            requestId ?: durableWorkManagerId(
                OperationHandle(OperationId(itemId), AttemptId(attemptId)),
                OperationKind.NAR_INSTALL,
            ),
        )
        .setInputData(
            Data.Builder()
                .putString(InstallNarWorker.INPUT_ITEM_ID, itemId)
                .putLong(InstallNarWorker.INPUT_ATTEMPT_ID, attemptId)
                .build(),
        )
        .addTag("${InstallNarWorker.ATTEMPT_TAG_PREFIX}$attemptId")
        .build()

    override fun enqueueStage(
        itemId: String,
        attemptId: Long,
        onPrepared: (workManagerId: String) -> Boolean,
    ): Boolean {
        val request = stageRequest(itemId, attemptId)
        if (!onPrepared(request.id.toString())) return false
        workManager.enqueueUniqueWork(
            NarDownloadRepository.stageWorkName(itemId),
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    override fun ensureStageEnqueued(
        itemId: String,
        attemptId: Long,
        workManagerId: String,
        recreateIfMissing: Boolean,
    ): NarStageWorkRecovery {
        val requestId = UUID.fromString(workManagerId)
        val workInfo = workManager.getWorkInfoById(requestId).get()
        if (workInfo != null) {
            return when (workInfo.state) {
                WorkInfo.State.SUCCEEDED -> NarStageWorkRecovery.SUCCEEDED
                WorkInfo.State.FAILED -> NarStageWorkRecovery.FAILED
                WorkInfo.State.CANCELLED -> NarStageWorkRecovery.CANCELLED
                else -> NarStageWorkRecovery.ACTIVE
            }
        }
        if (!recreateIfMissing) return NarStageWorkRecovery.MISSING
        workManager.enqueueUniqueWork(
            NarDownloadRepository.stageWorkName(itemId),
            ExistingWorkPolicy.KEEP,
            stageRequest(itemId, attemptId, requestId),
        )
        return NarStageWorkRecovery.ACTIVE
    }

    private fun stageRequest(
        itemId: String,
        attemptId: Long,
        requestId: UUID? = null,
    ) = OneTimeWorkRequestBuilder<StageLocalNarWorker>()
        .setId(
            requestId ?: durableWorkManagerId(
                OperationHandle(OperationId(itemId), AttemptId(attemptId)),
                OperationKind.LOCAL_NAR,
            ),
        )
        .setInputData(
            Data.Builder()
                .putString(StageLocalNarWorker.INPUT_ITEM_ID, itemId)
                .putLong(StageLocalNarWorker.INPUT_ATTEMPT_ID, attemptId)
                .build(),
        )
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()

    override fun cancel(itemId: String) {
        workManager.cancelUniqueWork(NarDownloadRepository.workName(itemId))
        workManager.cancelUniqueWork(NarDownloadRepository.stageWorkName(itemId))
    }
}
