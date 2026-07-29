package com.cattailsw.nanidroid.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.R

/** Compose-owned activity dialogs. State values are kept in the Activity bundle. */
internal sealed interface NanidroidSimpleDialog {
    data class Notice(@param:StringRes val title: Int, @param:StringRes val message: Int, val onConfirm: (() -> Unit)? = null) : NanidroidSimpleDialog
    data class DebugMessage(val message: String) : NanidroidSimpleDialog
    data class HelpMenu(val onGeneralHelp: () -> Unit, val onAbout: () -> Unit, val onFeedback: () -> Unit) : NanidroidSimpleDialog
    data class GeneralHelp(val onInstallHelp: () -> Unit, val onSupportedOperations: () -> Unit) : NanidroidSimpleDialog
    data class MoreGhost(val onEnterUrl: () -> Unit, val onInstallFromSdCard: () -> Unit, val onGhostTown: () -> Unit) : NanidroidSimpleDialog
    data class UrlEntry(val value: String, val validationError: Boolean, val onValueChanged: (String) -> Unit, val onSubmit: (String) -> Boolean, val onInvalid: () -> Unit) : NanidroidSimpleDialog
    data class UserInput(val id: String, val value: String, val onValueChanged: (String) -> Unit, val onSubmit: (String, String) -> Unit, val onCancel: () -> Unit) : NanidroidSimpleDialog
    data class UserChoice(val labels: List<String>, val ids: List<String>, val onChoice: (String) -> Unit) : NanidroidSimpleDialog
    data class GhostList(val names: List<String>, val ids: List<String>, val onSelect: (Int) -> Unit, val onMore: () -> Unit, val onCancel: () -> Unit) : NanidroidSimpleDialog
}

@Composable
internal fun NanidroidSimpleDialogHost(dialog: NanidroidSimpleDialog?, onDismiss: () -> Unit) {
    when (dialog) {
        null -> Unit
        is NanidroidSimpleDialog.Notice -> NoticeDialog(dialog, onDismiss)
        is NanidroidSimpleDialog.DebugMessage -> DebugDialog(dialog, onDismiss)
        is NanidroidSimpleDialog.HelpMenu -> HelpMenuDialog(dialog, onDismiss)
        is NanidroidSimpleDialog.GeneralHelp -> GeneralHelpDialog(dialog, onDismiss)
        is NanidroidSimpleDialog.MoreGhost -> MoreGhostDialog(dialog, onDismiss)
        is NanidroidSimpleDialog.UrlEntry -> UrlEntryDialog(dialog, onDismiss)
        is NanidroidSimpleDialog.UserInput -> UserInputDialog(dialog, onDismiss)
        is NanidroidSimpleDialog.UserChoice -> UserChoiceDialog(dialog, onDismiss)
        is NanidroidSimpleDialog.GhostList -> GhostListDialog(dialog, onDismiss)
    }
}

@Composable private fun NoticeDialog(dialog: NanidroidSimpleDialog.Notice, onDismiss: () -> Unit) = AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(dialog.title)) }, text = { Text(stringResource(dialog.message)) }, confirmButton = { TextButton(onClick = { onDismiss(); dialog.onConfirm?.invoke() }) { Text(stringResource(android.R.string.ok)) } })
@Composable private fun DebugDialog(dialog: NanidroidSimpleDialog.DebugMessage, onDismiss: () -> Unit) = AlertDialog(onDismissRequest = onDismiss, text = { SelectionContainer { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { Text(dialog.message) } } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } })

@Composable private fun UrlEntryDialog(dialog: NanidroidSimpleDialog.UrlEntry, onDismiss: () -> Unit) {
    fun submit() { if (dialog.onSubmit(dialog.value)) onDismiss() else dialog.onInvalid() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.more_g_enter_url_text)) },
        text = {
            Column {
                if (dialog.validationError) Text(stringResource(R.string.err_invalid_url), modifier = Modifier.testTag("url-validation-error"))
                OutlinedTextField(
                    value = dialog.value,
                    onValueChange = dialog.onValueChanged,
                    modifier = Modifier.fillMaxWidth().testTag("url-entry"),
                    isError = dialog.validationError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            }
        },
        confirmButton = { TextButton(onClick = ::submit, modifier = Modifier.testTag("url-submit")) { Text(stringResource(R.string.btn_dl_text)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable private fun UserInputDialog(dialog: NanidroidSimpleDialog.UserInput, onDismiss: () -> Unit) {
    fun cancel() { onDismiss(); dialog.onCancel() }
    AlertDialog(
        onDismissRequest = ::cancel,
        title = { Text(stringResource(R.string.user_input_dlg_title)) },
        text = { OutlinedTextField(value = dialog.value, onValueChange = dialog.onValueChanged, modifier = Modifier.fillMaxWidth().testTag("script-user-input"), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onDismiss(); dialog.onSubmit(dialog.id, dialog.value) })) },
        confirmButton = { TextButton(onClick = { onDismiss(); dialog.onSubmit(dialog.id, dialog.value) }, modifier = Modifier.testTag("script-user-input-confirm")) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = ::cancel, modifier = Modifier.testTag("script-user-input-cancel")) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable private fun UserChoiceDialog(dialog: NanidroidSimpleDialog.UserChoice, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = {},
    title = { Text(stringResource(R.string.user_sel_dlg_title)) },
    text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { dialog.labels.forEachIndexed { index, label -> TextButton(modifier = Modifier.fillMaxWidth().testTag("script-choice-$index"), onClick = { dialog.ids.getOrNull(index)?.let { onDismiss(); dialog.onChoice(it) } }) { Text(label) } } } },
    confirmButton = {},
)

@Composable private fun GhostListDialog(dialog: NanidroidSimpleDialog.GhostList, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = { onDismiss(); dialog.onCancel() },
    title = { Text(stringResource(R.string.list_ghost_dlg_title)) },
    text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { dialog.names.forEachIndexed { index, name -> TextButton(modifier = Modifier.fillMaxWidth().testTag("ghost-choice-$index"), onClick = { onDismiss(); dialog.onSelect(index) }) { Text(name) } } } },
    confirmButton = { TextButton(onClick = { onDismiss(); dialog.onMore() }, modifier = Modifier.testTag("ghost-list-more")) { Text(stringResource(R.string.more_ghosts_btn_text)) } },
    dismissButton = { TextButton(onClick = { onDismiss(); dialog.onCancel() }, modifier = Modifier.testTag("ghost-list-cancel")) { Text(stringResource(android.R.string.cancel)) } },
)

@Composable private fun HelpMenuDialog(dialog: NanidroidSimpleDialog.HelpMenu, onDismiss: () -> Unit) = ActionMenuDialog(stringResource(R.string.help_btn_text), listOf(stringResource(R.string.menu_help) to dialog.onGeneralHelp, stringResource(R.string.menu_about) to dialog.onAbout, stringResource(R.string.menu_feedback) to dialog.onFeedback), onDismiss)
@Composable private fun GeneralHelpDialog(dialog: NanidroidSimpleDialog.GeneralHelp, onDismiss: () -> Unit) = ActionMenuDialog(stringResource(R.string.menu_help), listOf(stringResource(R.string.help_install) to dialog.onInstallHelp, stringResource(R.string.help_supported_ops) to dialog.onSupportedOperations), onDismiss)
@Composable private fun MoreGhostDialog(dialog: NanidroidSimpleDialog.MoreGhost, onDismiss: () -> Unit) = ActionMenuDialog(stringResource(R.string.more_g_title), listOf(stringResource(R.string.more_g_enter_url_text) to dialog.onEnterUrl, stringResource(R.string.more_g_from_SD_text) to dialog.onInstallFromSdCard, stringResource(R.string.more_g_ghost_town_text) to dialog.onGhostTown), onDismiss)

@Composable private fun ActionMenuDialog(title: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) = AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column(modifier = Modifier.fillMaxWidth()) { actions.forEachIndexed { index, (label, action) -> TextButton(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("simple-action-$index"), onClick = { onDismiss(); action() }) { Text(label) } } } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })

@Preview(showBackground = true) @Composable private fun MoreGhostDialogPreview() { NanidroidSimpleDialogHost(NanidroidSimpleDialog.MoreGhost({}, {}, {}), {}) }
