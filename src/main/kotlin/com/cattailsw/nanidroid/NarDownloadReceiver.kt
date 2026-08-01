package com.cattailsw.nanidroid

import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log

/** Receives a system completion event and schedules the validated archive handoff. */
class NarDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0L) return
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val extras = PersistableBundle().apply { putLong(EXTRA_DOWNLOAD_ID, id) }
        val job = JobInfo.Builder(id.hashCode(), ComponentName(context, NarDownloadInstallJob::class.java))
            .setExtras(extras)
            .setPersisted(true)
            .build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            Log.w(TAG, "Could not schedule archive installation for $id")
        }
    }

    companion object {
        const val EXTRA_DOWNLOAD_ID = "download_id"
        private const val TAG = "NarDownloadReceiver"
    }
}
