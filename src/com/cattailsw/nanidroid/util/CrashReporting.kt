package com.cattailsw.nanidroid.util

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Crashlytics integration boundary.
 *
 * The repository deliberately contains no Firebase project configuration. Until an owner
 * supplies google-services.json, this remains a no-op rather than failing app startup or
 * sending reports to an unrelated project.
 */
object CrashReporting {
    private const val TAG = "CrashReporting"

    @Volatile
    private var enabled = false

    @JvmStatic
    fun initialize(application: Application) {
        FirebaseApp.initializeApp(application) ?: run {
            Log.i(TAG, "Firebase is not configured; Crashlytics reporting is disabled.")
            return
        }

        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
        enabled = true
    }

    /** Adds contextual metadata only when a configured Firebase app is active. */
    @JvmStatic
    fun setCustomKey(key: String, value: String) {
        if (enabled) {
            FirebaseCrashlytics.getInstance().setCustomKey(key, value)
        }
    }
}
