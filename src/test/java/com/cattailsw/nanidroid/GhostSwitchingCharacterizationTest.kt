package com.cattailsw.nanidroid

import java.io.File
import java.util.Arrays
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

    private fun fixture(
        trace: Trace,
        persistence: InMemoryGhostRuntimePersistence = InMemoryGhostRuntimePersistence(),
    ): RuntimeFixture = runtimes.create(
        id = "outgoing",
        root = File("build/runtime-fixtures/switching/outgoing"),
        persistence = persistence,
        response = { request ->
            val id = requestId(request)
            if (id == "OnGhostChanging" || id == "OnGhostChanged") {
                trace.add("request:${requestGhostId(request, id)}:$id:${references(request)}")
            }
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
        fixture.runner.setNoWaitMode(true)
        fixture.runner.setPresentationRenderer(TraceRenderer(trace))
    }

    private fun requestGhostId(request: String, eventId: String): String = when (eventId) {
        "OnGhostChanging" -> "outgoing"
        "OnGhostChanged" -> "replacement"
        else -> request
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

    private companion object {
        const val TRANSITION_SCRIPT = "\\_qSwitching\\e"
    }
}
