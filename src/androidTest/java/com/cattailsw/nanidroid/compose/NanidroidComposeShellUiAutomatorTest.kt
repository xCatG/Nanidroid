package com.cattailsw.nanidroid.compose

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NanidroidComposeShellUiAutomatorTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shell_exposes_stage_test_tag_as_accessibility_resource_id() {
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = false,
                progressMessage = "",
                toolbarVisible = false,
                onListGhost = {},
                onUpdate = {},
                onPreferences = {},
                onHelp = {},
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }
        composeRule.waitForIdle()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(
            "Expected UI Automator to discover the ghost-stage test tag as a resource ID",
            device.wait(Until.hasObject(By.res("ghost-stage")), RESOURCE_ID_TIMEOUT_MILLIS),
        )
    }

    private companion object {
        const val RESOURCE_ID_TIMEOUT_MILLIS = 5_000L
    }
}
