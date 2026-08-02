package com.cattailsw.nanidroid.install

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Hands known DownloadManager completion broadcasts to unique install work. */
class NarDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadManagerId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadManagerId < 0L) return
        NarDownloadRepository.get(context).onDownloadComplete(downloadManagerId)
    }
}
