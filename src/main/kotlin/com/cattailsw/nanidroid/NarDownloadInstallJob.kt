package com.cattailsw.nanidroid

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Performs potentially long archive staging and installation outside a broadcast receiver. */
class NarDownloadInstallJob : JobService() {
    @Volatile private var stopped = false
    @Volatile private var running: Future<*>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getLong(NarDownloadReceiver.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0L) return false
        stopped = false
        running = executor.submit {
            var shouldRetry = true
            try {
                shouldRetry = NarDownloadManager.handleCompletedDownload(applicationContext, id)
            } finally {
                if (!stopped) jobFinished(params, shouldRetry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        running?.cancel(true)
        return true
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}
