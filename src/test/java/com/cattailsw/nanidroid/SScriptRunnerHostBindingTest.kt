package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities
import com.cattailsw.nanidroid.runtime.dialogue.Support
import com.cattailsw.nanidroid.shiori.ShioriRequestException
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun initializedResumeRebindsCurrentHandleBeforeStartingRuntime() {
        val source = File("src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt").readText()
        val onResume = source.substringAfter("override fun onResume()")
            .substringBefore("private val backPressedCallback")
        val activeHandle = onResume.indexOf("ghostRuntime.identity().activeHandle")
        val rebind = onResume.indexOf("bindRuntimeHandle(")
        val startClock = onResume.indexOf("startClock()")
        val run = onResume.indexOf(".run()")

        assertTrue(
            "Initialized resume must resolve and rebind the active handle before clock/playback",
            activeHandle >= 0 && rebind > activeHandle && startClock > rebind && run > startClock,
        )
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

    @Test
    fun replayableOnCloseFailureCompletesExitOnceWithoutPendingLeak() {
        val fixture = runtimes.create()
        fixture.trace.requestFailure.set(IllegalStateException("replayable OnClose failure"))

        assertImmediateExitTerminal(fixture)
    }

    @Test
    fun fatalOnCloseFailureCompletesExitOnceWithoutPendingLeak() {
        val fixture = runtimes.create()
        fixture.trace.requestFailure.set(
            ShioriRequestException("fatal OnClose failure", ownershipCertain = false),
        )

        assertImmediateExitTerminal(fixture)
    }

    @Test
    fun nonPlayableOnCloseCompletesExitOnceWithoutPendingLeak() {
        assertImmediateExitTerminal(runtimes.create())
    }

    @Test
    fun exitWithoutActiveRuntimeCompletesCurrentHostOnce() {
        assertImmediateExitTerminal(runtimes.create(autoStart = false, autoAttach = false))
    }

    @Test
    fun completedExitDuringHostGapIsDeliveredOnceWhenSameGenerationHostBinds() {
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val fixture = runtimes.create(response = { request ->
            if ("ID: OnClose\r\n" in request) {
                requestEntered.countDown()
                check(releaseRequest.await(5, TimeUnit.SECONDS))
            }
            "SHIORI/3.0 204 No Content\r\n\r\n"
        })
        val runner = fixture.runner.apply { setNoWaitMode(true) }
        val hostA = SScriptRunner.HostToken()
        val hostB = SScriptRunner.HostToken()
        val aStatus = RecordingStatusCallback()
        val bStatus = RecordingStatusCallback()
        val executor = Executors.newSingleThreadExecutor()

        try {
            runner.bindHost(hostA, {}, {}, recordingUiCallback(mutableListOf()), aStatus)
            val exit = executor.submit<Unit> { runner.doExit() }
            assertTrue(requestEntered.await(5, TimeUnit.SECONDS))
            assertTrue(runner.unbindHost(hostA))
            releaseRequest.countDown()
            exit.get(5, TimeUnit.SECONDS)

            runner.bindHost(hostB, {}, {}, recordingUiCallback(mutableListOf()), bStatus)

            assertEquals(0, aStatus.canExitCount)
            assertEquals(1, bStatus.canExitCount)
            runner.addMsgToQueue(arrayOf("\\hLater\\e"))
            runner.run()
            assertEquals("Retained exit leaked into later playback", 1, bStatus.canExitCount)
        } finally {
            releaseRequest.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun reentrantHostBindDeliversRetainedExitOnlyToCurrentHost() {
        val onCloseCount = AtomicInteger()
        val fixture = runtimes.create(response = { request ->
            if ("ID: OnClose\r\n" in request) onCloseCount.incrementAndGet()
            "SHIORI/3.0 204 No Content\r\n\r\n"
        })
        val runner = fixture.runner.apply { setNoWaitMode(true) }
        val hostA = SScriptRunner.HostToken()
        val hostB = SScriptRunner.HostToken()
        val aStatus = RecordingStatusCallback()
        val bStatus = RecordingStatusCallback()
        var reentered = false

        runner.doExit()
        runner.bindHost(
            hostA,
            {},
            {
                if (!reentered) {
                    reentered = true
                    runner.doExit()
                    runner.bindHost(
                        hostB,
                        {},
                        {},
                        recordingUiCallback(mutableListOf()),
                        bStatus,
                    )
                }
            },
            recordingUiCallback(mutableListOf()),
            aStatus,
        )

        assertEquals("Reentrant binding started a second exit operation", 1, onCloseCount.get())
        assertEquals("Superseded host received retained exit", 0, aStatus.canExitCount)
        assertEquals(1, bStatus.canExitCount)
    }

    @Test
    fun retiredGenerationExitCannotTerminateReplacementHost() {
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val fixture = runtimes.create(response = { request ->
            if ("ID: OnClose\r\n" in request) {
                requestEntered.countDown()
                check(releaseRequest.await(5, TimeUnit.SECONDS))
            }
            "SHIORI/3.0 204 No Content\r\n\r\n"
        })
        val runner = fixture.runner.apply { setNoWaitMode(true) }
        val outgoing = fixture.requireHandle()
        val hostA = SScriptRunner.HostToken()
        val hostB = SScriptRunner.HostToken()
        val aStatus = RecordingStatusCallback()
        val bStatus = RecordingStatusCallback()
        val executor = Executors.newSingleThreadExecutor()

        try {
            runner.bindHost(hostA, {}, {}, recordingUiCallback(mutableListOf()), aStatus)
            val exit = executor.submit<Unit> { runner.doExit() }
            assertTrue(requestEntered.await(5, TimeUnit.SECONDS))
            runner.retireGeneration(outgoing.generation)
            val replacement = GhostHandle(
                Ghost(preparedGhost(2L, "replacement", File("build/runtime-fixtures/replacement"))),
                PointerEventCapabilities(Support.UNKNOWN, Support.UNKNOWN),
                outgoing.generation + 1L,
            )
            assertTrue(
                runner.admitAttachment(
                    2L,
                    replacement,
                    BootOutcome.BootAttemptFailed(IllegalStateException("no replacement boot")),
                ) is RuntimeResult.Success,
            )
            runner.bindHost(hostB, {}, {}, recordingUiCallback(mutableListOf()), bStatus)
            releaseRequest.countDown()
            exit.get(5, TimeUnit.SECONDS)

            assertEquals(0, aStatus.canExitCount)
            assertEquals(0, bStatus.canExitCount)
            runner.addMsgToQueue(arrayOf("\\hReplacement\\e"))
            runner.run()
            assertEquals("Retired exit leaked into replacement playback", 0, bStatus.canExitCount)
        } finally {
            releaseRequest.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun repeatedExitDoesNotPreemptFirstPlayableOnCloseScript() {
        val playbackScheduler = QueuedPlaybackScheduler()
        val requestCount = AtomicInteger()
        val fixture = runtimes.create(
            response = { request ->
                if ("ID: OnClose\r\n" !in request) {
                    "SHIORI/3.0 204 No Content\r\n\r\n"
                } else if (requestCount.incrementAndGet() == 1) {
                    "SHIORI/3.0 200 OK\r\nValue: \\hClose\\e\r\n\r\n"
                } else {
                    "SHIORI/3.0 204 No Content\r\n\r\n"
                }
            },
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackSchedulerFactory = { playbackScheduler },
            ),
        )
        val runner = fixture.runner
        val status = RecordingStatusCallback()
        runner.bindHost(
            SScriptRunner.HostToken(),
            {},
            {},
            recordingUiCallback(mutableListOf()),
            status,
        )

        runner.doExit()
        runner.doExit()

        assertEquals("Repeated exit preempted authored OnClose playback", 0, status.canExitCount)
        assertEquals("Repeated exit submitted another OnClose", 1, requestCount.get())
        playbackScheduler.drain()
        assertEquals(1, status.canExitCount)
    }

    @Test
    fun exitClearsQueuedPreExitDialogueBeforePlayingOnClose() {
        val playbackScheduler = QueuedPlaybackScheduler()
        val fixture = runtimes.create(
            response = { request ->
                if ("ID: OnClose\r\n" in request) {
                    "SHIORI/3.0 200 OK\r\nValue: \\hClose\\e\r\n\r\n"
                } else {
                    "SHIORI/3.0 204 No Content\r\n\r\n"
                }
            },
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackSchedulerFactory = { playbackScheduler },
            ),
        )
        val runner = fixture.runner
        val inputs = mutableListOf<String>()
        val status = RecordingStatusCallback()
        runner.bindHost(
            SScriptRunner.HostToken(),
            {},
            {},
            recordingUiCallback(inputs),
            status,
        )
        runner.addMsgToQueue(arrayOf("\\hQueued\\![open,inputbox,stale]\\e"))

        runner.doExit()
        playbackScheduler.drain()

        assertTrue("Queued pre-exit input script survived exit claim", inputs.isEmpty())
        assertEquals("OnClose playback did not reach its terminal", 1, status.canExitCount)
    }

    @Test
    fun reentrantStopEffectsDeliverPlayableExitOnlyToCurrentHost() {
        val playbackScheduler = QueuedPlaybackScheduler()
        val onCloseCount = AtomicInteger()
        val fixture = runtimes.create(
            response = { request ->
                if ("ID: OnClose\r\n" in request) {
                    onCloseCount.incrementAndGet()
                    "SHIORI/3.0 200 OK\r\nValue: \\hClose\\e\r\n\r\n"
                } else {
                    "SHIORI/3.0 204 No Content\r\n\r\n"
                }
            },
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackSchedulerFactory = { playbackScheduler },
            ),
        )
        val runner = fixture.runner
        val hostA = SScriptRunner.HostToken()
        val hostB = SScriptRunner.HostToken()
        val bStatus = RecordingStatusCallback()
        var stopCount = 0
        val aStatus = object : SScriptRunner.StatusCallback {
            var canExitCount = 0

            override fun stop() {
                stopCount++
                if (stopCount == 2) {
                    runner.doExit()
                    runner.bindHost(
                        hostB,
                        {},
                        {},
                        recordingUiCallback(mutableListOf()),
                        bStatus,
                    )
                }
            }

            override fun canExit() {
                canExitCount++
            }

            override fun switchPlaybackComplete() = Unit
        }
        runner.bindHost(
            hostA,
            {},
            {},
            recordingUiCallback(mutableListOf()),
            aStatus,
        )

        runner.doExit()
        playbackScheduler.drain()

        assertEquals("Reentrant stop started a second exit operation", 1, onCloseCount.get())
        assertEquals("Superseded host received playable exit terminal", 0, aStatus.canExitCount)
        assertEquals(1, bStatus.canExitCount)
    }

    @Test
    fun productionBackDelegatesInitialStopToSingleFlightExit() {
        val source = File("src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt").readText()
        val back = source.substringAfter("override fun handleOnBackPressed()")
            .substringBefore("private val mscb")

        assertFalse("Back must not stop authored OnClose playback", back.contains("activeRunner.stop()"))
        assertTrue(back.indexOf("activeRunner.stopClock()") < back.indexOf("activeRunner.doExit()"))
    }

    private fun assertImmediateExitTerminal(fixture: RuntimeFixture) {
        val runner = fixture.runner.apply { setNoWaitMode(true) }
        val status = RecordingStatusCallback()
        runner.bindHost(
            SScriptRunner.HostToken(),
            {},
            {},
            recordingUiCallback(mutableListOf()),
            status,
        )

        runner.stop()
        runner.doExit()

        assertEquals(1, status.canExitCount)
        runner.addMsgToQueue(arrayOf("\\hLater\\e"))
        runner.run()
        assertEquals("OnClose terminal leaked into later playback", 1, status.canExitCount)
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

    private class QueuedPlaybackScheduler : SScriptPlaybackScheduler {
        private val actions = ArrayDeque<() -> Unit>()

        override fun schedule(delayMillis: Long, action: () -> Unit) {
            actions.addLast(action)
        }

        override fun cancelPending() {
            actions.clear()
        }

        fun drain() {
            var count = 0
            while (actions.isNotEmpty()) {
                check(count++ < 100) { "Playback did not reach a terminal" }
                actions.removeFirst().invoke()
            }
        }
    }

}
