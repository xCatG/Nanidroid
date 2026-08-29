package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import com.cattailsw.nanidroid.runtime.dialogue.DialogueActionKey
import com.cattailsw.nanidroid.runtime.dialogue.InputBehavior
import com.cattailsw.nanidroid.runtime.dialogue.InputBoxSpec
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.InputPresentation
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeInputAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DialogueDialogBindingTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun staleInputAfterReplacementCannotSubmitAndKeepsItsPresentation() {
        val first = input(generation = 1L, incarnation = 1L, actionId = 1L, obscured = true)
        var snapshot = snapshotWith(first)
        val commands = mutableListOf<RuntimeCommand>()
        val dialog = DialogueDialogBinding({ snapshot }, commands::add).userInput(first)
        snapshot = snapshotWith(input(generation = 2L, incarnation = 1L, actionId = 1L))

        dialog.onSubmit(dialog.id, "secret")

        assertEquals(InputPresentation(obscured = true), dialog.presentation)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun matchedRestorationKeepsLivePresentationAndSavedValue() {
        val pending = input(generation = 7L, incarnation = 3L, actionId = 4L, obscured = true)
        val binding = DialogueDialogBinding({ snapshotWith(pending) }) {}

        val restored = binding.restoreUserInput(pending.key, "secret")

        requireNotNull(restored)
        assertEquals(pending.pending.spec.presentation, restored.presentation)
        assertEquals("secret", restored.value)
    }

    @Test
    fun unmatchedRestorationDoesNotCreateRenderableInputDialog() {
        val pending = input(generation = 7L, incarnation = 3L, actionId = 4L)
        val binding = DialogueDialogBinding({ snapshotWith(pending) }) {}

        assertNull(binding.restoreUserInput(pending.key.copy(actionId = 5L), "saved"))
    }

    @Test
    fun submitAndDismissCarryExactLiveActionKey() {
        val pending = input(generation = 11L, incarnation = 8L, actionId = 6L)
        val commands = mutableListOf<RuntimeCommand>()
        val dialog = DialogueDialogBinding({ snapshotWith(pending) }, commands::add).userInput(pending)

        dialog.onSubmit(dialog.id, "answer")
        dialog.onCancel()

        assertEquals(
            listOf(
                RuntimeCommand.SubmitInput(pending.key, "answer"),
                RuntimeCommand.DismissInput(pending.key),
            ),
            commands,
        )
    }

    private fun snapshotWith(input: RuntimeInputAction): RuntimeSnapshot = RuntimeSnapshot.initial().copy(
        generation = input.key.generation,
        dialogue = RuntimeSnapshot.initial().dialogue.copy(input = input),
    )

    private fun input(
        generation: Long,
        incarnation: Long,
        actionId: Long,
        obscured: Boolean = false,
    ): RuntimeInputAction = RuntimeInputAction(
        key = DialogueActionKey(generation, incarnation, actionId),
        pending = PendingInputState(
            generation = generation,
            spec = InputBoxSpec(
                dispatch = InputDispatch.Normal("answer"),
                timeoutMillis = 1_000L,
                initialText = "",
                behaviorOptions = if (obscured) setOf(InputBehavior.PASSWORD) else emptySet(),
                presentation = InputPresentation(obscured = obscured),
                supplement = "",
                extraReferences = emptyList(),
                unknownOptions = emptyList(),
            ),
            deadlineElapsedMillis = 1_000L,
        ),
    )
}
