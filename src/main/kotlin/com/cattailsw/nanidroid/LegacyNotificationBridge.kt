package com.cattailsw.nanidroid

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build

/**
 * Compiles the historical notification flow against API 37 without raising the
 * frozen source's API-9 runtime floor. The modern app has minSdk 31, but this
 * boundary keeps the compatibility behavior explicit for the Ant artifact.
 */
internal object LegacyNotificationBridge {
    fun create(
        context: Context,
        icon: Int,
        ticker: CharSequence,
        whenMillis: Long,
        title: CharSequence,
        text: CharSequence,
        contentIntent: PendingIntent,
    ): Notification {
        if (Build.VERSION.SDK_INT >= 11) {
            return Api11.create(context, icon, ticker, whenMillis, title, text, contentIntent)
        }

        @Suppress("DEPRECATION")
        val notification = Notification(icon, ticker, whenMillis)
        try {
            val legacySetter = Notification::class.java.getMethod(
                "setLatestEventInfo",
                Context::class.java,
                CharSequence::class.java,
                CharSequence::class.java,
                PendingIntent::class.java,
            )
            legacySetter.invoke(notification, context, title, text, contentIntent)
        } catch (exception: Exception) {
            throw IllegalStateException("legacy notification API is unavailable", exception)
        }
        return notification
    }

    /** Isolated so pre-Honeycomb devices do not resolve Notification.Builder. */
    private object Api11 {
        fun create(
            context: Context,
            icon: Int,
            ticker: CharSequence,
            whenMillis: Long,
            title: CharSequence,
            text: CharSequence,
            contentIntent: PendingIntent,
        ): Notification = Notification.Builder(context)
            .setSmallIcon(icon)
            .setTicker(ticker)
            .setWhen(whenMillis)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .build()
    }
}
