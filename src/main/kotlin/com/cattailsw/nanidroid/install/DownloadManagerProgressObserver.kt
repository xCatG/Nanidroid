package com.cattailsw.nanidroid.install

import android.os.Handler
import android.os.Looper
import com.cattailsw.nanidroid.durable.DurableOperationSupervisor
import com.cattailsw.nanidroid.durable.ExternalJobBinding
import com.cattailsw.nanidroid.durable.OperationHandle

internal interface NarRemoteProgressObserver {
    fun start(handle: OperationHandle, downloadManagerId: Long)
    fun stop(handle: OperationHandle)
    fun observeOnce(handle: OperationHandle, downloadManagerId: Long): Boolean
}

internal interface NarProgressScheduler {
    fun post(task: Runnable, delayMillis: Long)
    fun cancel(task: Runnable)
}

private class MainLooperProgressScheduler : NarProgressScheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun post(task: Runnable, delayMillis: Long) {
        handler.postDelayed(task, delayMillis)
    }

    override fun cancel(task: Runnable) {
        handler.removeCallbacks(task)
    }
}

/** Publishes progress from one exact DownloadManager row. */
internal class DownloadManagerProgressObserver(
    private val downloads: NarDownloadGateway,
    private val supervisor: DurableOperationSupervisor,
    private val scheduler: NarProgressScheduler = MainLooperProgressScheduler(),
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
