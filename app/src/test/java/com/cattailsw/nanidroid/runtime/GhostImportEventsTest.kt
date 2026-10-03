package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.data.LastGhostStore
import com.cattailsw.nanidroid.engine.ShioriEngine
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.engine.NativeAvailability
import com.cattailsw.nanidroid.engine.NativeKind
import com.cattailsw.nanidroid.engine.NativeLease
import com.cattailsw.nanidroid.engine.NativeLoadResult
import com.cattailsw.nanidroid.engine.NativeTransportException
import com.cattailsw.nanidroid.engine.NativeShutdownResult
import com.cattailsw.nanidroid.engine.NativeUnloadResult
import com.cattailsw.nanidroid.ghost.BundledGhost
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import java.io.File
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GhostImportEventsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun importedOfferIsDeferredUntilActiveAndUnavailableWithoutInstalledChoice() = runTest {
        val runtime = runtime(mutableListOf(), CompletableDeferred(Unit))
        assertEquals(ImportPromptResult.Deferred, runtime.offerImportedGhost("visitor"))
        runtime.start("ja")
        assertTrue(runtime.offerImportedGhost("visitor") is ImportPromptResult.Unavailable)
    }

    @Test fun capturedSinkSerializesEventsWithoutWaitingForBeginReply() = runTest {
        val events = mutableListOf<ShioriEvent>()
        val gate = CompletableDeferred<Unit>()
        val runtime = runtime(events, gate)
        runtime.start("ja")
        val sink = runtime.beginImportEvents()!!
        sink.emit("OnInstallBegin", listOf("ghost", "visitor", "visitor"))
        sink.emit("OnInstallComplete", listOf("ghost", "visitor", "visitor"))
        runCurrent()
        assertEquals(listOf("OnBoot", "OnInstallBegin"), events.map { it.id })
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("OnBoot", "OnInstallBegin", "OnInstallComplete"), events.map { it.id })
        assertEquals(listOf("ghost", "visitor", "visitor"), events.last().references)
        assertNull((runtime.state.value as StageState.Ready).switchPrompt)
    }

    @Test fun nonSuccessInstallReplyIsLoggedWithoutRetry() = runTest {
        val events = mutableListOf<ShioriEvent>()
        val messages = mutableListOf<String>()
        val logger = Logger.getLogger(GhostRuntime::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { messages += record.message }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            val runtime = runtime(events, CompletableDeferred(Unit)) {
                if (it.id == "OnInstallBegin") ShioriReply(400) else ShioriReply(204)
            }
            runtime.start("ja")
            runtime.beginImportEvents()!!.emit("OnInstallBegin", listOf("ghost", "visitor", "visitor"))
            runCurrent()
            assertEquals(1, events.count { it.id == "OnInstallBegin" })
            assertTrue(messages.any { it.contains("OnInstallBegin") && it.contains("400") })
        } finally { logger.removeHandler(handler) }
    }

    @Test fun nativeUnavailableIsAnExplicitPromptDispositionAndRunningBlocksManualSelection() = runTest {
        val root = folder.newFolder()
        val candidate = root.resolve("native")
        candidate.resolve("ghost/master").mkdirs()
        candidate.resolve("shell/master").mkdirs()
        candidate.resolve("ghost/master/descript.txt").writeText("name,Native\nshiori,satori.dll\n")
        val visitor = root.resolve("visitor")
        visitor.resolve("ghost/master").mkdirs()
        visitor.resolve("shell/master").mkdirs()
        visitor.resolve("ghost/master/descript.txt").writeText("name,Visitor\nshiori,Nanidroid\n")
        val port = object : NativePort {
            override val availability = MutableStateFlow(NativeAvailability.Quarantined)
            override suspend fun load(kind: NativeKind, directory: File, libraryName: String): NativeLoadResult =
                error("Unexpected native load")
            override suspend fun request(lease: NativeLease, event: ShioriEvent): ShioriReply =
                error("Unexpected native request")
            override suspend fun unload(lease: NativeLease): NativeUnloadResult =
                error("Unexpected native unload")
            override suspend fun shutdown(lease: NativeLease, notifyDestroy: Boolean,
                                          timeoutMillis: Long): NativeShutdownResult =
                error("Unexpected native shutdown")
        }
        val runtime = GhostRuntime(
            loadGhost = { BundledGhost("nanidroid", "Nanidroid", "", "", emptyMap(), "") },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent) = ShioriReply(204)
            } },
            bootState = object : BootStateStore {
                override suspend fun recordActivation(directoryId: String) = false
                override suspend fun consumeOnboarding() = false
            },
            scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root),
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            elapsedRealtime = { testScheduler.currentTime },
        )
        runtime.start("ja")
        assertEquals(listOf("visitor"), (runtime.state.value as StageState.Ready)
            .installedGhosts.map { it.directoryId })
        assertTrue(runtime.offerImportedGhost("native") is ImportPromptResult.Unavailable)
        runtime.setImportRunning(true)
        runtime.selectGhost("visitor")
        runCurrent()
        assertNull((runtime.state.value as StageState.Ready).switchPrompt)
        runtime.setImportRunning(false)
        runtime.selectGhost("visitor")
        runCurrent()
        assertEquals("visitor", (runtime.state.value as StageState.Ready).switchPrompt?.directoryId)

        // An imported YAYA choice is filtered while native ownership is unavailable.
        // Refresh must recompute its kind when its files change; unsupported ghosts stay selectable.
        val renamed = root.resolve("renamed")
        renamed.resolve("ghost/master").mkdirs()
        renamed.resolve("shell/master").mkdirs()
        renamed.resolve("ghost/master/descript.txt").writeText("name,Renamed\nshiori,X.dll\n")
        renamed.resolve("ghost/master/X.dll").writeText("yaya.dll")
        renamed.resolve("ghost/master/X.txt").writeText("")
        runtime.refreshInstalledChoices()
        assertEquals(listOf("visitor"), (runtime.state.value as StageState.Ready)
            .installedGhosts.map { it.directoryId })
        renamed.resolve("ghost/master/X.txt").delete()
        runtime.refreshInstalledChoices()
        assertEquals(listOf("renamed", "visitor"), (runtime.state.value as StageState.Ready)
            .installedGhosts.map { it.directoryId })
    }

    @Test fun quarantinedInstallEventRecoversAfterPauseAndDoesNotRetryNativeOwnership() = runTest {
        val root = folder.newFolder("install-failure")
        val native = root.resolve("native")
        native.resolve("ghost/master").mkdirs()
        native.resolve("shell/master").mkdirs()
        native.resolve("ghost/master/descript.txt").writeText("name,Native\nshiori,satori.dll\n")
        val lease = NativeLease(91)
        val nativeEvents = mutableListOf<String>()
        var nativeLoads = 0
        var nativeShutdowns = 0
        val port = object : NativePort {
            override val availability = MutableStateFlow(NativeAvailability.Available)
            override suspend fun load(kind: NativeKind, directory: File, libraryName: String): NativeLoadResult {
                nativeLoads++
                availability.value = NativeAvailability.Occupied
                return NativeLoadResult.Loaded(lease)
            }
            override suspend fun request(lease: NativeLease, event: ShioriEvent): ShioriReply {
                nativeEvents += event.id
                if (event.id == "OnInstallBegin") {
                    availability.value = NativeAvailability.Quarantined
                    throw NativeTransportException("controlled install request failure")
                }
                return ShioriReply(204)
            }
            override suspend fun unload(lease: NativeLease): NativeUnloadResult =
                error("Quarantined lease must not be unloaded")
            override suspend fun shutdown(lease: NativeLease, notifyDestroy: Boolean,
                                          timeoutMillis: Long): NativeShutdownResult {
                nativeShutdowns++
                error("Quarantined lease must not be shut down")
            }
        }
        val builtInEvents = mutableListOf<String>()
        val runtime = GhostRuntime(
            loadGhost = { BundledGhost("nanidroid", "Nanidroid", "", "", emptyMap(), "") },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    builtInEvents += event.id
                    return ShioriReply(204)
                }
            } },
            bootState = object : BootStateStore {
                override suspend fun recordActivation(directoryId: String) = false
                override suspend fun consumeOnboarding() = false
            },
            scope = backgroundScope,
            installedGhosts = InstalledGhostRepository(root),
            lastGhostStore = object : LastGhostStore {
                override fun read() = "native"
                override fun write(directoryId: String) = Unit
            },
            nativeHost = port,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            elapsedRealtime = { testScheduler.currentTime },
        )
        runtime.start("ja")
        assertEquals("Native", (runtime.state.value as StageState.Ready).ghostName)
        runtime.setResumed(true)
        runCurrent()
        val sink = runtime.beginImportEvents()!!
        sink.emit("OnInstallBegin", listOf("ghost", "visitor", "visitor"))
        sink.emit("OnInstallComplete", listOf("ghost", "visitor", "visitor"))
        runtime.setResumed(false)
        runCurrent()

        val recovered = runtime.state.value as StageState.Ready
        assertEquals("Nanidroid", recovered.ghostName)
        assertTrue(recovered.activationError.orEmpty().contains("controlled install request failure"))
        assertTrue(recovered.installedGhosts.none { it.directoryId == "native" })
        assertEquals(1, nativeLoads)
        assertEquals(0, nativeShutdowns)
        assertEquals(1, nativeEvents.count { it == "OnInstallBegin" })
        assertFalse(nativeEvents.contains("OnInstallComplete"))
        runtime.setResumed(true)
        runCurrent()
        runtime.click(0, 1, 1)
        runCurrent()
        assertTrue(builtInEvents.contains("OnMouseClick"))
        assertTrue(runtime.offerImportedGhost("native") is ImportPromptResult.Unavailable)
        runtime.close()
        runCurrent()
        assertTrue(runtime.state.value is StageState.Finished)
        assertEquals(0, nativeShutdowns)
    }

    private fun TestScope.runtime(events: MutableList<ShioriEvent>, gate: CompletableDeferred<Unit>,
                                  replyFor: (ShioriEvent) -> ShioriReply = { ShioriReply(204) }) =
        GhostRuntime(
            loadGhost = { BundledGhost("nanidroid", "Nanidroid", "", "", emptyMap(), "") },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    events += event
                    if (event.id == "OnInstallBegin") gate.await()
                    return replyFor(event)
                }
            } },
            bootState = object : BootStateStore {
                override suspend fun recordActivation(directoryId: String) = false
                override suspend fun consumeOnboarding() = false
            },
            scope = backgroundScope,
            elapsedRealtime = { testScheduler.currentTime },
        )
}
