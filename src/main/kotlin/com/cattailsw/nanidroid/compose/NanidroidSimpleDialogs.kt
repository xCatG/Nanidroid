package com.cattailsw.nanidroid.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cattailsw.nanidroid.DialogueDialogRestoration
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.install.NarDownload
import com.cattailsw.nanidroid.install.NarDownloadState

/** Compose-owned activity dialogs. State values are kept in the Activity bundle. */
internal sealed interface NanidroidSimpleDialog {
    data class Notice(@param:StringRes val title: Int, @param:StringRes val message: Int, val onConfirm: (() -> Unit)? = null) : NanidroidSimpleDialog
    data class DebugMessage(val message: String) : NanidroidSimpleDialog
    data class HelpMenu(val onGeneralHelp: () -> Unit, val onAbout: () -> Unit, val onFeedback: () -> Unit) : NanidroidSimpleDialog
    data class GeneralHelp(val onInstallHelp: () -> Unit, val onSupportedOperations: () -> Unit) : NanidroidSimpleDialog
    data class MoreGhost(val onEnterUrl: () -> Unit, val onInstallFromSdCard: () -> Unit, val onGhostTown: () -> Unit) : NanidroidSimpleDialog
    data class UrlEntry(val value: String, val validationError: Boolean, val onValueChanged: (String) -> Unit, val onSubmit: (String) -> Boolean, val onInvalid: () -> Unit) : NanidroidSimpleDialog
    data class UserInput(
        val id: String,
        val value: String,
        val onValueChanged: (String) -> Unit,
        val onSubmit: (String, String) -> Unit,
        val onCancel: () -> Unit,
        val restoration: DialogueDialogRestoration? = null,
    ) : NanidroidSimpleDialog
    data class UserChoice(
        val labels: List<String>,
        val ids: List<String>,
        val restoration: DialogueDialogRestoration? = null,
        val onChoice: (Int) -> Unit,
    ) : NanidroidSimpleDialog
    data class GhostList(val names: List<String>, val ids: List<String>, val onSelect: (Int) -> Unit, val onMore: () -> Unit, val onCancel: () -> Unit) : NanidroidSimpleDialog
    data class TextDocument(val title: String, val text: String, val onOpenLink: (String) -> Unit, val sourceId: String? = null, val onSwitch: (() -> Unit)? = null) : NanidroidSimpleDialog
    data class SwitchConfirmation(val ghostId: String, val ghostName: String, val onSwitch: () -> Unit, val onCancel: () -> Unit) : NanidroidSimpleDialog
    data class ArchiveQueue(val onRetry: (String) -> Unit, val onReselect: (String) -> Unit, val onDelete: (String) -> Unit) : NanidroidSimpleDialog
}

@Composable
internal fun NanidroidSimpleDialogHost(dialog: NanidroidSimpleDialog?, onDismiss: () -> Unit, archiveDownloads: List<NarDownload> = emptyList()) {
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
        is NanidroidSimpleDialog.TextDocument -> TextDocumentDialog(dialog, onDismiss)
        is NanidroidSimpleDialog.SwitchConfirmation -> SwitchConfirmationDialog(dialog, onDismiss)
        is NanidroidSimpleDialog.ArchiveQueue -> ArchiveQueueDialog(dialog, archiveDownloads, onDismiss)
    }
}

@Composable private fun NoticeDialog(dialog: NanidroidSimpleDialog.Notice, onDismiss: () -> Unit) = AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(dialog.title)) }, text = { Text(stringResource(dialog.message)) }, confirmButton = { TextButton(onClick = { onDismiss(); dialog.onConfirm?.invoke() }, modifier = Modifier.testTag("notice-confirm")) { Text(stringResource(android.R.string.ok)) } })
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
    fun submit() { onDismiss(); dialog.onSubmit(dialog.id, dialog.value) }
    val title = stringResource(R.string.user_input_dlg_title)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(dialog.id, dialog.restoration) {
        focusRequester.requestFocus()
    }

    Dialog(
        // Back/outside dismissal hides presentation only. The runtime-owned
        // pending input remains reopenable until explicit Cancel or submit.
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // safeDrawing includes both system bars and the IME.
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .semantics { paneTitle = title },
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 24.dp, bottom = 8.dp),
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.size(16.dp))
                        OutlinedTextField(
                            value = dialog.value,
                            onValueChange = dialog.onValueChanged,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .testTag("script-user-input"),
                            label = { Text(title) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = ::cancel,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("script-user-input-cancel"),
                        ) { Text(stringResource(android.R.string.cancel)) }
                        TextButton(
                            onClick = ::submit,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("script-user-input-confirm"),
                        ) { Text(stringResource(android.R.string.ok)) }
                    }
                }
            }
        }
    }
}

@Composable private fun UserChoiceDialog(dialog: NanidroidSimpleDialog.UserChoice, onDismiss: () -> Unit) = AlertDialog(
    // Dismissal hides only this legacy host presentation. The runner retains
    // its exact pending actions, allowing the stage's Choose control to reopen.
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.user_sel_dlg_title)) },
    text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { dialog.labels.forEachIndexed { index, label -> TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("script-choice-$index"), onClick = { onDismiss(); dialog.onChoice(index) }) { Text(label) } } } },
    confirmButton = {},
)

@Composable private fun GhostListDialog(dialog: NanidroidSimpleDialog.GhostList, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = { onDismiss(); dialog.onCancel() },
    title = { Text(stringResource(R.string.list_ghost_dlg_title)) },
    text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { dialog.names.forEachIndexed { index, name -> TextButton(modifier = Modifier.fillMaxWidth().testTag("ghost-choice-$index"), onClick = { onDismiss(); dialog.onSelect(index) }) { Text(name) } } } },
    confirmButton = { TextButton(onClick = { onDismiss(); dialog.onMore() }, modifier = Modifier.testTag("ghost-list-more")) { Text(stringResource(R.string.more_ghosts_btn_text)) } },
    dismissButton = { TextButton(onClick = { onDismiss(); dialog.onCancel() }, modifier = Modifier.testTag("ghost-list-cancel")) { Text(stringResource(android.R.string.cancel)) } },
)

@Composable private fun TextDocumentDialog(dialog: NanidroidSimpleDialog.TextDocument, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(dialog.title) },
    text = { SelectionContainer { Column(modifier = Modifier.verticalScroll(rememberScrollState()).testTag("text-document")) {
        PlainTextDocument.linkPattern.split(dialog.text).forEachIndexed { index, section ->
            if (section.isNotEmpty()) Text(section)
            PlainTextDocument.linkPattern.findAll(dialog.text).toList().getOrNull(index)?.value?.let { link ->
                TextButton(onClick = { dialog.onOpenLink(link) }, modifier = Modifier.testTag("document-link-$index")) { Text(link) }
            }
        }
    } } },
    confirmButton = {
        if (dialog.onSwitch != null) TextButton(onClick = { onDismiss(); dialog.onSwitch.invoke() }, modifier = Modifier.testTag("document-switch")) { Text(stringResource(R.string.switch_to_ghost_btn_text)) }
        else TextButton(onClick = onDismiss, modifier = Modifier.testTag("document-close")) { Text(stringResource(R.string.close_btn_text)) }
    },
    dismissButton = if (dialog.onSwitch != null) ({ TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_btn_text)) } }) else null,
)

@Composable private fun SwitchConfirmationDialog(dialog: NanidroidSimpleDialog.SwitchConfirmation, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = { onDismiss(); dialog.onCancel() },
    title = { Text(stringResource(R.string.no_readme_dlg_title)) },
    text = { Text(stringResource(R.string.no_readme_text, dialog.ghostName)) },
    confirmButton = { TextButton(onClick = { onDismiss(); dialog.onSwitch() }, modifier = Modifier.testTag("no-readme-switch")) { Text(stringResource(R.string.switch_to_ghost_btn_text)) } },
    dismissButton = { TextButton(onClick = { onDismiss(); dialog.onCancel() }, modifier = Modifier.testTag("no-readme-cancel")) { Text(stringResource(android.R.string.cancel)) } },
)

@Composable private fun ArchiveQueueDialog(dialog: NanidroidSimpleDialog.ArchiveQueue, downloads: List<NarDownload>, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Archive downloads") },
    text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (downloads.isEmpty()) Text("No archive downloads.")
        downloads.forEach { item ->
            Text(item.source.toString())
            Text(item.state.toString())
            if (item.state is NarDownloadState.NeedsAttention) {
                if (item.source is com.cattailsw.nanidroid.install.NarDownloadSource.Local) {
                    TextButton(onClick = { dialog.onReselect(item.id) }, modifier = Modifier.testTag("archive-reselect-${item.id}")) { Text("Select again") }
                }
                val reselectNeeded = item.state.failure.message.contains("Select the archive again")
                if (item.source !is com.cattailsw.nanidroid.install.NarDownloadSource.Local || !reselectNeeded) {
                    TextButton(onClick = { dialog.onRetry(item.id) }, modifier = Modifier.testTag("archive-retry-${item.id}")) { Text("Retry") }
                }
            }
            TextButton(onClick = { dialog.onDelete(item.id) }, modifier = Modifier.testTag("archive-delete-${item.id}")) { Text("Delete") }
        }
    } },
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
)

@Composable private fun HelpMenuDialog(dialog: NanidroidSimpleDialog.HelpMenu, onDismiss: () -> Unit) = ActionMenuDialog(stringResource(R.string.help_btn_text), listOf(stringResource(R.string.menu_help) to dialog.onGeneralHelp, stringResource(R.string.menu_about) to dialog.onAbout, stringResource(R.string.menu_feedback) to dialog.onFeedback), onDismiss)
@Composable private fun GeneralHelpDialog(dialog: NanidroidSimpleDialog.GeneralHelp, onDismiss: () -> Unit) = ActionMenuDialog(stringResource(R.string.menu_help), listOf(stringResource(R.string.help_install) to dialog.onInstallHelp, stringResource(R.string.help_supported_ops) to dialog.onSupportedOperations), onDismiss)
@Composable private fun MoreGhostDialog(dialog: NanidroidSimpleDialog.MoreGhost, onDismiss: () -> Unit) = ActionMenuDialog(stringResource(R.string.more_g_title), listOf(stringResource(R.string.more_g_enter_url_text) to dialog.onEnterUrl, stringResource(R.string.more_g_from_SD_text) to dialog.onInstallFromSdCard, stringResource(R.string.more_g_ghost_town_text) to dialog.onGhostTown), onDismiss)

@Composable private fun ActionMenuDialog(title: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) = AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column(modifier = Modifier.fillMaxWidth()) { actions.forEachIndexed { index, (label, action) -> TextButton(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("simple-action-$index"), onClick = { onDismiss(); action() }) { Text(label) } } } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })

@Preview(showBackground = true) @Composable private fun MoreGhostDialogPreview() { NanidroidSimpleDialogHost(NanidroidSimpleDialog.MoreGhost({}, {}, {}), {}) }
