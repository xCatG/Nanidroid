package com.cattailsw.nanidroid.util;

import android.app.Application;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Crashlytics integration boundary.
 *
 * <p>The repository deliberately does not contain a Firebase project configuration. Until an
 * owner supplies {@code google-services.json}, this remains a no-op rather than failing app
 * startup or sending reports to an unrelated project.</p>
 */
public final class CrashReporting {
    private static final String TAG = "CrashReporting";
    private static volatile boolean enabled;

    private CrashReporting() {
    }

    public static void initialize(Application application) {
        FirebaseApp firebaseApp = FirebaseApp.initializeApp(application);
        if (firebaseApp == null) {
            Log.i(TAG, "Firebase is not configured; Crashlytics reporting is disabled.");
            return;
        }

        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true);
        enabled = true;
    }

    /** Adds contextual metadata only when a configured Firebase app is active. */
    public static void setCustomKey(String key, String value) {
        if (enabled) {
            FirebaseCrashlytics.getInstance().setCustomKey(key, value);
        }
    }
}
