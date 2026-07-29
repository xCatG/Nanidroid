package com.cattailsw.nanidroid.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.cattailsw.nanidroid.runtime.GhostPresentationReducer

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

    @Test
    fun shell_routes_ghost_selection_and_keeps_the_selected_ghost_balloon_visible() {
        val selectedGhost = mutableStateOf("No ghost selected")
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {
                    Text(selectedGhost.value)
                    GhostPresentationStage(
                        presentation = GhostPresentationReducer.snapshot(
                            sakuraText = "Fixture ghost balloon",
                            sakuraSurfaceId = "0",
                            sakuraAnimationId = null,
                            sakuraBalloonId = "0",
                            keroText = "",
                            keroSurfaceId = "0",
                            keroAnimationId = null,
                            keroBalloonId = "-1",
                        ),
                        sakuraSurfaceSize = IntSize(120, 160),
                        keroSurfaceSize = IntSize(80, 120),
                    )
                },
                loading = false,
                progressMessage = "",
                toolbarVisible = true,
                onListGhost = { selectedGhost.value = "Fixture Ghost" },
                onUpdate = {},
                onPreferences = {},
                onHelp = {},
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        composeRule.onNodeWithTag("list-ghost").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Fixture Ghost").assertIsDisplayed()
        composeRule.onNodeWithText("Fixture ghost balloon").assertIsDisplayed()
    }

}
