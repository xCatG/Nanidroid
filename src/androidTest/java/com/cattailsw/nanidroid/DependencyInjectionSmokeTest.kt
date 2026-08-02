package com.cattailsw.nanidroid

import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cattailsw.nanidroid.di.MonotonicClock
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DependencyInjectionSmokeTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(ComponentActivity::class.java)

    @Inject
    lateinit var clock: MonotonicClock

    @Test
    fun injectedMonotonicClockProvidesANonNegativeElapsedTime() {
        hiltRule.inject()

        assertTrue(clock.nowMillis() >= 0)
    }
}
