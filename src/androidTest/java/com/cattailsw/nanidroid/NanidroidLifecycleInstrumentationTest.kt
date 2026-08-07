package com.cattailsw.nanidroid

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ActivityScenario.ActivityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.cattailsw.nanidroid.durable.DurableNotificationPermissionAcceptance
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real-device smoke coverage for main-activity launch and configuration recreation.  */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NanidroidLifecycleInstrumentationTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun launchAndRecreateKeepsMainActivityAvailable() {
        ActivityScenario.launch<Nanidroid?>(Nanidroid::class.java).use { scenario ->
            val initial = AtomicReference<Nanidroid?>()
            scenario.onActivity(ActivityAction { newValue: Nanidroid? -> initial.set(newValue) })
            Assert.assertNotNull(initial.get())

            scenario.recreate()

            val recreated = AtomicReference<Nanidroid?>()
            scenario.onActivity(ActivityAction { newValue: Nanidroid? -> recreated.set(newValue) })
            Assert.assertNotNull(recreated.get())
            Assert.assertFalse(recreated.get()!!.isFinishing())
        }
    }

    @Test
    fun acceptedUpdatePermissionOpportunitySurvivesRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val original = AtomicReference<Nanidroid?>()
        val markedDuringRecreation = AtomicBoolean(false)
        val permissionDialogExpected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            instrumentation.targetContext.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (activity === original.get() && activity.isChangingConfigurations) {
                    DurableNotificationPermissionAcceptance.markAccepted()
                    markedDuringRecreation.set(true)
                }
            }
        }
        DurableNotificationPermissionAcceptance.resetForTesting()
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        try {
            ActivityScenario.launch<Nanidroid?>(Nanidroid::class.java).use { scenario ->
                scenario.onActivity { original.set(it) }
                val permissionHandler = if (permissionDialogExpected) {
                    thread(name = "nanidroid-permission-dialog-dismissal") {
                        val device = UiDevice.getInstance(instrumentation)
                        val deadline = SystemClock.uptimeMillis() + PERMISSION_DIALOG_TIMEOUT_MILLIS
                        while (SystemClock.uptimeMillis() < deadline) {
                            if (PERMISSION_CONTROLLER_PACKAGES.any { device.hasObject(By.pkg(it)) }) {
                                device.pressBack()
                                return@thread
                            }
                            SystemClock.sleep(PERMISSION_DIALOG_POLL_MILLIS)
                        }
                    }
                } else {
                    null
                }

                scenario.recreate()
                scenario.moveToState(Lifecycle.State.RESUMED)
                instrumentation.waitForIdleSync()
                permissionHandler?.join(PERMISSION_DIALOG_TIMEOUT_MILLIS)

                Assert.assertTrue(markedDuringRecreation.get())
                Assert.assertFalse(
                    DurableNotificationPermissionAcceptance.hasPendingAcceptanceForTesting(),
                )
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
            DurableNotificationPermissionAcceptance.resetForTesting()
        }
    }

    private companion object {
        const val PERMISSION_DIALOG_TIMEOUT_MILLIS = 15_000L
        const val PERMISSION_DIALOG_POLL_MILLIS = 50L
        val PERMISSION_CONTROLLER_PACKAGES = listOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
    }
}
