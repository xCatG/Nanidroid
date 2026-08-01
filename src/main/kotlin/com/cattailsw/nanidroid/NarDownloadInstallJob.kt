package com.cattailsw.nanidroid

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors

/** Performs potentially long archive staging and installation outside a broadcast receiver. */
class NarDownloadInstallJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getLong(NarDownloadReceiver.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0L) return false
        executor.execute {
            try {
                NarDownloadManager.handleCompletedDownload(applicationContext, id)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}
