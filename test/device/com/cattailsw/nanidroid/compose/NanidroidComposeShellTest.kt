package com.cattailsw.nanidroid.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
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
    fun shell_exposes_compose_controls_and_keeps_the_stage_in_compose() {
        var selected = ""
        var stageTapped = false
        val loading = mutableStateOf(false)
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = loading.value,
                progressMessage = "Loading ghost",
                toolbarVisible = true,
                onListGhost = { selected = "list" },
                onUpdate = { selected = "update" },
                onPreferences = { selected = "preferences" },
                onHelp = { selected = "help" },
                onStageClick = { stageTapped = true },
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        composeRule.onNodeWithTag("ghost-stage").assertIsDisplayed()
        composeRule.onNodeWithTag("ghost-stage").performClick()
        composeRule.runOnIdle { assertEquals(true, stageTapped) }
        composeRule.onNodeWithTag("list-ghost").performClick()
        composeRule.runOnIdle { assertEquals("list", selected) }
        composeRule.onNodeWithTag("update").performClick()
        composeRule.runOnIdle { assertEquals("update", selected) }
        composeRule.onNodeWithTag("preferences").performClick()
        composeRule.runOnIdle { assertEquals("preferences", selected) }
        composeRule.onNodeWithTag("help").performClick()
        composeRule.runOnIdle { assertEquals("help", selected) }
        composeRule.runOnIdle { loading.value = true }
        composeRule.onNodeWithTag("loading-overlay").assertIsDisplayed()
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
        composeRule.runOnIdle { assertEquals("url", selected) }
    }

}
