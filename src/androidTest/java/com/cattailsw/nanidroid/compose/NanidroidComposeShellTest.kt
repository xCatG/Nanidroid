package com.cattailsw.nanidroid.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        composeRule.onNodeWithTag("ghost-stage").assertIsDisplayed()
        composeRule.onNodeWithTag("ghost-stage").assert(hasNoClickAction())
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
    fun operational_notice_and_help_actions_stay_in_the_compose_host() {
        val dialog = mutableStateOf<NanidroidSimpleDialog?>(null)
        dialog.value = NanidroidSimpleDialog.HelpMenu(
            onGeneralHelp = { dialog.value = NanidroidSimpleDialog.GeneralHelp({}, {}) },
            onAbout = {},
            onFeedback = {},
        )
        var installHelp = false
        var confirmed = false
        composeRule.setContent {
            val current = dialog.value
            NanidroidSimpleDialogHost(
                dialog = when (current) {
                    is NanidroidSimpleDialog.GeneralHelp -> NanidroidSimpleDialog.GeneralHelp(
                        onInstallHelp = { installHelp = true },
                        onSupportedOperations = {},
                    )
                    else -> current
                },
                onDismiss = {},
            )
        }

        composeRule.onNodeWithTag("simple-action-0").performClick()
        composeRule.onNodeWithTag("simple-action-0").performClick()
        composeRule.runOnIdle { assertEquals(true, installHelp) }
        composeRule.runOnIdle {
            dialog.value = NanidroidSimpleDialog.Notice(
                title = android.R.string.dialog_alert_title,
                message = android.R.string.ok,
                onConfirm = { confirmed = true },
            )
        }
        composeRule.onNodeWithTag("notice-confirm").performClick()
        composeRule.runOnIdle { assertEquals(true, confirmed) }
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

    @Test
    fun url_entry_keeps_invalid_urls_open_and_submits_an_approved_nar_url() {
        val value = mutableStateOf("")
        val error = mutableStateOf(false)
        var submitted = ""
        composeRule.setContent {
            NanidroidSimpleDialogHost(
                dialog = NanidroidSimpleDialog.UrlEntry(
                    value = value.value,
                    validationError = error.value,
                    onValueChanged = { value.value = it; error.value = false },
                    onSubmit = { candidate ->
                        candidate.startsWith("https://") && candidate.endsWith(".nar")
                            .also { if (it) submitted = candidate }
                    },
                    onInvalid = { error.value = true },
                ),
                onDismiss = {},
            )
        }

        composeRule.onNodeWithTag("url-entry").performTextReplacement("http://example.test/ghost.nar")
        composeRule.onNodeWithTag("url-submit").performClick()
        composeRule.onNodeWithTag("url-validation-error").assertIsDisplayed()
        composeRule.onNodeWithTag("url-entry").performTextReplacement("https://example.test/ghost.nar")
        composeRule.onNodeWithTag("url-submit").performClick()
        composeRule.runOnIdle { assertEquals("https://example.test/ghost.nar", submitted) }
    }

    @Test
    fun script_input_and_choice_callbacks_remain_at_the_runner_boundary() {
        val input = mutableStateOf("")
        var submittedInput = ""
        var selected = -1
        val showChoice = mutableStateOf(false)
        composeRule.setContent {
            NanidroidSimpleDialogHost(
                dialog = if (showChoice.value) {
                    NanidroidSimpleDialog.UserChoice(listOf("First"), listOf("choice-id")) { selected = it }
                } else {
                    NanidroidSimpleDialog.UserInput(
                        id = "name",
                        value = input.value,
                        onValueChanged = { input.value = it },
                        onSubmit = { id, value -> submittedInput = "$id:$value" },
                        onCancel = {},
                    )
                },
                onDismiss = {},
            )
        }
        composeRule.onNodeWithTag("script-user-input").performTextReplacement("Cat")
        composeRule.onNodeWithTag("script-user-input-confirm").performClick()
        composeRule.runOnIdle { assertEquals("name:Cat", submittedInput) }
        composeRule.runOnIdle { showChoice.value = true }
        composeRule.onNodeWithTag("script-choice-0").performClick()
        composeRule.runOnIdle { assertEquals(0, selected) }
    }

    @Test
    fun ghost_list_exposes_selection_more_and_cancellation_actions() {
        var selected = -1
        var more = false
        var cancelled = false
        composeRule.setContent {
            NanidroidSimpleDialogHost(
                dialog = NanidroidSimpleDialog.GhostList(
                    names = listOf("Fixture Ghost"),
                    ids = listOf("fixture"),
                    onSelect = { selected = it },
                    onMore = { more = true },
                    onCancel = { cancelled = true },
                ),
                onDismiss = {},
            )
        }
        composeRule.onNodeWithTag("ghost-choice-0").performClick()
        composeRule.runOnIdle { assertEquals(0, selected) }
        composeRule.onNodeWithTag("ghost-list-more").performClick()
        composeRule.runOnIdle { assertEquals(true, more) }
        composeRule.onNodeWithTag("ghost-list-cancel").performClick()
        composeRule.runOnIdle { assertEquals(true, cancelled) }
    }

    @Test
    fun compose_documents_keep_text_links_and_switch_actions_without_webview() {
        var opened = ""
        var switched = false
        composeRule.setContent {
            NanidroidSimpleDialogHost(
                dialog = NanidroidSimpleDialog.TextDocument(
                    title = "Installed New Ghost",
                    text = "Read this first. https://example.test/readme\nmailto:test@example.test",
                    onOpenLink = { opened = it },
                    sourceId = "fixture",
                    onSwitch = { switched = true },
                ),
                onDismiss = {},
            )
        }
        composeRule.onNodeWithTag("text-document").assertIsDisplayed()
        composeRule.onNodeWithTag("document-link-0").performClick()
        composeRule.runOnIdle { assertEquals("https://example.test/readme", opened) }
        composeRule.onNodeWithTag("document-switch").performClick()
        composeRule.runOnIdle { assertEquals(true, switched) }
    }

}
