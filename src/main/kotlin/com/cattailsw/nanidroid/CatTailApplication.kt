package com.cattailsw.nanidroid

import android.app.Application
import android.content.pm.PackageManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cattailsw.nanidroid.durable.SharedDurableOperationSupervisor
import com.cattailsw.nanidroid.util.AnalyticsUtils
import com.cattailsw.nanidroid.util.CrashReporting
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CatTailApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        SharedDurableOperationSupervisor.get(this)
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
