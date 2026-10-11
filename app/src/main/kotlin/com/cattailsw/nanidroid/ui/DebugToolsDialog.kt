package com.cattailsw.nanidroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.ghost.BundledAssistantContent

@Composable
fun DebugToolsDialog(
    showTouchBounds: Boolean,
    labels: BundledAssistantContent,
    onShowTouchBoundsChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = labels.label("DebugToolsTitle")
    val bounds = labels.label("ShowTouchBounds")
    val close = labels.label("Close")
    AlertDialog(
        modifier = Modifier.testTag("debug-tools-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(bounds, Modifier.weight(1f).padding(end = 12.dp))
                    Switch(checked = showTouchBounds, onCheckedChange = onShowTouchBoundsChange,
                        modifier = Modifier.semantics { contentDescription = bounds }
                            .testTag("debug-touch-bounds"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("debug-tools-close")) { Text(close) }
        },
    )
}
