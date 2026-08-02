package com.cattailsw.nanidroid.install

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

class InstallNarWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val itemId = inputData.getString(INPUT_ITEM_ID) ?: return Result.success()
        return execute(NarDownloadRepository.get(applicationContext), itemId) { isStopped }
    }

    companion object {
        internal const val INPUT_ITEM_ID = "nar-download-item-id"

        internal fun execute(
            repository: NarDownloadRepository,
            itemId: String,
            isStopped: () -> Boolean,
        ): ListenableWorker.Result {
            repository.install(itemId, isStopped)
            return ListenableWorker.Result.success()
        }
    }
}

internal class AndroidNarInstallWorkScheduler(context: Context) : NarInstallWorkScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

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

    override fun cancel(itemId: String) {
        workManager.cancelUniqueWork(NarDownloadRepository.workName(itemId))
    }
}
