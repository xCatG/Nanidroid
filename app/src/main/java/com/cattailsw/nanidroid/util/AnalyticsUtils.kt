package com.cattailsw.nanidroid.util

import android.content.Context
import android.util.Log

class AnalyticsUtils private constructor(context: Context?) {

    fun trackEvent(category: String, action: String, label: String, value: Int) {
        Log.d(TAG, "TrackEvent: category=$category, action=$action, label=$label, value=$value")
    }

    fun trackPageView(path: String) {
        Log.d(TAG, "TrackPageView: path=$path")
    }

    companion object {
        private const val TAG = "AnalyticsUtils"
        private var UACODE = "INSERT_YOUR_ANALYTICS_UA_CODE_HERE"
        private var ANALYTICS_ENABLED = true
        private var sInstance: AnalyticsUtils? = null

        private val sEmptyAnalyticsUtils = AnalyticsUtils(null)

        @JvmStatic
        fun getInstance(ctx: Context?, uaCode: String?, enableAnalytics: Boolean): AnalyticsUtils {
            if (uaCode != null) {
                UACODE = uaCode
            }
            ANALYTICS_ENABLED = enableAnalytics
            return getInstance(ctx)
        }

        @JvmStatic
        fun getInstance(context: Context?): AnalyticsUtils {
            if (!ANALYTICS_ENABLED) {
                return sEmptyAnalyticsUtils
            }
            if (sInstance == null) {
                if (context == null) {
                    return sEmptyAnalyticsUtils
                }
                sInstance = AnalyticsUtils(context)
            }
            return sInstance!!
        }
    }
}
