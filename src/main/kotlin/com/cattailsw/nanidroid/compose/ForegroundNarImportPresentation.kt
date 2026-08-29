package com.cattailsw.nanidroid.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.ForegroundCatalogRecovery
import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarImportAttemptToken
import com.cattailsw.nanidroid.install.NarImportPrimaryOutcome

/** Presents the process-owned foreground import state without Activity bundle state. */
@Composable
internal fun ForegroundNarImportPresentation(
    state: ForegroundNarImportState,
    installedReadyToken: NarImportAttemptToken?,
    catalogRecovery: ForegroundCatalogRecovery? = null,
    onAcknowledge: (NarImportAttemptToken) -> Unit,
    onSelectAnother: (NarImportAttemptToken) -> Unit,
    onRetryCleanup: (NarImportAttemptToken) -> Unit,
    onRetryCatalog: (ForegroundCatalogRecovery) -> Unit = {},
) {
    when (state) {
        ForegroundNarImportState.Recovering,
        ForegroundNarImportState.Idle,
        is ForegroundNarImportState.AwaitingSelection,
        -> Unit

        is ForegroundNarImportState.Copying -> BlockingImportProgress(
            message = stringResource(R.string.nar_import_copying),
        )

        is ForegroundNarImportState.Installing -> BlockingImportProgress(
            message = stringResource(R.string.nar_import_installing),
        )

        is ForegroundNarImportState.Cleaning -> BlockingImportProgress(
            message = stringResource(R.string.nar_import_cleaning),
        )

        is ForegroundNarImportState.Installed -> {
            if (catalogRecovery?.importToken == state.token) {
                ImportTerminalDialog(
                    title = stringResource(R.string.err_title),
                    message = stringResource(R.string.err_no_ghost_available),
                    onDismiss = {},
                    confirm = {
                        TextButton(
                            onClick = { onRetryCatalog(catalogRecovery) },
                            modifier = Modifier.testTag("nar-import-retry-catalog"),
                        ) {
                            Text(stringResource(R.string.retry_action))
                        }
                    },
                )
            } else if (installedReadyToken != state.token) {
                BlockingImportProgress(message = stringResource(R.string.nar_import_refreshing))
            } else {
                ImportTerminalDialog(
                    title = stringResource(R.string.nar_import_installed_title),
                    message = stringResource(R.string.nar_import_installed_message),
                    onDismiss = { onAcknowledge(state.token) },
                    confirm = {
                        TextButton(
                            onClick = { onAcknowledge(state.token) },
                            modifier = Modifier.testTag("nar-import-acknowledge"),
                        ) {
                            Text(stringResource(android.R.string.ok))
                        }
                    },
                )
            }
        }

        is ForegroundNarImportState.Failed -> ImportTerminalDialog(
            title = stringResource(R.string.nar_import_failed_title),
            message = state.message,
            onDismiss = { onAcknowledge(state.token) },
            confirm = {
                TextButton(
                    onClick = { onSelectAnother(state.token) },
                    modifier = Modifier.testTag("nar-import-select-another"),
                ) {
                    Text(stringResource(R.string.nar_import_select_another))
                }
            },
            dismiss = {
                TextButton(
                    onClick = { onAcknowledge(state.token) },
                    modifier = Modifier.testTag("nar-import-acknowledge"),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )

        is ForegroundNarImportState.Interrupted -> ImportTerminalDialog(
            title = stringResource(R.string.nar_import_interrupted_title),
            message = stringResource(R.string.nar_import_interrupted_message),
            onDismiss = { onAcknowledge(state.token) },
            confirm = {
                TextButton(
                    onClick = { onSelectAnother(state.token) },
                    modifier = Modifier.testTag("nar-import-select-another"),
                ) {
                    Text(stringResource(R.string.nar_import_select_another))
                }
            },
            dismiss = {
                TextButton(
                    onClick = { onAcknowledge(state.token) },
                    modifier = Modifier.testTag("nar-import-acknowledge"),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )

        is ForegroundNarImportState.RecoveryRequired -> ImportTerminalDialog(
            title = stringResource(R.string.nar_import_recovery_title),
            message = state.message,
            onDismiss = {},
            additionalMessage = when (state.primary) {
                is NarImportPrimaryOutcome.Installed -> stringResource(
                    R.string.nar_import_recovery_installed_message,
                )
                else -> null
            },
            confirm = {
                TextButton(
                    onClick = { onRetryCleanup(state.token) },
                    modifier = Modifier.testTag("nar-import-retry-cleanup"),
                ) {
                    Text(stringResource(R.string.nar_import_retry_cleanup))
                }
            },
        )
    }
}

@Composable
private fun BlockingImportProgress(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("nar-import-progress-overlay")
            .pointerInteropFilter { true },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(message)
        }
    }
}

@Composable
private fun ImportTerminalDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    dismiss: @Composable (() -> Unit)? = null,
    additionalMessage: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                additionalMessage?.let { Text(it) }
            }
        },
        confirmButton = confirm,
        dismissButton = dismiss,
    )
}
