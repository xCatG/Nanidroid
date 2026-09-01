package com.cattailsw.nanidroid.compose.stage

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import kotlinx.coroutines.launch

/** Dialog presentation follows actual usable width, not the stage's bubble-layout mode. */
internal fun useCompactDialogueActionSurface(
    widthPx: Int,
    density: Float,
    touch: Boolean,
): Boolean = touch && density > 0f && widthPx / density < 600f

/** Responsive, dismissible presentation of runtime-owned dialogue actions. */
@Composable
fun DialogueActionSurface(
    actions: List<DialogueAction>,
    speaker: SurfaceSpeaker,
    open: Boolean,
    compact: Boolean,
    onDismiss: () -> Unit,
    onAction: (DialogueAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!open || actions.isEmpty()) return
    val actionKeys = actions.map(::ActionIdentity)
    key(actionKeys) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            DialogueActionSurfaceContent(
                actions = actions,
                speaker = speaker,
                compact = compact,
                onDismiss = onDismiss,
                onAction = onAction,
                modifier = modifier,
            )
        }
    }
}

/** Dialog-window-independent body used to verify responsive constraints deterministically. */
@Composable
internal fun DialogueActionSurfaceContent(
    actions: List<DialogueAction>,
    speaker: SurfaceSpeaker,
    compact: Boolean,
    onDismiss: () -> Unit,
    onAction: (DialogueAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    val actionKeys = actions.map(::ActionIdentity)
    val focusRequesters = remember(actionKeys) { actions.map { FocusRequester() } }
    val bringIntoViewRequesters = remember(actionKeys) { actions.map { BringIntoViewRequester() } }
    val scope = rememberCoroutineScope()
    val dialogView = LocalView.current
    val inputModeManager = LocalInputModeManager.current
    var firstRowAttached by remember(actionKeys) { mutableStateOf(false) }
    DisposableEffect(dialogView, actionKeys, firstRowAttached) {
        val requestInitialFocus = Runnable {
            val windowFocused = dialogView.hasWindowFocus()
            if (firstRowAttached && windowFocused) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
                focusRequesters.first().requestFocus()
            }
        }
        val windowFocusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && firstRowAttached) dialogView.post(requestInitialFocus)
        }
        dialogView.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
        if (firstRowAttached && dialogView.hasWindowFocus()) dialogView.post(requestInitialFocus)
        onDispose {
            dialogView.removeCallbacks(requestInitialFocus)
            if (dialogView.viewTreeObserver.isAlive) {
                dialogView.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener)
            }
        }
    }
    key(actionKeys) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (compact) 0.dp else 24.dp)
                .testTag("dialogue-action-root")
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            contentAlignment = if (compact) Alignment.BottomCenter else Alignment.Center,
        ) {
            val availableHeight = maxHeight
            Surface(
                modifier = modifier
                    .then(
                        if (compact) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.widthIn(max = EXPANDED_MAX_WIDTH).fillMaxWidth()
                        },
                    )
                    .heightIn(max = maxHeight)
                    .testTag("dialogue-action-surface-${speaker.name.lowercase()}"),
                shape = if (compact) {
                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                } else {
                    MaterialTheme.shapes.extraLarge
                },
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(R.string.dialogue_choose_action), style = MaterialTheme.typography.headlineSmall)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(
                                max = minOf(
                                    if (compact) 560.dp else 480.dp,
                                    (availableHeight - 72.dp).coerceAtLeast(0.dp),
                                ),
                            )
                            .verticalScroll(rememberScrollState())
                            .focusGroup(),
                    ) {
                        actions.forEachIndexed { index, action ->
                            key(actionKeys[index]) {
                                TextButton(
                                    onClick = { onAction(action) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .focusRequester(focusRequesters[index])
                                        .focusProperties {
                                            previous = focusRequesters[(index - 1).mod(actions.size)]
                                            next = focusRequesters[(index + 1).mod(actions.size)]
                                            up = focusRequesters[(index - 1).mod(actions.size)]
                                            down = focusRequesters[(index + 1).mod(actions.size)]
                                        }
                                        .bringIntoViewRequester(bringIntoViewRequesters[index])
                                        .onFocusChanged { focusState ->
                                            if (focusState.isFocused) {
                                                scope.launch { bringIntoViewRequesters[index].bringIntoView() }
                                            }
                                        }
                                        .onGloballyPositioned { coordinates ->
                                            if (index == 0 && coordinates.isAttached) firstRowAttached = true
                                        }
                                        .testTag("dialogue-action-$index"),
                                ) {
                                    Text(action.label())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class ActionIdentity(private val action: DialogueAction) {
    override fun equals(other: Any?): Boolean = other is ActionIdentity && other.action === action
    override fun hashCode(): Int = System.identityHashCode(action)
}

private fun DialogueAction.label(): String = when (this) {
    is DialogueAction.Normal -> label
    is DialogueAction.DirectEvent -> label
    is DialogueAction.Script -> label
}

private val EXPANDED_MAX_WIDTH = 560.dp
