package com.cattailsw.nanidroid.install

import com.cattailsw.nanidroid.durable.DurableOperationSupervisor
import com.cattailsw.nanidroid.durable.ExternalJobBinding
import com.cattailsw.nanidroid.durable.OperationHandle
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal interface NarRemoteProgressObserver {
    fun start(handle: OperationHandle, downloadManagerId: Long)
    fun stop(handle: OperationHandle)
    fun observeOnce(handle: OperationHandle, downloadManagerId: Long): Boolean
}

internal interface NarProgressScheduler {
    fun post(task: Runnable, delayMillis: Long)
    fun cancel(task: Runnable)
}

internal interface NarStopReconciliationScheduler {
    fun schedule(handle: OperationHandle, delayMillis: Long, task: Runnable)
    fun cancel(handle: OperationHandle)

    data object None : NarStopReconciliationScheduler {
        override fun schedule(handle: OperationHandle, delayMillis: Long, task: Runnable) = Unit
        override fun cancel(handle: OperationHandle) = Unit
    }
}

internal class BackgroundStopReconciliationScheduler : NarStopReconciliationScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nanidroid-stop-reconciliation").apply { isDaemon = true }
    }
    private val pending = mutableMapOf<OperationHandle, PendingStopReconciliation>()

    @Synchronized
    override fun schedule(handle: OperationHandle, delayMillis: Long, task: Runnable) {
        pending.remove(handle)?.future?.cancel(false)
        val token = Any()
        val future = executor.schedule(reconcile@{
            synchronized(this) {
                if (pending[handle]?.token !== token) return@reconcile
                pending.remove(handle)
            }
            task.run()
        }, delayMillis, TimeUnit.MILLISECONDS)
        pending[handle] = PendingStopReconciliation(token, future)
    }

    @Synchronized
    override fun cancel(handle: OperationHandle) {
        pending.remove(handle)?.future?.cancel(false)
    }

    private data class PendingStopReconciliation(
        val token: Any,
        val future: ScheduledFuture<*>,
    )
}

private class BackgroundProgressScheduler : NarProgressScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nanidroid-download-progress").apply { isDaemon = true }
    }
    private val pending = mutableMapOf<Runnable, PendingProgressTask>()

    @Synchronized
    override fun post(task: Runnable, delayMillis: Long) {
        pending.remove(task)?.future?.cancel(false)
        val token = Any()
        val future = executor.schedule(run@{
            synchronized(this) {
                if (pending[task]?.token !== token) return@run
                pending.remove(task)
            }
            task.run()
        }, delayMillis, TimeUnit.MILLISECONDS)
        pending[task] = PendingProgressTask(token, future)
    }

    @Synchronized
    override fun cancel(task: Runnable) {
        pending.remove(task)?.future?.cancel(false)
    }

    private data class PendingProgressTask(
        val token: Any,
        val future: ScheduledFuture<*>,
    )
}

/** Publishes progress from one exact DownloadManager row. */
internal class DownloadManagerProgressObserver(
    private val downloads: NarDownloadGateway,
    private val supervisor: DurableOperationSupervisor,
    private val scheduler: NarProgressScheduler = BackgroundProgressScheduler(),
) : NarRemoteProgressObserver {
    private val observations = mutableMapOf<OperationHandle, Runnable>()

    @Synchronized
    override fun start(handle: OperationHandle, downloadManagerId: Long) {
        stop(handle)
        lateinit var observation: Runnable
        observation = Runnable {
            observeOnce(handle, downloadManagerId)
            val shouldContinue = runCatching { downloads.status(downloadManagerId) }
                .getOrNull() == NarRemoteDownloadStatus.InProgress
            synchronized(this) {
                if (observations[handle] !== observation) return@synchronized
                if (shouldContinue) {
                    scheduler.post(observation, POLL_INTERVAL_MILLIS)
                } else {
                    observations.remove(handle)
                }
            }
        }
        observations[handle] = observation
        scheduler.post(observation, 0L)
    }

    @Synchronized
    override fun stop(handle: OperationHandle) {
        observations.remove(handle)?.let(scheduler::cancel)
    }

    override fun observeOnce(handle: OperationHandle, downloadManagerId: Long): Boolean {
        val completed = downloads.downloadedBytes(downloadManagerId) ?: return false
        if (completed < 0L) return false
        return supervisor.reportProgress(
            handle,
            ExternalJobBinding.DownloadManager(downloadManagerId),
            "Downloading archive",
            completed,
        )
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_000L
    }
}
