package com.cattailsw.nanidroid.ui

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.cattailsw.nanidroid.ghost.SurfaceImageLoader
import com.cattailsw.nanidroid.install.ImportOutcome
import com.cattailsw.nanidroid.install.ImportState
import com.cattailsw.nanidroid.install.ImportAttemptStore
import com.cattailsw.nanidroid.MainActivity
import com.cattailsw.nanidroid.NanidroidApplication
import com.cattailsw.nanidroid.engine.EngineKind
import com.cattailsw.nanidroid.engine.EngineSelector
import com.cattailsw.nanidroid.engine.NativeAvailability
import com.cattailsw.nanidroid.engine.NativeShioriHost
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import com.cattailsw.nanidroid.runtime.PlaybackFrame
import com.cattailsw.nanidroid.runtime.SpeakerFrame
import com.cattailsw.nanidroid.runtime.StageState
import java.io.File
import java.io.FilterInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GhostImportUiTest {
    @get:Rule val compose = createComposeRule()
    private val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).asImageBitmap()
    private val loader = SurfaceImageLoader { image }
    private val stage = StageState.Ready(
        PlaybackFrame(SpeakerFrame(0, true, "", false), SpeakerFrame(10, true, "", false), false),
        "Nanidroid", "Sakura", "Kero", mapOf(0 to File("sakura"), 10 to File("kero")),
    )

    @Test fun importActionAndRunningCancelAreAccessible() {
        var imports = 0
        var cancels = 0
        compose.setContent {
            GhostStage(stage, loader, {}, {}, {}, { _, _, _ -> }, { _, _, _ -> },
                importState = ImportState.Running("attempt"), onImport = { imports++ },
                onCancelImport = { cancels++ })
        }
        compose.onNodeWithContentDescription("Ghosts").performClick()
        compose.onNodeWithText("Importing ghost…").assertExists()
        compose.onNodeWithContentDescription("Cancel import").performClick()
        assertEquals(1, cancels)
        assertEquals(0, imports)
    }

    @Test fun backDuringRunningRequestsCloseButOutsideTapDoesNot() {
        var closes = 0
        var outerBacks = 0
        compose.setContent {
            BackHandler { outerBacks++ }
            GhostStage(stage, loader, {}, {}, {}, { _, _, _ -> }, { _, _, _ -> },
                importState = ImportState.Running("attempt"), onClose = { closes++ })
        }
        compose.onNodeWithText("Importing ghost…").assertExists()
        // An outside tap must leave the running attempt under the user's control.
        compose.onAllNodes(isRoot())[0].performTouchInput {
            click(androidx.compose.ui.geometry.Offset(2f, 2f))
        }
        compose.runOnIdle { assertEquals(0, closes) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertEquals(1, closes)
        assertEquals(0, outerBacks)
    }

    @Test fun unavailablePromptExplainsImportedGhostCannotBeUsedYet() {
        compose.setContent {
            GhostStage(stage, loader, {}, {}, {}, { _, _, _ -> }, { _, _, _ -> },
                importState = ImportState.Completed("id", ImportOutcome.Installed("visitor"), "visitor",
                    promptResult = com.cattailsw.nanidroid.runtime.ImportPromptResult.Unavailable(
                        "Installed ghost is unavailable until restart")))
        }
        compose.onNodeWithText("Ghost imported. Installed ghost is unavailable until restart.").assertExists()
        compose.onNodeWithText("Ghost imported. Review the readme before switching.").assertDoesNotExist()
    }

    @Test fun failedImportDialogShowsArchiveByteLimitReason() {
        compose.setContent {
            GhostStage(stage, loader, {}, {}, {}, { _, _, _ -> }, { _, _, _ -> },
                importState = ImportState.Completed("id", ImportOutcome.Failed("Archive byte limit exceeded")))
        }
        compose.onNodeWithText("Import failed. Archive byte limit exceeded.").assertExists()
        compose.onNodeWithText("Import failed.").assertDoesNotExist()
    }

    @Test fun idleGhostsFlowLaunchesImportOnce() {
        var imports = 0
        compose.setContent {
            GhostStage(stage, loader, {}, {}, {}, { _, _, _ -> }, { _, _, _ -> },
                onImport = { imports++ })
        }
        compose.onNodeWithContentDescription("Ghosts").performClick()
        compose.onNodeWithContentDescription("Import .nar").performClick()
        assertEquals(1, imports)
    }

    @Test fun screenStaysOnOnlyDuringRunning() {
        val state = mutableStateOf<ImportState>(ImportState.Idle)
        lateinit var view: android.view.View
        compose.setContent {
            view = LocalView.current
            GhostStage(stage, loader, {}, {}, {}, { _, _, _ -> }, { _, _, _ -> }, importState = state.value)
        }
        compose.runOnIdle { assertFalse(view.keepScreenOn); state.value = ImportState.Running("id") }
        compose.runOnIdle { assertTrue(view.keepScreenOn); state.value = ImportState.Completed("id", ImportOutcome.Cancelled) }
        compose.runOnIdle { assertFalse(view.keepScreenOn) }
    }

    @Test fun consumedIdSurvivesStoreRecreation() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("ghost_import_ui_store_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val first = ImportAttemptStore.fromPreferences(prefs)
        first.markConsumed("first")
        val second = ImportAttemptStore.fromPreferences(prefs)
        assertTrue(second.wasConsumed("first"))
        assertFalse(second.wasConsumed("second"))
        second.markConsumed("second")
        assertFalse(first.wasConsumed("first"))
        prefs.edit().clear().commit()
        Unit
    }

    @Test fun completedImportShowsResultAndExplicitSwitchPrompt() {
        val current = stage.copy(switchPrompt = com.cattailsw.nanidroid.runtime.SwitchPrompt(
            "visitor", "Visitor", "Read me first"))
        compose.setContent {
            GhostStage(current, loader, {}, {}, {}, { _, _, _ -> }, { _, _, _ -> },
                importState = ImportState.Completed("attempt", ImportOutcome.Installed("visitor")))
        }
        compose.onNodeWithText("Read me first").assertExists()
        compose.onNodeWithContentDescription("Confirm ghost switch").assertExists()
    }
}

/** Host-staged unchanged corpus; kept separate from the normal synthetic UI tests. */
class RealCorpusImportUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** One fresh app-data installation and unchanged host-staged NAR per invocation. */
    @Test fun importedCorpusRendersOwnStageAndClosesThroughVisibleControls() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Run host-staged corpus UI probe; required arguments: uiCorpusPath, uiCorpusSha256, uiCorpusGhostId, uiCorpusMode",
            listOf("uiCorpusPath", "uiCorpusSha256", "uiCorpusGhostId", "uiCorpusMode").any { args.containsKey(it) })
        require(listOf("uiCorpusPath", "uiCorpusSha256", "uiCorpusGhostId", "uiCorpusMode").all { !args.getString(it).isNullOrBlank() }) {
            "Provide all nonblank host arguments: uiCorpusPath, uiCorpusSha256, uiCorpusGhostId, uiCorpusMode"
        }
        val name = requireNotNull(args.getString("uiCorpusPath"))
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Invalid uiCorpusPath" }
        val expectedHash = requireNotNull(args.getString("uiCorpusSha256")).lowercase()
        require(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "uiCorpusSha256 must be 64 hex characters" }
        val id = requireNotNull(args.getString("uiCorpusGhostId"))
        require(id.matches(Regex("[A-Za-z0-9 _-]{1,100}")) && id == id.trim()) { "Invalid uiCorpusGhostId" }
        val mode = requireNotNull(args.getString("uiCorpusMode"))
        require(mode == "aya5-only" || mode == "yaya") { "uiCorpusMode must be aya5-only or yaya" }
        val target = compose.activity
        val app = target.application as NanidroidApplication
        val source = File(target.filesDir, name)
        val output = requireNotNull(target.getExternalFilesDir(null))
        val resultFile = File(output, "corpus-import-ui-result.json")
        val result = JSONObject().put("directoryId", id).put("mode", mode)
            .put("expectedSha256", expectedHash)
        var closedByBack = false
        try {
            assertTrue("Staged archive missing", source.isFile)
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val bytes = ByteArray(8192)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            result.put("actualSha256", actual)
            assertEquals("Device archive hash", expectedHash, actual)
            assertFalse("Fixture already installed", File(target.filesDir, "ghost/$id").exists())
            compose.waitUntil(30_000) { app.runtime.state.value is StageState.Ready }
            compose.onNodeWithContentDescription("Ghosts").assertExists()
            assertEquals(NativeAvailability.Available, NativeShioriHost.process.availability.value)

            val attempt = requireNotNull(app.importCoordinator.beginPicking())
            app.importCoordinator.acceptResult(attempt) { source.inputStream() }
            compose.waitUntil(60_000) {
                val completed = app.importCoordinator.state.value as? ImportState.Completed
                completed?.outcome == ImportOutcome.Installed(id) &&
                    (app.runtime.state.value as? StageState.Ready)?.switchPrompt?.directoryId == id
            }
            result.put("importOutcome", app.importCoordinator.state.value.toString())
            val descriptor = InstalledGhostRepository(File(target.filesDir, "ghost")).validate(id)
            val expectedEngine = if (mode == "aya5-only") EngineKind.UNSUPPORTED else EngineKind.YAYA
            assertEquals("Selected engine", expectedEngine, EngineSelector.select(descriptor))
            result.put("displayName", descriptor.displayName).put("selectedEngine", expectedEngine.name)
            compose.onNodeWithContentDescription("Confirm ghost switch").performClick()
            compose.waitUntil(45_000) {
                val ready = app.runtime.state.value as? StageState.Ready
                ready?.ghostName == descriptor.displayName && ready.switchPrompt == null &&
                    ready.surfaces.isNotEmpty()
            }
            val ready = app.runtime.state.value as StageState.Ready
            assertTrue("Selected stage did not use the imported shell", ready.surfaces.values.all {
                it.canonicalPath.startsWith(descriptor.masterPath.parentFile!!.parentFile!!.canonicalPath + File.separator)
            })
            result.put("activeGhost", ready.ghostName).put("surfaceCount", ready.surfaces.size)
                .put("activationError", ready.activationError)
            compose.waitUntil(20_000) {
                compose.onAllNodesWithContentDescription(descriptor.sakuraName)
                    .fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithContentDescription(descriptor.keroName)
                        .fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("No imported character Canvas semantics", listOf("sakura", "kero").any {
                runCatching { compose.onNodeWithTag(it).fetchSemanticsNode() }.isSuccess
            })
            val lease = app.runtime.activeNativeLeaseForTest()
            result.put("nativeLeaseActive", lease != null)
            if (mode == "aya5-only") {
                val message = "This ghost uses a SHIORI Nanidroid does not support yet."
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithText(message, substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(message, substring = true).assertExists()
                assertNull("Unsupported ghost acquired native lease", lease)
                assertEquals(NativeAvailability.Available, NativeShioriHost.process.availability.value)
                result.put("visibleFallback", message)
            } else {
                assertNotNull("YAYA ghost has no native lease", lease)
                assertEquals(NativeAvailability.Occupied, NativeShioriHost.process.availability.value)
                assertNull("YAYA ghost fell back", ready.activationError)
            }
            compose.waitForIdle()
            val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                File(output, "corpus-import-ui-stage.png").outputStream().use { stream ->
                    assertTrue("PNG compression failed", screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            } finally { screenshot.recycle() }

            compose.onNodeWithContentDescription("Ghosts").performClick()
            compose.onNodeWithContentDescription("Select Nanidroid").performClick()
            compose.onNodeWithContentDescription("Confirm ghost switch").performClick()
            compose.waitUntil(45_000) {
                (app.runtime.state.value as? StageState.Ready)?.ghostName == "Nanidroid"
            }
            compose.onNodeWithContentDescription("Ghosts").assertExists()
            assertNull("Switch back retained native lease", app.runtime.activeNativeLeaseForTest())
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(45_000) { app.runtime.state.value is StageState.Finished }
            closedByBack = true
            assertNull("Back close retained native lease", app.runtime.activeNativeLeaseForTest())
            assertEquals(NativeAvailability.Available, NativeShioriHost.process.availability.value)
            result.put("switchBackGhost", "Nanidroid").put("closeState", "Finished")
                .put("testStatus", "completed")
        } catch (failure: Throwable) {
            result.put("testStatus", "failed")
                .put("failure", "${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        } finally {
            if (!closedByBack) {
                app.importCoordinator.cancelBeforePublication()
                app.runtime.close()
                runCatching { compose.waitUntil(45_000) { app.runtime.state.value is StageState.Finished } }
            }
            resultFile.writeText(result.toString(2))
        }
    }

    @Test fun cancelRealImportThroughVisibleDialog() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Run host-staged corpus cancellation probe; required arguments: uiProbePath, uiProbeSha256",
            listOf("uiProbePath", "uiProbeSha256").any { args.containsKey(it) })
        require(listOf("uiProbePath", "uiProbeSha256").all { !args.getString(it).isNullOrBlank() }) {
            "Provide all nonblank host arguments: uiProbePath, uiProbeSha256"
        }
        val target = compose.activity
        val app = target.application as NanidroidApplication
        val name = requireNotNull(args.getString("uiProbePath"))
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Invalid uiProbePath" }
        val source = File(target.filesDir, name)
        val expected = requireNotNull(args.getString("uiProbeSha256")).lowercase()
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid uiProbeSha256" }
        val actual = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, actual)
        val result = JSONObject().put("actualSha256", actual)
        val output = File(requireNotNull(target.getExternalFilesDir(null)), "import-ui-compose-result.json")
        try {
            compose.waitUntil(20_000) { app.runtime.state.value is StageState.Ready }
            compose.onNodeWithContentDescription("Ghosts").assertExists()
            val attempt = requireNotNull(app.importCoordinator.beginPicking())
            val reads = AtomicInteger()
            app.importCoordinator.acceptResult(attempt) {
                object : FilterInputStream(source.inputStream()) {
                    override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                        if (reads.incrementAndGet() > 1) Thread.sleep(25)
                        return super.read(bytes, off, len)
                    }
                }
            }
            compose.waitUntil(10_000) {
                app.importCoordinator.state.value is ImportState.Running &&
                    compose.onAllNodesWithContentDescription("Cancel import")
                        .fetchSemanticsNodes().isNotEmpty()
            }
            result.put("progressVisibleWhileRunning", true).put("readsAtClick", reads.get())
            assertTrue("Input already finished", app.importCoordinator.state.value is ImportState.Running)
            val started = android.os.SystemClock.elapsedRealtimeNanos()
            compose.onNodeWithContentDescription("Cancel import").performClick()
            compose.waitUntil(15_000) { app.importCoordinator.state.value is ImportState.Completed }
            val state = app.importCoordinator.state.value as ImportState.Completed
            result.put("stateAfterClick", state.toString())
                .put("clickToCompletionMs", (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
            assertEquals(ImportOutcome.Cancelled, state.outcome)
            assertTrue("Cancelled import published ghost", File(target.filesDir, "ghost/2elf").exists().not())
            assertTrue("Cancelled import left staging", File(target.filesDir, "import-staging").listFiles().isNullOrEmpty())
            result.put("testStatus", "completed")
        } catch (failure: Throwable) {
            result.put("testStatus", "failed")
                .put("failure", "${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        } finally { output.writeText(result.toString(2)) }
    }
}
