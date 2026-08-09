package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.compose.NanidroidSimpleDialog
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import com.cattailsw.nanidroid.runtime.dialogue.InputBoxSpec
import com.cattailsw.nanidroid.runtime.dialogue.InputPresentation

internal data class DialogueDialogRestoration(
    val owner: String,
    val generation: Long,
)

internal data class DialogueDialogRuntimeSnapshot(
    val owner: String,
    val choiceGeneration: Long?,
    val dialogue: DialogueRuntimeState,
)

/** Binds transient dialog callbacks to the exact runtime identities they presented. */
internal class DialogueDialogBinding(
    private val currentRunner: () -> SScriptRunner?,
) {
    fun userInput(
        id: String,
        generation: Long?,
        value: String = "",
        onValueChanged: (String) -> Unit = {},
    ): NanidroidSimpleDialog.UserInput {
        val runner = currentRunner()
        val snapshot = runner?.dialogueDialogRuntimeSnapshot()
        val boundGeneration = generation?.takeIf {
            snapshot?.dialogue?.pendingInput?.generation == it
        }
        return inputDialog(
            id,
            value,
            onValueChanged,
            runner.takeIf { boundGeneration != null },
            boundGeneration,
            boundGeneration?.let { DialogueDialogRestoration(requireNotNull(snapshot).owner, it) },
            snapshot?.dialogue?.pendingInput?.spec?.takeIf { boundGeneration != null },
        )
    }

    fun restoreUserInput(
        id: String,
        restoration: DialogueDialogRestoration?,
        value: String = "",
        onValueChanged: (String) -> Unit = {},
    ): NanidroidSimpleDialog.UserInput {
        val runner = currentRunner()
        val snapshot = runner?.dialogueDialogRuntimeSnapshot()
        val generation = restoration?.generation?.takeIf {
            snapshot?.owner == restoration.owner && snapshot.dialogue.pendingInput?.generation == it
        }
        return inputDialog(
            id,
            value,
            onValueChanged,
            runner.takeIf { generation != null },
            generation,
            restoration.takeIf { generation != null },
            snapshot?.dialogue?.pendingInput?.spec?.takeIf { generation != null },
        )
    }

    fun userChoice(
        labels: List<String>,
        ids: List<String>,
        actions: List<DialogueAction>,
    ): NanidroidSimpleDialog.UserChoice {
        val runner = currentRunner()
        val snapshot = runner?.dialogueDialogRuntimeSnapshot()
        val exactActions = actions.takeIf {
            it.size == labels.size &&
                it.size == ids.size &&
                snapshot?.dialogue?.pendingChoices?.hasSameIdentities(it) == true
        }.orEmpty()
        val restoration = snapshot?.choiceGeneration
            ?.takeIf { exactActions.isNotEmpty() }
            ?.let { DialogueDialogRestoration(snapshot.owner, it) }
        return choiceDialog(
            labels,
            ids,
            runner.takeIf { restoration != null },
            exactActions,
            restoration,
        )
    }

    fun restoreUserChoice(
        labels: List<String>,
        ids: List<String>,
        restoration: DialogueDialogRestoration?,
    ): NanidroidSimpleDialog.UserChoice {
        val runner = currentRunner()
        val snapshot = runner?.dialogueDialogRuntimeSnapshot()
        val actions = snapshot?.dialogue?.pendingChoices.orEmpty().takeIf {
            restoration != null &&
                snapshot?.owner == restoration.owner &&
                snapshot.choiceGeneration == restoration.generation &&
                it.size == labels.size &&
                it.size == ids.size
        }.orEmpty()
        return choiceDialog(
            labels,
            ids,
            runner.takeIf { actions.isNotEmpty() },
            actions,
            restoration.takeIf { actions.isNotEmpty() },
        )
    }

    private fun inputDialog(
        id: String,
        value: String,
        onValueChanged: (String) -> Unit,
        runner: SScriptRunner?,
        generation: Long?,
        restoration: DialogueDialogRestoration?,
        spec: InputBoxSpec?,
    ): NanidroidSimpleDialog.UserInput = NanidroidSimpleDialog.UserInput(
        id,
        value,
        onValueChanged = onValueChanged,
        onSubmit = { _, input ->
            if (runner != null && generation != null && currentRunner() === runner) {
                submitInput(runner, generation, input)
            }
        },
        onCancel = {
            if (runner != null && generation != null && currentRunner() === runner) {
                cancelInput(runner, generation)
            }
        },
        restoration = restoration,
        presentation = spec?.presentation ?: InputPresentation.Text,
        maximumLength = spec?.maximumLength,
    )

    private fun choiceDialog(
        labels: List<String>,
        ids: List<String>,
        runner: SScriptRunner?,
        actions: List<DialogueAction>,
        restoration: DialogueDialogRestoration?,
    ): NanidroidSimpleDialog.UserChoice = NanidroidSimpleDialog.UserChoice(
        labels,
        ids,
        onChoice = { index ->
            actions.getOrNull(index)?.let { action ->
                if (runner != null && currentRunner() === runner) runner.activateChoice(action)
            }
        },
        restoration = restoration,
    )

    private fun submitInput(runner: SScriptRunner, generation: Long, input: String) {
        if (runner.dialogueStateSnapshot().pendingInput?.generation != generation) return
        runner.resumeEvt()
        runner.submitInput(generation, input)
    }

    private fun cancelInput(runner: SScriptRunner, generation: Long) {
        if (runner.dialogueStateSnapshot().pendingInput?.generation != generation) return
        runner.resumeEvt()
        runner.dismissInput(generation)
    }

    private fun List<DialogueAction>.hasSameIdentities(other: List<DialogueAction>): Boolean =
        size == other.size && indices.all { this[it] === other[it] }
}
