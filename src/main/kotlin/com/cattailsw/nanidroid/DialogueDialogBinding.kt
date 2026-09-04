package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.compose.NanidroidSimpleDialog
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.InputPresentation
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState

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
        pending: PendingInputState,
        value: String = "",
        onValueChanged: (String) -> Unit = {},
    ): NanidroidSimpleDialog.UserInput {
        val runner = currentRunner()
        val snapshot = runner?.dialogueDialogRuntimeSnapshot()
        val livePending = snapshot?.dialogue?.pendingInput?.takeIf {
            it.generation == pending.generation && it.spec === pending.spec
        }
        return inputDialog(
            inputId(pending),
            value,
            livePending?.spec?.presentation ?: InputPresentation(),
            onValueChanged,
            runner.takeIf { livePending != null },
            livePending?.generation,
            livePending?.let { DialogueDialogRestoration(requireNotNull(snapshot).owner, it.generation) },
        )
    }

    fun restoreUserInput(
        id: String,
        restoration: DialogueDialogRestoration?,
        value: String = "",
        onValueChanged: (String) -> Unit = {},
    ): NanidroidSimpleDialog.UserInput? {
        val runner = currentRunner()
        val snapshot = runner?.dialogueDialogRuntimeSnapshot()
        val pending = snapshot?.dialogue?.pendingInput?.takeIf {
            restoration != null &&
                snapshot.owner == restoration.owner &&
                it.generation == restoration.generation
        }
        return pending?.let {
            inputDialog(
                id,
                value,
                it.spec.presentation,
                onValueChanged,
                runner,
                it.generation,
                restoration,
            )
        }
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
        presentation: InputPresentation,
        onValueChanged: (String) -> Unit,
        runner: SScriptRunner?,
        generation: Long?,
        restoration: DialogueDialogRestoration?,
    ): NanidroidSimpleDialog.UserInput = NanidroidSimpleDialog.UserInput(
        id,
        value,
        presentation,
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

    private fun inputId(pending: PendingInputState): String = when (val dispatch = pending.spec.dispatch) {
        is InputDispatch.Normal -> dispatch.id
        is InputDispatch.DirectEvent -> dispatch.eventId
    }
}
