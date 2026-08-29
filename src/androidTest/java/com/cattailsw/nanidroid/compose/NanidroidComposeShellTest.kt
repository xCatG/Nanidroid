package com.cattailsw.nanidroid.compose

import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
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
import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarImportAttemptToken
import com.cattailsw.nanidroid.install.NarImportPrimaryOutcome
import com.cattailsw.nanidroid.runtime.runtimePresentation


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
    fun shell_exposes_retained_controls_without_preferences_and_keeps_the_stage_in_compose() {
        var selected = ""
        val loading = mutableStateOf(false)
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = loading.value,
                progressMessage = "Loading ghost",
                toolbarVisible = true,
                onListGhost = { selected = "list" },
                onReadme = { selected = "readme" },
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        composeRule.onNodeWithTag("ghost-stage").assertIsDisplayed()
        composeRule.onNodeWithTag("ghost-stage").assert(hasNoClickAction())
        composeRule.onNodeWithTag("list-ghost").performClick()
        composeRule.runOnIdle { assertEquals("list", selected) }
        openOverflowMenu()
        assertNoNodeWithTag("update", useUnmergedTree = true)
        listOf("Check updates", "更新を確認", "檢查更新").forEach { removedLabel ->
            composeRule.onNodeWithText(removedLabel).assertDoesNotExist()
        }
        composeRule.onNodeWithTag("readme").assertIsDisplayed()
        assertNoNodeWithTag("archive-queue", useUnmergedTree = true)
        assertNoNodeWithTag("archive-queue-status", useUnmergedTree = true)
        composeRule.onNodeWithTag("readme").performClick()
        composeRule.runOnIdle { assertEquals("readme", selected) }
        openOverflowMenu()
        assertNoNodeWithTag("preferences")
        assertNoNodeWithTag("help")
        assertNoNodeWithTag("debug")
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
                onReadme = { selected = "readme" },
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        val listButtonCenter = composeRule.onNodeWithTag("list-ghost").fetchSemanticsNode().boundsInRoot.center

        composeRule.runOnIdle { loading.value = true }
        composeRule.onNodeWithTag("loading-overlay").assertIsDisplayed()

        composeRule.onRoot().performTouchInput {
            click(listButtonCenter)
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
                onReadme = {},
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
    fun storage_unavailable_notice_overrides_recovery_required_import_modal() {
        val token = NarImportAttemptToken("process", 1)
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = false,
                progressMessage = "",
                toolbarVisible = false,
                onListGhost = {},
                narImportState = ForegroundNarImportState.RecoveryRequired(
                    token = token,
                    primary = NarImportPrimaryOutcome.Interrupted,
                    message = "Private staging could not be reconciled.",
                ),
                simpleDialog = NanidroidSimpleDialog.Notice(
                    title = R.string.err_title,
                    message = R.string.err_no_sdcard,
                ),
                onDismissSimpleDialog = {},
            )
        }

        composeRule.onNodeWithTag("notice-confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("nar-import-retry-cleanup").assertDoesNotExist()
    }

    @Test
    fun ordinary_notice_remains_below_recovery_required_import_modal() {
        val token = NarImportAttemptToken("process", 2)
        composeRule.setContent {
            NanidroidComposeShell(
                ghostStage = {},
                loading = false,
                progressMessage = "",
                toolbarVisible = false,
                onListGhost = {},
                narImportState = ForegroundNarImportState.RecoveryRequired(
                    token = token,
                    primary = NarImportPrimaryOutcome.Interrupted,
                    message = "Private staging could not be reconciled.",
                ),
                simpleDialog = NanidroidSimpleDialog.Notice(
                    title = android.R.string.dialog_alert_title,
                    message = android.R.string.ok,
                ),
                onDismissSimpleDialog = {},
            )
        }

        composeRule.onNodeWithTag("nar-import-retry-cleanup").assertIsDisplayed()
        composeRule.onNodeWithTag("notice-confirm").assertDoesNotExist()
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
                onReadme = { selected = "readme" },
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }

        composeRule.onNodeWithTag("appbar-overflow").performClick()
        composeRule.onNodeWithTag("readme").assertIsDisplayed()
        val readmeCenter = composeRule.onNodeWithTag("readme").fetchSemanticsNode().boundsInRoot.center

        composeRule.runOnIdle { loading.value = true }
        composeRule.onNodeWithTag("loading-overlay").assertIsDisplayed()

        assertNoNodeWithTag("appbar-overflow", true)
        assertNoNodeWithTag("readme", true)
        assertNoNodeWithTag("list-ghost")
        composeRule.onRoot().performTouchInput { click(readmeCenter) }
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
                onReadme = {},
                simpleDialog = null,
                onDismissSimpleDialog = {},
            )
        }
        composeRule.onNodeWithTag("appbar-overflow").performClick()
        composeRule.onNodeWithTag("readme").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("appbar-overflow").assertIsDisplayed()
        assertNoNodeWithTag("readme", useUnmergedTree = true)
    }

    @Test
    fun operational_notice_confirmation_stays_in_the_compose_host() {
        var confirmed = false
        composeRule.setContent {
            NanidroidSimpleDialogHost(
                dialog = NanidroidSimpleDialog.Notice(
                    title = android.R.string.dialog_alert_title,
                    message = android.R.string.ok,
                    onConfirm = { confirmed = true },
                ),
                onDismiss = {},
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
                    SizedGhostPresentationStage(
                        presentation = runtimePresentation(
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
    fun ghost_list_exposes_selection_more_and_dismissal_actions() {
        var selected = -1
        var more = false
        var dismissed = false
        composeRule.setContent {
            NanidroidSimpleDialogHost(
                dialog = NanidroidSimpleDialog.GhostList(
                    names = listOf("Fixture Ghost"),
                    ids = listOf("fixture"),
                    onSelect = { selected = it },
                    onMore = { more = true },
                ),
                onDismiss = { dismissed = true },
            )
        }
        composeRule.onNodeWithTag("ghost-choice-0").performClick()
        composeRule.runOnIdle { assertEquals(0, selected) }
        composeRule.onNodeWithTag("ghost-list-more").performClick()
        composeRule.runOnIdle { assertEquals(true, more) }
        composeRule.runOnIdle { dismissed = false }
        composeRule.onNodeWithTag("ghost-list-cancel").performClick()
        composeRule.runOnIdle { assertEquals(true, dismissed) }
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

}
