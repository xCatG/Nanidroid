package com.cattailsw.nanidroid.compose

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import com.cattailsw.nanidroid.runtime.GhostPresentationReducer
import com.cattailsw.nanidroid.install.NarDownload
import com.cattailsw.nanidroid.install.NarDownloadSource
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
