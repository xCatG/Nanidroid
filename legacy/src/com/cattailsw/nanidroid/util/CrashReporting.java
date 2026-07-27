package com.cattailsw.nanidroid.util;

import android.app.Application;

/**
 * Frozen Ant-build compatibility shim. Firebase Crashlytics is only packaged by the modern
 * Gradle lane; this intentionally performs no legacy spreadsheet reporting.
 */
public final class CrashReporting {
    private CrashReporting() {
    }

    public static void initialize(Application application) {
    }

    public static void setCustomKey(String key, String value) {
    }
}
