package com.cattailsw.nanidroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.util.Log
import android.util.Pair
import androidx.core.app.NotificationCompat
import com.cattailsw.nanidroid.util.AnalyticsUtils
import com.cattailsw.nanidroid.util.NarUtil
import com.cattailsw.nanidroid.util.NetworkUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.util.ArrayList
import java.util.LinkedList

class NanidroidService : Service() {
    companion object {
        private const val TAG = "NanidroidService"
        const val START_HEADLINE_SENSOR = 9000
        const val START_NAR_DL = 9001
        const val ACTION_CAN_STOP = "canstopsensing"
        const val EXT_GID = "ghost_id_to_update"
        const val EXT_GROOT = "ghost_root_to_update"
        private const val DEF_TIME = 600000L
        private const val HTTP_TASK_START = 1
        private const val CHANNEL_ID = "nanidroid_service_channel"

        @JvmStatic
        fun createUpdateIntent(ctx: Context, homeurl: String, ghostId: String, ghostRoot: String): Intent {
            val u = Intent(ctx, NanidroidService::class.java)
            u.action = Intent.ACTION_SYNC
            u.data = Uri.parse(homeurl)
            u.putExtra(EXT_GID, ghostId)
            u.putExtra(EXT_GROOT, ghostRoot)
            return u
        }
    }

    private var runner: SScriptRunner? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val mHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                HTTP_TASK_START -> {
                    startSensingTask()
                    startHttpTask(DEF_TIME)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun startHttpTask(time: Long) {
        mHandler.removeMessages(HTTP_TASK_START)
        mHandler.sendEmptyMessageDelayed(HTTP_TASK_START, time)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            handleCommand(intent, startId)
        }
        return START_NOT_STICKY
    }

    private fun handleCommand(intent: Intent, startId: Int) {
        val action = intent.action
        if (action == null) {
            startHttpTask(1000)
        } else if (action.equals(Intent.ACTION_RUN, ignoreCase = true)) {
            val data = intent.data
            if (data != null) {
                startNarDownloadTask(data, startId)
            }
        } else if (Intent.ACTION_SYNC.equals(action, ignoreCase = true)) {
            val homeurl = intent.data
            val gid = intent.getStringExtra(EXT_GID)
            val gr = intent.getStringExtra(EXT_GROOT)
            if (homeurl != null && gid != null && gr != null) {
                startGhostUpdateTask(homeurl, gid, gr, startId)
            }
        } else if (action.equals(ACTION_CAN_STOP, ignoreCase = true)) {
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: called")
        mHandler.removeMessages(HTTP_TASK_START)
        serviceScope.cancel()
    }

    private fun startSensingTask() {
        serviceScope.launch {
            runner = SScriptRunner.getInstance(this@NanidroidService)
            val runnerRef = runner ?: return@launch
            
            withContext(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                try {
                    val pageContent1 = SSTPBottleSensor.getPageContent(this@NanidroidService)
                    Log.d(TAG, "bottle.length() = ${pageContent1.size}")
                    runnerRef.addMsgToQueue(pageContent1)

                    val pageContent2 = BottleLogSensor.getPageContent(this@NanidroidService)
                    runnerRef.addMsgToQueue(pageContent2)
                } catch (e: Exception) {
                    Log.e(TAG, "SensingTask error", e)
                }
                Log.d(TAG, "time = ${System.currentTimeMillis() - start} [ms]")
            }
            runnerRef.run()
        }
    }

    private fun startNarDownloadTask(uri: Uri, sid: Int) {
        val targetUrl = Uri.decode(uri.toString())
        serviceScope.launch {
            val resultPath = withContext(Dispatchers.IO) {
                try {
                    val extDir = externalCacheDir ?: return@withContext null
                    val lastSeg = uri.lastPathSegment ?: "package.nar"
                    val targetPath = File(extDir, lastSeg)
                    Log.d(TAG, "downloading:$targetUrl to $lastSeg")

                    if (!NetworkUtil.exists(this@NanidroidService, targetUrl)) {
                        Log.d(TAG, "file doesn't exist")
                        return@withContext null
                    }

                    NetworkUtil.getURLStream(this@NanidroidService, targetUrl).use { stream ->
                        FileOutputStream(targetPath).use { os ->
                            NarUtil.copyFile(stream, os)
                        }
                    }
                    targetPath.absolutePath
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            if (resultPath == null) {
                Log.d(TAG, "download failed.")
                return@launch
            }

            Log.d(TAG, "download complete?")
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ni = Intent(this@NanidroidService, MainActivity::class.java).apply {
                data = Uri.fromFile(File(resultPath))
                putExtra("DL_PKG", 0)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getActivity(this@NanidroidService, 0, ni, flags)
            val notetext = String.format(getString(R.string.dl_note), uri.lastPathSegment)

            val n = NotificationCompat.Builder(this@NanidroidService, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(getString(R.string.dl_complete))
                .setContentText(notetext)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()

            nm.notify(42, n)

            if (sid != -1) {
                stopSelf(sid)
            }
        }
    }

    private fun startGhostUpdateTask(base: Uri, ghostId: String, ghostRoot: String, sid: Int) {
        serviceScope.launch {
            if (runner == null) {
                runner = SScriptRunner.getInstance(this@NanidroidService)
            }
            val runnerRef = runner ?: return@launch
            val startTime = System.currentTimeMillis()
            var failedReason: String? = null
            var csvFilelist: String? = null

            withContext(Dispatchers.IO) {
                try {
                    val uFile = "updates2.dau"
                    val uFile2 = "updates.txt"
                    var ufileUrl = Uri.withAppendedPath(base, uFile).toString()
                    if (!NetworkUtil.exists(this@NanidroidService, ufileUrl)) {
                        ufileUrl = Uri.withAppendedPath(base, uFile2).toString()
                        if (!NetworkUtil.exists(this@NanidroidService, ufileUrl)) {
                            failedReason = "404"
                            return@withContext
                        }
                    }

                    if (!NarUtil.isPathSafe(ghostRoot, uFile)) {
                        throw SecurityException("Malicious update filename: $uFile")
                    }
                    val targetPath = "$ghostRoot/$uFile"
                    val md5 = NetworkUtil.getURLStream(this@NanidroidService, ufileUrl).use { isStream ->
                        FileOutputStream(targetPath).use { os ->
                            NarUtil.copyFile(isStream, os)
                        }
                    }
                    val md5String = md5?.let { NarUtil.md5ToString(it) } ?: ""
                    Log.d(TAG, "downloaded $targetPath w md5:$md5String")

                    val filesToUpdate = getUpdateMd5z(targetPath, ghostRoot, ghostId)
                    Log.d(TAG, "got ${filesToUpdate.size} files to update")

                    if (filesToUpdate.isEmpty()) {
                        failedReason = "none"
                        return@withContext
                    }

                    val sbList = StringBuilder()
                    for (p in filesToUpdate) {
                        if (sbList.isEmpty()) sbList.append(p.first)
                        else sbList.append(",").append(p.first)
                    }
                    csvFilelist = sbList.toString()

                    withContext(Dispatchers.Main) {
                        runnerRef.doShioriEvent("OnUpdateReady", arrayOf("changed", csvFilelist ?: ""))
                    }

                    // do download and compare
                    var idx = 0
                    val total = filesToUpdate.size
                    for (p in filesToUpdate) {
                        withContext(Dispatchers.Main) {
                            runnerRef.doShioriEvent(
                                "OnUpdate.OnDownloadBegin",
                                arrayOf(p.first, "${idx + 1}", "$total")
                            )
                        }

                        if (!NarUtil.isPathSafe(ghostRoot, p.first)) {
                            failedReason = "security error: path traversal"
                            break
                        }
                        val furi = Uri.withAppendedPath(base, p.first)
                        val fname = "$ghostRoot/${p.first}.tmp"
                        val fn = File(fname)
                        val fp = fn.parentFile
                        if (fp != null && !fp.exists()) fp.mkdirs()
                        Log.d(TAG, "dl:${p.first} to $fname from $furi")

                        val dlMd5 = NetworkUtil.getURLStream(this@NanidroidService, furi.toString()).use { stream ->
                            FileOutputStream(fname).use { os ->
                                NarUtil.copyFile(stream, os)
                            }
                        }
                        val md5s = dlMd5?.let { NarUtil.md5ToString(it) } ?: ""

                        withContext(Dispatchers.Main) {
                            runnerRef.doShioriEvent(
                                "OnUpdate.OnMD5CompareBegin",
                                arrayOf(p.first, p.second, md5s)
                            )
                        }

                        if (p.second.compareTo(md5s) != 0) {
                            failedReason = "md5 miss"
                            Log.d(TAG, "md5 error on ${p.first}")
                            withContext(Dispatchers.Main) {
                                runnerRef.doShioriEvent(
                                    "OnUpdate.OnMD5CompareFailure",
                                    arrayOf(p.first, p.second, md5s)
                                )
                            }
                            break
                        }

                        withContext(Dispatchers.Main) {
                            runnerRef.doShioriEvent(
                                "OnUpdate.OnMD5CompareComplete",
                                arrayOf(p.first, p.second, md5s)
                            )
                        }

                        val ft = File("$ghostRoot/${p.first}")
                        if (ft.exists()) {
                            val deleted = ft.delete()
                            if (!deleted) {
                                Log.d(TAG, "cannot create file ${ft.absolutePath}")
                                failedReason = "fileio"
                                break
                            }
                        }
                        val renamed = fn.renameTo(ft)
                        if (!renamed) {
                            Log.d(TAG, "cannot rename file")
                            failedReason = "fileio"
                            break
                        }
                        idx++
                    }
                } catch (e: Exception) {
                    failedReason = "exception during update"
                    e.printStackTrace()
                }
            }

            if (failedReason == null || failedReason == "none") {
                runnerRef.doShioriEvent("OnUpdateComplete", arrayOf("changed", csvFilelist ?: ""))
            } else {
                Log.d(TAG, "do OnUpdateFailure because $failedReason")
                runnerRef.doShioriEvent("OnUpdateFailure", arrayOf(failedReason ?: "", csvFilelist ?: ""))
            }

            if (sid != -1) {
                stopSelf(sid)
            }

            AnalyticsUtils.getInstance(null).trackEvent(
                Setup.ANA_PERF, "update_time", "",
                (System.currentTimeMillis() - startTime).toInt()
            )
        }
    }

    private fun getUpdateMd5z(ufile: String, ghostRoot: String, ghostId: String): List<Pair<String, String>> {
        val f = File(ufile)
        val ret = ArrayList<Pair<String, String>>()
        BufferedReader(FileReader(f)).use { br ->
            var line = br.readLine()
            while (line != null) {
                var p = line.split("\u0001").toTypedArray()
                if (p.size < 2) {
                    p = line.split(",").toTypedArray()
                    if (p.size < 2) {
                        AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "update_error", ghostId, -99)
                        throw IOException("invalid update file format")
                    }
                }

                Log.d(TAG, "pair=${p[0]},${p[1]}")
                if (!NarUtil.isPathSafe(ghostRoot, p[0])) {
                    throw IOException("Security Exception: Path traversal detected in updates file: ${p[0]}")
                }
                val fz = File(ghostRoot, p[0])
                Log.d(TAG, "file is=${fz.absolutePath}")
                if (!fz.exists()) {
                    Log.d(TAG, "local file not exist")
                    ret.add(Pair(p[0], p[1]))
                } else {
                    val fmd5 = FileInputStream(fz).use { fis -> NarUtil.createMD5(fis) }
                    val fmd5s = fmd5?.let { NarUtil.md5ToString(it) } ?: ""
                    if (p[1].compareTo(fmd5s) != 0) {
                        Log.d(TAG, "MD5 checksum mismatch:${p[1]}")
                        ret.add(Pair(p[0], p[1]))
                    }
                }
                line = br.readLine()
            }
        }
        return ret
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            val descriptionText = "Nanidroid background updates"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
