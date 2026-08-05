@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cattailsw.nanidroid.compose

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.compose.debug.DebugAvailabilityPolicy
import com.cattailsw.nanidroid.compose.durable.StalledOperationPrompt
import com.cattailsw.nanidroid.compose.durable.DurableStoreRecoveryPrompt
import com.cattailsw.nanidroid.install.NarDownload
import com.cattailsw.nanidroid.durable.DurableAttentionAction
import com.cattailsw.nanidroid.durable.DurableOperationRecord
import com.cattailsw.nanidroid.durable.OperationHandle

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
    onUpdate: () -> Unit,
    onReadme: () -> Unit = {},
    onPreferences: () -> Unit,
    onHelp: () -> Unit,
    onArchiveQueue: () -> Unit = {},
    archiveDownloads: List<NarDownload> = emptyList(),
    showDebugControls: Boolean = false,
    onDebug: () -> Unit = {},
    simpleDialog: NanidroidSimpleDialog?,
    onDismissSimpleDialog: () -> Unit,
    stalledOperations: List<DurableOperationRecord> = emptyList(),
    onDurableAttentionAction: (OperationHandle, DurableAttentionAction) -> Unit = { _, _ -> },
    durableRecoveryRequired: Boolean = false,
    onResolveDurableRecovery: () -> Boolean = { false },
    transientOverlay: @Composable () -> Unit = {},
    staticDurablePromptPreview: Boolean = false,
    wallpaper: Drawable? = null,
    modifier: Modifier = Modifier,
) {
    MaterialTheme {
        Surface(
            modifier = modifier.fillMaxSize().drawBehind {
                wallpaper?.apply {
                    setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    draw(drawContext.canvas.nativeCanvas)
                }
            },
            color = Color.Transparent,
        ) {
            val isDebuggable = DebugAvailabilityPolicy(isDebuggable = showDebugControls).showDebugIcon
            val lowerModalStateHolder = rememberSaveableStateHolder()

            Box(modifier = Modifier.fillMaxSize()) {
                val durableAttentionVisible = stalledOperations.any { it.showStallPrompt }
                val durableModalVisible = durableRecoveryRequired || durableAttentionVisible
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    topBar = {
                        if (toolbarVisible && !loading) {
                            NanidroidToolbar(
                                onListGhost = onListGhost,
                                onUpdate = onUpdate,
                                onReadme = onReadme,
                                onPreferences = onPreferences,
                                onHelp = onHelp,
                                onArchiveQueue = onArchiveQueue,
                                isDebuggable = isDebuggable,
                                archiveDownloads = archiveDownloads,
                                onDebugOpen = onDebug,
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
                if (!durableModalVisible) {
                    lowerModalStateHolder.SaveableStateProvider("simple-dialog") {
                        NanidroidSimpleDialogHost(
                            dialog = simpleDialog,
                            onDismiss = onDismissSimpleDialog,
                            archiveDownloads = archiveDownloads,
                        )
                    }
                    lowerModalStateHolder.SaveableStateProvider("transient-overlay") {
                        transientOverlay()
                    }
                }
                if (!durableRecoveryRequired) {
                    StalledOperationPrompt(
                        records = stalledOperations,
                        onAction = onDurableAttentionAction,
                        staticPreview = staticDurablePromptPreview,
                    )
                }
                DurableStoreRecoveryPrompt(
                    required = durableRecoveryRequired,
                    onResolve = onResolveDurableRecovery,
                )
            }
        }
    }
}

@Composable
private fun NanidroidToolbar(
    onListGhost: () -> Unit,
    onUpdate: () -> Unit,
    onReadme: () -> Unit,
    onPreferences: () -> Unit,
    onHelp: () -> Unit,
    onArchiveQueue: () -> Unit = {},
    isDebuggable: Boolean,
    archiveDownloads: List<NarDownload> = emptyList(),
    onDebugOpen: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val debugButtonDescription = stringResource(R.string.debug_button_description)
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
                        onUpdate()
                    },
                    text = { Text(stringResource(R.string.check_updates_btn_text)) },
                    modifier = Modifier.testTag("update"),
                )
                DropdownMenuItem(
                    onClick = {
                        showMenu = false
                        onReadme()
                    },
                    text = { Text(stringResource(R.string.readme_menu_text)) },
                    modifier = Modifier.testTag("readme"),
                )
                DropdownMenuItem(
                    onClick = {
                        showMenu = false
                        onPreferences()
                    },
                    text = { Text(stringResource(R.string.setup_btn_text)) },
                    modifier = Modifier.testTag("preferences"),
                )
                DropdownMenuItem(
                    onClick = {
                        showMenu = false
                        onHelp()
                    },
                    text = { Text(stringResource(R.string.help_btn_text)) },
                    modifier = Modifier.testTag("help"),
                )
                DropdownMenuItem(
                    onClick = {
                        showMenu = false
                        onArchiveQueue()
                    },
                    text = { Text(archiveQueueLabel(archiveDownloads)) },
                    modifier = Modifier.testTag("archive-queue"),
                )
            }
            if (isDebuggable) {
                IconButton(
                    onClick = onDebugOpen,
                    modifier = Modifier
                        .testTag("debug")
                        .semantics {
                            contentDescription = debugButtonDescription
                        },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bug_report_24),
                        contentDescription = null,
                    )
                }
            }
        },
    )
}

@Composable
private fun archiveQueueLabel(downloads: List<NarDownload>): String =
    if (downloads.isEmpty()) {
        stringResource(R.string.archive_queue_btn_text)
    } else {
        stringResource(R.string.archive_queue_btn_text_with_count, downloads.size)
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
            onUpdate = {},
            onReadme = {},
            onPreferences = {},
            onHelp = {},
            onArchiveQueue = {},
            isDebuggable = true,
            onDebugOpen = {},
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
