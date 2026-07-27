package com.cattailsw.nanidroid.util;

import android.content.Context;

/** Frozen Ant-build compatibility shim; modern analytics is implemented in Kotlin. */
public final class AnalyticsUtils {
    private static final AnalyticsUtils INSTANCE = new AnalyticsUtils();

    private AnalyticsUtils() {
    }

    public static AnalyticsUtils getInstance(Context context, String uaCode, boolean enabled) {
        return INSTANCE;
    }

    public static AnalyticsUtils getInstance(Context context) {
        return INSTANCE;
    }

    public static void setDeviceValidationNoTelemetry(boolean disabled) {
    }

    public void trackEvent(String category, String action, String label, int value) {
    }

    public void trackPageView(String path) {
    }

    public void dispatch() {
    }
}
