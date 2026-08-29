package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.compose.NanidroidSimpleDialog
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import com.cattailsw.nanidroid.runtime.dialogue.DialogueActionKey
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeChoiceAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeInputAction

internal data class DialogueDialogRestoration(val key: DialogueActionKey)

/** Creates view-local dialogs whose callbacks carry one exact runtime action identity. */
internal class DialogueDialogBinding(
    private val currentSnapshot: () -> RuntimeSnapshot,
    private val submit: (RuntimeCommand) -> Unit,
) {
    fun userInput(
        action: RuntimeInputAction,
        value: String = action.pending.spec.initialText,
        onValueChanged: (String) -> Unit = {},
    ): NanidroidSimpleDialog.UserInput = inputDialog(action, value, onValueChanged)

    fun restoreUserInput(
        key: DialogueActionKey,
        value: String,
        onValueChanged: (String) -> Unit = {},
    ): NanidroidSimpleDialog.UserInput? = currentSnapshot().dialogue.input
        ?.takeIf { it.key == key }
        ?.let { inputDialog(it, value, onValueChanged) }

    fun userChoice(actions: List<RuntimeChoiceAction>): NanidroidSimpleDialog.UserChoice =
        NanidroidSimpleDialog.UserChoice(
            labels = actions.map { it.action.label() },
            ids = actions.map { it.key.actionId.toString() },
            restoration = actions.firstOrNull()?.let { DialogueDialogRestoration(it.key) },
            onChoice = { index ->
                actions.getOrNull(index)
                    ?.takeIf { candidate ->
                        currentSnapshot().dialogue.choices.any { it.key == candidate.key }
                    }
                    ?.let { submit(RuntimeCommand.ActivateChoice(it.key)) }
            },
        )

    private fun inputDialog(
        action: RuntimeInputAction,
        value: String,
        onValueChanged: (String) -> Unit,
    ) = NanidroidSimpleDialog.UserInput(
        id = inputId(action),
        value = value,
        presentation = action.pending.spec.presentation,
        onValueChanged = onValueChanged,
        onSubmit = { _, input ->
            if (currentSnapshot().dialogue.input?.key == action.key) {
                submit(RuntimeCommand.SubmitInput(action.key, input))
            }
        },
        onCancel = {
            if (currentSnapshot().dialogue.input?.key == action.key) {
                submit(RuntimeCommand.DismissInput(action.key))
            }
        },
        restoration = DialogueDialogRestoration(action.key),
    )

    private fun inputId(action: RuntimeInputAction): String = when (
        val dispatch = action.pending.spec.dispatch
    ) {
        is InputDispatch.Normal -> dispatch.id
        is InputDispatch.DirectEvent -> dispatch.eventId
    }

    private fun com.cattailsw.nanidroid.runtime.dialogue.DialogueAction.label(): String = when (this) {
        is com.cattailsw.nanidroid.runtime.dialogue.DialogueAction.Normal -> label
        is com.cattailsw.nanidroid.runtime.dialogue.DialogueAction.DirectEvent -> label
        is com.cattailsw.nanidroid.runtime.dialogue.DialogueAction.Script -> label
    }
}
