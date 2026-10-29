package com.cattailsw.nanidroid.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cattailsw.nanidroid.R

@Composable
internal fun GenAiSettings(enabled: Boolean, available: Boolean, onEnabledChange: (Boolean) -> Unit) {
    val label = stringResource(R.string.genai_chat_enabled)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.testTag("genai-toggle").semantics { contentDescription = label }
        )
    }
    if (enabled && !available) Text(stringResource(R.string.genai_unavailable))
}
