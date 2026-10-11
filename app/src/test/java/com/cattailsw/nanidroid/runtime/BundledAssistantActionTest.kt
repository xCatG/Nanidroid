package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.data.LastGhostStore
import com.cattailsw.nanidroid.engine.*
import com.cattailsw.nanidroid.ghost.*
import com.cattailsw.nanidroid.ui.StageViewModel
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BundledAssistantActionTest {
    @get:Rule val folder = TemporaryFolder()
    private val GhostRuntime.ready get() = state.value as StageState.Ready
    private fun GhostRuntime.action() = ready.appAction
    private fun GhostRuntime.claim(id: Long) = claimAppAction(id)
    private fun PresentedAppAction.requestId() = requestId

    private class Boot(activated: Boolean = false, onboardingAvailable: Boolean = true) : BootStateStore {
        val activations = if (activated) mutableSetOf("nanidroid") else mutableSetOf()
        var onboarding = !activated && onboardingAvailable
        var consumed = 0
        override suspend fun recordActivation(directoryId: String) = activations.add(directoryId)
        override suspend fun consumeOnboarding(): Boolean {
            consumed++
            return onboarding.also { onboarding = false }
        }
    }
    private class Last(private var value: String?) : LastGhostStore {
        override fun read() = value
        override fun write(directoryId: String) { value = directoryId }
    }
    private class Fixture(val root: File, val repository: BundledGhostRepository, val base: BundledGhost)
    private fun fixture(language: String = "en"): Fixture {
        val root = folder.newFolder()
        val archive = javaClass.getResourceAsStream("/nanidroid.zip")!!.use { it.readBytes() }
        val repository = BundledGhostRepository({ ByteArrayInputStream(archive) }, root)
        val base = repository.load(repository.ensureInstalled(), language)
        return Fixture(root, repository, base)
    }
    private fun Fixture.imported(id: String = "lookalike") {
        val master = File(root, "$id/ghost/master").apply { mkdirs() }
        File(master, "descript.txt").writeText("name,Nanidroid\nsakura.name,Sakura\nkero.name,Kero\nshiori,Nanidroid")
        File(master, "ja").mkdirs()
        File(master, "ja/content.txt").writeText("OnFirstBoot,\\_q\\q[Ghosts,OnNanidroidOpenGhosts]\\e")
        File(root, "$id/shell/master").mkdirs()
    }
    private fun assistant(base: BundledGhost, language: String) = BundledAssistantShiori(
        BuiltInShiori(base.content), BundledAssistantContent.load({ File("src/main/assets", it).inputStream() }, language), true)
    private suspend fun TestScope.started(
        fixture: Fixture = fixture(), saved: String? = null, boot: Boot = Boot(onboardingAvailable = false), language: String = "en",
        engine: ShioriEngine = assistant(fixture.base, language),
    ): GhostRuntime = GhostRuntime(
        { fixture.base }, { BuiltInShiori(it.content) }, boot,
        backgroundScope, InstalledGhostRepository(fixture.root), Last(saved),
        ioDispatcher = StandardTestDispatcher(testScheduler),
        bundledEngineFactory = { engine },
        elapsedRealtime = { testScheduler.currentTime },
    ).also { it.start(language); it.setResumed(true); runCurrent() }

    private fun menu(event: String = "OnNanidroidOpenGhosts") = ShioriReply(200, "\\_q\\q[Tool,$event]\\e")
    private fun engine(answer: suspend (ShioriEvent) -> ShioriReply) = object : ShioriEngine {
        override suspend fun request(event: ShioriEvent) = answer(event)
    }
    private fun TestScope.selectTool(runtime: GhostRuntime, label: String = "Ghosts") {
        runtime.click(1, 0, 0); runCurrent(); advanceTimeBy(10_000); runCurrent()
        runtime.choose(runtime.ready.choices.values.flatten().first { it.label == label }.token)
        runCurrent()
    }
    private fun GhostRuntime.onlyChoice() = ready.choices.values.flatten().single().token
    private fun TestScope.choose(runtime: GhostRuntime) { runtime.choose(runtime.onlyChoice()); runCurrent() }

    @Test fun defaultOnSavedSelectionGetsAssistance() = runTest {
        val runtime = started(saved = "nanidroid")
        selectTool(runtime)
        assertNotNull(runtime.action())
        assertEquals(BuiltInAppAction.Ghosts, runtime.claim(runtime.action()!!.requestId()))
    }
    @Test fun switchBackToDefaultGetsAssistance() = runTest {
        val fixture = fixture().apply { imported() }
        val runtime = started(fixture, saved = "lookalike")
        runtime.selectGhost("nanidroid"); runCurrent(); runtime.confirmSwitch(); runCurrent()
        selectTool(runtime)
        assertNotNull(runtime.action())
    }
    @Test fun importedBuiltinCannotOpenTools() = runTest {
        val fixture = fixture().apply { imported() }
        val runtime = started(fixture, saved = "lookalike")
        choose(runtime)
        assertNull(runtime.action())
    }
    @Test fun claimIsSingleUse() = runTest {
        val runtime = started()
        selectTool(runtime)
        val action = runtime.action()
        assertNotNull(action)
        val id = action!!.requestId()
        assertEquals(runtime.ready.dialogueToken!!.sessionId, action.sessionId)
        assertNull(runtime.claim(id + 1))
        assertEquals(BuiltInAppAction.Ghosts, StageViewModel(runtime).claimAppAction(id))
        assertNull(runtime.claim(id))
        assertNull(runtime.action())
    }
    @Test fun pauseInvalidatesPendingAction() = runTest {
        val runtime = started()
        selectTool(runtime)
        val request = runtime.action()
        assertNotNull(request)
        val frame = runtime.ready.frame
        runtime.setResumed(false); runCurrent()
        assertEquals(frame, runtime.ready.frame)
        assertNull(runtime.action())
        runtime.setResumed(true); runCurrent()
        assertNull(runtime.claim(request!!.requestId()))
    }
    private suspend fun TestScope.assertPausedReply(resumeBeforeReply: Boolean) {
        val gate = CompletableDeferred<Unit>()
        val runtime = started(engine = engine { event -> when (event.id) {
            "OnFirstBoot", "OnMouseClick" -> menu()
            "OnNanidroidOpenGhosts" -> { gate.await(); ShioriReply(200, "\\_qOrdinary dialogue\\e", appAction = BuiltInAppAction.Ghosts) }
            else -> ShioriReply(204)
        } })
        choose(runtime)
        runtime.setResumed(false); runCurrent()
        if (resumeBeforeReply) { runtime.setResumed(true); runCurrent() }
        gate.complete(Unit); runCurrent()
        assertNull(runtime.action())
        assertEquals("Ordinary dialogue", runtime.ready.frame.sakura.text)
        if (!resumeBeforeReply) { runtime.setResumed(true); runCurrent(); assertNull(runtime.action()) }
        // A later choice, admitted in the new epoch, still works.
        runtime.click(1, 0, 0); runCurrent()
        choose(runtime)
        assertEquals(BuiltInAppAction.Ghosts, runtime.claim(runtime.action()!!.requestId))
    }
    @Test fun replyDuringPauseIsRejected() = runTest { assertPausedReply(false) }
    @Test fun replyAfterPauseResumeIsRejected() = runTest { assertPausedReply(true) }
    @Test fun forgedActionAndMismatchedEventRejected() = runTest {
        for ((event, action) in listOf("OnNanidroidOpenGhosts" to BuiltInAppAction.About,
            "OnOther" to BuiltInAppAction.Ghosts)) {
            val runtime = started(engine = engine { if (it.id == "OnFirstBoot") menu(event)
                else ShioriReply(200, appAction = action) })
            choose(runtime)
            assertNull(runtime.action())
        }
    }
    @Test fun bootTouchImportAndClockRepliesCannotOpenTools() = runTest {
        val runtime = started(engine = engine { ShioriReply(200, appAction = BuiltInAppAction.Ghosts) })
        assertNull(runtime.action())
        runtime.click(1, 0, 0); runCurrent(); assertNull(runtime.action())
        runtime.beginImportEvents()!!.emit("OnNanidroidOpenGhosts"); runCurrent(); assertNull(runtime.action())
        advanceTimeBy(1_100); runCurrent(); assertNull(runtime.action())
    }
    @Test fun closeAndFarewellCannotOpenTools() = runTest {
        val runtime = started(engine = engine { when (it.id) {
            "OnClose" -> menu().copy(appAction = BuiltInAppAction.Ghosts)
            "OnNanidroidOpenGhosts" -> ShioriReply(200, "\\_qBye\\e", appAction = BuiltInAppAction.Ghosts)
            else -> ShioriReply(204)
        } })
        runtime.close(); runCurrent(); assertNull(runtime.action())
        choose(runtime); assertNull(runtime.action())
    }
    @Test fun delayedReplyAfterSwitchOrReplacementDiscarded() = runTest {
        for (switch in listOf(false, true)) {
            val fixture = fixture().apply { imported() }
            val gate = CompletableDeferred<Unit>()
            val runtime = started(fixture, engine = engine { when (it.id) {
                "OnFirstBoot" -> menu()
                "OnNanidroidOpenGhosts" -> { gate.await(); ShioriReply(200, "\\_qLate\\e", appAction = BuiltInAppAction.Ghosts) }
                "OnMouseClick" -> ShioriReply(200, "\\_qNew\\e")
                else -> ShioriReply(204)
            } })
            choose(runtime)
            if (switch) { runtime.selectGhost("lookalike"); runCurrent(); runtime.confirmSwitch(); runCurrent() }
            else { runtime.click(0, 0, 0); runCurrent() }
            gate.complete(Unit); runCurrent()
            assertNull(runtime.action())
            assertFalse(runtime.ready.frame.sakura.text.contains("Late"))
        }
    }
    @Test fun pendingActionClearedByReplacementSwitchAndClose() = runTest {
        for (operation in listOf("replace", "switch", "close")) {
            val fixture = fixture().apply { imported() }
            val runtime = started(fixture)
            selectTool(runtime)
            val request = runtime.action()
            assertNotNull(request)
            when (operation) {
                "replace" -> runtime.click(0, 0, 0)
                "switch" -> { runtime.selectGhost("lookalike"); runCurrent(); runtime.confirmSwitch() }
                else -> runtime.close()
            }
            runCurrent()
            assertNull(runtime.claim(request!!.requestId()))
            assertNull((runtime.state.value as? StageState.Ready)?.let { runtime.action() })
        }
    }
    @Test fun consumedChoiceCannotRepeatAndNewRequestHasNewId() = runTest {
        val runtime = started()
        runtime.click(1, 0, 0); runCurrent(); advanceTimeBy(10_000); runCurrent()
        val token = runtime.ready.choices.values.flatten().first { it.label == "Ghosts" }.token
        runtime.choose(token); runCurrent()
        val first = runtime.action()
        assertNotNull(first)
        runtime.claim(first!!.requestId())
        runtime.choose(token); runCurrent(); assertNull(runtime.action())
        selectTool(runtime)
        assertTrue(runtime.action()!!.requestId() > first.requestId())
    }
    private suspend fun TestScope.welcome(language: String, saved: String?, welcome: String, role: String) {
        val fixture = fixture(language)
        val boot = Boot()
        val runtime = started(fixture, saved, boot, language)
        val frames = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.state.collect { state -> if (state is StageState.Ready)
                frames += state.frame.sakura.text + state.frame.kero.text }
        }
        advanceTimeBy(20_000); runCurrent()
        val text = runtime.ready.frame.sakura.text + runtime.ready.frame.kero.text
        assertEquals(1, Regex(Regex.escape(welcome)).findAll(text).count())
        assertEquals(1, Regex(Regex.escape(role)).findAll(text).count())
        assertFalse(frames.any { it.contains("Tap a character to talk.") })
        if (language != "en") assertFalse(frames.any { it.contains("Welcome to Nanidroid") })
        assertEquals(1, boot.consumed)
        assertFalse(boot.onboarding)
        assertEquals(setOf("nanidroid"), boot.activations)
    }
    @Test fun freshEnglishInstallHasOneWelcome() = runTest {
        welcome("en", "nanidroid", "Welcome to Nanidroid!", "Tap me for an introduction and help with using the app.")
    }
    @Test fun freshFallbackInstallHasOneJapaneseWelcome() = runTest {
        welcome("fr", null, "Nanidroidへようこそ！", "アプリの紹介や使い方を案内します。")
    }
    @Test fun existingActivatedInstallGetsReminder() = runTest {
        val boot = Boot(true)
        val runtime = started(saved = "nanidroid", boot = boot)
        advanceTimeBy(20_000); runCurrent()
        assertTrue(runtime.ready.frame.sakura.text.contains("Welcome back!"))
        assertFalse(runtime.ready.frame.sakura.text.contains("Welcome to Nanidroid"))
        assertEquals(0, boot.consumed)
    }
    @Test fun existingInstalledDefaultFilesUntouched() = runTest {
        val fixture = fixture()
        fun snapshot() = fixture.root.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(fixture.root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
        }
        val before = snapshot()
        val repository = BundledGhostRepository({ error("Existing ZIP must not be opened") }, fixture.root)
        assertEquals(File(fixture.root, "nanidroid"), repository.ensureInstalled())
        val runtime = started(fixture, saved = "nanidroid", boot = Boot(true))
        selectTool(runtime)
        assertNotNull(runtime.action())
        assertEquals(before, snapshot())
    }

    @Test fun sameDirectoryIdAtAnotherMasterPathCannotGainCapability() = runTest {
        val original = fixture()
        val other = Fixture(folder.newFolder(), original.repository, original.base).apply { imported("nanidroid") }
        var trustedLoads = 0
        val forged = engine { if (it.id == "OnFirstBoot") menu()
            else ShioriReply(200, appAction = BuiltInAppAction.Ghosts) }
        val runtime = GhostRuntime({ original.base }, { forged }, Boot(onboardingAvailable = false),
            backgroundScope, InstalledGhostRepository(other.root), Last("nanidroid"),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            bundledEngineFactory = { trustedLoads++; assistant(it, "en") },
            elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        choose(runtime)
        assertEquals(0, trustedLoads)
        assertNull(runtime.action())
    }

    @Test fun importedForgedTypedReplyCannotOpenTools() = runTest {
        val fixture = fixture().apply { imported() }
        val forged = engine { if (it.id == "OnFirstBoot") menu()
            else ShioriReply(200, appAction = BuiltInAppAction.Ghosts) }
        val runtime = GhostRuntime({ fixture.base }, { forged }, Boot(onboardingAvailable = false),
            backgroundScope, InstalledGhostRepository(fixture.root), Last("lookalike"),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            bundledEngineFactory = { assistant(it, "en") }, elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        choose(runtime)
        assertNull(runtime.action())
    }

    @Test fun invalidSavedDescriptorFallsBackToTrustedDefault() = runTest {
        val runtime = started(saved = "missing")
        selectTool(runtime)
        assertEquals(BuiltInAppAction.Ghosts, runtime.claim(runtime.action()!!.requestId))
    }

    @Test fun delayedReplyAfterCloseIsDiscarded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = started(engine = engine { when (it.id) {
            "OnFirstBoot" -> menu()
            "OnNanidroidOpenGhosts" -> { gate.await(); ShioriReply(200, appAction = BuiltInAppAction.Ghosts) }
            "OnClose" -> ShioriReply(200, "\\_qBye\\e")
            else -> ShioriReply(204)
        } })
        choose(runtime)
        runtime.close(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertNull(runtime.action())
        assertEquals("Bye", runtime.ready.frame.sakura.text)
    }

    @Test fun allThreeExactToolEventsAreClaimable() = runTest {
        for (action in BuiltInAppAction.entries) {
            val runtime = started()
            selectTool(runtime, when (action) {
                BuiltInAppAction.Ghosts -> "Ghosts"
                BuiltInAppAction.About -> "About and licenses"
                BuiltInAppAction.DebugTools -> "Debug tools"
            })
            assertEquals(action, runtime.claim(runtime.action()!!.requestId))
        }
    }

    @Test fun failedTypedReplyCannotOpenTools() = runTest {
        val runtime = started(engine = engine { if (it.id == "OnFirstBoot") menu()
            else ShioriReply(500, appAction = BuiltInAppAction.Ghosts) })
        choose(runtime)
        assertNull(runtime.action())
    }

    @Test fun ordinaryBundledFactoryPreservesSavedDefaultOnboarding() = runTest {
        val fixture = fixture()
        val boot = Boot()
        val runtime = GhostRuntime({ fixture.base }, { BuiltInShiori(it.content) }, boot,
            backgroundScope, InstalledGhostRepository(fixture.root), Last("nanidroid"),
            ioDispatcher = StandardTestDispatcher(testScheduler), elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        assertEquals(1, boot.consumed)
        advanceTimeBy(3_000); runCurrent()
        assertTrue(runtime.ready.frame.sakura.text.contains("Tap a character to talk."))
    }
}
