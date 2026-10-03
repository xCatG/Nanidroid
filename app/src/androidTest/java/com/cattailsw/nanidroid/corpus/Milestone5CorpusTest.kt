package com.cattailsw.nanidroid.corpus

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.MainActivity
import com.cattailsw.nanidroid.NanidroidApplication
import com.cattailsw.nanidroid.data.PreferencesLastGhostStore
import com.cattailsw.nanidroid.engine.NativeShioriHost
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import com.cattailsw.nanidroid.install.ImportOutcome
import com.cattailsw.nanidroid.runtime.StageState
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One process and one app-data reset per host row; results precede assertions. */
@RunWith(AndroidJUnit4::class)
class Milestone5CorpusTest {
    /** Red until Task 2 permits only the harmless root directory record. */
    @Test fun rootOnlyDirectoryRegression() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "corpus-root-positive-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(root, "input.nar")
            rootFixture(source, "\\", UnixStat.DIR_FLAG, byteArrayOf())
            val result = (context.applicationContext as NanidroidApplication).ghostImporter
                .importArchive({ source.inputStream() }) {}
            assertTrue("Empty root-directory record must be ignored: $result", result is ImportOutcome.Installed)
        } finally { root.deleteRecursively() }
    }

    @Test fun unsafeRootEntryVariantsStayRejected() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "corpus-root-negative-${System.nanoTime()}").apply { mkdirs() }
        try {
            val cases = listOf(
                Triple("root-file", "\\", UnixStat.FILE_FLAG to byteArrayOf(1)),
                Triple("nonempty-root", "\\", UnixStat.DIR_FLAG to byteArrayOf(1)),
                Triple("root-symlink", "\\", UnixStat.LINK_FLAG to byteArrayOf()),
                Triple("absolute-child", "/escape", UnixStat.FILE_FLAG to byteArrayOf(1)),
                Triple("drive-child", "C:/escape", UnixStat.FILE_FLAG to byteArrayOf(1)),
                Triple("traversal", "../escape", UnixStat.FILE_FLAG to byteArrayOf(1)),
            )
            for ((label, name, entry) in cases) {
                val source = File(root, "$label.nar")
                rootFixture(source, name, entry.first, entry.second)
                val target = File(root, label).apply { mkdirs() }
                val outcome = com.cattailsw.nanidroid.install.GhostImporter(target)
                    .importArchive({ source.inputStream() }) {}
                assertTrue("$label must reject: $outcome", outcome is ImportOutcome.Failed)
                assertTrue("$label published", File(target, "ghost").listFiles().isNullOrEmpty())
                assertTrue("$label left staging", File(target, "import-staging").listFiles().isNullOrEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun smokeArchive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        val app = context.applicationContext as NanidroidApplication
        val label = requireNotNull(args.getString("corpusLabel"))
        require(label.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
        val expectedHash = requireNotNull(args.getString("corpusSha256"))
        require(expectedHash.matches(Regex("[0-9a-fA-F]{64}")))
        val inputName = requireNotNull(args.getString("corpusPath"))
        require(inputName.matches(Regex("[a-zA-Z0-9_.-]{1,100}")))
        val input = File(context.filesDir, inputName)
        val output = requireNotNull(context.getExternalFilesDir(null))
        val phaseFile = File(output, "corpus-phase.txt")
        val resultFile = File(output, "corpus-result.json")
        phaseFile.writeText("label=$label\nphase=started\n")
        val result = JSONObject().put("label", label).put("expectedSha256", expectedHash.lowercase())
        try {
            assertTrue("Input missing", input.isFile)
            val actualHash = sha256(input)
            result.put("actualSha256", actualHash)
            phaseFile.appendText("phase=hashed sha256=$actualHash\n")
            assertEquals("Device-boundary hash mismatch", expectedHash.lowercase(), actualHash)
            val kind = args.getString("corpusKind") ?: "ghost"
            if (kind != "ghost") {
                val sentinel = File(context.cacheDir, "corpus-sentinel.nar")
                syntheticGhost(sentinel)
                val prior = runBlocking { app.ghostImporter.importArchive({ sentinel.inputStream() }) {} }
                assertTrue("Sentinel import: $prior", prior is ImportOutcome.Installed)
                phaseFile.appendText("phase=sentinel-installed\n")
            }
            val repository = InstalledGhostRepository(File(context.filesDir, "ghost"))
            val before = repository.list().map { it.directoryId }.toSet()
            var validatedId: String? = null
            val imported = runBlocking {
                app.ghostImporter.importArchive({ input.inputStream() }) {
                    validatedId = it.directoryId
                    phaseFile.appendText("phase=validated id=${it.directoryId} root=${it.rootPrefix}\n")
                }
            }
            result.put("importOutcome", imported.javaClass.simpleName)
                .put("importDetail", imported.toString()).put("validatedId", validatedId)
            phaseFile.appendText("phase=import outcome=$imported\n")
            val after = repository.list().map { it.directoryId }.toSet()
            result.put("installedBefore", before.sorted().joinToString(","))
                .put("installedAfter", after.sorted().joinToString(","))
            val residue = File(context.filesDir, "import-staging").listFiles().orEmpty()
                .filter { it.name.startsWith("attempt-") }
            result.put("stagingResidue", residue.joinToString(",") { it.name })
            assertTrue("Owned staging residue: $residue", residue.isEmpty())
            if (kind != "ghost") {
                assertTrue("Expected rejection: $imported", imported is ImportOutcome.Failed)
                assertEquals("Rejected input changed installed ghosts", before, after)
                assertTrue("Prior sentinel lost", "corpus-sentinel" in after)
                result.put("classification", "expected-rejection")
            } else if (imported is ImportOutcome.Installed) {
                assertTrue("Published ghost missing", imported.directoryId in after)
                val descriptor = repository.validate(imported.directoryId)
                result.put("directoryId", descriptor.directoryId)
                    .put("displayName", descriptor.displayName)
                    .put("engineDeclaration", descriptor.engineDeclaration)
                PreferencesLastGhostStore(context.getSharedPreferences("last_ghost", 0))
                    .write(imported.directoryId)
                phaseFile.appendText("phase=activating id=${imported.directoryId} name=${descriptor.displayName}\n")
                ActivityScenario.launch(MainActivity::class.java).use {
                    val runtime = app.runtime
                    val deadline = SystemClock.elapsedRealtime() + 35_000
                    var ready: StageState.Ready? = null
                    while (SystemClock.elapsedRealtime() < deadline) {
                        ready = runtime.state.value as? StageState.Ready
                        if (ready != null && ready.ghostName.isNotEmpty()) break
                        SystemClock.sleep(100)
                    }
                    assertTrue("Runtime did not become ready: ${runtime.state.value}", ready != null)
                    val first = requireNotNull(ready)
                    result.put("activeGhost", first.ghostName)
                        .put("activationError", first.activationError)
                        .put("surfaceIds", first.surfaces.keys.sorted().joinToString(","))
                        .put("firstBootText", first.frame.sakura.text + first.frame.kero.text)
                    phaseFile.appendText("phase=ready ghost=${first.ghostName} error=${first.activationError} surfaces=${first.surfaces.keys}\n")
                    SystemClock.sleep(1500)
                    val later = runtime.state.value as? StageState.Ready
                    val laterText = later?.frame?.let { frame -> frame.sakura.text + frame.kero.text }.orEmpty()
                    result.put("laterText", laterText)
                    if (first.frame.sakura.text.isEmpty() && first.frame.kero.text.isEmpty() &&
                        laterText.isEmpty()) {
                        val lease = runtime.activeNativeLeaseForTest()
                        if (lease != null) {
                            // One exact boot-event replay to diagnose a silent stage; it does not
                            // establish the response of the earlier activation request.
                            val reply = runBlocking {
                                NativeShioriHost.process.request(lease, ShioriEvent("OnFirstBoot"))
                            }
                            result.put("bootReplayStatus", reply.status)
                                .put("bootReplayValue", reply.value)
                            phaseFile.appendText("phase=boot-replay status=${reply.status} valueLength=${reply.value?.length ?: 0}\n")
                        } else result.put("bootReplayStatus", "no-native-lease")
                    }
                    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
                        "Stage screenshot unavailable"
                    }
                    File(output, "corpus-stage.png").outputStream().use { stream ->
                        assertTrue("PNG compression failed", screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    }
                    screenshot.recycle()
                    phaseFile.appendText("phase=screenshot bytes=${File(output, "corpus-stage.png").length()}\n")
                    runtime.close()
                    phaseFile.appendText("phase=close-requested count=1\n")
                    val closeDeadline = SystemClock.elapsedRealtime() + 25_000
                    while (SystemClock.elapsedRealtime() < closeDeadline &&
                        runtime.state.value !is StageState.Finished) SystemClock.sleep(100)
                    result.put("closeState", runtime.state.value.javaClass.simpleName)
                    assertTrue("Close incomplete: ${runtime.state.value}",
                        runtime.state.value is StageState.Finished)
                    phaseFile.appendText("phase=closed\n")
                }
                result.put("classification", if (result.isNull("activationError")) "supported-smoke"
                    else "in-scope-failure")
            } else result.put("classification", "in-scope-failure")
            result.put("testStatus", "completed")
            resultFile.writeText(result.toString(2))
        } catch (failure: Throwable) {
            result.put("testStatus", "failed")
                .put("failure", "${failure.javaClass.simpleName}: ${failure.message}")
            resultFile.writeText(result.toString(2))
            phaseFile.appendText("phase=failed error=${failure.javaClass.simpleName}: ${failure.message}\n")
            throw failure
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val block = ByteArray(8192)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun syntheticGhost(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            mapOf(
                "install.txt" to "type,ghost\ndirectory,corpus-sentinel\n",
                "ghost/master/descript.txt" to "name,Corpus sentinel\nshiori,Nanidroid\n",
                "shell/master/placeholder.txt" to "placeholder",
            ).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun rootFixture(file: File, name: String, mode: Int, payload: ByteArray) {
        ZipArchiveOutputStream(file).use { zip ->
            val entries = listOf(
                Triple(name, mode, payload),
                Triple("install.txt", UnixStat.FILE_FLAG, "type,ghost\ndirectory,root-test\n".toByteArray()),
                Triple("ghost/master/descript.txt", UnixStat.FILE_FLAG,
                    "name,Root test\nshiori,Nanidroid\n".toByteArray()),
                Triple("ghost/master/ja/content.txt", UnixStat.FILE_FLAG,
                    "OnFirstBoot,\\0Ready\\e".toByteArray()),
                Triple("shell/master/placeholder.txt", UnixStat.FILE_FLAG, byteArrayOf(1)),
            )
            for ((path, unixMode, bytes) in entries) {
                val entry = ZipArchiveEntry(path).apply { this.unixMode = unixMode or 0x1FF }
                zip.putArchiveEntry(entry)
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
    }
}
