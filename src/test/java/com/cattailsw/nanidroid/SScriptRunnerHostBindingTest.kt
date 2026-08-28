package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SScriptRunnerHostBindingTest {
    @get:Rule val androidStubs = HostAndroidStubRule()
    @get:Rule val runtimes = RuntimeFixtureRegistry()

    @Test
    fun olderHostUnbindCannotDetachNewerHostCallbacks() {
        val runner = runtimes.create().runner.apply { setNoWaitMode(true) }
        val hostA = SScriptRunner.HostToken()
        val hostB = SScriptRunner.HostToken()
        val aFrames = mutableListOf<GhostPresentationFrame>()
        val aDialogue = mutableListOf<DialogueRuntimeState>()
        val aInputs = mutableListOf<String>()
        val aStatus = ClearingStatusCallback(runner, hostA)
        runner.bindHost(hostA, aFrames::add, aDialogue::add, recordingUiCallback(aInputs))
        assertTrue(runner.setHostStatusCallback(hostA, aStatus))
        runner.addMsgToQueue(arrayOf("\\hExisting\\e"))
        runner.run()
        val aFrameCount = aFrames.size
        val aDialogueCount = aDialogue.size

        val bFrames = mutableListOf<GhostPresentationFrame>()
        val bDialogue = mutableListOf<DialogueRuntimeState>()
        val bInputs = mutableListOf<String>()
        val bStatus = RecordingStatusCallback()
        runner.bindHost(hostB, bFrames::add, bDialogue::add, recordingUiCallback(bInputs))
        assertTrue(runner.setHostStatusCallback(hostB, bStatus))
        assertEquals(aFrames.last(), bFrames.single())
        assertEquals(runner.dialogueStateSnapshot(), bDialogue.single())

        aStatus.canExit()
        assertFalse(aStatus.clearAccepted)
        assertFalse(runner.unbindHost(hostA))
        runner.addMsgToQueue(arrayOf("\\hFresh\\![open,inputbox,new-host]\\e"))
        runner.run()
        runner.doExit()
        runner.stop()

        assertTrue(bFrames.any { it.sakura.text == "Fresh" })
        assertEquals(listOf("new-host"), bInputs)
        assertEquals(runner.dialogueStateSnapshot(), bDialogue.last())
        assertEquals(1, bStatus.canExitCount)
        assertEquals(aFrameCount, aFrames.size)
        assertEquals(aDialogueCount, aDialogue.size)
        assertTrue(aInputs.isEmpty())
        assertEquals(1, aStatus.canExitCount)
    }

    @Test
    fun productionActivityUsesTokenOwnedHostBindingAndTeardown() {
        val source = File("src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt").readText()

        assertTrue(source.contains("runnerHostToken"))
        assertTrue(source.contains("bindHost("))
        assertTrue(source.contains("setHostStatusCallback(runnerHostToken"))
        assertTrue(source.contains("unbindHost(runnerHostToken)"))
        assertFalse(Regex("\\.set(?:PresentationRenderer|DialogueStateObserver|UICallback|Callback)\\(")
            .containsMatchIn(source))
    }

    @Test
    fun sameHostRebindPreservesPendingStatusTerminal() {
        val runner = runtimes.create().runner.apply { setNoWaitMode(true) }
        val host = SScriptRunner.HostToken()
        val status = RecordingStatusCallback()

        runner.bindHost(host, {}, {}, recordingUiCallback(mutableListOf()))
        assertTrue(runner.setHostStatusCallback(host, status))
        runner.bindHost(host, {}, {}, recordingUiCallback(mutableListOf()))
        runner.doExit()
        runner.stop()

        assertEquals(1, status.canExitCount)
    }

    private fun recordingUiCallback(inputs: MutableList<String>) = object : SScriptRunner.UICallback {
        override fun showUserInputBox(id: String) {
            inputs += id
        }

        override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
    }

    private class RecordingStatusCallback : SScriptRunner.StatusCallback {
        var canExitCount = 0
        override fun stop() = Unit
        override fun canExit() {
            canExitCount++
        }

        override fun switchPlaybackComplete() = Unit
    }

    private class ClearingStatusCallback(
        private val runner: SScriptRunner,
        private val token: SScriptRunner.HostToken,
    ) : SScriptRunner.StatusCallback {
        var canExitCount = 0
        var clearAccepted = true
        override fun stop() = Unit
        override fun canExit() {
            canExitCount++
            clearAccepted = runner.setHostStatusCallback(token, null)
        }

        override fun switchPlaybackComplete() = Unit
    }
}
