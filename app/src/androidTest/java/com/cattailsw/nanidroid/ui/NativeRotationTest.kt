package com.cattailsw.nanidroid.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.MainActivity
import com.cattailsw.nanidroid.NanidroidApplication
import com.cattailsw.nanidroid.engine.NativeShioriHost
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.runtime.StageState
import java.io.File
import java.security.MessageDigest
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real 2elf flag and owner continuity through Activity and display rotation. */
@RunWith(AndroidJUnit4::class)
class NativeRotationTest {
    @Test fun loboRuntimeSwitchBackAndCloseWritesInterval() = runtimeSwitchAndBack("lobo")

    @Test fun yayaRuntimeSwitchBackAndCloseWritesName() = runtimeSwitchAndBack("yaya")

    @Test fun loboReadsAfterRuntimeBackInFreshProcess() = freshRead("lobo")

    @Test fun yayaReadsAfterRuntimeBackInFreshProcess() = freshRead("yaya")

    private fun runtimeSwitchAndBack(kind: String) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = InstrumentationRegistry.getArguments().getString("fixtureId")
        assumeTrue("Run via tools/test-native-persistence.ps1", !id.isNullOrBlank())
        val save = if (kind == "lobo") File(context.filesDir, "ghost/$id/ghost/master/profile/dict-savedata.txt")
            else File(context.filesDir, "ghost/$id/ghost/master/yaya_variable.cfg")
        val initialHash = baselineHash(save)
        assertTrue(context.getSharedPreferences("last_ghost", 0).edit().putString("last_ghost", id).commit())
        val runtime = (context.applicationContext as NanidroidApplication).runtime
        val host = NativeShioriHost.process
        ActivityScenario.launch(MainActivity::class.java).use {
            waitUntil(30_000) { runtime.activeNativeLeaseForTest() != null }
            val first = requireNotNull(runtime.activeNativeLeaseForTest())
            when (kind) {
                "lobo" -> {
                    host.request(first, ShioriEvent("OnAssignTalkRate", listOf("60")))
                    assertTrue(host.request(first, ShioriEvent("OnSakuraMenu")).value.orEmpty()
                        .contains("Talkrate: 1 minute"))
                }
                "yaya" -> {
                    waitUntil(30_000, { "YAYA boot event missing: ${host.observationForTest()}" }) {
                        host.observationForTest().events.any { it == "OnFirstBoot" || it == "OnBoot" }
                    }
                    assertEquals(1, host.observationForTest().events.count {
                        it == "OnFirstBoot" || it == "OnBoot"
                    })
                    assertEquals("USER", host.request(first, ShioriEvent("On_username")).value)
                    host.request(first, ShioriEvent("OnNameTeach", listOf("")))
                    assertEquals("User", host.request(first, ShioriEvent("On_username")).value)
                }
            }
            runtime.selectGhost("nanidroid")
            waitUntil(15_000) { (runtime.state.value as? StageState.Ready)?.switchPrompt?.directoryId == "nanidroid" }
            runtime.confirmSwitch()
            waitUntil(45_000) { runtime.activeNativeLeaseForTest() == null &&
                runtime.state.value is StageState.Ready && host.observationForTest().unloads == 1 }
            val switchHash = changedHash(save, kind, initialHash)
            runtime.selectGhost(id!!)
            waitUntil(15_000) { (runtime.state.value as? StageState.Ready)?.switchPrompt?.directoryId == id }
            runtime.confirmSwitch()
            waitUntil(45_000) { runtime.activeNativeLeaseForTest() != null }
            val second = requireNotNull(runtime.activeNativeLeaseForTest())
            assertNotSame(first, second)
            val restored = if (kind == "lobo") host.request(second, ShioriEvent("OnSakuraMenu")).value.orEmpty()
                else host.request(second, ShioriEvent("On_username")).value.orEmpty()
            assertTrue("$kind not restored: $restored", restored.contains(if (kind == "lobo") "Talkrate: 1 minute" else "User"))
            runtime.close()
            waitUntil(45_000) { runtime.state.value is StageState.Finished }
            val backHash = changedHash(save, kind, initialHash)
            writeSaveMarker(kind, save, initialHash, switchHash, backHash)
            val observation = host.observationForTest()
            assertEquals(2, observation.loads)
            assertEquals(2, observation.unloads)
            assertEquals(2, observation.events.count { event -> event == "OnDestroy" })
            println("PERSIST RUNTIME $kind $observation initial=$initialHash switch=$switchHash back=$backHash save=${save.absolutePath}")
        }
    }

    private fun freshRead(kind: String) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = InstrumentationRegistry.getArguments().getString("fixtureId")
        assumeTrue("Run via tools/test-native-persistence.ps1", !id.isNullOrBlank())
        val master = File(context.filesDir, "ghost/$id/ghost/master")
        val save = if (kind == "lobo") File(master, "profile/dict-savedata.txt") else File(master, "yaya_variable.cfg")
        val marker = readSaveMarker(kind)
        assertEquals(save.absolutePath, marker["file"])
        assertEquals(marker["backHash"], changedHash(save, kind, marker["initialHash"].takeUnless { it == "absent" }))
        val host = NativeShioriHost.process
        val nativeKind = if (kind == "lobo") com.cattailsw.nanidroid.engine.NativeKind.KAWARI
            else com.cattailsw.nanidroid.engine.NativeKind.YAYA
        val result = host.load(nativeKind, master, if (kind == "lobo") "shiori.dll" else "yaya.dll")
        assertTrue(result is com.cattailsw.nanidroid.engine.NativeLoadResult.Loaded)
        val lease = (result as com.cattailsw.nanidroid.engine.NativeLoadResult.Loaded).lease
        try {
            val restored = if (kind == "lobo") host.request(lease, ShioriEvent("OnSakuraMenu")).value.orEmpty()
                else host.request(lease, ShioriEvent("On_username")).value.orEmpty()
            assertTrue("$kind fresh process restore: $restored", restored.contains(
                if (kind == "lobo") "Talkrate: 1 minute" else "User"))
            println("PERSIST RUNTIME FRESH_READ $kind hash=${sha(save)} $restored")
        } finally { host.shutdown(lease) }
    }

    @Test fun twoelfRuntimeSwitchBackAndCloseWritesFlag() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = InstrumentationRegistry.getArguments().getString("fixtureId")
        assumeTrue("Run via tools/test-native-persistence.ps1", !id.isNullOrBlank())
        val saved = File(context.filesDir, "ghost/$id/ghost/master/satori_savedata.txt")
        val initialHash = baselineHash(saved)
        assertTrue(context.getSharedPreferences("last_ghost", 0).edit().putString("last_ghost", id).commit())
        val runtime = (context.applicationContext as NanidroidApplication).runtime
        val host = NativeShioriHost.process
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitUntil(30_000) { runtime.activeNativeLeaseForTest() != null }
            val first = requireNotNull(runtime.activeNativeLeaseForTest())
            host.request(first, ShioriEvent("見切れOFF"))
            assertTrue(host.request(first, ShioriEvent("見切れ重なり消滅表示"))
                .value.orEmpty().contains("見切れ：現在 OFF"))
            runtime.selectGhost("nanidroid")
            waitUntil(15_000) { (runtime.state.value as? StageState.Ready)?.switchPrompt?.directoryId == "nanidroid" }
            runtime.confirmSwitch()
            waitUntil(45_000, {
                val state = runtime.state.value
                val ready = state as? StageState.Ready
                "switch-away state=${state.javaClass.simpleName} ghost=${ready?.ghostName} " +
                    "prompt=${ready?.switchPrompt?.directoryId} error=${ready?.activationError} " +
                    "frame=${ready?.frame} " +
                    "lease=${runtime.activeNativeLeaseForTest()} availability=${host.availability.value} " +
                    "host=${host.observationForTest()} activity=${scenario.state}"
            }) { runtime.activeNativeLeaseForTest() == null &&
                runtime.state.value is StageState.Ready && host.observationForTest().unloads == 1 }
            val switchHash = changedHash(saved, "satori", initialHash)
            val afterSwitch = host.observationForTest()
            assertEquals(1, afterSwitch.loads)
            assertEquals(1, afterSwitch.unloads)
            assertEquals(1, afterSwitch.events.count { event -> event == "OnDestroy" })
            runtime.selectGhost(id!!)
            waitUntil(15_000) { (runtime.state.value as? StageState.Ready)?.switchPrompt?.directoryId == id }
            runtime.confirmSwitch()
            waitUntil(45_000) { runtime.activeNativeLeaseForTest() != null }
            val second = requireNotNull(runtime.activeNativeLeaseForTest())
            assertNotSame(first, second)
            assertTrue(host.request(second, ShioriEvent("見切れ重なり消滅表示"))
                .value.orEmpty().contains("見切れ：現在 OFF"))
            runtime.close()
            waitUntil(45_000) { runtime.state.value is StageState.Finished }
            val backHash = changedHash(saved, "satori", initialHash)
            writeSaveMarker("satori", saved, initialHash, switchHash, backHash)
            val afterBack = host.observationForTest()
            assertEquals(2, afterBack.loads)
            assertEquals(2, afterBack.unloads)
            assertEquals(2, afterBack.events.count { event -> event == "OnDestroy" })
            println("PERSIST RUNTIME SWITCH_BACK_CLOSE $afterBack initial=$initialHash switch=$switchHash back=$backHash save=${saved.absolutePath}")
        }
    }

    @Test fun twoelfReadsAfterRuntimeBackInFreshProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = InstrumentationRegistry.getArguments().getString("fixtureId")
        assumeTrue("Run via tools/test-native-persistence.ps1", !id.isNullOrBlank())
        val master = File(context.filesDir, "ghost/$id/ghost/master")
        val save = File(master, "satori_savedata.txt")
        val marker = readSaveMarker("satori")
        assertEquals(save.absolutePath, marker["file"])
        assertEquals(marker["backHash"], changedHash(save, "satori", marker["initialHash"].takeUnless { it == "absent" }))
        val host = NativeShioriHost.process
        val result = host.load(com.cattailsw.nanidroid.engine.NativeKind.SATORI, master, "satori.dll")
        assertTrue(result is com.cattailsw.nanidroid.engine.NativeLoadResult.Loaded)
        val lease = (result as com.cattailsw.nanidroid.engine.NativeLoadResult.Loaded).lease
        try {
            val restored = host.request(lease, ShioriEvent("見切れ重なり消滅表示"))
            assertTrue(restored.value.orEmpty().contains("見切れ：現在 OFF"))
            println("PERSIST RUNTIME FRESH_READ satori hash=${sha(save)} ${restored.value}")
        } finally { host.shutdown(lease) }
    }

    @Test fun twoelfKeepsFlagAndLeaseAcrossRecreationAndDisplayRotation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = InstrumentationRegistry.getArguments().getString("fixtureId")
        assumeTrue("Run via tools/test-native-persistence.ps1", !id.isNullOrBlank())
        val master = File(context.filesDir, "ghost/$id/ghost/master/descript.txt")
        assertTrue("2elf fixture missing: $master", master.isFile)
        assertTrue(context.getSharedPreferences("last_ghost", 0).edit().putString("last_ghost", id).commit())
        val runtime = (context.applicationContext as NanidroidApplication).runtime
        val host = NativeShioriHost.process
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitUntil { (runtime.state.value as? StageState.Ready)?.ghostName?.isNotEmpty() == true &&
                runtime.activeNativeLeaseForTest() != null }
            val lease = requireNotNull(runtime.activeNativeLeaseForTest())
            val baseline = host.request(lease, ShioriEvent("見切れ重なり消滅表示"))
            val change = host.request(lease, ShioriEvent("見切れOFF"))
            val changed = host.request(lease, ShioriEvent("見切れ重なり消滅表示"))
            assertEquals(200, changed.status)
            assertTrue("Flag did not change: ${changed.value}", changed.value.orEmpty().contains("見切れ：現在 OFF"))
            waitUntil(30_000, { "Initial boot event missing: ${host.observationForTest()}" }) {
                host.observationForTest().events.any { it == "OnFirstBoot" || it == "OnBoot" }
            }
            val before = host.observationForTest()
            assertEquals(1, before.events.count { it == "OnFirstBoot" || it == "OnBoot" })
            println("PERSIST ROTATION baseline=${baseline.value} change=${change.value} before=$before")

            scenario.recreate()
            waitUntil { runtime.activeNativeLeaseForTest() === lease && runtime.state.value is StageState.Ready }
            val afterRecreate = host.observationForTest()
            assertEquals(before.loads, afterRecreate.loads)
            assertEquals(before.unloads, afterRecreate.unloads)
            assertEquals(1, afterRecreate.events.count { it == "OnFirstBoot" || it == "OnBoot" })
            assertNoLifecycleEventsAdded(before.events, afterRecreate.events, "Activity recreation")

            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            shell("cmd window user-rotation lock 1")
            try {
                waitUntil {
                    var landscape = false
                    scenario.onActivity {
                        landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    }
                    landscape
                }
                assertSame(lease, runtime.activeNativeLeaseForTest())
                val afterRotation = host.observationForTest()
                assertEquals(before.loads, afterRotation.loads)
                assertEquals(before.unloads, afterRotation.unloads)
                assertEquals(1, afterRotation.events.count { it == "OnFirstBoot" || it == "OnBoot" })
                assertNoLifecycleEventsAdded(before.events, afterRotation.events, "display rotation")
                val restored = host.request(lease, ShioriEvent("見切れ重なり消滅表示"))
                assertTrue(restored.value.orEmpty().contains("見切れ：現在 OFF"))
                assertTrue("App entered fallback", runtime.state.value is StageState.Ready)
                println("PERSIST ROTATION after=$afterRotation restored=${restored.value}")
            } finally {
                shell("cmd window user-rotation lock 0")
            }
        }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
    }

    private fun assertNoLifecycleEventsAdded(before: List<String>, after: List<String>, phase: String) {
        assertTrue("$phase lost prior events: before=$before after=$after", after.size >= before.size)
        assertEquals("$phase changed prior event order", before, after.take(before.size))
        val added = after.drop(before.size)
        assertTrue("$phase added lifecycle events: $added", added.none {
            it == "OnInitialize" || it == "OnFirstBoot" || it == "OnBoot" ||
                it == "OnClose" || it == "OnGhostChanging" ||
                it == "OnGhostChanged" || it == "OnDestroy"
        })
    }

    private fun baselineHash(save: File): String? = save.takeIf(File::isFile)?.let(::sha).also {
        println("PERSIST SAVE BASELINE path=${save.absolutePath} hash=${it ?: "absent"}")
    }

    private fun changedHash(save: File, kind: String, baseline: String?): String {
        assertTrue("Saved file missing: $save", save.isFile)
        val encoding = if (kind == "satori") Charset.forName("Shift_JIS") else Charsets.UTF_8
        val expected = when (kind) {
            "satori" -> "見切れ利用\t０"
            "lobo" -> "talkinterval"
            "yaya" -> "User"
            else -> error("Unknown fixture kind: $kind")
        }
        val contents = save.readText(encoding)
        assertTrue("$kind changed value absent from $save", contents.contains(expected))
        if (kind == "lobo") assertTrue(contents.contains("60"))
        val hash = sha(save)
        if (baseline != null) assertNotEquals("Save bytes did not change from pristine baseline", baseline, hash)
        println("PERSIST SAVE $kind path=${save.absolutePath} hash=$hash bytes=${save.length()}")
        return hash
    }

    private fun writeSaveMarker(kind: String, save: File, initial: String?, switched: String, back: String) {
        val runId = requireNotNull(InstrumentationRegistry.getArguments().getString("runId"))
        val marker = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "native-persistence-$runId-$kind.saved")
        marker.writeText("runId=$runId\nkind=$kind\nfile=${save.absolutePath}\ninitialHash=${initial ?: "absent"}\nswitchHash=$switched\nbackHash=$back\n")
    }

    private fun readSaveMarker(kind: String): Map<String, String> {
        val runId = requireNotNull(InstrumentationRegistry.getArguments().getString("runId"))
        val marker = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "native-persistence-$runId-$kind.saved")
        assertTrue("Missing save marker from runtime Back: $marker", marker.isFile)
        val fields = marker.readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(runId, fields["runId"])
        assertEquals(kind, fields["kind"])
        assertTrue("Switch save hash missing", fields["switchHash"]?.matches(Regex("[0-9a-f]{64}")) == true)
        assertTrue("Back save hash missing", fields["backHash"]?.matches(Regex("[0-9a-f]{64}")) == true)
        return fields
    }

    private fun sha(file: File): String {
        assertTrue("Missing saved bytes: $file", file.isFile)
        return MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }

    private fun waitUntil(
        timeoutMillis: Long = 15_000,
        timeoutDetails: (() -> String)? = null,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for native Activity state or physical orientation: " +
            (timeoutDetails?.invoke() ?: "no phase details"))
    }
}
