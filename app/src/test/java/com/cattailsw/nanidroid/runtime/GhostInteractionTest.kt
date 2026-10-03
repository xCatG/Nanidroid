package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.engine.ShioriEngine
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.ghost.BundledGhost
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GhostInteractionTest {
    @get:Rule val folder = TemporaryFolder()
    private val ghost = BundledGhost("nanidroid", "Nanidroid", "Sakura", "Kero",
        mapOf(0 to File("sakura.png"), 10 to File("kero.png")), "")

    private class Store : BootStateStore {
        override suspend fun recordActivation(directoryId: String) = true
        override suspend fun consumeOnboarding() = false
    }

    private class Engine(private val answer: (ShioriEvent) -> ShioriReply) : ShioriEngine {
        val events = mutableListOf<ShioriEvent>()
        override suspend fun request(event: ShioriEvent): ShioriReply {
            events += event
            return answer(event)
        }
    }

    private suspend fun TestScope.started(engine: ShioriEngine, ghost: BundledGhost = this@GhostInteractionTest.ghost) =
        GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { testScheduler.currentTime }).also {
            it.start("en"); it.setResumed(true); runCurrent()
        }

    private val GhostRuntime.ready get() = state.value as StageState.Ready

    /** The source the stage builds for Sakura, over 20x20 images with [ids]. */
    private fun sakuraSource(ready: StageState.Ready, vararg ids: Int): RenderedMoveSource {
        val images = ids.associateWith { SurfaceImageBounds(0, 0, 20, 20) }
        val sakura = ready.frame.sakura
        val coordinates = MoveGeometryResolver.resolve(sakura.surfaceId, sakura.visual, images, 0)!!
            .coordinates
        return RenderedMoveSource(ready.dialogueToken, sakura.surfaceId, sakura.visual,
            RenderedMoveSnapshot(images, coordinates))
    }

    /** A gated [eventId] reply still plays after an ordinary script advanced the queue. */
    private suspend fun TestScope.assertReplySurvivesQueueAdvancement(eventId: String,
        trigger: TestScope.(GhostRuntime) -> Unit) {
        val gate = CompletableDeferred<Unit>()
        val engine = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply = when (event.id) {
                eventId -> { gate.await(); ShioriReply(200, "\\_qReply\\e") }
                "OnInstallBegin" -> ShioriReply(200, "\\_qOrdinary\\e")
                else -> ShioriReply(204)
            }
        }
        var now = 0L
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { now })
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        trigger(runtime); runCurrent()
        runtime.beginImportEvents()!!.emit("OnInstallBegin"); runCurrent()
        assertEquals("Ordinary", runtime.ready.frame.sakura.text)
        gate.complete(Unit); runCurrent()
        now = 1_100; advanceTimeBy(50); runCurrent()
        assertEquals("Reply", runtime.ready.frame.sakura.text)
    }

    @Test fun closeChoiceWaitsForItsReplyBeforeFinishing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = object : ShioriEngine {
            val events = mutableListOf<String>()
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event.id
                return when (event.id) {
                    "OnClose" -> ShioriReply(200, "\\q[Stay,answer]\\e")
                    "OnChoiceSelect" -> { gate.await(); ShioriReply(200, "\\_qFarewell reply\\e") }
                    else -> ShioriReply(204)
                }
            }
        }
        val runtime = started(engine)
        runtime.close(); runCurrent()
        val token = runtime.ready.choices[0]!!.single().token
        advanceTimeBy(100); runCurrent()
        assertTrue(runtime.state.value is StageState.Ready)
        runtime.choose(token); runtime.choose(token); runCurrent()
        assertEquals(1, engine.events.count { it == "OnChoiceSelect" })
        assertTrue(runtime.state.value is StageState.Ready)
        gate.complete(Unit); runCurrent()
        assertEquals("Farewell reply", runtime.ready.frame.sakura.text)
        advanceTimeBy(1_500); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(1, engine.events.count { it == "OnClose" })
    }

    @Test fun closeInputCancelResumesRemainderAndOldActiveInputIsInvalid() = runTest {
        val engine = Engine { event -> when (event.id) {
            "OnFirstBoot" -> ShioriReply(200, "\\![open,inputbox,old]\\e")
            "OnClose" -> ShioriReply(200, "\\_qBye\\![open,inputbox,new]Done\\e")
            else -> ShioriReply(204)
        } }
        val runtime = started(engine)
        val old = runtime.ready.input!!.token
        runtime.close(); runCurrent()
        val farewell = runtime.ready.input!!.token
        runtime.submitInput(old, "stale"); runCurrent()
        assertEquals(0, engine.events.count { it.id == "OnUserInput" })
        runtime.cancelInput(farewell); runCurrent()
        assertEquals("ByeDone", runtime.ready.frame.sakura.text)
        advanceTimeBy(1_500); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
    }

    @Test fun closeInputOkPlaysRemainderThenActionReplyBeforeFinishing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = object : ShioriEngine {
            val events = mutableListOf<ShioriEvent>()
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event
                return when (event.id) {
                    "OnClose" -> ShioriReply(200, "\\_qBefore\\![open,inputbox,box]After\\e")
                    "OnUserInput" -> { gate.await(); ShioriReply(200, "\\_qAnswer reply\\e") }
                    else -> ShioriReply(204)
                }
            }
        }
        val runtime = started(engine)
        runtime.close(); runCurrent()
        val token = runtime.ready.input!!.token
        runtime.submitInput(token, "hello"); runtime.submitInput(token, "again"); runCurrent()
        assertEquals(listOf("box", "hello"), engine.events.single { it.id == "OnUserInput" }.references)
        assertEquals("BeforeAfter", runtime.ready.frame.sakura.text)
        advanceTimeBy(1_500); runCurrent()
        assertTrue(runtime.state.value is StageState.Ready)
        gate.complete(Unit); runCurrent()
        assertEquals("Answer reply", runtime.ready.frame.sakura.text)
        advanceTimeBy(1_500); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
    }

    @Test fun farewellSurfaceChangesRenderWithoutDispatchingSurfaceEvents() = runTest {
        val engine = Engine { event -> when (event.id) {
            "OnClose" -> ShioriReply(200, "\\_q\\s[2]Bye\\e")
            "OnSurfaceChange" -> ShioriReply(200, "\\s[99]Unexpected\\e")
            else -> ShioriReply(204)
        } }
        val runtime = started(engine)
        runtime.close(); runCurrent()
        assertEquals(2, runtime.ready.frame.sakura.surfaceId)
        assertEquals(0, engine.events.count { it.id == "OnSurfaceChange" })
        advanceTimeBy(1_500); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
    }

    @Test fun secondBackSkipsPendingFarewellAndDiscardsLateReply() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = object : ShioriEngine {
            val events = mutableListOf<String>()
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event.id
                return when (event.id) {
                    "OnClose" -> ShioriReply(200, "\\![open,inputbox,box]\\e")
                    "OnUserInput" -> { gate.await(); ShioriReply(200, "\\q[Late,late]\\e") }
                    else -> ShioriReply(204)
                }
            }
        }
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runtime.close(); runCurrent()
        val token = runtime.ready.input!!.token
        runtime.submitInput(token, "answer"); runCurrent()
        runtime.close(); runtime.close(); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        gate.complete(Unit); runCurrent()
        runtime.submitInput(token, "again"); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(1, engine.events.count { it == "OnClose" })
        assertEquals(1, engine.events.count { it == "OnUserInput" })
    }

    @Test fun secondBackSkipsDelayedCloseReplyWithoutAnotherCloseRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = object : ShioriEngine {
            val events = mutableListOf<String>()
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event.id
                return when (event.id) {
                    "OnClose" -> { gate.await(); ShioriReply(200, "\\q[Late,late]\\e") }
                    else -> ShioriReply(204)
                }
            }
        }
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runtime.close(); runCurrent()
        assertTrue(runtime.state.value is StageState.Ready)
        runtime.close(); runtime.close(); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        gate.complete(Unit); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(1, engine.events.count { it == "OnClose" })
    }

    @Test fun delayedActiveSurfaceReplyCannotCreateFarewellDialogue() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = object : ShioriEngine {
            val events = mutableListOf<String>()
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event.id
                return when (event.id) {
                    "OnFirstBoot" -> ShioriReply(200, "\\s[2]\\e")
                    "OnSurfaceChange" -> { gate.await(); ShioriReply(200, "\\q[Stale,stale]\\e") }
                    "OnClose" -> ShioriReply(200, "\\_q\\s[3]Bye\\e")
                    else -> ShioriReply(204)
                }
            }
        }
        val runtime = started(engine)
        assertEquals(1, engine.events.count { it == "OnSurfaceChange" })
        runtime.close(); runCurrent()
        assertEquals(3, runtime.ready.frame.sakura.surfaceId)
        gate.complete(Unit); runCurrent()
        advanceTimeBy(1_500); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(1, engine.events.count { it == "OnSurfaceChange" })
    }

    @Test fun choiceIsConsumedOnceAndClearsQueuedDialogueBeforeNativeReply() = runTest {
        val engine = Engine { event -> when (event.id) {
            "OnFirstBoot" -> ShioriReply(200, "\\_q\\q[Yes,chosen]\\q[No,other]\\e")
            "OnSecondChange" -> ShioriReply(200, "Clock\\e")
            "OnChoiceSelect" -> ShioriReply(200, "\\_qSelected\\e")
            else -> ShioriReply(204)
        } }
        val runtime = started(engine)
        val ready = runtime.state.value as StageState.Ready
        assertEquals(listOf("Yes", "No"), ready.choices[0]?.map { it.label })
        val token = ready.choices[0]!!.first().token
        advanceTimeBy(2_100); runCurrent()
        assertEquals(2, engine.events.count { it.id == "OnSecondChange" })
        assertEquals(token, runtime.ready.choices[0]!!.first().token)
        runtime.choose(token); runtime.choose(token); runCurrent()
        assertEquals(1, engine.events.count { it.id == "OnChoiceSelect" })
        assertEquals(listOf("chosen"), engine.events.single { it.id == "OnChoiceSelect" }.references)
        assertEquals("Selected", runtime.ready.frame.sakura.text)
    }

    @Test fun choiceSurvivesSpeakerSwitchAndTimerRepliesUntilChosen() = runTest {
        val engine = Engine { event -> when (event.id) {
            "OnFirstBoot" -> ShioriReply(200,
                "\\_q\\0Sakura\\q[First,first]\\1Kero\\q[Second,second]\\0\\e")
            "OnSecondChange" -> ShioriReply(200, "\\_qClock\\e")
            "OnChoiceSelect" -> ShioriReply(200, when (event.references.single()) {
                "first" -> "\\_qFirst response\\e"
                "second" -> "\\_qSecond response\\e"
                else -> error("Unexpected choice")
            })
            else -> ShioriReply(204)
        } }
        val runtime = started(engine)
        val initial = runtime.ready
        assertEquals("Sakura", initial.frame.sakura.text)
        assertEquals("Kero", initial.frame.kero.text)
        assertEquals(listOf("First"), initial.choices[0]?.map { it.label })
        assertEquals(listOf("Second"), initial.choices[1]?.map { it.label })
        val token = initial.choices[0]!!.single().token

        advanceTimeBy(2_100); runCurrent()
        assertEquals(token, runtime.ready.choices[0]!!.single().token)
        assertEquals("Sakura", runtime.ready.frame.sakura.text)
        assertEquals("Kero", runtime.ready.frame.kero.text)
        assertTrue(engine.events.any { it.id == "OnSecondChange" })

        runtime.choose(token); runCurrent()
        assertEquals(listOf("first"), engine.events.single { it.id == "OnChoiceSelect" }.references)
        assertEquals("First response", runtime.ready.frame.sakura.text)
        runtime.choose(token); runCurrent()
        assertEquals(1, engine.events.count { it.id == "OnChoiceSelect" })
    }

    @Test fun onPrefixedChoiceDispatchesExactEventWithReferencesOnce() = runTest {
        val engine = Engine { event -> when (event.id) {
            "OnFirstBoot" -> ShioriReply(200, "\\_q\\q[Last,OnLastTalk,one,two]\\q[Other,ordinary,ignored]\\e")
            else -> ShioriReply(204)
        } }
        val runtime = started(engine)
        val token = runtime.ready.choices[0]!!.first().token
        runtime.choose(token); runtime.choose(token); runCurrent()
        assertEquals(1, engine.events.count { it.id == "OnLastTalk" })
        assertEquals(listOf("one", "two"), engine.events.single { it.id == "OnLastTalk" }.references)
        assertEquals(0, engine.events.count { it.id == "OnChoiceSelect" })
    }

    @Test fun unchangedEarthquakeMenuFragmentRoutesAuthoredOnAiTalk() = runTest {
        // Earthquake Duo 1.0.1, duo_menu.dic:49; the archive handler is OnAiTalk.
        val engine = Engine { event -> when (event.id) {
            "OnFirstBoot" -> ShioriReply(200, "\\![*]\\q[Say something,OnAiTalk]\\n")
            "OnAiTalk" -> ShioriReply(200, "\\_qTalk\\e")
            else -> ShioriReply(204)
        } }
        val runtime = started(engine)
        val token = runtime.ready.choices[0]!!.single().token
        runtime.choose(token); runCurrent()
        assertEquals(1, engine.events.count { it.id == "OnAiTalk" })
        assertEquals(emptyList<String>(), engine.events.single { it.id == "OnAiTalk" }.references)
        assertEquals("Talk", runtime.ready.frame.sakura.text)
    }

    @Test fun inputRejectsControlTextThenResumesRemainderBeforeQueuedReply() = runTest {
        val engine = Engine { event -> when (event.id) {
            "OnFirstBoot" -> ShioriReply(200, "\\_qBefore\\![open,inputbox,box]After\\e")
            "OnSecondChange" -> ShioriReply(200, "Clock\\e")
            else -> ShioriReply(204)
        } }
        val runtime = started(engine)
        val token = runtime.ready.input!!.token
        advanceTimeBy(1_100); runCurrent()
        runtime.submitInput(token, "bad\r\nReference0: injected"); runCurrent()
        assertEquals(0, engine.events.count { it.id == "OnUserInput" })
        runtime.submitInput(token, "bad\u0085Reference0: injected"); runCurrent()
        assertEquals(0, engine.events.count { it.id == "OnUserInput" })
        assertEquals(token, runtime.ready.input!!.token)
        runtime.submitInput(token, "hello"); runtime.submitInput(token, "again"); runCurrent()
        assertEquals(listOf("box", "hello"), engine.events.single { it.id == "OnUserInput" }.references)
        assertEquals("BeforeAfter", runtime.ready.frame.sakura.text)
    }

    @Test fun cancelInputResumesWithoutAnEvent() = runTest {
        val engine = Engine { event -> if (event.id == "OnFirstBoot")
            ShioriReply(200, "\\_q\\![open,inputbox,box]Done\\e") else ShioriReply(204) }
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runCurrent()
        runtime.cancelInput(runtime.ready.input!!.token); runCurrent()
        assertNull(runtime.ready.input)
        assertEquals("Done", runtime.ready.frame.sakura.text)
        assertEquals(0, engine.events.count { it.id == "OnUserInput" })
    }

    @Test fun quickSurfaceChangesEmitOrderedPairsWithoutRepeatedAssignments() = runTest {
        val engine = Engine { event -> if (event.id == "OnFirstBoot")
            ShioriReply(200, "\\0\\s[2]\\s[2]\\1\\s[11]\\0\\s[3]\\e") else ShioriReply(204) }
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runCurrent()
        assertEquals(listOf(listOf("2", "10"), listOf("2", "11"), listOf("3", "11")),
            engine.events.filter { it.id == "OnSurfaceChange" }.map { it.references })
    }

    @Test fun surfaceChangeAtDialogueEndDispatchesBeforeQueuedDialogueStarts() = runTest {
        val engine = Engine { event -> when (event.id) {
            "OnFirstBoot" -> ShioriReply(200, "\\_w[50]\\s[2]\\e")
            "OnInstallBegin" -> ShioriReply(200, "\\_qNext\\e")
            else -> ShioriReply(204)
        } }
        var now = 0L
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { now })
        runtime.start("en"); runtime.beginImportEvents()!!.emit("OnInstallBegin")
        runtime.setResumed(true); runCurrent()
        now = 1_100
        advanceTimeBy(50); runCurrent()
        assertEquals(listOf("2", "10"),
            engine.events.single { it.id == "OnSurfaceChange" }.references)
        assertEquals("Next", runtime.ready.frame.sakura.text)
    }

    @Test fun delayedSurfaceReplyCannotReplaceNewTouchDialogue() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<ShioriEvent>()
        val engine = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event
                return when (event.id) {
                    "OnFirstBoot" -> ShioriReply(200, "\\s[2]\\s[3]\\e")
                    "OnSurfaceChange" -> { gate.await(); ShioriReply(200, "Old\\e") }
                    "OnMouseClick" -> ShioriReply(200, "\\_qNew\\e")
                    else -> ShioriReply(204)
                }
            }
        }
        val runtime = started(engine)
        runtime.click(0, 1, 2); runCurrent()
        assertEquals("New", runtime.ready.frame.sakura.text)
        gate.complete(Unit); runCurrent()
        assertEquals("New", runtime.ready.frame.sakura.text)
        assertEquals(listOf(listOf("2", "10")),
            events.filter { it.id == "OnSurfaceChange" }.map { it.references })
    }

    @Test fun suspendedFirstSurfaceEventDoesNotLoseLaterEdgesWhenQueueAdvances() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<ShioriEvent>()
        val engine = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event
                return when (event.id) {
                    "OnFirstBoot" -> ShioriReply(200, "\\s[2]\\s[3]\\e")
                    "OnInstallBegin" -> ShioriReply(200, "\\_qQueued\\e")
                    "OnSurfaceChange" -> if (event.references[0] == "2") {
                        gate.await(); ShioriReply(200, "\\_qStale\\e")
                    } else ShioriReply(204)
                    else -> ShioriReply(204)
                }
            }
        }
        var now = 0L
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { now })
        runtime.start("en"); runtime.beginImportEvents()!!.emit("OnInstallBegin")
        runtime.setResumed(true); runCurrent()
        assertEquals(listOf(listOf("2", "10")),
            events.filter { it.id == "OnSurfaceChange" }.map { it.references })
        now = 1_100; advanceTimeBy(50); runCurrent()
        assertEquals("Queued", runtime.ready.frame.sakura.text)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(listOf("2", "10"), listOf("3", "10")),
            events.filter { it.id == "OnSurfaceChange" }.map { it.references })
        assertEquals("Queued", runtime.ready.frame.sakura.text)
    }

    @Test fun surfaceEdgesFromQueuedDialogueWaitBehindEarlierBlockedEdges() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<ShioriEvent>()
        val engine = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event
                return when (event.id) {
                    "OnFirstBoot" -> ShioriReply(200, "\\s[2]\\s[3]\\e")
                    "OnInstallBegin" -> ShioriReply(200, "\\_qNew\\s[4]\\e")
                    "OnSurfaceChange" -> if (event.references[0] == "2") {
                        gate.await(); ShioriReply(200, "\\_qOldReply\\e")
                    } else ShioriReply(204)
                    else -> ShioriReply(204)
                }
            }
        }
        var now = 0L
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { now })
        runtime.start("en"); runtime.beginImportEvents()!!.emit("OnInstallBegin")
        runtime.setResumed(true); runCurrent()
        assertEquals(listOf(listOf("2", "10")),
            events.filter { it.id == "OnSurfaceChange" }.map { it.references })
        now = 1_100; advanceTimeBy(50); runCurrent()
        assertEquals("New", runtime.ready.frame.sakura.text)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(listOf("2", "10"), listOf("3", "10"), listOf("4", "10")),
            events.filter { it.id == "OnSurfaceChange" }.map { it.references })
        assertEquals("New", runtime.ready.frame.sakura.text)
    }

    @Test fun acceptedInputReplySurvivesOrdinaryQueueAdvancement() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<ShioriEvent>()
        val engine = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event
                return when (event.id) {
                    "OnFirstBoot" -> ShioriReply(200, "\\![open,inputbox,box]\\e")
                    "OnInstallBegin" -> ShioriReply(200, "\\_qQueued\\e")
                    "OnUserInput" -> { gate.await(); ShioriReply(200, "\\_qInputReply\\e") }
                    else -> ShioriReply(204)
                }
            }
        }
        var now = 0L
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { now })
        runtime.start("en"); runtime.beginImportEvents()!!.emit("OnInstallBegin")
        runtime.setResumed(true); runCurrent()
        val token = runtime.ready.input!!.token
        runtime.submitInput(token, "answer"); runCurrent()
        assertEquals(1, events.count { it.id == "OnUserInput" })
        now = 1_100; advanceTimeBy(50); runCurrent()
        assertEquals("Queued", runtime.ready.frame.sakura.text)
        gate.complete(Unit); runCurrent()
        now = 2_200; advanceTimeBy(50); runCurrent()
        assertEquals("InputReply", runtime.ready.frame.sakura.text)
    }

    @Test fun acceptedChoiceReplySurvivesOrdinaryQueueAdvancement() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply = when (event.id) {
                "OnFirstBoot" -> ShioriReply(200, "\\q[Pick,id]\\e")
                "OnChoiceSelect" -> { gate.await(); ShioriReply(200, "\\_qChoiceReply\\e") }
                "OnInstallBegin" -> ShioriReply(200, "\\_qOrdinary\\e")
                else -> ShioriReply(204)
            }
        }
        var now = 0L
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { now })
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        runtime.choose(runtime.ready.choices[0]!!.single().token)
        runCurrent()
        runtime.beginImportEvents()!!.emit("OnInstallBegin"); runCurrent()
        assertEquals("Ordinary", runtime.ready.frame.sakura.text)
        gate.complete(Unit); runCurrent()
        now = 1_100; advanceTimeBy(50); runCurrent()
        assertEquals("ChoiceReply", runtime.ready.frame.sakura.text)
    }

    @Test fun acceptedMoveReplySurvivesOrdinaryQueueAdvancement() = runTest {
        assertReplySurvivesQueueAdvancement("OnMouseMove") { it.move(0, 1, 1); advanceTimeBy(50) }
    }

    @Test fun acceptedTouchReplySurvivesOrdinaryQueueAdvancement() = runTest {
        assertReplySurvivesQueueAdvancement("OnMouseClick") { it.click(0, 1, 1) }
    }

    @Test fun explicitReplacementDiscardsDelayedInputReply() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply = when (event.id) {
                "OnFirstBoot" -> ShioriReply(200, "\\![open,inputbox,box]\\e")
                "OnUserInput" -> { gate.await(); ShioriReply(200, "\\_qOldReply\\e") }
                "OnMouseClick" -> ShioriReply(200, "\\_qNew\\e")
                else -> ShioriReply(204)
            }
        }
        var now = 0L
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { now })
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        runtime.submitInput(runtime.ready.input!!.token, "answer")
        runCurrent()
        runtime.click(0, 0, 0); runCurrent()
        gate.complete(Unit); runCurrent()
        now = 1_100; advanceTimeBy(50); runCurrent()
        assertEquals("New", runtime.ready.frame.sakura.text)
    }

    @Test fun clockRepliesCoalesceWhileChoiceWaitsAndClearOnReplacement() = runTest {
        var count = 0
        val engine = Engine { event -> when (event.id) {
            "OnFirstBoot" -> ShioriReply(200, "\\q[Pick,id]\\e")
            "OnSecondChange" -> ShioriReply(200, "\\_qClock${++count}\\e")
            "OnMouseClick" -> ShioriReply(200, "\\_qTap\\e")
            else -> ShioriReply(204)
        } }
        val runtime = started(engine)
        val token = runtime.ready.choices[0]!!.single().token
        advanceTimeBy(4_100); runCurrent()
        assertEquals(4, count)
        assertEquals(token, runtime.ready.choices[0]!!.single().token)
        runtime.click(0, 0, 0); runCurrent()
        assertEquals("Tap", runtime.ready.frame.sakura.text)
        runtime.choose(token); runCurrent()
        assertEquals(0, engine.events.count { it.id == "OnChoiceSelect" })
    }

    @Test fun lateClockReplyFromPreviousDialogueIsDiscarded() = runTest {
        val gate = CompletableDeferred<Unit>()
        var clocks = 0
        val engine = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply = when (event.id) {
                "OnFirstBoot" -> ShioriReply(200, "\\q[Pick,id]\\e")
                "OnSecondChange" -> if (++clocks == 1) {
                    gate.await(); ShioriReply(200, "\\_qOldClock\\e")
                } else ShioriReply(204)
                "OnMouseClick" -> ShioriReply(200, "\\_qNew\\e")
                else -> ShioriReply(204)
            }
        }
        val runtime = started(engine)
        advanceTimeBy(1_000); runCurrent()
        runtime.click(0, 0, 0); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("New", runtime.ready.frame.sakura.text)
        advanceTimeBy(1_000); runCurrent()
        assertNotEquals("OldClock", runtime.ready.frame.sakura.text)
    }

    @Test fun inputRemainderPrecedesLatestDeferredClockDialogue() = runTest {
        var count = 0
        val engine = Engine { event -> when (event.id) {
            "OnFirstBoot" -> ShioriReply(200, "\\_qBegin\\![open,inputbox,box]End\\e")
            "OnSecondChange" -> ShioriReply(200, "\\_qClock${++count}\\e")
            else -> ShioriReply(204)
        } }
        val runtime = started(engine)
        val token = runtime.ready.input!!.token
        advanceTimeBy(3_100); runCurrent()
        assertEquals(3, count)
        runtime.submitInput(token, "answer"); runCurrent()
        assertEquals("BeginEnd", runtime.ready.frame.sakura.text)
        advanceTimeBy(1_000); runCurrent()
        assertEquals("Clock3", runtime.ready.frame.sakura.text)
    }

    @Test fun alreadyQueuedClockReplyKeepsItsFirstFifoPositionWhenInputAppears() = runTest {
        var clock = 0
        val engine = Engine { event -> when (event.id) {
            "OnFirstBoot" -> ShioriReply(200, "\\_w[3500]\\![open,inputbox,box]\\e")
            "OnSecondChange" -> ShioriReply(200, "\\_qClock${++clock}\\e")
            "OnInstallBegin" -> ShioriReply(200, "\\_qOrdinary\\e")
            else -> ShioriReply(204)
        } }
        val runtime = started(engine)
        advanceTimeBy(1_500); runCurrent()
        runtime.beginImportEvents()!!.emit("OnInstallBegin"); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        val token = runtime.ready.input!!.token
        runtime.cancelInput(token); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals("Clock3", runtime.ready.frame.sakura.text)
    }

    @Test fun explicitAnimationRunsWhileInputWaitsAndIdleTicksContinue() = runTest {
        val shell = folder.newFolder("animations")
        File(shell, "surfaces.txt").writeText("""
            surface0 {
            animation0.interval,never
            animation0.pattern0,overlay,1001,50,0,0
            }
        """.trimIndent())
        val engine = Engine { event -> if (event.id == "OnFirstBoot")
            ShioriReply(200, "\\i[0]\\![open,inputbox,box]\\e") else ShioriReply(204) }
        val runtime = started(engine, ghost.copy(shellDirectory = shell))
        assertNotNull(runtime.ready.input)
        assertTrue(runtime.ready.frame.sakura.visual?.layers?.isEmpty() == true)
        advanceTimeBy(50); runCurrent()
        assertEquals(1001, runtime.ready.frame.sakura.visual?.layers?.single()?.surfaceId)
        assertNotNull(runtime.ready.input)
    }

    @Test fun oldSessionTokenCannotActAfterCloseAndRestart() = runTest {
        val engine = Engine { event -> when (event.id) {
            "OnFirstBoot", "OnBoot" -> ShioriReply(200, "\\q[Pick,id]\\e")
            else -> ShioriReply(204)
        } }
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runCurrent()
        val old = runtime.ready.choices[0]!!.single().token
        runtime.close(); runCurrent(); runtime.start("en"); runCurrent()
        val current = runtime.ready.choices[0]!!.single().token
        assertNotEquals(old, current)
        runtime.choose(old); runCurrent()
        assertEquals(0, engine.events.count { it.id == "OnChoiceSelect" })
        runtime.choose(current); runCurrent()
        assertEquals(1, engine.events.count { it.id == "OnChoiceSelect" })
    }

    @Test fun dialogueLinkIdentitySurvivesReattachmentButExpiresOnReplacement() = runTest {
        val engine = Engine { event -> if (event.id == "OnFirstBoot")
            ShioriReply(200, "\\_qVisit https://example.org\\e") else ShioriReply(204) }
        val runtime = started(engine)
        val token = runtime.ready.dialogueToken!!
        assertTrue(runtime.isCurrentDialogue(token))
        runtime.start("en"); runCurrent() // Activity recreation reuses the same runtime.
        assertTrue(runtime.isCurrentDialogue(token))
        runtime.click(0, 0, 0); runCurrent()
        assertFalse(runtime.isCurrentDialogue(token))
    }

    @Test fun currentFarewellDialogueLinkRemainsCurrentUntilCloseIsSkipped() = runTest {
        val engine = Engine { event -> when (event.id) {
            "OnFirstBoot" -> ShioriReply(200, "\\_qOld https://example.org\\e")
            "OnClose" -> ShioriReply(200, "\\q[Wait,wait] Visit https://example.org\\e")
            else -> ShioriReply(204)
        } }
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runCurrent()
        val old = runtime.ready.dialogueToken!!
        runtime.close(); runCurrent()
        val farewell = runtime.ready.dialogueToken!!
        assertFalse(runtime.isCurrentDialogue(old))
        assertTrue(runtime.isCurrentDialogue(farewell))
        runtime.close(); runCurrent()
        assertFalse(runtime.isCurrentDialogue(farewell))
    }

    @Test fun touchReferencesUseCurrentBaseAnimationCollisions() = runTest {
        val shell = folder.newFolder("touch-shell")
        File(shell, "surfaces.txt").writeText("""
            surface0 {
            collision0,0,0,20,20,Original
            animation0.interval,runonce
            animation0.pattern0,base,1001,50,0,0
            }
            surface1001 {
            collision0,0,0,20,20,Replacement
            }
        """.trimIndent())
        val engine = Engine { event -> if (event.id == "OnFirstBoot")
            ShioriReply(200, "\\_qHello\\e") else ShioriReply(204) }
        val runtime = started(engine, ghost.copy(shellDirectory = shell))
        runtime.click(0, 4, 5); runCurrent()
        assertEquals(listOf("4", "5", "0", "0", "Original", "0", "touch"),
            engine.events.last { it.id == "OnMouseClick" }.references)
        advanceTimeBy(50); runCurrent()
        runtime.doubleClick(0, 4, 5); runCurrent()
        assertEquals(listOf("4", "5", "0", "0", "Replacement", "0", "touch"),
            engine.events.last { it.id == "OnMouseDoubleClick" }.references)
    }

    @Test fun slowMoveKeepsOneRequestAndLatestFollowUpWithoutClearingDialogue() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<ShioriEvent>()
        val engine = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event
                if (event.id == "OnMouseMove" && events.count { it.id == "OnMouseMove" } == 1) gate.await()
                return if (event.id == "OnFirstBoot") ShioriReply(200, "\\q[Stay,id]Hello\\e")
                    else ShioriReply(204)
            }
        }
        val runtime = started(engine)
        val choice = runtime.ready.choices[0]!!.single().token
        runtime.move(0, 1, 1); advanceTimeBy(50); runCurrent()
        repeat(20) { runtime.move(0, it + 2, it + 3); advanceTimeBy(50); runCurrent() }
        assertEquals(1, events.count { it.id == "OnMouseMove" })
        assertEquals(choice, runtime.ready.choices[0]!!.single().token)
        gate.complete(Unit); runCurrent(); advanceTimeBy(50); runCurrent()
        assertEquals(2, events.count { it.id == "OnMouseMove" })
        assertEquals(listOf("21", "22", "0", "0", "", "0", "touch"),
            events.last { it.id == "OnMouseMove" }.references)
    }

    @Test fun pausedAndCancelledMovesNeverReplayTheirPendingPoint() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<ShioriEvent>()
        val engine = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event
                if (event.id == "OnMouseMove") gate.await()
                return ShioriReply(204)
            }
        }
        val runtime = started(engine)
        runtime.move(0, 1, 1); advanceTimeBy(50); runCurrent()
        runtime.move(0, 2, 2); runtime.cancelMove(0); runtime.move(0, 3, 3)
        runtime.setResumed(false); runCurrent()
        gate.complete(Unit); runCurrent()
        runtime.setResumed(true); advanceTimeBy(100); runCurrent()
        assertEquals(1, events.count { it.id == "OnMouseMove" })
    }

    @Test fun queuedMoveIsDiscardedWhenAnimationReplacesBaseBeforeTick() = runTest {
        val shell = folder.newFolder("moving-base")
        File(shell, "surfaces.txt").writeText("""
            surface0 {
            collision0,0,0,20,20,Old
            animation0.interval,runonce
            animation0.pattern0,base,1001,50,0,0
            }
            surface1001 {
            collision0,0,0,20,20,New
            }
        """.trimIndent())
        val engine = Engine { ShioriReply(204) }
        val runtime = started(engine, ghost.copy(shellDirectory = shell))
        runtime.move(0, 4, 5); runCurrent()
        advanceTimeBy(50); runCurrent()
        assertEquals(0, engine.events.count { it.id == "OnMouseMove" })
        runtime.move(0, 4, 5); runCurrent()
        advanceTimeBy(50); runCurrent()
        assertEquals("New", engine.events.single { it.id == "OnMouseMove" }.references[4])
    }

    @Test fun continuousOverlayAnimationDoesNotStarveCapturedDragMoves() = runTest {
        val shell = folder.newFolder("animated-drag")
        File(shell, "surfaces.txt").writeText("""
            surface0 {
            collision0,0,0,20,20,Body
            animation0.interval,always
            animation0.pattern0,overlay,1001,50,0,0
            animation0.pattern1,overlay,1002,50,0,0
            }
        """.trimIndent())
        val engine = Engine { ShioriReply(204) }
        val runtime = started(engine, ghost.copy(shellDirectory = shell))
        val source = sakuraSource(runtime.ready, 0, 1001, 1002)
        repeat(4) { index ->
            runtime.move(0, index + 1, index + 2, 0, source)
            advanceTimeBy(50); runCurrent()
        }
        assertEquals(listOf("1", "2", "3", "4"), engine.events.filter { it.id == "OnMouseMove" }
            .map { it.references[0] })
        assertTrue((runtime.ready.frame.sakura.visual?.layers?.single()?.surfaceId ?: 0) > 0)
        assertTrue(engine.events.filter { it.id == "OnMouseMove" }
            .all { it.references[4] == "Body" })
    }

    @Test fun otherCharacterSurfaceChangeDoesNotDropCapturedDragMoves() = runTest {
        val engine = Engine { event -> if (event.id == "OnFirstBoot")
            ShioriReply(200, "\\1\\s[10]\\_w[100]\\s[11]\\e") else ShioriReply(204) }
        val runtime = started(engine)
        val source = sakuraSource(runtime.ready, 0)
        repeat(4) { index ->
            runtime.move(0, index + 1, index + 2, 0, source)
            advanceTimeBy(50); runCurrent()
        }
        assertEquals(11, runtime.ready.frame.kero.surfaceId)
        assertEquals(listOf("1", "2", "3", "4"), engine.events.filter { it.id == "OnMouseMove" }
            .map { it.references[0] })
    }

    @Test fun protrudingOverlayInvalidatesQueuedRenderedMove() = runTest {
        val shell = folder.newFolder("protruding-overlay")
        File(shell, "surfaces.txt").writeText("""
            surface0 {
            collision0,0,0,20,20,Body
            animation0.interval,runonce
            animation0.pattern0,overlay,1001,50,-10,0
            }
        """.trimIndent())
        val engine = Engine { ShioriReply(204) }
        val runtime = started(engine, ghost.copy(shellDirectory = shell))
        runtime.move(0, 4, 5, 0, sakuraSource(runtime.ready, 0, 1001))
        advanceTimeBy(50); runCurrent()
        assertEquals(0, engine.events.count { it.id == "OnMouseMove" })
    }

    @Test fun cancelledDragHasNoFollowUpAfterSlowMoveCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<ShioriEvent>()
        val engine = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event
                if (event.id == "OnMouseMove") gate.await()
                return ShioriReply(204)
            }
        }
        val runtime = started(engine)
        runtime.move(0, 1, 1); advanceTimeBy(50); runCurrent()
        runtime.move(0, 2, 2); runtime.cancelMove(0); runCurrent()
        gate.complete(Unit); runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(1, events.count { it.id == "OnMouseMove" })
    }

    @Test fun switchDropsQueuedMoveAndLateReply() = runTest {
        val root = folder.newFolder("switch-move")
        File(root, "other/ghost/master").mkdirs()
        File(root, "other/shell/master").mkdirs()
        File(root, "other/ghost/master/en").mkdirs()
        File(root, "other/ghost/master/en/content.txt").writeText("OnFirstBoot,\\e")
        File(root, "other/ghost/master/descript.txt").writeText(
            "name,Other\r\nsakura.name,Other\r\nshiori,Nanidroid\r\n")
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<ShioriEvent>()
        val engine = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event
                if (event.id == "OnMouseMove") gate.await()
                return ShioriReply(204)
            }
        }
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            installedGhosts = InstalledGhostRepository(root),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        runtime.move(0, 1, 1); advanceTimeBy(50); runCurrent()
        runtime.move(0, 2, 2); runCurrent()
        runtime.selectGhost("other"); runCurrent()
        assertEquals("other", runtime.ready.switchPrompt?.directoryId)
        runtime.confirmSwitch(); runCurrent()
        gate.complete(Unit); runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(1, events.count { it.id == "OnMouseMove" })
        assertEquals("Other", runtime.ready.ghostName)
    }

    @Test fun displayedFallbackCollisionOverridesMissingLogicalSurface() = runTest {
        val shell = folder.newFolder("fallback-collision")
        File(shell, "surfaces.txt").writeText("""
            surface0 {
            collision0,0,0,20,20,FallbackBody
            }
            surface42 {
            collision0,0,0,20,20,InvisibleBody
            }
        """.trimIndent())
        val engine = Engine { event -> if (event.id == "OnFirstBoot")
            ShioriReply(200, "\\s[42]\\_qReady\\e") else ShioriReply(204) }
        val runtime = started(engine, ghost.copy(shellDirectory = shell))
        runtime.click(0, 5, 5, 0, runtime.ready.dialogueToken); runCurrent()
        assertEquals("FallbackBody", engine.events.single { it.id == "OnMouseClick" }.references[4])
    }

    @Test fun busyDispatcherConflatesPointerSamplesBeforeScheduling() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var dispatches = 0
        val counting = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatches++
                dispatcher.dispatch(context, block)
            }
        }
        val engine = Engine { ShioriReply(204) }
        val runtime = GhostRuntime({ ghost }, { engine }, Store(),
            CoroutineScope(backgroundScope.coroutineContext + counting),
            elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        dispatches = 0
        repeat(100) { runtime.move(0, it, it + 1) }
        assertEquals(0, dispatches)
        runCurrent()
        assertEquals(0, dispatches)
    }

    @Test fun oldRenderedMoveAfterMoveOnlyVisualChangeIsNotReinterpreted() = runTest {
        val shell = folder.newFolder("moved-visual-race")
        File(shell, "surfaces.txt").writeText("""
            surface0 {
            collision0,0,0,20,20,Body
            animation0.interval,runonce
            animation0.pattern0,move,0,50,4,-3
            }
        """.trimIndent())
        val engine = Engine { ShioriReply(204) }
        val runtime = started(engine, ghost.copy(shellDirectory = shell))
        val before = runtime.state.value as StageState.Ready
        val old = before.frame.sakura
        val oldSource = RenderedMoveSource(before.dialogueToken, old.surfaceId, old.visual)
        advanceTimeBy(50); runCurrent()
        val after = runtime.state.value as StageState.Ready
        val current = after.frame.sakura
        assertNotEquals(old.visual, current.visual)
        assertEquals(old.visual?.baseSurfaceId, current.visual?.baseSurfaceId)
        runtime.move(0, 4, 5, 0, oldSource)
        advanceTimeBy(50); runCurrent()
        assertEquals(0, engine.events.count { it.id == "OnMouseMove" })
        runtime.move(0, 4, 5, 0,
            RenderedMoveSource(after.dialogueToken, current.surfaceId, current.visual))
        advanceTimeBy(50); runCurrent()
        assertEquals(1, engine.events.count { it.id == "OnMouseMove" })
    }

    @Test fun pausedTouchDoesNotClearDialogueOrRequestNativeEvent() = runTest {
        val engine = Engine { event -> if (event.id == "OnFirstBoot")
            ShioriReply(200, "\\_qReady\\e") else ShioriReply(204) }
        val runtime = started(engine)
        val before = runtime.state.value as StageState.Ready
        runtime.setResumed(false); runCurrent()
        runtime.click(0, 4, 5)
        runtime.doubleClick(0, 4, 5)
        runCurrent()
        assertEquals(0, engine.events.count {
            it.id == "OnMouseClick" || it.id == "OnMouseDoubleClick" })
        assertEquals(before.frame.sakura.text,
            runtime.ready.frame.sakura.text)
        assertEquals(before.dialogueToken,
            runtime.ready.dialogueToken)
    }

    @Test fun staleResolvedTapCannotClearReplacementSession() = runTest {
        val root = folder.newFolder("stale-touch-session")
        File(root, "other/ghost/master").mkdirs()
        File(root, "other/shell/master").mkdirs()
        File(root, "other/ghost/master/en").mkdirs()
        File(root, "other/ghost/master/en/content.txt").writeText("OnFirstBoot,\\e")
        File(root, "other/ghost/master/descript.txt").writeText(
            "name,Other\r\nsakura.name,Other\r\nshiori,Nanidroid\r\n")
        val engine = Engine { ShioriReply(204) }
        val runtime = GhostRuntime({ ghost }, { engine }, Store(), backgroundScope,
            installedGhosts = InstalledGhostRepository(root),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        val oldToken = runtime.ready.dialogueToken!!
        runtime.selectGhost("other"); runCurrent()
        runtime.confirmSwitch(); runCurrent()
        val current = runtime.state.value as StageState.Ready
        assertEquals("Other", current.ghostName)
        assertNotEquals(oldToken.sessionId, current.dialogueToken!!.sessionId)
        runtime.click(0, 4, 5, 0, oldToken)
        runtime.doubleClick(0, 4, 5, 0, oldToken)
        runCurrent()
        assertEquals(0, engine.events.count {
            it.id == "OnMouseClick" || it.id == "OnMouseDoubleClick" })
        assertEquals(current.dialogueToken,
            runtime.ready.dialogueToken)
        runtime.click(0, 4, 5, 0, current.dialogueToken)
        runCurrent()
        assertEquals(1, engine.events.count { it.id == "OnMouseClick" })
        val afterClick = runtime.state.value as StageState.Ready
        assertEquals(current.dialogueToken.sessionId, afterClick.dialogueToken!!.sessionId)
        assertNotEquals(current.dialogueToken.dialogueId, afterClick.dialogueToken.dialogueId)
        runtime.doubleClick(0, 4, 5, 0, current.dialogueToken)
        runCurrent()
        assertEquals(0, engine.events.count { it.id == "OnMouseDoubleClick" })
        assertEquals(afterClick.dialogueToken,
            runtime.ready.dialogueToken)
    }
}
