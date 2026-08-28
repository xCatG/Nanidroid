package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        val aStatus = RecordingStatusCallback()
        runner.bindHost(hostA, aFrames::add, aDialogue::add, recordingUiCallback(aInputs), aStatus)
        runner.addMsgToQueue(arrayOf("\\hExisting\\e"))
        runner.run()
        val aFrameCount = aFrames.size
        val aDialogueCount = aDialogue.size

        val bFrames = mutableListOf<GhostPresentationFrame>()
        val bDialogue = mutableListOf<DialogueRuntimeState>()
        val bInputs = mutableListOf<String>()
        val bStatus = RecordingStatusCallback()
        runner.bindHost(hostB, bFrames::add, bDialogue::add, recordingUiCallback(bInputs), bStatus)
        assertEquals(aFrames.last(), bFrames.single())
        assertEquals(runner.dialogueStateSnapshot(), bDialogue.single())

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
        assertEquals(0, aStatus.canExitCount)
    }

    @Test
    fun productionActivityUsesTokenOwnedHostBindingAndTeardown() {
        val source = File("src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt").readText()

        assertTrue(source.contains("runnerHostToken"))
        assertTrue(source.contains("bindHost("))
        assertTrue(Regex("bindHost\\([\\s\\S]*?this@Nanidroid,\\s*mscb,\\s*\\)")
            .containsMatchIn(source))
        assertTrue(source.contains("unbindHost(runnerHostToken)"))
        assertFalse(source.contains("setHostStatusCallback"))
        assertFalse(Regex("\\.set(?:PresentationRenderer|DialogueStateObserver|UICallback|Callback)\\(")
            .containsMatchIn(source))
    }

    @Test
    fun sameHostRebindPreservesPendingStatusTerminal() {
        val runner = runtimes.create().runner.apply { setNoWaitMode(true) }
        val host = SScriptRunner.HostToken()
        val status = RecordingStatusCallback()

        runner.bindHost(host, {}, {}, recordingUiCallback(mutableListOf()), status)
        runner.bindHost(host, {}, {}, recordingUiCallback(mutableListOf()), status)
        runner.doExit()
        runner.stop()

        assertEquals(1, status.canExitCount)
    }

    @Test
    fun replacementHostOwnsBlockedExitTerminalWithoutPendingLeak() {
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val fixture = runtimes.create(response = { request ->
            if ("ID: OnClose\r\n" in request) {
                requestEntered.countDown()
                check(releaseRequest.await(5, TimeUnit.SECONDS))
                "SHIORI/3.0 200 OK\r\nValue: \\hClose\\e\r\n\r\n"
            } else {
                "SHIORI/3.0 204 No Content\r\n\r\n"
            }
        })
        val runner = fixture.runner.apply { setNoWaitMode(true) }
        val hostA = SScriptRunner.HostToken()
        val hostB = SScriptRunner.HostToken()
        val aStatus = RecordingStatusCallback()
        val bStatus = RecordingStatusCallback()
        val executor = Executors.newSingleThreadExecutor()

        try {
            runner.bindHost(hostA, {}, {}, recordingUiCallback(mutableListOf()), aStatus)
            runner.stop()
            val exit = executor.submit<Unit> { runner.doExit() }
            assertTrue(requestEntered.await(5, TimeUnit.SECONDS))

            runner.bindHost(hostB, {}, {}, recordingUiCallback(mutableListOf()), bStatus)
            assertFalse(runner.unbindHost(hostA))
            releaseRequest.countDown()

            exit.get(5, TimeUnit.SECONDS)
            assertEquals("Replacement host did not receive OnClose terminal", 1, bStatus.canExitCount)
            assertEquals(0, aStatus.canExitCount)
            runner.addMsgToQueue(arrayOf("\\hLater\\e"))
            runner.run()
            assertEquals("OnClose terminal leaked into later playback", 1, bStatus.canExitCount)
        } finally {
            releaseRequest.countDown()
            executor.shutdownNow()
        }
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

}
