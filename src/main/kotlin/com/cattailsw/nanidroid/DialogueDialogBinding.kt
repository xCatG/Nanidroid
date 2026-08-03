package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.compose.NanidroidSimpleDialog
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction

/** Binds transient dialog callbacks to the exact runtime identities they presented. */
internal class DialogueDialogBinding(
    private val currentRunner: () -> SScriptRunner?,
) {
    fun userInput(
        id: String,
        generation: Long?,
        value: String = "",
        onValueChanged: (String) -> Unit = {},
    ): NanidroidSimpleDialog.UserInput = NanidroidSimpleDialog.UserInput(
        id,
        value,
        onValueChanged = onValueChanged,
        onSubmit = { _, input -> generation?.let { submitInput(it, input) } },
        onCancel = { generation?.let(::cancelInput) },
    )

    fun userChoice(
        labels: List<String>,
        ids: List<String>,
        actions: List<DialogueAction>,
    ): NanidroidSimpleDialog.UserChoice = NanidroidSimpleDialog.UserChoice(
        labels,
        ids,
        onChoice = { index -> actions.getOrNull(index)?.let(::activateChoice) },
    )

    private fun submitInput(generation: Long, input: String) {
        val runner = currentRunner() ?: return
        if (runner.dialogueStateSnapshot().pendingInput?.generation != generation) return
        runner.resumeEvt()
        runner.submitInput(generation, input)
    }

    private fun cancelInput(generation: Long) {
        val runner = currentRunner() ?: return
        if (runner.dialogueStateSnapshot().pendingInput?.generation != generation) return
        runner.resumeEvt()
        runner.dismissInput(generation)
    }

    private fun activateChoice(action: DialogueAction) {
        currentRunner()?.activateChoice(action)
    }
}
