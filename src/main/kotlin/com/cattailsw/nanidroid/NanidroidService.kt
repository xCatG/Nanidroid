package com.cattailsw.nanidroid

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.cattailsw.nanidroid.durable.DurableNotificationPermissionAcceptance
import com.cattailsw.nanidroid.durable.GhostUpdateWorker
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun dispatchGhostUpdateEnqueue(executor: Executor, task: () -> Unit) {
    executor.execute(task)
}

/** Fetches the legacy sensor sources in their established order and isolates source failures. */
internal fun fetchAndQueueSensorMessages(
    fetchSstp: () -> Collection<String>,
    fetchBottleLog: () -> Collection<String>,
    enqueue: (Collection<String>) -> Unit,
): Boolean = try {
    enqueue(fetchSstp())
    enqueue(fetchBottleLog())
    true
} catch (_: Exception) {
    false
}

/** Kotlin owner of foreground downloads, polling, and ghost updates. */
class NanidroidService : Service() {
    private var runner: SScriptRunner? = null
    private val activeForegroundStartIds = HashSet<Int>()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var sensingJob: Job? = null
    private val ghostUpdateEnqueueExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ghost-update-enqueue")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startHttpTask(initialDelayMillis: Long) {
        if (sensingJob?.isActive == true) return
        runner = SScriptRunner.getInstance(this)
        sensingJob = serviceScope.launch {
            delay(initialDelayMillis)
            while (isActive) {
                val start = System.currentTimeMillis()
                fetchAndQueueSensorMessages(
                    fetchSstp = { SSTPBottleSensor.getPageContent(this@NanidroidService) },
                    fetchBottleLog = { BottleLogSensor.getPageContent(this@NanidroidService) },
                    enqueue = { pageContent -> runner?.addMsgToQueue(pageContent) },
                )
                Log.d(TAG, "time = ${System.currentTimeMillis() - start} [ms]")
                withContext(Dispatchers.Main.immediate) {
                    runner?.run()
                }
                delay(DEF_TIME)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        handleCommand(intent, startId)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    override fun onStart(intent: Intent?, startId: Int) {
        super.onStart(intent, startId)
        handleCommand(intent, startId)
    }

    private fun handleCommand(intent: Intent?, startId: Int) {
        if (intent == null) {
            finishForegroundWork(startId)
            return
        }
        val action = intent.action
        when {
            action == null -> startHttpTask(1_000)
            action.equals(Intent.ACTION_RUN, ignoreCase = true) -> {
                Log.w(TAG, "Archive downloads are handled by NarDownloadRepository")
                finishForegroundWork(startId)
            }
            Intent.ACTION_SYNC.equals(action, ignoreCase = true) -> {
                val homeurl = intent.data
                val gid = intent.getStringExtra(EXT_GID)
                val ghostRoot = intent.getStringExtra(EXT_GROOT)
                if (isHttpsUri(homeurl) && gid != null && ghostRoot != null) {
                    startForegroundWork(startId)
                    dispatchGhostUpdateEnqueue(ghostUpdateEnqueueExecutor) {
                        try {
                            val accepted = GhostUpdateWorker.enqueue(this, homeurl!!, gid, File(ghostRoot))
                            if (accepted) DurableNotificationPermissionAcceptance.markAccepted()
                            if (!accepted) {
                                Log.w(TAG, "Ghost update is already active or could not be queued")
                            }
                        } catch (error: RuntimeException) {
                    Log.e(TAG, "Could not enqueue ghost update", error)
                        } finally {
                            finishForegroundWork(startId)
                        }
                    }
                } else {
                    Log.w(TAG, "Rejected update request without an HTTPS URL, ghost id, and root")
                    finishForegroundWork(startId)
                }
            }
            action.equals(ACTION_CAN_STOP, ignoreCase = true) -> finishForegroundWork(startId)
        }
    }

    private fun startForegroundWork(startId: Int) {
        synchronized(activeForegroundStartIds) { activeForegroundStartIds.add(startId) }
    }

    private fun finishForegroundWork(startId: Int) {
        val noForegroundWorkRemains = synchronized(activeForegroundStartIds) {
            activeForegroundStartIds.remove(startId)
            activeForegroundStartIds.isEmpty()
        }
        if (noForegroundWorkRemains) stopForeground(true)
        stopSelf(startId)
    }

    private fun ensureForeground() {
        if (Build.VERSION.SDK_INT >= 26) createNotificationChannelCompat()
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
    }

    private fun createForegroundNotification(): Notification {
        val launchIntent = Intent(this, Nanidroid::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this)
            .setSmallIcon(R.drawable.notification)
            .setContentTitle(getString(R.string.download_in_progress))
            .setContentText(getString(R.string.download_in_progress))
            .setContentIntent(contentIntent)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                builder.javaClass.getMethod("setChannelId", String::class.java).invoke(builder, CHANNEL_ID)
            } catch (e: Exception) {
                Log.w(TAG, "notification channel API unavailable", e)
            }
        }
        @Suppress("DEPRECATION")
        return builder.notification
    }

    private fun createNotificationChannelCompat() {
        try {
            val channelClass = Class.forName("android.app.NotificationChannel")
            val channel = channelClass.getConstructor(String::class.java, CharSequence::class.java, Int::class.javaPrimitiveType)
                .newInstance(CHANNEL_ID, getString(R.string.download_channel_name), 2)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.javaClass.getMethod("createNotificationChannel", channelClass).invoke(manager, channel)
        } catch (e: Exception) {
            Log.w(TAG, "notification channel API unavailable", e)
        }
    }

    override fun onDestroy() {
        sensingJob?.cancel()
        serviceJob.cancel()
        ghostUpdateEnqueueExecutor.shutdown()
        runner?.stop()
        Log.d(TAG, "onDestory: called")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HeadLineSensorService"
        private const val DEF_TIME = 600_000L
        private const val CHANNEL_ID = "nanidroid_downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 41

        private fun isHttpsUri(uri: Uri?): Boolean =
            uri != null && "https".equals(uri.scheme, ignoreCase = true) && !uri.host.isNullOrEmpty()

        const val START_HEADLINE_SENSOR = 9000
        const val START_NAR_DL = 9001
        const val ACTION_CAN_STOP = "canstopsensing"
        const val EXT_GID = "ghost_id_to_update"
        const val EXT_GROOT = "ghost_root_to_update"
        @JvmStatic
        fun createUpdateIntent(
            ctx: Context,
            homeurl: String,
            ghostId: String,
            ghostRoot: String,
        ): Intent =
            Intent(ctx, NanidroidService::class.java).apply {
                action = Intent.ACTION_SYNC
                data = Uri.parse(homeurl)
                putExtra(EXT_GID, ghostId)
                putExtra(EXT_GROOT, ghostRoot)
            }
    }
}
