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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
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
        val leaseValidation = source.substringAfter("private fun adoptionLeaseIsCurrent(")
            .substringBefore("private fun adoptRuntimeHandle(")
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
                leaseValidation.contains("playbackHandle(handle.generation)"),
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

        assertTrue(
            attach.contains("ghostRuntime.identity().playbackHandle(handle.generation)"),
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
    fun currentHostCanStopAnAlreadyStoppedClock() {
        val runner = runtimes.create().runner
        val host = SScriptRunner.HostToken()
        val staleHost = SScriptRunner.HostToken()

        LegacyPlatform.withTestSeams(
            clock = { 0L },
            delayedScheduler = { _, _ -> },
            delayedCancellation = {},
        ) {
            runner.bindHost(
                host,
                {},
                {},
                recordingUiCallback(mutableListOf()),
                RecordingStatusCallback(),
            )
            assertTrue(runner.startClock(host))
            assertTrue(runner.stopClock(host))

            assertTrue("Current host could not confirm an already-stopped clock", runner.stopClock(host))
            assertFalse("A stale host claimed the stopped clock", runner.stopClock(staleHost))
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
                        override fun canExit(expectedGeneration: Long?) {
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
    fun scheduledExitDeliveryRevalidatesHostBeforeConsuming() {
        val responseScheduler = QueuedResponseScheduler()
        val fixture = runtimes.create(
            autoStart = false,
            autoAttach = false,
            responseSchedulerFactory = { responseScheduler },
        )
        val runner = fixture.runner.apply { setNoWaitMode(true) }
        val hostA = SScriptRunner.HostToken()
        val hostB = SScriptRunner.HostToken()
        val aStatus = RecordingStatusCallback()
        val bStatus = RecordingStatusCallback()

        runner.bindHost(hostA, {}, {}, recordingUiCallback(mutableListOf()), aStatus)
        runner.doExit()
        runner.bindHost(hostB, {}, {}, recordingUiCallback(mutableListOf()), bStatus)
        responseScheduler.drain()

        assertEquals("Superseded host consumed scheduled exit delivery", 0, aStatus.canExitCount)
        assertEquals(1, bStatus.canExitCount)
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
    fun exitAbortsPendingSwitchBeforeStoppingOutgoingPlayback() {
        val playbackScheduler = QueuedPlaybackScheduler()
        val fixture = runtimes.create(
            response = { request ->
                when {
                    "ID: OnGhostChanging\r\n" in request ->
                        "SHIORI/3.0 200 OK\r\nValue: \\hChanging\\e\r\n\r\n"
                    else -> "SHIORI/3.0 204 No Content\r\n\r\n"
                }
            },
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackSchedulerFactory = { playbackScheduler },
            ),
        )
        val runner = fixture.runner
        val status = RecordingStatusCallback()
        runner.bindHost(
            SScriptRunner.HostToken(), {}, {}, recordingUiCallback(mutableListOf()), status,
        )
        val outgoing = fixture.requireHandle()
        val targetRoot = File("build/runtime-fixtures/exit-aborts-switch-target")
        val switchId = (fixture.runtime.beginSwitch(
            outgoing.generation,
            "exit-aborts-switch-target",
            targetRoot,
        ) as RuntimeResult.Success).value
        assertTrue(runner.doGhostChanging(switchId, "Target", "manual", targetRoot.path))
        assertEquals(GhostRuntimePhase.SwitchPlayback, fixture.runtime.identity().phase)

        runner.doExit()

        assertEquals(1, status.canExitCount)
        assertTrue(fixture.trace.requests.any { "ID: OnClose\r\n" in it })
        assertEquals(GhostRuntimePhase.Idle, fixture.runtime.identity().phase)
        assertEquals(1, fixture.trace.unloadCount.get())
    }

    @Test
    fun exitDuringClaimedSwitchRetargetsCloseToReplacement() {
        val playbackScheduler = QueuedPlaybackScheduler()
        val unloadEntered = CountDownLatch(1)
        val releaseUnload = CountDownLatch(1)
        val exitCaptured = CountDownLatch(1)
        val exitExecutor = Executors.newSingleThreadExecutor()
        val fixture = runtimes.create(
            response = { request ->
                if ("ID: OnGhostChanging\r\n" in request) {
                    "SHIORI/3.0 200 OK\r\nValue: \\hChanging\\e\r\n\r\n"
                } else {
                    "SHIORI/3.0 204 No Content\r\n\r\n"
                }
            },
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackSchedulerFactory = { playbackScheduler },
                playbackHooks = SScriptPlaybackHooks(
                    afterExitCaptured = { exitCaptured.countDown() },
                ),
            ),
        )
        val runner = fixture.runner
        val status = RecordingStatusCallback()
        runner.bindHost(
            SScriptRunner.HostToken(), {}, {}, recordingUiCallback(mutableListOf()), status,
        )
        val outgoing = fixture.requireHandle()
        val targetId = "claimed-switch-exit-target"
        val targetRoot = File("build/runtime-fixtures/$targetId")
        val switchId = (fixture.runtime.beginSwitch(
            outgoing.generation,
            targetId,
            targetRoot,
        ) as RuntimeResult.Success).value
        fixture.trace.unloadObserver.set {
            unloadEntered.countDown()
            check(releaseUnload.await(5, TimeUnit.SECONDS))
        }

        try {
            assertTrue(runner.doGhostChanging(switchId, "Target", "manual", targetRoot.path))
            playbackScheduler.drain()
            assertTrue("Claimed switch unload did not start", unloadEntered.await(5, TimeUnit.SECONDS))

            val exitCall = exitExecutor.submit<Boolean> { runner.doExit(); true }
            assertTrue("Exit did not capture the outgoing generation", exitCaptured.await(5, TimeUnit.SECONDS))
            releaseUnload.countDown()
            fixture.trace.unloadObserver.set(null)
            assertTrue(exitCall.get(5, TimeUnit.SECONDS))
            assertEquals(
                outgoing.generation to "AwaitingReplacement",
                runner.exitStateForTesting(),
            )

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var replacement: GhostHandle? = null
            while (System.nanoTime() < deadline && replacement == null) {
                replacement = fixture.runtime.identity().activeHandle?.takeIf {
                    it.generation != outgoing.generation
                }
                if (replacement == null) Thread.sleep(10)
            }
            val replacementHandle = requireNotNull(replacement) { "Replacement was not published" }
            runBlocking {
                assertTrue(fixture.runtime.attachHost(replacementHandle.generation) is RuntimeResult.Success)
            }

            while (System.nanoTime() < deadline && status.canExitCount == 0) {
                Thread.sleep(10)
            }
            assertEquals(1, status.canExitCount)
            assertEquals(
                "Unexpected unload trace: ${fixture.trace.lifecycleEvents}; requests=${fixture.trace.ownedRequests}",
                2,
                fixture.trace.unloadCount.get(),
            )
            assertEquals(GhostRuntimePhase.Idle, fixture.runtime.identity().phase)
            assertTrue(
                fixture.trace.ownedRequests.any {
                    it.ownerGhostId == targetId && "ID: OnClose\r\n" in it.protocolText
                },
            )
        } finally {
            releaseUnload.countDown()
            fixture.trace.unloadObserver.set(null)
            exitExecutor.shutdownNow()
        }
    }

    @Test
    fun switchRetirementPreservesExitAwaitingPlaybackForReplacement() {
        val playbackScheduler = QueuedPlaybackScheduler()
        val fixture = runtimes.create(
            response = { request ->
                if ("ID: OnClose\r\n" in request) {
                    "SHIORI/3.0 200 OK\r\nValue: \\hClosing\\e\r\n\r\n"
                } else {
                    "SHIORI/3.0 204 No Content\r\n\r\n"
                }
            },
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackSchedulerFactory = { playbackScheduler },
            ),
        )
        val runner = fixture.runner
        runner.bindHost(
            SScriptRunner.HostToken(), {}, {}, recordingUiCallback(mutableListOf()),
            RecordingStatusCallback(),
        )
        val outgoing = fixture.requireHandle()

        runner.doExit()
        assertEquals(outgoing.generation to "AwaitingPlayback", runner.exitStateForTesting())

        runner.retireGenerationForSwitch(outgoing.generation)

        assertEquals(
            outgoing.generation to "AwaitingReplacement",
            runner.exitStateForTesting(),
        )
    }

    @Test
    fun switchRetirementPreservesExitAfterResponseFence() {
        val fenceEntered = CountDownLatch(1)
        val releaseFence = CountDownLatch(1)
        val blockFence = AtomicBoolean(false)
        val requestExecutor = Executors.newSingleThreadExecutor()
        val fixture = runtimes.create(
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackHooks = SScriptPlaybackHooks(
                    afterRequestResponseFence = {
                        if (blockFence.get()) {
                            fenceEntered.countDown()
                            check(releaseFence.await(5, TimeUnit.SECONDS))
                        }
                    },
                ),
            ),
        )
        val runner = fixture.runner
        runner.bindHost(
            SScriptRunner.HostToken(), {}, {}, recordingUiCallback(mutableListOf()),
            RecordingStatusCallback(),
        )
        val outgoing = fixture.requireHandle()

        try {
            blockFence.set(true)
            val exit = requestExecutor.submit<Unit> { runner.doExit() }
            assertTrue("Exit response did not reach its admission fence", fenceEntered.await(5, TimeUnit.SECONDS))

            runner.retireGenerationForSwitch(outgoing.generation)
            releaseFence.countDown()
            exit.get(5, TimeUnit.SECONDS)

            assertEquals(
                outgoing.generation to "AwaitingReplacement",
                runner.exitStateForTesting(),
            )
        } finally {
            releaseFence.countDown()
            requestExecutor.shutdownNow()
        }
    }

    @Test
    fun switchRetirementPreservesExitAwaitingUnloadForReplacement() {
        val playbackScheduler = QueuedPlaybackScheduler()
        val unloadEntered = CountDownLatch(1)
        val releaseUnload = CountDownLatch(1)
        val playbackExecutor = Executors.newSingleThreadExecutor()
        val fixture = runtimes.create(
            response = { request ->
                if ("ID: OnClose\r\n" in request) {
                    "SHIORI/3.0 200 OK\r\nValue: \\hClosing\\e\r\n\r\n"
                } else {
                    "SHIORI/3.0 204 No Content\r\n\r\n"
                }
            },
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackSchedulerFactory = { playbackScheduler },
            ),
        )
        val runner = fixture.runner
        runner.bindHost(
            SScriptRunner.HostToken(), {}, {}, recordingUiCallback(mutableListOf()),
            RecordingStatusCallback(),
        )
        val outgoing = fixture.requireHandle()
        fixture.trace.unloadObserver.set {
            unloadEntered.countDown()
            check(releaseUnload.await(5, TimeUnit.SECONDS))
        }

        try {
            runner.doExit()
            val playback = playbackExecutor.submit<Unit> { playbackScheduler.drain() }
            assertTrue("Exit unload did not start", unloadEntered.await(5, TimeUnit.SECONDS))
            assertEquals(outgoing.generation to "Unloading", runner.exitStateForTesting())

            runner.retireGeneration(
                outgoing.generation,
                runner.exitOperationIdForTesting(),
            )
            runner.retireGenerationForSwitch(outgoing.generation)

            assertEquals(
                outgoing.generation to "AwaitingReplacement",
                runner.exitStateForTesting(),
            )
            releaseUnload.countDown()
            playback.get(5, TimeUnit.SECONDS)
        } finally {
            releaseUnload.countDown()
            fixture.trace.unloadObserver.set(null)
            playbackExecutor.shutdownNow()
        }
    }

    @Test
    fun claimedSwitchPreventsIdempotentExitUnloadFromDeliveringReadyHost() {
        val fixture = runtimes.create()
        val runner = fixture.runner
        val status = RecordingStatusCallback()
        runner.bindHost(
            SScriptRunner.HostToken(), {}, {}, recordingUiCallback(mutableListOf()), status,
        )
        val outgoing = fixture.requireHandle()
        val targetRoot = File("build/runtime-fixtures/claimed-switch-ready-exit-target")
        assertTrue(
            fixture.runtime.beginSwitch(
                outgoing.generation,
                "claimed-switch-ready-exit-target",
                targetRoot,
            ) is RuntimeResult.Success,
        )

        runner.doExit()

        assertEquals(0, status.canExitCount)
        assertEquals(
            outgoing.generation to "AwaitingReplacement",
            runner.exitStateForTesting(),
        )
    }

    @Test
    fun exitInPostRetirementReplacementGapWaitsForReplacementClose() {
        val playbackScheduler = QueuedPlaybackScheduler()
        val gapEntered = CountDownLatch(1)
        val releaseReplacement = CountDownLatch(1)
        val fixture = runtimes.create(
            response = { request ->
                if ("ID: OnGhostChanging\r\n" in request) {
                    "SHIORI/3.0 200 OK\r\nValue: \\hChanging\\e\r\n\r\n"
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
            SScriptRunner.HostToken(), {}, {}, recordingUiCallback(mutableListOf()), status,
        )
        val outgoing = fixture.requireHandle()
        val targetId = "post-retirement-exit-target"
        val targetRoot = File("build/runtime-fixtures/$targetId")
        val hookToken = fixture.runtime.installTestHooksForTesting(
            GhostRuntimeTestHooks(
                afterSwitchRunnerRetired = {
                    gapEntered.countDown()
                    check(releaseReplacement.await(5, TimeUnit.SECONDS))
                },
            ),
        )

        try {
            val switchId = (fixture.runtime.beginSwitch(
                outgoing.generation,
                targetId,
                targetRoot,
            ) as RuntimeResult.Success).value
            assertTrue(runner.doGhostChanging(switchId, "Target", "manual", targetRoot.path))
            playbackScheduler.drain()
            assertTrue("Switch did not reach the post-retirement gap", gapEntered.await(5, TimeUnit.SECONDS))

            runner.doExit()

            assertEquals("Exit completed before replacement attachment", 0, status.canExitCount)
            assertEquals(null to "AwaitingReplacement", runner.exitStateForTesting())
            releaseReplacement.countDown()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var replacement: GhostHandle? = null
            while (System.nanoTime() < deadline && replacement == null) {
                replacement = fixture.runtime.identity().activeHandle?.takeIf {
                    it.generation != outgoing.generation
                }
                if (replacement == null) Thread.sleep(10)
            }
            val replacementHandle = requireNotNull(replacement) { "Replacement was not published" }
            runBlocking {
                assertTrue(
                    fixture.runtime.attachHost(replacementHandle.generation) is RuntimeResult.Success,
                )
            }
            while (System.nanoTime() < deadline && status.canExitCount == 0) Thread.sleep(10)

            assertEquals(1, status.canExitCount)
            assertTrue(
                fixture.trace.ownedRequests.any {
                    it.ownerGhostId == targetId && "ID: OnClose\r\n" in it.protocolText
                },
            )
        } finally {
            releaseReplacement.countDown()
            hookToken.close()
        }
    }

    @Test
    fun replacementCloseRequestWaitsForMainResponseScheduler() {
        val playbackScheduler = QueuedPlaybackScheduler()
        val responseScheduler = GatedResponseScheduler()
        val gapEntered = CountDownLatch(1)
        val releaseReplacement = CountDownLatch(1)
        val onCloseRequestCount = AtomicInteger()
        val fixture = runtimes.create(
            response = { request ->
                when {
                    "ID: OnGhostChanging\r\n" in request ->
                        "SHIORI/3.0 200 OK\r\nValue: \\hChanging\\e\r\n\r\n"
                    "ID: OnClose\r\n" in request -> {
                        onCloseRequestCount.incrementAndGet()
                        "SHIORI/3.0 200 OK\r\nValue: \\hClose\\e\r\n\r\n"
                    }
                    else -> "SHIORI/3.0 204 No Content\r\n\r\n"
                }
            },
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackSchedulerFactory = { playbackScheduler },
            ),
            responseSchedulerFactory = { responseScheduler },
        )
        val runner = fixture.runner.apply { setNoWaitMode(true) }
        runner.bindHost(
            SScriptRunner.HostToken(),
            {},
            {},
            recordingUiCallback(mutableListOf()),
            RecordingStatusCallback(),
        )
        val outgoing = fixture.requireHandle()
        val targetRoot = File("build/runtime-fixtures/replacement-close-main")
        val hookToken = fixture.runtime.installTestHooksForTesting(
            GhostRuntimeTestHooks(
                afterSwitchRunnerRetired = {
                    gapEntered.countDown()
                    check(releaseReplacement.await(5, TimeUnit.SECONDS))
                },
            ),
        )

        try {
            val switchId = (fixture.runtime.beginSwitch(
                outgoing.generation,
                targetRoot.name,
                targetRoot,
            ) as RuntimeResult.Success).value
            assertTrue(runner.doGhostChanging(switchId, "Target", "manual", targetRoot.path))
            playbackScheduler.drain()
            assertTrue("Switch did not reach the post-retirement gap", gapEntered.await(5, TimeUnit.SECONDS))
            runner.doExit()
            releaseReplacement.countDown()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var replacement: GhostHandle? = null
            while (System.nanoTime() < deadline && replacement == null) {
                replacement = fixture.runtime.identity().activeHandle?.takeIf {
                    it.generation != outgoing.generation
                }
                if (replacement == null) Thread.sleep(10)
            }
            val replacementHandle = requireNotNull(replacement) { "Replacement was not published" }
            responseScheduler.defer()
            runBlocking {
                assertTrue(
                    fixture.runtime.attachHost(replacementHandle.generation) is RuntimeResult.Success,
                )
            }

            responseScheduler.awaitQueued()
            assertEquals("Replacement close bypassed the response scheduler", 0, onCloseRequestCount.get())
            responseScheduler.drain()
            assertEquals(1, onCloseRequestCount.get())
        } finally {
            releaseReplacement.countDown()
            hookToken.close()
        }
    }

    @Test
    fun replacementAttachmentRetargetsNullGenerationExitBeforeExitFence() {
        val playbackScheduler = QueuedPlaybackScheduler()
        val gapEntered = CountDownLatch(1)
        val releaseReplacement = CountDownLatch(1)
        val exitCaptured = CountDownLatch(1)
        val releaseExitFence = CountDownLatch(1)
        val exitExecutor = Executors.newSingleThreadExecutor()
        val fixture = runtimes.create(
            response = { request ->
                if ("ID: OnGhostChanging\r\n" in request) {
                    "SHIORI/3.0 200 OK\r\nValue: \\hChanging\\e\r\n\r\n"
                } else {
                    "SHIORI/3.0 204 No Content\r\n\r\n"
                }
            },
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackSchedulerFactory = { playbackScheduler },
                playbackHooks = SScriptPlaybackHooks(
                    afterExitCaptured = {
                        exitCaptured.countDown()
                        check(releaseExitFence.await(5, TimeUnit.SECONDS))
                    },
                ),
            ),
        )
        val runner = fixture.runner
        val status = RecordingStatusCallback()
        runner.bindHost(
            SScriptRunner.HostToken(), {}, {}, recordingUiCallback(mutableListOf()), status,
        )
        val outgoing = fixture.requireHandle()
        val targetId = "attachment-wins-exit-fence"
        val targetRoot = File("build/runtime-fixtures/$targetId")
        val hookToken = fixture.runtime.installTestHooksForTesting(
            GhostRuntimeTestHooks(
                afterSwitchRunnerRetired = {
                    gapEntered.countDown()
                    check(releaseReplacement.await(5, TimeUnit.SECONDS))
                },
            ),
        )

        try {
            val switchId = (fixture.runtime.beginSwitch(
                outgoing.generation,
                targetId,
                targetRoot,
            ) as RuntimeResult.Success).value
            assertTrue(runner.doGhostChanging(switchId, "Target", "manual", targetRoot.path))
            playbackScheduler.drain()
            assertTrue("Switch did not reach the post-retirement gap", gapEntered.await(5, TimeUnit.SECONDS))

            val exitCall = exitExecutor.submit<Unit> { runner.doExit() }
            assertTrue("Exit did not pause before its runtime fence", exitCaptured.await(5, TimeUnit.SECONDS))
            releaseReplacement.countDown()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var replacement: GhostHandle? = null
            while (System.nanoTime() < deadline && replacement == null) {
                replacement = fixture.runtime.identity().activeHandle?.takeIf {
                    it.generation != outgoing.generation
                }
                if (replacement == null) Thread.sleep(10)
            }
            val replacementHandle = requireNotNull(replacement) { "Replacement was not published" }
            runBlocking {
                assertTrue(
                    fixture.runtime.attachHost(replacementHandle.generation) is RuntimeResult.Success,
                )
            }
            releaseExitFence.countDown()
            exitCall.get(5, TimeUnit.SECONDS)
            while (System.nanoTime() < deadline && status.canExitCount == 0) Thread.sleep(10)

            assertEquals(
                "Exit did not complete after attachment won: state=${runner.exitStateForTesting()}; " +
                    "runtime=${fixture.runtime.identity()}; requests=${fixture.trace.ownedRequests}",
                1,
                status.canExitCount,
            )
            assertTrue(
                fixture.trace.ownedRequests.any {
                    it.ownerGhostId == targetId && "ID: OnClose\r\n" in it.protocolText
                },
            )
        } finally {
            releaseReplacement.countDown()
            releaseExitFence.countDown()
            hookToken.close()
            exitExecutor.shutdownNow()
        }
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

            override fun canExit(expectedGeneration: Long?) {
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
        assertEquals(fixture.handle?.generation, status.expectedGeneration)
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
        var expectedGeneration: Long? = null
        override fun stop() = Unit
        override fun canExit(expectedGeneration: Long?) {
            canExitCount++
            this.expectedGeneration = expectedGeneration
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

    private class QueuedResponseScheduler : SScriptResponseScheduler {
        private val actions = ArrayDeque<() -> Unit>()

        override fun schedule(action: () -> Unit) {
            actions.addLast(action)
        }

        fun drain() {
            while (actions.isNotEmpty()) actions.removeFirst().invoke()
        }
    }

    private class GatedResponseScheduler : SScriptResponseScheduler {
        private val actions = ArrayDeque<() -> Unit>()
        private val queued = CountDownLatch(1)
        private var deferred = false

        override fun schedule(action: () -> Unit) {
            val runNow = synchronized(this) {
                if (deferred) {
                    actions.addLast(action)
                    queued.countDown()
                }
                !deferred
            }
            if (runNow) action()
        }

        fun defer() = synchronized(this) {
            deferred = true
        }

        fun awaitQueued() {
            check(queued.await(5, TimeUnit.SECONDS)) { "Response action was not queued" }
        }

        fun drain() {
            while (true) {
                val action = synchronized(this) {
                    if (actions.isEmpty()) null else actions.removeFirst()
                } ?: return
                action()
            }
        }
    }
}
