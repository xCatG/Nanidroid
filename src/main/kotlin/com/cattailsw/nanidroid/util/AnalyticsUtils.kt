/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.cattailsw.nanidroid.util

import android.content.Context
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import com.google.android.apps.analytics.GoogleAnalyticsTracker
import java.util.concurrent.Executors

/** Helper singleton class for the frozen Google Analytics tracking library. */
open class AnalyticsUtils private constructor(context: Context?) {
    private val tracker: GoogleAnalyticsTracker?

    init {
        if (context == null) {
            tracker = null
        } else {
            val applicationContext = context.applicationContext
            tracker = GoogleAnalyticsTracker.getInstance()
            tracker.startNewSession(uaCode, DISPATCH_PERIOD_SECONDS, applicationContext)

            val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            if (preferences.getBoolean(FIRST_RUN_KEY, true)) {
                Log.d(TAG, "Analytics firstRun")
                tracker.setCustomVar(1, "apiLevel", Build.VERSION.SDK_INT.toString(), VISITOR_SCOPE)
                tracker.setCustomVar(2, "model", Build.MODEL, VISITOR_SCOPE)
                preferences.edit().putBoolean(FIRST_RUN_KEY, false).commit()
            }
        }
    }

    open fun trackEvent(category: String?, action: String?, label: String?, value: Int) {
        runAsync {
            tracker?.trackEvent(category, action, label, value)
        }
    }

    open fun trackPageView(path: String?) {
        runAsync {
            tracker?.trackPageView(path)
        }
    }

    open fun dispatch() {
        tracker?.dispatch()
    }

    private fun runAsync(action: () -> Unit) {
        analyticsExecutor.execute {
            try {
                action()
            } catch (_: Exception) {
                // Analytics failures must not crash the host app.
            }
        }
    }

    companion object {
        private const val TAG = "AnalyticsUtils"
        private const val VISITOR_SCOPE = 1
        private const val FIRST_RUN_KEY = "firstRun"
        private const val DISPATCH_PERIOD_SECONDS = 300

        private var uaCode = "INSERT_YOUR_ANALYTICS_UA_CODE_HERE"
        private var analyticsEnabled = true
        private var deviceValidationNoTelemetry = false
        private var instance: AnalyticsUtils? = null
        /** Application-process lifetime executor; analytics must not retain an Activity. */
        private val analyticsExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "analytics-dispatch").apply { isDaemon = true }
        }
        private val emptyAnalyticsUtils: AnalyticsUtils = object : AnalyticsUtils(null) {
            override fun trackEvent(category: String?, action: String?, label: String?, value: Int) = Unit

            override fun trackPageView(path: String?) = Unit

            override fun dispatch() = Unit
        }

        @JvmStatic
        fun getInstance(ctx: Context?, uaCode: String?, enableAnalytics: Boolean): AnalyticsUtils {
            if (uaCode != null) {
                this.uaCode = uaCode
            }
            analyticsEnabled = !deviceValidationNoTelemetry && enableAnalytics
            return getInstance(ctx)
        }

        /** Device-only validation manifest profile: never initialize legacy senders. */
        @JvmStatic
        fun setDeviceValidationNoTelemetry(disabled: Boolean) {
            deviceValidationNoTelemetry = disabled
            if (disabled) {
                analyticsEnabled = false
            }
        }

        /** Returns the global singleton, or the no-op instance when tracking is unavailable. */
        @JvmStatic
        fun getInstance(context: Context?): AnalyticsUtils {
            if (!analyticsEnabled) {
                return emptyAnalyticsUtils
            }
            if (instance == null) {
                if (context == null) {
                    return emptyAnalyticsUtils
                }
                instance = AnalyticsUtils(context)
            }
            return instance!!
        }
    }
}
