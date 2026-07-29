package com.cattailsw.nanidroid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class PreferencesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun analytics_checkbox_updates_the_hoisted_preference_value() {
        composeRule.setContent {
            var enabled by remember { mutableStateOf(true) }
            PreferencesScreen(
                analyticsEnabled = enabled,
                onAnalyticsEnabledChanged = { enabled = it },
            )
        }

        composeRule.onNodeWithText("Allow Anonymous Usage Data").assertExists()
        val checkbox = composeRule.onNodeWithTag("analytics-preference")
        checkbox.assertIsOn()
        checkbox.performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("analytics-preference").assertIsOff()
    }
}
