@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cattailsw.nanidroid.compose

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.cattailsw.nanidroid.BundledInstallState
import com.cattailsw.nanidroid.ForegroundCatalogRecovery
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarImportAttemptToken

/**
 * The activity's Compose-owned chrome.
 *
 * [ghostStage] is production Compose content.  The shell intentionally has no
 * AndroidView boundary: image composition, pointer routing, and balloons are
 * supplied by the declarative ghost-stage host.
 */
@Composable
@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
internal fun NanidroidComposeShell(
    ghostStage: @Composable () -> Unit,
    loading: Boolean,
    progressMessage: String,
    toolbarVisible: Boolean,
    onListGhost: () -> Unit,
    onReadme: () -> Unit = {},
    narImportState: ForegroundNarImportState = ForegroundNarImportState.Idle,
    installedReadyToken: NarImportAttemptToken? = null,
    foregroundCatalogRecovery: ForegroundCatalogRecovery? = null,
    onAcknowledgeNarImport: (NarImportAttemptToken) -> Unit = {},
    onSelectAnotherNarImport: (NarImportAttemptToken) -> Unit = {},
    onRetryNarImportCleanup: (NarImportAttemptToken) -> Unit = {},
    onRetryForegroundCatalog: (ForegroundCatalogRecovery) -> Unit = {},
    bundledInstallState: BundledInstallState = BundledInstallState.Idle,
    onRetryBundledInstall: (Long) -> Unit = {},
    startupExhaustedEpoch: Long? = null,
    onRecoverStartup: (Long) -> Unit = {},
    simpleDialog: NanidroidSimpleDialog?,
    onDismissSimpleDialog: () -> Unit,
    wallpaper: Drawable? = null,
    modifier: Modifier = Modifier,
) {
    NanidroidTheme {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .drawBehind {
                    wallpaper?.apply {
                        setBounds(0, 0, size.width.toInt(), size.height.toInt())
                        draw(drawContext.canvas.nativeCanvas)
                    }
                },
            color = Color.Transparent,
        ) {
            val lowerModalStateHolder = rememberSaveableStateHolder()

            Box(modifier = Modifier.fillMaxSize()) {
                val storageUnavailableNoticeVisible =
                    simpleDialog is NanidroidSimpleDialog.Notice &&
                        simpleDialog.message == R.string.err_no_sdcard
                val foregroundImportModalVisible = !storageUnavailableNoticeVisible && when (narImportState) {
                    ForegroundNarImportState.Recovering,
                    ForegroundNarImportState.Idle,
                    is ForegroundNarImportState.AwaitingSelection,
                    -> false
                    else -> true
                }
                val bundledRecovery = bundledInstallState as? BundledInstallState.RecoveryRequired
                val bundledRecoveryVisible = !storageUnavailableNoticeVisible &&
                    !foregroundImportModalVisible && bundledRecovery != null
                val startupRecoveryVisible = !storageUnavailableNoticeVisible &&
                    !foregroundImportModalVisible && !bundledRecoveryVisible && startupExhaustedEpoch != null
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    topBar = {
                        if (toolbarVisible && !loading) {
                            NanidroidToolbar(
                                onListGhost = onListGhost,
                                onReadme = onReadme,
                            )
                        }
                    },
                    // Keep composition padding explicit because stage layout policy
                    // already reserves CANONICAL_APP_BAR_HEIGHT (64.dp) for the
                    // stage's adaptive candidate, which prevents double top-space
                    // reservation and preserves stable stage reclassification.
                ) { _ ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxSize().testTag("ghost-stage")) {
                            ghostStage()
                        }
                    }
                }

                if (loading) {
                    LoadingOverlay(progressMessage)
                }
                if (!foregroundImportModalVisible && !bundledRecoveryVisible && !startupRecoveryVisible) {
                    lowerModalStateHolder.SaveableStateProvider("simple-dialog") {
                        NanidroidSimpleDialogHost(
                            dialog = simpleDialog,
                            onDismiss = onDismissSimpleDialog,
                        )
                    }
                }
                if (!storageUnavailableNoticeVisible) {
                    ForegroundNarImportPresentation(
                        state = narImportState,
                        installedReadyToken = installedReadyToken,
                        catalogRecovery = foregroundCatalogRecovery,
                        onAcknowledge = onAcknowledgeNarImport,
                        onSelectAnother = onSelectAnotherNarImport,
                        onRetryCleanup = onRetryNarImportCleanup,
                        onRetryCatalog = onRetryForegroundCatalog,
                    )
                }
                if (bundledRecoveryVisible) {
                    val recovery = requireNotNull(bundledRecovery)
                    RecoveryActionDialog(
                        title = stringResource(R.string.bundled_install_recovery_title),
                        message = recovery.message,
                        actionLabel = stringResource(R.string.retry_action),
                        actionTag = "bundled-install-retry",
                        onAction = { onRetryBundledInstall(recovery.operationId) },
                    )
                }
                if (startupRecoveryVisible) {
                    val exhaustedEpoch = requireNotNull(startupExhaustedEpoch)
                    RecoveryActionDialog(
                        title = stringResource(R.string.startup_recovery_title),
                        message = stringResource(R.string.err_no_ghost_available),
                        actionLabel = stringResource(R.string.nar_import_from_document),
                        actionTag = "startup-recovery-install",
                        onAction = { onRecoverStartup(exhaustedEpoch) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecoveryActionDialog(
    title: String,
    message: String,
    actionLabel: String,
    actionTag: String,
    onAction: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onAction,
                modifier = Modifier.testTag(actionTag),
            ) {
                Text(actionLabel)
            }
        },
    )
}

@Composable
private fun NanidroidToolbar(
    onListGhost: () -> Unit,
    onReadme: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    TopAppBar(
        modifier = Modifier.testTag("appbar"),
        title = {
            Text(
                text = stringResource(R.string.app_name),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            Button(onClick = onListGhost, modifier = Modifier.testTag("list-ghost")) {
                Text(
                    text = stringResource(R.string.ghosts_btn_text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = { showMenu = true },
                modifier = Modifier.testTag("appbar-overflow"),
            ) {
                Text(
                    text = stringResource(R.string.more_btn_text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    onClick = {
                        showMenu = false
                        onReadme()
                    },
                    text = { Text(stringResource(R.string.readme_menu_text)) },
                    modifier = Modifier.testTag("readme"),
                )
            }
        },
    )
}

@Composable
private fun LoadingOverlay(progressMessage: String) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("loading-overlay")
            .pointerInteropFilter { true },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(progressMessage)
        }
    }
}

@Composable
@Preview(showBackground = true, widthDp = 360)
private fun NanidroidToolbarPreview() {
    MaterialTheme {
        NanidroidToolbar(
            onListGhost = {},
            onReadme = {},
        )
    }
}

@Composable
@Preview(showBackground = true, widthDp = 360, heightDp = 640)
private fun NanidroidLoadingPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                LoadingOverlay("Loading Nanidroid")
            }
        }
    }
}
