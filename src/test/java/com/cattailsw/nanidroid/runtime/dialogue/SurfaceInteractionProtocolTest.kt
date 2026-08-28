package com.cattailsw.nanidroid.runtime.dialogue

import androidx.compose.ui.unit.IntOffset
import com.cattailsw.nanidroid.AttachmentReceipt
import com.cattailsw.nanidroid.GhostHandle
import com.cattailsw.nanidroid.RuntimeFixture
import com.cattailsw.nanidroid.RuntimeFixtureRegistry
import com.cattailsw.nanidroid.RuntimeResult
import com.cattailsw.nanidroid.SScriptPlaybackScheduler
import com.cattailsw.nanidroid.SScriptPlaybackHooks
import com.cattailsw.nanidroid.SScriptRunnerConfiguration
import com.cattailsw.nanidroid.assertIs
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SurfaceInteractionProtocolTest {
    @Rule @JvmField val androidStubs = com.cattailsw.nanidroid.HostAndroidStubRule()
    @Rule @JvmField val runtimes = RuntimeFixtureRegistry()

    @Test
    fun `touch capability table chooses exactly one approved event`() {
        listOf(
            Triple(Support.SUPPORTED, Support.SUPPORTED, "OnMouseClick"),
            Triple(Support.UNSUPPORTED, Support.SUPPORTED, "OnMouseDoubleClick"),
            Triple(Support.UNSUPPORTED, Support.UNSUPPORTED, null),
            Triple(Support.UNSUPPORTED, Support.UNKNOWN, "OnMouseDoubleClick"),
            Triple(Support.UNKNOWN, Support.SUPPORTED, "OnMouseDoubleClick"),
            Triple(Support.UNKNOWN, Support.UNSUPPORTED, "OnMouseClick"),
            Triple(Support.UNKNOWN, Support.UNKNOWN, "OnMouseDoubleClick"),
        ).forEach { (click, doubleClick, expectedEvent) ->
            assertEquals(
                expectedEvent,
                SurfaceInteractionProtocol.eventFor(
                    effect = effect(source = PointerSource.TOUCH),
                    capabilities = PointerEventCapabilities(click, doubleClick),
                ),
            )
        }
    }

    @Test
    fun `deferred touch kinds are not dispatched`() {
        listOf(
            PointerEventKind.MOVE,
            PointerEventKind.ENTER,
            PointerEventKind.LEAVE,
            PointerEventKind.WHEEL,
            PointerEventKind.DRAG,
        ).forEach { kind ->
            assertNull(
                SurfaceInteractionProtocol.eventFor(
                    effect(PointerSource.TOUCH, kind = kind),
                    PointerEventCapabilities(Support.SUPPORTED, Support.SUPPORTED),
                ),
            )
        }
    }

    @Test
    fun `explicitly unsupported physical pointer effects are not dispatched`() {
        val capabilities = PointerEventCapabilities(Support.UNSUPPORTED, Support.UNSUPPORTED)
        assertNull(SurfaceInteractionProtocol.eventFor(effect(PointerSource.MOUSE), capabilities))
        assertNull(
            SurfaceInteractionProtocol.eventFor(
                effect(PointerSource.PEN, kind = PointerEventKind.DOUBLE_CLICK),
                capabilities,
            ),
        )
        assertNull(SurfaceInteractionProtocol.eventFor(effect(PointerSource.ERASER), capabilities))
    }

    @Test
    fun `non-primary button effects are not dispatched`() {
        assertNull(
            SurfaceInteractionProtocol.eventFor(
                effect(PointerSource.MOUSE).copy(button = 1),
                PointerEventCapabilities(Support.SUPPORTED, Support.SUPPORTED),
            ),
        )
    }

    @Test
    fun `runner does not request non-primary effects`() {
        val fixture = fixture(Support.SUPPORTED, Support.SUPPORTED)

        fixture.runner.dispatchSurfaceInteraction(effect(PointerSource.MOUSE).copy(button = 1))

        assertTrue(fixture.trace.requests.isEmpty())
    }

    @Test
    fun `runner sends one exact request with named collision and event local source`() {
        val fixture = fixture(Support.SUPPORTED, Support.SUPPORTED)

        fixture.runner.dispatchSurfaceInteraction(
            effect(
                source = PointerSource.PEN,
                collisionIdentifier = "Face",
                diagnosticCollisionId = 42,
            ),
        )

        assertEquals(
            listOf(
                "GET SHIORI/3.0\r\nSender: Nanidroid\r\nSecurityLevel: local\r\n" +
                    "ID: OnMouseClick\r\nReference0: 12\r\nReference1: 34\r\n" +
                    "Reference2: 0\r\nReference3: 0\r\nReference4: Face\r\n" +
                    "Reference5: 0\r\nReference6: pen\r\n\r\n",
            ),
            fixture.trace.requests,
        )
    }

    @Test
    fun `runner serializes generic canvas as present empty reference four without numeric sentinel`() {
        val fixture = fixture(Support.SUPPORTED, Support.UNSUPPORTED)

        fixture.runner.dispatchSurfaceInteraction(
            effect(PointerSource.TOUCH, collisionIdentifier = null, diagnosticCollisionId = -1),
        )

        assertEquals(
            listOf(
                "GET SHIORI/3.0\r\nSender: Nanidroid\r\nSecurityLevel: local\r\n" +
                    "ID: OnMouseClick\r\nReference0: 12\r\nReference1: 34\r\n" +
                    "Reference2: 0\r\nReference3: 0\r\nReference4: \r\n" +
                    "Reference5: 0\r\nReference6: touch\r\n\r\n",
            ),
            fixture.trace.requests,
        )
    }

    @Test
    fun `runner plays one successful pointer response without using it for capabilities`() {
        val fixture = fixture(
            Support.SUPPORTED,
            Support.UNSUPPORTED,
            response = { valueResponse("\\hpointer reply\\e") },
        )
        fixture.runner.setNoWaitMode(true)

        fixture.runner.dispatchSurfaceInteraction(effect(PointerSource.TOUCH))

        assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("pointer reply")))),
            fixture.runner.dialogueStateSnapshot().contents,
        )
    }

    @Test
    fun `pointer response waits behind active playback without replacing its source`() {
        val scheduler = RecordingScheduler()
        val fixture = fixture(
            Support.SUPPORTED,
            Support.UNSUPPORTED,
            response = { valueResponse("\\hReply\\e") },
            scheduler = scheduler,
        )
        val runner = fixture.runner
        runner.addMsgToQueue(arrayOf("\\hABCDEFGHIJ\\_w[5000]\\e"))
        runner.run()
        scheduler.runNext()
        assertEquals("A", runner.dialogueStateSnapshot().contents.single().segments.text())

        runner.dispatchSurfaceInteraction(effect(PointerSource.TOUCH))
        scheduler.runUntil {
            runner.dialogueStateSnapshot().contents.singleOrNull()?.segments?.text() == "ABCDEFGHIJ"
        }
        scheduler.runUntil {
            runner.dialogueStateSnapshot().contents.singleOrNull()?.segments?.text() == "Reply"
        }

        assertEquals(2L, runner.dialogueStateSnapshot().talkId)
    }

    @Test
    fun `pointer response arriving during terminal stop delay is not stranded`() {
        val scheduler = RecordingScheduler()
        val fixture = fixture(
            Support.SUPPORTED,
            Support.UNSUPPORTED,
            response = { valueResponse("\\hReply\\e") },
            scheduler = scheduler,
        )
        val runner = fixture.runner
        runner.addMsgToQueue(arrayOf("\\hDone\\e"))
        runner.run()
        scheduler.runUntil {
            runner.dialogueStateSnapshot().contents.singleOrNull()?.segments?.text() == "Done"
        }
        scheduler.runNext()
        scheduler.runNext()

        runner.dispatchSurfaceInteraction(effect(PointerSource.TOUCH))
        scheduler.runNext()
        scheduler.runUntil {
            runner.dialogueStateSnapshot().contents.singleOrNull()?.segments?.text() == "Reply"
        }

        assertEquals(2L, runner.dialogueStateSnapshot().talkId)
    }

    @Test
    fun `kero interaction clears queued dialogue before dispatch`() {
        val fixture = fixture(Support.SUPPORTED, Support.UNSUPPORTED)
        val runner = fixture.runner
        runner.setNoWaitMode(true)
        runner.addMsgToQueue(arrayOf("\\hqueued talk\\e"))

        runner.dispatchSurfaceInteraction(effect(PointerSource.TOUCH, speaker = SurfaceSpeaker.KERO))
        runner.run()

        assertTrue(runner.dialogueStateSnapshot().contents.isEmpty())
    }

    @Test
    fun `rejected kero interaction preserves queued dialogue`() {
        val fixture = fixture(Support.UNSUPPORTED, Support.UNSUPPORTED)
        val runner = fixture.runner
        runner.setNoWaitMode(true)
        runner.addMsgToQueue(arrayOf("\\hqueued talk\\e"))

        assertTrue(!runner.dispatchSurfaceInteraction(effect(PointerSource.TOUCH, speaker = SurfaceSpeaker.KERO)))
        runner.run()

        assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("queued talk")))),
            runner.dialogueStateSnapshot().contents,
        )
        assertTrue(fixture.trace.requests.isEmpty())
    }

    @Test
    fun `generation replacement after capture cannot clear replacement dialogue`() {
        lateinit var fixture: RuntimeFixture
        var transitioned = false
        fixture = fixture(
            Support.SUPPORTED,
            Support.UNSUPPORTED,
            playbackHooks = SScriptPlaybackHooks(
                afterSurfaceInteractionCaptured = {
                    if (!transitioned) {
                        transitioned = true
                        val outgoing = fixture.requireHandle()
                        assertIs<RuntimeResult.Success<Unit>>(
                            fixture.runtime.unload(outgoing.generation),
                        )
                        val targetRoot = File("build/runtime-fixtures/pointer-replacement")
                        val replacement = runBlocking {
                            assertIs<RuntimeResult.Success<GhostHandle>>(
                                fixture.runtime.startOrJoin("pointer-replacement", targetRoot),
                            ).value
                        }
                        runBlocking {
                            assertIs<RuntimeResult.Success<AttachmentReceipt>>(
                                fixture.runtime.attachHost(replacement.generation),
                            )
                        }
                        fixture.runner.addMsgToQueue(arrayOf("\\hreplacement queued\\e"))
                    }
                },
            ),
        )
        fixture.runner.setNoWaitMode(true)

        assertTrue(
            !fixture.runner.dispatchSurfaceInteraction(
                effect(PointerSource.TOUCH, speaker = SurfaceSpeaker.KERO),
            ),
        )
        fixture.runner.run()

        assertEquals(
            listOf(
                DialogueContent(
                    GhostSpeaker.SAKURA,
                    listOf(DialogueSegment.Text("replacement queued")),
                ),
            ),
            fixture.runner.dialogueStateSnapshot().contents,
        )
    }

    @Test
    fun `switch pending kero interaction never requests an unloaded session`() {
        val fixture = fixture(Support.SUPPORTED, Support.UNSUPPORTED)
        val runner = fixture.runner
        runner.setNoWaitMode(true)
        val active = fixture.requireHandle()
        val target = File("build/runtime-fixtures/replacement")
        val operationId = assertIs<RuntimeResult.Success<Long>>(
            fixture.runtime.beginSwitch(active.generation, "replacement", target),
        ).value
        runner.addMsgToQueue(arrayOf("\\hqueued talk\\e"))

        assertTrue(runner.doGhostChanging(operationId, "next", "ghost", target.path))
        await { fixture.trace.unloadCount.get() == 1 }
        val diagnostic = runner.dispatchSurfaceInteractionWithDiagnostics(
            effect(PointerSource.TOUCH, speaker = SurfaceSpeaker.KERO),
        )

        assertNull(diagnostic.candidateEvent)
        assertTrue(!diagnostic.accepted)
        assertTrue(fixture.trace.requests.none { it.contains("ID: OnMouseClick") })
    }

    @Test
    fun `unloaded session does not fabricate a pointer diagnostic candidate`() {
        val fixture = fixture(Support.UNSUPPORTED, Support.UNSUPPORTED)
        val runner = fixture.runner
        val generation = fixture.requireHandle().generation

        val live = runner.dispatchSurfaceInteractionWithDiagnostics(effect(PointerSource.TOUCH))
        assertNull(live.candidateEvent)
        assertTrue(!live.accepted)
        assertIs<RuntimeResult.Success<Unit>>(fixture.runtime.unload(generation))

        val unloaded = runner.dispatchSurfaceInteractionWithDiagnostics(effect(PointerSource.TOUCH))
        assertNull(unloaded.candidateEvent)
        assertTrue(!unloaded.accepted)
        assertEquals(1, fixture.trace.unloadCount.get())
    }

    private fun fixture(
        click: Support,
        doubleClick: Support,
        response: (String) -> String = { noContentResponse() },
        scheduler: RecordingScheduler? = null,
        playbackHooks: SScriptPlaybackHooks? = null,
    ): RuntimeFixture = runtimes.create(
        response = response,
        bootstrapResponse = { supportedEventsResponse(click, doubleClick) },
        runnerConfiguration = if (scheduler != null || playbackHooks != null) {
            SScriptRunnerConfiguration(
                playbackSchedulerFactory = scheduler?.let { { it } }
                    ?: SScriptRunnerConfiguration().playbackSchedulerFactory,
                playbackHooks = playbackHooks ?: SScriptPlaybackHooks(),
            )
        } else null,
        preparedFactory = { operationId, ghostId, root ->
            com.cattailsw.nanidroid.preparedGhost(
                operationId,
                ghostId,
                root,
                name = "Recording",
                sakuraName = "Sakura",
                keroName = "Kero",
            )
        },
    )
}

private class RecordingScheduler : SScriptPlaybackScheduler {
    private val pending = ArrayDeque<() -> Unit>()

    override fun schedule(delayMillis: Long, action: () -> Unit) {
        pending.addLast(action)
    }

    override fun cancelPending() {
        pending.clear()
    }

    fun runNext() = requireNotNull(pending.removeFirstOrNull()).invoke()

    fun runUntil(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            runNext()
        }
        throw AssertionError("playback condition was not reached")
    }
}

private fun supportedEventsResponse(click: Support, doubleClick: Support): String {
    val events = buildList {
        if (click == Support.SUPPORTED) add("OnMouseClick")
        if (doubleClick == Support.SUPPORTED) add("OnMouseDoubleClick")
    }.joinToString(",")
    return "SHIORI/3.0 204 No Content\r\nX-SSTP-PassThru-local: $events\r\n\r\n"
}

private fun noContentResponse() = "SHIORI/3.0 204 No Content\r\n\r\n"

private fun valueResponse(value: String) =
    "SHIORI/3.0 200 OK\r\nValue: $value\r\n\r\n"

private fun await(predicate: () -> Boolean) {
    repeat(10_000) {
        if (predicate()) return
        Thread.yield()
    }
    throw AssertionError("runtime condition was not reached")
}

private fun List<DialogueSegment>.text(): String = buildString {
    this@text.forEach { segment -> if (segment is DialogueSegment.Text) append(segment.value) }
}

private fun effect(
    source: PointerSource,
    kind: PointerEventKind = PointerEventKind.CLICK,
    speaker: SurfaceSpeaker = SurfaceSpeaker.SAKURA,
    collisionIdentifier: String? = "Face",
    diagnosticCollisionId: Int? = 42,
) = SurfaceInteractionEffect(
    kind = kind,
    speaker = speaker,
    intrinsic = IntOffset(12, 34),
    button = 0,
    source = source,
    collisionIdentifier = collisionIdentifier,
    diagnosticCollisionId = diagnosticCollisionId,
)
