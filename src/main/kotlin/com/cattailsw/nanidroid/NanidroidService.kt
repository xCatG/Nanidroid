package com.cattailsw.nanidroid

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.util.Log
import android.util.Pair
import com.cattailsw.nanidroid.util.AnalyticsUtils
import com.cattailsw.nanidroid.util.NarUtil
import com.cattailsw.nanidroid.util.NetworkUtil
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.LinkedList

/** Kotlin owner of foreground downloads, polling, and ghost updates. */
class NanidroidService : Service() {
    private var runner: SScriptRunner? = null
    private val activeForegroundStartIds = HashSet<Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startHttpTask(time: Long) = handler.sendEmptyMessageDelayed(HTTP_TASK_START, time)

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
                val data = intent.data
                if (RemoteNarUrl.isApproved(data)) {
                    startForegroundWork(startId)
                    NarDownloadTask(data!!, startId).execute(this)
                } else {
                    Log.w(TAG, "Rejected non-HTTPS archive download request")
                    finishForegroundWork(startId)
                }
            }
            Intent.ACTION_SYNC.equals(action, ignoreCase = true) -> {
                val homeurl = intent.data
                val gid = intent.getStringExtra(EXT_GID)
                val ghostRoot = intent.getStringExtra(EXT_GROOT)
                if (isHttpsUri(homeurl) && gid != null) {
                    startForegroundWork(startId)
                    GhostUpdateTask(homeurl!!, gid, ghostRoot, startId).execute(this)
                } else {
                    Log.w(TAG, "Rejected update request without an HTTPS URL and ghost id")
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
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(this, 0, launchIntent, flags)
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
        super.onDestroy()
        runner?.stop()
        Log.d(TAG, "onDestory: called")
        handler.removeMessages(HTTP_TASK_START)
    }

    private val handler: Handler = object : Handler() {
        override fun handleMessage(msg: Message) {
            if (msg.what == HTTP_TASK_START) {
                SensingTask().execute(this@NanidroidService)
                startHttpTask(DEF_TIME)
            }
        }
    }

    @Suppress("DEPRECATION")
    private inner class SensingTask : AsyncTask<Context, String, String>() {
        override fun onPreExecute() { runner = SScriptRunner.getInstance(this@NanidroidService) }

        override fun doInBackground(vararg args: Context): String {
            val start = System.currentTimeMillis()
            try {
                var pageContent: LinkedList<String> = SSTPBottleSensor.getPageContent(args[0])
                Log.d(TAG, "bottle.length() = ${pageContent.size}")
                runner?.addMsgToQueue(pageContent)
                pageContent = BottleLogSensor.getPageContent(args[0])
                runner?.addMsgToQueue(pageContent)
            } catch (_: Exception) {
                // Legacy sensor failure isolation.
            }
            Log.d(TAG, "time = ${System.currentTimeMillis() - start} [ms]")
            return "End of conversions"
        }

        override fun onPostExecute(result: String) { runner?.run() }
    }

    @Suppress("DEPRECATION")
    private inner class NarDownloadTask(private val targeturi: Uri, private val svcid: Int) : AsyncTask<Context, String, String?>() {
        private val targetUrl = Uri.decode(targeturi.toString())

        override fun doInBackground(vararg args: Context): String? = try {
            val context = args[0]
            val targetPath = File(context.externalCacheDir, targeturi.lastPathSegment)
            Log.d(TAG, "downloading:$targetUrl to ${targeturi.lastPathSegment}")
            if (!NetworkUtil.exists(context, targetUrl)) {
                Log.d(TAG, "file doesn't exist")
                null
            } else {
                val input: InputStream = NetworkUtil.getURLStream(context, targetUrl)
                NarUtil.copyFile(input, FileOutputStream(targetPath))
                targetPath.toString()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        override fun onPostExecute(result: String?) {
            if (result == null) {
                Log.d(TAG, "download failed.")
                finishForegroundWork(svcid)
                return
            }
            Log.d(TAG, "download complete?")
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val launch = Intent(this@NanidroidService, Nanidroid::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 23) flags = flags or FLAG_IMMUTABLE
            val content = PendingIntent.getActivity(this@NanidroidService, 0, launch, flags)
            val noteText = String.format(getString(R.string.dl_note), targeturi.lastPathSegment)
            val notification = LegacyNotificationBridge.create(
                applicationContext, R.drawable.notification, getString(R.string.dl_complete),
                System.currentTimeMillis(), getString(R.string.dl_complete), noteText, content
            )
            notification.flags = Notification.FLAG_AUTO_CANCEL
            manager.notify(42, notification)
            finishForegroundWork(svcid)
        }
    }

    @Suppress("DEPRECATION")
    private inner class GhostUpdateTask(
        private val base: Uri,
        private val ghostId: String,
        private val ghostRoot: String?,
        private val sid: Int,
    ) : AsyncTask<Context, String, String?>() {
        private var failedReason: String? = null
        private var startTime = 0L
        private var filesToUpdate: List<Pair<String, String>>? = null
        private var csvFilelist: String? = null

        override fun onPreExecute() {
            startTime = System.currentTimeMillis()
            if (runner == null) runner = SScriptRunner.getInstance(this@NanidroidService)
        }

        override fun doInBackground(vararg args: Context): String? {
            try {
                val context = args[0]
                var updateFile = Uri.withAppendedPath(base, UPDATE_FILE).toString()
                if (!NetworkUtil.exists(context, updateFile)) {
                    updateFile = Uri.withAppendedPath(base, UPDATE_FILE_FALLBACK).toString()
                    if (!NetworkUtil.exists(context, updateFile)) {
                        failedReason = "404"
                        return null
                    }
                }
                val targetPath = "$ghostRoot/$UPDATE_FILE"
                val md5 = NarUtil.copyFile(NetworkUtil.getURLStream(context, updateFile), FileOutputStream(targetPath))
                Log.d(TAG, "downloaded $targetPath w md5:${NarUtil.md5ToString(md5)}")
                if (doFileComp(targetPath) == 0) {
                    failedReason = "none"
                    return null
                }
                runner?.doShioriEvent("OnUpdateReady", arrayOf("changed", csvFilelist))
                doDownloadCompare(context)
            } catch (_: SocketTimeoutException) {
                failedReason = "timeout"
            } catch (_: Exception) {
                failedReason = "exception during update"
            }
            return null
        }

        private fun doFileComp(updateFile: String): Int {
            filesToUpdate = getUpdateMd5z(updateFile)
            Log.d(TAG, "got ${filesToUpdate!!.size} files to update")
            return filesToUpdate!!.size
        }

        @Throws(IOException::class, FileNotFoundException::class)
        private fun getUpdateMd5z(updateFile: String): List<Pair<String, String>> {
            val result = ArrayList<Pair<String, String>>()
            BufferedReader(FileReader(File(updateFile))).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    var pair = line.split("\u0001".toRegex()).toTypedArray()
                    if (pair.size < 2) {
                        pair = line.split(",".toRegex()).toTypedArray()
                        if (pair.size < 2) {
                            AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "update_error", ghostId, -99)
                            throw IOException()
                        }
                    }
                    Log.d(TAG, "pair=${pair[0]},${pair[1]}")
                    val localFile = File(ghostRoot, pair[0])
                    Log.d(TAG, "file is=${localFile.absolutePath}")
                    if (!localFile.exists()) {
                        Log.d(TAG, "local file not exist")
                        addToUpdateList(result, pair[0], pair[1])
                    } else {
                        val md5 = NarUtil.createMD5(FileInputStream(localFile))
                        if (pair[1] != NarUtil.md5ToString(md5)) {
                            Log.d(TAG, "MD5 checksum mismatch:${pair[1]}")
                            addToUpdateList(result, pair[0], pair[1])
                        }
                    }
                    line = reader.readLine()
                }
            }
            return result
        }

        private fun addToUpdateList(destination: MutableList<Pair<String, String>>, file: String, md5: String) {
            destination.add(Pair(file, md5))
            csvFilelist = if (csvFilelist == null) file else "$csvFilelist,$file"
        }

        @Throws(IOException::class, FileNotFoundException::class)
        private fun doDownloadCompare(context: Context) {
            val updates = filesToUpdate ?: return
            val total = updates.size
            updates.forEachIndexed { index, pair ->
                runner?.doShioriEvent("OnUpdate.OnDownloadBegin", arrayOf(pair.first, "${index + 1}", "$total"))
                val fileUri = Uri.withAppendedPath(base, pair.first)
                val temp = File("$ghostRoot/${pair.first}.tmp")
                temp.parentFile?.let { if (!it.exists()) it.mkdirs() }
                Log.d(TAG, "dl:${pair.first} to $temp")
                Log.d(TAG, "from $fileUri")
                val md5 = NarUtil.copyFile(NetworkUtil.getURLStream(context, fileUri.toString()), FileOutputStream(temp))
                val md5String = NarUtil.md5ToString(md5)
                runner?.doShioriEvent("OnUpdate.OnMD5CompareBegin", arrayOf(pair.first, pair.second, md5String))
                if (pair.second != md5String) {
                    failedReason = "md5 miss"
                    Log.d(TAG, "md5 error on ${pair.first}")
                    runner?.doShioriEvent("OnUpdate.OnMD5CompareFailure", arrayOf(pair.first, pair.second, md5String))
                    return
                }
                runner?.doShioriEvent("OnUpdate.OnMD5CompareComplete", arrayOf(pair.first, pair.second, md5String))
                val finalFile = File("$ghostRoot/${pair.first}")
                if (finalFile.exists() && !finalFile.delete()) {
                    Log.d(TAG, "cannot create file${finalFile.absolutePath}")
                    failedReason = "fileio"
                    return
                }
                if (!temp.renameTo(finalFile)) {
                    Log.d(TAG, "cannot rename file")
                    failedReason = "fileio"
                    return
                }
            }
        }

        override fun onPostExecute(result: String?) {
            if (failedReason == null) {
                runner?.doShioriEvent("OnUpdateComplete", arrayOf("changed", csvFilelist))
            } else {
                Log.d(TAG, "do OnUpdateFilure because $failedReason")
                runner?.doShioriEvent("OnUpdateFilure", arrayOf(failedReason, csvFilelist))
            }
            finishForegroundWork(sid)
            AnalyticsUtils.getInstance(null).trackEvent(
                Setup.ANA_PERF, "update_time", "", (System.currentTimeMillis() - startTime).toInt()
            )
        }

    }

    companion object {
        private const val TAG = "HeadLineSensorService"
        private const val DEF_TIME = 600_000L
        private const val HTTP_TASK_START = 1
        private const val CHANNEL_ID = "nanidroid_downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 41
        private const val FLAG_IMMUTABLE = 0x04000000
        private const val UPDATE_FILE = "updates2.dau"
        private const val UPDATE_FILE_FALLBACK = "updates.txt"

        private fun isHttpsUri(uri: Uri?): Boolean =
            uri != null && "https".equals(uri.scheme, ignoreCase = true) && !uri.host.isNullOrEmpty()

        const val START_HEADLINE_SENSOR = 9000
        const val START_NAR_DL = 9001
        const val ACTION_CAN_STOP = "canstopsensing"
        const val EXT_GID = "ghost_id_to_update"
        const val EXT_GROOT = "ghost_root_to_update"

        @JvmStatic
        fun createUpdateIntent(ctx: Context, homeurl: String, ghostId: String, ghostRoot: String): Intent =
            Intent(ctx, NanidroidService::class.java).apply {
                action = Intent.ACTION_SYNC
                data = Uri.parse(homeurl)
                putExtra(EXT_GID, ghostId)
                putExtra(EXT_GROOT, ghostRoot)
            }
    }
}
