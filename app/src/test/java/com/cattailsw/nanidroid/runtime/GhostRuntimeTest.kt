package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.engine.ShioriEngine
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.ghost.BundledGhost
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import com.cattailsw.nanidroid.engine.NativeAvailability
import com.cattailsw.nanidroid.engine.NativeKind
import com.cattailsw.nanidroid.engine.NativeLease
import com.cattailsw.nanidroid.engine.NativeLoadResult
import com.cattailsw.nanidroid.engine.NativeUnloadResult
import com.cattailsw.nanidroid.engine.NativeShutdownResult
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GhostRuntimeTest {
    @get:Rule val folder = TemporaryFolder()
    private val ghost = BundledGhost("nanidroid", "Nanidroid", "Sakura", "Kero", mapOf(0 to File("sakura.png"), 10 to File("kero.png")), "")

    @Test fun firstLaunchOnboardingExplainsStageControls() = runTest {
        var now = 0L
        val runtime = GhostRuntime(
            loadGhost = { ghost }, engineFactory = { ScriptEngine("") },
            bootState = FakeStore(), scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler), elapsedRealtime = { now },
        )
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        now = 5_000L
        advanceTimeBy(50); runCurrent()
        assertTrue((runtime.state.value as StageState.Ready).frame.sakura.text
            .contains("Tap empty stage for Ghosts and About"))
    }

    @Test fun enqueuedScriptSubstitutesNamesExactlyOnce() = runTest {
        val named = ghost.copy(sakuraName = "First", sakuraName2 = "Second", keroName = "Kero")
        val runtime = GhostRuntime(
            loadGhost = { named }, engineFactory = { ScriptEngine("\\_q%username|%selfname2|%selfname|%keroname\\e") },
            bootState = FakeStore(onboarding = false), scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler), elapsedRealtime = { 0L },
        )
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        assertEquals("User|Second|First|Kero", (runtime.state.value as StageState.Ready).frame.sakura.text)
    }

    @Test fun idleAlwaysAnimationPublishesOnlyWhenDueAndPauseKeepsRemainingWait() = runTest {
        val shell = folder.newFolder("timed-shell")
        File(shell, "surfaces.txt").writeText("""
            surface0 {
            animation0.interval,always
            animation0.pattern0,overlay,1001,100,0,0
            }
        """.trimIndent())
        val timedGhost = ghost.copy(shellDirectory = shell)
        var now = 0L
        val runtime = GhostRuntime(
            loadGhost = { timedGhost }, engineFactory = { ScriptEngine("") },
            bootState = FakeStore(onboarding = false), scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler), elapsedRealtime = { now },
        )
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        val initial = runtime.state.value as StageState.Ready
        now = 50
        advanceTimeBy(50); runCurrent()
        assertSame(initial, runtime.state.value)
        runtime.setResumed(false); runCurrent()
        now = 10_000
        advanceTimeBy(500); runCurrent()
        assertSame(initial, runtime.state.value)
        runtime.setResumed(true); runCurrent()
        now = 10_050
        advanceTimeBy(50); runCurrent()
        val due = runtime.state.value as StageState.Ready
        assertEquals(1001, due.frame.sakura.visual?.layers?.single()?.surfaceId)
        now = 10_100
        advanceTimeBy(50); runCurrent()
        assertSame(due, runtime.state.value)
    }

    @Test fun animatedFramesReuseFilteredChoicesAndNativeEligibilityRefreshesThem() = runTest {
        val shell = folder.newFolder("choice-shell")
        File(shell, "surfaces.txt").writeText("""
            surface0 {
            animation0.interval,always
            animation0.pattern0,overlay,1001,0,0,0
            animation0.pattern1,overlay,1002,5000,0,0
            }
        """.trimIndent())
        val root = folder.newFolder("choice-root")
        File(root, "native/ghost/master").mkdirs()
        File(root, "native/shell/master").mkdirs()
        File(root, "native/ghost/master/descript.txt").writeText("name,Native\nshiori,satori.dll\n")
        val native = object : NativePort {
            override val availability = MutableStateFlow(NativeAvailability.Available)
            override suspend fun load(kind: NativeKind, directory: File, libraryName: String): NativeLoadResult =
                error("No native load expected")
            override suspend fun request(lease: NativeLease, event: ShioriEvent): ShioriReply =
                error("No native request expected")
            override suspend fun unload(lease: NativeLease): NativeUnloadResult =
                error("No native unload expected")
            override suspend fun shutdown(lease: NativeLease, notifyDestroy: Boolean,
                timeoutMillis: Long): NativeShutdownResult = error("No native shutdown expected")
        }
        var now = 0L
        val runtime = GhostRuntime(
            loadGhost = { ghost.copy(shellDirectory = shell) },
            engineFactory = { ScriptEngine("") }, bootState = FakeStore(onboarding = false),
            scope = backgroundScope, installedGhosts = InstalledGhostRepository(root),
            nativeHost = native, ioDispatcher = StandardTestDispatcher(testScheduler),
            elapsedRealtime = { now },
        )
        runtime.start("en")
        runtime.setResumed(true); runCurrent()
        val initial = runtime.state.value as StageState.Ready
        assertEquals(1, initial.installedGhosts.size)
        now = 50; advanceTimeBy(50); runCurrent()
        val animated = runtime.state.value as StageState.Ready
        assertSame(initial.installedGhosts, animated.installedGhosts)
        native.availability.value = NativeAvailability.Quarantined
        now = 100; advanceTimeBy(50); runCurrent()
        assertTrue((runtime.state.value as StageState.Ready).installedGhosts.isEmpty())
        native.availability.value = NativeAvailability.Available
        now = 150; advanceTimeBy(50); runCurrent()
        assertEquals(1, (runtime.state.value as StageState.Ready).installedGhosts.size)
    }

    @Test fun foreverAnimationDoesNotHoldCloseDialogueOpen() = runTest {
        val shell = folder.newFolder("closing-shell")
        File(shell, "surfaces.txt").writeText("""
            surface0 {
            animation0.interval,always
            animation0.pattern0,overlay,1001,0,0,0
            }
        """.trimIndent())
        val runtime = GhostRuntime(
            loadGhost = { ghost.copy(shellDirectory = shell) },
            engineFactory = { ScriptEngine("") }, bootState = FakeStore(onboarding = false),
            scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler),
            elapsedRealtime = { 0L },
        )
        runtime.start("en")
        runtime.setResumed(true); runCurrent()
        runtime.close(); runCurrent()
        assertEquals(StageState.Finished, runtime.state.value)
    }

    @Test fun foreverAnimationDoesNotHoldGhostSwitchOpen() = runTest {
        val shell = folder.newFolder("switching-shell")
        File(shell, "surfaces.txt").writeText("""
            surface0 {
            animation0.interval,always
            animation0.pattern0,overlay,1001,0,0,0
            }
        """.trimIndent())
        val root = folder.newFolder("switching-root")
        File(root, "candidate/ghost/master").mkdirs()
        File(root, "candidate/shell/master").mkdirs()
        File(root, "candidate/ghost/master/descript.txt")
            .writeText("name,Other\nshiori,Unsupported.dll\n")
        val runtime = GhostRuntime(
            loadGhost = { ghost.copy(shellDirectory = shell) },
            engineFactory = { ScriptEngine("") }, bootState = FakeStore(onboarding = false),
            scope = backgroundScope, installedGhosts = InstalledGhostRepository(root),
            ioDispatcher = StandardTestDispatcher(testScheduler), elapsedRealtime = { 0L },
        )
        runtime.start("en")
        runtime.setResumed(true); runCurrent()
        runtime.selectGhost("candidate"); runCurrent()
        assertEquals("candidate", (runtime.state.value as StageState.Ready).switchPrompt?.directoryId)
        runtime.confirmSwitch(); runCurrent()
        assertEquals("Other", (runtime.state.value as StageState.Ready).ghostName)
    }

    @Test fun talkAnimationUsesTextAdvanceEdgeWhenFinalTextMatchesPreviousFrame() = runTest {
        val shell = folder.newFolder("talk-shell")
        File(shell, "surfaces.txt").writeText("""
            surface0 {
            animation0.interval,talk
            animation0.pattern0,overlay,1001,0,0,0
            }
        """.trimIndent())
        var now = 0L
        val events = mutableListOf<String>()
        val script = "\\0" + "A\\c".repeat(10) + "\\e"
        val runtime = GhostRuntime(
            loadGhost = { ghost.copy(shellDirectory = shell) },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    events += event.id
                    return if (event.id == "OnFirstBoot") ShioriReply(200, script) else ShioriReply(204)
                }
            } }, bootState = FakeStore(onboarding = false),
            scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler),
            elapsedRealtime = { now },
        )
        runtime.start("en")
        runtime.setResumed(true); runCurrent()
        now = 500; advanceTimeBy(50); runCurrent()
        val triggered = runtime.state.value as StageState.Ready
        assertEquals("", triggered.frame.sakura.text)
        assertTrue(triggered.frame.sakura.visual?.layers?.isEmpty() == true)
        now = 550; advanceTimeBy(50); runCurrent()
        val ready = runtime.state.value as StageState.Ready
        assertEquals(1001, ready.frame.sakura.visual?.layers?.single()?.surfaceId)
        now = 600; advanceTimeBy(50); runCurrent()
        assertSame(ready, runtime.state.value)
        assertFalse(events.contains("OnSurfaceChange"))
    }

    @Test fun queuedDialogueSurfaceGetsFreshAnimationWaitWhenPreviousDialogueEnds() = runTest {
        val shell = folder.newFolder("queued-surface-shell")
        File(shell, "surfaces.txt").writeText("""
            surface1 {
            animation0.interval,runonce
            animation0.pattern0,overlay,1001,500,0,0
            }
        """.trimIndent())
        var now = 0L
        val runtime = GhostRuntime(
            loadGhost = { ghost.copy(shellDirectory = shell) },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent) = when (event.id) {
                    "OnFirstBoot" -> ShioriReply(200, "\\0\\e")
                    "OnInstallBegin" -> ShioriReply(200, "\\0\\s[1]\\e")
                    else -> ShioriReply(204)
                }
            } },
            bootState = FakeStore(onboarding = false), scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler), elapsedRealtime = { now },
        )
        runtime.start("en")
        runtime.beginImportEvents()!!.emit("OnInstallBegin")
        runCurrent()
        runtime.setResumed(true); runCurrent()
        now = 1_000; advanceTimeBy(50); runCurrent()
        val entered = runtime.state.value as StageState.Ready
        assertEquals(1, entered.frame.sakura.surfaceId)
        assertTrue(entered.frame.sakura.visual?.layers?.isEmpty() == true)
        now = 1_499; advanceTimeBy(50); runCurrent()
        assertTrue((runtime.state.value as StageState.Ready).frame.sakura.visual?.layers?.isEmpty() == true)
        now = 1_500; advanceTimeBy(50); runCurrent()
        assertEquals(1001, (runtime.state.value as StageState.Ready).frame.sakura.visual?.layers?.single()?.surfaceId)
    }

    @Test fun delayedTickerUsesElapsedTimeToRevealSeveralCharacters() = runTest {
        var now = 0L
        val runtime = GhostRuntime(
            loadGhost = { ghost },
            engineFactory = { ScriptEngine("\\0ABCDEFGHIJ\\e") },
            bootState = FakeStore(onboarding = false),
            scope = backgroundScope,
            elapsedRealtime = { now },
        )
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        now = 250L
        advanceTimeBy(50); runCurrent()
        assertEquals("ABCDE", (runtime.state.value as StageState.Ready).frame.sakura.text)
        now = 300L
        advanceTimeBy(50); runCurrent()
        assertEquals("ABCDEF", (runtime.state.value as StageState.Ready).frame.sakura.text)
    }

    @Test fun replacementDialogueOnlyReceivesTimeAfterItsReplyArrives() = runTest {
        var now = 0L
        val runtime = GhostRuntime(
            loadGhost = { ghost },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply = when (event.id) {
                    "OnFirstBoot" -> ShioriReply(200, "\\0Old dialogue\\e")
                    "OnMouseClick" -> ShioriReply(200, "\\0ABCDEFGHIJ\\e")
                    else -> ShioriReply(204)
                }
            } },
            bootState = FakeStore(onboarding = false),
            scope = backgroundScope,
            elapsedRealtime = { now },
        )
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        now = 200L
        runtime.click(0, 1, 2)
        runCurrent()
        assertEquals("", (runtime.state.value as StageState.Ready).frame.sakura.text)
        now = 250L
        advanceTimeBy(50); runCurrent()
        assertEquals("A", (runtime.state.value as StageState.Ready).frame.sakura.text)
    }

    @Test fun resumeStartsNewElapsedBaselineWithoutPlayingPausedTime() = runTest {
        var now = 0L
        val runtime = GhostRuntime(
            loadGhost = { ghost },
            engineFactory = { ScriptEngine("\\0ABCDEFGHIJ\\e") },
            bootState = FakeStore(onboarding = false),
            scope = backgroundScope,
            elapsedRealtime = { now },
        )
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        now = 100L
        advanceTimeBy(50); runCurrent()
        assertEquals("AB", (runtime.state.value as StageState.Ready).frame.sakura.text)
        runtime.setResumed(false)
        runCurrent()
        now = 10_100L
        advanceTimeBy(50); runCurrent()
        assertEquals("AB", (runtime.state.value as StageState.Ready).frame.sakura.text)
        runtime.setResumed(true)
        runCurrent()
        now = 10_150L
        advanceTimeBy(50); runCurrent()
        assertEquals("ABC", (runtime.state.value as StageState.Ready).frame.sakura.text)
    }

    @Test fun delayedTickerSpendsAuthoredWaitBeforeRevealingNextCharacter() = runTest {
        var now = 0L
        val runtime = GhostRuntime(
            loadGhost = { ghost },
            engineFactory = { ScriptEngine("\\0A\\_w[500]B\\e") },
            bootState = FakeStore(onboarding = false),
            scope = backgroundScope,
            elapsedRealtime = { now },
        )
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        now = 550L
        advanceTimeBy(50); runCurrent()
        assertEquals("A", (runtime.state.value as StageState.Ready).frame.sakura.text)
        now = 600L
        advanceTimeBy(50); runCurrent()
        assertEquals("AB", (runtime.state.value as StageState.Ready).frame.sakura.text)
    }

    @Test fun overlappingStartsJoinAndOnlyFirstActivationBootsOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore()
        val events = mutableListOf<String>()
        var loads = 0
        var engines = 0
        val runtime = GhostRuntime(
            loadGhost = { loads++; gate.await(); ghost },
            engineFactory = { engines++; RecordingEngine(events) },
            bootState = store,
            scope = backgroundScope,
            elapsedRealtime = { 0L },
        )
        val first = async { runtime.start("en") }
        val second = async { runtime.start("en") }
        runCurrent()
        assertEquals(1, loads)
        gate.complete(Unit)
        first.await(); second.await()
        assertEquals(1, loads)
        assertEquals(1, engines)
        assertEquals(1, store.activations)
        assertEquals(listOf("OnFirstBoot"), events)
        assertTrue(runtime.state.value is StageState.Ready)

        val laterEvents = mutableListOf<String>()
        GhostRuntime({ ghost }, { RecordingEngine(laterEvents) }, store, backgroundScope) { 0L }.start("en")
        assertEquals(listOf("OnBoot"), laterEvents)
    }

    @Test fun pauseFreezesPartialTextAndResumeDoesNotCatchUp() = runTest {
        val events = mutableListOf<String>()
        val runtime = GhostRuntime({ ghost }, { RecordingEngine(events) }, FakeStore(onboarding = false), backgroundScope) { testScheduler.currentTime }
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        advanceTimeBy(50); runCurrent()
        val partial = (runtime.state.value as StageState.Ready).frame
        assertEquals("H", partial.sakura.text)
        runtime.setResumed(false)
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(partial, (runtime.state.value as StageState.Ready).frame)
        runtime.setResumed(true)
        runCurrent()
        advanceTimeBy(50); runCurrent()
        assertEquals("Hi", (runtime.state.value as StageState.Ready).frame.sakura.text)
        assertEquals(1, events.count { it == "OnFirstBoot" })
    }

    @Test fun unknownTouchClearsDialogueAndCloseWaitsForReply() = runTest {
        val events = mutableListOf<String>()
        val runtime = GhostRuntime({ ghost }, { RecordingEngine(events) }, FakeStore(onboarding = false), backgroundScope) { testScheduler.currentTime }
        runtime.start("en")
        runtime.setResumed(true); runCurrent()
        runtime.click(0, 3, 4)
        runCurrent()
        assertEquals("OnMouseClick", events.last())
        assertEquals("", (runtime.state.value as StageState.Ready).frame.sakura.text)
        runtime.setResumed(false); runCurrent()
        runtime.close()
        runCurrent()
        assertEquals("OnClose", events.last())
        assertTrue(runtime.state.value is StageState.Ready)
        runtime.setResumed(true)
        runCurrent()
        advanceTimeBy(1_500); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
    }

    @Test fun failedLoadShowsErrorInsteadOfLoadingForever() = runTest {
        val runtime = GhostRuntime(
            loadGhost = { throw IllegalStateException("asset unreadable") },
            engineFactory = { RecordingEngine(mutableListOf()) },
            bootState = FakeStore(),
            scope = backgroundScope,
        ) { 0L }
        runtime.start("en")
        assertEquals("asset unreadable", (runtime.state.value as StageState.Error).message)
    }

    @Test fun clockEventsOnlyRunWhileResumedAndUseUptimeHours() = runTest {
        val calls = mutableListOf<ShioriEvent>()
        val runtime = GhostRuntime(
            loadGhost = { ghost },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    calls += event
                    return ShioriReply(204)
                }
            } },
            bootState = FakeStore(onboarding = false),
            scope = backgroundScope,
        ) { 7_200_000L + testScheduler.currentTime }
        runtime.start("en")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, calls.count { it.id == "OnSecondChange" })
        runtime.setResumed(true)
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(60, calls.count { it.id == "OnSecondChange" })
        assertEquals(1, calls.count { it.id == "OnMinuteChange" })
        assertEquals(listOf("2", "0", "0", "1"), calls.last().references)
        runtime.setResumed(false)
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(60, calls.count { it.id == "OnSecondChange" })
        runtime.setResumed(true)
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(61, calls.count { it.id == "OnSecondChange" })
    }

    @Test fun pausesDelayButDoNotRestartTheMinute() = runTest {
        val calls = mutableListOf<String>()
        val runtime = GhostRuntime(
            loadGhost = { ghost },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    calls += event.id
                    return ShioriReply(204)
                }
            } },
            bootState = FakeStore(onboarding = false),
            scope = backgroundScope,
        ) { testScheduler.currentTime }
        runtime.start("en")
        repeat(2) {
            runtime.setResumed(true)
            runCurrent()
            advanceTimeBy(40_000); runCurrent()
            runtime.setResumed(false)
            runCurrent()
            advanceTimeBy(30_000); runCurrent()
        }
        assertEquals(80, calls.count { it == "OnSecondChange" })
        assertEquals(1, calls.count { it == "OnMinuteChange" })
    }

    @Test fun delayedTickerCoalescesMissedSecondsButStillDispatchesMinute() = runTest {
        var now = 7_200_000L
        val calls = mutableListOf<ShioriEvent>()
        val runtime = GhostRuntime(
            loadGhost = { ghost },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    calls += event
                    return ShioriReply(204)
                }
            } },
            bootState = FakeStore(onboarding = false),
            scope = backgroundScope,
            elapsedRealtime = { now },
        )
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        now += 60_000
        advanceTimeBy(50); runCurrent()
        assertEquals(1, calls.count { it.id == "OnSecondChange" })
        assertEquals(1, calls.count { it.id == "OnMinuteChange" })
        assertEquals(listOf("2", "0", "0", "1"), calls.last().references)
    }

    @Test fun secondReplyStartingPlaybackDoesNotDiscardConcurrentMinuteReply() = runTest {
        val minute = CompletableDeferred<ShioriReply>()
        var now = 0L
        var secondReplies = 0
        val runtime = GhostRuntime(
            loadGhost = { ghost },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply = when (event.id) {
                    "OnSecondChange" -> if (secondReplies++ == 0) ShioriReply(200, "\\0Second\\e") else ShioriReply(204)
                    "OnMinuteChange" -> minute.await()
                    else -> ShioriReply(204)
                }
            } },
            bootState = FakeStore(onboarding = false), scope = backgroundScope,
            elapsedRealtime = { now },
        )
        runtime.start("en")
        runtime.setResumed(true); runCurrent()
        val before = (runtime.state.value as StageState.Ready).dialogueToken!!.dialogueId
        now = 60_000
        advanceTimeBy(50); runCurrent()
        assertTrue((runtime.state.value as StageState.Ready).dialogueToken!!.dialogueId > before)
        minute.complete(ShioriReply(200, "\\0Minute\\e")); runCurrent()
        repeat(80) {
            now += 50
            advanceTimeBy(50); runCurrent()
        }
        assertEquals("Minute", (runtime.state.value as StageState.Ready).frame.sakura.text)
    }

    @Test fun slowClockRequestDoesNotAccumulateSecondRequests() = runTest {
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val runtime = GhostRuntime(
            loadGhost = { ghost },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    if (event.id == "OnSecondChange") {
                        calls += event.id
                        release.await()
                    }
                    return ShioriReply(204)
                }
            } },
            bootState = FakeStore(onboarding = false),
            scope = backgroundScope,
            elapsedRealtime = { testScheduler.currentTime },
        )
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("OnSecondChange"), calls)
        release.complete(Unit)
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, calls.size)
    }

    @Test fun oldPendingClockRequestDoesNotBlockNewSession() = runTest {
        val oldRequest = CompletableDeferred<Unit>()
        val secondCalls = mutableListOf<Int>()
        var activation = 0
        val runtime = GhostRuntime(
            loadGhost = { ghost },
            engineFactory = {
                object : ShioriEngine {
                    override suspend fun request(event: ShioriEvent): ShioriReply {
                        if (event.id == "OnFirstBoot" || event.id == "OnBoot") activation++
                        if (event.id == "OnSecondChange") {
                            secondCalls += activation
                            if (activation == 1) oldRequest.await()
                        }
                        return ShioriReply(204)
                    }
                }
            },
            bootState = FakeStore(onboarding = false),
            scope = backgroundScope,
            elapsedRealtime = { testScheduler.currentTime },
        )
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(1), secondCalls)

        runtime.close()
        runCurrent()
        runtime.start("en")
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(1, 2), secondCalls)
    }

    @Test fun startAfterCompletedCloseBeginsNewActivationWithOnBoot() = runTest {
        val store = FakeStore(onboarding = false)
        val events = mutableListOf<String>()
        var loads = 0
        var engines = 0
        val runtime = GhostRuntime(
            loadGhost = { loads++; ghost },
            engineFactory = { engines++; RecordingEngine(events) },
            bootState = store,
            scope = backgroundScope,
        ) { testScheduler.currentTime }
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        runtime.close()
        runCurrent()
        advanceTimeBy(1_500); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        runtime.start("en")
        val relaunched = runtime.state.value as StageState.Ready
        assertEquals("", relaunched.frame.sakura.text)
        assertEquals(listOf("OnFirstBoot", "OnClose", "OnBoot"), events.filter { it != "OnSecondChange" })
        assertEquals(2, store.activations)
        assertEquals(1, loads)
        assertEquals(1, engines)
        runtime.start("en") // recreation joins the ready activation
        assertEquals(2, store.activations)
    }

    @Test fun closeFromErrorFinishesWithoutEngine() = runTest {
        val runtime = GhostRuntime(
            loadGhost = { throw IllegalStateException("asset unreadable") },
            engineFactory = { RecordingEngine(mutableListOf()) },
            bootState = FakeStore(),
            scope = backgroundScope,
        ) { 0L }
        runtime.start("en")
        assertTrue(runtime.state.value is StageState.Error)
        runtime.close()
        runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
    }

    @Test fun closeReplyWithoutExplicitEndStillFinishes() = runTest {
        val runtime = GhostRuntime(
            loadGhost = { ghost },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply = when (event.id) {
                    "OnClose" -> ShioriReply(200, "\\0Bye")
                    else -> ShioriReply(204)
                }
            } },
            bootState = FakeStore(onboarding = false),
            scope = backgroundScope,
        ) { testScheduler.currentTime }
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        runtime.close()
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
    }

    @Test fun closeWhileLoadingFinishesAndLateLoadCannotResurrectStage() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore()
        val events = mutableListOf<String>()
        val runtime = GhostRuntime(
            loadGhost = { gate.await(); ghost },
            engineFactory = { RecordingEngine(events) },
            bootState = store,
            scope = backgroundScope,
        ) { 0L }
        val pending = async { runtime.start("en") }
        runCurrent()
        assertTrue(runtime.state.value is StageState.Loading)
        runtime.close()
        runCurrent()
        val stateAfterClose = runtime.state.value
        gate.complete(Unit)
        pending.await()
        assertTrue(stateAfterClose is StageState.Finished)
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(0, store.activations)
        assertTrue(events.isEmpty())
    }

    @Test fun relaunchStartsBeforeAbandonedLoadCompletes() = runTest {
        val firstLoad = CompletableDeferred<Unit>()
        val store = FakeStore(onboarding = false)
        val events = mutableListOf<String>()
        var loads = 0
        val runtime = GhostRuntime(
            loadGhost = {
                loads++
                if (loads == 1) firstLoad.await()
                ghost
            },
            engineFactory = { RecordingEngine(events) },
            bootState = store,
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        val abandoned = async { runtime.start("en") }
        runCurrent()
        runtime.close()
        runCurrent()
        val relaunched = async { runtime.start("en") }
        runCurrent()
        val beforeOldLoadCompletes = runtime.state.value
        firstLoad.complete(Unit)
        abandoned.await()
        relaunched.await()
        assertTrue(beforeOldLoadCompletes is StageState.Ready)
        assertTrue(runtime.state.value is StageState.Ready)
        assertEquals(2, loads)
        assertEquals(1, store.activations)
        assertEquals(listOf("OnFirstBoot"), events)
    }

    private class FakeStore(var onboarding: Boolean = true) : BootStateStore {
        var activations = 0
        override suspend fun recordActivation(directoryId: String): Boolean = activations++ == 0
        override suspend fun consumeOnboarding(): Boolean = onboarding.also { onboarding = false }
    }

    private class RecordingEngine(private val events: MutableList<String>) : ShioriEngine {
        override suspend fun request(event: ShioriEvent): ShioriReply {
            events += event.id
            return when (event.id) {
                "OnFirstBoot", "OnBoot" -> ShioriReply(200, "\\0\\s0Hi\\e")
                "OnClose" -> ShioriReply(200, "\\0Bye\\e")
                else -> ShioriReply(204)
            }
        }
    }

    private class ScriptEngine(private val script: String) : ShioriEngine {
        override suspend fun request(event: ShioriEvent): ShioriReply =
            if (event.id == "OnFirstBoot") ShioriReply(200, script) else ShioriReply(204)
    }
}
