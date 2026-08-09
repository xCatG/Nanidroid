package com.cattailsw.nanidroid.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.runtime.dialogue.InputPresentation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NanidroidSimpleDialogsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun passwordInputUsesPasswordKeyboardSemantics() {
        composeRule.setContent {
            InputDialog(
                presentation = InputPresentation(obscured = true),
            )
        }

        composeRule.onNodeWithTag("script-user-input")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
    }

    @Test
    fun multilineInputKeepsNewlinesAndUsesExplicitConfirm() {
        var submitted: String? = null
        composeRule.setContent {
            InputDialog(
                presentation = InputPresentation(multiline = true),
                onSubmit = { submitted = it },
            )
        }

        composeRule.onNodeWithTag("script-user-input").performTextInput("first\nsecond")
        composeRule.onNodeWithTag("script-user-input")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Default))
        composeRule.onNodeWithTag("script-user-input-confirm")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals("first\nsecond", submitted) }
    }

    @Test
    fun requiredInputExposesErrorAndDisablesConfirmWithoutCancel() {
        composeRule.setContent {
            InputDialog(
                presentation = InputPresentation(requireNonEmpty = true, allowCancel = false),
            )
        }

        composeRule.onNodeWithTag("script-user-input")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        composeRule.onNodeWithTag("script-user-input-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("script-user-input-cancel").assertDoesNotExist()
    }

    @Test
    fun compactInputPaneAndConfirmRemainDiscoverable() = assertDialogDiscoverable(
        DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
    )

    @Test
    fun expandedInputPaneAndConfirmRemainDiscoverable() = assertDialogDiscoverable(
        DeviceConfigurationOverride.WindowSize(DpSize(1000.dp, 800.dp)),
    )

    @Test
    fun largeFontInputPaneAndConfirmRemainDiscoverable() = assertDialogDiscoverable(
        DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)) then
            DeviceConfigurationOverride.FontScale(1.5f),
    )

    private fun assertDialogDiscoverable(configuration: DeviceConfigurationOverride) {
        composeRule.setContent {
            DeviceConfigurationOverride(configuration) {
                InputDialog()
            }
        }
        composeRule.onNodeWithTag("script-user-input").assertIsDisplayed()
        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Input"),
        ).assertCountEquals(1)
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertCountEquals(1)
        composeRule.onNodeWithTag("script-user-input-confirm")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @androidx.compose.runtime.Composable
    private fun InputDialog(
        presentation: InputPresentation = InputPresentation(),
        onSubmit: (String) -> Unit = {},
    ) {
        var value by remember { mutableStateOf("") }
        NanidroidSimpleDialogHost(
            dialog = NanidroidSimpleDialog.UserInput(
                id = "test",
                value = value,
                presentation = presentation,
                onValueChanged = { value = it },
                onSubmit = { _, input -> onSubmit(input) },
                onCancel = {},
            ),
            onDismiss = {},
        )
    }
}
