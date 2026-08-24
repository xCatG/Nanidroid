package com.cattailsw.nanidroid

import org.junit.After
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import com.cattailsw.nanidroid.runtime.MonotonicClock
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
import java.io.File
import java.util.Hashtable
import java.util.concurrent.TimeUnit

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
    fun ordinaryReservedSwitchUnloadsBeforeConstructionAndAdvancesOneGeneration() {
        val trace = mutableListOf<String?>()
        var clockOwner = "initial"
        LegacyPlatform.withTestSeams(
            clock = { 0L },
            delayedScheduler = { delayMillis, _ -> trace += "$clockOwner:clock:$delayMillis" },
            delayedCancellation = {},
        ) {
            val coordinator = GhostSessionCoordinator()
            val runner = SScriptRunner(null, coordinator).also(runners::add)
            val initialRoot = File("ordinary-switch/initial").canonicalFile
            val replacementRoot = File("ordinary-switch/replacement").canonicalFile
            val initialConstruction = coordinator.beginConstruction("initial", initialRoot)
            val initial = RecordingGhost(
                "initial",
                "Initial Ghost",
                2,
                trace,
                ghostPath = initialRoot.path,
                lifecycleTrace = trace,
            )
            val initialReservation = initialConstruction.bind(initial)

            Assert.assertTrue(runner.attachReservedGhost(initialReservation))
            runner.startClock()
            runner.startClock()
            runner.stopClock()
            Assert.assertThrows(IllegalStateException::class.java) {
                coordinator.beginConstruction("replacement", replacementRoot)
            }

            Assert.assertTrue(runner.unloadGhostForSwitchForTesting(initial))
            val replacementConstruction = coordinator.beginConstruction("replacement", replacementRoot)
            val replacement = RecordingGhost(
                "replacement",
                "Replacement Ghost",
                2,
                trace,
                ghostPath = replacementRoot.path,
                lifecycleTrace = trace,
            )
            val replacementReservation = replacementConstruction.bind(replacement)
            Assert.assertEquals(initialReservation.generation + 1L, replacementReservation.generation)
            Assert.assertTrue(runner.attachReservedGhost(replacementReservation))
            clockOwner = "replacement"
            runner.startClock()
            runner.startClock()

            Assert.assertEquals(
                listOf(
                    "initial:constructed",
                    "initial:clock:1000",
                    "initial:OnBoot:[master]",
                    "initial:unload",
                    "replacement:constructed",
                    "replacement:OnGhostChanged:[Initial Ghost, null]",
                    "replacement:clock:1000",
                ),
                trace,
            )
        }
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
    fun timerGetDoesNotPlayWhenInteractionFinishesAfterEligibilityCheck() {
        lateinit var runner: SScriptRunner
        val hooks = SScriptPlaybackHooks(
            beforeTimerResponseAdmission = {
                runner.addMsgToQueue(arrayOf("\\hIntervening\\e"))
                runner.run()
            },
        )
        runner = SScriptRunner(
            null,
            GhostSessionCoordinator(),
            FakeClock(1_000L),
            playbackHooks = hooks,
        )
        runner.setNoWaitMode(true)
        val ghost = RawRecordingGhost("timer", "Timer", 2, mutableListOf()).apply {
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

    private fun runner(): com.cattailsw.nanidroid.SScriptRunner {
        val runner: com.cattailsw.nanidroid.SScriptRunner =
            com.cattailsw.nanidroid.SScriptRunner(null, GhostSessionCoordinator())
        runners.add(runner)
        return runner
    }

    private fun awaitTrace(trace: List<String?>, expectedSize: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (trace.size < expectedSize && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        Assert.assertEquals(expectedSize, trace.size)
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

    private open class RecordingGhost(
        ghostId: String,
        ghostName: String?,
        createCount: Long,
        private val trace: MutableList<String?>,
        ghostPath: String = ghostId,
        private val lifecycleTrace: MutableList<String?>? = null,
    ) : Ghost(ghostPath) {
        private val fakeGhostId = ghostId
        private val fakeGhostName = ghostName
        private val fakeCreateCount = createCount

        init {
            lifecycleTrace?.add("$ghostId:constructed")
        }

        override fun getGhostId(): String = fakeGhostId
        override fun getGhostName(): String? = fakeGhostName
        override fun getCreateCount(): Long = fakeCreateCount

        override fun loadGhostInfo() = Unit

        override fun incrementCreateCount() = Unit

        override fun unload() {
            lifecycleTrace?.add("$fakeGhostId:unload")
        }

        override fun doShioriEvent(event: String, ref: Array<String>?): ShioriResponse {
            trace += "$fakeGhostId:$event:${ref.contentToString()}"
            return ShioriResponse("SHIORI/3.0 204 No Content")
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
        var rawResponseHook: (() -> Unit)? = null
        val eventRequests = mutableListOf<String>()
        var eventRequestHook: ((String) -> Unit)? = null

        override fun requestRaw(method: ShioriMethod, eventId: String, references: List<String>): ShioriResponse {
            rawRequests += "$method:$eventId:$references"
            rawResponseHook?.invoke()
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

    }
}
