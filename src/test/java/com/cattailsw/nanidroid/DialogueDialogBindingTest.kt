package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.MonotonicClock
import com.cattailsw.nanidroid.runtime.dialogue.InputPresentation
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DialogueDialogBindingTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun staleInputAfterReplacementGenerationCannotSubmitAndKeepsItsPresentation(): Unit = fixture().use { fixture ->
        val first = fixture.openInput("first", "passwordinput")
        val dialog = DialogueDialogBinding { fixture.runner }.userInput(first)
        val replacement = fixture.openInput("replacement", "inputbox")

        dialog.onSubmit(dialog.id, "secret")

        assertEquals(InputPresentation(obscured = true), dialog.presentation)
        assertEquals(replacement, fixture.runner.dialogueStateSnapshot().pendingInput)
        assertTrue(fixture.trace.requests.isEmpty())
    }

    @Test
    fun carriedInputReopensWithTheSameSpecAndPresentationAfterChoiceOnlyTalk(): Unit = fixture().use { fixture ->
        val first = fixture.openInput("answer", "passwordinput")
        fixture.runner.addMsgToQueue(arrayOf("\\h\\q[Choice,choice]\\e"))
        fixture.runner.run()
        val carried = requireNotNull(fixture.runner.dialogueStateSnapshot().pendingInput)

        val dialog = DialogueDialogBinding { fixture.runner }.userInput(carried)

        assertSame(first.spec, carried.spec)
        assertSame(carried.spec.presentation, dialog.presentation)
    }

    @Test
    fun unmatchedRestorationDoesNotCreateARenderableInputDialog(): Unit = fixture().use { fixture ->
        val first = fixture.openInput("answer", "passwordinput")
        val binding = DialogueDialogBinding { fixture.runner }
        val restoration = requireNotNull(binding.userInput(first).restoration)
        fixture.openInput("replacement", "inputbox")

        assertNull(binding.restoreUserInput("answer", restoration))
    }

    @Test
    fun matchedRestorationKeepsTheLivePresentationAndSavedValue(): Unit = fixture().use { fixture ->
        val pending = fixture.openInput("answer", "passwordinput")
        val binding = DialogueDialogBinding { fixture.runner }
        val restoration = requireNotNull(binding.userInput(pending).restoration)

        requireNotNull(binding.restoreUserInput("answer", restoration, "secret")).also {
            assertSame(pending.spec.presentation, it.presentation)
            assertEquals("secret", it.value)
        }
    }

    private fun fixture(): RuntimeFixture = RuntimeFixture(
        runnerConfiguration = SScriptRunnerConfiguration(monotonicClock = FakeClock()),
        preparedFactory = { operationId, ghostId, root ->
            preparedGhost(
                operationId,
                ghostId,
                root,
                name = "Recording",
                sakuraName = "Sakura",
                keroName = "Kero",
            )
        },
    ).also { it.runner.setNoWaitMode(true) }

    private fun RuntimeFixture.openInput(id: String, form: String): PendingInputState {
        runner.addMsgToQueue(arrayOf("\\![open,$form,$id,1000]\\e"))
        runner.run()
        return requireNotNull(runner.dialogueStateSnapshot().pendingInput)
    }

    private class FakeClock : MonotonicClock {
        override fun nowMillis(): Long = 10_000L
    }
}
