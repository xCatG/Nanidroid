package com.cattailsw.nanidroid.compose

import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.runtime.GhostPresentationReducer
import com.cattailsw.nanidroid.install.NarDownload
import com.cattailsw.nanidroid.install.NarDownloadSource
import com.cattailsw.nanidroid.install.NarDownloadState
import com.cattailsw.nanidroid.durable.AttemptId
import com.cattailsw.nanidroid.durable.CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX
import com.cattailsw.nanidroid.durable.DurableAttentionAction
import com.cattailsw.nanidroid.durable.DurableOperationRecord
import com.cattailsw.nanidroid.durable.OperationHandle
import com.cattailsw.nanidroid.durable.OperationId
import com.cattailsw.nanidroid.durable.OperationKind
import com.cattailsw.nanidroid.durable.OperationProgress
import com.cattailsw.nanidroid.durable.OperationStatus


class NanidroidComposeShellTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun restoreOrientation() {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        uiAutomation().executeShellCommand("wm size reset").close()
    }

    @Test
    fun shell_uses_dark_theme_for_night_mode_configuration() {
        var observedScheme: ColorScheme? = null

        composeRule.setContent {
            val nightConfiguration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            CompositionLocalProvider(LocalConfiguration provides nightConfiguration) {
                NanidroidComposeShell(
                    ghostStage = {
                        val colorScheme = MaterialTheme.colorScheme
                        SideEffect { observedScheme = colorScheme }
                    },
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
        }

        composeRule.runOnIdle {
            val scheme = observedScheme
            assertNotNull("The production shell should provide a color scheme", scheme)
            assertEquals(
                "UI_MODE_NIGHT_YES must select the dark production scheme",
                darkColorScheme().surface,
                scheme!!.surface,
            )
        }
    }

    private fun openOverflowMenu() {
        composeRule.onNodeWithTag("appbar-overflow").performClick()
    }

    private fun assertNoNodeWithTag(tag: String, useUnmergedTree: Boolean = false) {
        assertFalse(
            "Expected no node with tag=$tag",
            composeRule.onAllNodesWithTag(tag, useUnmergedTree).fetchSemanticsNodes().isNotEmpty(),
        )
    }

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
                onReadme = { selected = "readme" },
                onPreferences = { selected = "preferences" },
                onHelp = { selected = "help" },
                showDebugControls = false,
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        composeRule.onNodeWithTag("ghost-stage").assertIsDisplayed()
        composeRule.onNodeWithTag("ghost-stage").assert(hasNoClickAction())
        composeRule.onNodeWithTag("list-ghost").performClick()
        composeRule.runOnIdle { assertEquals("list", selected) }
        openOverflowMenu()
        composeRule.onNodeWithTag("update").performClick()
        composeRule.runOnIdle { assertEquals("update", selected) }
        openOverflowMenu()
        composeRule.onNodeWithTag("readme").performClick()
        composeRule.runOnIdle { assertEquals("readme", selected) }
        openOverflowMenu()
        composeRule.onNodeWithTag("preferences").performClick()
        composeRule.runOnIdle { assertEquals("preferences", selected) }
        openOverflowMenu()
        composeRule.onNodeWithTag("help").performClick()
        composeRule.runOnIdle { assertEquals("help", selected) }
        composeRule.runOnIdle { loading.value = true }
        composeRule.onNodeWithTag("loading-overlay").assertIsDisplayed()
    }

    @Test
    fun shell_loading_overlay_blocks_top_app_bar_pointer_actions() {
        val loading = mutableStateOf(false)
        var selected = ""
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = loading.value,
                progressMessage = "Loading ghost",
                toolbarVisible = true,
                onListGhost = { selected = "list" },
                onUpdate = { selected = "update" },
                onReadme = { selected = "readme" },
                onPreferences = { selected = "preferences" },
                onHelp = { selected = "help" },
                onArchiveQueue = { selected = "queue" },
                showDebugControls = true,
                onDebug = { selected = "debug" },
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        val listButtonCenter = composeRule.onNodeWithTag("list-ghost").fetchSemanticsNode().boundsInRoot.center
        val debugButtonCenter = composeRule.onNodeWithTag("debug").fetchSemanticsNode().boundsInRoot.center

        composeRule.runOnIdle { loading.value = true }
        composeRule.onNodeWithTag("loading-overlay").assertIsDisplayed()

        composeRule.onRoot().performTouchInput {
            click(listButtonCenter)
        }
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput {
            click(debugButtonCenter)
        }
        composeRule.waitForIdle()

        assertEquals("", selected)
    }

    @Test
    fun shell_loading_overlay_allows_notice_dialog_tap_while_blocking_appbar() {
        var dismissed = false
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = true,
                progressMessage = "Loading ghost",
                toolbarVisible = true,
                onListGhost = {},
                onUpdate = {},
                onReadme = {},
                onPreferences = {},
                onHelp = {},
                showDebugControls = false,
                onDebug = {},
                simpleDialog = NanidroidSimpleDialog.Notice(
                    title = android.R.string.dialog_alert_title,
                    message = android.R.string.ok,
                    onConfirm = { dismissed = true },
                ),
                onDismissSimpleDialog = { dismissed = true },
            )
        }

        composeRule.onNodeWithTag("loading-overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("notice-confirm").performTouchInput { click() }
        composeRule.waitForIdle()

        assertEquals(true, dismissed)
    }

    @Test
    fun stalled_operation_remains_actionable_above_loading_and_other_dialogs() {
        val actions = mutableListOf<Pair<OperationHandle, DurableAttentionAction>>()
        val stalled = stalledRecord("archive-1", OperationStatus.RUNNING)
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = true,
                progressMessage = "Loading ghost",
                toolbarVisible = true,
                onListGhost = {},
                onUpdate = {},
                onPreferences = {},
                onHelp = {},
                simpleDialog = NanidroidSimpleDialog.Notice(
                    title = android.R.string.dialog_alert_title,
                    message = android.R.string.ok,
                ),
                onDismissSimpleDialog = {},
                stalledOperations = listOf(stalled),
                onDurableAttentionAction = { handle, action -> actions += handle to action },
            )
        }

        composeRule.onNodeWithText("Archive download needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Keep waiting").assertIsDisplayed()
        composeRule.onNodeWithText("Request stop").performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(OperationHandle(stalled.id, stalled.attemptId) to DurableAttentionAction.STOP),
                actions,
            )
        }
    }

    @Test
    fun stopping_dispatch_failure_offers_retry_without_a_second_stop() {
        val selected = mutableListOf<DurableAttentionAction>()
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
                stalledOperations = listOf(
                    stalledRecord(
                        id = "update-1",
                        status = OperationStatus.CANCEL_REQUESTED,
                        kind = OperationKind.GHOST_UPDATE,
                        diagnostics = CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX,
                    ),
                ),
                onDurableAttentionAction = { _, action -> selected += action },
            )
        }

        composeRule.onNodeWithText("Stopping…").assertIsDisplayed()
        composeRule.onNodeWithText("Retry stop request").performClick()
        assertNoNodeWithTag("durable-attention-stop", useUnmergedTree = true)
        composeRule.runOnIdle { assertEquals(listOf(DurableAttentionAction.RETRY_STOP), selected) }
    }

    @Test
    fun stop_advances_to_the_next_stalled_operation_without_waiting_for_terminal_cleanup() {
        val archive = stalledRecord("archive-1", OperationStatus.RUNNING)
        val update = stalledRecord(
            "update-1",
            OperationStatus.RUNNING,
            kind = OperationKind.GHOST_UPDATE,
        )
        val records = mutableStateOf(listOf(archive, update))
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
                stalledOperations = records.value,
                onDurableAttentionAction = { handle, action ->
                    if (handle.operationId == archive.id && action == DurableAttentionAction.STOP) {
                        records.value = listOf(
                            archive.copy(status = OperationStatus.CANCEL_REQUESTED),
                            update,
                        )
                    } else if (
                        handle.operationId == update.id && action == DurableAttentionAction.STOP
                    ) {
                        records.value = listOf(
                            archive.copy(
                                status = OperationStatus.CANCEL_REQUESTED,
                                diagnostics = CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX,
                            ),
                            update.copy(status = OperationStatus.CANCEL_REQUESTED),
                        )
                    }
                },
            )
        }

        composeRule.onNodeWithText("Archive download needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Request stop").performClick()

        composeRule.onNodeWithText("Ghost update needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Archive download — Stopping…").assertIsDisplayed()
        composeRule.onNodeWithText("Request stop").performClick()

        composeRule.onNodeWithText("Archive download needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Retry stop request").assertIsDisplayed()
        composeRule.onNodeWithText("Ghost update — Stopping…").assertIsDisplayed()
    }

    @Test
    fun stop_does_not_advance_until_the_exact_operation_enters_stopping() {
        val archive = stalledRecord("archive-1", OperationStatus.RUNNING)
        val update = stalledRecord(
            "update-1",
            OperationStatus.RUNNING,
            kind = OperationKind.GHOST_UPDATE,
        )
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
                stalledOperations = listOf(archive, update),
            )
        }

        composeRule.onNodeWithText("Request stop").performClick()

        composeRule.onNodeWithText("Archive download needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Request stop").assertIsDisplayed()
        composeRule.onNodeWithText("Ghost update needs attention").assertDoesNotExist()
        composeRule.onNodeWithText("Archive download — Stopping…").assertDoesNotExist()
    }

    @Test
    fun keep_waiting_does_not_advance_when_the_exact_action_is_rejected() {
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
                stalledOperations = listOf(
                    stalledRecord("archive-1", OperationStatus.RUNNING),
                    stalledRecord(
                        "update-1",
                        OperationStatus.RUNNING,
                        kind = OperationKind.GHOST_UPDATE,
                    ),
                ),
            )
        }

        composeRule.onNodeWithText("Keep waiting").performClick()

        composeRule.onNodeWithText("Archive download needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Ghost update needs attention").assertDoesNotExist()
    }

    @Test
    fun retry_stop_does_not_advance_while_the_exact_failure_remains() {
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
                stalledOperations = listOf(
                    stalledRecord(
                        "archive-1",
                        OperationStatus.CANCEL_REQUESTED,
                        diagnostics = CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX,
                    ),
                    stalledRecord(
                        "update-1",
                        OperationStatus.RUNNING,
                        kind = OperationKind.GHOST_UPDATE,
                    ),
                ),
            )
        }

        composeRule.onNodeWithText("Retry stop request").performClick()

        composeRule.onNodeWithText("Archive download needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Retry stop request").assertIsDisplayed()
        composeRule.onNodeWithText("Ghost update needs attention").assertDoesNotExist()
    }

    @Test
    fun retry_stop_advances_after_the_exact_failure_is_cleared() {
        val archive = stalledRecord(
            "archive-1",
            OperationStatus.CANCEL_REQUESTED,
            diagnostics = CANCELLATION_FAILURE_DIAGNOSTIC_PREFIX,
        )
        val update = stalledRecord(
            "update-1",
            OperationStatus.RUNNING,
            kind = OperationKind.GHOST_UPDATE,
        )
        val records = mutableStateOf(listOf(archive, update))
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
                stalledOperations = records.value,
                onDurableAttentionAction = { handle, action ->
                    if (
                        handle.operationId == archive.id &&
                        action == DurableAttentionAction.RETRY_STOP
                    ) {
                        records.value = listOf(archive.copy(diagnostics = null), update)
                    }
                },
            )
        }

        composeRule.onNodeWithText("Retry stop request").performClick()

        composeRule.onNodeWithText("Ghost update needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Archive download — Stopping…").assertIsDisplayed()
    }

    @Test
    fun stop_from_another_action_surface_advances_the_exact_selected_operation() {
        val archive = stalledRecord("archive-1", OperationStatus.RUNNING)
        val update = stalledRecord(
            "update-1",
            OperationStatus.RUNNING,
            kind = OperationKind.GHOST_UPDATE,
        )
        val records = mutableStateOf(listOf(archive, update))
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
                stalledOperations = records.value,
            )
        }

        composeRule.onNodeWithText("Archive download needs attention").assertIsDisplayed()
        composeRule.runOnIdle {
            records.value = listOf(
                archive.copy(status = OperationStatus.CANCEL_REQUESTED),
                update,
            )
        }

        composeRule.onNodeWithText("Ghost update needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Archive download — Stopping…").assertIsDisplayed()
    }

    @Test
    fun stopping_summary_clears_when_the_exact_operation_is_no_longer_actionable() {
        val archive = stalledRecord("archive-1", OperationStatus.RUNNING)
        val update = stalledRecord(
            "update-1",
            OperationStatus.RUNNING,
            kind = OperationKind.GHOST_UPDATE,
        )
        val records = mutableStateOf(listOf(archive, update))
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
                stalledOperations = records.value,
                onDurableAttentionAction = { handle, action ->
                    if (handle.operationId == archive.id && action == DurableAttentionAction.STOP) {
                        records.value = listOf(
                            archive.copy(status = OperationStatus.CANCEL_REQUESTED),
                            update,
                        )
                    }
                },
            )
        }

        composeRule.onNodeWithText("Request stop").performClick()
        composeRule.onNodeWithText("Archive download — Stopping…").assertIsDisplayed()

        composeRule.runOnIdle { records.value = listOf(update) }

        composeRule.onNodeWithText("Ghost update needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Archive download — Stopping…").assertDoesNotExist()
    }

    @Test
    fun stalled_prompt_suppresses_later_lower_modals_until_attention_clears() {
        val records = mutableStateOf(
            listOf(stalledRecord("archive-1", OperationStatus.RUNNING)),
        )
        val dialog = mutableStateOf<NanidroidSimpleDialog?>(null)
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
                simpleDialog = dialog.value,
                onDismissSimpleDialog = {},
                stalledOperations = records.value,
                transientOverlay = { Text("Debug tools", Modifier.testTag("test-debug-tools")) },
            )
        }

        composeRule.runOnIdle {
            dialog.value = NanidroidSimpleDialog.Notice(
                title = android.R.string.dialog_alert_title,
                message = android.R.string.ok,
            )
        }
        composeRule.onNodeWithText("Archive download needs attention").assertIsDisplayed()
        assertNoNodeWithTag("notice-confirm", useUnmergedTree = true)
        assertNoNodeWithTag("test-debug-tools", useUnmergedTree = true)

        composeRule.runOnIdle { records.value = emptyList() }
        composeRule.onNodeWithTag("notice-confirm").assertIsDisplayed()
        composeRule.onNodeWithText("Debug tools").assertIsDisplayed()
    }

    @Test
    fun stalled_prompt_restores_internal_transient_overlay_state_after_suppression() {
        val records = mutableStateOf(emptyList<DurableOperationRecord>())
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
                stalledOperations = records.value,
                transientOverlay = {
                    val internalCount = rememberSaveable { mutableStateOf(0) }
                    Button(
                        modifier = Modifier.testTag("test-transient-state"),
                        onClick = { internalCount.value += 1 },
                    ) {
                        Text("Transient state ${internalCount.value}")
                    }
                },
            )
        }

        composeRule.onNodeWithText("Transient state 0").performClick()
        composeRule.onNodeWithText("Transient state 1").assertIsDisplayed()

        composeRule.runOnIdle {
            records.value = listOf(stalledRecord("archive-1", OperationStatus.RUNNING))
        }
        assertNoNodeWithTag("test-transient-state", useUnmergedTree = true)
        composeRule.runOnIdle { records.value = emptyList() }

        composeRule.onNodeWithText("Transient state 1").assertIsDisplayed()
    }

    @Test
    fun durable_store_recovery_requires_explicit_acknowledgement_and_restores_lower_modals() {
        val recoveryRequired = mutableStateOf(true)
        val resolutionSucceeds = mutableStateOf(false)
        var resolutionAttempts = 0
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
                simpleDialog = NanidroidSimpleDialog.Notice(
                    title = android.R.string.dialog_alert_title,
                    message = android.R.string.ok,
                ),
                onDismissSimpleDialog = {},
                durableRecoveryRequired = recoveryRequired.value,
                onResolveDurableRecovery = {
                    resolutionAttempts += 1
                    resolutionSucceeds.value.also { resolved ->
                        if (resolved) recoveryRequired.value = false
                    }
                },
                transientOverlay = {
                    Text("Debug tools", Modifier.testTag("test-debug-tools"))
                },
            )
        }

        composeRule.onNodeWithTag("durable-store-recovery-prompt").assertIsDisplayed()
        composeRule.onNodeWithText("Request stop").assertDoesNotExist()
        composeRule.onNodeWithText("Keep waiting").assertDoesNotExist()
        assertNoNodeWithTag("notice-confirm", useUnmergedTree = true)
        assertNoNodeWithTag("test-debug-tools", useUnmergedTree = true)

        composeRule.onNodeWithTag("durable-store-recovery-confirm").performClick()
        composeRule.onNodeWithTag("durable-store-recovery-error").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, resolutionAttempts) }

        composeRule.runOnIdle { resolutionSucceeds.value = true }
        composeRule.onNodeWithTag("durable-store-recovery-confirm").performClick()
        composeRule.onNodeWithTag("notice-confirm").assertIsDisplayed()
        composeRule.onNodeWithText("Debug tools").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(2, resolutionAttempts) }
    }

    @Test
    fun long_diagnostic_body_scrolls_while_actions_remain_reachable() {
        val diagnostic = buildString {
            repeat(80) { index -> append("Diagnostic line $index\n") }
        }.take(512)
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
                stalledOperations = listOf(
                    stalledRecord(
                        id = "archive-1",
                        status = OperationStatus.RUNNING,
                        diagnostics = diagnostic,
                    ),
                ),
            )
        }
        composeRule.onNodeWithTag("durable-attention-body").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )
        composeRule.onNodeWithText("Keep waiting").assertIsDisplayed()
        composeRule.onNodeWithText("Request stop").assertIsDisplayed()
    }

    @Test
    fun shell_top_app_bar_and_overflow_popup_dont_persist_when_loading_becomes_true() {
        val loading = mutableStateOf(false)
        var selected = ""
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = loading.value,
                progressMessage = "Loading ghost",
                toolbarVisible = true,
                onListGhost = { selected = "list" },
                onUpdate = { selected = "update" },
                onReadme = { selected = "readme" },
                onPreferences = { selected = "preferences" },
                onHelp = { selected = "help" },
                onArchiveQueue = { selected = "queue" },
                showDebugControls = false,
                onDebug = {},
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        composeRule.onNodeWithTag("appbar-overflow").performClick()
        composeRule.onNodeWithTag("update").assertIsDisplayed()
        val updateCenter = composeRule.onNodeWithTag("update").fetchSemanticsNode().boundsInRoot.center

        composeRule.runOnIdle { loading.value = true }
        composeRule.onNodeWithTag("loading-overlay").assertIsDisplayed()

        assertNoNodeWithTag("appbar-overflow", true)
        assertNoNodeWithTag("update", true)
        assertNoNodeWithTag("list-ghost")
        composeRule.onRoot().performTouchInput { click(updateCenter) }
        composeRule.waitForIdle()
        assertEquals("", selected)
    }

    @Test
    fun shell_overflow_popup_is_not_restored_with_the_activity() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = false,
                progressMessage = "Loading ghost",
                toolbarVisible = true,
                onListGhost = {},
                onUpdate = {},
                onReadme = {},
                onPreferences = {},
                onHelp = {},
                showDebugControls = false,
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }
        composeRule.onNodeWithTag("appbar-overflow").performClick()
        composeRule.onNodeWithTag("update").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("appbar-overflow").assertIsDisplayed()
        assertNoNodeWithTag("update", useUnmergedTree = true)
    }

    @Test
    fun shell_overflow_actions_show_downloads_and_route_readme() {
        var selected = ""
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = false,
                progressMessage = "Loading ghost",
                toolbarVisible = true,
                onListGhost = {},
                onUpdate = {},
                onReadme = { selected = "readme" },
                onPreferences = {},
                onHelp = {},
                onArchiveQueue = { selected = "archive-queue" },
                archiveDownloads = listOf(
                    NarDownload("download-1", NarDownloadSource.Remote("https://example.test/1.nar")),
                    NarDownload("download-2", NarDownloadSource.Remote("https://example.test/2.nar")),
                ),
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        openOverflowMenu()
        composeRule.onNodeWithTag("archive-queue").performClick()
        composeRule.runOnIdle { assertEquals("archive-queue", selected) }
        openOverflowMenu()
        composeRule.onNodeWithTag("readme").performClick()
        composeRule.runOnIdle { assertEquals("readme", selected) }
        openOverflowMenu()
        composeRule.onNodeWithText("2 downloads").assertIsDisplayed()
    }

    @Test
    fun shell_overflow_exposes_archive_queue_status_before_the_menu_opens() {
        val downloads = mutableStateOf(emptyList<NarDownload>())
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = false,
                progressMessage = "Loading ghost",
                toolbarVisible = true,
                onListGhost = {},
                onUpdate = {},
                onPreferences = {},
                onHelp = {},
                archiveDownloads = downloads.value,
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        fun assertOverflowDescription(expected: String) {
            composeRule.onNodeWithTag("appbar-overflow").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(expected)),
            )
        }

        assertOverflowDescription("More, no archive downloads")
        assertNoNodeWithTag("archive-queue-status", useUnmergedTree = true)

        downloads.value = listOf(
            NarDownload(
                "active",
                NarDownloadSource.Remote("https://example.test/active.nar"),
                state = NarDownloadState.Downloading,
            ),
        )
        assertOverflowDescription("More, 1 download active")
        composeRule.onNodeWithTag("archive-queue-status", useUnmergedTree = true).assertIsDisplayed()

        downloads.value = listOf(
            NarDownload(
                "complete",
                NarDownloadSource.Remote("https://example.test/complete.nar"),
                state = NarDownloadState.Complete,
            ),
        )
        assertOverflowDescription("More, 1 download complete")
        composeRule.onNodeWithTag("archive-queue-status", useUnmergedTree = true).assertIsDisplayed()

        downloads.value = listOf(
            NarDownload(
                "attention",
                NarDownloadSource.Remote("https://example.test/attention.nar"),
                state = NarDownloadState.NeedsAttention(
                    NarDownloadState.Failure("network failed"),
                ),
            ),
        )
        assertOverflowDescription("More, 1 download needs attention")
        composeRule.onNodeWithTag("archive-queue-status", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun shell_routes_debug_callback_when_enabled_and_hides_debug_controls_when_disabled() {
        var selected = ""
        val debugEnabled = mutableStateOf(true)
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = false,
                progressMessage = "Loading ghost",
                toolbarVisible = true,
                onListGhost = {},
                onUpdate = {},
                onReadme = {},
                onPreferences = {},
                onHelp = {},
                onDebug = { selected = "debug" },
                showDebugControls = debugEnabled.value,
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        composeRule.onNodeWithTag("debug").performClick()
        composeRule.runOnIdle { assertEquals("debug", selected) }

        composeRule.runOnIdle { debugEnabled.value = false }
        composeRule.waitForIdle()
        assertNoNodeWithTag("debug")
    }

    @Test
    fun shell_keeps_old_debug_toolbar_tags_out_of_the_chrome() {
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = false,
                progressMessage = "Loading ghost",
                toolbarVisible = true,
                onListGhost = {},
                onUpdate = {},
                onReadme = {},
                onPreferences = {},
                onHelp = {},
                showDebugControls = true,
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        assertNoNodeWithTag("debug-next-surface", true)
        assertNoNodeWithTag("debug-draw-cbox", true)
        assertNoNodeWithTag("debug-dump-surfaces", true)
        assertNoNodeWithTag("debug-run", true)
        assertNoNodeWithTag("debug-nar", true)
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
    fun compact_landscape_user_input_keeps_cancel_and_submit_above_the_real_ime() {
        requestLandscape()
        val fixture = UserInputFixture()
        renderUserInput(fixture)

        showIme()
        explicitAction("script-user-input-cancel").assertImeSafeAndTappable()
        explicitAction("script-user-input-confirm").assertImeSafeAndTappable()
    }

    @Test
    fun compact_landscape_user_input_cancel_is_reachable_while_the_ime_is_visible() {
        requestLandscape()
        val fixture = UserInputFixture()
        renderUserInput(fixture)

        showIme()
        explicitAction("script-user-input-cancel").tap()
        composeRule.waitForIdle()

        assertEquals(1, fixture.cancelled)
        assertEquals(emptyList<String>(), fixture.submitted)
        assertFalse(fixture.open.value)
    }

    @Test
    fun compact_landscape_user_input_submit_is_reachable_while_the_ime_is_visible() {
        requestLandscape()
        val fixture = UserInputFixture()
        renderUserInput(fixture)

        composeRule.onNodeWithTag("script-user-input").performTextReplacement("Cat")
        showIme()
        explicitAction("script-user-input-confirm").tap()
        composeRule.waitForIdle()

        assertEquals(0, fixture.cancelled)
        assertEquals(listOf("name:Cat"), fixture.submitted)
        assertFalse(fixture.open.value)
    }

    @Test
    fun ime_done_submits_user_input_once() {
        val fixture = UserInputFixture()
        renderUserInput(fixture)

        composeRule.onNodeWithTag("script-user-input").performTextReplacement("Cat")
        showIme()
        composeRule.onNodeWithTag("script-user-input").performImeAction()
        composeRule.waitForIdle()

        assertEquals(0, fixture.cancelled)
        assertEquals(listOf("name:Cat"), fixture.submitted)
        assertFalse(fixture.open.value)
    }

    @Test
    fun portrait_user_input_keeps_explicit_actions_above_the_real_ime() {
        val fixture = UserInputFixture()
        renderUserInput(fixture)

        showIme()
        explicitAction("script-user-input-cancel").assertImeSafeAndTappable()
        explicitAction("script-user-input-confirm").assertImeSafeAndTappable()
    }

    @Test
    fun user_input_surface_padding_does_not_dismiss_presentation() {
        val fixture = UserInputFixture()
        renderUserInput(fixture)

        val surface = userInputSurface().fetchSemanticsNode().boundsInWindow
        assertTrue(
            "Could not tap noninteractive Surface padding",
            uiDevice().click((surface.left + 1f).toInt(), (surface.top + 1f).toInt()),
        )
        composeRule.waitForIdle()

        assertTrue("Surface-padding tap dismissed the presentation", fixture.open.value)
        assertEquals(0, fixture.cancelled)
        assertEquals(emptyList<String>(), fixture.submitted)
    }

    @Test
    fun user_input_dimmed_margin_dismisses_presentation_without_cancelling() {
        val fixture = UserInputFixture()
        renderUserInput(fixture)

        val surface = userInputSurface().fetchSemanticsNode().boundsInWindow
        val marginX = if (surface.left > 1f) surface.left - 1f else surface.right + 1f
        assertTrue("Expected a dimmed margin around the centered dialog Surface", marginX >= 0f)
        assertTrue(
            "Could not tap the dimmed margin",
            uiDevice().click(marginX.toInt(), surface.center.y.toInt()),
        )
        composeRule.waitUntil(timeoutMillis = 5_000) { !fixture.open.value }

        assertEquals(0, fixture.cancelled)
        assertEquals(emptyList<String>(), fixture.submitted)
    }

    @Test
    fun user_input_vertical_dimmed_margin_dismisses_presentation_without_cancelling() {
        val fixture = UserInputFixture()
        renderUserInput(fixture)

        val surface = userInputSurface().fetchSemanticsNode().boundsInWindow
        val marginY = if (surface.top > 1f) surface.top - 1f else surface.bottom + 1f
        assertTrue("Expected a vertical dimmed margin around the centered dialog Surface", marginY >= 0f)
        assertTrue(
            "Could not tap the vertical dimmed margin",
            uiDevice().click(surface.center.x.toInt(), marginY.toInt()),
        )
        composeRule.waitUntil(timeoutMillis = 5_000) { !fixture.open.value }

        assertEquals(0, fixture.cancelled)
        assertEquals(emptyList<String>(), fixture.submitted)
    }
    @Test
    fun user_input_safe_area_edge_strip_dismisses_presentation_without_cancelling() {
        val fixture = UserInputFixture()
        renderUserInput(fixture)

        val surface = userInputSurface().fetchSemanticsNode().boundsInWindow
        val edgeY = systemBarTopInset() + oneDpInPixels()
        assertTrue("Expected the safe-area edge strip above the Surface", edgeY < surface.top)
        assertTrue(
            "Could not tap the safe-area edge strip",
            uiDevice().click(surface.center.x.toInt(), edgeY),
        )
        composeRule.waitUntil(timeoutMillis = 5_000) { !fixture.open.value }

        assertEquals(0, fixture.cancelled)
        assertEquals(emptyList<String>(), fixture.submitted)
    }

    @Test
    fun real_160dp_display_keeps_explicit_actions_at_minimum_touch_width() {
        setDisplaySize(width = 320, height = 640)
        val fixture = UserInputFixture()
        renderUserInput(fixture)

        assertActionsAreIndependentlyReachable(
            explicitAction("script-user-input-cancel"),
            explicitAction("script-user-input-confirm"),
        )
    }

    @Test
    fun real_narrow_display_keeps_cancel_and_submit_independently_reachable() {
        setDisplaySize(width = 360, height = 640)
        val cancelFixture = UserInputFixture()
        val activeFixture = mutableStateOf(cancelFixture)
        composeRule.setContent {
            UserInputFixtureContent(activeFixture.value)
        }

        val cancel = explicitAction("script-user-input-cancel")
        val submit = explicitAction("script-user-input-confirm")
        assertActionsAreIndependentlyReachable(cancel, submit)
        cancel.tap()
        composeRule.waitUntil(timeoutMillis = 5_000) { !cancelFixture.open.value }
        assertEquals(1, cancelFixture.cancelled)
        assertEquals(emptyList<String>(), cancelFixture.submitted)

        val submitFixture = UserInputFixture()
        composeRule.runOnIdle { activeFixture.value = submitFixture }
        composeRule.waitForIdle()
        val nextCancel = explicitAction("script-user-input-cancel")
        val nextSubmit = explicitAction("script-user-input-confirm")
        assertActionsAreIndependentlyReachable(nextCancel, nextSubmit)
        nextSubmit.tap()
        composeRule.waitUntil(timeoutMillis = 5_000) { !submitFixture.open.value }
        assertEquals(0, submitFixture.cancelled)
        assertEquals(listOf("name:"), submitFixture.submitted)
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

    private fun requestLandscape() {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
    }

    private fun renderUserInput(fixture: UserInputFixture) {
        composeRule.setContent {
            UserInputFixtureContent(fixture)
        }
    }

    @Composable
    private fun UserInputFixtureContent(fixture: UserInputFixture) {
            NanidroidSimpleDialogHost(
                dialog = if (fixture.open.value) {
                    NanidroidSimpleDialog.UserInput(
                        id = "name",
                        value = fixture.value.value,
                        onValueChanged = { fixture.value.value = it },
                        onSubmit = { id, value -> fixture.submitted += "$id:$value" },
                        onCancel = { fixture.cancelled++ },
                    )
                } else {
                    null
                },
                onDismiss = { fixture.open.value = false },
            )
    }

    private fun showIme() {
        composeRule.onNodeWithTag("script-user-input").performClick()
        waitForSettledImeAndDialog()
    }

    private fun waitForSettledImeAndDialog() {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var previous: ImeDialogGeometry? = null
        var matchingSamples = 0

        while (SystemClock.elapsedRealtime() < deadline) {
            composeRule.waitForIdle()
            val current = currentImeDialogGeometry()
            if (current != null && current.imeBottom > 0) {
                matchingSamples = if (current == previous) matchingSamples + 1 else 1
                if (matchingSamples >= 3) return
                previous = current
            } else {
                previous = null
                matchingSamples = 0
            }
            SystemClock.sleep(100)
        }

        throw AssertionError("The IME and user-input dialog did not settle within 5 seconds")
    }

    private fun currentImeDialogGeometry(): ImeDialogGeometry? {
        val cancel = runCatching {
            composeRule.onNodeWithTag("script-user-input-cancel")
                .fetchSemanticsNode()
                .boundsInWindow
        }.getOrNull() ?: return null
        val submit = runCatching {
            composeRule.onNodeWithTag("script-user-input-confirm")
                .fetchSemanticsNode()
                .boundsInWindow
        }.getOrNull() ?: return null
        return ImeDialogGeometry(
            imeBottom = imeBottomInset(),
            cancelLeft = cancel.left.toInt(),
            cancelTop = cancel.top.toInt(),
            cancelRight = cancel.right.toInt(),
            cancelBottom = cancel.bottom.toInt(),
            submitLeft = submit.left.toInt(),
            submitTop = submit.top.toInt(),
            submitRight = submit.right.toInt(),
            submitBottom = submit.bottom.toInt(),
        )
    }

    private fun explicitAction(tag: String): SemanticsNodeInteraction = composeRule.onNodeWithTag(tag)
        .assertIsDisplayed()

    private fun userInputSurface(): SemanticsNodeInteraction = composeRule.onNode(
        SemanticsMatcher.expectValue(
            SemanticsProperties.PaneTitle,
            composeRule.activity.getString(R.string.user_input_dlg_title),
        ),
    )

    private fun setDisplaySize(width: Int, height: Int) {
        uiAutomation().executeShellCommand("wm size ${width}x${height}").close()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.activity.window.decorView.width <= width
        }
    }
    private fun SemanticsNodeInteraction.assertImeSafeAndTappable() {
        val visible = fetchSemanticsNode().boundsInWindow
        val safeBottom = composeRule.activity.window.decorView.height - imeBottomInset()
        val minimumTargetSize = (48 * composeRule.activity.resources.displayMetrics.density).toInt()

        assertTrue("action has no visible bounds", visible.height > 0f)
        assertTrue("action is covered by the IME: $visible > $safeBottom", visible.bottom <= safeBottom)
        assertTrue("action has only ${visible.height}px visible", visible.height >= minimumTargetSize)
    }

    private fun SemanticsNodeInteraction.tap() {
        assertImeSafeAndTappable()
        val bounds = fetchSemanticsNode().boundsInWindow
        assertTrue("Could not tap explicit dialog action", uiDevice().click(bounds.center.x.toInt(), bounds.center.y.toInt()))
    }

    private fun assertActionsAreIndependentlyReachable(
        cancel: SemanticsNodeInteraction,
        submit: SemanticsNodeInteraction,
    ) {
        cancel.assertIsDisplayed()
        submit.assertIsDisplayed()
        val cancelBounds = cancel.fetchSemanticsNode().boundsInWindow
        val submitBounds = submit.fetchSemanticsNode().boundsInWindow
        val minimumTargetSize = 48 * composeRule.activity.resources.displayMetrics.density

        assertTrue("Cancel target is too short: ${cancelBounds.height}px", cancelBounds.height >= minimumTargetSize)
        assertTrue("Cancel target is too narrow: ${cancelBounds.width}px", cancelBounds.width >= minimumTargetSize)
        assertTrue("Submit target is too short: ${submitBounds.height}px", submitBounds.height >= minimumTargetSize)
        assertTrue("Submit target is too narrow: ${submitBounds.width}px", submitBounds.width >= minimumTargetSize)
        assertTrue(
            "Cancel and submit targets overlap: $cancelBounds / $submitBounds",
            cancelBounds.right <= submitBounds.left || submitBounds.right <= cancelBounds.left ||
                cancelBounds.bottom <= submitBounds.top || submitBounds.bottom <= cancelBounds.top,
        )
    }

    private fun systemBarTopInset(): Int = composeRule.activity.window.decorView.rootWindowInsets
        ?.getInsets(WindowInsets.Type.systemBars())
        ?.top
        ?: 0

    private fun oneDpInPixels(): Int = composeRule.activity.resources.displayMetrics.density.toInt()

    private fun imeBottomInset(): Int = composeRule.activity.window.decorView.rootWindowInsets
        ?.getInsets(WindowInsets.Type.ime())
        ?.bottom
        ?: 0

    private fun uiDevice(): UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun uiAutomation() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private class UserInputFixture {
        val open = mutableStateOf(true)
        val value = mutableStateOf("")
        val submitted = mutableListOf<String>()
        var cancelled = 0
    }

    private data class ImeDialogGeometry(
        val imeBottom: Int,
        val cancelLeft: Int,
        val cancelTop: Int,
        val cancelRight: Int,
        val cancelBottom: Int,
        val submitLeft: Int,
        val submitTop: Int,
        val submitRight: Int,
        val submitBottom: Int,
    )

    private fun stalledRecord(
        id: String,
        status: OperationStatus,
        kind: OperationKind = OperationKind.REMOTE_NAR,
        diagnostics: String? = null,
    ) = DurableOperationRecord(
        id = OperationId(id),
        attemptId = AttemptId(1L),
        kind = kind,
        externalJob = null,
        progress = OperationProgress("internal phase", 0L),
        status = status,
        showStallPrompt = true,
        diagnostics = diagnostics,
    )

}
