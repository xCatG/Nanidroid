package com.cattailsw.nanidroid.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.R

/**
 * Compose-owned dialogs whose only effects are either dismissal or an explicit
 * Activity callback. Script interaction and installation dialogs deliberately
 * remain outside this model until they have their own state machines.
 */
internal sealed interface NanidroidSimpleDialog {
    data class Notice(
        @param:StringRes val title: Int,
        @param:StringRes val message: Int,
        val onConfirm: (() -> Unit)? = null,
    ) : NanidroidSimpleDialog

    data class DebugMessage(val message: String) : NanidroidSimpleDialog
    data object About : NanidroidSimpleDialog
    data class HelpMenu(
        val onGeneralHelp: () -> Unit,
        val onAbout: () -> Unit,
        val onFeedback: () -> Unit,
    ) : NanidroidSimpleDialog
    data class GeneralHelp(
        val onInstallHelp: () -> Unit,
        val onSupportedOperations: () -> Unit,
    ) : NanidroidSimpleDialog
    data class MoreGhost(
        val onEnterUrl: () -> Unit,
        val onInstallFromSdCard: () -> Unit,
        val onGhostTown: () -> Unit,
    ) : NanidroidSimpleDialog
}

@Composable
internal fun NanidroidSimpleDialogHost(
    dialog: NanidroidSimpleDialog?,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        is NanidroidSimpleDialog.Notice -> NoticeDialog(dialog, onDismiss)
        is NanidroidSimpleDialog.DebugMessage -> DebugDialog(dialog, onDismiss)
        NanidroidSimpleDialog.About -> AboutDialog(onDismiss)
        is NanidroidSimpleDialog.HelpMenu -> HelpMenuDialog(dialog, onDismiss)
        is NanidroidSimpleDialog.GeneralHelp -> GeneralHelpDialog(dialog, onDismiss)
        is NanidroidSimpleDialog.MoreGhost -> MoreGhostDialog(dialog, onDismiss)
    }
}

@Composable
private fun NoticeDialog(dialog: NanidroidSimpleDialog.Notice, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(dialog.title)) },
        text = { Text(stringResource(dialog.message)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                dialog.onConfirm?.invoke()
            }) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

@Composable
private fun DebugDialog(dialog: NanidroidSimpleDialog.DebugMessage, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            SelectionContainer {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(dialog.message)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            SelectionContainer {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                    """This software contains shared libraries under different licenses. See each library's license.txt for detailed information.

Satoriya and Satoriya-Posix: libsatoriya.so
Kawari: libkawari.so
YAYA: libyaya.so

Sincere thanks to the authors of NiseRingo and NiseSakura for Android, which inspired Nanidroid.

Credits
Program: CatTail Software LLC
Built-in Ghost & Shell: CatG Studio
Japanese Translation: Maggie Chuang

Referenced projects: ccm.sherry.jp/ninni/, android.com/developers/branding.html, and creativecommons.org/licenses/by/3.0/.""",
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_btn_text)) } },
    )
}

@Composable
private fun HelpMenuDialog(dialog: NanidroidSimpleDialog.HelpMenu, onDismiss: () -> Unit) {
    ActionMenuDialog(
        title = stringResource(R.string.help_btn_text),
        actions = listOf(
            stringResource(R.string.menu_help) to dialog.onGeneralHelp,
            stringResource(R.string.menu_about) to dialog.onAbout,
            stringResource(R.string.menu_feedback) to dialog.onFeedback,
        ),
        onDismiss = onDismiss,
    )
}

@Composable
private fun GeneralHelpDialog(dialog: NanidroidSimpleDialog.GeneralHelp, onDismiss: () -> Unit) {
    ActionMenuDialog(
        title = stringResource(R.string.menu_help),
        actions = listOf(
            stringResource(R.string.help_install) to dialog.onInstallHelp,
            stringResource(R.string.help_supported_ops) to dialog.onSupportedOperations,
        ),
        onDismiss = onDismiss,
    )
}

@Composable
private fun MoreGhostDialog(dialog: NanidroidSimpleDialog.MoreGhost, onDismiss: () -> Unit) {
    ActionMenuDialog(
        title = stringResource(R.string.more_g_title),
        actions = listOf(
            stringResource(R.string.more_g_enter_url_text) to dialog.onEnterUrl,
            stringResource(R.string.more_g_from_SD_text) to dialog.onInstallFromSdCard,
            stringResource(R.string.more_g_ghost_town_text) to dialog.onGhostTown,
        ),
        onDismiss = onDismiss,
    )
}

@Composable
private fun ActionMenuDialog(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                actions.forEachIndexed { index, (label, action) ->
                    TextButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .testTag("simple-action-$index"),
                        onClick = {
                            onDismiss()
                            action()
                        },
                    ) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Preview(showBackground = true)
@Composable
private fun MoreGhostDialogPreview() {
    NanidroidSimpleDialogHost(
        dialog = NanidroidSimpleDialog.MoreGhost({}, {}, {}),
        onDismiss = {},
    )
}
