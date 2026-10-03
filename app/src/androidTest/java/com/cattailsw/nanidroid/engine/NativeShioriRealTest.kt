package com.cattailsw.nanidroid.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.data.LastGhostStore
import com.cattailsw.nanidroid.ghost.BundledGhostRepository
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import com.cattailsw.nanidroid.runtime.GhostRuntime
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.shiori.Kawari
import com.cattailsw.nanidroid.shiori.SatoriShiori
import com.cattailsw.nanidroid.shiori.YayaShiori
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires user-provided fixture masters staged in private app files, outside the APK. */
@RunWith(AndroidJUnit4::class)
class NativeShioriRealTest {
    @Test fun satori() = exercise(NativeKind.SATORI, "satori", "satori.dll")
    @Test fun kawari() = exercise(NativeKind.KAWARI, "kawari", "shiori.dll")
    @Test fun yaya() = exercise(NativeKind.YAYA, "yaya", "yaya.dll")

    /** Run after a corpus row installs an unchanged NAR; records dispatch before any correction. */
    @Test fun aya5InstalledCorpusDiagnostic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureId"))
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val repository = InstalledGhostRepository(File(context.filesDir, "ghost"))
        val descriptor = repository.validate(id)
        val master = descriptor.masterPath
        fun pair(stem: String) = "${File(master, "$stem.dll").isFile}/${File(master, "$stem.txt").isFile}"
        val selected = EngineSelector.select(descriptor)
        println("AYA5_DIAG id=$id declaration=${descriptor.engineDeclaration} aya5DllTxt=${pair("aya5")} yayaDllTxt=${pair("yaya")} selected=$selected")
        val host = NativeShioriHost.process
        assertEquals(NativeAvailability.Available, host.availability.value)
        if (selected == EngineKind.YAYA) {
            val load = runBlocking { host.load(NativeKind.YAYA, master, descriptor.engineDeclaration.orEmpty()) }
            println("AYA5_DIAG directLoad=$load availability=${host.availability.value}")
            if (load is NativeLoadResult.Loaded) {
                println("AYA5_DIAG directUnload=${runBlocking { host.unload(load.lease) }} availability=${host.availability.value}")
            }
        }
        val bundled = BundledGhostRepository(
            openAsset = { context.assets.open("nanidroid.zip") },
            ghostRoot = File(context.filesDir, "ghost"),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val runtime = GhostRuntime(
            loadGhost = { bundled.load(bundled.ensureInstalled(), "en") },
            engineFactory = { BuiltInShiori(it.content) },
            bootState = object : BootStateStore {
                override suspend fun recordActivation(directoryId: String) = false
                override suspend fun consumeOnboarding() = false
            },
            scope = scope,
            installedGhosts = repository,
            lastGhostStore = object : LastGhostStore {
                override fun read() = id
                override fun write(directoryId: String) = Unit
            },
        )
        try {
            runBlocking { runtime.start("en") }
            runtime.setResumed(true)
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (runtime.state.value !is StageState.Ready && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            val state = runtime.state.value
            val ready = state as? StageState.Ready
            println("AYA5_DIAG runtimeState=${state.javaClass.simpleName} ghost=${ready?.ghostName} error=${ready?.activationError} surfaces=${ready?.surfaces?.size} text=${ready?.frame?.sakura?.text?.take(120)} nativeLease=${runtime.activeNativeLeaseForTest() != null} availability=${host.availability.value}")
            assertTrue("Runtime not ready: $state", state is StageState.Ready)
            if (selected == EngineKind.UNSUPPORTED) {
                val message = "This ghost uses a SHIORI Nanidroid does not support yet."
                val messageDeadline = SystemClock.elapsedRealtime() + 20_000
                while (SystemClock.elapsedRealtime() < messageDeadline) {
                    val frame = (runtime.state.value as? StageState.Ready)?.frame
                    if (frame?.sakura?.text?.contains(message) == true) break
                    SystemClock.sleep(50)
                }
                val unsupported = runtime.state.value as StageState.Ready
                println("AYA5_DIAG unsupportedText=${unsupported.frame.sakura.text} ownGhost=${unsupported.ghostName == descriptor.displayName} ownShell=${unsupported.surfaces.isNotEmpty()} nativeLease=${runtime.activeNativeLeaseForTest() != null}")
                assertTrue(unsupported.frame.sakura.text.contains(message))
                assertEquals(descriptor.displayName, unsupported.ghostName)
                assertTrue(unsupported.surfaces.isNotEmpty())
                assertNull(runtime.activeNativeLeaseForTest())
                runtime.selectGhost("nanidroid")
                val switchDeadline = SystemClock.elapsedRealtime() + 20_000
                while ((runtime.state.value as? StageState.Ready)?.switchPrompt?.directoryId != "nanidroid" &&
                    SystemClock.elapsedRealtime() < switchDeadline) SystemClock.sleep(50)
                assertEquals("nanidroid", (runtime.state.value as StageState.Ready).switchPrompt?.directoryId)
                runtime.confirmSwitch()
                val changedDeadline = SystemClock.elapsedRealtime() + 30_000
                while ((runtime.state.value as? StageState.Ready)?.ghostName != "Nanidroid" &&
                    SystemClock.elapsedRealtime() < changedDeadline) SystemClock.sleep(50)
                println("AYA5_DIAG switchedTo=${(runtime.state.value as? StageState.Ready)?.ghostName} availability=${host.availability.value}")
                assertEquals("Nanidroid", (runtime.state.value as StageState.Ready).ghostName)
            }
        } finally {
            runtime.close()
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (runtime.state.value !is StageState.Finished && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            println("AYA5_DIAG afterClose=${runtime.state.value} availability=${host.availability.value}")
            scope.cancel()
        }
    }

    private fun exercise(kind: NativeKind, fixture: String, library: String) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "native-fixtures/$fixture/master")
        assumeTrue("Fixture unavailable: $directory", File(directory, "descript.txt").isFile)
        val host = NativeShioriHost.process
        val loaded = host.load(kind, directory, library)
        assertTrue("$kind load: $loaded", loaded is NativeLoadResult.Loaded)
        val lease = (loaded as NativeLoadResult.Loaded).lease
        try {
            val secondStatus = when (kind) {
                NativeKind.SATORI -> SatoriShiori().nativeLoad(directory.absolutePath + File.separator, directory.absolutePath)
                NativeKind.KAWARI -> Kawari().nativeLoad(directory.absolutePath + File.separator)
                NativeKind.YAYA -> YayaShiori().nativeLoad(directory.absolutePath + File.separator, directory.absolutePath)
            }
            assertEquals("$kind second native load", -1, secondStatus)
            val reply = host.request(lease, ShioriEvent("OnBoot", listOf("master")))
            println("REAL_JNI $kind status=${reply.status} charset=${reply.charset} value=${reply.value?.take(120)}")
            assertEquals("$kind OnBoot", 200, reply.status)
            assertFalse("$kind script missing", reply.value.isNullOrEmpty())
        } finally {
            assertEquals(NativeUnloadResult.Unloaded, host.unload(lease))
            val repeated = when (kind) {
                NativeKind.SATORI -> SatoriShiori().nativeUnload()
                NativeKind.KAWARI -> Kawari().nativeUnload()
                NativeKind.YAYA -> YayaShiori().nativeUnload()
            }
            assertTrue("$kind repeated empty unload", repeated)
        }
    }
}
