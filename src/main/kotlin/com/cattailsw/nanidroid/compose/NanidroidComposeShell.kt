@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cattailsw.nanidroid.compose

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.compose.durable.StalledOperationPrompt
import com.cattailsw.nanidroid.compose.durable.DurableStoreRecoveryPrompt
import com.cattailsw.nanidroid.install.NarDownload
import com.cattailsw.nanidroid.install.NarDownloadState
import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarImportAttemptToken
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
    onReadme: () -> Unit = {},
    onArchiveQueue: () -> Unit = {},
    archiveDownloads: List<NarDownload> = emptyList(),
    narImportState: ForegroundNarImportState = ForegroundNarImportState.Idle,
    installedReadyToken: NarImportAttemptToken? = null,
    onAcknowledgeNarImport: (NarImportAttemptToken) -> Unit = {},
    onSelectAnotherNarImport: (NarImportAttemptToken) -> Unit = {},
    onRetryNarImportCleanup: (NarImportAttemptToken) -> Unit = {},
    simpleDialog: NanidroidSimpleDialog?,
    onDismissSimpleDialog: () -> Unit,
    stalledOperations: List<DurableOperationRecord> = emptyList(),
    onDurableAttentionAction: (OperationHandle, DurableAttentionAction) -> Unit = { _, _ -> },
    durableRecoveryRequired: Boolean = false,
    onResolveDurableRecovery: () -> Boolean = { false },
    staticDurablePromptPreview: Boolean = false,
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
                val durableAttentionVisible = stalledOperations.any { it.showStallPrompt }
                val durableModalVisible = durableRecoveryRequired || durableAttentionVisible
                val foregroundImportModalVisible = when (narImportState) {
                    ForegroundNarImportState.Recovering,
                    ForegroundNarImportState.Idle,
                    is ForegroundNarImportState.AwaitingSelection,
                    -> false
                    else -> true
                }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    topBar = {
                        if (toolbarVisible && !loading) {
                            NanidroidToolbar(
                                onListGhost = onListGhost,
                                onReadme = onReadme,
                                onArchiveQueue = onArchiveQueue,
                                archiveDownloads = archiveDownloads,
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
                if (!durableModalVisible && !foregroundImportModalVisible) {
                    lowerModalStateHolder.SaveableStateProvider("simple-dialog") {
                        NanidroidSimpleDialogHost(
                            dialog = simpleDialog,
                            onDismiss = onDismissSimpleDialog,
                            archiveDownloads = archiveDownloads,
                        )
                    }
                }
                ForegroundNarImportPresentation(
                    state = narImportState,
                    installedReadyToken = installedReadyToken,
                    onAcknowledge = onAcknowledgeNarImport,
                    onSelectAnother = onSelectAnotherNarImport,
                    onRetryCleanup = onRetryNarImportCleanup,
                )
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
    onReadme: () -> Unit,
    onArchiveQueue: () -> Unit = {},
    archiveDownloads: List<NarDownload> = emptyList(),
) {
    var showMenu by remember { mutableStateOf(false) }
    val archiveQueueStatus = archiveQueueStatus(archiveDownloads)
    val archiveQueueDescription = archiveQueueDescription(archiveQueueStatus)
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
                modifier = Modifier
                    .testTag("appbar-overflow")
                    .semantics { contentDescription = archiveQueueDescription },
            ) {
                BadgedBox(
                    badge = {
                        if (archiveQueueStatus.count > 0) {
                            Badge(
                                containerColor = archiveQueueStatus.badgeColor(),
                                modifier = Modifier.testTag("archive-queue-status"),
                            ) {
                                Text(archiveQueueStatus.count.toString())
                            }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.more_btn_text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                DropdownMenuItem(
                    onClick = {
                        showMenu = false
                        onArchiveQueue()
                    },
                    text = { Text(archiveQueueLabel(archiveDownloads)) },
                    modifier = Modifier.testTag("archive-queue"),
                )
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

private enum class ArchiveQueueStatusType {
    Empty,
    Active,
    Complete,
    NeedsAttention,
    Other,
}

private data class ArchiveQueueStatus(
    val type: ArchiveQueueStatusType,
    val count: Int,
)

private fun archiveQueueStatus(downloads: List<NarDownload>): ArchiveQueueStatus {
    val attentionCount = downloads.count { it.state is NarDownloadState.NeedsAttention }
    if (attentionCount > 0) return ArchiveQueueStatus(ArchiveQueueStatusType.NeedsAttention, attentionCount)

    val activeCount = downloads.count {
        it.state == NarDownloadState.Copying ||
            it.state == NarDownloadState.Queued ||
            it.state == NarDownloadState.Downloading ||
            it.state == NarDownloadState.Installing
    }
    if (activeCount > 0) return ArchiveQueueStatus(ArchiveQueueStatusType.Active, activeCount)

    val completeCount = downloads.count { it.state == NarDownloadState.Complete }
    if (completeCount > 0) return ArchiveQueueStatus(ArchiveQueueStatusType.Complete, completeCount)

    return ArchiveQueueStatus(
        type = if (downloads.isEmpty()) ArchiveQueueStatusType.Empty else ArchiveQueueStatusType.Other,
        count = downloads.size,
    )
}

@Composable
private fun archiveQueueDescription(status: ArchiveQueueStatus): String = when (status.type) {
    ArchiveQueueStatusType.Empty -> stringResource(R.string.archive_queue_overflow_empty)
    ArchiveQueueStatusType.Active -> pluralStringResource(R.plurals.archive_queue_overflow_active, status.count, status.count)
    ArchiveQueueStatusType.Complete -> pluralStringResource(R.plurals.archive_queue_overflow_complete, status.count, status.count)
    ArchiveQueueStatusType.NeedsAttention -> pluralStringResource(R.plurals.archive_queue_overflow_attention, status.count, status.count)
    ArchiveQueueStatusType.Other -> pluralStringResource(R.plurals.archive_queue_overflow_other, status.count, status.count)
}

@Composable
private fun ArchiveQueueStatus.badgeColor(): Color = when (type) {
    ArchiveQueueStatusType.NeedsAttention -> MaterialTheme.colorScheme.error
    ArchiveQueueStatusType.Active -> MaterialTheme.colorScheme.tertiary
    ArchiveQueueStatusType.Complete,
    ArchiveQueueStatusType.Other,
    ArchiveQueueStatusType.Empty,
    -> MaterialTheme.colorScheme.primary
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
            onArchiveQueue = {},
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
