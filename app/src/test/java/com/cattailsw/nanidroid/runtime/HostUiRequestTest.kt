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
class HostUiRequestTest {
    @get:Rule val folder = TemporaryFolder()
    private val GhostRuntime.ready get() = state.value as StageState.Ready
    private fun GhostRuntime.action() = ready.hostUiRequest
    private fun GhostRuntime.claim(id: Long) = claimHostUiRequest(id)
    private fun HostUiRequest.requestId() = id

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
        File(master, "ja/content.txt").writeText("OnFirstBoot,\\_q\\q[Ghosts,OnNanidroidOpenGhosts]\\e\nOnNanidroidOpenGhosts,\\![open,ghostexplorer]\\e")
        File(root, "$id/shell/master").mkdirs()
    }
    private fun assistant(base: BundledGhost, language: String) = BuiltInShiori.bundled(base.content, true)
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
        assertEquals(HostUiCommand.GhostExplorer, runtime.claim(runtime.action()!!.requestId()))
    }
    @Test fun importedSwitchNameCannotInjectTrustedDeveloperCommand() = runTest {
        for (name in listOf("Probe\\![open,developer]", "\\\\![open,developer]",
            "\\0\\c\\s[-1]\\q[Injected,OnOther]\\![open,inputbox,x]\\e",
            "正常な名前\$値", "%selfname\\![open,developer]", "Trailing\\")) {
            val fixture = fixture().apply { imported() }
            File(fixture.root, "lookalike/ghost/master/descript.txt").writeText(
                "charset,UTF-8\nname,$name\nsakura.name,Foreign\nkero.name,Companion\nshiori,Nanidroid")
            val runtime = started(fixture, boot = Boot(true))
            runtime.selectGhost("lookalike"); runCurrent(); runtime.confirmSwitch(); runCurrent()
            advanceTimeBy(10_000); runCurrent()
            assertEquals(name, runtime.ready.ghostName)
            runtime.selectGhost("nanidroid"); runCurrent(); runtime.confirmSwitch(); runCurrent()
            advanceTimeBy(10_000); runCurrent()
            assertEquals("Nanidroid", runtime.ready.ghostName)
            assertNull("Foreign name became a trusted command", runtime.action())
            assertEquals("Changed From $name", runtime.ready.frame.sakura.text)
            selectTool(runtime, "Debug tools")
            assertEquals(HostUiCommand.Developer, runtime.claim(runtime.action()!!.id))
        }
    }

    @Test fun switchBackToDefaultGetsAssistance() = runTest {
        val fixture = fixture().apply { imported() }
        val runtime = started(fixture, saved = "lookalike")
        runtime.selectGhost("nanidroid"); runCurrent(); runtime.confirmSwitch(); runCurrent()
        selectTool(runtime)
        assertNotNull(runtime.action())
    }
    @Test fun importedBuiltinUsesFullLocaleAndPreservesAuthoredLanguageDirectories() = runTest {
        val fixture = fixture().apply { imported() }
        for ((directory, text) in listOf("ja" to "Japanese", "zh-TW" to "Traditional",
            "en" to "English", "fr" to "French")) {
            val content = File(fixture.root, "lookalike/ghost/master/$directory/content.txt")
            content.parentFile!!.mkdirs()
            content.writeText("OnFirstBoot,\\_q$text\\e")
        }
        for ((tag, expected) in listOf("zh-Hant-TW" to "Traditional", "zh-HK" to "Traditional",
            "zh-MO" to "Traditional", "zh-Hans-TW" to "Japanese", "en-GB" to "English",
            "fr-FR" to "French")) {
            val runtime = started(fixture, saved = "lookalike", language = tag)
            assertEquals(expected, runtime.ready.frame.sakura.text)
        }
    }

    @Test fun importedTraditionalLocaleRetainsLegacyChineseDictionaryIfStandardFolderMissing() = runTest {
        val fixture = fixture().apply { imported() }
        val content = File(fixture.root, "lookalike/ghost/master/zh/content.txt")
        content.parentFile!!.mkdirs()
        content.writeText("OnFirstBoot,\\_qLegacy Chinese\\e")
        val runtime = started(fixture, saved = "lookalike", language = "zh-Hant-TW")
        assertEquals("Legacy Chinese", runtime.ready.frame.sakura.text)
    }

    @Test fun periodicHostRequestsRemainBoundedWhileConsumerIsBlocked() = runTest {
        val runtime = started(engine = engine {
            if (it.id == "OnSecondChange") ShioriReply(200, "\\![open,configurationdialog,about]\\e")
            else ShioriReply(204)
        })
        advanceTimeBy(70_000); runCurrent()
        val requests = mutableListOf<HostUiRequest>()
        while (runtime.action() != null) {
            requests += runtime.action()!!
            assertEquals(HostUiCommand.About, runtime.claim(requests.last().id))
        }
        assertEquals(32, requests.size)
        assertEquals(requests.size, requests.map { it.id }.distinct().size)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(runtime.action()!!.id > requests.last().id)
    }

    @Test fun oneScriptCannotFloodHostFifoOrLoseRetainedOrderAndText() = runTest {
        val commands = "\\![open,ghostexplorer]\\![open,configurationdialog,about]"
        val runtime = pointerScript("\\_qBefore${commands.repeat(100)}After\\e")
        advanceTimeBy(1_000); runCurrent()
        assertEquals("BeforeAfter", runtime.ready.frame.sakura.text)
        val retained = mutableListOf<HostUiCommand?>()
        while (runtime.action() != null) retained += runtime.claim(runtime.action()!!.id)
        assertEquals(List(32) { if (it % 2 == 0) HostUiCommand.GhostExplorer else HostUiCommand.About }, retained)
    }

    @Test fun importedBuiltinCanOpenCommonTools() = runTest {
        val fixture = fixture().apply { imported() }
        val runtime = started(fixture, saved = "lookalike")
        choose(runtime)
        assertEquals(HostUiCommand.GhostExplorer, runtime.claim(runtime.action()!!.id))
    }
    @Test fun claimIsSingleUse() = runTest {
        val runtime = started()
        selectTool(runtime)
        val action = runtime.action()
        assertNotNull(action)
        val id = action!!.requestId()
        assertNull(runtime.claim(id + 1))
        assertEquals(HostUiCommand.GhostExplorer, StageViewModel(runtime).claimHostUiRequest(id))
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
            "OnNanidroidOpenGhosts" -> { gate.await(); ShioriReply(200, "\\_qOrdinary dialogue\\![open,ghostexplorer]\\e") }
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
        assertEquals(HostUiCommand.GhostExplorer, runtime.claim(runtime.action()!!.id))
    }
    @Test fun replyDuringPauseIsRejected() = runTest { assertPausedReply(false) }
    @Test fun replyAfterPauseResumeIsRejected() = runTest { assertPausedReply(true) }
    @Test fun bootTouchImportAndClockRepliesCanOpenCommonTools() = runTest {
        val runtime = started(engine = engine { ShioriReply(200, "\\![open,ghostexplorer]\\e") })
        assertNull(runtime.action()) // Startup requested while paused.
        runtime.click(1, 0, 0); runCurrent()
        assertEquals(HostUiCommand.GhostExplorer, runtime.claim(runtime.action()!!.id))
        runtime.beginImportEvents()!!.emit("OnUnrelated"); runCurrent()
        advanceTimeBy(1_100); runCurrent()
        assertEquals(HostUiCommand.GhostExplorer, runtime.claim(runtime.action()!!.id))
        advanceTimeBy(1_100); runCurrent()
        assertEquals(HostUiCommand.GhostExplorer, runtime.claim(runtime.action()!!.id))
    }
    @Test fun closeAndFarewellCannotOpenTools() = runTest {
        val runtime = started(engine = engine { when (it.id) {
            "OnClose" -> menu().copy(value = menu().value + "\\![open,ghostexplorer]")
            "OnNanidroidOpenGhosts" -> ShioriReply(200, "\\_qBye\\![open,ghostexplorer]\\e")
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
                "OnNanidroidOpenGhosts" -> { gate.await(); ShioriReply(200, "\\_qLate\\![open,ghostexplorer]\\e") }
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
        val repository = fixture.repository
        assertEquals(File(fixture.root, "nanidroid"), repository.ensureInstalled())
        val runtime = started(fixture, saved = "nanidroid", boot = Boot(true))
        selectTool(runtime)
        assertNotNull(runtime.action())
        assertEquals(before, snapshot())
    }

    @Test fun sameDirectoryIdAtAnotherMasterPathCannotGainDeveloperCapability() = runTest {
        val original = fixture()
        val other = Fixture(folder.newFolder(), original.repository, original.base).apply { imported("nanidroid") }
        var trustedLoads = 0
        val forged = engine { if (it.id == "OnFirstBoot") menu()
            else ShioriReply(200, "\\![open,developer]\\e") }
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

    @Test fun importedFakeEngineCannotOpenDeveloperTools() = runTest {
        val fixture = fixture().apply { imported() }
        val forged = engine { if (it.id == "OnFirstBoot") menu()
            else ShioriReply(200, "\\![open,developer]\\e") }
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
        assertEquals(HostUiCommand.GhostExplorer, runtime.claim(runtime.action()!!.id))
    }

    @Test fun delayedReplyAfterCloseIsDiscarded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = started(engine = engine { when (it.id) {
            "OnFirstBoot" -> menu()
            "OnNanidroidOpenGhosts" -> { gate.await(); ShioriReply(200, "\\![open,ghostexplorer]\\e") }
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
        for (action in HostUiCommand.entries) {
            val runtime = started()
            selectTool(runtime, when (action) {
                HostUiCommand.GhostExplorer -> "Ghosts"
                HostUiCommand.About -> "About and licenses"
                HostUiCommand.Developer -> "Debug tools"
            })
            assertEquals(action, runtime.claim(runtime.action()!!.id))
        }
    }

    @Test fun failedTypedReplyCannotOpenTools() = runTest {
        val runtime = started(engine = engine { if (it.id == "OnFirstBoot") menu()
            else ShioriReply(500, "\\![open,ghostexplorer]\\e") })
        choose(runtime)
        assertNull(runtime.action())
    }

    @Test fun ordinaryBundledFactoryUsesOnlyArchiveWelcome() = runTest {
        val fixture = fixture()
        val boot = Boot()
        val runtime = GhostRuntime({ fixture.base }, { BuiltInShiori(it.content) }, boot,
            backgroundScope, InstalledGhostRepository(fixture.root), Last("nanidroid"),
            ioDispatcher = StandardTestDispatcher(testScheduler), elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        assertEquals(1, boot.consumed)
        advanceTimeBy(20_000); runCurrent()
        assertTrue(runtime.ready.frame.sakura.text.contains("Welcome to Nanidroid!"))
        assertFalse(runtime.ready.frame.sakura.text.contains("Tap a character to talk."))
    }

    private suspend fun TestScope.pointerScript(script: String, trustedDebug: Boolean = false): GhostRuntime {
        val fixture = fixture()
        return started(fixture, engine = if (trustedDebug)
            BuiltInShiori.bundled("OnMouseClick,$script", true) else engine {
                if (it.id == "OnMouseClick") ShioriReply(200, script) else ShioriReply(204)
            }).also { it.click(0, 0, 0); runCurrent() }
    }
    @Test fun commandAfterWaitPublishesWithUnchangedFrame() = runTest {
        val runtime = pointerScript("\\_qSame\\_w[100]\\![open,ghostexplorer]\\_w[1000]\\e")
        val before = runtime.ready
        assertNull(before.hostUiRequest)
        advanceTimeBy(100); runCurrent()
        assertEquals(before.frame, runtime.ready.frame)
        assertEquals(before.dialogueToken, runtime.ready.dialogueToken)
        assertNotNull(runtime.action())
        assertEquals(HostUiCommand.GhostExplorer, runtime.claim(runtime.action()!!.id))
        assertEquals(emptyList<HostUiCommand>(), runtime.ready.frame.hostCommands)
    }
    @Test fun deniedBatchCreatesNoRequest() = runTest {
        val runtime = pointerScript("\\_qBefore\\![open,developer]\\![open,developer]After\\e")
        assertNull(runtime.action())
        assertEquals("BeforeAfter", runtime.ready.frame.sakura.text)
    }
    @Test fun orderedRepeatedCommandsHaveIndependentIdsAndClaims() = runTest {
        val runtime = pointerScript("\\![open,ghostexplorer]\\![open,configurationdialog,about]\\![open,ghostexplorer]\\e")
        val first = runtime.action()!!
        assertNull(runtime.claim(first.id + 1))
        assertEquals(HostUiCommand.GhostExplorer, StageViewModel(runtime).claimHostUiRequest(first.id))
        val second = runtime.action()!!
        assertTrue(second.id > first.id)
        assertNull(runtime.claim(first.id))
        assertEquals(HostUiCommand.About, runtime.claim(second.id))
        val third = runtime.action()!!
        assertTrue(third.id > second.id)
        assertEquals(HostUiCommand.GhostExplorer, runtime.claim(third.id))
        assertNull(runtime.action())
    }
    @Test fun endingFrameCommandSurvivesNextQueuedScript() = runTest {
        val runtime = started(engine = engine { when (it.id) {
            "OnMouseClick" -> ShioriReply(200, "\\_w[100]\\![open,ghostexplorer]\\e")
            "OnImport" -> ShioriReply(200, "\\_qNext script\\_w[5000]\\e")
            else -> ShioriReply(204)
        } })
        runtime.click(0, 0, 0); runCurrent()
        val token = runtime.ready.dialogueToken
        runtime.beginImportEvents()!!.emit("OnImport"); runCurrent()
        // One tick reaches the command, the ending frame and the next queued script.
        advanceTimeBy(2_000); runCurrent()
        assertNotEquals(token, runtime.ready.dialogueToken)
        assertEquals("Next script", runtime.ready.frame.sakura.text)
        assertEquals(HostUiCommand.GhostExplorer, runtime.claim(runtime.action()!!.id))
    }
    @Test fun releaseAndFakeDefaultDenyDeveloperButKeepSurroundingText() = runTest {
        val fixture = fixture()
        for (candidate in listOf<ShioriEngine>(
            BuiltInShiori.bundled("OnMouseClick,\\_qBefore\\![open,developer]After\\e", false),
            engine { if (it.id == "OnMouseClick") ShioriReply(200, "\\_qBefore\\![open,developer]After\\e") else ShioriReply(204) })) {
            val runtime = started(fixture, engine = candidate)
            runtime.click(0, 0, 0); runCurrent()
            assertNull(runtime.action())
            assertEquals("BeforeAfter", runtime.ready.frame.sakura.text)
        }
        val debug = pointerScript("\\![open,developer]\\e", trustedDebug = true)
        assertEquals(HostUiCommand.Developer, debug.claim(debug.action()!!.id))
    }
    @Test fun queuedAndWaitingScriptsFromBeforePauseCannotOpenDialogsAfterResume() = runTest {
        val runtime = started(engine = engine { when (it.id) {
            "OnMouseClick" -> ShioriReply(200, "\\_qOld text\\_w[200]\\![open,ghostexplorer]\\e")
            "OnImport" -> ShioriReply(200, "\\_qQueued text\\![open,configurationdialog,about]\\e")
            else -> ShioriReply(204)
        } })
        runtime.click(0, 0, 0); runCurrent()
        runtime.beginImportEvents()!!.emit("OnImport"); runCurrent()
        runtime.setResumed(false); runCurrent()
        runtime.setResumed(true); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertNull(runtime.action())
        assertEquals("Queued text", runtime.ready.frame.sakura.text)
        runtime.click(0, 0, 0); runCurrent(); advanceTimeBy(250); runCurrent()
        assertEquals(HostUiCommand.GhostExplorer, runtime.claim(runtime.action()!!.id))
    }
    @Test fun importReplyEpochTransportDeniesOldAndPausedRequests() = runTest {
        for (requestPaused in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val runtime = started(engine = engine {
                if (it.id == "OnImport") { gate.await(); ShioriReply(200, "\\_qValid text\\![open,ghostexplorer]\\e") }
                else ShioriReply(204)
            })
            val sink = runtime.beginImportEvents()!!
            if (requestPaused) { runtime.setResumed(false); runCurrent() }
            sink.emit("OnImport"); runCurrent()
            if (!requestPaused) { runtime.setResumed(false); runCurrent() }
            runtime.setResumed(true); runCurrent()
            gate.complete(Unit); runCurrent(); advanceTimeBy(1_100); runCurrent()
            assertNull(runtime.action())
            assertEquals("Valid text", runtime.ready.frame.sakura.text)
            sink.emit("OnImport"); runCurrent(); advanceTimeBy(1_100); runCurrent()
            assertEquals(HostUiCommand.GhostExplorer, runtime.claim(runtime.action()!!.id))
        }
    }
    @Test fun requestsInitiatedWhilePausedKeepTextWithoutHostDelivery() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = started(engine = engine { when (it.id) {
            "OnFirstBoot" -> menu("OnAnswer")
            "OnAnswer" -> { gate.await(); ShioriReply(200, "\\_qAnswer\\![open,ghostexplorer]\\e") }
            else -> ShioriReply(204)
        } })
        runtime.setResumed(false); runCurrent()
        choose(runtime)
        runtime.setResumed(true); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("Answer", runtime.ready.frame.sakura.text)
        assertNull(runtime.action())
    }
    @Test fun importReplyAfterExplicitReplacementKeepsTextWithoutHostDelivery() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = started(engine = engine { when (it.id) {
            "OnImport" -> { gate.await(); ShioriReply(200, "\\_qImport text\\![open,ghostexplorer]\\e") }
            "OnMouseClick" -> ShioriReply(200, "\\_qReplacement\\e")
            else -> ShioriReply(204)
        } })
        runtime.beginImportEvents()!!.emit("OnImport"); runCurrent()
        runtime.click(0, 0, 0); runCurrent()
        gate.complete(Unit); runCurrent(); advanceTimeBy(2_100); runCurrent()
        assertEquals("Import text", runtime.ready.frame.sakura.text)
        assertNull(runtime.action())
    }
    @Test fun delayedInputAndSurfaceRepliesCarryRequestEpoch() = runTest {
        for (id in listOf("OnUserInput", "OnSurfaceChange")) {
            val gate = CompletableDeferred<Unit>()
            val runtime = started(engine = engine { when (it.id) {
                "OnMouseClick" -> ShioriReply(200, if (id == "OnUserInput")
                    "\\![open,inputbox,answer]\\e" else "\\s1\\_w[1000]\\e")
                id -> { gate.await(); ShioriReply(200, "\\_qReply\\![open,ghostexplorer]\\e") }
                else -> ShioriReply(204)
            } })
            runtime.click(0, 0, 0); runCurrent()
            if (id == "OnUserInput") { runtime.submitInput(runtime.ready.input!!.token, "test"); runCurrent() }
            runtime.setResumed(false); runCurrent(); runtime.setResumed(true); runCurrent()
            gate.complete(Unit); runCurrent(); advanceTimeBy(3_000); runCurrent()
            assertNull(runtime.action())
            assertEquals("Reply", runtime.ready.frame.sakura.text)
        }
    }
    @Test fun newSessionIdsCannotConsumeAnotherSessionsRequests() = runTest {
        val fixture = fixture().apply { imported() }
        val runtime = started(fixture)
        selectTool(runtime)
        val old = runtime.action()!!
        runtime.selectGhost("lookalike"); runCurrent(); runtime.confirmSwitch(); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        choose(runtime)
        val current = runtime.action()!!
        assertTrue(current.id > old.id)
        assertNull(runtime.claim(old.id))
        assertEquals(HostUiCommand.GhostExplorer, runtime.claim(current.id))
    }
    @Test fun nativeSessionCanOpenCommonCommandsAndCannotOpenDeveloper() = runTest {
        val fixture = fixture().apply { imported("native") }
        File(fixture.root, "native/ghost/master/descript.txt").writeText("name,Native\nshiori,satori.dll")
        val port = object : NativePort {
            override val availability = kotlinx.coroutines.flow.MutableStateFlow(NativeAvailability.Available)
            override suspend fun load(kind: NativeKind, directory: File, libraryName: String): NativeLoadResult {
                availability.value = NativeAvailability.Occupied
                return NativeLoadResult.Loaded(NativeLease(20))
            }
            override suspend fun request(lease: NativeLease, event: ShioriEvent) =
                if (event.id == "OnMouseClick") ShioriReply(200,
                    "\\_qBefore\\![open,developer]After\\![open,ghostexplorer]\\![open,configurationdialog,about]\\e") else ShioriReply(204)
            override suspend fun unload(lease: NativeLease) = NativeUnloadResult.Unloaded
            override suspend fun shutdown(lease: NativeLease, notifyDestroy: Boolean, timeoutMillis: Long) = NativeShutdownResult.Completed
        }
        val runtime = GhostRuntime({ fixture.base }, { BuiltInShiori(it.content) }, Boot(), backgroundScope,
            InstalledGhostRepository(fixture.root), Last("native"), port,
            ioDispatcher = StandardTestDispatcher(testScheduler), elapsedRealtime = { testScheduler.currentTime })
        runtime.start("en"); runtime.setResumed(true); runCurrent()
        runtime.click(0, 0, 0); runCurrent()
        assertEquals("BeforeAfter", runtime.ready.frame.sakura.text)
        assertEquals(HostUiCommand.GhostExplorer, runtime.claim(runtime.action()!!.id))
        assertEquals(HostUiCommand.About, runtime.claim(runtime.action()!!.id))
        assertNull(runtime.action())
    }
}
