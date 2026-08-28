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
    fun initializedResumeAttachesTransitionalRuntimeBeforeBindingAndStarting() {
        val source = File("src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt").readText()
        val activation = source.substringAfter("private fun activateTopResumedHost()")
            .substringBefore("private val backPressedCallback")
        val transitional = activation.substringAfter("GhostRuntimePhase.Unattached,")
            .substringBefore("GhostRuntimePhase.Attached")
        val attached = activation.substringAfter("GhostRuntimePhase.Attached")
            .substringBefore("else ->")
        val resumeHelper = source.substringAfter("private suspend fun resumeRuntimeForLease(")
            .substringBefore("private fun showNoGhostAvailable()")
        val adoptionHelper = source.substringAfter("private fun adoptRuntimeHandle(")
            .substringBefore("private suspend fun resumeRuntimeForLease(")

        assertTrue(
            "Unattached and Attaching resume must join attachment before clock/playback",
            activation.contains("when (identity.phase)") &&
                transitional.contains("GhostRuntimePhase.Attaching") &&
                transitional.contains("resumeRuntimeForLease(lease)") &&
                resumeHelper.indexOf("attachRuntimeHandle(") <
                resumeHelper.indexOf("adoptRuntimeHandle("),
        )
        assertTrue(
            "Starting and Replacing resume must join the runtime-owned operation",
            activation.contains("GhostRuntimePhase.Starting") &&
                activation.contains("GhostRuntimePhase.Replacing") &&
                resumeHelper.contains("ghostRuntime.startOrJoin(") &&
                resumeHelper.contains("resumeReadyHandleAfterRuntimeSettles()"),
        )
        assertTrue(
            "Attached and SwitchPlayback resume must rebind admitted playback synchronously",
            attached.contains("GhostRuntimePhase.SwitchPlayback") &&
                attached.contains("adoptRuntimeHandle(lease, handle, startRuntime = true)"),
        )
        assertTrue(
            "Resume completion must be fenced across pause/destroy and stale generation",
            source.contains("resumeActivationEpoch++") &&
                source.contains("hostResumed") &&
                source.contains("adoptionLeaseIsCurrent(lease, handle)") &&
                source.contains("current.generation == handle.generation"),
        )
        assertTrue(
            "Every production clock transition must be owned by this Activity host token",
            adoptionHelper.contains("startClock(runnerHostToken)") &&
                source.contains("stopClock(runnerHostToken)") &&
                !source.contains("runner!!.startClock()") &&
                !source.contains("runner?.stopClock()") &&
                !source.contains("activeRunner.stopClock()"),
        )
        val checkedClockStart = adoptionHelper.indexOf(
            "if (!activeRunner.startClock(runnerHostToken)) return false",
        )
        val runAfterClockStart = adoptionHelper.indexOf("activeRunner.run()")
        assertTrue(
            "Rejected host clock activation must return before runner playback starts",
            checkedClockStart >= 0 && runAfterClockStart > checkedClockStart,
        )
        assertTrue(
            "A joined completion must not bind or start twice in one resume lease",
            adoptionHelper.indexOf("lastStartedAdoptionEpoch == lease.epoch") in
                0 until adoptionHelper.indexOf("bindRuntimeHandle(handle)") &&
                adoptionHelper.indexOf("lastStartedAdoptionGeneration == handle.generation") in
                0 until adoptionHelper.indexOf("bindRuntimeHandle(handle)"),
        )
    }

    @Test
    fun productionResumeUsesTopHostOwnershipAndRenderOnlySwitchPlayback() {
        val source = File("src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt").readText()
        val lease = source.substringAfter("private fun currentRuntimeAdoptionLease()")
            .substringBefore("private fun adoptionLeaseIsCurrent(")
        val topCallback = source.substringAfter("override fun onTopResumedActivityChanged(")
            .substringBefore("private val backPressedCallback")
        val resume = source.substringAfter("override fun onResume()")
            .substringBefore("private val backPressedCallback")
        val switchPlayback = resume.substringAfter("GhostRuntimePhase.SwitchPlayback")
            .substringBefore("else ->")

        assertTrue(lease.contains("hostTopResumed"))
        assertTrue(topCallback.contains("activateTopResumedHost()"))
        assertTrue(
            "SwitchPlayback adoption must bind/render without starting an empty runner",
            switchPlayback.contains("startRuntime = false") &&
                switchPlayback.contains("awaitSwitchReplacementForLease(lease, pending)"),
        )
        assertTrue(source.contains("ownsTopRuntimeHost()"))
        assertTrue(source.contains("runner.takeIf { ownsTopRuntimeHost() }"))
    }

    @Test
    fun productionOwnedStopInvalidatesStartedAdoptionBeforeSwitchFailureRestart() {
        val source = File("src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt").readText()
        val stop = source.substringAfter("private fun stopRuntimeForHost()")
            .substringBefore("override fun onPause()")
        val switching = source.substringAfter("fun switchGhost(nextId: String)")
            .substringBefore("fun onListGhost()")

        assertTrue(stop.contains("stopClock(runnerHostToken)"))
        assertTrue(stop.contains("lastStartedAdoptionEpoch = -1L"))
        assertTrue(stop.contains("lastStartedAdoptionGeneration = -1L"))
        assertTrue(switching.contains("if (!stopRuntimeForHost())"))
        assertTrue(switching.contains("adoptRuntimeHandle("))
    }

    @Test
    fun postAttachmentValidationAcceptsConcurrentSwitchPlaybackForSameGeneration() {
        val source = File("src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt").readText()
        val attach = source.substringAfter("private suspend fun attachRuntimeHandle(")
            .substringBefore("private fun bindRuntimeHandle(")

        assertTrue(attach.contains("GhostRuntimePhase.Attached"))
        assertTrue(attach.contains("GhostRuntimePhase.SwitchPlayback"))
        assertTrue(attach.contains("current.generation != handle.generation"))
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
    fun staleHostCannotStopCurrentHostsClock() {
        val runner = runtimes.create().runner
        val hostA = SScriptRunner.HostToken()
        val hostB = SScriptRunner.HostToken()
        var cancellations = 0

        LegacyPlatform.withTestSeams(
            clock = { 0L },
            delayedScheduler = { _, _ -> },
            delayedCancellation = {
                cancellations++
            },
        ) {
            runner.bindHost(
                hostA,
                {},
                {},
                recordingUiCallback(mutableListOf()),
                RecordingStatusCallback(),
            )
            assertTrue(runner.startClock(hostA))
            runner.bindHost(
                hostB,
                {},
                {},
                recordingUiCallback(mutableListOf()),
                RecordingStatusCallback(),
            )
            assertTrue(runner.startClock(hostB))

            val beforeStaleStop = cancellations
            assertFalse(runner.stopClock(hostA))

            assertEquals("Stale host stopped the current host clock", beforeStaleStop, cancellations)
            assertTrue(runner.stopClock(hostB))
            assertEquals(beforeStaleStop + 1, cancellations)
        }
    }

    @Test
    fun currentHostCanStopClockAfterRuntimeRetiresItsGeneration() {
        val fixture = runtimes.create()
        val runner = fixture.runner
        val host = SScriptRunner.HostToken()
        var cancellations = 0

        LegacyPlatform.withTestSeams(
            clock = { 0L },
            delayedScheduler = { _, _ -> },
            delayedCancellation = { cancellations++ },
        ) {
            runner.bindHost(
                host,
                {},
                {},
                recordingUiCallback(mutableListOf()),
                RecordingStatusCallback(),
            )
            assertTrue(runner.startClock(host))

            runner.retireGeneration(fixture.requireHandle().generation)

            assertTrue("Runtime retirement stripped the host clock lease", runner.stopClock(host))
            assertEquals(1, cancellations)
        }
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
    fun replacementHostCannotActivateClockWhileBlockedExitIsPending() {
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val exitDelivered = CountDownLatch(1)
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
        var bCanExitCount = 0
        var schedules = 0
        val executor = Executors.newSingleThreadExecutor()

        try {
            LegacyPlatform.withTestSeams(
                clock = { 0L },
                delayedScheduler = { _, _ -> schedules++ },
                delayedCancellation = {},
            ) {
                runner.bindHost(
                    hostA,
                    {},
                    {},
                    recordingUiCallback(mutableListOf()),
                    aStatus,
                )
                assertTrue(runner.startClock(hostA))
                assertTrue(runner.stopClock(hostA))
                val exit = executor.submit<Unit> { runner.doExit() }
                assertTrue(requestEntered.await(5, TimeUnit.SECONDS))

                runner.bindHost(
                    hostB,
                    {},
                    {},
                    recordingUiCallback(mutableListOf()),
                    object : SScriptRunner.StatusCallback {
                        override fun stop() = Unit
                        override fun canExit() {
                            bCanExitCount++
                            exitDelivered.countDown()
                        }
                        override fun switchPlaybackComplete() = Unit
                    },
                )
                val schedulesBeforeActivation = schedules
                assertFalse(
                    "Replacement host activated timers while OnClose was pending",
                    runner.startClock(hostB),
                )
                assertEquals(schedulesBeforeActivation, schedules)

                releaseRequest.countDown()
                exit.get(5, TimeUnit.SECONDS)
                assertTrue(exitDelivered.await(5, TimeUnit.SECONDS))
                assertEquals(0, aStatus.canExitCount)
                assertEquals(1, bCanExitCount)
                assertTrue(
                    fixture.trace.requests.none {
                        "ID: OnSecondChange\r\n" in it || "ID: OnMinuteChange\r\n" in it
                    },
                )

                assertTrue("Cleared exit terminal did not permit later activation", runner.startClock(hostB))
                runner.stopClock(hostB)
                runner.addMsgToQueue(arrayOf("\\hLater\\e"))
                runner.run()
                assertEquals("Exit terminal leaked into later playback", 1, bCanExitCount)
            }
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
        assertTrue(
            back.indexOf("stopRuntimeForHost()") <
                back.indexOf("activeRunner.doExit()"),
        )
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
