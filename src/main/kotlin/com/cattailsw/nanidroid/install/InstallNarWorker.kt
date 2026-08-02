package com.cattailsw.nanidroid.install

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

class InstallNarWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val itemId = inputData.getString(INPUT_ITEM_ID) ?: return Result.success()
        val attemptId = inputData.getLong(INPUT_ATTEMPT_ID, NO_ATTEMPT)
        return if (attemptId == NO_ATTEMPT) {
            execute(NarDownloadRepository.get(applicationContext), itemId) { isStopped }
        } else {
            execute(NarDownloadRepository.get(applicationContext), itemId, attemptId) { isStopped }
        }
    }

    override fun onStopped() {
        val itemId = inputData.getString(INPUT_ITEM_ID)
        val attemptId = inputData.getLong(INPUT_ATTEMPT_ID, NO_ATTEMPT)
        if (itemId != null && attemptId != NO_ATTEMPT) {
            NarDownloadRepository.get(applicationContext).workerStopped(itemId, attemptId)
        }
        super.onStopped()
    }

    companion object {
        internal const val INPUT_ITEM_ID = "nar-download-item-id"
        internal const val INPUT_ATTEMPT_ID = "nar-download-attempt-id"
        private const val NO_ATTEMPT = -1L

        internal fun execute(
            repository: NarDownloadRepository,
            itemId: String,
            isStopped: () -> Boolean,
        ): ListenableWorker.Result {
            repository.install(itemId, isStopped)
            return ListenableWorker.Result.success()
        }

        internal fun execute(
            repository: NarDownloadRepository,
            itemId: String,
            attemptId: Long,
            isStopped: () -> Boolean,
        ): ListenableWorker.Result {
            repository.install(itemId, attemptId, isStopped)
            return ListenableWorker.Result.success()
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

    override fun enqueue(
        itemId: String,
        attemptId: Long,
        onPrepared: (workManagerId: String) -> Boolean,
    ): Boolean {
        val request = OneTimeWorkRequestBuilder<InstallNarWorker>()
            .setInputData(
                Data.Builder()
                    .putString(InstallNarWorker.INPUT_ITEM_ID, itemId)
                    .putLong(InstallNarWorker.INPUT_ATTEMPT_ID, attemptId)
                    .build(),
            )
            .build()
        if (!onPrepared(request.id.toString())) return false
        workManager.enqueueUniqueWork(
            NarDownloadRepository.workName(itemId),
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    override fun enqueueStage(
        itemId: String,
        attemptId: Long,
        onPrepared: (workManagerId: String) -> Boolean,
    ): Boolean {
        val request = OneTimeWorkRequestBuilder<StageLocalNarWorker>()
            .setInputData(
                Data.Builder()
                    .putString(StageLocalNarWorker.INPUT_ITEM_ID, itemId)
                    .putLong(StageLocalNarWorker.INPUT_ATTEMPT_ID, attemptId)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        if (!onPrepared(request.id.toString())) return false
        workManager.enqueueUniqueWork(
            NarDownloadRepository.stageWorkName(itemId),
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    override fun cancel(itemId: String) {
        workManager.cancelUniqueWork(NarDownloadRepository.workName(itemId))
        workManager.cancelUniqueWork(NarDownloadRepository.stageWorkName(itemId))
    }
}
