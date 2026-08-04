package com.cattailsw.nanidroid.durable

import android.app.DownloadManager
import android.content.Context
import androidx.work.WorkManager
import com.cattailsw.nanidroid.di.MonotonicClock
import java.util.UUID

internal object SharedDurableOperationSupervisor {
    @Volatile private var appState: AppState? = null

    fun get(context: Context): DurableOperationSupervisor = synchronized(this) {
        appState?.supervisor ?: create(context).also { appState = it }.supervisor
    }

    @JvmStatic
    internal fun resetForTesting() = synchronized(this) {
        appState = null
    }

    @JvmStatic
    internal fun replaceForTesting(supervisor: DurableOperationSupervisor) = synchronized(this) {
        appState = AppState(
            store = null,
            supervisor = supervisor,
        )
    }

    @JvmStatic
    internal fun get(
        context: Context,
        cancellation: OperationCancellation,
    ): DurableOperationSupervisor = synchronized(this) {
        appState?.supervisor ?: create(context, cancellation).also { appState = it }.supervisor
    }

    internal fun isRecoveryRequired(): Boolean = synchronized(this) {
        appState?.isRecoveryRequired() ?: false
    }

    internal fun resolveRecovery(): Boolean = synchronized(this) {
        appState?.resolveRecovery() ?: false
    }

    internal fun createForTesting(
        store: SharedPreferencesDurableOperationStore,
        clock: MonotonicClock,
        cancellation: OperationCancellation,
    ): AppState {
        try {
            store.read()
        } catch (_: DurableOperationStoreCorruptionException) {
            store.acknowledgeRecoverySignal()
        }
        return AppState(
            store = store,
            supervisor = DurableOperationSupervisor(
                store,
                clock,
                cancellation,
            ),
        )
    }

    private fun create(context: Context): AppState = createForTesting(
        store = SharedPreferencesDurableOperationStore(context.applicationContext),
        clock = MonotonicClock { android.os.SystemClock.elapsedRealtime() },
        cancellation = AndroidDurableOperationCancellation(context),
    )

    private fun create(
        context: Context,
        cancellation: OperationCancellation,
    ): AppState = createForTesting(
        store = SharedPreferencesDurableOperationStore(context.applicationContext),
        clock = MonotonicClock { android.os.SystemClock.elapsedRealtime() },
        cancellation = cancellation,
    )

    internal interface DownloadManagerCancellationGateway {
        fun cancel(downloadManagerId: Long)
    }

    internal interface WorkManagerCancellationGateway {
        fun cancel(workManagerId: UUID)
    }

    internal class AndroidDurableOperationCancellation : OperationCancellation {
        private val downloadGateway: DownloadManagerCancellationGateway
        private val workGateway: WorkManagerCancellationGateway

        constructor(
            context: Context,
        ) : this(
            downloadGateway = AndroidDownloadManagerCancellation(context.applicationContext),
            workGateway = AndroidWorkManagerCancellation(context.applicationContext),
        )

        internal constructor(
            downloadGateway: DownloadManagerCancellationGateway,
            workGateway: WorkManagerCancellationGateway,
        ) {
            this.downloadGateway = downloadGateway
            this.workGateway = workGateway
        }

        override fun cancel(
            handle: OperationHandle,
            kind: OperationKind,
            binding: ExternalJobBinding,
        ) {
            when (binding) {
                is ExternalJobBinding.DownloadManager -> downloadGateway.cancel(binding.id)
                is ExternalJobBinding.WorkManager -> {
                    val uuid = canonicalUuidOrNull(binding.uuid)
                        ?: throw IllegalArgumentException("invalid WorkManager binding")
                    workGateway.cancel(uuid)
                }
            }
        }
    }

    internal class AndroidDownloadManagerCancellation(context: Context) :
        DownloadManagerCancellationGateway {
        private val appContext = context.applicationContext
        private val downloadManager = appContext.getSystemService(DownloadManager::class.java)

        override fun cancel(downloadManagerId: Long) {
            if (downloadManager == null) {
                throw IllegalStateException("platform cancellation request failed")
            }
            downloadManager.remove(downloadManagerId)
        }
    }

    internal class AndroidWorkManagerCancellation(context: Context) :
        WorkManagerCancellationGateway {
        private val appContext = context.applicationContext
        private val workManager by lazy { WorkManager.getInstance(appContext) }

        override fun cancel(workManagerId: UUID) {
            workManager.cancelWorkById(workManagerId)
        }
    }

    internal data class AppState(
        val store: SharedPreferencesDurableOperationStore?,
        val supervisor: DurableOperationSupervisor,
    ) {
        fun isRecoveryRequired(): Boolean = store?.isRecoveryRequired() ?: false

        fun resolveRecovery(): Boolean = store?.resolveRecovery() ?: false
    }
}
