package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeHostId
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueActionKey
import com.cattailsw.nanidroid.runtime.dialogue.InputBehavior
import com.cattailsw.nanidroid.runtime.dialogue.InputBoxSpec
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.InputPresentation
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeInputAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeChoiceAction
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
        val dialog = DialogueDialogBinding({ snapshot }, { snapshot.foregroundHost }, commands::add)
            .userInput(first)
        snapshot = snapshotWith(input(generation = 2L, incarnation = 1L, actionId = 1L))

        dialog.onSubmit(dialog.id, "secret")

        assertEquals(InputPresentation(obscured = true), dialog.presentation)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun matchedRestorationKeepsLivePresentationAndSavedValue() {
        val pending = input(generation = 7L, incarnation = 3L, actionId = 4L, obscured = true)
        val binding = DialogueDialogBinding({ snapshotWith(pending) }, { null }) {}

        val restored = binding.restoreUserInput(pending.key, "secret")

        requireNotNull(restored)
        assertEquals(pending.pending.spec.presentation, restored.presentation)
        assertEquals("secret", restored.value)
    }

    @Test
    fun unmatchedRestorationDoesNotCreateRenderableInputDialog() {
        val pending = input(generation = 7L, incarnation = 3L, actionId = 4L)
        val binding = DialogueDialogBinding({ snapshotWith(pending) }, { null }) {}

        assertNull(binding.restoreUserInput(pending.key.copy(actionId = 5L), "saved"))
    }

    @Test
    fun submitAndDismissCarryExactLiveActionKey() {
        val pending = input(generation = 11L, incarnation = 8L, actionId = 6L)
        val lease = lease(41L, 3L)
        val commands = mutableListOf<RuntimeCommand>()
        val dialog = DialogueDialogBinding(
            { snapshotWith(pending, lease) },
            { lease },
            commands::add,
        ).userInput(pending)

        dialog.onSubmit(dialog.id, "answer")
        dialog.onCancel()

        assertEquals(
            listOf(
                RuntimeCommand.SubmitInput(pending.key, "answer", lease),
                RuntimeCommand.DismissInput(pending.key, lease),
            ),
            commands,
        )
    }

    @Test
    fun restoredInputCallbacksReadAdvancedLeaseAtInvocationTime() {
        val pending = input(generation = 12L, incarnation = 2L, actionId = 7L)
        val first = lease(51L, 3L)
        val advanced = lease(51L, 6L)
        var localHost: RuntimeHostLease? = first
        var snapshot = snapshotWith(pending, first)
        val commands = mutableListOf<RuntimeCommand>()
        val restored = requireNotNull(
            DialogueDialogBinding({ snapshot }, { localHost }, commands::add)
                .restoreUserInput(pending.key, "saved"),
        )
        localHost = advanced
        snapshot = snapshotWith(pending, advanced)

        restored.onSubmit(restored.id, "answer")

        assertEquals(listOf(RuntimeCommand.SubmitInput(pending.key, "answer", advanced)), commands)
    }

    @Test
    fun choiceCallbackCarriesCurrentExactHostAndActionIdentity() {
        val host = lease(59L, 4L)
        val choice = RuntimeChoiceAction(
            DialogueActionKey(12L, 3L, 8L),
            DialogueAction.Normal("Choose", "id", emptyList()),
        )
        val snapshot = RuntimeSnapshot.initial().copy(
            generation = choice.key.generation,
            dialogue = RuntimeSnapshot.initial().dialogue.copy(choices = listOf(choice)),
            foregroundHost = host,
        )
        val commands = mutableListOf<RuntimeCommand>()
        val dialog = DialogueDialogBinding({ snapshot }, { host }, commands::add)
            .userChoice(listOf(choice))

        dialog.onChoice(0)

        assertEquals(listOf(RuntimeCommand.ActivateChoice(choice.key, host)), commands)
    }

    @Test
    fun staleActivityProviderSuppressesChoiceSubmitAndDismissCallbacks() {
        val pending = input(generation = 13L, incarnation = 4L, actionId = 8L)
        val oldHost = lease(61L, 3L)
        val currentHost = lease(62L, 3L)
        val choice = RuntimeChoiceAction(
            DialogueActionKey(13L, 4L, 9L),
            DialogueAction.Normal("Choose", "id", emptyList()),
        )
        val snapshot = snapshotWith(pending, currentHost).copy(
            dialogue = snapshotWith(pending, currentHost).dialogue.copy(choices = listOf(choice)),
        )
        val commands = mutableListOf<RuntimeCommand>()
        val binding = DialogueDialogBinding({ snapshot }, { oldHost }, commands::add)
        val choiceDialog = binding.userChoice(listOf(choice))
        val inputDialog = binding.userInput(pending)

        choiceDialog.onChoice(0)
        inputDialog.onSubmit(inputDialog.id, "answer")
        inputDialog.onCancel()

        assertTrue(commands.isEmpty())
    }

    private fun snapshotWith(
        input: RuntimeInputAction,
        foregroundHost: RuntimeHostLease? = null,
    ): RuntimeSnapshot = RuntimeSnapshot.initial().copy(
        generation = input.key.generation,
        dialogue = RuntimeSnapshot.initial().dialogue.copy(input = input),
        foregroundHost = foregroundHost,
    )

    private fun lease(hostId: Long, epoch: Long) = RuntimeHostLease(RuntimeHostId(hostId), epoch)

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
