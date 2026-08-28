package com.cattailsw.nanidroid

import androidx.compose.ui.unit.IntOffset
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.MonotonicClock
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.shiori.Shiori
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

/** Characterizes boot delivery, clock requests, and passive dialogue through [GhostRuntime]. */
class SScriptRunnerBootDispatchTest {
    @Rule @JvmField val androidStubs = HostAndroidStubRule()
    @Rule @JvmField val runtimes = RuntimeFixtureRegistry()

    @Test
    fun attachmentSelectsExactlyOneFirstBootGhostChangedOrBootEvent() {
        val firstActivation = runtimes.create(
            id = "first",
            persistence = InMemoryGhostRuntimePersistence(),
            bootstrapResponse = { noContent() },
            autoAttach = false,
        )
        firstActivation.trace.requests.clear()
        attach(firstActivation, firstActivation.requireHandle())

        val switchedReturn = runtimes.create(
            id = "outgoing",
            persistence = InMemoryGhostRuntimePersistence().apply {
                activationCounts["outgoing"] = 1L
                activationCounts["replacement"] = 1L
            },
            bootstrapResponse = { noContent() },
            preparedFactory = ::namedPrepared,
            autoAttach = false,
        )
        switchedReturn.trace.requests.clear()
        attach(switchedReturn, switchedReturn.requireHandle())
        val outgoing = switchedReturn.requireHandle()
        val replacementRoot = File(switchedReturn.root.parentFile, "replacement")
        val switchOperation = assertIs<RuntimeResult.Success<Long>>(
            switchedReturn.runtime.beginSwitch(outgoing.generation, "replacement", replacementRoot),
        ).value
        Assert.assertTrue(
            switchedReturn.runner.doGhostChanging(
                switchOperation,
                "replacement Sakura",
                "manual",
                replacementRoot.path,
            ),
        )
        val replacement = runBlocking {
            assertIs<RuntimeResult.Success<GhostHandle>>(
                switchedReturn.runtime.startOrJoin("replacement", replacementRoot),
            ).value
        }
        switchedReturn.trace.requests.clear()
        attach(switchedReturn, replacement)

        val ordinaryReturn = runtimes.create(
            id = "ordinary",
            persistence = InMemoryGhostRuntimePersistence().apply { activationCounts["ordinary"] = 1L },
            bootstrapResponse = { noContent() },
            autoAttach = false,
        )
        ordinaryReturn.trace.requests.clear()
        attach(ordinaryReturn, ordinaryReturn.requireHandle())

        Assert.assertEquals(listOf("OnFirstBoot"), requestIds(firstActivation))
        Assert.assertEquals(listOf("OnGhostChanged"), requestIds(switchedReturn))
        Assert.assertEquals(listOf("OnBoot"), requestIds(ordinaryReturn))
    }

    @Test
    fun blockedTimerResponseCannotEnterAfterClockEpochChanges() {
        lateinit var adapter: BlockingRuntimeAdapter
        val fixture = runtimes.create(
            id = "blocked-timer",
            response = { "SHIORI/3.0 200 OK\r\nValue: \\hstale timer\\e\r\n\r\n" },
            adapterDecorator = { delegate ->
                val blocking = BlockingRuntimeAdapter(delegate)
                adapter = blocking
                object : Shiori by delegate {
                    override fun request(request: String): String =
                        if (requestId(request) == "OnSecondChange") blocking.request(request) else delegate.request(request)
                }
            },
            runnerConfiguration = SScriptRunnerConfiguration(
                monotonicClock = MonotonicClock { 1_000L },
            ),
        )
        val runner = fixture.runner.apply { setNoWaitMode(true) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            runner.startClock()
            val clockTick = executor.submit<Unit> { runner.dispatchClockTickForTesting() }
            Assert.assertTrue(adapter.entered.await(5, TimeUnit.SECONDS))

            runner.stopClock()
            adapter.release.countDown()
            clockTick.get(5, TimeUnit.SECONDS)

            Assert.assertTrue(runner.dialogueStateSnapshot().contents.isEmpty())
            Assert.assertEquals(listOf("OnSecondChange"), requestIds(fixture))
        } finally {
            adapter.release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun dispatchesBootOnceAcrossDuplicateStartResumeAndNamedGhostHandoff() {
        val persistence = InMemoryGhostRuntimePersistence().apply {
            activationCounts["initial"] = 1L
            activationCounts["replacement"] = 1L
        }
        val fixture = runtimes.create(
            id = "initial",
            root = File("build/runtime-fixtures/boot/initial"),
            persistence = persistence,
            bootstrapResponse = { noContent() },
            preparedFactory = ::namedPrepared,
            autoAttach = false,
        )
        fixture.trace.requests.clear()
        attach(fixture, fixture.requireHandle())
        fixture.runner.startClock()
        fixture.runner.startClock()
        fixture.runner.stopClock()
        fixture.runner.startClock()
        fixture.runner.stopClock()

        val targetRoot = File(fixture.root.parentFile, "replacement")
        val replacement = switchAndAttach(fixture, "replacement")
        fixture.runner.startClock()

        Assert.assertEquals(
            listOf(
                "OnBoot:[master]",
                "OnGhostChanging:[replacement Sakura, manual, null, ${targetRoot.path}]",
                "OnGhostChanged:[Initial Ghost, null]",
            ),
            fixture.trace.requests.mapNotNull(::requestSignature)
                .filter { signature ->
                    signature.substringBefore(':') in
                        setOf("OnBoot", "OnGhostChanging", "OnGhostChanged", "OnFirstBoot")
                },
        )
        Assert.assertEquals(fixture.requireHandle().generation + 1L, replacement.generation)
    }

    @Test
    fun ordinaryReservedSwitchUnloadsBeforeConstructionAndAdvancesOneGeneration() {
        val order = mutableListOf<String>()
        val persistence = InMemoryGhostRuntimePersistence().apply {
            activationCounts["initial"] = 1L
            activationCounts["replacement"] = 1L
        }
        val fixture = runtimes.create(
            id = "initial",
            root = File("build/runtime-fixtures/order/initial"),
            persistence = persistence,
            bootstrapResponse = { request ->
                requestId(request)
                    ?.takeIf { it in setOf("OnBoot", "OnGhostChanging", "OnGhostChanged") }
                    ?.let { order += "request:$it:${requestReferences(request)}" }
                noContent()
            },
            preparedFactory = ::namedPrepared,
            autoStart = false,
        )
        fixture.runtime.installTestHooksForTesting(
            GhostRuntimeTestHooks(
                onPreparationStarted = { _, id, _ -> order += "$id:prepare" },
                onGenerationPublished = { generation, id -> order += "$id:publish:$generation" },
                onOutgoingUnloaded = { order += "initial:unload" },
            ),
        ).use {
            val initial = runBlocking {
                assertIs<RuntimeResult.Success<GhostHandle>>(
                    fixture.runtime.startOrJoin("initial", fixture.root),
                ).value
            }
            fixture.trace.requests.clear()
            attach(fixture, initial)
            val replacement = switchAndAttach(fixture, "replacement")

            Assert.assertEquals(initial.generation + 1L, replacement.generation)
            Assert.assertEquals(
                listOf(
                    "initial:prepare",
                    "initial:publish:${initial.generation}",
                    "request:OnBoot:[master]",
                    "request:OnGhostChanging:[replacement Sakura, manual, null, ${File(fixture.root.parentFile, "replacement").path}]",
                    "initial:unload",
                    "replacement:prepare",
                    "replacement:publish:${replacement.generation}",
                    "request:OnGhostChanged:[Initial Ghost, null]",
                ),
                order,
            )
            Assert.assertEquals(1, fixture.trace.unloadCount.get())
        }
    }

    @Test
    fun newlyConstructedRunnerDispatchesBootOnceAfterAppRecreation() {
        val persistence = InMemoryGhostRuntimePersistence().apply { activationCounts["recreated"] = 1L }
        val fixture = runtimes.create(
            id = "recreated",
            persistence = persistence,
            bootstrapResponse = { noContent() },
            autoAttach = false,
        )
        fixture.trace.requests.clear()

        attach(fixture, fixture.requireHandle())
        fixture.runner.startClock()
        fixture.runner.startClock()

        Assert.assertEquals(
            listOf("OnBoot:[master]"),
            fixture.trace.requests.mapNotNull(::requestSignature),
        )
    }

    @Test
    fun firstActivationReplacementSendsFirstBootWithoutAdditionalBoot() {
        val persistence = InMemoryGhostRuntimePersistence().apply { activationCounts["initial"] = 1L }
        val fixture = runtimes.create(
            id = "initial",
            persistence = persistence,
            bootstrapResponse = { noContent() },
            preparedFactory = ::namedPrepared,
            autoAttach = false,
        )
        fixture.trace.requests.clear()
        attach(fixture, fixture.requireHandle())

        val targetRoot = File(fixture.root.parentFile, "replacement")
        switchAndAttach(fixture, "replacement")

        Assert.assertEquals(
            listOf(
                "OnBoot:[master]",
                "OnGhostChanging:[replacement Sakura, manual, null, ${targetRoot.path}]",
                "OnFirstBoot:[0]",
            ),
            fixture.trace.requests.mapNotNull(::requestSignature)
                .filter { signature ->
                    signature.substringBefore(':') in
                        setOf("OnBoot", "OnGhostChanging", "OnGhostChanged", "OnFirstBoot")
                },
        )
    }

    @Test
    fun timerUsesSleepInclusiveClockHoursAndPlaysOnlyIdleGetResponses() {
        val harness = harness(FakeClock(7 * 3_600_000L + 1_000L))
        harness.runner.setNoWaitMode(true)
        harness.responses += talk("\\hawake\\e")

        harness.runner.dispatchClockTickForTesting()
        harness.runner.dispatchClockTickForTesting()

        Assert.assertEquals(listOf("GET:OnSecondChange:[7, 0, 0, 1]"), harness.rawRequests)
        Assert.assertTrue(harness.runner.dialogueStateSnapshot().contents.any { it.segments.toString().contains("awake") })
    }

    @Test
    fun timerDispatchesChangedElapsedBucketsAfterADelayedJump() {
        val clock = FakeClock(59_000L)
        val harness = harness(clock)
        harness.runner.setNoWaitMode(true)

        harness.runner.dispatchClockTickForTesting()
        clock.millis = 61_000L
        harness.runner.dispatchClockTickForTesting()

        Assert.assertEquals(
            listOf(
                "GET:OnSecondChange:[0, 0, 0, 1]",
                "GET:OnSecondChange:[0, 0, 0, 1]",
                "GET:OnMinuteChange:[0, 0, 0, 1]",
            ),
            harness.rawRequests,
        )
    }

    @Test
    fun timerDispatchesEachObservedSecondAndMinuteBoundaryOnce() {
        val clock = FakeClock(1_000L)
        val harness = harness(clock)
        harness.runner.setNoWaitMode(true)

        harness.runner.dispatchClockTickForTesting()
        clock.millis = 2_000L
        harness.runner.dispatchClockTickForTesting()
        clock.millis = 60_000L
        harness.runner.dispatchClockTickForTesting()

        Assert.assertEquals(
            listOf(
                "GET:OnSecondChange:[0, 0, 0, 1]",
                "GET:OnSecondChange:[0, 0, 0, 1]",
                "GET:OnSecondChange:[0, 0, 0, 1]",
                "GET:OnMinuteChange:[0, 0, 0, 1]",
            ),
            harness.rawRequests,
        )
    }

    @Test
    fun timerGetDoesNotPlayAfterAnInterveningTalkReturnsIdle() {
        val harness = harness(FakeClock(1_000L))
        harness.runner.setNoWaitMode(true)
        harness.rawResponseHook = {
            harness.runner.addMsgToQueue(arrayOf("\\hIntervening\\e"))
            harness.runner.run()
        }
        harness.responses += talk("\\hStaleTimer\\e")

        harness.runner.dispatchClockTickForTesting()

        assertSakuraText(harness.runner, "Intervening")
    }

    @Test
    fun timerGetFromStoppedClockDoesNotPlayAfterClockRestarts() {
        lateinit var harness: Harness
        val hooks = SScriptPlaybackHooks(
            beforeTimerResponseAdmission = {
                harness.runner.stopClock()
                harness.runner.startClock()
            },
        )
        harness = harness(FakeClock(1_000L), playbackHooks = hooks)
        harness.runner.setNoWaitMode(true)
        harness.runner.startClock()
        harness.responses += talk("\\hStaleTimer\\e")

        harness.runner.dispatchClockTickForTesting()

        Assert.assertTrue(harness.runner.dialogueStateSnapshot().contents.isEmpty())
        harness.runner.stopClock()
    }

    @Test
    fun timerGetDoesNotPlayWhenInteractionFinishesAfterEligibilityCheck() {
        lateinit var harness: Harness
        val hooks = SScriptPlaybackHooks(
            beforeTimerResponseAdmission = {
                harness.runner.addMsgToQueue(arrayOf("\\hIntervening\\e"))
                harness.runner.run()
            },
        )
        harness = harness(FakeClock(1_000L), playbackHooks = hooks)
        harness.runner.setNoWaitMode(true)
        harness.responses += talk("\\hStaleTimer\\e")

        harness.runner.dispatchClockTickForTesting()

        assertSakuraText(harness.runner, "Intervening")
    }

    @Test
    fun timerGetPlaysWhenIdleEligibilityNeverChanges() {
        val harness = harness(FakeClock(1_000L))
        harness.runner.setNoWaitMode(true)
        harness.responses += talk("\\hTimer\\e")

        harness.runner.dispatchClockTickForTesting()

        assertSakuraText(harness.runner, "Timer")
    }

    @Test
    fun timerGetStillDropsWhenInterveningTalkRemainsActive() {
        val scheduler = RecordingPlaybackScheduler()
        val harness = harness(FakeClock(1_000L), scheduler)
        harness.rawResponseHook = {
            harness.runner.addMsgToQueue(arrayOf("\\hIntervening\\e"))
            harness.runner.run()
        }
        harness.responses += talk("\\hStaleTimer\\e")

        harness.runner.dispatchClockTickForTesting()
        scheduler.runPending()

        assertSakuraText(harness.runner, "Intervening")
    }

    @Test
    fun passiveTimerSendsNotifyAndDoesNotReplaceItsDialogueOrPendingActions() {
        val harness = harness(FakeClock(3_600_000L + 1_000L))
        harness.runner.setNoWaitMode(true)
        harness.responses += talk("\\hignored\\e")
        harness.runner.addMsgToQueue(arrayOf("\\hbefore\\q[Keep,keep]\\![enter,passivemode]\\e"))
        harness.runner.run()
        val before = harness.runner.dialogueStateSnapshot()

        harness.runner.dispatchClockTickForTesting()

        Assert.assertEquals(listOf("NOTIFY:OnSecondChange:[1, 0, 0, 0]"), harness.rawRequests)
        Assert.assertEquals(before.contents, harness.runner.dialogueStateSnapshot().contents)
        Assert.assertEquals(before.pendingChoices, harness.runner.dialogueStateSnapshot().pendingChoices)
    }

    @Test
    fun passiveSurfaceTapIsIgnoredBeforeItCanClearKeroDialogue() {
        val harness = harness(FakeClock(1_000L))
        val runner = harness.runner
        runner.setNoWaitMode(true)
        runner.addMsgToQueue(arrayOf("\\hbefore\\q[Keep,keep]\\![enter,passivemode]\\e"))
        runner.run()
        val before = runner.dialogueStateSnapshot()

        Assert.assertTrue(runner.dispatchSurfaceInteraction(pointerEffect(SurfaceSpeaker.KERO)))

        Assert.assertEquals(listOf("GET:OnMouseClick:[0, 0, 0, 1, , 0, touch]"), harness.rawRequests)
        Assert.assertEquals(before, runner.dialogueStateSnapshot())
    }

    @Test
    fun ordinaryPendingChoiceStillAcceptsAndPlaysAPointerResponse() {
        val harness = harness(FakeClock(1_000L))
        val runner = harness.runner
        runner.setNoWaitMode(true)
        harness.responses += talk("\\hpointer reply\\e")
        runner.addMsgToQueue(arrayOf("\\hchoice\\q[Choose,choice]\\e"))
        runner.run()

        Assert.assertTrue(runner.dispatchSurfaceInteraction(pointerEffect(SurfaceSpeaker.SAKURA)))

        assertSakuraText(runner, "pointer reply")
    }

    @Test
    fun passiveCommandsAreIdempotentAndUnloadClearsOnlyTheMode() {
        val harness = harness(FakeClock(1_000L))
        val runner = harness.runner
        runner.setNoWaitMode(true)
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
        assertIs<RuntimeResult.Success<Unit>>(
            harness.fixture.runtime.unload(harness.fixture.requireHandle().generation),
        )
        Assert.assertFalse(runner.runtimeModeSnapshot().passive)
        Assert.assertEquals(shown.pendingChoices, runner.dialogueStateSnapshot().pendingChoices)
    }

    @Test
    fun speakerChangeBeforePassiveOnlyCommandPreservesExistingDialogueAndChoices() {
        val runner = harness(FakeClock(1_000L)).runner
        runner.setNoWaitMode(true)
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
        val runner = harness(FakeClock(1_000L)).runner
        runner.setNoWaitMode(true)
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
        val runner = harness(FakeClock(1_000L), scheduler).runner
        var inputShown = false
        val frames = mutableListOf<GhostPresentationFrame>()
        runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) { inputShown = true }
            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
        })
        runner.setPresentationRendererForTesting(frames::add)
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

    private fun harness(
        clock: FakeClock,
        scheduler: RecordingPlaybackScheduler? = null,
        playbackHooks: SScriptPlaybackHooks = SScriptPlaybackHooks(),
    ): Harness {
        val rawRequests = mutableListOf<String>()
        val responses = ArrayDeque<String>()
        val hook = ResponseHook()
        val fixture = runtimes.create(
            response = { request ->
                val id = requestId(request).orEmpty()
                if (id.startsWith("OnSecond") || id.startsWith("OnMinute") || id.startsWith("OnMouse")) {
                    rawRequests += "${request.substringBefore(' ')}:$id:${requestReferences(request)}"
                    hook.action?.invoke()
                }
                responses.removeFirstOrNull() ?: noContent()
            },
            bootstrapResponse = { supportedClick() },
            runnerConfiguration = SScriptRunnerConfiguration(
                monotonicClock = clock,
                playbackSchedulerFactory = scheduler?.let { { it } }
                    ?: SScriptRunnerConfiguration().playbackSchedulerFactory,
                playbackHooks = playbackHooks,
            ),
            preparedFactory = ::namedPrepared,
        )
        return Harness(fixture, rawRequests, responses, hook)
    }

    private fun attach(fixture: RuntimeFixture, handle: GhostHandle) = runBlocking {
        assertIs<RuntimeResult.Success<AttachmentReceipt>>(
            fixture.runtime.attachHost(handle.generation),
        )
    }

    private fun switchAndAttach(fixture: RuntimeFixture, targetId: String): GhostHandle {
        val outgoing = requireNotNull(fixture.runtime.identity().activeHandle)
        val targetRoot = File(fixture.root.parentFile, targetId)
        val operationId = assertIs<RuntimeResult.Success<Long>>(
            fixture.runtime.beginSwitch(outgoing.generation, targetId, targetRoot),
        ).value
        Assert.assertTrue(
            fixture.runner.doGhostChanging(
                operationId,
                "$targetId Sakura",
                "manual",
                targetRoot.path,
            ),
        )
        val replacement = runBlocking {
            assertIs<RuntimeResult.Success<GhostHandle>>(
                fixture.runtime.startOrJoin(targetId, targetRoot),
            ).value
        }
        attach(fixture, replacement)
        return replacement
    }

    private fun assertSakuraText(runner: SScriptRunner, value: String) {
        Assert.assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text(value)))),
            runner.dialogueStateSnapshot().contents,
        )
    }

    private fun requestIds(fixture: RuntimeFixture): List<String> = fixture.trace.requests
        .mapNotNull(::requestId)

    private class FakeClock(var millis: Long) : MonotonicClock {
        override fun nowMillis(): Long = millis
    }

    private data class Harness(
        val fixture: RuntimeFixture,
        val rawRequests: MutableList<String>,
        val responses: ArrayDeque<String>,
        val hook: ResponseHook,
    ) {
        val runner get() = fixture.runner
        var rawResponseHook: (() -> Unit)?
            get() = hook.action
            set(value) { hook.action = value }
    }

    private class ResponseHook(var action: (() -> Unit)? = null)

    private class RecordingPlaybackScheduler : SScriptPlaybackScheduler {
        private val pending = ArrayDeque<() -> Unit>()
        private val cancelled = ArrayDeque<() -> Unit>()
        val pendingCount: Int get() = pending.size

        override fun schedule(delayMillis: Long, action: () -> Unit) { pending.addLast(action) }
        override fun cancelPending() { while (pending.isNotEmpty()) cancelled.addLast(pending.removeFirst()) }

        fun runUntil(condition: () -> Boolean) {
            repeat(100) {
                if (condition()) return
                requireNotNull(pending.removeFirstOrNull()).invoke()
            }
            throw AssertionError("playback condition was not reached")
        }

        fun runPending() {
            repeat(1_000) {
                val action = pending.removeFirstOrNull() ?: return
                action()
            }
            throw AssertionError("playback scheduler did not become idle")
        }
    }

    private companion object {
        fun namedPrepared(operationId: Long, ghostId: String, root: File) = preparedGhost(
            operationId,
            ghostId,
            root,
            name = if (ghostId == "initial") "Initial Ghost" else "Replacement Ghost",
            sakuraName = "$ghostId Sakura",
            keroName = "$ghostId Kero",
        )

        fun noContent() = "SHIORI/3.0 204 No Content\r\n\r\n"
        fun supportedClick() =
            "SHIORI/3.0 204 No Content\r\nX-SSTP-PassThru-local: OnMouseClick\r\n\r\n"
        fun talk(value: String) = "SHIORI/3.0 200 OK\r\nValue: $value\r\n\r\n"

        fun requestId(request: String): String? = request.lineSequence()
            .firstOrNull { it.startsWith("ID: ") }
            ?.removePrefix("ID: ")

        fun requestSignature(request: String): String? = requestId(request)?.let { id ->
            "$id:${requestReferences(request)}"
        }

        fun requestReferences(request: String): List<String> = request.lineSequence()
            .filter { it.startsWith("Reference") }
            .map { it.substringAfter(": ") }
            .toList()

        fun pointerEffect(speaker: SurfaceSpeaker) = SurfaceInteractionEffect(
            PointerEventKind.CLICK,
            speaker,
            IntOffset.Zero,
            0,
            PointerSource.TOUCH,
            null,
            null,
        )
    }
}
