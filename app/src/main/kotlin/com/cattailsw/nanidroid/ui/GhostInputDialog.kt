package com.cattailsw.nanidroid.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import com.cattailsw.nanidroid.runtime.PresentedInput
import com.cattailsw.nanidroid.runtime.ScriptInput

@Composable
fun GhostInputDialog(
    input: PresentedInput,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit = onCancel,
) {
    var consumed by remember(input.token) { mutableStateOf(false) }
    fun submit() {
        if (!consumed && ScriptInput.isAllowed(draft)) {
            consumed = true
            onSubmit(draft)
        }
    }
    fun cancel() {
        if (!consumed) { consumed = true; onCancel() }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Dialog(
        onDismissRequest = { if (!consumed) { consumed = true; onBack() } },
        properties = DialogProperties(dismissOnClickOutside = false,
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
            ) {
                if (landscape) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        InputField(input, draft, onDraftChange, ::submit, Modifier.weight(1f))
                        TextButton(onClick = { cancel() }, modifier = Modifier.semantics {
                            contentDescription = "Input Cancel"
                        }) { Text("Cancel") }
                        TextButton(onClick = { submit() }, modifier = Modifier.semantics {
                            contentDescription = "Input OK"
                        }) { Text("OK") }
                    }
                } else {
                    Column(Modifier.padding(16.dp)) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            Text("Input", style = MaterialTheme.typography.headlineSmall)
                            InputField(input, draft, onDraftChange, ::submit, Modifier.fillMaxWidth())
                        }
                        Row(Modifier.align(Alignment.End)) {
                            TextButton(onClick = { cancel() }, modifier = Modifier.semantics {
                                contentDescription = "Input Cancel"
                            }) { Text("Cancel") }
                            TextButton(onClick = { submit() }, modifier = Modifier.semantics {
                                contentDescription = "Input OK"
                            }) { Text("OK") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputField(input: PresentedInput, draft: String, onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit, modifier: Modifier) {
    val focused = remember(input.token) { java.util.concurrent.atomic.AtomicBoolean(false) }
    OutlinedTextField(
        value = draft,
        onValueChange = { value ->
            // A stale empty edit can arrive from TextField.onBlur during Activity recreation.
            if (value.isNotEmpty() || focused.get()) onDraftChange(value)
        },
        modifier = modifier.onFocusChanged { focused.set(it.isFocused) }
            .semantics { contentDescription = "Input field" }.testTag("ghost-input"),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        label = { Text(input.boxId) },
    )
}
