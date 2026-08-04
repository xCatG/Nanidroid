package com.cattailsw.nanidroid.durable

import android.app.DownloadManager
import android.content.Context
import androidx.work.WorkManager
import com.cattailsw.nanidroid.di.MonotonicClock
import java.util.UUID

internal object SharedDurableOperationSupervisor {
    @Volatile private var appSupervisor: DurableOperationSupervisor? = null

    fun get(context: Context): DurableOperationSupervisor = synchronized(this) {
        appSupervisor ?: create(context).also { appSupervisor = it }
    }

    @JvmStatic
    internal fun resetForTesting() = synchronized(this) { appSupervisor = null }

    @JvmStatic
    internal fun replaceForTesting(supervisor: DurableOperationSupervisor) = synchronized(this) {
        appSupervisor = supervisor
    }

    internal fun get(
        context: Context,
        cancellation: OperationCancellation,
    ): DurableOperationSupervisor = synchronized(this) {
        appSupervisor ?: create(context, cancellation).also { appSupervisor = it }
    }

    private fun create(context: Context): DurableOperationSupervisor = create(
        context,
        AndroidDurableOperationCancellation(context),
    )

    private fun create(context: Context, cancellation: OperationCancellation): DurableOperationSupervisor = DurableOperationSupervisor(
        SharedPreferencesDurableOperationStore(context.applicationContext),
        MonotonicClock { android.os.SystemClock.elapsedRealtime() },
        cancellation,
    )

    internal class AndroidDurableOperationCancellation(context: Context) :
        OperationCancellation {
        private val appContext = context.applicationContext
        private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
        private val workManager by lazy { WorkManager.getInstance(appContext) }

        override fun cancel(handle: OperationHandle, binding: ExternalJobBinding) {
            when (binding) {
                is ExternalJobBinding.DownloadManager -> {
                    if (downloadManager == null) {
                        throw IllegalStateException("platform cancellation request failed")
                    }
                    downloadManager.remove(binding.id)
                }
                is ExternalJobBinding.WorkManager -> {
                    val uuid = UUID.fromString(binding.uuid)
                    workManager.cancelWorkById(uuid)
                }
            }
        }
    }
}
