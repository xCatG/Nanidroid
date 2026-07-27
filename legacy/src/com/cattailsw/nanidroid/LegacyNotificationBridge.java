package com.cattailsw.nanidroid;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;

import java.lang.reflect.Method;

/** Frozen Ant-build compatibility shim; Gradle uses the Kotlin bridge. */
final class LegacyNotificationBridge {
    private LegacyNotificationBridge() {}

    static Notification create(
            Context context,
            int icon,
            CharSequence ticker,
            long when,
            CharSequence title,
            CharSequence text,
            PendingIntent contentIntent) {
        if (Build.VERSION.SDK_INT >= 11) {
            return Api11.create(context, icon, ticker, when, title, text, contentIntent);
        }

        Notification notification = new Notification(icon, ticker, when);
        try {
            Method legacySetter = Notification.class.getMethod(
                    "setLatestEventInfo",
                    Context.class,
                    CharSequence.class,
                    CharSequence.class,
                    PendingIntent.class);
            legacySetter.invoke(notification, context, title, text, contentIntent);
        } catch (Exception exception) {
            throw new IllegalStateException("legacy notification API is unavailable", exception);
        }
        return notification;
    }

    private static final class Api11 {
        private Api11() {}

        static Notification create(
                Context context,
                int icon,
                CharSequence ticker,
                long when,
                CharSequence title,
                CharSequence text,
                PendingIntent contentIntent) {
            return new Notification.Builder(context)
                    .setSmallIcon(icon)
                    .setTicker(ticker)
                    .setWhen(when)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(contentIntent)
                    .getNotification();
        }
    }
}
