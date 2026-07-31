package com.cattailsw.nanidroid

import android.app.Application
import android.content.pm.PackageManager
import com.cattailsw.nanidroid.util.AnalyticsUtils
import com.cattailsw.nanidroid.util.CrashReporting

class CatTailApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (isDeviceValidationNoTelemetry()) {
            AnalyticsUtils.setDeviceValidationNoTelemetry(true)
            return
        }
        CrashReporting.initialize(this)
    }

    private fun isDeviceValidationNoTelemetry(): Boolean = try {
        val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        info.metaData?.getBoolean(DEVICE_VALIDATION_NO_TELEMETRY, false) == true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val DEVICE_VALIDATION_NO_TELEMETRY =
            "com.cattailsw.nanidroid.DEVICE_VALIDATION_NO_TELEMETRY"
    }
}
