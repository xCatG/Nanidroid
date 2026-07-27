package com.cattailsw.nanidroid.compose

import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NanidroidComposeShellTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shell_exposes_compose_controls_and_keeps_the_stage_as_one_android_view() {
        var selected = ""
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = FrameLayout(composeRule.activity),
                loading = true,
                progressMessage = "Loading ghost",
                toolbarVisible = true,
                onListGhost = { selected = "list" },
                onUpdate = { selected = "update" },
                onPreferences = { selected = "preferences" },
                onHelp = { selected = "help" },
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        composeRule.onNodeWithTag("ghost-stage").assertIsDisplayed()
        composeRule.onNodeWithTag("loading-overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("list-ghost").performClick()
        assertEquals("list", selected)
        composeRule.onNodeWithTag("update").performClick()
        assertEquals("update", selected)
        composeRule.onNodeWithTag("preferences").performClick()
        assertEquals("preferences", selected)
        composeRule.onNodeWithTag("help").performClick()
        assertEquals("help", selected)
    }

    @Test
    fun simple_dialogs_keep_menu_actions_at_the_activity_callback_boundary() {
        var selected = ""
        composeRule.setContent {
            NanidroidSimpleDialogHost(
                dialog = NanidroidSimpleDialog.MoreGhost(
                    onEnterUrl = { selected = "url" },
                    onInstallFromSdCard = { selected = "sd" },
                    onGhostTown = { selected = "town" },
                ),
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("To Get More Ghosts").assertIsDisplayed()
        composeRule.onNodeWithTag("simple-action-0").performClick()
        assertEquals("url", selected)
    }

}
