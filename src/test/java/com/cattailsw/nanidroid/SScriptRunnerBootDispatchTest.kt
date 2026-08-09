package com.cattailsw.nanidroid

import android.os.Handler
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import com.cattailsw.nanidroid.di.MonotonicClock
import com.cattailsw.nanidroid.runtime.dialogue.ShioriMethod
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities
import com.cattailsw.nanidroid.runtime.dialogue.Support
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import androidx.compose.ui.unit.IntOffset
import java.util.Hashtable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Characterizes boot delivery across runner clock and ghost lifecycles.  */
class SScriptRunnerBootDispatchTest {
    @Rule
    @JvmField
    val androidStubs: com.cattailsw.nanidroid.HostAndroidStubRule =
        com.cattailsw.nanidroid.HostAndroidStubRule()

    private val runners: MutableList<com.cattailsw.nanidroid.SScriptRunner> =
        ArrayList<com.cattailsw.nanidroid.SScriptRunner>()

    @After
    fun stopClocks() {
        for (runner in runners) {
            runner.stopClock()
        }
    }

    @Test
    fun dispatchesBootOnceAcrossDuplicateStartResumeAndNamedGhostHandoff() {
        val trace: MutableList<String?> = ArrayList<String?>()
        val runner: com.cattailsw.nanidroid.SScriptRunner = runner()
        val initial = RecordingGhost("initial", "Initial Ghost", 2, trace)
        val replacement = RecordingGhost("replacement", "Replacement Ghost", 2, trace)

        runner.setGhost(initial)
        runner.startClock()
        runner.startClock()
        runner.stopClock()
        runner.startClock()
        runner.stopClock()
        runner.unloadGhostForSwitchForTesting(initial)
        Assert.assertTrue(
            runner.attachReservedGhost(runner.reserveGhostForAttachmentForTesting(replacement)),
        )
        runner.startClock()

        Assert.assertEquals(
            mutableListOf<String?>(
                "initial:OnBoot:[master]",
                "replacement:OnGhostChanged:[Initial Ghost, null]"
            ),
            trace
        )
    }

    @Test
    fun newlyConstructedRunnerDispatchesBootOnceAfterAppRecreation() {
        val trace: MutableList<String?> = ArrayList<String?>()
        val runner: com.cattailsw.nanidroid.SScriptRunner = runner()

        runner.setGhost(RecordingGhost("recreated", "Recreated Ghost", 2, trace))
        runner.startClock()

        Assert.assertEquals(mutableListOf<String?>("recreated:OnBoot:[master]"), trace)
    }

    @Test
    fun firstActivationReplacementSendsFirstBootWithoutAdditionalBoot() {
        val trace: MutableList<String?> = ArrayList<String?>()
        val runner: com.cattailsw.nanidroid.SScriptRunner = runner()
        val initial = RecordingGhost("initial", "Initial Ghost", 2, trace)
        runner.setGhost(initial)
        runner.startClock()
        runner.stopClock()
        runner.unloadGhostForSwitchForTesting(initial)
        Assert.assertTrue(
            runner.attachReservedGhost(
                runner.reserveGhostForAttachmentForTesting(
                    RecordingGhost("replacement", "New Ghost", 0, trace),
                ),
            ),
        )
        runner.startClock()

        Assert.assertEquals(
            mutableListOf<String?>(
                "initial:OnBoot:[master]",
                "replacement:OnFirstBoot:[0]"
            ),
            trace
        )
    }

    @Test
    fun timerUsesSleepInclusiveClockHoursAndPlaysOnlyIdleGetResponses() {
        val trace = mutableListOf<String?>()
        val clock = FakeClock(7 * 3_600_000L + 1_000L)
        val runner = SScriptRunner(null, GhostSessionCoordinator(), clock)
        runner.setNoWaitMode(true)
        val ghost = RawRecordingGhost("timer", "Timer", 2, trace).apply {
            rawResponses += talk("\\hawake\\e")
        }
        runner.setGhost(ghost)

        runner.dispatchClockTickForTesting()

        Assert.assertEquals(
            listOf("GET:OnSecondChange:[7, 0, 0, 1]"),
            ghost.rawRequests,
        )
        Assert.assertTrue(runner.dialogueStateSnapshot().contents.any { it.segments.toString().contains("awake") })
    }

    @Test
    fun timerDispatchesChangedElapsedBucketsAfterADelayedJump() {
        val clock = FakeClock(59_000L)
        val runner = SScriptRunner(null, GhostSessionCoordinator(), clock)
        runner.setNoWaitMode(true)
        val ghost = RawRecordingGhost("timer", "Timer", 2, mutableListOf())
        runner.setGhost(ghost)

        runner.dispatchClockTickForTesting()
        clock.millis = 61_000L
        runner.dispatchClockTickForTesting()

        Assert.assertEquals(
            listOf(
                "GET:OnSecondChange:[0, 0, 0, 1]",
                "GET:OnSecondChange:[0, 0, 0, 1]",
                "GET:OnMinuteChange:[0, 0, 0, 1]",
            ),
            ghost.rawRequests,
        )
    }

    @Test
    fun timerDispatchesEachObservedSecondAndMinuteBoundaryOnce() {
        val clock = FakeClock(1_000L)
        val runner = SScriptRunner(null, GhostSessionCoordinator(), clock)
        runner.setNoWaitMode(true)
        val ghost = RawRecordingGhost("timer", "Timer", 2, mutableListOf())
        runner.setGhost(ghost)

        runner.dispatchClockTickForTesting()
        clock.millis = 2_000L
        runner.dispatchClockTickForTesting()
        clock.millis = 60_000L
        runner.dispatchClockTickForTesting()

        Assert.assertEquals(
            listOf(
                "GET:OnSecondChange:[0, 0, 0, 1]",
                "GET:OnSecondChange:[0, 0, 0, 1]",
                "GET:OnSecondChange:[0, 0, 0, 1]",
                "GET:OnMinuteChange:[0, 0, 0, 1]",
            ),
            ghost.rawRequests,
        )
    }

    @Test
    fun timerGetDoesNotPlayAfterAnInterveningTalkReturnsIdle() {
        val runner = SScriptRunner(null, GhostSessionCoordinator(), FakeClock(1_000L))
        runner.setNoWaitMode(true)
        val ghost = RawRecordingGhost("timer", "Timer", 2, mutableListOf()).apply {
            rawResponseHook = {
                runner.addMsgToQueue(arrayOf("\\hIntervening\\e"))
                runner.run()
            }
            rawResponses += talk("\\hStaleTimer\\e")
        }
        runner.setGhost(ghost)

        runner.dispatchClockTickForTesting()

        Assert.assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("Intervening")))),
            runner.dialogueStateSnapshot().contents,
        )
    }

    @Test
    fun timerGetPlaysWhenIdleEligibilityNeverChanges() {
        val runner = SScriptRunner(null, GhostSessionCoordinator(), FakeClock(1_000L))
        runner.setNoWaitMode(true)
        val ghost = RawRecordingGhost("timer", "Timer", 2, mutableListOf()).apply {
            rawResponses += talk("\\hTimer\\e")
        }
        runner.setGhost(ghost)

        runner.dispatchClockTickForTesting()

        Assert.assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("Timer")))),
            runner.dialogueStateSnapshot().contents,
        )
    }

    @Test
    fun timerGetStillDropsWhenInterveningTalkRemainsActive() {
        val scheduler = RecordingPlaybackScheduler()
        val runner = SScriptRunner(null, GhostSessionCoordinator(), FakeClock(1_000L), { scheduler })
        val ghost = RawRecordingGhost("timer", "Timer", 2, mutableListOf()).apply {
            rawResponseHook = {
                runner.addMsgToQueue(arrayOf("\\hIntervening\\e"))
                runner.run()
            }
            rawResponses += talk("\\hStaleTimer\\e")
        }
        runner.setGhost(ghost)

        runner.dispatchClockTickForTesting()
        scheduler.runPending()

        Assert.assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("Intervening")))),
            runner.dialogueStateSnapshot().contents,
        )
    }

    @Test
    fun passiveTimerSendsNotifyAndDoesNotReplaceItsDialogueOrPendingActions() {
        val clock = FakeClock(3_600_000L + 1_000L)
        val runner = SScriptRunner(null, GhostSessionCoordinator(), clock)
        runner.setNoWaitMode(true)
        val ghost = RawRecordingGhost("timer", "Timer", 2, mutableListOf()).apply {
            rawResponses += talk("\\hignored\\e")
        }
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hbefore\\q[Keep,keep]\\![enter,passivemode]\\e"))
        runner.run()
        val before = runner.dialogueStateSnapshot()

        runner.dispatchClockTickForTesting()

        Assert.assertEquals(
            listOf("NOTIFY:OnSecondChange:[1, 0, 0, 0]"),
            ghost.rawRequests,
        )
        Assert.assertEquals(before.contents, runner.dialogueStateSnapshot().contents)
        Assert.assertEquals(before.pendingChoices, runner.dialogueStateSnapshot().pendingChoices)
    }

    @Test
    fun passiveSurfaceTapIsIgnoredBeforeItCanClearKeroDialogue() {
        val runner = SScriptRunner(null, GhostSessionCoordinator(), FakeClock(1_000L))
        runner.setNoWaitMode(true)
        val ghost = RawRecordingGhost("surface", "Surface", 2, mutableListOf())
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hbefore\\q[Keep,keep]\\![enter,passivemode]\\e"))
        runner.run()
        val before = runner.dialogueStateSnapshot()

        val dispatched = runner.dispatchSurfaceInteraction(
            SurfaceInteractionEffect(
                PointerEventKind.CLICK,
                SurfaceSpeaker.KERO,
                IntOffset.Zero,
                0,
                PointerSource.TOUCH,
                null,
                null,
            ),
        )

        Assert.assertTrue(dispatched)
        Assert.assertEquals(
            listOf("GET:OnMouseClick:[0, 0, 0, 1, , 0, touch]"),
            ghost.rawRequests,
        )
        Assert.assertEquals(before, runner.dialogueStateSnapshot())
    }

    @Test
    fun ordinaryPendingChoiceStillAcceptsAndPlaysAPointerResponse() {
        val runner = SScriptRunner(null, GhostSessionCoordinator(), FakeClock(1_000L))
        runner.setNoWaitMode(true)
        val ghost = RawRecordingGhost("surface", "Surface", 2, mutableListOf()).apply {
            rawResponses += talk("\\hpointer reply\\e")
        }
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hchoice\\q[Choose,choice]\\e"))
        runner.run()

        Assert.assertTrue(
            runner.dispatchSurfaceInteraction(
                SurfaceInteractionEffect(
                    PointerEventKind.CLICK,
                    SurfaceSpeaker.SAKURA,
                    IntOffset.Zero,
                    0,
                    PointerSource.TOUCH,
                    null,
                    null,
                ),
            ),
        )

        Assert.assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("pointer reply")))),
            runner.dialogueStateSnapshot().contents,
        )
    }

    @Test
    fun passiveCommandsAreIdempotentAndUnloadClearsOnlyTheMode() {
        val runner = SScriptRunner(null, GhostSessionCoordinator(), FakeClock(1_000L))
        runner.setNoWaitMode(true)
        val ghost = RawRecordingGhost("passive", "Passive", 2, mutableListOf())
        runner.setGhost(ghost)

        runner.addMsgToQueue(arrayOf("\\hshown\\q[Keep,keep]\\![enter,passivemode]\\![enter,passivemode]\\e"))
        runner.run()
        val shown = runner.dialogueStateSnapshot()
        Assert.assertTrue(runner.runtimeModeSnapshot().passive)

        runner.addMsgToQueue(arrayOf("\\![leave,passivemode]\\![leave,passivemode]\\e"))
        runner.run()
        Assert.assertFalse(runner.runtimeModeSnapshot().passive)
        Assert.assertEquals(shown.pendingChoices, runner.dialogueStateSnapshot().pendingChoices)

        runner.addMsgToQueue(arrayOf("\\![enter,passivemode]\\e"))
        runner.run()
        Assert.assertTrue(runner.runtimeModeSnapshot().passive)
        Assert.assertTrue(runner.unloadGhostForSwitchForTesting(ghost))
        Assert.assertFalse(runner.runtimeModeSnapshot().passive)
        Assert.assertEquals(shown.pendingChoices, runner.dialogueStateSnapshot().pendingChoices)
    }

    @Test
    fun speakerChangeBeforePassiveOnlyCommandPreservesExistingDialogueAndChoices() {
        val runner = SScriptRunner(null, GhostSessionCoordinator(), FakeClock(1_000L))
        runner.setNoWaitMode(true)
        runner.setGhost(RawRecordingGhost("passive-clear", "Passive clear", 2, mutableListOf()))
        runner.addMsgToQueue(arrayOf("\\hshown\\q[Keep,keep]\\e"))
        runner.run()
        val before = runner.dialogueStateSnapshot()

        runner.addMsgToQueue(arrayOf("\\u\\![enter,passivemode]\\e"))
        runner.run()

        val after = runner.dialogueStateSnapshot()
        Assert.assertTrue(runner.runtimeModeSnapshot().passive)
        Assert.assertEquals(before.contents, after.contents)
        Assert.assertEquals(before.pendingChoices, after.pendingChoices)
    }

    @Test
    fun authoredClearBeforePassiveCommandRetiresExistingDialogueAndChoices() {
        val runner = SScriptRunner(null, GhostSessionCoordinator(), FakeClock(1_000L))
        runner.setNoWaitMode(true)
        runner.setGhost(RawRecordingGhost("passive-authored-clear", "Passive authored clear", 2, mutableListOf()))
        runner.addMsgToQueue(arrayOf("\\hshown\\q[Keep,keep]\\e"))
        runner.run()

        runner.addMsgToQueue(arrayOf("\\c\\![enter,passivemode]\\e"))
        runner.run()

        val after = runner.dialogueStateSnapshot()
        Assert.assertTrue(runner.runtimeModeSnapshot().passive)
        Assert.assertTrue(after.contents.flatMap { it.segments }.none { it is DialogueSegment.Text })
        Assert.assertTrue(after.pendingChoices.isEmpty())
    }

    @Test
    fun updateReloadInvalidatesPassiveDialogueAndStaleActionsFromTheOldSession() {
        val runner = SScriptRunner(null, GhostSessionCoordinator(), FakeClock(1_000L))
        runner.setNoWaitMode(true)
        val ghost = RawRecordingGhost("update", "Update", 2, mutableListOf())
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hshown\\q[Old,old]\\![enter,passivemode]\\e"))
        runner.run()
        val oldAction = runner.dialogueStateSnapshot().pendingChoices.single()

        runner.withGhostUpdateCommitQuiesced(ghost.getGhostId(), java.io.File(ghost.getGhostPath())) { Unit }
        runner.activateChoice(oldAction)

        Assert.assertFalse(runner.runtimeModeSnapshot().passive)
        Assert.assertTrue(runner.dialogueStateSnapshot().contents.isEmpty())
        Assert.assertTrue(runner.dialogueStateSnapshot().pendingChoices.isEmpty())
        Assert.assertTrue(ghost.eventRequests.isEmpty())
    }

    @Test
    fun updateReloadWhileInputIsPausedLetsTheReloadedSessionPlayTimerTalk() {
        val runner = SScriptRunner(null, GhostSessionCoordinator(), FakeClock(1_000L))
        runner.setNoWaitMode(true)
        var shownInputId: String? = null
        runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) {
                shownInputId = id
            }

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
        })
        val ghost = RawRecordingGhost("update", "Update", 2, mutableListOf()).apply {
            rawResponses += talk("\\hnew session timer talk\\e")
        }
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hwaiting\\![open,inputbox,answer]\\w9old tail\\e"))
        runner.run()
        val pending = requireNotNull(runner.dialogueStateSnapshot().pendingInput)
        val staleDialog = DialogueDialogBinding { runner }.userInput("answer", pending.generation)
        Assert.assertEquals("answer", shownInputId)

        runner.withGhostUpdateCommitQuiesced(ghost.getGhostId(), java.io.File(ghost.getGhostPath())) { Unit }
        staleDialog.onSubmit("answer", "stale")
        staleDialog.onCancel()
        runner.dispatchClockTickForTesting()

        Assert.assertEquals(1, ghost.unloadCount)
        Assert.assertEquals(1, ghost.reloadCount)
        Assert.assertTrue(ghost.eventRequests.isEmpty())
        Assert.assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("new session timer talk")))),
            runner.dialogueStateSnapshot().contents,
        )
        Assert.assertFalse(runner.runtimeModeSnapshot().playingTalk)
    }

    @Test
    fun updateReloadRejectsQueuedPlaybackCallbacksFromTheInvalidatedSession() {
        val scheduler = RecordingPlaybackScheduler()
        val runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            FakeClock(1_000L),
            playbackSchedulerFactory = { scheduler },
        )
        var inputShown = false
        val frames = mutableListOf<GhostPresentationFrame>()
        runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) {
                inputShown = true
            }

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
        })
        runner.setPresentationRendererForTesting(frames::add)
        val ghost = RawRecordingGhost("update", "Update", 2, mutableListOf()).apply {
            rawResponses += talk("\\hnew session talk\\e")
        }
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hwaiting\\![open,inputbox,answer]\\w9old tail\\e"))
        runner.run()
        val stalePlaybackCallback = scheduler.captureNext()
        scheduler.runUntil { inputShown }
        Assert.assertEquals(0, scheduler.pendingCount)

        runner.withGhostUpdateCommitQuiesced(ghost.getGhostId(), java.io.File(ghost.getGhostPath())) { Unit }
        frames.clear()
        runner.dispatchClockTickForTesting()

        stalePlaybackCallback()
        Assert.assertTrue(frames.isEmpty())
        scheduler.runPending()
        Assert.assertTrue(frames.any { it.sakura.text.contains("new session talk") })
        Assert.assertTrue(frames.none { it.sakura.text.contains("old tail") })
        Assert.assertFalse(runner.runtimeModeSnapshot().playingTalk)
    }

    @Test
    fun ordinaryInputPauseAndResumeKeepsTheCurrentScriptPlayable() {
        val scheduler = RecordingPlaybackScheduler()
        val runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            FakeClock(1_000L),
            playbackSchedulerFactory = { scheduler },
        )
        var inputShown = false
        val frames = mutableListOf<GhostPresentationFrame>()
        runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) {
                inputShown = true
            }

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
        })
        runner.setPresentationRendererForTesting(frames::add)
        runner.setGhost(RawRecordingGhost("ordinary", "Ordinary", 2, mutableListOf()))
        runner.addMsgToQueue(arrayOf("\\hwaiting\\![open,inputbox,answer]\\w9resumed tail\\e"))
        runner.run()
        Assert.assertEquals(1, scheduler.pendingCount)
        runner.resumeEvt()
        Assert.assertEquals(1, scheduler.pendingCount)
        scheduler.runUntil { inputShown }
        val pending = requireNotNull(runner.dialogueStateSnapshot().pendingInput)
        val dialog = DialogueDialogBinding { runner }.userInput("answer", pending.generation)

        scheduler.runPending()
        Assert.assertTrue(frames.none { it.sakura.text.contains("resumed tail") })
        dialog.onSubmit("answer", "value")
        scheduler.runPending()

        Assert.assertTrue(frames.any { it.sakura.text.contains("resumed tail") })
        Assert.assertFalse(runner.runtimeModeSnapshot().playingTalk)
    }

    @Test
    fun claimedStopFromInvalidatedPlaybackCannotCancelReloadedTalk() {
        val scheduler = RecordingPlaybackScheduler()
        val claimed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            FakeClock(1_000L),
            playbackSchedulerFactory = { scheduler },
            playbackHooks = SScriptPlaybackHooks(
                afterStopClaimed = {
                    claimed.countDown()
                    Assert.assertTrue(release.await(5, TimeUnit.SECONDS))
                },
            ),
        )
        val frames = mutableListOf<GhostPresentationFrame>()
        runner.setPresentationRendererForTesting(frames::add)
        val ghost = RawRecordingGhost("update", "Update", 2, mutableListOf())
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\e"))
        runner.run()
        scheduler.runNext()
        scheduler.runNext()

        val oldStop = Thread(scheduler::runNext)
        oldStop.start()
        Assert.assertTrue(claimed.await(5, TimeUnit.SECONDS))
        runner.withGhostUpdateCommitQuiesced(ghost.getGhostId(), java.io.File(ghost.getGhostPath())) { Unit }
        frames.clear()
        runner.addMsgToQueue(arrayOf("\\hnew session talk\\e"))
        runner.run()
        Assert.assertEquals(1, scheduler.pendingCount)

        release.countDown()
        oldStop.join(5_000L)
        Assert.assertFalse(oldStop.isAlive)
        Assert.assertEquals(1, scheduler.pendingCount)
        scheduler.runPending()

        Assert.assertTrue(frames.any { it.sakura.text.contains("new session talk") })
        Assert.assertFalse(runner.runtimeModeSnapshot().playingTalk)
    }

    @Test
    fun claimedRunFromInvalidatedPlaybackCannotAdvanceReloadedTalk() {
        val scheduler = RecordingPlaybackScheduler()
        val claimed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            FakeClock(1_000L),
            playbackSchedulerFactory = { scheduler },
            playbackHooks = SScriptPlaybackHooks(
                afterRunClaimed = {
                    claimed.countDown()
                    Assert.assertTrue(release.await(5, TimeUnit.SECONDS))
                },
            ),
        )
        val frames = mutableListOf<GhostPresentationFrame>()
        runner.setPresentationRendererForTesting(frames::add)
        val ghost = RawRecordingGhost("update", "Update", 2, mutableListOf())
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hold session talk\\e"))
        runner.run()

        val oldRun = Thread(scheduler::runNext)
        oldRun.start()
        Assert.assertTrue(claimed.await(5, TimeUnit.SECONDS))
        runner.withGhostUpdateCommitQuiesced(ghost.getGhostId(), java.io.File(ghost.getGhostPath())) { Unit }
        frames.clear()
        runner.addMsgToQueue(arrayOf("\\hnew session talk\\e"))
        runner.run()
        Assert.assertEquals(1, scheduler.pendingCount)

        release.countDown()
        oldRun.join(5_000L)
        Assert.assertFalse(oldRun.isAlive)
        Assert.assertTrue(frames.isEmpty())
        Assert.assertEquals(1, scheduler.pendingCount)
        scheduler.runPending()

        Assert.assertTrue(frames.any { it.sakura.text.contains("new session talk") })
        Assert.assertTrue(frames.none { it.sakura.text.contains("old session talk") })
        Assert.assertFalse(runner.runtimeModeSnapshot().playingTalk)
    }

    @Test
    fun invalidatedRunPreparationCannotScheduleIntoReloadedPlayback() {
        val scheduler = RecordingPlaybackScheduler()
        val prepared = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstPreparation = AtomicBoolean(true)
        val runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            FakeClock(1_000L),
            playbackSchedulerFactory = { scheduler },
            playbackHooks = SScriptPlaybackHooks(
                afterRunPrepared = {
                    if (firstPreparation.compareAndSet(true, false)) {
                        prepared.countDown()
                        Assert.assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                },
            ),
        )
        val frames = mutableListOf<GhostPresentationFrame>()
        runner.setPresentationRendererForTesting(frames::add)
        val ghost = RawRecordingGhost("update", "Update", 2, mutableListOf())
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hold prepared talk\\e"))

        val oldRun = Thread(runner::run)
        oldRun.start()
        Assert.assertTrue(prepared.await(5, TimeUnit.SECONDS))
        runner.withGhostUpdateCommitQuiesced(ghost.getGhostId(), java.io.File(ghost.getGhostPath())) { Unit }
        runner.addMsgToQueue(arrayOf("\\hnew session talk\\e"))
        runner.run()
        Assert.assertEquals(1, scheduler.pendingCount)

        release.countDown()
        oldRun.join(5_000L)
        Assert.assertFalse(oldRun.isAlive)
        Assert.assertEquals(1, scheduler.pendingCount)
        scheduler.runPending()

        Assert.assertTrue(frames.any { it.sakura.text.contains("new session talk") })
        Assert.assertTrue(frames.none { it.sakura.text.contains("old prepared talk") })
        Assert.assertFalse(runner.runtimeModeSnapshot().playingTalk)
    }

    @Test
    fun capturedSurfaceChangeFromInvalidatedPlaybackDoesNotReachReloadedShiori() {
        lateinit var runner: SScriptRunner
        val ghost = RawRecordingGhost("update", "Update", 2, mutableListOf())
        runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            FakeClock(1_000L),
            playbackHooks = SScriptPlaybackHooks(
                afterSurfaceChangeCaptured = {
                    runner.withGhostUpdateCommitQuiesced(
                        ghost.getGhostId(),
                        java.io.File(ghost.getGhostPath()),
                    ) { Unit }
                },
            ),
        )
        runner.setNoWaitMode(true)
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\h\\s[42]\\e"))

        runner.run()

        Assert.assertTrue(ghost.eventRequests.none { it.startsWith("OnSurfaceChange:") })
        Assert.assertEquals(1, ghost.reloadCount)
    }

    @Test
    fun capturedInputPromptFromInvalidatedPlaybackIsNotPublished() {
        lateinit var runner: SScriptRunner
        val ghost = RawRecordingGhost("update", "Update", 2, mutableListOf())
        runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            FakeClock(1_000L),
            playbackHooks = SScriptPlaybackHooks(
                afterInputEffectCaptured = {
                    runner.withGhostUpdateCommitQuiesced(
                        ghost.getGhostId(),
                        java.io.File(ghost.getGhostPath()),
                    ) { Unit }
                },
            ),
        )
        var prompts = 0
        runner.setNoWaitMode(true)
        runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) {
                prompts++
            }

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
        })
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\h\\![open,inputbox,answer]\\e"))

        runner.run()

        Assert.assertEquals(0, prompts)
        Assert.assertEquals(1, ghost.reloadCount)
    }

    @Test
    fun capturedSelectionFromInvalidatedPlaybackIsNotPublished() {
        lateinit var runner: SScriptRunner
        val ghost = RawRecordingGhost("update", "Update", 2, mutableListOf())
        runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            FakeClock(1_000L),
            playbackHooks = SScriptPlaybackHooks(
                afterSelectionEffectCaptured = {
                    runner.withGhostUpdateCommitQuiesced(
                        ghost.getGhostId(),
                        java.io.File(ghost.getGhostPath()),
                    ) { Unit }
                },
            ),
        )
        var selections = 0
        runner.setNoWaitMode(true)
        runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) = Unit

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
                selections++
            }
        })
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\h\\q[One,id1]\\e"))

        runner.run()

        Assert.assertEquals(0, selections)
        Assert.assertEquals(1, ghost.reloadCount)
    }

    @Test
    fun capturedFrameFromInvalidatedPlaybackIsNotRendered() {
        lateinit var runner: SScriptRunner
        val ghost = RawRecordingGhost("update", "Update", 2, mutableListOf())
        runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            FakeClock(1_000L),
            playbackHooks = SScriptPlaybackHooks(
                afterPresentationEffectCaptured = {
                    runner.withGhostUpdateCommitQuiesced(
                        ghost.getGhostId(),
                        java.io.File(ghost.getGhostPath()),
                    ) { Unit }
                },
            ),
        )
        val frames = mutableListOf<GhostPresentationFrame>()
        runner.setNoWaitMode(true)
        runner.setPresentationRendererForTesting(frames::add)
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hold session frame\\e"))

        runner.run()

        Assert.assertTrue(frames.isEmpty())
        Assert.assertEquals(1, ghost.reloadCount)
    }

    @Test
    fun updateInvalidationCompletesPendingGhostChangeOutsideTheMutationGate() {
        val lifecycleDispatcher = RecordingLifecycleDispatcher()
        val runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            FakeClock(1_000L),
            lifecycleDispatcher = lifecycleDispatcher,
        )
        runner.setNoWaitMode(true)
        runner.setUICallback(IgnoreUiCallback)
        val ghost = RawRecordingGhost("update", "Update", 2, mutableListOf())
        val eventEntered = CountDownLatch(1)
        val releaseEvent = CountDownLatch(1)
        ghost.eventRequestHook = { event ->
            if (event == "OnGhostChanging") {
                eventEntered.countDown()
                releaseEvent.await(5, TimeUnit.SECONDS)
            }
        }
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hwaiting\\![open,inputbox,answer]\\w9old tail\\e"))
        runner.run()
        val transition = RecordingLifecycleCallback(runner, ghost)
        runner.setCallback(transition)
        val transitionRequest = Thread {
            runner.doGhostChanging("Next", "manual", "/next")
        }.apply { start() }
        Assert.assertTrue(eventEntered.await(5, TimeUnit.SECONDS))
        val mutationAttempted = CountDownLatch(1)
        val mutationActionEntered = CountDownLatch(1)
        val callbackSeenInsideMutation = AtomicBoolean(false)
        val lifecycleScheduledInsideMutation = AtomicBoolean(false)
        val mutation = Thread {
            mutationAttempted.countDown()
            runner.withGhostUpdateCommitQuiesced(ghost.getGhostId(), java.io.File(ghost.getGhostPath())) {
                callbackSeenInsideMutation.set(transition.events.isNotEmpty())
                lifecycleScheduledInsideMutation.set(lifecycleDispatcher.pendingCount != 0)
                mutationActionEntered.countDown()
            }
        }.apply { start() }
        Assert.assertTrue(mutationAttempted.await(5, TimeUnit.SECONDS))
        Assert.assertFalse(mutationActionEntered.await(100, TimeUnit.MILLISECONDS))

        releaseEvent.countDown()
        transitionRequest.join(5_000L)
        mutation.join(5_000L)

        Assert.assertFalse(transitionRequest.isAlive)
        Assert.assertFalse(mutation.isAlive)
        Assert.assertTrue(mutationActionEntered.await(0, TimeUnit.MILLISECONDS))
        Assert.assertFalse(callbackSeenInsideMutation.get())
        Assert.assertFalse(lifecycleScheduledInsideMutation.get())
        Assert.assertTrue(transition.events.isEmpty())
        Assert.assertEquals(1, lifecycleDispatcher.pendingCount)
        Assert.assertEquals(listOf(mutation), lifecycleDispatcher.dispatchThreads)
        val laterPlayback = RecordingLifecycleCallback(runner, ghost)
        runner.setCallback(laterPlayback)
        runner.addMsgToQueue(arrayOf("\\hnew session talk\\e"))
        runner.run()
        Assert.assertEquals(listOf("stop"), laterPlayback.events)
        lifecycleDispatcher.runPending()
        Assert.assertEquals(listOf("stop", "handoff"), transition.events)
        Assert.assertEquals(listOf(true), transition.coordinatorWasAvailable)
        Assert.assertTrue(transition.callbackThreads.all { it === Thread.currentThread() })
        Assert.assertTrue(transition.callbackThreads.none { it === mutation })
        lifecycleDispatcher.runPending()
        Assert.assertEquals(listOf("stop", "handoff"), transition.events)
    }

    @Test
    fun updateInvalidationCompletesPendingExitOutsideTheMutationGate() {
        val lifecycleDispatcher = RecordingLifecycleDispatcher()
        val runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            FakeClock(1_000L),
            lifecycleDispatcher = lifecycleDispatcher,
        )
        runner.setNoWaitMode(true)
        runner.setUICallback(IgnoreUiCallback)
        val ghost = RawRecordingGhost("update", "Update", 2, mutableListOf())
        val eventEntered = CountDownLatch(1)
        val releaseEvent = CountDownLatch(1)
        ghost.eventRequestHook = { event ->
            if (event == "OnClose") {
                eventEntered.countDown()
                releaseEvent.await(5, TimeUnit.SECONDS)
            }
        }
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hwaiting\\![open,inputbox,answer]\\w9old tail\\e"))
        runner.run()
        val transition = RecordingLifecycleCallback(runner, ghost)
        runner.setCallback(transition)
        val transitionRequest = Thread(runner::doExit).apply { start() }
        Assert.assertTrue(eventEntered.await(5, TimeUnit.SECONDS))
        val mutationAttempted = CountDownLatch(1)
        val mutationActionEntered = CountDownLatch(1)
        val callbackSeenInsideMutation = AtomicBoolean(false)
        val lifecycleScheduledInsideMutation = AtomicBoolean(false)
        val mutation = Thread {
            mutationAttempted.countDown()
            runner.withGhostUpdateCommitQuiesced(ghost.getGhostId(), java.io.File(ghost.getGhostPath())) {
                callbackSeenInsideMutation.set(transition.events.isNotEmpty())
                lifecycleScheduledInsideMutation.set(lifecycleDispatcher.pendingCount != 0)
                mutationActionEntered.countDown()
            }
        }.apply { start() }
        Assert.assertTrue(mutationAttempted.await(5, TimeUnit.SECONDS))
        Assert.assertFalse(mutationActionEntered.await(100, TimeUnit.MILLISECONDS))

        releaseEvent.countDown()
        transitionRequest.join(5_000L)
        mutation.join(5_000L)

        Assert.assertFalse(transitionRequest.isAlive)
        Assert.assertFalse(mutation.isAlive)
        Assert.assertTrue(mutationActionEntered.await(0, TimeUnit.MILLISECONDS))
        Assert.assertFalse(callbackSeenInsideMutation.get())
        Assert.assertFalse(lifecycleScheduledInsideMutation.get())
        Assert.assertTrue(transition.events.isEmpty())
        Assert.assertEquals(1, lifecycleDispatcher.pendingCount)
        Assert.assertEquals(listOf(mutation), lifecycleDispatcher.dispatchThreads)
        val laterPlayback = RecordingLifecycleCallback(runner, ghost)
        runner.setCallback(laterPlayback)
        runner.addMsgToQueue(arrayOf("\\hnew session talk\\e"))
        runner.run()
        Assert.assertEquals(listOf("stop"), laterPlayback.events)
        lifecycleDispatcher.runPending()
        Assert.assertEquals(listOf("stop", "exit"), transition.events)
        Assert.assertEquals(listOf(true), transition.coordinatorWasAvailable)
        Assert.assertTrue(transition.callbackThreads.all { it === Thread.currentThread() })
        Assert.assertTrue(transition.callbackThreads.none { it === mutation })
        lifecycleDispatcher.runPending()
        Assert.assertEquals(listOf("stop", "exit"), transition.events)
    }

    @Test
    fun productionLifecycleDispatcherPostsThroughTheMainLooper() {
        mockkStatic(Looper::class)
        try {
            val mainLooper = mockk<Looper>()
            val handler = mockk<Handler>()
            var selectedLooper: Looper? = null
            every { Looper.getMainLooper() } returns mainLooper
            every { handler.post(any()) } returns true
            val dispatcher = MainLooperSScriptLifecycleDispatcher { looper ->
                selectedLooper = looper
                handler
            }

            dispatcher.dispatch { Unit }

            Assert.assertSame(mainLooper, selectedLooper)
            verify(exactly = 1) { handler.post(any()) }
        } finally {
            unmockkStatic(Looper::class)
        }
    }

    private fun runner(): com.cattailsw.nanidroid.SScriptRunner {
        val runner: com.cattailsw.nanidroid.SScriptRunner =
            com.cattailsw.nanidroid.SScriptRunner(null, GhostSessionCoordinator())
        runners.add(runner)
        return runner
    }

    private class FakeClock(var millis: Long) : MonotonicClock {
        override fun nowMillis(): Long = millis
    }

    private fun talk(value: String): ShioriResponse = ShioriResponse(
        "SHIORI/3.0 200 OK",
        Hashtable<String, String>().apply { put("Value", value) },
    )

    private class RecordingPlaybackScheduler : SScriptPlaybackScheduler {
        private val pending = ArrayDeque<() -> Unit>()
        private val cancelled = ArrayDeque<() -> Unit>()
        val pendingCount: Int get() = pending.size
        val cancelledCount: Int get() = cancelled.size

        override fun schedule(delayMillis: Long, action: () -> Unit) {
            pending.addLast(action)
        }

        override fun cancelPending() {
            while (pending.isNotEmpty()) cancelled.addLast(pending.removeFirst())
        }

        fun runUntil(condition: () -> Boolean) {
            repeat(100) {
                if (condition()) return
                requireNotNull(pending.removeFirstOrNull()).invoke()
            }
            throw AssertionError("playback condition was not reached")
        }

        fun captureNext(): () -> Unit = requireNotNull(pending.firstOrNull())

        fun runNext() {
            requireNotNull(pending.removeFirstOrNull()).invoke()
        }

        fun runPending() {
            repeat(1_000) {
                val action = pending.removeFirstOrNull() ?: return
                action()
            }
            throw AssertionError("playback scheduler did not become idle")
        }

        fun runCancelled() {
            while (cancelled.isNotEmpty()) cancelled.removeFirst().invoke()
        }
    }

    private class RecordingLifecycleDispatcher : SScriptLifecycleDispatcher {
        private val pending = ArrayDeque<() -> Unit>()
        val dispatchThreads = mutableListOf<Thread>()
        val pendingCount: Int get() = synchronized(this) { pending.size }

        override fun dispatch(action: () -> Unit) = synchronized(this) {
            dispatchThreads += Thread.currentThread()
            pending.addLast(action)
        }

        fun runPending() {
            while (true) {
                val action = synchronized(this) { pending.removeFirstOrNull() } ?: return
                action()
            }
        }
    }

    private object IgnoreUiCallback : SScriptRunner.UICallback {
        override fun showUserInputBox(id: String) = Unit
        override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
    }

    private class RecordingLifecycleCallback(
        private val runner: SScriptRunner,
        private val ghost: RawRecordingGhost,
    ) : SScriptRunner.StatusCallback {
        val events = mutableListOf<String>()
        val coordinatorWasAvailable = mutableListOf<Boolean>()
        val callbackThreads = mutableListOf<Thread>()

        override fun stop() {
            callbackThreads += Thread.currentThread()
            events += "stop"
            val acquired = CountDownLatch(1)
            Thread {
                runner.withGhostUpdateQuiesced(ghost.getGhostId()) { acquired.countDown() }
            }.start()
            coordinatorWasAvailable += acquired.await(5, TimeUnit.SECONDS)
        }

        override fun canExit() {
            callbackThreads += Thread.currentThread()
            events += "exit"
        }

        override fun ghostSwitchScriptComplete() {
            callbackThreads += Thread.currentThread()
            events += "handoff"
        }
    }

    private open class RecordingGhost(
        ghostId: String,
        ghostName: String?,
        createCount: Long,
        private val trace: MutableList<String?>
    ) : com.cattailsw.nanidroid.Ghost(
        ghostId
    ) {
        private val fakeGhostId = ghostId
        private val fakeGhostName = ghostName
        private val fakeCreateCount = createCount

        override fun getGhostId(): String = fakeGhostId
        override fun getGhostName(): String? = fakeGhostName
        override fun getCreateCount(): Long = fakeCreateCount

        override fun loadGhostInfo() {
            // The fake owns all metadata needed by this lifecycle trace.
        }

        override fun incrementCreateCount() {
            // Creation counts are fixed test fixtures, not persisted state.
        }

        override fun unload() {
            // Test fake has no native SHIORI session.
        }

        public override fun doShioriEvent(
            event: String,
            references: Array<String>?
        ): com.cattailsw.nanidroid.ShioriResponse {
            trace.add(fakeGhostId + ":" + event + ":" + references.contentToString())
            return com.cattailsw.nanidroid.ShioriResponse("SHIORI/3.0 204 No Content")
        }
    }

    private class RawRecordingGhost(
        ghostId: String,
        ghostName: String?,
        createCount: Long,
        trace: MutableList<String?>,
    ) : RecordingGhost(ghostId, ghostName, createCount, trace) {
        val rawRequests = mutableListOf<String>()
        val rawResponses = ArrayDeque<ShioriResponse>()
        val eventRequests = mutableListOf<String>()
        var eventRequestHook: ((String) -> Unit)? = null

        override fun requestRaw(method: ShioriMethod, eventId: String, references: List<String>): ShioriResponse {
            rawRequests += "$method:$eventId:$references"
            return rawResponses.removeFirstOrNull() ?: ShioriResponse("SHIORI/3.0 204 No Content")
        }

        override fun getSakuraName(): String = "Sakura"
        override fun getKeroName(): String = "Kero"
        override fun pointerEventCapabilities(): PointerEventCapabilities =
            PointerEventCapabilities(click = Support.SUPPORTED)

        override fun doShioriEvent(event: String, ref: Array<String>?): ShioriResponse {
            eventRequests += "$event:${ref.contentToString()}"
            eventRequestHook?.invoke(event)
            return ShioriResponse("SHIORI/3.0 204 No Content")
        }

        var unloadCount = 0
        var reloadCount = 0

        override fun unload() {
            unloadCount++
        }

        internal override fun reloadAfterGhostUpdate() {
            reloadCount++
        }
    }
}
