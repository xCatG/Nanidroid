package com.cattailsw.nanidroid.runtime.dialogue

import androidx.compose.ui.unit.IntOffset
import com.cattailsw.nanidroid.Ghost
import com.cattailsw.nanidroid.GhostSessionCoordinator
import com.cattailsw.nanidroid.SScriptRunner
import com.cattailsw.nanidroid.SScriptPlaybackScheduler
import com.cattailsw.nanidroid.ShioriResponse
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SurfaceInteractionProtocolTest {
    @Rule @JvmField val androidStubs = com.cattailsw.nanidroid.HostAndroidStubRule()

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
        val requests = mutableListOf<String>()
        val ghost = object : Ghost("recording") {
            override fun loadGhostInfo() = Unit
            override fun getCreateCount(): Long = 1L
            override fun incrementCreateCount() = Unit
            override fun pointerEventCapabilities() = PointerEventCapabilities(Support.SUPPORTED, Support.SUPPORTED)
            override fun requestRaw(method: ShioriMethod, eventId: String, references: List<String>): ShioriResponse {
                requests += eventId
                return ShioriResponse("SHIORI/3.0 204 No Content")
            }
        }
        val runner = SScriptRunner(null, GhostSessionCoordinator())
        runner.setGhost(ghost)

        runner.dispatchSurfaceInteraction(effect(PointerSource.MOUSE).copy(button = 1))

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `runner sends one exact request with named collision and event local source`() {
        val requests = mutableListOf<Triple<ShioriMethod, String, List<String>>>()
        val ghost = object : Ghost("recording") {
            override fun loadGhostInfo() = Unit

            override fun getCreateCount(): Long = 1L

            override fun incrementCreateCount() = Unit

            override fun pointerEventCapabilities() = PointerEventCapabilities(Support.SUPPORTED, Support.SUPPORTED)

            override fun requestRaw(method: ShioriMethod, eventId: String, references: List<String>): ShioriResponse {
                requests += Triple(method, eventId, references)
                return ShioriResponse("SHIORI/3.0 204 No Content")
            }
        }
        val runner = SScriptRunner(null, GhostSessionCoordinator())
        runner.setGhost(ghost)

        runner.dispatchSurfaceInteraction(
            effect(
                source = PointerSource.PEN,
                collisionIdentifier = "Face",
                diagnosticCollisionId = 42,
            ),
        )

        assertEquals(
            listOf(
                Triple(
                    ShioriMethod.GET,
                    "OnMouseClick",
                    listOf("12", "34", "0", "0", "Face", "0", "pen"),
                ),
            ),
            requests,
        )
    }

    @Test
    fun `runner serializes generic canvas as present empty reference four without numeric sentinel`() {
        val requests = mutableListOf<List<String>>()
        val ghost = object : Ghost("recording") {
            override fun loadGhostInfo() = Unit

            override fun getCreateCount(): Long = 1L

            override fun incrementCreateCount() = Unit

            override fun pointerEventCapabilities() = PointerEventCapabilities(Support.SUPPORTED, Support.UNSUPPORTED)

            override fun requestRaw(method: ShioriMethod, eventId: String, references: List<String>): ShioriResponse {
                requests += references
                return ShioriResponse("SHIORI/3.0 204 No Content")
            }
        }
        val runner = SScriptRunner(null, GhostSessionCoordinator())
        runner.setGhost(ghost)

        runner.dispatchSurfaceInteraction(effect(PointerSource.TOUCH, collisionIdentifier = null, diagnosticCollisionId = -1))

        assertEquals(listOf(listOf("12", "34", "0", "0", "", "0", "touch")), requests)
    }

    @Test
    fun `runner plays one successful pointer response without using it for capabilities`() {
        val ghost = object : Ghost("recording") {
            override fun loadGhostInfo() = Unit
            override fun getCreateCount(): Long = 1L
            override fun incrementCreateCount() = Unit
            override fun getSakuraName(): String = "Sakura"
            override fun getKeroName(): String = "Kero"
            override fun pointerEventCapabilities() = PointerEventCapabilities(Support.SUPPORTED, Support.UNSUPPORTED)
            override fun requestRaw(method: ShioriMethod, eventId: String, references: List<String>) =
                ShioriResponse("SHIORI/3.0 200 OK", java.util.Hashtable<String, String>().apply {
                    put("Value", "\\hpointer reply\\e")
                })
        }
        val runner = SScriptRunner(null, GhostSessionCoordinator())
        runner.setNoWaitMode(true)
        runner.setGhost(ghost)

        runner.dispatchSurfaceInteraction(effect(PointerSource.TOUCH))

        assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("pointer reply")))),
            runner.dialogueStateSnapshot().contents,
        )
    }

    @Test
    fun `pointer response waits behind active playback without replacing its source`() {
        val scheduler = RecordingScheduler()
        val ghost = object : Ghost("recording") {
            override fun loadGhostInfo() = Unit
            override fun getCreateCount(): Long = 1L
            override fun incrementCreateCount() = Unit
            override fun getSakuraName(): String = "Sakura"
            override fun getKeroName(): String = "Kero"
            override fun pointerEventCapabilities() = PointerEventCapabilities(Support.SUPPORTED, Support.UNSUPPORTED)
            override fun requestRaw(method: ShioriMethod, eventId: String, references: List<String>) =
                ShioriResponse("SHIORI/3.0 200 OK", java.util.Hashtable<String, String>().apply {
                    put("Value", "\\hReply\\e")
                })
        }
        val runner = SScriptRunner(
            ctx = null,
            sessionCoordinator = GhostSessionCoordinator(),
            playbackSchedulerFactory = { scheduler },
        )
        runner.setGhost(ghost)
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
        val ghost = object : Ghost("recording") {
            override fun loadGhostInfo() = Unit
            override fun getCreateCount(): Long = 1L
            override fun incrementCreateCount() = Unit
            override fun getSakuraName(): String = "Sakura"
            override fun getKeroName(): String = "Kero"
            override fun pointerEventCapabilities() = PointerEventCapabilities(Support.SUPPORTED, Support.UNSUPPORTED)
            override fun requestRaw(method: ShioriMethod, eventId: String, references: List<String>) =
                ShioriResponse("SHIORI/3.0 200 OK", java.util.Hashtable<String, String>().apply {
                    put("Value", "\\hReply\\e")
                })
        }
        val runner = SScriptRunner(
            ctx = null,
            sessionCoordinator = GhostSessionCoordinator(),
            playbackSchedulerFactory = { scheduler },
        )
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hDone\\e"))
        runner.run()
        scheduler.runUntil {
            runner.dialogueStateSnapshot().contents.singleOrNull()?.segments?.text() == "Done"
        }
        scheduler.runNext() // consume \e
        scheduler.runNext() // poll empty and schedule delayed STOP

        runner.dispatchSurfaceInteraction(effect(PointerSource.TOUCH))
        scheduler.runNext() // delayed STOP must hand off to the newly queued response
        scheduler.runUntil {
            runner.dialogueStateSnapshot().contents.singleOrNull()?.segments?.text() == "Reply"
        }

        assertEquals(2L, runner.dialogueStateSnapshot().talkId)
    }

    @Test
    fun `kero interaction clears queued dialogue before dispatch`() {
        val ghost = object : Ghost("recording") {
            override fun loadGhostInfo() = Unit
            override fun getCreateCount(): Long = 1L
            override fun incrementCreateCount() = Unit
            override fun getSakuraName(): String = "Sakura"
            override fun getKeroName(): String = "Kero"
            override fun pointerEventCapabilities() = PointerEventCapabilities(Support.SUPPORTED, Support.UNSUPPORTED)
            override fun requestRaw(method: ShioriMethod, eventId: String, references: List<String>) =
                ShioriResponse("SHIORI/3.0 204 No Content")
        }
        val runner = SScriptRunner(null, GhostSessionCoordinator())
        runner.setNoWaitMode(true)
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hqueued talk\\e"))

        runner.dispatchSurfaceInteraction(effect(PointerSource.TOUCH, speaker = SurfaceSpeaker.KERO))
        runner.run()

        assertTrue(runner.dialogueStateSnapshot().contents.isEmpty())
    }

    @Test
    fun `rejected kero interaction preserves queued dialogue`() {
        val requests = mutableListOf<String>()
        val ghost = object : Ghost("recording") {
            override fun loadGhostInfo() = Unit
            override fun getCreateCount(): Long = 1L
            override fun incrementCreateCount() = Unit
            override fun getSakuraName(): String = "Sakura"
            override fun getKeroName(): String = "Kero"
            override fun pointerEventCapabilities() = PointerEventCapabilities(Support.UNSUPPORTED, Support.UNSUPPORTED)
            override fun requestRaw(method: ShioriMethod, eventId: String, references: List<String>): ShioriResponse {
                requests += eventId
                return ShioriResponse("SHIORI/3.0 204 No Content")
            }
        }
        val runner = SScriptRunner(null, GhostSessionCoordinator())
        runner.setNoWaitMode(true)
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hqueued talk\\e"))

        assertTrue(!runner.dispatchSurfaceInteraction(effect(PointerSource.TOUCH, speaker = SurfaceSpeaker.KERO)))
        runner.run()

        assertEquals(
            listOf(DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("queued talk")))),
            runner.dialogueStateSnapshot().contents,
        )
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `switch pending kero interaction never requests an unloaded session`() {
        var unloads = 0
        var stops = 0
        var handoffs = 0
        var capabilityQueries = 0
        val pointerRequests = mutableListOf<String>()
        val ghost = object : Ghost("recording") {
            override fun loadGhostInfo() = Unit
            override fun getCreateCount(): Long = 1L
            override fun incrementCreateCount() = Unit
            override fun getSakuraName(): String = "Sakura"
            override fun getKeroName(): String = "Kero"
            override fun pointerEventCapabilities() = if (capabilityQueries++ == 0) {
                PointerEventCapabilities(Support.SUPPORTED, Support.UNSUPPORTED)
            } else {
                PointerEventCapabilities(Support.UNSUPPORTED, Support.SUPPORTED)
            }
            override fun doShioriEvent(event: String, ref: Array<String>?) =
                ShioriResponse("SHIORI/3.0 204 No Content")
            override fun requestRaw(method: ShioriMethod, eventId: String, references: List<String>): ShioriResponse {
                pointerRequests += eventId
                return ShioriResponse("SHIORI/3.0 204 No Content")
            }
            override fun unload() {
                unloads++
            }
        }
        val runner = SScriptRunner(null, GhostSessionCoordinator())
        runner.setNoWaitMode(true)
        runner.setCallback(object : SScriptRunner.StatusCallback {
            override fun stop() {
                stops++
            }
            override fun canExit() = Unit
            override fun ghostSwitchScriptComplete() {
                handoffs++
            }
        })
        runner.setGhost(ghost)
        runner.addMsgToQueue(arrayOf("\\hqueued talk\\e"))
        runner.doGhostChanging("next", "ghost", "next-path")

        val interaction = effect(PointerSource.TOUCH, speaker = SurfaceSpeaker.KERO)
        val diagnosticDispatch = runner.dispatchSurfaceInteractionWithDiagnostics(interaction)

        assertEquals("OnMouseClick", diagnosticDispatch.candidateEvent)
        assertTrue(!diagnosticDispatch.accepted)
        assertEquals(1, capabilityQueries)
        assertEquals(1, unloads)
        assertEquals(1, stops)
        assertEquals(1, handoffs)
        assertTrue(pointerRequests.isEmpty())

        runner.run()

        assertEquals(2, stops)
        assertTrue(runner.dialogueStateSnapshot().contents.isEmpty())
    }

    @Test
    fun `unloaded session does not fabricate a pointer diagnostic candidate`() {
        var unloaded = false
        var capabilityQueries = 0
        val ghost = object : Ghost("recording") {
            override fun loadGhostInfo() = Unit
            override fun getCreateCount(): Long = 1L
            override fun incrementCreateCount() = Unit
            override fun getSakuraName(): String = "Sakura"
            override fun getKeroName(): String = "Kero"
            override fun pointerEventCapabilities(): PointerEventCapabilities {
                capabilityQueries++
                return if (unloaded) {
                    PointerEventCapabilities()
                } else {
                    PointerEventCapabilities(Support.UNSUPPORTED, Support.UNSUPPORTED)
                }
            }
            override fun doShioriEvent(event: String, ref: Array<String>?) =
                ShioriResponse("SHIORI/3.0 204 No Content")
            override fun unload() {
                unloaded = true
            }
        }
        val runner = SScriptRunner(null, GhostSessionCoordinator())
        runner.setNoWaitMode(true)
        runner.setGhost(ghost)
        val liveDiagnosticDispatch = runner.dispatchSurfaceInteractionWithDiagnostics(effect(PointerSource.TOUCH))

        assertEquals(null, liveDiagnosticDispatch.candidateEvent)
        assertTrue(!liveDiagnosticDispatch.accepted)
        assertEquals(1, capabilityQueries)
        assertTrue(runner.unloadGhostForSwitchForTesting(ghost))

        assertTrue(unloaded)
        val diagnosticDispatch = runner.dispatchSurfaceInteractionWithDiagnostics(effect(PointerSource.TOUCH))

        assertEquals(null, diagnosticDispatch.candidateEvent)
        assertTrue(!diagnosticDispatch.accepted)
        assertEquals(1, capabilityQueries)
    }
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
