package com.cattailsw.nanidroid

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
    fun recreatedActivityWithNullRunnerRejectsArchiveIntentWhenRetainedRunnerIsPassive() {
        val retainedRunner = SScriptRunner.getInstance(null)
        val archiveIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://archives/recreated.nar"), "application/x-nar")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            // Keep cleanup active while preparing the process-global runner: either
            // wait below can time out after no-wait or passive mode has changed.
            retainedRunner.run {
                setNoWaitMode(true)
                // The process-global runner may still be mid-playback -- or have messages
                // still queued -- from a preceding instrumentation test in this same app
                // process. run() silently no-ops while state.running is already true
                // (SScriptRunner.run()), so the passive-mode command below would never
                // execute and this test would become order-dependent. Force the runner
                // fully idle first (stopping playback AND draining any queued messages),
                // then wait for the passive-mode command to actually take effect.
                forceRunnerIdle(this)
                addMsgToQueue(arrayOf("\\![enter,passivemode]\\e"))
                run()
                awaitRunnerState(this) { it.passive }
            }
            ActivityScenario.launch<Nanidroid?>(Nanidroid::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val runnerField = Nanidroid::class.java.getDeclaredField("runner").apply {
                        isAccessible = true
                    }
                    // initOnSeparateThread()'s lifecycle coroutine may still be preparing a
                    // ghost. Null the field only for this reflective call and restore it
                    // immediately, so the initialization completion never observes null.
                    val originalRunner = runnerField.get(activity)
                    try {
                        runnerField.set(activity, null)
                        Nanidroid::class.java.getDeclaredMethod(
                            "handleIncomingIntent",
                            Intent::class.java,
                            Boolean::class.javaPrimitiveType,
                        ).apply {
                            isAccessible = true
                            invoke(activity, archiveIntent, false)
                        }
                    } finally {
                        runnerField.set(activity, originalRunner)
                    }

                    val state = Nanidroid::class.java.getDeclaredField("archiveIntentState").apply {
                        isAccessible = true
                    }.get(activity) as ArchiveIntentState

                    Assert.assertSame(retainedRunner, SScriptRunner.getInstance(null))
                    Assert.assertNull(state.pendingUri)
                }
            }
        } finally {
            // Activity initialization above may have left the retained runner running
            // (or paused mid dialogue action) rather than sitting idle in passive mode:
            // closing ActivityScenario stops the clock but does not stop playback, and
            // run() silently no-ops while playback is already active. Force idle again
            // before queuing the cleanup command so it is guaranteed to execute, then
            // wait for passive mode to actually clear -- otherwise the singleton stays
            // passive and contaminates later instrumentation tests in this process.
            forceRunnerIdle(retainedRunner)
            retainedRunner.addMsgToQueue(arrayOf("\\![leave,passivemode]\\e"))
            retainedRunner.run()
            awaitRunnerState(retainedRunner) { !it.passive }
            retainedRunner.setNoWaitMode(false)
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

    /**
     * Forces the process-global runner into a fully idle state: stops any in-flight playback
     * AND drains any messages left queued by a preceding instrumentation test. stop() alone
     * leaves a non-empty msgQueue behind, which keeps runtimeModeSnapshot().playingTalk true
     * and makes any subsequent wait for idle time out. clearMsgQueue() clears the queue and
     * stops playback in one call, so it is used here instead of stop() alone.
     */
    private fun forceRunnerIdle(runner: SScriptRunner) {
        runner.clearMsgQueue()
        awaitRunnerState(runner) { !it.playingTalk }
    }

    /**
     * Polls the process-global runner's mode snapshot until [predicate] holds, or fails the
     * test if it never converges. Mirrors the deadline/poll idiom already used above for the
     * notification-permission dialog dismissal wait.
     */
    private fun awaitRunnerState(
        runner: SScriptRunner,
        predicate: (com.cattailsw.nanidroid.runtime.dialogue.GhostRuntimeMode) -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + RUNNER_STATE_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate(runner.runtimeModeSnapshot())) return
            SystemClock.sleep(RUNNER_STATE_POLL_MILLIS)
        }
        Assert.assertTrue(
            "Retained runner did not converge to the expected state within " +
                "${RUNNER_STATE_TIMEOUT_MILLIS}ms",
            predicate(runner.runtimeModeSnapshot()),
        )
    }

    private companion object {
        const val PERMISSION_DIALOG_TIMEOUT_MILLIS = 15_000L
        const val PERMISSION_DIALOG_POLL_MILLIS = 50L
        const val RUNNER_STATE_TIMEOUT_MILLIS = 5_000L
        const val RUNNER_STATE_POLL_MILLIS = 20L
        val PERMISSION_CONTROLLER_PACKAGES = listOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
    }
}
