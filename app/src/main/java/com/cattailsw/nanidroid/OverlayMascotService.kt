package com.cattailsw.nanidroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat

class OverlayMascotService : Service() {
    companion object {
        private const val TAG = "OverlayMascotService"
        private const val CHANNEL_ID = "overlay_mascot_channel"
        private const val NOTIFICATION_ID = 101
        @JvmStatic var isRunning: Boolean = false
    }

    private var windowManager: WindowManager? = null
    private var overlayLayout: FrameLayout? = null
    private var sv: SakuraView? = null
    private var kv: KeroView? = null
    private var bSakura: Balloon? = null
    private var bKero: Balloon? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForegroundService()
        setupOverlay()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nanidroid Floating Mascot",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs the floating desktop mascot companion"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val intent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nanidroid Active")
            .setContentText("Mascot companion is running on your desktop")
            .setSmallIcon(R.drawable.notification)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dpToPx(400),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            x = 0
            y = 0
        }

        overlayLayout = FrameLayout(this)
        
        sv = SakuraView(this).apply { id = R.id.sakura_display }
        kv = KeroView(this).apply { id = R.id.kero_display }
        
        bSakura = Balloon(this).apply {
            id = R.id.bSakura
            setBackgroundResource(R.drawable.balloon)
            setTextColor(Color.BLACK)
        }
        bKero = Balloon(this).apply {
            id = R.id.bKero
            setBackgroundResource(R.drawable.balloon)
            setTextColor(Color.BLACK)
        }

        overlayLayout?.addView(bKero)
        overlayLayout?.addView(bSakura)
        overlayLayout?.addView(sv)
        overlayLayout?.addView(kv)

        windowManager?.addView(overlayLayout, params)

        val gm = GhostMgr(this)
        val lastGhostId = gm.getLastRunGhostId() ?: "nanidroid"
        val activeGhost = gm.createGhost(lastGhostId)
        
        if (activeGhost != null) {
            val lm = LayoutManager.getInstance(this)
            val runner = SScriptRunner.getInstance(this)

            sv?.mgr = activeGhost.mgr
            kv?.mgr = activeGhost.mgr

            lm.setViews(overlayLayout, sv, kv, bSakura, bKero)
            runner.setViews(sv, kv, bSakura, bKero)
            runner.setGhost(activeGhost)
            runner.setLayoutMgr(lm)
            
            overlayLayout?.post {
                lm.checkAndUpdateLayoutParam()
            }
            
            runner.startClock()
            runner.run()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        val runner = SScriptRunner.getInstance(this)
        runner.setGhost(null)
        runner.stopClock()
        runner.clearMsgQueue()
        runner.clearViews()
        LayoutManager.getInstance(this).clearViews()
        
        overlayLayout?.let {
            windowManager?.removeView(it)
        }
        stopForeground(true)
    }
}
