package com.cattailsw.nanidroid

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ActivityScenario.ActivityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.durable.DurableNotificationPermissionAcceptance
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

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
        DurableNotificationPermissionAcceptance.resetForTesting()
        try {
            ActivityScenario.launch<Nanidroid?>(Nanidroid::class.java).use { scenario ->
                scenario.moveToState(Lifecycle.State.CREATED)
                DurableNotificationPermissionAcceptance.markAccepted()

                scenario.recreate()
                scenario.moveToState(Lifecycle.State.RESUMED)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()

                Assert.assertFalse(
                    DurableNotificationPermissionAcceptance.hasPendingAcceptanceForTesting(),
                )
            }
        } finally {
            DurableNotificationPermissionAcceptance.resetForTesting()
        }
    }
}
