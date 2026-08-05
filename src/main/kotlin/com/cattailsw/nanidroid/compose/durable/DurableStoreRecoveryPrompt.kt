package com.cattailsw.nanidroid.compose.durable

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.R

@Composable
internal fun DurableStoreRecoveryPrompt(
    required: Boolean,
    onResolve: () -> Boolean,
) {
    var resolutionFailed by remember { mutableStateOf(false) }
    LaunchedEffect(required) {
        if (!required) resolutionFailed = false
    }
    if (!required) return

    AlertDialog(
        modifier = Modifier.testTag("durable-store-recovery-prompt"),
        onDismissRequest = {},
        title = { Text(stringResource(R.string.durable_store_recovery_title)) },
        text = {
            Text(
                text = stringResource(
                    if (resolutionFailed) {
                        R.string.durable_store_recovery_failed
                    } else {
                        R.string.durable_store_recovery_message
                    },
                ),
                modifier = if (resolutionFailed) {
                    Modifier.testTag("durable-store-recovery-error")
                } else {
                    Modifier
                },
            )
        },
        confirmButton = {
            TextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("durable-store-recovery-confirm"),
                onClick = { resolutionFailed = !onResolve() },
            ) {
                Text(stringResource(R.string.durable_store_recovery_confirm))
            }
        },
    )
}
