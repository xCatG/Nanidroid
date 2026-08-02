package com.cattailsw.nanidroid.install

import com.cattailsw.nanidroid.durable.DurableOperationSupervisor
import com.cattailsw.nanidroid.durable.OperationHandle

/** Publishes progress from one exact DownloadManager row. */
internal class DownloadManagerProgressObserver(
    private val downloads: NarDownloadGateway,
    private val supervisor: DurableOperationSupervisor,
) {
    fun observe(handle: OperationHandle, downloadManagerId: Long): Boolean {
        val completed = downloads.downloadedBytes(downloadManagerId) ?: return false
        if (completed < 0L) return false
        return supervisor.reportProgress(handle, "Downloading archive", completed)
    }
}
