package com.cattailsw.nanidroid

import java.io.File
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

/** Characterizes deterministic runtime-owned ghost handoff without filesystem discovery. */
class GhostSwitchingCharacterizationTest {
    @Rule @JvmField val androidStubs = HostAndroidStubRule()
    @Rule @JvmField val runtimes = RuntimeFixtureRegistry()

    @Test
    fun switchPlaybackOwnsOutgoingResponseBeforeUnload() {
        val trace = Trace()
        val fixture = fixture(trace)
        val outgoing = fixture.requireHandle()
        val targetRoot = File("build/runtime-fixtures/switching/owned-response")
        val operationId = assertIs<RuntimeResult.Success<Long>>(
            fixture.runtime.beginSwitch(outgoing.generation, "owned-response", targetRoot),
        ).value

        fixture.runtime.installTestHooksForTesting(
            GhostRuntimeTestHooks(onOutgoingUnloaded = { trace.add("unload") }),
        ).use {
            Assert.assertTrue(
                fixture.runner.doGhostChanging(
                    operationId,
                    "Owned Response",
                    "manual",
                    targetRoot.path,
                ),
            )
            trace.awaitSize(3)
        }

        Assert.assertEquals(
            listOf(
                "request:outgoing:OnGhostChanging:[Owned Response, manual, null, ${targetRoot.path}]",
                "render:Switching",
                "unload",
            ),
            trace.events(),
        )
    }

    @Test
    fun requiredMigrationInvariant_outgoingScriptRendersBeforeSingleHandoffCallback() {
        val trace = Trace()
        val fixture = fixture(trace)
        val outgoing = fixture.requireHandle()
        val targetRoot = File("build/runtime-fixtures/switching/next")
        val operationId = assertIs<RuntimeResult.Success<Long>>(
            fixture.runtime.beginSwitch(outgoing.generation, "next", targetRoot),
        ).value
        fixture.runtime.installTestHooksForTesting(
            GhostRuntimeTestHooks(onOutgoingUnloaded = { trace.add("handoff") }),
        ).use {
            Assert.assertTrue(
                fixture.runner.doGhostChanging(
                    operationId,
                    "Next Sakura",
                    "manual",
                    "/ghosts/next",
                ),
            )
            trace.awaitSize(3)
        }

        Assert.assertEquals(
            Arrays.asList<String?>(
                "request:outgoing:OnGhostChanging:[Next Sakura, manual, null, /ghosts/next]",
                "render:Switching",
                "handoff",
            ),
            trace.events(),
        )
    }

    @Test
    fun requiredMigrationInvariant_returningReplacementReceivesChangedFromOutgoingName() {
        val trace = Trace()
        val persistence = InMemoryGhostRuntimePersistence().apply {
            activationCounts["replacement"] = 1L
        }
        val fixture = fixture(trace, persistence)
        val outgoing = fixture.requireHandle()
        val targetRoot = File("build/runtime-fixtures/switching/replacement")
        val operationId = assertIs<RuntimeResult.Success<Long>>(
            fixture.runtime.beginSwitch(outgoing.generation, "replacement", targetRoot),
        ).value
        fixture.runtime.installTestHooksForTesting(
            GhostRuntimeTestHooks(onOutgoingUnloaded = { trace.add("handoff") }),
        ).use {
            Assert.assertTrue(
                fixture.runner.doGhostChanging(
                    operationId,
                    "Next Sakura",
                    "manual",
                    "/ghosts/next",
                ),
            )
            val replacement = runBlocking {
                assertIs<RuntimeResult.Success<GhostHandle>>(
                    fixture.runtime.startOrJoin("replacement", targetRoot),
                ).value
            }
            runBlocking {
                assertIs<RuntimeResult.Success<AttachmentReceipt>>(
                    fixture.runtime.attachHost(replacement.generation),
                )
            }
        }

        Assert.assertEquals(
            Arrays.asList<String?>(
                "request:outgoing:OnGhostChanging:[Next Sakura, manual, null, /ghosts/next]",
                "render:Switching",
                "handoff",
                "request:replacement:OnGhostChanged:[Old Ghost Metadata, null]",
            ),
            trace.events(),
        )
    }

    @Test
    fun fatalChangingRequestTerminalizesAnAlreadyJoinedRuntimeSwitch() = runBlocking {
        val failure = com.cattailsw.nanidroid.shiori.ShioriRequestException(
            "outgoing ownership became uncertain",
            ownershipCertain = false,
        )
        val fixture = runtimes.create(
            id = "fatal-outgoing",
            root = File("build/runtime-fixtures/switching/fatal-outgoing"),
            response = { request ->
                if (requestId(request) == "OnGhostChanging") throw failure
                "SHIORI/3.0 204 No Content\r\n\r\n"
            },
        )
        val outgoing = fixture.requireHandle()
        val targetRoot = File("build/runtime-fixtures/switching/fatal-target")
        val operationId = assertIs<RuntimeResult.Success<Long>>(
            fixture.runtime.beginSwitch(outgoing.generation, "fatal-target", targetRoot),
        ).value
        val joined = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.runtime.startOrJoin("fatal-target", targetRoot)
        }

        Assert.assertFalse(
            fixture.runner.doGhostChanging(
                operationId,
                "Fatal Target",
                "manual",
                targetRoot.path,
            ),
        )

        val terminal = withTimeout(1_000L) { joined.await() }
        Assert.assertSame(
            failure,
            assertIs<RuntimeFailure.Fatal>(
                assertIs<RuntimeResult.Failure>(terminal).failure,
            ).cause,
        )
    }

    @Test
    fun poisonedAuthoredPlaybackTerminalizesItsAlreadyJoinedSwitchExactlyOnce() = runBlocking {
        val failure = com.cattailsw.nanidroid.shiori.ShioriRequestException(
            "inline surface request lost native ownership",
            ownershipCertain = false,
        )
        val scheduler = RecordingPlaybackScheduler()
        val fixture = runtimes.create(
            id = "poison-playback-outgoing",
            root = File("build/runtime-fixtures/switching/poison-playback-outgoing"),
            response = { request ->
                when (requestId(request)) {
                    "OnGhostChanging" -> {
                        "SHIORI/3.0 200 OK\r\n" +
                            "Value: \\s[120]\\![open,inputbox,transition]\\e\r\n\r\n"
                    }
                    "OnSurfaceChange" -> throw failure
                    else -> "SHIORI/3.0 204 No Content\r\n\r\n"
                }
            },
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackSchedulerFactory = { scheduler },
            ),
        )
        fixture.runner.setNoWaitMode(true)
        var inputShown = false
        fixture.runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) {
                inputShown = id == "transition"
            }

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
        })
        val outgoing = fixture.requireHandle()
        val targetRoot = File("build/runtime-fixtures/switching/poison-playback-target")
        val operationId = assertIs<RuntimeResult.Success<Long>>(
            fixture.runtime.beginSwitch(
                outgoing.generation,
                "poison-playback-target",
                targetRoot,
            ),
        ).value
        val joinedCompletionCount = AtomicInteger()
        val joined = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.runtime.startOrJoin("poison-playback-target", targetRoot)
        }.also { deferred ->
            deferred.invokeOnCompletion { joinedCompletionCount.incrementAndGet() }
        }

        Assert.assertTrue(
            fixture.runner.doGhostChanging(
                operationId,
                "Poison Playback Target",
                "manual",
                targetRoot.path,
            ),
        )
        Assert.assertTrue(inputShown)
        Assert.assertEquals(GhostRuntimePhase.Poisoned, fixture.runtime.identity().phase)

        fixture.runner.resumeEvt()
        scheduler.runNext()

        val sharedTerminal = withTimeout(1_000L) { joined.await() }
        val directTerminal = fixture.runtime.completeSwitchPlayback(
            outgoing.generation,
            operationId,
        )
        Assert.assertSame(
            failure,
            assertIs<RuntimeFailure.Fatal>(
                assertIs<RuntimeResult.Failure>(sharedTerminal).failure,
            ).cause,
        )
        Assert.assertSame(
            failure,
            assertIs<RuntimeFailure.Fatal>(
                assertIs<RuntimeResult.Failure>(directTerminal).failure,
            ).cause,
        )
        Assert.assertEquals(1, joinedCompletionCount.get())
        Assert.assertEquals(1, fixture.trace.loadCount.get())
        Assert.assertEquals(0, fixture.trace.unloadCount.get())
    }

    @Test
    fun authoredSwitchStaysInteractiveUntilPlaybackCompletionNotifiesTheHost() = runBlocking {
        val scheduler = RecordingPlaybackScheduler()
        val fixture = runtimes.create(
            id = "interactive-outgoing",
            root = File("build/runtime-fixtures/switching/interactive-outgoing"),
            response = { request ->
                if (requestId(request) == "OnGhostChanging") {
                    "SHIORI/3.0 200 OK\r\n" +
                        "Value: \\![open,inputbox,transition]\\e\r\n\r\n"
                } else {
                    "SHIORI/3.0 204 No Content\r\n\r\n"
                }
            },
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackSchedulerFactory = { scheduler },
            ),
        )
        fixture.runner.setNoWaitMode(true)
        var inputShown = false
        fixture.runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) {
                inputShown = id == "transition"
            }

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
        })
        val status = RecordingStatusCallback()
        fixture.runner.setCallback(status)
        val outgoing = fixture.requireHandle()
        val targetRoot = File("build/runtime-fixtures/switching/interactive-target")
        val operationId = assertIs<RuntimeResult.Success<Long>>(
            fixture.runtime.beginSwitch(outgoing.generation, "interactive-target", targetRoot),
        ).value
        val joined = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.runtime.startOrJoin("interactive-target", targetRoot)
        }

        Assert.assertTrue(
            fixture.runner.doGhostChanging(
                operationId,
                "Interactive Target",
                "manual",
                targetRoot.path,
            ),
        )
        Assert.assertTrue(inputShown)
        Assert.assertEquals(GhostRuntimePhase.SwitchPlayback, fixture.runtime.identity().phase)
        Assert.assertEquals(0, status.switchPlaybackCompletions.get())

        val pendingInput = requireNotNull(fixture.runner.dialogueStateSnapshot().pendingInput)
        DialogueDialogBinding { fixture.runner }
            .userInput(pendingInput)
            .onSubmit("transition", "continue")
        scheduler.runNext()

        Assert.assertEquals(1, status.switchPlaybackCompletions.get())
        assertIs<RuntimeResult.Success<GhostHandle>>(withTimeout(1_000L) { joined.await() })
        Unit
    }

    @Test
    fun noScriptSwitchNotifiesHostToEnterReplacementProgress() = runBlocking {
        val fixture = runtimes.create(
            id = "no-script-outgoing",
            root = File("build/runtime-fixtures/switching/no-script-outgoing"),
        )
        val status = RecordingStatusCallback()
        fixture.runner.setCallback(status)
        val outgoing = fixture.requireHandle()
        val targetRoot = File("build/runtime-fixtures/switching/no-script-target")
        val operationId = assertIs<RuntimeResult.Success<Long>>(
            fixture.runtime.beginSwitch(outgoing.generation, "no-script-target", targetRoot),
        ).value
        val joined = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.runtime.startOrJoin("no-script-target", targetRoot)
        }

        Assert.assertTrue(
            fixture.runner.doGhostChanging(
                operationId,
                "No Script Target",
                "manual",
                targetRoot.path,
            ),
        )

        Assert.assertEquals(1, status.switchPlaybackCompletions.get())
        assertIs<RuntimeResult.Success<GhostHandle>>(withTimeout(1_000L) { joined.await() })
        Unit
    }

    private fun fixture(
        trace: Trace,
        persistence: InMemoryGhostRuntimePersistence = InMemoryGhostRuntimePersistence(),
    ): RuntimeFixture = runtimes.create(
        id = "outgoing",
        root = File("build/runtime-fixtures/switching/outgoing"),
        persistence = persistence,
        response = { request ->
            val id = requestId(request)
            if (id == "OnGhostChanging") {
                "SHIORI/3.0 200 OK\r\nValue: $TRANSITION_SCRIPT\r\n\r\n"
            } else {
                "SHIORI/3.0 204 No Content\r\n\r\n"
            }
        },
        preparedFactory = { operationId, ghostId, root ->
            preparedGhost(
                operationId,
                ghostId,
                root,
                name = if (ghostId == "outgoing") "Old Ghost Metadata" else "New Ghost Metadata",
                sakuraName = if (ghostId == "outgoing") "Old Sakura Display" else "New Sakura Display",
                keroName = "Kero",
            )
        },
    ).also { fixture ->
        fixture.trace.requestObserver.set { recorded ->
            val id = requestId(recorded.protocolText)
            if (id == "OnGhostChanging" || id == "OnGhostChanged") {
                trace.add(
                    "request:${recorded.ownerGhostId}:$id:${references(recorded.protocolText)}",
                )
            }
        }
        fixture.runner.setNoWaitMode(true)
        fixture.runner.setPresentationRenderer(TraceRenderer(trace))
    }

    private fun requestId(request: String): String? = request.lineSequence()
        .firstOrNull { it.startsWith("ID: ") }
        ?.removePrefix("ID: ")

    private fun references(request: String): List<String> = request.lineSequence()
        .filter { it.startsWith("Reference") }
        .map { it.substringAfter(": ") }
        .toList()

    private class Trace {
        private val events = java.util.concurrent.CopyOnWriteArrayList<String?>()
        fun add(event: String?) { events += event }
        fun events(): MutableList<String?> = ArrayList(events)
        fun awaitSize(expected: Int) {
            repeat(10_000) {
                if (events.size >= expected) return
                Thread.yield()
            }
            throw AssertionError("trace never reached $expected events: $events")
        }
    }

    private class TraceRenderer(private val trace: Trace) : GhostPresentationRenderer {
        private var previousText = ""
        override fun render(frame: GhostPresentationFrame) {
            val value = frame.sakura.text
            if (value != previousText && value.isNotEmpty()) trace.add("render:$value")
            previousText = value
        }
    }

    private class RecordingPlaybackScheduler : SScriptPlaybackScheduler {
        private val pending = ArrayDeque<() -> Unit>()

        override fun schedule(delayMillis: Long, action: () -> Unit) {
            pending.addLast(action)
        }

        override fun cancelPending() {
            pending.clear()
        }

        fun runNext() = requireNotNull(pending.removeFirstOrNull()).invoke()
    }

    private class RecordingStatusCallback : SScriptRunner.StatusCallback {
        val switchPlaybackCompletions = AtomicInteger()

        override fun stop() = Unit

        override fun canExit() = Unit

        override fun switchPlaybackComplete() {
            switchPlaybackCompletions.incrementAndGet()
        }
    }

    private companion object {
        const val TRANSITION_SCRIPT = "\\_qSwitching\\e"
    }
}
