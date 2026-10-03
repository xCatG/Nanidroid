package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.data.LastGhostStore
import com.cattailsw.nanidroid.engine.ShioriEngine
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.engine.ShioriMethod
import com.cattailsw.nanidroid.engine.NativeAvailability
import com.cattailsw.nanidroid.engine.NativeKind
import com.cattailsw.nanidroid.engine.NativeLease
import com.cattailsw.nanidroid.engine.NativeLoadResult
import com.cattailsw.nanidroid.engine.NativeUnloadResult
import com.cattailsw.nanidroid.engine.NativeShutdownResult
import com.cattailsw.nanidroid.engine.NativeTransportException
import com.cattailsw.nanidroid.ghost.BundledGhost
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import com.cattailsw.nanidroid.ghost.ShellCatalog
import com.cattailsw.nanidroid.ghost.SurfaceDefinitions
import com.cattailsw.nanidroid.ui.StageViewModel
import java.io.File
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GhostSwitchTest {
    @get:Rule val folder = TemporaryFolder()

    private val GhostRuntime.ready get() = state.value as StageState.Ready

    @Test fun switchChoiceWaitsForOutgoingAnswerThenUnloadsOnce() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        fixture(root, "nanidroid", "Nanidroid", "Nanidroid")
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            changingReply = ShioriReply(200, "\\q[Leave,chosen]\\e")
            actionReply = ShioriReply(200, "\\_qAnswer\\e")
        }
        val runtime = GhostRuntime({ bundled() }, { SilentEngine },
            MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port,
            ioDispatcher = StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        runtime.selectGhost("nanidroid"); runCurrent()
        runtime.confirmSwitch(); runCurrent()
        val token = runtime.ready.choices[0]!!.single().token
        advanceTimeBy(100); runCurrent()
        assertEquals(0, port.unloadCalls)
        runtime.choose(token); runtime.choose(token); runCurrent()
        assertEquals(listOf("chosen"), port.events.single { it.id == "OnChoiceSelect" }.references)
        assertEquals("Answer", runtime.ready.frame.sakura.text)
        advanceTimeBy(1_500); runCurrent()
        assertEquals("Nanidroid", runtime.ready.ghostName)
        assertEquals(1, port.unloadCalls)
        assertEquals(listOf("request:OnGhostChanging", "request:OnChoiceSelect", "shutdown:true", "unload"),
            port.operations.takeLast(4))
    }

    @Test fun switchInputCancelFinishesRemainderWithoutUserInputEvent() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        fixture(root, "nanidroid", "Nanidroid", "Nanidroid")
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            changingReply = ShioriReply(200, "\\_qBefore\\![open,inputbox,box]After\\e")
        }
        val runtime = GhostRuntime({ bundled() }, { SilentEngine },
            MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port,
            ioDispatcher = StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        runtime.selectGhost("nanidroid"); runCurrent()
        runtime.confirmSwitch(); runCurrent()
        val token = runtime.ready.input!!.token
        runtime.cancelInput(token); runCurrent()
        assertEquals("BeforeAfter", runtime.ready.frame.sakura.text)
        assertEquals(0, port.events.count { it.id == "OnUserInput" })
        advanceTimeBy(1_500); runCurrent()
        assertEquals("Nanidroid", runtime.ready.ghostName)
        assertEquals(1, port.unloadCalls)
    }

    @Test fun switchInputOkRoutesReplyBeforeOneUnload() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        fixture(root, "nanidroid", "Nanidroid", "Nanidroid")
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            changingReply = ShioriReply(200, "\\_qBefore\\![open,inputbox,box]After\\e")
            actionReply = ShioriReply(200, "\\_qAnswer\\e")
        }
        val runtime = GhostRuntime({ bundled() }, { SilentEngine }, MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port,
            ioDispatcher = StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        runtime.selectGhost("nanidroid"); runCurrent()
        runtime.confirmSwitch(); runCurrent()
        val token = runtime.ready.input!!.token
        runtime.submitInput(token, "answer"); runCurrent()
        assertEquals(listOf("box", "answer"), port.events.single { it.id == "OnUserInput" }.references)
        assertEquals("BeforeAfter", runtime.ready.frame.sakura.text)
        advanceTimeBy(1_500); runCurrent()
        assertEquals("Answer", runtime.ready.frame.sakura.text)
        advanceTimeBy(1_500); runCurrent()
        assertEquals("Nanidroid", runtime.ready.ghostName)
        assertEquals(1, port.unloadCalls)
    }

    @Test fun switchSurfaceChangeRendersWithoutSurfaceRequest() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        fixture(root, "nanidroid", "Nanidroid", "Nanidroid")
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            changingReply = ShioriReply(200, "\\_q\\s[2]Leaving\\e")
            surfaceReply = ShioriReply(200, "\\s[99]Wrong\\e")
        }
        val runtime = GhostRuntime({ bundled() }, { SilentEngine },
            MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port,
            ioDispatcher = StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        runtime.selectGhost("nanidroid"); runCurrent()
        runtime.confirmSwitch(); runCurrent()
        assertEquals(2, runtime.ready.frame.sakura.surfaceId)
        assertEquals(0, port.events.count { it.id == "OnSurfaceChange" })
        advanceTimeBy(1_500); runCurrent()
        assertEquals("Nanidroid", runtime.ready.ghostName)
    }

    @Test fun backDuringSwitchDialogueStartsCloseAndSecondBackSkipsIt() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        fixture(root, "nanidroid", "Nanidroid", "Nanidroid")
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            changingReply = ShioriReply(200, "\\q[Switch,choice]\\e")
            closeReply = ShioriReply(200, "\\q[Close,choice]\\e")
        }
        val runtime = GhostRuntime({ bundled() }, { error("Unexpected Kotlin engine") },
            MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port,
            ioDispatcher = StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        runtime.selectGhost("nanidroid"); runCurrent()
        runtime.confirmSwitch(); runCurrent()
        val switchToken = runtime.ready.choices[0]!!.single().token
        runtime.close(); runCurrent()
        val closeToken = runtime.ready.choices[0]!!.single().token
        runtime.choose(switchToken); runCurrent()
        assertEquals(0, port.events.count { it.id == "OnChoiceSelect" })
        runtime.close(); runtime.close(); runCurrent()
        runtime.choose(closeToken); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(1, port.events.count { it.id == "OnClose" })
        assertEquals(1, port.unloadCalls)
    }

    @Test fun repeatedBackDuringFarewellUnloadKeepsOneDestroyAndOneUnload() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            closeReply = ShioriReply(200, "\\q[Wait,choice]\\e")
        }
        val unloadGate = CompletableDeferred<NativeUnloadResult>()
        port.unloadGate = unloadGate
        val runtime = GhostRuntime({ bundled() }, { error("Unexpected Kotlin engine") },
            MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port,
            ioDispatcher = StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        runtime.close(); runCurrent()
        val token = runtime.ready.choices[0]!!.single().token
        runtime.close(); runCurrent()
        assertEquals(1, port.unloadCalls)
        runtime.close(); runtime.choose(token); runCurrent()
        assertEquals(1, port.unloadCalls)
        assertEquals(1, port.events.count { it.id == "OnDestroy" })
        assertEquals(0, port.events.count { it.id == "OnChoiceSelect" })
        unloadGate.complete(NativeUnloadResult.Unloaded); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(listOf("request:OnClose", "shutdown:true", "unload"), port.operations.takeLast(3))
    }

    @Test fun startupFilesystemReadsWaitForIoDispatcher() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "candidate", "Other", "Unsupported.dll")
        val queued = ArrayDeque<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        }
        val runtime = GhostRuntime({ bundled() }, { SilentEngine }, MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("candidate") }, ioDispatcher = io) { 0L }
        val starting = async { runtime.start("en") }
        runCurrent()
        assertTrue(runtime.state.value is StageState.Loading)
        assertTrue("startup must dispatch filesystem work", queued.isNotEmpty())
        queued.removeFirst().run()
        runCurrent()
        assertTrue("catalog must dispatch its own filesystem read", queued.isNotEmpty())
        queued.removeFirst().run()
        runCurrent()
        starting.await()
        assertEquals("Other", runtime.ready.ghostName)
    }

    @Test fun cancellingNativeStartupDuringShellCatalogLoadReleasesLeaseOnce() = runTest {
        val root = folder.newFolder("cancelled-catalog")
        fixture(root, "native", "Native", "satori.dll")
        val lease = NativeLease(71)
        val port = FakePort().apply { loads.add(NativeLoadResult.Loaded(lease)) }
        val queued = ArrayDeque<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        }
        val runtimeScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runtime = GhostRuntime({ bundled() }, { error("Unexpected Kotlin engine") },
            MemoryBoot(), runtimeScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port, ioDispatcher = io) { 0L }
        val starting = backgroundScope.async { runtime.start("en") }
        runCurrent()
        while (port.events.none { it.id == "OnInitialize" }) {
            assertTrue("Native initialization must be reached", queued.isNotEmpty())
            queued.removeFirst().run()
            runCurrent()
        }
        assertTrue("Shell catalog read must be pending", queued.isNotEmpty())
        assertEquals(1, port.loadCalls)
        assertEquals(NativeAvailability.Occupied, port.availability.value)
        runtimeScope.cancel()
        while (queued.isNotEmpty()) {
            queued.removeFirst().run()
            runCurrent()
        }
        runCurrent()
        assertEquals(listOf(lease), port.unloadedLeases)
        assertFalse(runtime.state.value is StageState.Loading)
        assertEquals(1, port.unloadCalls)
    }

    @Test fun ignoredSwitchDuringImportKeepsOrdinaryInputBackAsCancel() = runTest {
        val root = folder.newFolder("ignored-switch-input")
        fixture(root, "candidate", "Candidate", "Unsupported.dll")
        val events = mutableListOf<String>()
        val runtime = GhostRuntime({ bundled() }, { object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event.id
                return if (event.id == "OnFirstBoot")
                    ShioriReply(200, "\\_q\\![open,inputbox,ordinary]\\e") else ShioriReply(204)
            }
        } }, MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            ioDispatcher = StandardTestDispatcher(testScheduler)) { 0L }
        val model = StageViewModel(runtime)
        model.start("en"); runCurrent()
        val input = runtime.ready.input!!.token
        runtime.selectGhost("candidate"); runCurrent()
        assertNotNull(runtime.ready.switchPrompt)
        runtime.setImportRunning(true)
        model.confirmSwitch(); runCurrent()
        assertFalse(model.isFarewellInput(input))
        assertEquals(input, runtime.ready.input!!.token)
        if (model.isFarewellInput(input)) model.close() else model.cancelInput(input)
        runCurrent()
        assertFalse(events.contains("OnClose"))
        assertTrue(runtime.state.value is StageState.Ready)
    }

    @Test fun escapedCatalogFailureAfterNativeLoadReleasesLeaseAndFallsBackToBundled() = runTest {
        val root = folder.newFolder("failed-catalog")
        fixture(root, "native", "Native", "satori.dll")
        val lease = NativeLease(72)
        val port = FakePort().apply { loads.add(NativeLoadResult.Loaded(lease)) }
        val runtime = GhostRuntime({ bundled() }, { SilentEngine }, MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            loadCatalog = { _, _, _ -> throw IllegalStateException("catalog escaped") }) { 0L }
        runtime.start("en")
        assertEquals(1, port.loadCalls)
        assertEquals(1, port.events.count { it.id == "OnInitialize" })
        assertEquals(listOf(lease), port.unloadedLeases)
        assertEquals(1, port.unloadCalls)
        assertEquals(NativeAvailability.Available, port.availability.value)
        assertEquals("Nanidroid", runtime.ready.ghostName)
        assertTrue(runtime.ready.activationError!!.contains("catalog escaped"))
    }

    @Test fun escapedCatalogFailureDuringSwitchReleasesLeaseAndFallsBackToBundled() = runTest {
        val root = folder.newFolder("failed-switch-catalog")
        fixture(root, "native", "Native", "satori.dll")
        val lease = NativeLease(73)
        val port = FakePort().apply { loads.add(NativeLoadResult.Loaded(lease)) }
        val runtime = GhostRuntime({ bundled() }, { SilentEngine }, MemoryBoot(), backgroundScope, InstalledGhostRepository(root), nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            loadCatalog = { _, _, _ -> throw IllegalStateException("catalog escaped") }) { 0L }
        runtime.start("en")
        runtime.selectGhost("native"); runCurrent()
        runtime.confirmSwitch(); runCurrent()
        assertEquals(1, port.loadCalls)
        assertEquals(listOf(lease), port.unloadedLeases)
        assertEquals(1, port.unloadCalls)
        assertEquals(NativeAvailability.Available, port.availability.value)
        assertEquals("Nanidroid", runtime.ready.ghostName)
        assertTrue(runtime.ready.activationError!!.contains("catalog escaped"))
    }

    @Test fun catalogFailureReleasesNativeLeaseBeforeAnotherActivationCanStart() = runTest {
        val root = folder.newFolder("slow-catalog-cleanup")
        fixture(root, "native", "Native", "satori.dll")
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(74)))
            loads.add(NativeLoadResult.Loaded(NativeLease(75)))
            unloadGate = CompletableDeferred()
        }
        var catalogFailures = 1
        val runtime = GhostRuntime({ bundled() }, { SilentEngine }, MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            loadCatalog = { directory, numbered, _ ->
                if (catalogFailures-- > 0) throw IllegalStateException("catalog escaped")
                ShellCatalog(numbered, SurfaceDefinitions.parse(""), directory)
            }) { 0L }
        backgroundScope.async { runtime.start("en") }
        runCurrent()
        assertEquals(1, port.unloadCalls)
        assertTrue(runtime.state.value is StageState.Loading)
        val second = backgroundScope.async { runtime.start("en") }
        runCurrent()
        assertEquals(1, port.loadCalls)
        port.unloadGate!!.complete(NativeUnloadResult.Unloaded)
        port.unloadGate = null
        runCurrent(); second.await()
        assertEquals(1, port.loadCalls)
        assertEquals("Nanidroid", runtime.ready.ghostName)
        runtime.selectGhost("native"); runCurrent()
        assertEquals("native", runtime.ready.switchPrompt?.directoryId)
        runtime.confirmSwitch(); runCurrent()
        assertEquals(2, port.loadCalls)
        assertEquals("Native", runtime.ready.ghostName)
    }

    @Test fun inputBackUsesRuntimeFarewellDispositionAndRejectsOldToken() = runTest {
        val runtime = GhostRuntime({ bundled() }, { object : ShioriEngine {
            override suspend fun request(event: ShioriEvent) = when (event.id) {
                "OnFirstBoot" -> ShioriReply(200, "\\_q\\![open,inputbox,ordinary]\\e")
                "OnClose" -> ShioriReply(200, "\\_q\\![open,inputbox,farewell]\\e")
                else -> ShioriReply(204)
            }
        } }, MemoryBoot(), backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler)) { 0L }
        val model = StageViewModel(runtime)
        model.start("en"); runCurrent()
        val ordinary = runtime.ready.input!!.token
        assertFalse(model.isFarewellInput(ordinary))
        model.close(); runCurrent()
        val farewell = runtime.ready.input!!.token
        assertTrue(model.isFarewellInput(farewell))
        assertFalse(model.isFarewellInput(ordinary))
        if (model.isFarewellInput(ordinary)) model.close() else model.cancelInput(ordinary)
        runCurrent()
        assertTrue(model.isFarewellInput(farewell))
        if (model.isFarewellInput(farewell)) model.close() else model.cancelInput(farewell)
        runCurrent()
        assertEquals(StageState.Finished, runtime.state.value)
    }

    @Test fun laterSelectionWinsAndDismissInvalidatesPendingRead() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "first", "First", "Unsupported.dll")
        fixture(root, "second", "Second", "Unsupported.dll")
        val queued = ArrayDeque<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        }
        val runtime = GhostRuntime({ bundled() }, { SilentEngine }, MemoryBoot(), backgroundScope, InstalledGhostRepository(root), ioDispatcher = io) { 0L }
        val starting = async { runtime.start("en") }
        runCurrent()
        queued.removeFirst().run(); runCurrent(); starting.await()

        val imported = async { runtime.offerImportedGhost("first") }
        runtime.selectGhost("second")
        runCurrent()
        assertEquals(2, queued.size)
        queued.removeLast().run(); runCurrent()
        assertEquals("second", runtime.ready.switchPrompt?.directoryId)
        queued.removeFirst().run(); runCurrent()
        assertTrue(imported.await() is ImportPromptResult.Unavailable)
        assertEquals("second", runtime.ready.switchPrompt?.directoryId)

        runtime.selectGhost("first")
        runCurrent()
        runtime.dismissSwitch()
        runCurrent()
        queued.removeFirst().run(); runCurrent()
        assertNull(runtime.ready.switchPrompt)
    }

    @Test fun pendingSelectionPreservesNewTouchErrorAndCannotReopenAfterClose() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "candidate", "Other", "Unsupported.dll")
        val queued = ArrayDeque<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        }
        val runtime = GhostRuntime({ bundled() }, { object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                if (event.id == "OnMouseClick") error("Touch failed")
                return ShioriReply(204)
            }
        } }, MemoryBoot(), backgroundScope, InstalledGhostRepository(root), ioDispatcher = io) { 0L }
        val starting = async { runtime.start("en") }
        runCurrent()
        queued.removeFirst().run(); runCurrent(); starting.await()

        runtime.setResumed(true); runCurrent()
        runtime.selectGhost("candidate")
        runCurrent()
        runtime.click(0, 1, 1)
        runCurrent()
        assertEquals("Touch failed", runtime.ready.activationError)
        queued.removeFirst().run(); runCurrent()
        val ready = runtime.state.value as StageState.Ready
        assertEquals("candidate", ready.switchPrompt?.directoryId)
        assertEquals("Touch failed", ready.activationError)

        runtime.dismissSwitch(); runCurrent()
        runtime.selectGhost("candidate"); runCurrent()
        runtime.close(); runCurrent()
        queued.removeFirst().run(); runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
    }

    @Test fun failedSwitchDialogueDoesNotReviveSelectionStartedBeforeConfirmation() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "first", "First", "Unsupported.dll")
        fixture(root, "second", "Second", "Unsupported.dll")
        val queued = ArrayDeque<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        }
        val runtime = GhostRuntime({ bundled() }, { object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                if (event.id == "OnGhostChanging") error("Changing failed")
                return ShioriReply(204)
            }
        } }, MemoryBoot(), backgroundScope, InstalledGhostRepository(root), ioDispatcher = io) { 0L }
        val starting = async { runtime.start("en") }
        runCurrent()
        queued.removeFirst().run(); runCurrent(); starting.await()
        runtime.selectGhost("first"); runCurrent()
        queued.removeFirst().run(); runCurrent()
        assertEquals("first", runtime.ready.switchPrompt?.directoryId)

        runtime.selectGhost("second"); runCurrent()
        runtime.confirmSwitch(); runCurrent()
        assertEquals("Changing failed", runtime.ready.activationError)
        queued.removeFirst().run(); runCurrent()
        assertEquals("first", runtime.ready.switchPrompt?.directoryId)
    }

    @Test fun importStartInvalidatesPendingManualSelection() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "candidate", "Other", "Unsupported.dll")
        val queued = ArrayDeque<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        }
        val runtime = GhostRuntime({ bundled() }, { SilentEngine }, MemoryBoot(), backgroundScope, InstalledGhostRepository(root), ioDispatcher = io) { 0L }
        val starting = async { runtime.start("en") }
        runCurrent()
        queued.removeFirst().run(); runCurrent(); starting.await()

        runtime.selectGhost("candidate"); runCurrent()
        runtime.setImportRunning(true)
        queued.removeFirst().run(); runCurrent()
        assertNull(runtime.ready.switchPrompt)

        runtime.setImportRunning(false)
        runtime.selectGhost("candidate"); runCurrent()
        runtime.setImportRunning(true)
        runtime.setImportRunning(false)
        queued.removeFirst().run(); runCurrent()
        assertNull(runtime.ready.switchPrompt)
    }

    @Test fun profilePreparationRunsOnIoDispatcherBeforeNativeLoad() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        var dispatches = 0
        val recordingIo = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatches++
                block.run()
            }
        }
        val port = FakePort().apply { loads.add(NativeLoadResult.Loaded(NativeLease(1))) }
        val runtime = GhostRuntime({ bundled() }, { error("Kotlin engine unexpectedly used") },
            MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port,
            ioDispatcher = recordingIo) { 0L }
        runtime.start("en")
        assertTrue(dispatches > 0)
        assertEquals(1, port.loadCalls)
        assertTrue(File(root, "native/ghost/master/profile").isDirectory)
    }

    @Test fun rejectedProfileDoesNotHideAnotherNativeGhost() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "broken", "Broken", "satori.dll")
        fixture(root, "valid", "Valid", "satori.dll")
        File(root, "broken/ghost/master/profile").writeText("not a directory")
        val last = MemoryLastGhost().apply { write("broken") }
        val port = FakePort().apply { loads.add(NativeLoadResult.Loaded(NativeLease(1))) }
        val runtime = GhostRuntime({ bundled() }, { SilentEngine }, MemoryBoot(), backgroundScope, InstalledGhostRepository(root), last, port,
            ioDispatcher = StandardTestDispatcher(testScheduler)) { 0L }
        runtime.start("en")
        val fallback = runtime.state.value as StageState.Ready
        assertEquals("Nanidroid", fallback.ghostName)
        assertNotNull(fallback.activationError)
        assertEquals(0, port.loadCalls)
        assertTrue(fallback.installedGhosts.any { it.directoryId == "valid" })
        runtime.selectGhost("valid")
        runCurrent()
        assertEquals("valid", runtime.ready.switchPrompt?.directoryId)
        runtime.confirmSwitch()
        runCurrent()
        assertEquals("Valid", runtime.ready.ghostName)
        assertEquals("valid", last.read())
        assertEquals(1, port.loadCalls)
    }

    @Test fun backWhileProfilePreparationIsQueuedSkipsNativeLoad() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val queued = ArrayDeque<Runnable>()
        val gatedIo = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queued.addLast(block)
            }
        }
        val boot = MemoryBoot()
        val port = FakePort().apply { loads.add(NativeLoadResult.Loaded(NativeLease(1))) }
        val runtime = GhostRuntime({ bundled() }, { error("Kotlin engine unexpectedly used") },
            boot, backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port,
            ioDispatcher = gatedIo) { 0L }
        val starting = async { runtime.start("en") }
        runCurrent()
        assertEquals(1, queued.size)
        runtime.close()
        runCurrent()
        queued.removeFirst().run()
        runCurrent()
        starting.await()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(0, port.loadCalls)
        assertEquals(0, boot.count("native"))
    }

    @Test fun nativePreparationAndInitializationPrecedeBootAndHistory() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val master = File(root, "native/ghost/master")
        val last = MemoryLastGhost().apply { write("native") }
        val boot = MemoryBoot()
        val port = FakePort().apply { loads.add(NativeLoadResult.Loaded(NativeLease(1))) }
        val gate = CompletableDeferred<ShioriReply>()
        port.initializeGate = gate
        port.onLoad = { assertTrue(File(master, "profile").isDirectory) }
        val runtime = GhostRuntime({ bundled() }, { error("Kotlin engine unexpectedly used") },
            boot, backgroundScope, InstalledGhostRepository(root), last, port, ioDispatcher = StandardTestDispatcher(testScheduler)) { 0L }
        val starting = async { runtime.start("en") }
        runCurrent()
        assertEquals(listOf("OnInitialize"), port.events.map { it.id })
        assertEquals(ShioriMethod.NOTIFY, port.events.single().method)
        assertTrue(port.events.single().references.isEmpty())
        assertEquals(0, boot.count("native"))
        assertEquals("native", last.read())
        gate.complete(ShioriReply(200, "\\0Do not play\\e"))
        starting.await()
        assertEquals(listOf("OnInitialize", "OnFirstBoot"), port.events.map { it.id })
        assertEquals(ShioriMethod.GET, port.events.last().method)
        assertEquals("", runtime.ready.frame.sakura.text)
        assertEquals(1, boot.count("native"))
    }

    @Test fun initializationTransportFailureCleansKnownLeaseWithoutDestroyOrHistory() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val last = MemoryLastGhost().apply { write("native") }
        val boot = MemoryBoot()
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            requestFailureFor = "OnInitialize"
            quarantineOnRequestFailure = false
        }
        val runtime = GhostRuntime({ bundled() }, { SilentEngine }, boot, backgroundScope, InstalledGhostRepository(root), last, port, ioDispatcher = StandardTestDispatcher(testScheduler)) { 0L }
        runtime.start("en")
        assertEquals("Nanidroid", runtime.ready.ghostName)
        assertEquals(0, boot.count("native"))
        assertEquals("nanidroid", last.read())
        assertEquals(listOf(false), port.shutdownNotifications)
        assertEquals(listOf("OnInitialize"), port.events.map { it.id })
    }

    @Test fun backUsesOneDestroyShutdownAndFinishesOnCleanupError() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            unloadResult = NativeUnloadResult.Unresolved("unload failed")
        }
        val runtime = GhostRuntime({ bundled() }, { error("Kotlin engine unexpectedly used") },
            MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port, ioDispatcher = StandardTestDispatcher(testScheduler)) { 0L }
        runtime.start("en")
        runtime.close()
        runCurrent()
        runtime.close()
        runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(listOf(true), port.shutdownNotifications)
        assertEquals(listOf("OnInitialize", "OnFirstBoot", "OnClose", "OnDestroy"), port.events.map { it.id })
        assertEquals(ShioriMethod.NOTIFY, port.events.last().method)
        assertEquals(listOf("request:OnClose", "shutdown:true", "unload"), port.operations.takeLast(3))
    }

    @Test fun initializationStatusesDoNotPreventBootOrPlayNotificationValue() = runTest {
        for (status in listOf(400, 500, 299)) {
            val root = folder.newFolder("ghost-$status")
            fixture(root, "native", "Native", "satori.dll")
            val port = FakePort().apply {
                loads.add(NativeLoadResult.Loaded(NativeLease(status.toLong())))
                initializeReply = ShioriReply(status, "\\0Hidden\\e")
            }
            val boot = MemoryBoot()
            val runtime = GhostRuntime({ bundled() }, { error("Kotlin engine unexpectedly used") },
                boot, backgroundScope, InstalledGhostRepository(root),
                MemoryLastGhost().apply { write("native") }, port, ioDispatcher = StandardTestDispatcher(testScheduler)) { 0L }
            runtime.start("en")
            assertEquals("Native", runtime.ready.ghostName)
            assertEquals("", runtime.ready.frame.sakura.text)
            assertEquals(1, boot.count("native"))
            assertEquals(listOf("OnInitialize", "OnFirstBoot"), port.events.map { it.id })
            assertTrue(port.shutdownNotifications.isEmpty())
        }
    }

    @Test fun backDuringInitializationCleansCandidateWithoutHistoryOrDestroy() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val last = MemoryLastGhost().apply { write("native") }
        val boot = MemoryBoot()
        val gate = CompletableDeferred<ShioriReply>()
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            initializeGate = gate
        }
        val runtime = GhostRuntime({ bundled() }, { error("Kotlin engine unexpectedly used") },
            boot, backgroundScope, InstalledGhostRepository(root), last, port, ioDispatcher = StandardTestDispatcher(testScheduler)) { 0L }
        val starting = async { runtime.start("en") }
        runCurrent()
        runtime.close()
        runCurrent()
        gate.complete(ShioriReply(204))
        starting.await()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(0, boot.count("native"))
        assertEquals("native", last.read())
        assertEquals(listOf(false), port.shutdownNotifications)
        assertEquals(listOf("OnInitialize"), port.events.map { it.id })
    }

    @Test fun unresolvedOwnershipAfterInitializationNeverCommitsCandidateOrRetriesJni() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val last = MemoryLastGhost().apply { write("native") }
        val boot = MemoryBoot()
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            quarantineOnInitializeReply = true
        }
        val runtime = GhostRuntime({ bundled() }, { SilentEngine }, boot, backgroundScope, InstalledGhostRepository(root), last, port, ioDispatcher = StandardTestDispatcher(testScheduler)) { 0L }
        runtime.start("en")
        assertEquals("Nanidroid", runtime.ready.ghostName)
        assertEquals(0, boot.count("native"))
        assertEquals("nanidroid", last.read())
        assertTrue(port.shutdownNotifications.isEmpty())
        assertEquals(0, port.unloadCalls)
    }

    @Test fun repeatedStartAndPauseResumeKeepOneNativeLeaseAndInitialization() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val port = FakePort().apply { loads.add(NativeLoadResult.Loaded(NativeLease(1))) }
        val runtime = GhostRuntime({ bundled() }, { error("Kotlin engine unexpectedly used") },
            MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port, ioDispatcher = StandardTestDispatcher(testScheduler)) { 0L }
        runtime.start("en")
        runtime.start("en")
        runtime.setResumed(true); runCurrent()
        runtime.setResumed(false); runCurrent()
        runtime.setResumed(true); runCurrent()
        assertEquals(1, port.loadCalls)
        assertEquals(0, port.unloadCalls)
        assertEquals(listOf("OnInitialize", "OnFirstBoot"), port.events.map { it.id })
    }

    @Test fun nativeDoubleClickDispatchesOnceWithUnscaledReferencesAndPlaysReplyAfterResume() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            doubleClickReply = ShioriReply(200, "\\1Hello\\e")
        }
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root),
            lastGhostStore = MemoryLastGhost().apply { write("native") },
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { testScheduler.currentTime }
        runtime.start("en")
        runtime.doubleClick(1, 12, 34)
        runCurrent()
        assertEquals(listOf("OnInitialize", "OnFirstBoot"), port.events.map { it.id })
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("", runtime.ready.frame.kero.text)
        runtime.setResumed(true)
        runCurrent()
        runtime.doubleClick(1, 12, 34)
        runCurrent()
        assertEquals(listOf("OnInitialize", "OnFirstBoot", "OnMouseDoubleClick"), port.events.map { it.id })
        assertEquals(listOf("12", "34", "0", "1", "", "0", "touch"), port.events.last().references)
        advanceTimeBy(50)
        runCurrent()
        assertEquals("H", runtime.ready.frame.kero.text)
    }

    @Test fun doubleClickNoScriptClearsPriorDialogue() = runTest {
        val events = mutableListOf<ShioriEvent>()
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    events += event
                    return if (event.id == "OnFirstBoot") ShioriReply(200, "\\0Hello\\e")
                        else ShioriReply(204)
                }
            } }, bootState = MemoryBoot(), scope = backgroundScope,
        ) { testScheduler.currentTime }
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals("H", runtime.ready.frame.sakura.text)
        runtime.doubleClick(0, 5, 6)
        runCurrent()
        assertEquals("", runtime.ready.frame.sakura.text)
        assertEquals("OnMouseDoubleClick", events.last().id)
        advanceTimeBy(500)
        runCurrent()
        assertEquals("", runtime.ready.frame.sakura.text)
    }

    @Test fun lateDoubleClickReplyCannotResurrectFinishedSession() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val port = FakePort().apply { loads.add(NativeLoadResult.Loaded(NativeLease(1))) }
        val replyGate = CompletableDeferred<ShioriReply>()
        port.doubleClickGate = replyGate
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root),
            lastGhostStore = MemoryLastGhost().apply { write("native") },
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        runtime.setResumed(true); runCurrent()
        runtime.doubleClick(0, 5, 6)
        runCurrent()
        assertEquals(1, port.events.count { it.id == "OnMouseDoubleClick" })
        runtime.close()
        runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        replyGate.complete(ShioriReply(200, "\\0Stale\\e"))
        runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(1, port.unloadCalls)
    }

    @Test fun quarantinedDoubleClickFallsBackWithoutFurtherNativeCalls() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val last = MemoryLastGhost().apply { write("native") }
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            requestFailureFor = "OnMouseDoubleClick"
        }
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        runtime.setResumed(true); runCurrent()
        runtime.doubleClick(0, 5, 6)
        runCurrent()
        val ready = runtime.state.value as StageState.Ready
        assertEquals("Nanidroid", ready.ghostName)
        assertTrue(ready.activationError!!.contains("request failed"))
        assertEquals("nanidroid", last.read())
        assertEquals(1, port.loadCalls)
        assertEquals(0, port.unloadCalls)
        assertEquals(listOf("OnInitialize", "OnFirstBoot", "OnMouseDoubleClick"), port.events.map { it.id })
    }

    @Test fun sessionTransitionTable() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "candidate", "Candidate", "unsupported.dll")
        val events = mutableListOf<String>()
        fun runtime(loader: suspend () -> BundledGhost = { bundled() }) = GhostRuntime(
            loadGhost = loader,
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    events += event.id
                    return when (event.id) {
                        "OnClose", "OnGhostChanging" -> ShioriReply(200, "\\0Bye")
                        else -> ShioriReply(204)
                    }
                }
            } },
            bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { testScheduler.currentTime }
        val cases: List<Pair<String, suspend () -> Unit>> = listOf(
            "Idle + Back -> Finished" to {
                val r = runtime()
                r.close(); runCurrent()
                assertTrue(r.state.value is StageState.Finished)
            },
            "Loading + Back rejects late load" to {
                val gate = CompletableDeferred<Unit>()
                val r = runtime { gate.await(); bundled() }
                val starting = async { r.start("en") }
                runCurrent()
                assertTrue(r.state.value is StageState.Loading)
                r.close(); runCurrent()
                gate.complete(Unit)
                starting.await()
                assertTrue(r.state.value is StageState.Finished)
            },
            "Active recreation, pause, resume, click" to {
                events.clear()
                val r = runtime()
                r.start("en"); r.start("en")
                r.setResumed(true); runCurrent()
                r.setResumed(false); runCurrent()
                r.setResumed(true); runCurrent()
                r.click(0, 3, 4); runCurrent()
                assertTrue(r.state.value is StageState.Ready)
                assertEquals(1, events.count { it == "OnFirstBoot" })
                assertEquals(1, events.count { it == "OnMouseClick" })
                r.setResumed(false); runCurrent()
            },
            "Switching + Back -> Closing -> Finished" to {
                val r = runtime()
                r.start("en")
                r.selectGhost("candidate"); runCurrent()
                r.confirmSwitch(); runCurrent()
                assertEquals("Nanidroid", (r.state.value as StageState.Ready).ghostName)
                assertEquals("OnGhostChanging", events.last())
                r.close(); runCurrent()
                r.setResumed(true); runCurrent()
                advanceTimeBy(1_500); runCurrent()
                assertTrue(r.state.value is StageState.Finished)
            },
            "Closing + second Back remains single flight" to {
                events.clear()
                val r = runtime()
                r.start("en")
                r.close(); runCurrent()
                r.close(); runCurrent()
                assertEquals(1, events.count { it == "OnClose" })
                r.setResumed(true); runCurrent()
                advanceTimeBy(1_500); runCurrent()
                assertTrue(r.state.value is StageState.Finished)
            },
            "Finished + start creates new active session" to {
                val r = runtime()
                r.start("en")
                r.close(); runCurrent()
                r.setResumed(true); runCurrent()
                advanceTimeBy(1_500); runCurrent()
                assertTrue(r.state.value is StageState.Finished)
                r.start("en")
                assertTrue(r.state.value is StageState.Ready)
                r.setResumed(false); runCurrent()
            },
        )
        for ((name, transition) in cases) try { transition() }
            catch (error: Throwable) { throw AssertionError(name, error) }
    }

    @Test fun quarantinedChangingRequestFallsBackWithoutAnotherNativeCall() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        fixture(root, "nanidroid", "Nanidroid", "Nanidroid")
        val last = MemoryLastGhost().apply { write("native") }
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            requestFailureFor = "OnGhostChanging"
        }
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        runtime.selectGhost("nanidroid")
        runCurrent()
        runtime.confirmSwitch()
        runCurrent()
        val ready = runtime.state.value as StageState.Ready
        assertEquals("Nanidroid", ready.ghostName)
        assertTrue(ready.activationError!!.contains("request failed"))
        assertEquals("nanidroid", last.read())
        assertEquals(1, port.loadCalls)
        assertEquals(0, port.unloadCalls)
        assertEquals(listOf("OnInitialize", "OnFirstBoot", "OnGhostChanging"), port.events.map { it.id })
        assertTrue(ready.installedGhosts.none { it.directoryId == "native" })
    }

    @Test fun quarantinedBootRequestFallsBackToBundled() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val last = MemoryLastGhost().apply { write("native") }
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            requestFailureFor = "OnFirstBoot"
        }
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        val ready = runtime.state.value as StageState.Ready
        assertEquals("Nanidroid", ready.ghostName)
        assertTrue(ready.activationError!!.contains("request failed"))
        assertEquals("nanidroid", last.read())
        assertEquals(1, port.loadCalls)
        assertEquals(0, port.unloadCalls)
        assertEquals(listOf("OnInitialize", "OnFirstBoot"), port.events.map { it.id })
    }

    @Test fun quarantinedTouchRequestFallsBackToBundled() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val last = MemoryLastGhost().apply { write("native") }
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            requestFailureFor = "OnMouseClick"
        }
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        runtime.setResumed(true); runCurrent()
        runtime.click(0, 2, 3)
        runCurrent()
        val ready = runtime.state.value as StageState.Ready
        assertEquals("Nanidroid", ready.ghostName)
        assertTrue(ready.activationError!!.contains("request failed"))
        assertEquals("nanidroid", last.read())
        assertEquals(1, port.loadCalls)
        assertEquals(0, port.unloadCalls)
        assertEquals(listOf("OnInitialize", "OnFirstBoot", "OnMouseClick"), port.events.map { it.id })
    }

    @Test fun quarantinedFallbackDropsNativeSwitchPrompt() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        fixture(root, "native2", "Native 2", "satori.dll")
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            requestFailureFor = "OnMouseClick"
        }
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root),
            lastGhostStore = MemoryLastGhost().apply { write("native") },
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        runtime.setResumed(true); runCurrent()
        runtime.selectGhost("native2")
        runCurrent()
        assertEquals("native2", runtime.ready.switchPrompt?.directoryId)

        runtime.click(0, 2, 3)
        runCurrent()
        val recovered = runtime.state.value as StageState.Ready
        assertEquals("Nanidroid", recovered.ghostName)
        assertNull(recovered.switchPrompt)
        runtime.confirmSwitch()
        runCurrent()
        assertEquals("Nanidroid", runtime.ready.ghostName)
        assertEquals(1, port.loadCalls)
        assertEquals(listOf("OnInitialize", "OnFirstBoot", "OnMouseClick"), port.events.map { it.id })
    }

    @Test fun nonquarantiningTouchRequestErrorKeepsCurrentNativeSession() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            requestFailureFor = "OnMouseClick"
            quarantineOnRequestFailure = false
        }
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root),
            lastGhostStore = MemoryLastGhost().apply { write("native") },
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        runtime.setResumed(true); runCurrent()
        runtime.click(0, 2, 3)
        runCurrent()
        val ready = runtime.state.value as StageState.Ready
        assertEquals("Native", ready.ghostName)
        assertTrue(ready.activationError!!.contains("request failed"))
        assertEquals(1, port.loadCalls)
    }

    @Test fun quarantinedTimerRequestFallsBackToBundled() = runTest {
        var now = 0L
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val last = MemoryLastGhost().apply { write("native") }
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            requestFailureFor = "OnSecondChange"
        }
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { now }
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        now = 60_000L
        advanceTimeBy(50)
        runCurrent()
        val ready = runtime.state.value as StageState.Ready
        assertEquals("Nanidroid", ready.ghostName)
        assertTrue(ready.activationError!!.contains("request failed"))
        assertEquals("nanidroid", last.read())
        assertEquals(1, port.loadCalls)
        assertEquals(listOf("OnInitialize", "OnFirstBoot", "OnSecondChange"), port.events.map { it.id })
    }

    @Test fun failedTouchAndTimerRequestsKeepOpenSwitchPrompt() = runTest {
        var now = 0L
        val root = folder.newFolder("ghost")
        fixture(root, "candidate", "Other", "Unsupported.dll")
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply =
                    if (event.id == "OnMouseClick" || event.id == "OnSecondChange") error("${event.id} failed")
                    else ShioriReply(204)
            } },
            bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = MemoryLastGhost(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { now }
        runtime.start("en")
        runtime.setResumed(true); runCurrent()
        runtime.selectGhost("candidate")
        runCurrent()
        runtime.click(0, 1, 1)
        runCurrent()
        var ready = runtime.state.value as StageState.Ready
        assertEquals("OnMouseClick failed", ready.activationError)
        assertEquals("candidate", ready.switchPrompt?.directoryId)
        runtime.setResumed(true)
        runCurrent()
        now = 1_000L
        advanceTimeBy(50); runCurrent()
        ready = runtime.state.value as StageState.Ready
        assertEquals("OnSecondChange failed", ready.activationError)
        assertEquals("candidate", ready.switchPrompt?.directoryId)
    }

    @Test fun selectionWaitsForConfirmationAndChangingScript() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "candidate", "Other", "Unsupported.dll")
        File(root, "candidate/readme.txt").writeBytes("\uFEFFRead this first".toByteArray(Charsets.UTF_8))
        val events = mutableListOf<ShioriEvent>()
        val last = MemoryLastGhost()
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    events += event
                    return when (event.id) {
                        "OnGhostChanging" -> ShioriReply(200, "\\0Leaving")
                        else -> ShioriReply(204)
                    }
                }
            } },
            bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { testScheduler.currentTime }
        runtime.start("en")
        runtime.selectGhost("candidate")
        runCurrent()
        assertEquals("nanidroid", runtime.ready.ghostName.lowercase())
        assertEquals("candidate", runtime.ready.switchPrompt?.directoryId)
        assertEquals("Read this first", runtime.ready.switchPrompt?.readme)
        assertEquals("nanidroid", last.read())
        runtime.confirmSwitch()
        runtime.confirmSwitch()
        runCurrent()
        assertEquals(1, events.count { it.id == "OnGhostChanging" })
        assertEquals(listOf("Other", "manual", "", File(root, "candidate").absolutePath),
            events.last().references)
        assertEquals("nanidroid", last.read())
        runtime.setResumed(true)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("candidate", last.read())
        assertEquals("Other", runtime.ready.ghostName)
    }

    @Test fun oversizedReadmeShowsBoundedPreviewAndTruncationNotice() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "candidate", "Other", "Unsupported.dll")
        File(root, "candidate/readme.txt").writeBytes(
            "Read this first\n".toByteArray(Charsets.UTF_8) + ByteArray(256 * 1024) { 'a'.code.toByte() }
        )
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine },
            bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { testScheduler.currentTime }
        runtime.start("en")
        runtime.selectGhost("candidate")
        runCurrent()

        val preview = runtime.ready.switchPrompt?.readme
        assertNotNull(preview)
        assertTrue(preview!!.startsWith("Read this first\n"))
        assertTrue(preview.contains("truncated", ignoreCase = true))
        assertTrue(preview.length < 100_000)
    }

    @Test fun malformedSelectionPreservesCurrentDialogueAndLastGhost() = runTest {
        val root = folder.newFolder("ghost")
        val last = MemoryLastGhost()
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent) = ShioriReply(200, "\\0Speaking\\e")
            } }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        val before = runtime.ready.frame
        runtime.selectGhost("../escape")
        runCurrent()
        assertEquals(before, runtime.ready.frame)
        assertEquals("nanidroid", last.read())
        assertNotNull(runtime.ready.activationError)
    }

    @Test fun failedNativeStartupFallsBackWithoutRecordingCandidateAndRetryGetsFirstBoot() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val last = MemoryLastGhost().apply { write("native") }
        val boot = MemoryBoot()
        val port = FakePort().apply { loads.add(NativeLoadResult.Failed(0)) }
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine },
            bootState = boot, scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        assertEquals(0, boot.count("native"))
        assertEquals("nanidroid", last.read())
        assertNotNull(runtime.ready.activationError)
        port.loads.add(NativeLoadResult.Loaded(NativeLease(1)))
        runtime.selectGhost("native")
        runCurrent()
        runtime.confirmSwitch()
        runCurrent()
        assertEquals(1, boot.count("native"))
        assertEquals("native", last.read())
        assertEquals("OnFirstBoot", port.events.last().id)
        assertEquals(listOf("0"), port.events.last().references)
    }

    @Test fun failedNativeUnloadQuarantinesChoicesAndKeepsBundledDialogue() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        fixture(root, "nanidroid", "Nanidroid", "Nanidroid")
        val last = MemoryLastGhost().apply { write("native") }
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            unloadResult = NativeUnloadResult.Unresolved("Native unload returned false")
        }
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine },
            bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        runtime.selectGhost("nanidroid")
        runCurrent()
        runtime.confirmSwitch()
        runCurrent()
        val ready = runtime.state.value as StageState.Ready
        assertEquals("Nanidroid", ready.ghostName)
        assertTrue(ready.activationError!!.contains("false"))
        assertTrue(ready.installedGhosts.none { it.directoryId == "native" })
        assertEquals(1, port.loadCalls)
        runtime.selectGhost("native")
        runCurrent()
        assertNull(runtime.ready.switchPrompt)
        assertEquals(1, port.loadCalls)
    }

    @Test fun occupiedByCurrentGhostStillAllowsNativeToNativeSwitch() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native1", "First Native", "satori.dll")
        fixture(root, "native2", "Second Native", "satori.dll")
        val last = MemoryLastGhost().apply { write("native1") }
        val firstLease = NativeLease(1)
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(firstLease))
            loads.add(NativeLoadResult.Loaded(NativeLease(2)))
        }
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        assertTrue(runtime.ready.installedGhosts.any { it.directoryId == "native2" })
        runtime.selectGhost("native2")
        runCurrent()
        assertEquals("native2", runtime.ready.switchPrompt?.directoryId)
        runtime.confirmSwitch()
        runCurrent()
        assertEquals("Second Native", runtime.ready.ghostName)
        assertEquals("native2", last.read())
        assertEquals(2, port.loadCalls)
        assertEquals(1, port.unloadCalls)
        assertSame(firstLease, port.unloadedLeases.single())
        assertEquals(listOf("request:OnGhostChanging", "shutdown:true", "unload"),
            port.operations.subList(port.operations.indexOf("request:OnGhostChanging"),
                port.operations.indexOf("unload") + 1))
        port.loads.add(NativeLoadResult.Loaded(NativeLease(3)))
        runtime.selectGhost("native1")
        runCurrent()
        runtime.confirmSwitch()
        runCurrent()
        assertEquals("OnGhostChanged", port.events.last().id)
        assertEquals(listOf("Second Native"), port.events.last().references)
    }

    @Test fun switchDuringPendingFirstBootPlaysDialogueBeforeOneShutdownAndActivation() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        fixture(root, "nanidroid", "Nanidroid", "Nanidroid")
        val last = MemoryLastGhost().apply { write("native") }
        val firstBoot = CompletableDeferred<ShioriReply>()
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            firstBootGate = firstBoot
            changingReply = ShioriReply(200, "\\0Leaving\\e")
        }
        val runtime = GhostRuntime({ bundled() }, { SilentEngine }, MemoryBoot(), backgroundScope, InstalledGhostRepository(root), last, port,
            ioDispatcher = StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        runtime.setResumed(true)
        val starting = async { runtime.start("en") }
        runCurrent()
        assertEquals(listOf("OnInitialize", "OnFirstBoot"), port.events.map { it.id })
        runtime.selectGhost("nanidroid")
        runCurrent()
        runtime.confirmSwitch()
        runCurrent()
        assertEquals("Native", runtime.ready.ghostName)
        assertEquals(0, port.unloadCalls)
        firstBoot.complete(ShioriReply(200, "\\0Late boot\\e"))
        starting.await()
        advanceTimeBy(50)
        runCurrent()
        assertEquals("L", runtime.ready.frame.sakura.text)
        assertEquals(0, port.unloadCalls)
        advanceTimeBy(1_450)
        runCurrent()
        assertEquals("Nanidroid", runtime.ready.ghostName)
        assertEquals("nanidroid", last.read())
        assertEquals(listOf("OnInitialize", "OnFirstBoot", "OnGhostChanging", "OnDestroy"),
            port.events.map { it.id })
        assertEquals(listOf("request:OnGhostChanging", "shutdown:true", "unload"),
            port.operations.takeLast(3))
        assertEquals(1, port.unloadCalls)
        assertEquals(listOf(true), port.shutdownNotifications)
    }

    @Test fun backDuringPendingFirstBootPlaysCloseBeforeOneShutdown() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val firstBoot = CompletableDeferred<ShioriReply>()
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            firstBootGate = firstBoot
            closeReply = ShioriReply(200, "\\0Goodbye\\e")
        }
        val runtime = GhostRuntime({ bundled() }, { error("Kotlin engine unexpectedly used") },
            MemoryBoot(), backgroundScope, InstalledGhostRepository(root),
            MemoryLastGhost().apply { write("native") }, port,
            ioDispatcher = StandardTestDispatcher(testScheduler)) { testScheduler.currentTime }
        runtime.setResumed(true)
        val starting = async { runtime.start("en") }
        runCurrent()
        runtime.close()
        runCurrent()
        assertTrue(runtime.state.value is StageState.Ready)
        assertEquals(0, port.unloadCalls)
        firstBoot.complete(ShioriReply(200, "\\0Late boot\\e"))
        starting.await()
        advanceTimeBy(50)
        runCurrent()
        assertEquals("G", runtime.ready.frame.sakura.text)
        assertEquals(0, port.unloadCalls)
        advanceTimeBy(1_450)
        runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(listOf("OnInitialize", "OnFirstBoot", "OnClose", "OnDestroy"),
            port.events.map { it.id })
        assertEquals(listOf("request:OnClose", "shutdown:true", "unload"), port.operations.takeLast(3))
        assertEquals(1, port.unloadCalls)
        assertEquals(listOf(true), port.shutdownNotifications)
    }

    @Test fun lateNativeLoadAfterBackIsUnloadedWithoutBootHistory() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val last = MemoryLastGhost().apply { write("native") }
        val boot = MemoryBoot()
        val port = FakePort()
        val gate = CompletableDeferred<NativeLoadResult>()
        port.loadGate = gate
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = boot, scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        val starting = async { runtime.start("en") }
        runCurrent()
        runtime.close()
        runCurrent()
        gate.complete(NativeLoadResult.Loaded(NativeLease(9)))
        starting.await()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(0, boot.count("native"))
        assertEquals(1, port.unloadCalls)
    }

    @Test fun lateTouchReplyCannotPublishAfterClose() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val last = MemoryLastGhost().apply { write("native") }
        val port = FakePort().apply { loads.add(NativeLoadResult.Loaded(NativeLease(1))) }
        val replyGate = CompletableDeferred<ShioriReply>()
        port.touchGate = replyGate
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        runtime.click(0, 1, 2)
        runCurrent()
        runtime.close()
        runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        replyGate.complete(ShioriReply(200, "\\0Stale\\e"))
        runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(1, port.unloadCalls)
    }

    @Test fun olderTouchReplyCannotReplaceNewerTouchDialogue() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val last = MemoryLastGhost().apply { write("native") }
        val port = FakePort().apply { loads.add(NativeLoadResult.Loaded(NativeLease(1))) }
        val oldReply = CompletableDeferred<ShioriReply>()
        port.touchReplies.add(oldReply)
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        runtime.click(0, 1, 2)
        runCurrent()
        runtime.click(0, 3, 4)
        runCurrent()
        oldReply.complete(ShioriReply(200, "\\0Stale\\e"))
        runCurrent()
        assertEquals("", runtime.ready.frame.sakura.text)
    }

    @Test fun pausingDuringOutgoingCleanupDoesNotStrandSwitch() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        fixture(root, "nanidroid", "Nanidroid", "Nanidroid")
        val last = MemoryLastGhost().apply { write("native") }
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            changingReply = ShioriReply(200, "\\0Leaving")
        }
        val unloadGate = CompletableDeferred<NativeUnloadResult>()
        port.unloadGate = unloadGate
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { testScheduler.currentTime }
        runtime.start("en")
        runtime.selectGhost("nanidroid")
        runCurrent()
        runtime.confirmSwitch()
        runCurrent()
        runtime.setResumed(true)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, port.unloadCalls)
        runtime.setResumed(false)
        runCurrent()
        unloadGate.complete(NativeUnloadResult.Unloaded)
        runCurrent()
        assertEquals("Nanidroid", runtime.ready.ghostName)
    }

    @Test fun failedExitCleanupStillFinishesOnFirstBack() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val last = MemoryLastGhost().apply { write("native") }
        val port = FakePort().apply {
            loads.add(NativeLoadResult.Loaded(NativeLease(1)))
            unloadResult = NativeUnloadResult.Unresolved("Native unload returned false")
        }
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        val warnings = mutableListOf<String>()
        val logger = Logger.getLogger(GhostRuntime::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() >= Level.WARNING.intValue()) warnings += record.message
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            runtime.start("en")
            runtime.close()
            runCurrent()
            assertTrue(runtime.state.value is StageState.Finished)
            assertEquals(1, port.unloadCalls)
            runtime.close()
            runCurrent()
            assertTrue(runtime.state.value is StageState.Finished)
            assertEquals(1, port.unloadCalls)
            assertEquals(listOf("Native unload returned false"), warnings)
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test fun backDuringSwitchUnloadWaitsAndDoesNotUnloadTwice() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        fixture(root, "nanidroid", "Nanidroid", "Nanidroid")
        val last = MemoryLastGhost().apply { write("native") }
        val port = FakePort().apply { loads.add(NativeLoadResult.Loaded(NativeLease(1))) }
        val unloadGate = CompletableDeferred<NativeUnloadResult>()
        port.unloadGate = unloadGate
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { SilentEngine }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { 0L }
        runtime.start("en")
        runtime.selectGhost("nanidroid")
        runCurrent()
        runtime.confirmSwitch()
        runCurrent()
        assertEquals(1, port.unloadCalls)
        runtime.close()
        runCurrent()
        assertTrue(runtime.state.value is StageState.Ready)
        unloadGate.complete(NativeUnloadResult.Unloaded)
        runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(1, port.unloadCalls)
    }

    @Test fun nativeLoadFailureModesFallBackWithoutRecordingDestination() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "native", "Native", "satori.dll")
        val outcomes = listOf(
            NativeLoadResult.Failed(-1) to false,
            NativeLoadResult.Unresolved("Native load returned -2") to false,
            null to true,
        )
        for ((outcome, throws) in outcomes) {
            val last = MemoryLastGhost().apply { write("native") }
            val boot = MemoryBoot()
            val port = FakePort()
            if (throws) port.loadError = IllegalStateException("JNI crashed")
            else port.loads.add(outcome!!)
            val runtime = GhostRuntime(
                loadGhost = { bundled() },
                engineFactory = { SilentEngine }, bootState = boot, scope = backgroundScope,
                installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
                nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            ) { 0L }
            runtime.start("en")
            assertEquals("Nanidroid", runtime.ready.ghostName)
            assertNotNull(runtime.ready.activationError)
            assertEquals(0, boot.count("native"))
            assertEquals("nanidroid", last.read())
        }
    }

    @Test fun installedBuiltinCandidateUsesItsOwnContentAndIdentity() = runTest {
        val root = folder.newFolder("ghost")
        fixture(root, "otherbuiltin", "Other Builtin", "Nanidroid")
        File(root, "otherbuiltin/ghost/master/en").mkdirs()
        File(root, "otherbuiltin/ghost/master/en/content.txt")
            .writeText("OnFirstBoot,\\0Own script\\e\r\n")
        val last = MemoryLastGhost()
        val runtime = GhostRuntime(
            loadGhost = { bundled() },
            engineFactory = { ghost -> object : ShioriEngine {
                override suspend fun request(event: ShioriEvent) =
                    ShioriReply(200, if (ghost.content.contains("Own script")) "\\0Own script\\e" else "\\0Base\\e")
            } }, bootState = MemoryBoot(), scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root), lastGhostStore = last,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ) { testScheduler.currentTime }
        runtime.start("en")
        runtime.selectGhost("otherbuiltin")
        runCurrent()
        runtime.confirmSwitch()
        runCurrent()
        runtime.setResumed(true)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("Other Builtin", runtime.ready.ghostName)
        assertEquals("otherbuiltin", last.read())
    }

    private fun bundled() = BundledGhost("nanidroid", "Nanidroid", "Sakura", "Kero", emptyMap(), "")
    private fun fixture(root: File, id: String, name: String, engine: String) {
        val directory = File(root, id)
        File(directory, "ghost/master").mkdirs()
        File(directory, "shell/master").mkdirs()
        File(directory, "ghost/master/descript.txt").writeText("name,$name\r\nsakura.name,$name\r\nshiori,$engine\r\n")
    }
    private class MemoryBoot : BootStateStore {
        private val seen = mutableSetOf<String>()
        private val counts = mutableMapOf<String, Int>()
        override suspend fun recordActivation(directoryId: String): Boolean {
            counts[directoryId] = (counts[directoryId] ?: 0) + 1
            return seen.add(directoryId)
        }
        fun count(id: String) = counts[id] ?: 0
        override suspend fun consumeOnboarding() = false
    }
    private class FakePort : NativePort {
        override val availability = MutableStateFlow(NativeAvailability.Available)
        val loads = ArrayDeque<NativeLoadResult>()
        val events = mutableListOf<ShioriEvent>()
        var loadCalls = 0
        var unloadCalls = 0
        val shutdownNotifications = mutableListOf<Boolean>()
        val operations = mutableListOf<String>()
        var onLoad: (() -> Unit)? = null
        var initializeGate: CompletableDeferred<ShioriReply>? = null
        var firstBootGate: CompletableDeferred<ShioriReply>? = null
        var initializeReply = ShioriReply(204)
        var quarantineOnInitializeReply = false
        val unloadedLeases = mutableListOf<NativeLease>()
        var loadGate: CompletableDeferred<NativeLoadResult>? = null
        var touchGate: CompletableDeferred<ShioriReply>? = null
        val touchReplies = ArrayDeque<CompletableDeferred<ShioriReply>>()
        var unloadGate: CompletableDeferred<NativeUnloadResult>? = null
        var changingReply: ShioriReply = ShioriReply(204)
        var closeReply: ShioriReply = ShioriReply(204)
        var actionReply: ShioriReply = ShioriReply(204)
        var surfaceReply: ShioriReply = ShioriReply(204)
        var doubleClickReply: ShioriReply = ShioriReply(204)
        var doubleClickGate: CompletableDeferred<ShioriReply>? = null
        var requestFailureFor: String? = null
        var quarantineOnRequestFailure = true
        var loadError: Exception? = null
        var unloadResult: NativeUnloadResult = NativeUnloadResult.Unloaded
        override suspend fun load(kind: NativeKind, directory: File, libraryName: String): NativeLoadResult {
            loadCalls++
            onLoad?.invoke()
            loadError?.let { throw it }
            val result = loadGate?.await() ?: loads.removeFirst()
            if (result is NativeLoadResult.Loaded) availability.value = NativeAvailability.Occupied
            if (result is NativeLoadResult.Failed && result.status == -1)
                availability.value = NativeAvailability.Occupied
            if (result is NativeLoadResult.Unresolved)
                availability.value = NativeAvailability.Quarantined
            return result
        }
        override suspend fun request(lease: NativeLease, event: ShioriEvent): ShioriReply {
            events += event
            operations += "request:${event.id}"
            if (event.id == requestFailureFor) {
                if (quarantineOnRequestFailure) availability.value = NativeAvailability.Quarantined
                throw NativeTransportException("Native request failed")
            }
            if (event.id == "OnInitialize") {
                val reply = initializeGate?.await() ?: initializeReply
                if (quarantineOnInitializeReply) availability.value = NativeAvailability.Quarantined
                return reply
            }
            if (event.id == "OnFirstBoot") firstBootGate?.let { return it.await() }
            if (event.id == "OnMouseClick") {
                touchReplies.removeFirstOrNull()?.let { return it.await() }
                touchGate?.let { return it.await() }
            }
            if (event.id == "OnMouseDoubleClick") {
                doubleClickGate?.let { return it.await() }
                return doubleClickReply
            }
            if (event.id == "OnGhostChanging") return changingReply
            if (event.id == "OnClose") return closeReply
            if (event.id == "OnChoiceSelect" || event.id == "OnUserInput") return actionReply
            if (event.id == "OnSurfaceChange") return surfaceReply
            return ShioriReply(204)
        }
        override suspend fun unload(lease: NativeLease): NativeUnloadResult {
            unloadCalls++
            operations += "unload"
            unloadedLeases += lease
            val result = unloadGate?.await() ?: unloadResult
            availability.value = if (result is NativeUnloadResult.Unresolved)
                NativeAvailability.Quarantined else NativeAvailability.Available
            return result
        }
        override suspend fun shutdown(lease: NativeLease, notifyDestroy: Boolean, timeoutMillis: Long): NativeShutdownResult {
            shutdownNotifications += notifyDestroy
            operations += "shutdown:$notifyDestroy"
            if (notifyDestroy) events += ShioriEvent("OnDestroy", method = ShioriMethod.NOTIFY)
            return when (val result = unload(lease)) {
                NativeUnloadResult.Unloaded -> NativeShutdownResult.Completed
                is NativeUnloadResult.Unresolved -> NativeShutdownResult.Unresolved(result.error)
                NativeUnloadResult.Empty, NativeUnloadResult.Stale -> NativeShutdownResult.Failed("No active lease")
            }
        }
    }
    private class MemoryLastGhost : LastGhostStore {
        private var value: String? = null
        override fun read() = value
        override fun write(directoryId: String) { value = directoryId }
    }

    private object SilentEngine : ShioriEngine {
        override suspend fun request(event: ShioriEvent) = ShioriReply(204)
    }
}
