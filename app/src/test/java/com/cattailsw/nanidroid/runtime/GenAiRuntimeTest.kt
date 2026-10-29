package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.data.GenAiSettingsStore
import com.cattailsw.nanidroid.engine.BuiltInShiori
import com.cattailsw.nanidroid.engine.GenAiAvailability
import com.cattailsw.nanidroid.engine.ManagedGhostTalkGenerator
import com.cattailsw.nanidroid.engine.NativeAvailability
import com.cattailsw.nanidroid.engine.NativeKind
import com.cattailsw.nanidroid.engine.NativeLease
import com.cattailsw.nanidroid.engine.NativeLoadResult
import com.cattailsw.nanidroid.engine.NativeShutdownResult
import com.cattailsw.nanidroid.engine.NativeUnloadResult
import com.cattailsw.nanidroid.engine.ShioriEngine
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.ghost.BundledGhost
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GenAiRuntimeTest {
    @get:Rule val folder = TemporaryFolder()
    private val GhostRuntime.ready get() = state.value as StageState.Ready

    private class Settings(var enabled: Boolean) : GenAiSettingsStore {
        override fun read() = enabled
        override fun write(enabled: Boolean) { this.enabled = enabled }
    }

    private class Generator : ManagedGhostTalkGenerator {
        val prompts = mutableListOf<String>()
        var closes = 0
        var gate: CompletableDeferred<String>? = null
        var failClose = false
        override suspend fun reply(userText: String): String {
            prompts += userText
            return gate?.await() ?: "generated"
        }
        override fun close() {
            closes++
            if (failClose) error("close failed")
        }
    }

    private class Fixture(enabled: Boolean, var available: Boolean = true) {
        val settings = Settings(enabled)
        val events = mutableListOf<ShioriEvent>()
        val generators = mutableListOf<Generator>()
        val identities = mutableListOf<Pair<Long, BundledGhost>>()
        var checks = 0
        var engineCreations = 0
        var probe: (suspend () -> Boolean)? = null
        val dependencies = GenAiRuntimeDependencies(
            settings,
            GenAiAvailability {
                checks++
                probe?.invoke() ?: available
            }
        ) { id, ghost ->
            identities += id to ghost
            Generator().also { generators += it }
        }
        val engine = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                events += event
                return if (event.id == "OnMouseClick") ShioriReply(200, "\\_qAuthored\\e") else ShioriReply(204)
            }
        }
    }

    private fun TestScope.runtime(
        fixture: Fixture,
        engine: ShioriEngine = fixture.engine,
        root: File? = null,
        owner: CoroutineScope = backgroundScope,
        port: NativePort? = null
    ) = GhostRuntime(
        loadGhost = {
            BundledGhost(
                "nanidroid", "Nanidroid", "Sakura", "Kero", emptyMap(), "",
                root?.let { File(it, "nanidroid/shell/master") }
            )
        },
        engineFactory = { fixture.engineCreations++; engine },
        bootState = object : BootStateStore {
            override suspend fun recordActivation(directoryId: String) = false
            override suspend fun consumeOnboarding() = false
        },
        scope = owner,
        installedGhosts = root?.let { InstalledGhostRepository(it) },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        genAi = fixture.dependencies,
        nativeHost = port ?: HostNativePort(com.cattailsw.nanidroid.engine.NativeShioriHost.process),
        elapsedRealtime = { testScheduler.currentTime }
    )

    private suspend fun TestScope.start(runtime: GhostRuntime) {
        runtime.start("en")
        runtime.setResumed(true)
        runCurrent()
    }

    @Test fun disabledAndUnavailablePreserveAuthoredEventsAndReplies() = runTest {
        for ((enabled, available) in listOf(false to true, true to false)) {
            val f = Fixture(enabled, available)
            val runtime = runtime(f)
            start(runtime)
            runtime.click(0, 0, 0)
            runCurrent()
            assertEquals("Authored", runtime.ready.frame.sakura.text)
            assertEquals(listOf("OnBoot", "OnMouseClick"), f.events.map { it.id })
            assertEquals(if (enabled) 1 else 0, f.checks)
            assertTrue(f.generators.isEmpty())
            assertFalse(runtime.ready.talkAvailable)
            runtime.openTalk(); runCurrent()
            assertNull(runtime.ready.talkInput)
            runtime.setResumed(false)
        }
    }

    @Test fun talkDispatchesUserCommunicationAndDisableRejectsOldDialog() = runTest {
        val f = Fixture(true)
        val runtime = runtime(f)
        start(runtime)
        assertTrue(runtime.ready.talkAvailable)
        assertEquals("Sakura", f.identities.single().second.sakuraName)
        runtime.openTalk(); runCurrent()
        val first = runtime.ready.talkInput!!.token
        runtime.submitTalk(first, "hello"); runCurrent()
        assertEquals(listOf("user", "hello"), f.events.single { it.id == "OnCommunicate" }.references)
        assertEquals(listOf("hello"), f.generators.single().prompts)
        advanceTimeBy(600); runCurrent()
        assertEquals("generated", runtime.ready.frame.sakura.text)
        runtime.openTalk(); runCurrent()
        val stale = runtime.ready.talkInput!!.token
        runtime.setGenAiEnabled(false); runCurrent()
        assertFalse(f.settings.enabled)
        assertFalse(runtime.ready.talkAvailable)
        runtime.submitTalk(stale, "ignored"); runCurrent()
        assertEquals(1, f.events.count { it.id == "OnCommunicate" })
        assertEquals(1, f.generators.single().closes)
        runtime.setGenAiEnabled(true); runCurrent()
        assertEquals(2, f.generators.size)
        runtime.close(); runCurrent()
        assertEquals(listOf(1, 1), f.generators.map { it.closes })
    }

    @Test fun cachedBundledEngineGetsFreshGeneratorAfterSwitchAndBack() = runTest {
        val root = folder.newFolder()
        for (id in listOf("other", "nanidroid")) {
            val master = File(root, "$id/ghost/master").apply { mkdirs() }
            File(master, "descript.txt").writeText("name,$id\nsakura.name,$id\nkero.name,Companion\nshiori,Nanidroid\n")
            File(master, "ja").mkdirs()
            File(master, "ja/content.txt").writeText("")
            File(root, "$id/shell/master").mkdirs()
        }
        val f = Fixture(true)
        val runtime = runtime(f, root = root)
        start(runtime)
        for (id in listOf("other", "nanidroid")) {
            runtime.selectGhost(id); runCurrent(); runtime.confirmSwitch(); runCurrent()
        }
        assertEquals(3, f.generators.size)
        assertEquals(2, f.engineCreations)
        assertEquals(listOf(1, 1, 0), f.generators.map { it.closes })
        assertEquals(3, f.identities.map { it.first }.distinct().size)
        runtime.close(); runCurrent()
        assertEquals(listOf(1, 1, 1), f.generators.map { it.closes })
    }

    @Test fun decoratedBundledDeveloperPolicyIsUnchanged() = runTest {
        for (debug in listOf(true, false)) {
            val f = Fixture(true)
            val engine = BuiltInShiori.bundled("OnMouseClick,\\![open,developer]\\e", debug)
            val runtime = runtime(f, engine)
            start(runtime)
            assertTrue(runtime.ready.talkAvailable)
            runtime.click(0, 0, 0); runCurrent()
            val request = runtime.ready.hostUiRequest
            if (debug) {
                assertNotNull(request)
                assertEquals(HostUiCommand.Developer, runtime.claimHostUiRequest(request!!.id))
            } else assertNull(request)
            assertTrue(f.generators.single().prompts.isEmpty())
            runtime.close(); runCurrent()
        }
    }

    @Test fun closeFailureDoesNotPreventFinishAndScopeCancellationReleasesOwner() = runTest {
        val f = Fixture(true)
        val runtime = runtime(f)
        start(runtime)
        f.generators.single().failClose = true
        runtime.close(); runCurrent()
        assertEquals(StageState.Finished, runtime.state.value)
        assertEquals(1, f.generators.single().closes)
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val other = Fixture(true)
        start(runtime(other, owner = owner))
        owner.cancel(); runCurrent()
        assertEquals(1, other.generators.single().closes)
    }

    @Test fun disablingCancelsGenerationAndLateProbeCannotEnableTalk() = runTest {
        val f = Fixture(true)
        val runtime = runtime(f)
        start(runtime)
        val gate = CompletableDeferred<String>()
        f.generators.single().gate = gate
        runtime.openTalk(); runCurrent()
        runtime.submitTalk(runtime.ready.talkInput!!.token, "pending"); runCurrent()
        assertEquals(listOf("pending"), f.generators.single().prompts)
        runtime.setGenAiEnabled(false); runCurrent()
        gate.complete("late"); runCurrent()
        assertFalse(runtime.ready.frame.sakura.text.contains("late"))
        val probe = CompletableDeferred<Boolean>()
        f.probe = { probe.await() }
        runtime.setGenAiEnabled(true); runCurrent()
        runtime.setGenAiEnabled(false); runCurrent()
        probe.complete(true); runCurrent()
        assertEquals(1, f.generators.size)
        assertFalse(runtime.ready.talkAvailable)
    }

    @Test fun probeFailureAndRejectedActivationKeepNoGenerator() = runTest {
        val f = Fixture(true).apply { probe = { error("not available") } }
        val runtime = runtime(f)
        start(runtime)
        assertFalse(runtime.ready.talkAvailable)
        assertTrue(f.generators.isEmpty())
        runtime.close(); runCurrent()
        val other = Fixture(true)
        val gate = CompletableDeferred<Boolean>()
        other.probe = { gate.await() }
        val candidate = runtime(other)
        val loading = backgroundScope.launch { candidate.start("en") }
        runCurrent()
        candidate.close(); runCurrent()
        gate.complete(true); runCurrent()
        loading.join()
        assertEquals(StageState.Finished, candidate.state.value)
        assertTrue(other.generators.isEmpty())
    }

    @Test fun nativeGeneratorClosesOnQuarantineAndNativeShutdownStillRunsOnClose() = runTest {
        val root = folder.newFolder()
        val master = File(root, "native/ghost/master").apply { mkdirs() }
        File(master, "descript.txt").writeText("name,Native\nsakura.name,Native\nshiori,satori.dll\n")
        File(root, "native/shell/master").mkdirs()
        File(root, "nanidroid/shell/master").mkdirs()
        val events = mutableListOf<String>()
        var quarantine = false
        val port = object : NativePort {
            override val availability = MutableStateFlow(NativeAvailability.Available)
            override suspend fun load(kind: NativeKind, directory: File, libraryName: String): NativeLoadResult {
                availability.value = NativeAvailability.Occupied
                return NativeLoadResult.Loaded(NativeLease(1))
            }
            override suspend fun request(lease: NativeLease, event: ShioriEvent): ShioriReply {
                events += event.id
                if (quarantine && event.id == "OnMouseClick") {
                    availability.value = NativeAvailability.Quarantined
                    error("transport failed")
                }
                return ShioriReply(204)
            }
            override suspend fun unload(lease: NativeLease): NativeUnloadResult {
                events += "unload"
                availability.value = NativeAvailability.Available
                return NativeUnloadResult.Unloaded
            }
            override suspend fun shutdown(
                lease: NativeLease, notifyDestroy: Boolean, timeoutMillis: Long
            ): NativeShutdownResult {
                events += "shutdown"
                unload(lease)
                return NativeShutdownResult.Completed
            }
        }
        val f = Fixture(true)
        val runtime = runtime(f, root = root, port = port)
        start(runtime)
        runtime.selectGhost("native"); runCurrent(); runtime.confirmSwitch(); runCurrent()
        assertEquals("Native", runtime.ready.ghostName)
        assertEquals(2, f.generators.size)
        runtime.openTalk(); runCurrent()
        runtime.submitTalk(runtime.ready.talkInput!!.token, "native chat"); runCurrent()
        assertTrue(events.contains("OnCommunicate"))
        assertEquals(listOf("native chat"), f.generators[1].prompts)
        quarantine = true
        runtime.click(0, 0, 0); runCurrent()
        assertEquals("Nanidroid", runtime.ready.ghostName)
        assertEquals(listOf(1, 1, 0), f.generators.map { it.closes })
        // A separate healthy native session must still reach native shutdown if AI close throws.
        quarantine = false
        port.availability.value = NativeAvailability.Available
        val other = Fixture(true)
        val healthy = runtime(other, root = root, port = port)
        start(healthy)
        healthy.selectGhost("native"); runCurrent(); healthy.confirmSwitch(); runCurrent()
        other.generators.last().failClose = true
        healthy.close(); runCurrent()
        assertEquals(StageState.Finished, healthy.state.value)
        assertTrue(events.contains("OnClose"))
        assertTrue(events.contains("shutdown"))
        assertEquals(1, other.generators.last().closes)
    }

    @Test fun activationFailureReleasesGeneratorAndCloseDoesNotDoubleClose() = runTest {
        val f = Fixture(true)
        val base = object : ShioriEngine {
            override suspend fun request(event: ShioriEvent): ShioriReply {
                if (event.id == "OnBoot") error("boot failed")
                return ShioriReply(204)
            }
        }
        val runtime = runtime(f, base)
        start(runtime)
        assertNotNull(runtime.ready.activationError)
        assertFalse(runtime.ready.talkAvailable)
        assertEquals(1, f.generators.single().closes)
        runtime.close(); runCurrent()
        assertEquals(1, f.generators.single().closes)
    }
}
