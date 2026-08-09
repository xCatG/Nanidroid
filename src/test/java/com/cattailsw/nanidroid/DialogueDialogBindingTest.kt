package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.InputPresentation
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.shiori.Shiori
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
    fun staleInputAfterReplacementGenerationCannotSubmitAndKeepsItsPresentation() {
        val fixture = fixture()
        val first = fixture.openInput("first", "passwordinput")
        val dialog = DialogueDialogBinding { fixture.runner }.userInput(first)
        val replacement = fixture.openInput("replacement", "inputbox")

        dialog.onSubmit(dialog.id, "secret")

        assertEquals(InputPresentation(obscured = true), dialog.presentation)
        assertEquals(replacement, fixture.runner.dialogueStateSnapshot().pendingInput)
        assertTrue(fixture.shiori.requests.isEmpty())
    }

    @Test
    fun carriedInputReopensWithTheSameSpecAndPresentationAfterChoiceOnlyTalk() {
        val fixture = fixture()
        val first = fixture.openInput("answer", "passwordinput")
        fixture.runner.addMsgToQueue(arrayOf("\\h\\q[Choice,choice]\\e"))
        fixture.runner.run()
        val carried = requireNotNull(fixture.runner.dialogueStateSnapshot().pendingInput)

        val dialog = DialogueDialogBinding { fixture.runner }.userInput(carried)

        assertSame(first.spec, carried.spec)
        assertSame(carried.spec.presentation, dialog.presentation)
    }

    @Test
    fun unmatchedRestorationDoesNotCreateARenderableInputDialog() {
        val fixture = fixture()
        val first = fixture.openInput("answer", "passwordinput")
        val binding = DialogueDialogBinding { fixture.runner }
        val restoration = requireNotNull(binding.userInput(first).restoration)
        fixture.openInput("replacement", "inputbox")

        val restored = binding.restoreUserInput("answer", restoration)

        assertNull(restored)
    }

    @Test
    fun matchedRestorationKeepsTheLivePresentationAndSavedValue() {
        val fixture = fixture()
        val pending = fixture.openInput("answer", "passwordinput")
        val binding = DialogueDialogBinding { fixture.runner }
        val restoration = requireNotNull(binding.userInput(pending).restoration)

        val restored = binding.restoreUserInput("answer", restoration, "secret")

        requireNotNull(restored).also {
            assertSame(pending.spec.presentation, it.presentation)
            assertEquals("secret", it.value)
        }
    }

    private fun fixture(): Fixture {
        val shiori = RecordingShiori()
        val runner = SScriptRunner(null, GhostSessionCoordinator(), FakeClock())
        runner.setNoWaitMode(true)
        runner.setGhost(RecordingGhost(shiori))
        return Fixture(runner, shiori)
    }

    private fun Fixture.openInput(id: String, form: String): PendingInputState {
        runner.addMsgToQueue(arrayOf("\\![open,$form,$id,1000]\\e"))
        runner.run()
        return requireNotNull(runner.dialogueStateSnapshot().pendingInput)
    }

    private data class Fixture(val runner: SScriptRunner, val shiori: RecordingShiori)

    private class FakeClock : MonotonicClock {
        override fun nowMillis(): Long = 10_000L
    }

    private class RecordingShiori : Shiori {
        val requests = mutableListOf<String>()

        override fun getModuleName(): String = "recording"
        override fun request(request: String): String {
            requests += request
            return "SHIORI/3.0 204 No Content\r\n\r\n"
        }
        override fun terminate() = Unit
        override fun unloadShiori() = Unit
    }

    private class RecordingGhost(recordingShiori: RecordingShiori) : Ghost("recording") {
        init { shiori = recordingShiori }

        override fun loadGhostInfo() = Unit
        override fun getCreateCount(): Long = 1L
        override fun incrementCreateCount() = Unit
        override fun getGhostName(): String = "Recording"
        override fun getSakuraName(): String = "Sakura"
        override fun getKeroName(): String = "Kero"
        override fun unload() = Unit
    }
}
