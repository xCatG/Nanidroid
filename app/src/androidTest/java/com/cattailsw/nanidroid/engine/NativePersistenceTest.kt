package com.cattailsw.nanidroid.engine

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.ghost.NativeProfileDirectory
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real, data-only corpus tests. The host script stages a unique copy before each case. */
@RunWith(AndroidJUnit4::class)
class NativePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val id get() = requireNotNull(arguments.getString("fixtureId")) { "fixtureId required" }
    private val master get() = File(context.filesDir, "ghost/$id/ghost/master")
    private val host get() = NativeShioriHost.process

    @Before fun requireHostStaging() {
        val args = arguments
        org.junit.Assume.assumeTrue("Run tools/test-native-persistence.ps1; required arguments: fixtureId, runId",
            listOf("fixtureId", "runId").any { args.containsKey(it) })
        require(listOf("fixtureId", "runId").all { !args.getString(it).isNullOrBlank() }) {
            "Provide all nonblank host arguments: fixtureId, runId"
        }
        require(args.getString("fixtureId")!!.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid fixtureId" }
        require(args.getString("runId")!!.matches(Regex("[0-9a-f]{32}"))) { "runId must be 32 lowercase hex characters" }
    }

    @Test fun satoriOrderlyUnloadRestoresChangedFlag() = runBlocking {
        val initial = baseline("satori_savedata.txt", "satori_savedata.sat")
        val first = load(NativeKind.SATORI, "satori.dll")
        try {
            val before = get(first, "見切れ重なり消滅表示")
            val changed = get(first, "見切れOFF")
            val after = get(first, "見切れ重なり消滅表示")
            println("PERSIST SATORI before=$before changed=$changed after=$after")
            assertTrue(after.contains("見切れ：現在 OFF"))
        } finally { shutdown(first) }
        val save = saveFile("satori_savedata.txt", "satori_savedata.sat")
        val hash = sha(save)
        if (initial != null) assertNotEquals("Satori save unchanged from pristine fixture", initial, hash)
        println("PERSIST SATORI save=${save.absolutePath} sha256=$hash")
        assertTrue("Satori saved flag is absent", save.readText(charset("Shift_JIS"))
            .contains("見切れ利用\t０"))
        val second = load(NativeKind.SATORI, "satori.dll")
        try {
            val restored = get(second, "見切れ重なり消滅表示")
            println("PERSIST SATORI restored=$restored")
            assertTrue(restored.contains("見切れ：現在 OFF"))
            assertEquals(hash, sha(save))
        } finally { shutdown(second) }
    }

    @Test fun loboMinuteSaveRestoresTalkInterval() = runBlocking {
        val initial = baseline("profile/dict-savedata.txt")
        val first = load(NativeKind.KAWARI, "shiori.dll")
        val profile = File(master, "profile")
        assertTrue("Nested profile must be prepared before JNI", profile.isDirectory)
        try {
            val before = get(first, "OnSakuraMenu")
            val changed = get(first, "OnAssignTalkRate", listOf("60"))
            val after = get(first, "OnSakuraMenu")
            println("PERSIST LOBO before=$before changed=$changed after=$after")
            assertTrue(after.contains("Talkrate: 1 minute"))
            minute(first)
            val save = File(profile, "dict-savedata.txt")
            assertTrue("OnMinuteChange did not write ${save.absolutePath}", save.isFile)
            assertTrue(save.readText().contains("60"))
            val minuteHash = sha(save)
            if (initial != null) assertNotEquals("LOBO minute save unchanged", initial, minuteHash)
            println("PERSIST LOBO minuteSave=${save.absolutePath} sha256=$minuteHash")
            shutdown(first)
            assertTrue(save.isFile)
            val second = load(NativeKind.KAWARI, "shiori.dll")
            try {
                val restored = get(second, "OnSakuraMenu")
                println("PERSIST LOBO restored=$restored")
                assertTrue(restored.contains("Talkrate: 1 minute"))
                assertEquals(minuteHash, sha(save))
            } finally { shutdown(second) }
        } catch (error: Throwable) {
            if (host.availability.value == NativeAvailability.Occupied) host.shutdown(first)
            throw error
        }
    }

    @Test fun loboDestroyWritesNestedProfile() = runBlocking {
        val initial = baseline("profile/dict-savedata.txt")
        val first = load(NativeKind.KAWARI, "shiori.dll")
        get(first, "OnAssignTalkRate", listOf("60"))
        val save = File(master, "profile/dict-savedata.txt")
        val before = save.takeIf(File::isFile)?.let(::sha)
        shutdown(first)
        assertTrue("OnDestroy did not write ${save.absolutePath}", save.isFile)
        assertTrue(save.readText().contains("60"))
        if (initial != null) assertNotEquals("LOBO destroy save unchanged", initial, sha(save))
        println("PERSIST LOBO destroyBefore=$before destroyAfter=${sha(save)}")
    }

    @Test fun yayaOrderlyUnloadRestoresChangedName() = runBlocking {
        val initial = baseline("yaya_variable.cfg", "yaya_variable.ays")
        val first = load(NativeKind.YAYA, "yaya.dll")
        try {
            val boot = host.request(first, ShioriEvent("OnFirstBoot"))
            println("PERSIST YAYA firstBoot status=${boot.status} value=${boot.value}")
            val before = get(first, "On_username")
            val changed = get(first, "OnNameTeach", listOf(""))
            val after = get(first, "On_username")
            println("PERSIST YAYA before=$before changed=$changed after=$after")
            assertEquals("USER", before)
            assertEquals("User", after)
        } finally { shutdown(first) }
        val save = saveFile("yaya_variable.cfg", "yaya_variable.ays")
        val hash = sha(save)
        if (initial != null) assertNotEquals("YAYA save unchanged from pristine fixture", initial, hash)
        println("PERSIST YAYA save=${save.absolutePath} sha256=$hash")
        assertTrue(save.readText().contains("User"))
        val second = load(NativeKind.YAYA, "yaya.dll")
        try {
            assertEquals("User", get(second, "On_username"))
            assertEquals(hash, sha(save))
        } finally { shutdown(second) }
    }

    /** Host force-stops this still-running instrumentation after reading the readiness marker. */
    @Test fun abruptWriteWaitsForHostKill() = runBlocking {
        val runId = requireNotNull(arguments.getString("runId"))
        val first = load(NativeKind.KAWARI, "shiori.dll")
        get(first, "OnAssignTalkRate", listOf("60"))
        assertTrue(get(first, "OnSakuraMenu").contains("Talkrate: 1 minute"))
        minute(first)
        val save = File(master, "profile/dict-savedata.txt")
        assertTrue(save.isFile)
        assertTrue(save.readText().contains("60"))
        val observation = host.observationForTest()
        assertEquals(0, observation.unloads)
        assertFalse(observation.events.contains("OnDestroy"))
        val marker = File(context.filesDir, "native-persistence-$runId.ready")
        marker.writeText("runId=$runId\npid=${Process.myPid()}\nvalue=60\nsha256=${sha(save)}\nunloads=${observation.unloads}\ndestroys=${observation.events.count { it == "OnDestroy" }}\nfile=${save.absolutePath}\n")
        println("PERSIST KILL READY ${marker.absolutePath} pid=${Process.myPid()}")
        // The host owns the kill. This process deliberately never calls shutdown/unload.
        java.util.concurrent.CountDownLatch(1).await()
        fail("Host released the wait gate without killing the target")
    }

    @Test fun abruptReadUsesFreshProcessAndSavedBytes() = runBlocking {
        val runId = requireNotNull(arguments.getString("runId"))
        val marker = File(context.filesDir, "native-persistence-$runId.ready")
        assertTrue(marker.isFile)
        val fields = marker.readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(runId, fields["runId"])
        assertNotEquals(fields["pid"]?.toInt(), Process.myPid())
        val save = File(master, "profile/dict-savedata.txt")
        assertEquals(fields["sha256"], sha(save))
        val fresh = load(NativeKind.KAWARI, "shiori.dll")
        try {
            val value = get(fresh, "OnSakuraMenu")
            println("PERSIST KILL READ pid=${Process.myPid()} sha256=${sha(save)} value=$value")
            assertTrue(value.contains("Talkrate: 1 minute"))
        } finally { shutdown(fresh) }
    }

    private suspend fun load(kind: NativeKind, library: String): NativeLease {
        assertTrue("Fixture missing: $master", File(master, "descript.txt").isFile)
        NativeProfileDirectory.prepare(master)
        val result = host.load(kind, master, library)
        assertTrue("Load failed: $result", result is NativeLoadResult.Loaded)
        val lease = (result as NativeLoadResult.Loaded).lease
        val init = host.request(lease, ShioriEvent("OnInitialize", method = ShioriMethod.NOTIFY))
        println("PERSIST INIT $kind status=${init.status} value=${init.value}")
        return lease
    }

    private suspend fun get(lease: NativeLease, event: String, references: List<String> = emptyList()): String {
        val reply = host.request(lease, ShioriEvent(event, references))
        println("PERSIST GET $event references=$references status=${reply.status} value=${reply.value}")
        assertEquals("$event status", 200, reply.status)
        return reply.value.orEmpty()
    }

    private suspend fun minute(lease: NativeLease) {
        val reply = host.request(lease, ShioriEvent("OnMinuteChange"))
        assertTrue("OnMinuteChange status=${reply.status}", reply.status == 200 || reply.status == 204)
        println("PERSIST LOBO OnMinuteChange status=${reply.status}")
    }

    private suspend fun shutdown(lease: NativeLease) {
        val result = host.shutdown(lease)
        assertEquals("Native shutdown", NativeShutdownResult.Completed, result)
    }

    private fun saveFile(vararg names: String): File = names.map { File(master, it) }
        .firstOrNull(File::isFile) ?: error("No save file: ${names.joinToString()} in $master")

    private fun baseline(vararg names: String): String? {
        val existing = names.map { File(master, it) }.firstOrNull(File::isFile)
        val hash = existing?.let(::sha)
        println("PERSIST BASELINE candidates=${names.joinToString()} path=${existing?.absolutePath ?: "absent"} hash=${hash ?: "absent"}")
        return hash
    }

    private fun sha(file: File): String {
        assertTrue("Missing saved bytes: $file", file.isFile)
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun charset(name: String) = java.nio.charset.Charset.forName(name)
}
