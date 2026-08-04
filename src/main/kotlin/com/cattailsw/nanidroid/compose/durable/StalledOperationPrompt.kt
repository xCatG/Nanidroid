package com.cattailsw.nanidroid.compose.durable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.durable.DurableAttentionAction
import com.cattailsw.nanidroid.durable.DurableAttentionNotificationPolicy
import com.cattailsw.nanidroid.durable.DurableOperationPresentation
import com.cattailsw.nanidroid.durable.DurableOperationRecord
import com.cattailsw.nanidroid.durable.OperationHandle
import com.cattailsw.nanidroid.durable.OperationStatus
import com.cattailsw.nanidroid.durable.isCancellationDispatchFailure

@Composable
internal fun StalledOperationPrompt(
    records: List<DurableOperationRecord>,
    onAction: (OperationHandle, DurableAttentionAction) -> Unit,
) {
    var pinnedTag by rememberSaveable { mutableStateOf<String?>(null) }
    var seenTags by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var stoppingSummaryTags by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val actionableRecords = records.filter { it.showStallPrompt }
    val unseenRecords = actionableRecords.filter {
        DurableAttentionNotificationPolicy.notificationTag(it.handle()) !in seenTags
    }
    val selectionPool = unseenRecords.ifEmpty { actionableRecords }
    val selected = selectionPool.firstOrNull {
        DurableAttentionNotificationPolicy.notificationTag(it.handle()) == pinnedTag
    } ?: selectionPool.firstOrNull()
    val selectedTag = selected?.let {
        DurableAttentionNotificationPolicy.notificationTag(it.handle())
    }
    LaunchedEffect(selectedTag) { pinnedTag = selectedTag }
    LaunchedEffect(actionableRecords.map { DurableAttentionNotificationPolicy.notificationTag(it.handle()) }) {
        val activeTags = actionableRecords.mapTo(mutableSetOf()) {
            DurableAttentionNotificationPolicy.notificationTag(it.handle())
        }
        seenTags = ArrayList(seenTags.filter { it in activeTags })
        stoppingSummaryTags = ArrayList(stoppingSummaryTags.filter { it in activeTags })
    }
    val selectedStopping = selected?.let {
        it.status == OperationStatus.CANCEL_REQUESTED &&
            !it.isCancellationDispatchFailure() &&
            selectedTag !in stoppingSummaryTags
    } == true
    LaunchedEffect(selectedTag, selectedStopping, actionableRecords) {
        val stoppingTag = selectedTag?.takeIf { selectedStopping } ?: return@LaunchedEffect
        // Publish at least one frame of the exact operation's Stopping transition before
        // advancing, then retain it as a summary so another stalled operation is reachable.
        withFrameNanos { }
        stoppingSummaryTags = ArrayList(stoppingSummaryTags).apply {
            if (stoppingTag !in this) add(stoppingTag)
        }
        val nextSeen = ArrayList(seenTags).apply {
            if (stoppingTag !in this) add(stoppingTag)
        }
        val next = actionableRecords.firstOrNull {
            DurableAttentionNotificationPolicy.notificationTag(it.handle()) !in nextSeen
        }
        if (next == null) {
            seenTags = arrayListOf()
            pinnedTag = actionableRecords.firstOrNull()?.let {
                DurableAttentionNotificationPolicy.notificationTag(it.handle())
            }
        } else {
            seenTags = nextSeen
            pinnedTag = DurableAttentionNotificationPolicy.notificationTag(next.handle())
        }
    }
    selected ?: return

    val context = LocalContext.current
    val bodyMaxHeight = if (LocalConfiguration.current.screenHeightDp < 480) 96.dp else 240.dp
    val diagnostics = DurableOperationPresentation.diagnosticText(context, selected)
    val deferredStopping = actionableRecords.filter { record ->
        val tag = DurableAttentionNotificationPolicy.notificationTag(record.handle())
        tag in stoppingSummaryTags && tag != selectedTag &&
            record.status == OperationStatus.CANCEL_REQUESTED
    }
    AlertDialog(
        modifier = Modifier.testTag("durable-attention-prompt"),
        onDismissRequest = {},
        title = {
            Text(
                stringResource(
                    R.string.durable_attention_title,
                    stringResource(DurableOperationPresentation.titleResource(selected.kind)),
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .testTag("durable-attention-body"),
            ) {
                deferredStopping.forEach { record ->
                    Text(
                        "${stringResource(DurableOperationPresentation.titleResource(record.kind))} — " +
                            stringResource(DurableOperationPresentation.phaseResource(record)),
                    )
                }
                Text(stringResource(DurableOperationPresentation.phaseResource(selected)))
                diagnostics?.let {
                    Text("${stringResource(R.string.durable_diagnostics_label)}: $it")
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                DurableAttentionNotificationPolicy.actions(selected).forEach { action ->
                    TextButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("durable-attention-${action.tagSuffix()}"),
                        onClick = {
                            onAction(selected.handle(), action)
                        },
                    ) {
                        Text(stringResource(DurableOperationPresentation.actionLabelResource(action)))
                    }
                }
            }
        },
    )
}

private fun DurableOperationRecord.handle() = OperationHandle(id, attemptId)

private fun DurableAttentionAction.tagSuffix() = when (this) {
    DurableAttentionAction.KEEP_WAITING -> "keep-waiting"
    DurableAttentionAction.STOP -> "stop"
    DurableAttentionAction.RETRY_STOP -> "retry-stop"
}
