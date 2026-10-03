package com.cattailsw.nanidroid.install

import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.cattailsw.nanidroid.NanidroidApplication
import com.cattailsw.nanidroid.MainActivity
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.runtime.ImportPromptResult
import com.cattailsw.nanidroid.ui.StageViewModel
import androidx.lifecycle.SavedStateHandle
import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.data.LastGhostStore
import com.cattailsw.nanidroid.engine.BuiltInShiori
import com.cattailsw.nanidroid.engine.EngineKind
import com.cattailsw.nanidroid.engine.EngineSelector
import com.cattailsw.nanidroid.engine.NativeAvailability
import com.cattailsw.nanidroid.engine.NativeShioriHost
import com.cattailsw.nanidroid.engine.ShioriEngine
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.ghost.BundledGhost
import java.io.File
import java.io.FilterInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.io.FileInputStream
import org.json.JSONObject
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GhostImportInstrumentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var root: File
    private val context by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }

    @Before fun createDisposableRoot() {
        if (InstrumentationRegistry.getArguments().containsKey("phase")) return
        root = File(context.cacheDir, "import-device-${UUID.randomUUID()}/files")
        assertTrue(root.mkdirs())
    }

    @After fun removeDisposableRoot() {
        if (::root.isInitialized) root.parentFile?.deleteRecursively()
    }

    /** Host-staged, unchanged corpus input; one invocation per cleared installation. */
    @Test fun measureCorpusImport() { runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Host-only measurement", args.containsKey("measurePath"))
        val name = requireNotNull(args.getString("measurePath"))
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        val label = requireNotNull(args.getString("measureLabel"))
        require(label.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val expected = requireNotNull(args.getString("measureSha256")).lowercase()
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val input = File(context.filesDir, name)
        val output = requireNotNull(context.getExternalFilesDir(null))
        val resultFile = File(output, "import-measure-result.json")
        val result = JSONObject().put("label", label).put("expectedSha256", expected)
        try {
            assertTrue("Staged input missing", input.isFile)
            val actual = sha256(input)
            result.put("actualSha256", actual).put("archiveBytes", input.length())
            assertEquals("Device input hash", expected, actual)
            val importer = GhostImporter(root)
            val ui = Handler(Looper.getMainLooper())
            val largestMainGap = AtomicLong(0)
            val lastMainTick = AtomicLong(0)
            val mainTicks = AtomicInteger(0)
            val polling = AtomicBoolean(true)
            fun tick() {
                if (!polling.get()) return
                val now = SystemClock.elapsedRealtimeNanos()
                val previous = lastMainTick.getAndSet(now)
                if (previous != 0L) largestMainGap.accumulateAndGet((now - previous) / 1_000_000, ::maxOf)
                mainTicks.incrementAndGet()
                ui.postDelayed({ tick() }, 50)
            }
            ui.post { tick() }
            var validatedAt = 0L
            val start = SystemClock.elapsedRealtimeNanos()
            val outcome = importer.importArchive({ input.inputStream() }) {
                validatedAt = SystemClock.elapsedRealtimeNanos()
                result.put("directoryId", it.directoryId)
            }
            val end = SystemClock.elapsedRealtimeNanos()
            polling.set(false)
            result.put("importOutcome", outcome.toString())
                .put("importTotalMs", (end - start) / 1_000_000.0)
                .put("copyInspectUntilValidatedMs", (validatedAt - start) / 1_000_000.0)
                .put("extractValidatePublishCleanupMs", (end - validatedAt) / 1_000_000.0)
                .put("mainTicks", mainTicks.get())
                .put("largestMainTickGapMs", largestMainGap.get())
            assertTrue("Import failed: $outcome", outcome is ImportOutcome.Installed)
            val installed = outcome as ImportOutcome.Installed
            assertTrue("Published tree missing", File(root, "ghost/${installed.directoryId}").isDirectory)
            assertTrue("Owned staging remains", File(root, "import-staging").listFiles().isNullOrEmpty())

            // Phase probes run after the measured import and outside its total duration.
            val reader = NarArchiveReader()
            val inspectStart = SystemClock.elapsedRealtimeNanos()
            val metadata = reader.inspect(input)
            val inspectEnd = SystemClock.elapsedRealtimeNanos()
            val extracted = File(root, "phase-extract").apply { mkdirs() }
            val extractStart = SystemClock.elapsedRealtimeNanos()
            reader.extract(input, metadata, extracted) {}
            val extractEnd = SystemClock.elapsedRealtimeNanos()
            result.put("inspectProbeMs", (inspectEnd - inspectStart) / 1_000_000.0)
                .put("extractProbeMs", (extractEnd - extractStart) / 1_000_000.0)
                .put("expandedBytes", extracted.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                .put("expandedFileCount", extracted.walkTopDown().count { it.isFile })

            val runtimeStart = SystemClock.elapsedRealtimeNanos()
            // A separate copied installation keeps runtime timing out of the archive-processing interval.
            val app = context.applicationContext as NanidroidApplication
            val appGhost = File(context.filesDir, "ghost/${installed.directoryId}")
            appGhost.parentFile!!.mkdirs()
            assertTrue("App ghost already exists", !appGhost.exists())
            assertTrue("Cannot stage runtime tree", File(root, "ghost/${installed.directoryId}").copyRecursively(appGhost))
            com.cattailsw.nanidroid.data.PreferencesLastGhostStore(
                context.getSharedPreferences("last_ghost", 0)).write(installed.directoryId)
            val launchAt = SystemClock.elapsedRealtimeNanos()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val deadline = SystemClock.elapsedRealtime() + 35_000
                var leaseAt = 0L
                var readyAt = 0L
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (leaseAt == 0L && app.runtime.activeNativeLeaseForTest() != null)
                        leaseAt = SystemClock.elapsedRealtimeNanos()
                    val ready = app.runtime.state.value as? StageState.Ready
                    if (ready != null && ready.ghostName.isNotEmpty()) {
                        readyAt = SystemClock.elapsedRealtimeNanos()
                        result.put("runtimeGhost", ready.ghostName)
                            .put("activationError", ready.activationError)
                            .put("readySurfaceCount", ready.surfaces.size)
                        break
                    }
                    SystemClock.sleep(20)
                }
                assertTrue("Runtime did not become ready", readyAt != 0L)
                assertTrue("Ready state has no authored surfaces", result.getInt("readySurfaceCount") > 0)
                result.put("runtimeStageCopyMs", (launchAt - runtimeStart) / 1_000_000.0)
                    .put("nativeLeaseObservedMsFromLaunch", if (leaseAt == 0L) JSONObject.NULL else (leaseAt - launchAt) / 1_000_000.0)
                    .put("readyObservedMsFromLaunch", (readyAt - launchAt) / 1_000_000.0)
                // LoadedImages.Ready exposes the authored character canvas tags. Runtime Ready
                // alone can still show the loading card while images are decoded on this emulator.
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithTag("sakura").fetchSemanticsNodes().isNotEmpty() ||
                        compose.onAllNodesWithTag("kero").fetchSemanticsNodes().isNotEmpty()
                }
                val firstCharacterAt = SystemClock.elapsedRealtimeNanos()
                result.put("firstCharacterSemanticsMsFromLaunch", (firstCharacterAt - launchAt) / 1_000_000.0)
                compose.waitForIdle()
                val captureAt = SystemClock.elapsedRealtimeNanos()
                val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try {
                    val pixels = mutableSetOf<Int>()
                    for (y in 0 until screenshot.height step (screenshot.height / 20).coerceAtLeast(1))
                        for (x in 0 until screenshot.width step (screenshot.width / 20).coerceAtLeast(1))
                            pixels += screenshot.getPixel(x, y)
                    result.put("frameWidth", screenshot.width).put("frameHeight", screenshot.height)
                        .put("sampledDistinctPixels", pixels.size)
                    assertTrue("Captured frame is nearly uniform", pixels.size >= 8)
                    File(output, "import-measure-stage.png").outputStream().use { stream ->
                        assertTrue("PNG encoding failed", screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    }
                    assertTrue("Captured frame is empty", File(output, "import-measure-stage.png").length() > 24)
                } finally { screenshot.recycle() }
                result.put("screenshotCompletedMsFromReady", (SystemClock.elapsedRealtimeNanos() - readyAt) / 1_000_000.0)
                    .put("screenshotStartedMsFromReady", (captureAt - readyAt) / 1_000_000.0)
                app.runtime.close()
            }
            result.put("testStatus", "completed")
        } catch (failure: Throwable) {
            result.put("testStatus", "failed")
                .put("failure", "${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        } finally { resultFile.writeText(result.toString(2)) }
    } }

    @Test fun importedAya5RootEntryGhostUsesOwnUnsupportedStageWithoutNativeLease() = runBlocking {
        val id = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureId"))
        require(id.matches(Regex("[A-Za-z0-9 _-]{1,100}")) && id == id.trim())
        val repository = InstalledGhostRepository(File(context.filesDir, "ghost"))
        val descriptor = repository.validate(id)
        assertEquals("aya5.dll", descriptor.engineDeclaration)
        assertEquals(EngineKind.UNSUPPORTED, EngineSelector.select(descriptor))
        val host = NativeShioriHost.process
        assertEquals(NativeAvailability.Available, host.availability.value)
        assertTrue(context.getSharedPreferences("last_ghost", android.content.Context.MODE_PRIVATE)
            .edit().putString("last_ghost", id).commit())
        val runtime = (context.applicationContext as NanidroidApplication).runtime
        try {
            runtime.start("en")
            runtime.setResumed(true)
            val message = "This ghost uses a SHIORI Nanidroid does not support yet."
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val ready = runtime.state.value as? StageState.Ready
                if (ready?.frame?.sakura?.text?.contains(message) == true) break
                SystemClock.sleep(50)
            }
            val ready = runtime.state.value as StageState.Ready
            println("ROOT_ENTRY_DIAG id=$id ghost=${ready.ghostName} surfaces=${ready.surfaces.size} message=${ready.frame.sakura.text} lease=${runtime.activeNativeLeaseForTest()} availability=${host.availability.value}")
            assertEquals(descriptor.displayName, ready.ghostName)
            assertTrue(ready.surfaces.isNotEmpty())
            assertTrue(ready.frame.sakura.text.contains(message))
            assertNull(runtime.activeNativeLeaseForTest())
            assertEquals(NativeAvailability.Available, host.availability.value)
            runtime.selectGhost("nanidroid")
            val promptDeadline = SystemClock.elapsedRealtime() + 20_000
            while ((runtime.state.value as? StageState.Ready)?.switchPrompt?.directoryId != "nanidroid" &&
                SystemClock.elapsedRealtime() < promptDeadline) SystemClock.sleep(50)
            assertEquals("nanidroid", (runtime.state.value as StageState.Ready).switchPrompt?.directoryId)
            runtime.confirmSwitch()
            val switchDeadline = SystemClock.elapsedRealtime() + 30_000
            while ((runtime.state.value as? StageState.Ready)?.ghostName != "Nanidroid" &&
                SystemClock.elapsedRealtime() < switchDeadline) SystemClock.sleep(50)
            println("ROOT_ENTRY_DIAG switched=${(runtime.state.value as? StageState.Ready)?.ghostName} lease=${runtime.activeNativeLeaseForTest()} availability=${host.availability.value}")
            assertEquals("Nanidroid", (runtime.state.value as StageState.Ready).ghostName)
            assertNull(runtime.activeNativeLeaseForTest())
        } finally {
            runtime.close()
            val closeDeadline = SystemClock.elapsedRealtime() + 30_000
            while (runtime.state.value !is StageState.Finished &&
                SystemClock.elapsedRealtime() < closeDeadline) SystemClock.sleep(50)
            println("ROOT_ENTRY_DIAG close=${runtime.state.value.javaClass.simpleName} availability=${host.availability.value}")
            assertTrue(runtime.state.value is StageState.Finished)
            assertEquals(NativeAvailability.Available, host.availability.value)
        }
    }

    @Test fun unknownSizeProviderSuppliesExactBytesForFreshInstall() = runBlocking {
        val bytes = archive("devicefresh")
        val uri = GhostImportTestProvider.uri(bytes, "exact")
        context.contentResolver.query(uri, null, null, null, null).use { cursor ->
            assertTrue(cursor!!.moveToFirst())
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        val outcome = GhostImporter(root).importArchive({ context.contentResolver.openInputStream(uri)!! }) {}
        assertEquals(ImportOutcome.Installed("devicefresh"), outcome)
        assertTrue(File(root, "ghost/devicefresh/ghost/master/descript.txt").isFile)
        assertFalse(File(root, "import-staging").listFiles()?.any() == true)
    }

    @Test fun slowAndUnreadableProvidersLeaveNoPartialInstall() = runBlocking {
        val bytes = archive("deviceslow", padding = 4096)
        val slow = GhostImportTestProvider.uri(bytes, "slow")
        assertEquals(ImportOutcome.Installed("deviceslow"), GhostImporter(root).importArchive(
            { context.contentResolver.openInputStream(slow)!! }) {})
        for (mode in listOf("open-fail", "read-fail")) {
            val uri = GhostImportTestProvider.uri(archive("devicefailed", padding = 4096), mode)
            val outcome = GhostImporter(root).importArchive(
                { context.contentResolver.openInputStream(uri)!! }) {}
            assertTrue("$mode: $outcome", outcome is ImportOutcome.Failed)
            assertFalse(File(root, "ghost/devicefailed").exists())
        }
        assertFalse(File(root, "import-staging").listFiles()?.any() == true)
    }

    @Test fun cancellationAndMalformedArchiveDoNotPublish() = runBlocking {
        val uri = GhostImportTestProvider.uri(archive("devicecancel"), "exact")
        assertEquals(ImportOutcome.Cancelled, GhostImporter(root).importArchive(
            { context.contentResolver.openInputStream(uri)!! }) { throw CancellationException("test cancel") })
        assertFalse(File(root, "ghost/devicecancel").exists())
        val malformed = GhostImportTestProvider.uri(byteArrayOf(1, 2, 3), "exact")
        assertTrue(GhostImporter(root).importArchive(
            { context.contentResolver.openInputStream(malformed)!! }) {} is ImportOutcome.Failed)
        assertFalse(File(root, "import-staging").listFiles()?.any() == true)
    }

    @Test fun repeatedPickerCallbackAndImportClickPublishAndNotifyOnce() = runBlocking {
        val id = "repeat${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val gateId = UUID.randomUUID().toString()
        val uri = GhostImportTestProvider.gatedUri(archive(id, padding = 4096), gateId)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = mutableListOf<ShioriEvent>()
        val activations = mutableListOf<String>()
        val runtime = com.cattailsw.nanidroid.runtime.GhostRuntime(
            loadGhost = { BundledGhost("nanidroid", "Nanidroid", "", "", emptyMap(), "") },
            engineFactory = { object : ShioriEngine {
                override suspend fun request(event: ShioriEvent): ShioriReply {
                    synchronized(events) { events += event }
                    return ShioriReply(204)
                }
            } },
            bootState = object : BootStateStore {
                override suspend fun recordActivation(directoryId: String): Boolean {
                    synchronized(activations) { activations += directoryId }
                    return false
                }
                override suspend fun consumeOnboarding() = false
            },
            scope = scope,
            installedGhosts = InstalledGhostRepository(File(root, "ghost")),
            elapsedRealtime = { 0L },
        )
        val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
        val preferences = context.getSharedPreferences("repeated-import-$id", android.content.Context.MODE_PRIVATE)
        val model = StageViewModel(runtime, coordinator, SavedStateHandle(),
            ImportAttemptStore.fromPreferences(preferences))
        val sourceOpens = AtomicInteger()
        val pickerLaunches = AtomicInteger()
        val completions = AtomicInteger()
        val prompts = AtomicInteger()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var completed = false
            coordinator.state.collect { state ->
                val now = state is ImportState.Completed
                if (now && !completed) completions.incrementAndGet()
                completed = now
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var shown = false
            runtime.state.collect { state ->
                val now = (state as? StageState.Ready)?.switchPrompt?.directoryId == id
                if (now && !shown) prompts.incrementAndGet()
                shown = now
            }
        }
        val host = Any()
        try {
            runtime.start("ja")
            assertTrue(runtime.state.value is StageState.Ready)
            model.setImportActivityStarted(host, true)
            model.restoreAttempt()
            model.launchImport { pickerLaunches.incrementAndGet() }
            model.launchImport { pickerLaunches.incrementAndGet() }
            assertEquals(1, pickerLaunches.get())
            val attempt = (coordinator.state.value as ImportState.Picking).attemptId
            val source = {
                sourceOpens.incrementAndGet()
                context.contentResolver.openInputStream(uri)!!
            }
            model.submitPickerResult(source)
            model.submitPickerResult(source)
            val blocked = withTimeoutOrNull(10_000) {
                while (true) {
                    val waiting = context.contentResolver.call(uri, "gate-status", gateId, null)
                        ?.getBoolean("blocked") == true
                    val partial = File(root, "import-staging").listFiles()
                        ?.any { File(it, "source.nar").length() > 0L } == true
                    if (waiting && partial) break
                    delay(20)
                }
                true
            }
            assertTrue("First provider copy did not reach its read gate", blocked == true)
            assertEquals(ImportState.Running(attempt), coordinator.state.value)
            model.submitPickerResult(source)
            model.launchImport { pickerLaunches.incrementAndGet() }
            assertEquals(1, pickerLaunches.get())
            assertEquals(1, sourceOpens.get())
            releaseProviderGate(uri, gateId)
            val completed = withTimeout(10_000) {
                while (true) {
                    val state = coordinator.state.value as? ImportState.Completed
                    if (state?.promptResult == ImportPromptResult.Shown) break
                    delay(20)
                }
                coordinator.state.value as ImportState.Completed
            }
            assertEquals(attempt, completed.attemptId)
            assertEquals(ImportOutcome.Installed(id), completed.outcome)
            withTimeout(10_000) {
                while (synchronized(events) { events.count { it.id.startsWith("OnInstall") } } < 2) delay(20)
            }
            model.acceptPickerResult(source)
            assertEquals(1, sourceOpens.get())
            assertEquals(1, File(root, "ghost").listFiles()?.count { it.name == id })
            assertTrue(File(root, "ghost/$id/ghost/master/descript.txt").isFile)
            assertFalse(File(root, "import-staging").listFiles()?.any { it.name.startsWith("attempt-") } == true)
            assertEquals(listOf(
                ShioriEvent("OnInstallBegin", listOf("ghost", id, id)),
                ShioriEvent("OnInstallComplete", listOf("ghost", id, id)),
            ), synchronized(events) { events.filter { it.id.startsWith("OnInstall") }.toList() })
            val stage = runtime.state.value as StageState.Ready
            assertEquals(id, stage.switchPrompt?.directoryId)
            assertEquals("Nanidroid", stage.ghostName)
            assertEquals(listOf("nanidroid"), synchronized(activations) { activations.toList() })
            assertEquals(1, completions.get())
            assertEquals(1, prompts.get())
        } finally {
            releaseProviderGate(uri, gateId)
            model.setImportActivityStarted(host, false)
            coordinator.cancelBeforePublication()
            scope.cancel()
            preferences.edit().clear().commit()
        }
    }

    @Test fun importedBuiltInGhostSwitchesAfterPrompt() = runBlocking {
        val id = "switch${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val uri = GhostImportTestProvider.uri(
            archive(id, builtinContent = "OnGhostChanged,\\0Imported ghost\\e\n"), "exact")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val last = object : LastGhostStore {
            private val directoryId = AtomicReference<String?>()
            override fun read() = directoryId.get()
            override fun write(directoryId: String) { this.directoryId.set(directoryId) }
        }
        val runtime = com.cattailsw.nanidroid.runtime.GhostRuntime(
            loadGhost = { BundledGhost("nanidroid", "Nanidroid", "", "", emptyMap(), "") },
            engineFactory = { BuiltInShiori(it.content) },
            bootState = object : BootStateStore {
                override suspend fun recordActivation(directoryId: String) = false
                override suspend fun consumeOnboarding() = false
            },
            scope = scope,
            installedGhosts = InstalledGhostRepository(File(root, "ghost")),
            lastGhostStore = last,
            elapsedRealtime = { 0L },
        )
        val coordinator = ImportCoordinator(GhostImporter(root), runtime, scope)
        try {
            runtime.start("ja")
            assertEquals("nanidroid", last.read())
            val attempt = coordinator.beginPicking()!!
            coordinator.acceptResult(attempt) { context.contentResolver.openInputStream(uri)!! }
            withTimeout(10_000) {
                while ((coordinator.state.value as? ImportState.Completed)?.promptResult !=
                    ImportPromptResult.Shown) delay(20)
            }
            assertEquals(id, (runtime.state.value as StageState.Ready).switchPrompt?.directoryId)
            runtime.confirmSwitch()
            withTimeout(10_000) {
                while (last.read() != id ||
                    (runtime.state.value as? StageState.Ready)?.ghostName != "Device test") delay(20)
            }
            val switched = runtime.state.value as StageState.Ready
            assertEquals("Device test", switched.ghostName)
            assertNull(switched.switchPrompt)
            assertNull(switched.activationError)
            assertTrue(switched.installedGhosts.any { it.directoryId == id })
        } finally {
            coordinator.cancelBeforePublication()
            scope.cancel()
        }
    }

    @Test fun cancellationInsideProviderReadClosesSourceAndRemovesStaging() = runBlocking {
        val gateId = UUID.randomUUID().toString()
        val uri = GhostImportTestProvider.gatedUri(archive("deviceblocked", padding = 4096), gateId)
        val enteredSecondRead = CountDownLatch(1)
        val readCalls = AtomicInteger()
        val sourceClosed = AtomicBoolean()
        val returned = CompletableDeferred<ImportOutcome>()
        val job = launch(Dispatchers.Default) {
            returned.complete(GhostImporter(root).importArchive({
                object : FilterInputStream(context.contentResolver.openInputStream(uri)!!) {
                    override fun read(bytes: ByteArray): Int {
                        if (readCalls.incrementAndGet() == 2) enteredSecondRead.countDown()
                        return super.read(bytes)
                    }

                    override fun close() {
                        sourceClosed.set(true)
                        super.close()
                    }
                }
            }) {})
        }
        try {
            assertTrue("Importer never entered the blocked provider read",
                enteredSecondRead.await(5, TimeUnit.SECONDS))
            assertFalse("Importer completed while the provider gate was closed", returned.isCompleted)
            job.cancel()
            assertEquals(ImportOutcome.Cancelled, withTimeout(2_000) { returned.await() })
            assertTrue("Client provider stream was not closed", sourceClosed.get())
            assertFalse(File(root, "ghost/deviceblocked").exists())
            assertFalse(File(root, "import-staging").listFiles()?.any() == true)
        } finally {
            context.contentResolver.call(uri, "release-read", gateId, null)
            withTimeout(5_000) { job.join() }
        }
    }

    @Test fun activityRotationKeepsBlockedCopyAndPublishesOnce() = runBlocking {
        val id = "rotate${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val gateId = UUID.randomUUID().toString()
        val uri = GhostImportTestProvider.gatedUri(archive(id, padding = 4096), gateId)
        val app = context as NanidroidApplication
        val sourceClosed = AtomicBoolean()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var attempt: String? = null
        var assertionsPassed = false
        try {
            awaitScenarioState(scenario, Lifecycle.State.RESUMED)
            attempt = app.importCoordinator.beginPicking() ?: throw AssertionError("Could not begin import")
            app.importCoordinator.acceptResult(attempt, trackedSource(uri, sourceClosed))
            awaitProviderGate(uri, gateId)
            assertTrue(app.importCoordinator.state.value is ImportState.Running)
            scenario.recreate()
            awaitScenarioState(scenario, Lifecycle.State.RESUMED)
            assertEquals(ImportState.Running(attempt), app.importCoordinator.state.value)
            assertFalse("Rotation closed the active stream", sourceClosed.get())
            releaseProviderGate(uri, gateId)
            val completed = awaitCompletion(app)
            assertEquals(attempt, completed.attemptId)
            assertEquals(ImportOutcome.Installed(id), completed.outcome)
            assertTrue(sourceClosed.get())
            assertTrue(File(context.filesDir, "ghost/$id/ghost/master/descript.txt").isFile)
            assertNoOwnedStaging()
            assertionsPassed = true
        } finally {
            app.importCoordinator.cancelBeforePublication()
            releaseProviderGate(uri, gateId)
            val settled = awaitOwnedImportSettled(app, attempt)
            if (settled) {
                (app.importCoordinator.state.value as? ImportState.Completed)?.let {
                    app.importCoordinator.acknowledge(it.attemptId)
                }
            }
            scenario.close()
            if (settled) File(context.filesDir, "ghost/$id").deleteRecursively()
            else {
                Log.e("ImportLifecycle", "Rotation-copy cleanup timed out; retained synthetic installation")
                if (assertionsPassed) throw AssertionError("Import did not settle before cleanup")
            }
        }
    }

    @Test fun activityRotationDuringGrowingExtractionCompletesOneAttempt() = runBlocking {
        val id = "extractrotate${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val expectedBytes = 120L * 1024 * 1024
        val source = File(context.cacheDir, "$id.nar")
        val expectedHash = largeExtractionArchive(source, id, expectedBytes)
        val installed = File(context.filesDir, "ghost/$id")
        val app = context as NanidroidApplication
        val sourceClosed = AtomicBoolean()
        val completionCount = AtomicInteger()
        val promptCount = AtomicInteger()
        val stopPartial = AtomicLong(-1)
        val stopWasConfigurationChange = AtomicBoolean()
        val trackedAttempt = AtomicReference<String>()
        val collector = launch(Dispatchers.Default) {
            var completed = false
            app.importCoordinator.state.collect { state ->
                val now = state is ImportState.Completed && state.attemptId == trackedAttempt.get()
                if (now && !completed) completionCount.incrementAndGet()
                completed = now
            }
        }
        val promptCollector = launch(Dispatchers.Default) {
            var shown = false
            app.runtime.state.collect { state ->
                val now = (state as? StageState.Ready)?.switchPrompt?.directoryId == id
                if (now && !shown) promptCount.incrementAndGet()
                shown = now
            }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var assertionsPassed = false
        try {
            awaitScenarioState(scenario, Lifecycle.State.RESUMED)
            lateinit var viewModel: StageViewModel
            scenario.onActivity { activity ->
                viewModel = activityStageViewModel(activity)
            }
            var attempt: String? = null
            withTimeout(15_000) {
                while (attempt == null) {
                    scenario.onActivity { attempt = viewModel.beginImport() }
                    if (attempt == null) delay(20)
                }
            }
            val acceptedAttempt = requireNotNull(attempt)
            trackedAttempt.set(acceptedAttempt)
            Log.i("ImportExtractRotation", "phase=begin attempt=$acceptedAttempt id=$id")
            viewModel.submitPickerResult {
                object : FilterInputStream(FileInputStream(source)) {
                    override fun close() {
                        try { super.close() } finally { sourceClosed.set(true) }
                    }
                }
            }
            var partial = 0L
            val partialPath = withTimeout(120_000) {
                while (true) {
                    val paths = File(context.filesDir, "import-staging").listFiles()
                        ?.filter { it.name.startsWith("attempt-") }
                        ?.map { File(it, "tree/$id/shell/master/payload.bin") }
                        .orEmpty()
                    val growing = paths.singleOrNull { it.isFile && it.length() in 1 until expectedBytes / 2 }
                    if (growing != null) {
                        partial = growing.length()
                        break
                    }
                    delay(5)
                }
                File(context.filesDir, "import-staging").listFiles()!!
                    .single { it.name.startsWith("attempt-") }
                    .resolve("tree/$id/shell/master/payload.bin")
            }
            assertEquals(ImportState.Running(acceptedAttempt), app.importCoordinator.state.value)
            assertTrue("Source stream must close before extraction", sourceClosed.get())
            Log.i("ImportExtractRotation", "phase=partial bytes=$partial expected=$expectedBytes activity=${scenario.state} path=${partialPath.name}")
            scenario.onActivity { activity ->
                activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) {
                        stopPartial.set(partialPath.length())
                        stopWasConfigurationChange.set(activity.isChangingConfigurations)
                        Log.i("ImportExtractRotation", "phase=onStop bytes=${stopPartial.get()} changing=${activity.isChangingConfigurations}")
                    }
                })
            }
            scenario.recreate()
            awaitScenarioState(scenario, Lifecycle.State.RESUMED)
            scenario.onActivity { activity -> assertSame(viewModel, activityStageViewModel(activity)) }
            assertTrue("Rotation reached ON_STOP after extraction ended: ${stopPartial.get()}",
                stopPartial.get() in 1 until expectedBytes)
            assertTrue("Rotation ON_STOP was not a configuration change", stopWasConfigurationChange.get())
            val result = awaitCompletion(app)
            Log.i("ImportExtractRotation", "phase=result attempt=${result.attemptId} outcome=${result.outcome} activity=${scenario.state}")
            assertEquals(acceptedAttempt, result.attemptId)
            assertEquals(ImportOutcome.Installed(id), result.outcome)
            val prompted = withTimeout(10_000) {
                while (true) {
                    val current = app.importCoordinator.state.value as? ImportState.Completed
                    if (current?.promptResult != null) break
                    delay(20)
                }
                app.importCoordinator.state.value as ImportState.Completed
            }
            assertEquals(id, prompted.pendingDirectoryId)
            assertEquals(ImportPromptResult.Shown, prompted.promptResult)
            withTimeout(5_000) { while (promptCount.get() == 0) delay(20) }
            assertEquals(expectedBytes, File(installed, "shell/master/payload.bin").length())
            assertEquals(expectedHash, sha256(File(installed, "shell/master/payload.bin")))
            assertEquals(1, completionCount.get())
            assertEquals(1, promptCount.get())
            assertEquals(id, (app.runtime.state.value as StageState.Ready).switchPrompt?.directoryId)
            Log.i("ImportExtractRotation", "phase=verified completions=${completionCount.get()} prompts=${promptCount.get()} bytes=$expectedBytes")
            assertNoOwnedStaging()
            assertionsPassed = true
        } finally {
            app.importCoordinator.cancelBeforePublication()
            val settled = awaitOwnedImportSettled(app, trackedAttempt.get())
            if (settled) {
                app.runtime.dismissSwitch()
                (app.importCoordinator.state.value as? ImportState.Completed)?.let {
                    app.importCoordinator.acknowledge(it.attemptId)
                }
            }
            app.runtime.close()
            withTimeout(90_000) {
                while (app.runtime.state.value !is StageState.Finished) delay(20)
            }
            scenario.close()
            collector.cancelAndJoin()
            promptCollector.cancelAndJoin()
            if (settled) {
                installed.deleteRecursively()
                source.delete()
            } else {
                Log.e("ImportExtractRotation", "Cleanup timed out; retained synthetic source/installation")
                if (assertionsPassed) throw AssertionError("Import did not settle before cleanup")
            }
            assertNull("Extraction rotation retained the application runtime's native lease",
                app.runtime.activeNativeLeaseForTest())
            assertEquals("Extraction rotation retained native host ownership", NativeAvailability.Available,
                NativeShioriHost.process.availability.value)
        }
    }

    @Test fun homeStopCancelsBlockedCopyAndClosesStream() = runBlocking {
        assertPhysicalStopCancels("HOME", "input keyevent HOME")
    }

    @Test fun explicitScreenOffStopCancelsBlockedCopyAndClosesStream() = runBlocking {
        assertPhysicalStopCancels("screen-off", "input keyevent POWER", wakeAfter = true)
    }

    @Test fun notificationShadePauseKeepsCopyRunningWhenActivityRemainsStarted() = runBlocking {
        val id = "shade${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val gateId = UUID.randomUUID().toString()
        val uri = GhostImportTestProvider.gatedUri(archive(id, padding = 4096), gateId)
        val app = context as NanidroidApplication
        val sourceClosed = AtomicBoolean()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var attempt: String? = null
        var assertionsPassed = false
        try {
            awaitScenarioState(scenario, Lifecycle.State.RESUMED)
            attempt = app.importCoordinator.beginPicking() ?: throw AssertionError("Could not begin import")
            app.importCoordinator.acceptResult(attempt, trackedSource(uri, sourceClosed))
            awaitProviderGate(uri, gateId)
            shell("cmd statusbar expand-notifications")
            // On some emulator builds this overlay leaves the Activity resumed or stops it.
            // Only a STARTED state proves the intended pause-only lifecycle boundary.
            val pausedOnly = withTimeoutOrNull(3_000) {
                while (scenario.state == Lifecycle.State.RESUMED) delay(20)
                scenario.state == Lifecycle.State.STARTED
            } == true
            assumeTrue("Notification shade did not yield PAUSE without STOP: ${scenario.state}", pausedOnly)
            assertEquals(ImportState.Running(attempt), app.importCoordinator.state.value)
            releaseProviderGate(uri, gateId)
            assertEquals(ImportOutcome.Installed(id), awaitCompletion(app).outcome)
            assertTrue(sourceClosed.get())
            assertNoOwnedStaging()
            assertionsPassed = true
        } finally {
            shell("cmd statusbar collapse")
            app.importCoordinator.cancelBeforePublication()
            releaseProviderGate(uri, gateId)
            val settled = awaitOwnedImportSettled(app, attempt)
            if (settled) {
                (app.importCoordinator.state.value as? ImportState.Completed)?.let {
                    app.importCoordinator.acknowledge(it.attemptId)
                }
            }
            scenario.close()
            if (settled) File(context.filesDir, "ghost/$id").deleteRecursively()
            else {
                Log.e("ImportLifecycle", "Shade cleanup timed out; retained synthetic installation")
                if (assertionsPassed) throw AssertionError("Import did not settle before cleanup")
            }
        }
    }

    @Test fun pauseWithoutStopKeepsActiveProviderCopyAndCompletesOnce() = runBlocking {
        val id = "pause${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val gateId = UUID.randomUUID().toString()
        val uri = GhostImportTestProvider.gatedUri(archive(id, padding = 4096), gateId)
        val app = context as NanidroidApplication
        val sourceClosed = AtomicBoolean()
        val pauses = AtomicInteger()
        val stops = AtomicInteger()
        val completions = AtomicInteger()
        val prompts = AtomicInteger()
        val trackedAttempt = AtomicReference<String>()
        val completionCollector = launch(Dispatchers.Default) {
            var completed = false
            app.importCoordinator.state.collect { state ->
                val now = state is ImportState.Completed && state.attemptId == trackedAttempt.get()
                if (now && !completed) completions.incrementAndGet()
                completed = now
            }
        }
        val promptCollector = launch(Dispatchers.Default) {
            var shown = false
            app.runtime.state.collect { state ->
                val now = (state as? StageState.Ready)?.switchPrompt?.directoryId == id
                if (now && !shown) prompts.incrementAndGet()
                shown = now
            }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var assertionsPassed = false
        try {
            awaitScenarioState(scenario, Lifecycle.State.RESUMED)
            lateinit var viewModel: StageViewModel
            scenario.onActivity { activity ->
                viewModel = activityStageViewModel(activity)
                activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_PAUSE) pauses.incrementAndGet()
                    if (event == Lifecycle.Event.ON_STOP) stops.incrementAndGet()
                })
            }
            var attempt: String? = null
            withTimeout(15_000) {
                while (attempt == null) {
                    scenario.onActivity { attempt = viewModel.beginImport() }
                    if (attempt == null) delay(20)
                }
            }
            val acceptedAttempt = requireNotNull(attempt)
            trackedAttempt.set(acceptedAttempt)
            viewModel.submitPickerResult(trackedSource(uri, sourceClosed))
            awaitProviderGate(uri, gateId)
            Log.i("ImportPauseOnly", "phase=copy attempt=$acceptedAttempt activity=${scenario.state}")
            scenario.moveToState(Lifecycle.State.STARTED)
            assertEquals(Lifecycle.State.STARTED, scenario.state)
            assertEquals("Expected one ON_PAUSE", 1, pauses.get())
            assertEquals("Pause-only transition reached ON_STOP", 0, stops.get())
            assertEquals(ImportState.Running(acceptedAttempt), app.importCoordinator.state.value)
            assertFalse("Pause-only transition closed the source", sourceClosed.get())
            Log.i("ImportPauseOnly", "phase=paused pauses=${pauses.get()} stops=${stops.get()} state=${app.importCoordinator.state.value}")
            releaseProviderGate(uri, gateId)
            val result = awaitCompletion(app)
            assertEquals(acceptedAttempt, result.attemptId)
            assertEquals(ImportOutcome.Installed(id), result.outcome)
            val prompted = withTimeout(10_000) {
                while (true) {
                    val current = app.importCoordinator.state.value as? ImportState.Completed
                    if (current?.promptResult != null) break
                    delay(20)
                }
                app.importCoordinator.state.value as ImportState.Completed
            }
            assertEquals(ImportPromptResult.Shown, prompted.promptResult)
            withTimeout(5_000) { while (prompts.get() == 0) delay(20) }
            assertEquals(1, completions.get())
            assertEquals(1, prompts.get())
            assertEquals(0, stops.get())
            assertTrue(sourceClosed.get())
            assertTrue(ByteArray(4096) { (it % 251).toByte() }
                .contentEquals(File(context.filesDir, "ghost/$id/shell/master/placeholder.txt").readBytes()))
            assertNoOwnedStaging()
            Log.i("ImportPauseOnly", "phase=verified completions=${completions.get()} prompts=${prompts.get()} activity=${scenario.state}")
            assertionsPassed = true
        } finally {
            app.importCoordinator.cancelBeforePublication()
            releaseProviderGate(uri, gateId)
            val settled = awaitOwnedImportSettled(app, trackedAttempt.get())
            scenario.moveToState(Lifecycle.State.RESUMED)
            if (settled) {
                app.runtime.dismissSwitch()
                (app.importCoordinator.state.value as? ImportState.Completed)?.let {
                    app.importCoordinator.acknowledge(it.attemptId)
                }
            }
            app.runtime.close()
            withTimeout(90_000) {
                while (app.runtime.state.value !is StageState.Finished) delay(20)
            }
            scenario.close()
            assertNull("Activity close retained the application runtime's native lease",
                app.runtime.activeNativeLeaseForTest())
            assertEquals("Activity close retained native host ownership", NativeAvailability.Available,
                NativeShioriHost.process.availability.value)
            completionCollector.cancelAndJoin()
            promptCollector.cancelAndJoin()
            if (settled) {
                File(context.filesDir, "ghost/$id").deleteRecursively()
            } else {
                Log.e("ImportPauseOnly", "Cleanup timed out; retained synthetic installation")
                if (assertionsPassed) throw AssertionError("Import did not settle before cleanup")
            }
        }
        Unit
    }

    private suspend fun assertPhysicalStopCancels(
        action: String, command: String, wakeAfter: Boolean = false,
    ) {
        val id = "stop${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val gateId = UUID.randomUUID().toString()
        val uri = GhostImportTestProvider.gatedUri(archive(id, padding = 4096), gateId)
        val app = context as NanidroidApplication
        val sourceClosed = AtomicBoolean()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var attempt: String? = null
        var assertionsPassed = false
        try {
            awaitScenarioState(scenario, Lifecycle.State.RESUMED)
            attempt = app.importCoordinator.beginPicking() ?: throw AssertionError("Could not begin import")
            app.importCoordinator.acceptResult(attempt, trackedSource(uri, sourceClosed))
            awaitProviderGate(uri, gateId)
            assertTrue(app.importCoordinator.state.value is ImportState.Running)
            shell(command)
            awaitScenarioState(scenario, Lifecycle.State.CREATED)
            releaseProviderGate(uri, gateId)
            val completed = awaitCompletion(app)
            assertEquals("$action should cancel at onStop", ImportOutcome.Cancelled, completed.outcome)
            assertTrue("$action left the source stream open", sourceClosed.get())
            assertFalse(File(context.filesDir, "ghost/$id").exists())
            assertNoOwnedStaging()
            assertionsPassed = true
        } finally {
            app.importCoordinator.cancelBeforePublication()
            releaseProviderGate(uri, gateId)
            val settled = awaitOwnedImportSettled(app, attempt)
            if (wakeAfter) shell("input keyevent POWER")
            if (settled) {
                (app.importCoordinator.state.value as? ImportState.Completed)?.let {
                    app.importCoordinator.acknowledge(it.attemptId)
                }
            }
            scenario.close()
            if (settled) File(context.filesDir, "ghost/$id").deleteRecursively()
            else {
                Log.e("ImportLifecycle", "$action cleanup timed out; retained synthetic installation")
                if (assertionsPassed) throw AssertionError("Import did not settle before cleanup")
            }
        }
    }

    private fun trackedSource(uri: android.net.Uri, closed: AtomicBoolean): () -> java.io.InputStream = {
        object : FilterInputStream(context.contentResolver.openInputStream(uri)!!) {
            override fun close() {
                try { super.close() } finally { closed.set(true) }
            }
        }
    }

    private suspend fun awaitProviderGate(uri: android.net.Uri, gateId: String) {
        val entered = withTimeoutOrNull(10_000) {
            while (true) {
                val providerWaiting = context.contentResolver.call(uri, "gate-status", gateId, null)
                    ?.getBoolean("blocked") == true
                val partialSources = File(context.filesDir, "import-staging").listFiles()
                    ?.filter { it.name.startsWith("attempt-") }
                    ?.map { File(it, "source.nar") }
                    ?.filter { it.isFile && it.length() > 0L }
                    .orEmpty()
                if (providerWaiting && partialSources.size == 1) break
                delay(20)
            }
            true
        }
        assertTrue("Provider was not waiting with copied source bytes; state=${(context as NanidroidApplication).importCoordinator.state.value}", entered == true)
    }

    private fun releaseProviderGate(uri: android.net.Uri, gateId: String) {
        context.contentResolver.call(uri, "release-read", gateId, null)
    }

    private suspend fun awaitCompletion(app: NanidroidApplication): ImportState.Completed =
        withTimeout(10_000) {
            while (true) {
                (app.importCoordinator.state.value as? ImportState.Completed)?.let { return@withTimeout it }
                delay(20)
            }
            error("unreachable")
        }

    private suspend fun awaitScenarioState(
        scenario: ActivityScenario<MainActivity>, expected: Lifecycle.State,
    ) {
        val reached = withTimeoutOrNull(15_000) {
            while (scenario.state != expected) delay(20)
            true
        }
        assertTrue("Expected Activity $expected, observed ${scenario.state}", reached == true)
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command).use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).readBytes()
            }
    }

    private fun assertNoOwnedStaging() {
        assertFalse(File(context.filesDir, "import-staging").listFiles()
            ?.any { it.name.startsWith("attempt-") } == true)
    }

    private suspend fun awaitOwnedImportSettled(app: NanidroidApplication, attemptId: String?): Boolean =
        withTimeoutOrNull(10_000) {
            while (true) {
                val state = app.importCoordinator.state.value
                // Installed is published before the coordinator finishes offering its prompt.
                val active = when (state) {
                    is ImportState.Picking -> state.attemptId == attemptId
                    is ImportState.Running -> state.attemptId == attemptId
                    is ImportState.Completed -> state.attemptId == attemptId &&
                        state.outcome is ImportOutcome.Installed && state.promptResult == null &&
                        app.runtime.state.value != StageState.Finished
                    else -> false
                }
                val staged = File(context.filesDir, "import-staging").listFiles()
                    ?.any { it.name.startsWith("attempt-") } == true
                if (!active && !staged) break
                delay(20)
            }
            true
        } == true

    @Test fun duplicateAndCaseCollisionPreserveEveryExistingFile() = runBlocking {
        val existing = File(root, "ghost/DeviceValue").apply { mkdirs() }
        File(existing, "ghost/master/savedata/value.txt").apply {
            parentFile!!.mkdirs()
            writeText("engine-written-value")
        }
        File(existing, "ghost/master/descript.txt").writeText("name,Existing\n")
        val before = treeHashes(existing)
        for (id in listOf("DeviceValue", "devicevalue")) {
            val uri = GhostImportTestProvider.uri(archive(id), "exact")
            assertEquals(ImportOutcome.Refused(id), GhostImporter(root).importArchive(
                { context.contentResolver.openInputStream(uri)!! }) {})
            assertEquals(before, treeHashes(existing))
        }
        assertEquals("engine-written-value",
            File(existing, "ghost/master/savedata/value.txt").readText())
    }

    /** The host kills this live instrumented app process after observing the marker. */
    @Test fun holdAtProcessDeathBoundary() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Host-only process-death test requires phase", args.containsKey("phase"))
        val phase = args.getString("phase") ?: throw AssertionError("Missing phase")
        val runId = args.getString("runId") ?: throw AssertionError("Missing runId")
        require(phase in setOf("copy", "extract", "pre", "post"))
        require(runId.matches(Regex("[0-9a-f]{32}")))
        val ghostId = "kill${phase}${runId.take(12)}"
        val marker = File(context.filesDir, "process-death-$runId.marker")
        val sentinel = File(context.filesDir, "import-staging/sentinel-$runId")
        assertTrue(sentinel.parentFile!!.isDirectory || sentinel.parentFile!!.mkdirs())
        sentinel.writeText("unrelated:$runId")
        val latch = CountDownLatch(1)
        fun hold(boundary: String) {
            marker.writeText("runId=$runId\nphase=$boundary\npid=${Process.myPid()}\nghostId=$ghostId\n")
            latch.await()
        }
        val importer = GhostImporter(context.filesDir,
            writeSourceChunk = { output, bytes, count ->
                output.write(bytes, 0, count)
                output.flush()
                if (phase == "copy" && !marker.exists()) hold("copy")
            },
            beforeMove = { if (phase == "pre") hold("pre") },
            afterMove = { if (phase == "post") hold("post") },
        )
        // Enough source bytes to leave a real partial source.nar at the copy boundary.
        if (phase == "extract") {
            // 120 MiB expanded, under the 128 MiB per-file limit. Write one reusable
            // block at a time without allocating an archive-sized byte array.
            val source = File(context.cacheDir, "process-death-$runId.nar")
            val expectedBytes = 120L * 1024 * 1024
            ZipOutputStream(source.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("install.txt"))
                zip.write("type,ghost\ndirectory,$ghostId\n".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("ghost/master/descript.txt"))
                zip.write("name,Device test\nshiori,Nanidroid\n".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("shell/master/payload.bin"))
                val block = ByteArray(8192)
                val random = java.util.Random(0)
                repeat((expectedBytes / block.size).toInt()) {
                    random.nextBytes(block)
                    // A zero half keeps the entry compressible while requiring real
                    // decompression and file writes across the whole output.
                    block.fill(0, block.size / 2)
                    zip.write(block)
                }
                zip.closeEntry()
            }
            importer.importArchive({ source.inputStream() }) {
                marker.writeText("runId=$runId\nphase=extract\npid=${Process.myPid()}\n" +
                    "ghostId=$ghostId\nexpectedBytes=$expectedBytes\n")
                val release = File(context.filesDir, "process-death-$runId.release")
                while (!release.exists()) delay(10)
            }
        } else {
            val bytes = archive(ghostId, padding = 1024 * 1024, randomPadding = true)
            assertTrue(bytes.size > 8192)
            importer.importArchive({ bytes.inputStream() }) {}
        }
        fail("Host did not kill the instrumented process at $phase")
    }

    @Test fun verifyProcessDeathRecovery() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Host-only process-death verification requires phase", args.containsKey("phase"))
        val phase = args.getString("phase") ?: throw AssertionError("Missing phase")
        val runId = args.getString("runId") ?: throw AssertionError("Missing runId")
        require(phase in setOf("copy", "extract", "pre", "post"))
        require(runId.matches(Regex("[0-9a-f]{32}")))
        val ghostId = "kill${phase}${runId.take(12)}"
        val staging = File(context.filesDir, "import-staging")
        val sentinel = File(staging, "sentinel-$runId")
        val target = File(context.filesDir, "ghost/$ghostId")
        // Startup recovery is asynchronous; the host has already launched MainActivity.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (staging.listFiles()?.any { it.name.startsWith("attempt-") } == true &&
            System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse(staging.listFiles()?.any { it.name.startsWith("attempt-") } == true)
        assertEquals("unrelated:$runId", sentinel.readText())
        assertEquals(phase == "post", target.exists())
        val listed = InstalledGhostRepository(File(context.filesDir, "ghost")).list()
            .any { it.directoryId == ghostId }
        assertEquals(phase == "post", listed)
        val app = context as NanidroidApplication
        assertTrue(app.importCoordinator.state.value is ImportState.Idle)
        withTimeout(10_000) { app.runtime.start("en") }
        val stage = app.runtime.state.value as? StageState.Ready
            ?: throw AssertionError("Runtime did not reach a ready stage on restart")
        assertNull("Restart must not offer an import switch prompt", stage.switchPrompt)
        assertEquals(phase == "post", stage.installedGhosts.any { it.directoryId == ghostId })
        assertFalse(context.getSharedPreferences("last_ghost", android.content.Context.MODE_PRIVATE)
            .getString("last_ghost", null) == ghostId)
    }

    private fun archive(id: String, padding: Int = 0, randomPadding: Boolean = false,
                        builtinContent: String? = null): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val payload = ByteArray(padding.coerceAtLeast(1)) { (it % 251).toByte() }
            if (randomPadding) java.util.Random(0).nextBytes(payload)
            val contents = linkedMapOf(
                "install.txt" to "type,ghost\ndirectory,$id\n".toByteArray(),
                "ghost/master/descript.txt" to "name,Device test\nshiori,Nanidroid\n".toByteArray(),
                "shell/master/placeholder.txt" to payload,
            )
            if (builtinContent != null) {
                contents["ghost/master/ja/content.txt"] = builtinContent.toByteArray()
            }
            contents.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun largeExtractionArchive(source: File, id: String, payloadBytes: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        ZipOutputStream(source.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("install.txt"))
            zip.write("type,ghost\ndirectory,$id\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("ghost/master/descript.txt"))
            zip.write("name,Extraction rotation\nshiori,Nanidroid\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("shell/master/payload.bin"))
            val block = ByteArray(8192)
            val random = java.util.Random(0)
            repeat((payloadBytes / block.size).toInt()) {
                random.nextBytes(block)
                block.fill(0, block.size / 2)
                digest.update(block)
                zip.write(block)
            }
            zip.closeEntry()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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

    private fun activityStageViewModel(activity: MainActivity): StageViewModel =
        ViewModelProvider(activity, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                throw AssertionError("MainActivity did not create its StageViewModel")
        })[StageViewModel::class.java]

    private fun treeHashes(tree: File): Map<String, String> = tree.walkTopDown()
        .filter { it.isFile }
        .associate { file ->
            file.relativeTo(tree).invariantSeparatorsPath to
                MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
        }
}
