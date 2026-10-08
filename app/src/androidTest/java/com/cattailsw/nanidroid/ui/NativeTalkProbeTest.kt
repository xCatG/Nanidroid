package com.cattailsw.nanidroid.ui

import android.os.SystemClock
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.MainActivity
import com.cattailsw.nanidroid.NanidroidApplication
import com.cattailsw.nanidroid.data.BootStateStore
import com.cattailsw.nanidroid.data.LastGhostStore
import com.cattailsw.nanidroid.engine.BuiltInShiori
import com.cattailsw.nanidroid.engine.NativeAvailability
import com.cattailsw.nanidroid.engine.NativeKind
import com.cattailsw.nanidroid.engine.NativeLoadResult
import com.cattailsw.nanidroid.engine.NativeLease
import com.cattailsw.nanidroid.engine.NativeShioriHost
import com.cattailsw.nanidroid.engine.NativeUnloadResult
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.engine.ShioriReply
import com.cattailsw.nanidroid.ghost.BundledGhostRepository
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import com.cattailsw.nanidroid.runtime.GhostRuntime
import com.cattailsw.nanidroid.runtime.HostNativePort
import com.cattailsw.nanidroid.runtime.NativePort
import com.cattailsw.nanidroid.runtime.ScriptPlayer
import com.cattailsw.nanidroid.runtime.StageState
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded nonboot probes using user-provided masters staged outside the APK. */
@RunWith(AndroidJUnit4::class)
class NativeTalkProbeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun nativeAboutBackKeepsSameReadyLeaseWithoutBootOrClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Run host-staged native interaction probe; required arguments: fixtureId",
            listOf("fixtureId").any { args.containsKey(it) })
        require(listOf("fixtureId").all { !args.getString(it).isNullOrBlank() }) {
            "Provide all nonblank host arguments: fixtureId"
        }
        val fixtureId = requireNotNull(args.getString("fixtureId"))
        require(fixtureId == "earthquake_duo") { "fixtureId must be earthquake_duo" }
        assertTrue("Exact Earthquake corpus row was not imported",
            File(context.filesDir, "ghost/$fixtureId/ghost/master/descript.txt").isFile)
        assertTrue(context.getSharedPreferences("last_ghost", 0).edit()
            .putString("last_ghost", fixtureId).commit())
        val runtime = (context.applicationContext as NanidroidApplication).runtime
        val host = NativeShioriHost.process
        host.trackEventsForTest(setOf("OnBoot", "OnFirstBoot", "OnClose")).use {
        fun capture(phase: String) {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(requireNotNull(context.getExternalFilesDir(null)), "native-about-$phase.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        assertEquals(NativeAvailability.Available, host.availability.value)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
            waitUntil(45_000, "nativeAboutBackKeepsSameReadyLeaseWithoutBootOrClose/" + fixtureId, "ready Earthquake ghost with native lease and boot event",
                snapshot = { nativeSnapshot(runtime, host) + " " }) {
                (runtime.state.value as? StageState.Ready)?.ghostName == "Earthquake Duo!" &&
                    runtime.activeNativeLeaseForTest() != null &&
                    host.observationForTest().let { it.eventCount("OnBoot") + it.eventCount("OnFirstBoot") > 0 }
            }
            compose.onNodeWithTag("about-action").assertIsDisplayed()
            val lease = requireNotNull(runtime.activeNativeLeaseForTest())
            val baseline = host.observationForTest()
            compose.onNodeWithTag("about-action").performClick()
            compose.onNodeWithTag("about-dialog").assertIsDisplayed()
            capture("open")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("about-dialog").assertDoesNotExist()
            compose.onNodeWithTag("about-action").assertIsDisplayed()
            SystemClock.sleep(400) // Let the dialog window's exit animation finish for the capture.
            capture("dismissed")
            assertEquals("About Back changed native lease", lease, runtime.activeNativeLeaseForTest())
            assertEquals("About Back left native Ready stage", "Earthquake Duo!",
                (runtime.state.value as StageState.Ready).ghostName)
            val after = host.observationForTest()
            assertEquals("About loaded another native engine", baseline.loads, after.loads)
            assertEquals("About unloaded the native engine", baseline.unloads, after.unloads)
            assertEquals("About dispatched another boot or a close", emptyList<String>(),
                setOf("OnBoot", "OnFirstBoot", "OnClose").filter { after.eventCount(it) != baseline.eventCount(it) })
            println("M5_NATIVE_ABOUT fixture=$fixtureId lease=${lease.generation} " +
                "loads=${after.loads} unloads=${after.unloads} " +
                "eventsAfterAbout=${after.events.drop(baseline.events.size)}")
            } finally {
                runtime.close()
                waitUntil(30_000, "nativeAboutBackKeepsSameReadyLeaseWithoutBootOrClose/" + fixtureId, "runtime finished",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { runtime.state.value is StageState.Finished }
                waitUntil(30_000, "nativeAboutBackKeepsSameReadyLeaseWithoutBootOrClose/" + fixtureId, "native host available",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { host.availability.value == NativeAvailability.Available }
                val cleaned = host.observationForTest()
                println("M5_NATIVE_ABOUT_CLEANUP loads=${cleaned.loads} " +
                    "unloads=${cleaned.unloads} available=${host.availability.value}")
            }
        }
        }
    }

    @Test fun groupBDeepInteraction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Run host-staged native interaction probe; required arguments: fixtureId",
            listOf("fixtureId").any { args.containsKey(it) })
        require(listOf("fixtureId").all { !args.getString(it).isNullOrBlank() }) {
            "Provide all nonblank host arguments: fixtureId"
        }
        val id = requireNotNull(args.getString("fixtureId"))
        val expectedName = when (id) {
            "Nanika_Atsume" -> "Nanika Atsume"
            "Snake_Otacon" -> "Snake and Otacon"
            "bancho_jet" -> "Watchdog Banch"
            "Yes_Man" -> "Yes Man"
            else -> error("Unexpected group B fixture: $id")
        }
        val minimumBootLetters = if (id == "Snake_Otacon") 7 else 8
        assertTrue("Fresh import missing: $id",
            File(context.filesDir, "ghost/$id/ghost/master/descript.txt").isFile)
        val runtime = (context.applicationContext as NanidroidApplication).runtime
        val host = NativeShioriHost.process
        host.trackEventsForTest(setOf("OnMouseDoubleClick", "OnAiTalk", "OnAITalk")).use {
        fun ready(): StageState.Ready = runtime.state.value as StageState.Ready
        fun detail(phase: String) {
            val state = ready()
            println("M5_DEEP_B id=$id phase=$phase ghost=${state.ghostName} " +
                "sakura=${state.frame.sakura.text.take(300).replace('\n', '|')} " +
                "kero=${state.frame.kero.text.take(300).replace('\n', '|')} " +
                "surfaces=${state.frame.sakura.surfaceId},${state.frame.kero.surfaceId} " +
                "choices=${state.choices.values.flatten().map { it.label }} " +
                "error=${state.activationError} events=${host.observationForTest().events.takeLast(8)}")
        }
        fun capture(phase: String) {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(requireNotNull(context.getExternalFilesDir(null)), "deep-b-$id-$phase.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitUntil(45_000, "groupBDeepInteraction/" + id, "ready expected fixture ghost",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { (runtime.state.value as? StageState.Ready)?.ghostName?.startsWith(expectedName) == true }
            assertEquals(id, com.cattailsw.nanidroid.data.PreferencesLastGhostStore(
                context.getSharedPreferences("last_ghost", 0)).read())
            assertEquals(null, ready().activationError)
            waitUntil(45_000, "groupBDeepInteraction/" + id, "active native lease",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { runtime.activeNativeLeaseForTest() != null }
            compose.waitUntil(45_000) {
                val authored = (runtime.state.value as? StageState.Ready)?.frame?.let { frame ->
                    val speakers = listOf(frame.sakura.text, frame.kero.text)
                    if (speakers.sumOf { it.count(Char::isLetter) } >= minimumBootLetters) {
                        speakers.firstOrNull { it.any(Char::isLetter) }
                    } else null
                }
                authored != null &&
                    compose.onAllNodesWithTag("stage-controls-toggle").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText(authored.take(4), substring = true, useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
            }
            val bootSpeaker = listOf(ready().frame.sakura.text, ready().frame.kero.text)
                .first { it.any(Char::isLetter) }
            compose.onAllNodesWithText(bootSpeaker.take(4), substring = true, useUnmergedTree = true)
                .onFirst().assertIsDisplayed()
            detail("boot")
            capture("boot")
            val bootText = ready().frame.let { it.sakura.text + it.kero.text }
            assertTrue("No substantive authored boot dialogue visible for $id",
                bootText.count(Char::isLetter) >= minimumBootLetters)

            val beforeClick = host.observationForTest().eventCount("OnMouseDoubleClick")
            runtime.doubleClick(0, 100, 100)
            waitUntil(20_000, "groupBDeepInteraction/" + id, "double-click event count increased",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { host.observationForTest().eventCount("OnMouseDoubleClick") > beforeClick }
            SystemClock.sleep(1_300)
            detail("runtime-double-click")
            capture("runtime-double-click")
            val menuText = ready().frame.let { it.sakura.text + it.kero.text }
            assertTrue("Double-click produced no authored visible dialogue for $id",
                menuText.count(Char::isLetter) >= 8)
            val offered = ready().choices.values.flatten()
            val expectedLabel = when (id) {
                "Nanika_Atsume" -> "Talk"
                "Snake_Otacon" -> "Talk to me."
                "bancho_jet" -> "Say Something"
                "Yes_Man" -> "Say Something"
                else -> null
            }
            val choice = if (offered.isEmpty()) null else {
                assertTrue("Expected authored choice missing for $id: ${offered.map { it.label }}",
                    offered.any { it.label == expectedLabel })
                offered.first { it.label == expectedLabel }
            }
            if (choice != null) {
                val expectedChoiceEvent = when (id) {
                    "Snake_Otacon" -> "OnAiTalk"
                    "bancho_jet" -> "OnAITalk"
                    "Yes_Man" -> "OnAiTalk"
                    else -> null
                }
                val eventsBefore = host.observationForTest()
                println("M5_DEEP_B id=$id phase=choose label=${choice.label}")
                runtime.choose(choice.token)
                waitUntil(20_000, "groupBDeepInteraction/" + id, "choice event count increased once or native request ordinal advanced",
                snapshot = { nativeSnapshot(runtime, host) + " " }) {
                    val events = host.observationForTest()
                    if (expectedChoiceEvent == null) events.requestOrdinal > eventsBefore.requestOrdinal
                    else events.eventCount(expectedChoiceEvent) ==
                        eventsBefore.eventCount(expectedChoiceEvent) + 1
                }
                println("M5_DEEP_B id=$id phase=choice-events events=${host.observationForTest().events.drop(eventsBefore.events.size)}")
                SystemClock.sleep(1_300)
                detail("choice-reply")
                capture("choice-reply")
                val replyText = ready().frame.let { it.sakura.text + it.kero.text }
                assertTrue("Authored choice yielded no new visible dialogue for $id",
                    replyText != menuText && replyText.count(Char::isLetter) >= 8)
            } else {
                println("M5_DEEP_B id=$id phase=choice-unoffered")
            }

            runtime.selectGhost("nanidroid")
            waitUntil(20_000, "groupBDeepInteraction/" + id, "switch prompt targets Nanidroid",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { (runtime.state.value as? StageState.Ready)?.switchPrompt?.directoryId == "nanidroid" }
            runtime.confirmSwitch()
            waitUntil(60_000, "groupBDeepInteraction/" + id, "ready Nanidroid ghost",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { (runtime.state.value as? StageState.Ready)?.ghostName == "Nanidroid" }
            println("M5_DEEP_B id=$id phase=switch-away")
            runtime.selectGhost(id)
            waitUntil(20_000, "groupBDeepInteraction/" + id, "switch prompt targets fixture",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { (runtime.state.value as? StageState.Ready)?.switchPrompt?.directoryId == id }
            runtime.confirmSwitch()
            waitUntil(60_000, "groupBDeepInteraction/" + id, "ready expected fixture ghost",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { (runtime.state.value as? StageState.Ready)?.ghostName?.startsWith(expectedName) == true }
            SystemClock.sleep(1_300)
            detail("switch-back")
            capture("switch-back")
            runtime.close()
            println("M5_DEEP_B id=$id phase=single-close-requested")
            waitUntil(90_000, "groupBDeepInteraction/" + id, "runtime finished",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { runtime.state.value is StageState.Finished }
            println("M5_DEEP_B id=$id phase=finished availability=${host.availability.value}")
        }
        assertEquals(NativeAvailability.Available, host.availability.value)
        }
    }

    @Test fun groupBRecordedNativeReplyMatchesVisibleState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Run host-staged native interaction probe; required arguments: fixtureId",
            listOf("fixtureId").any { args.containsKey(it) })
        require(listOf("fixtureId").all { !args.getString(it).isNullOrBlank() }) {
            "Provide all nonblank host arguments: fixtureId"
        }
        val id = requireNotNull(args.getString("fixtureId"))
        val expectedName = when (id) {
            "Nanika_Atsume" -> "Nanika Atsume"
            "Snake_Otacon" -> "Snake and Otacon"
            "bancho_jet" -> "Watchdog Banch"
            "Yes_Man" -> "Yes Man"
            else -> error("Unexpected group B fixture: $id")
        }
        assertTrue("Freshly imported group B ghost missing: $id",
            File(context.filesDir, "ghost/$id/ghost/master/descript.txt").isFile)
        val host = NativeShioriHost.process
        host.trackEventsForTest(setOf("OnChoiceSelect", "OnAiTalk", "OnAITalk")).use {
        assertEquals(NativeAvailability.Available, host.availability.value)
        val delegate = HostNativePort(host)
        val clickReply = AtomicReference<ShioriReply?>()
        val choiceReply = AtomicReference<ShioriReply?>()
        val choiceEvent = when (id) {
            "Nanika_Atsume" -> "OnChoiceSelect"
            "Snake_Otacon" -> "OnAiTalk"
            "bancho_jet" -> "OnAITalk"
            "Yes_Man" -> "OnAiTalk"
            else -> null
        }
        val recordingPort = object : NativePort by delegate {
            override suspend fun request(lease: NativeLease, event: ShioriEvent): ShioriReply =
                delegate.request(lease, event).also { reply ->
                    if (event.id == "OnMouseDoubleClick") clickReply.set(reply)
                    if (event.id == choiceEvent) choiceReply.set(reply)
                }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = BundledGhostRepository(
            openAsset = { context.assets.open("nanidroid.zip") },
            ghostRoot = File(context.filesDir, "ghost"),
        )
        val runtime = GhostRuntime(
            loadGhost = { repository.load(repository.ensureInstalled(), "en") },
            engineFactory = { BuiltInShiori(it.content) },
            bootState = object : BootStateStore {
                override suspend fun recordActivation(directoryId: String) = false
                override suspend fun consumeOnboarding() = false
            },
            scope = scope,
            installedGhosts = InstalledGhostRepository(File(context.filesDir, "ghost")),
            lastGhostStore = object : LastGhostStore {
                override fun read() = id
                override fun write(directoryId: String) = Unit
            },
            nativeHost = recordingPort,
        )
        fun visibleText(): String = (runtime.state.value as StageState.Ready).frame.let {
            it.sakura.text + it.kero.text
        }
        fun matchReply(phase: String, reply: ShioriReply): String {
            assertEquals("$id $phase native status", 200, reply.status)
            val script = requireNotNull(reply.value)
            val nativeText = ScriptPlayer(script).advanceBy(30_000).let {
                it.sakura.text + it.kero.text
            }
            assertTrue("$id $phase native script lacked substantive text: $script",
                nativeText.count(Char::isLetter) >= 8)
            val signature = nativeText.trimStart().take(16)
            waitUntil(30_000, "groupBRecordedNativeReplyMatchesVisibleState/" + id, "ready frame contains native reply signature",
                snapshot = { nativeSnapshot(runtime, host) + " clickReplyStatus=${clickReply.get()?.status} choiceReplyStatus=${choiceReply.get()?.status}" }) { (runtime.state.value as? StageState.Ready)?.frame?.let {
                frame -> (frame.sakura.text + frame.kero.text).contains(signature)
            } == true }
            println("M5_DEEP_B_NATIVE id=$id phase=$phase status=${reply.status} " +
                "matched=${signature.replace('\n', '|')} " +
                "visible=${visibleText().take(160).replace('\n', '|')}")
            return nativeText
        }
        try {
            runBlocking { runtime.start("en") }
            waitUntil(45_000, "groupBRecordedNativeReplyMatchesVisibleState/" + id, "ready expected fixture ghost with native lease",
                snapshot = { nativeSnapshot(runtime, host) + " clickReplyStatus=${clickReply.get()?.status} choiceReplyStatus=${choiceReply.get()?.status}" }) {
                (runtime.state.value as? StageState.Ready)?.ghostName?.startsWith(expectedName) == true &&
                    runtime.activeNativeLeaseForTest() != null
            }
            runtime.setResumed(true)
            runtime.doubleClick(0, 100, 100)
            waitUntil(30_000, "groupBRecordedNativeReplyMatchesVisibleState/" + id, "native double-click reply recorded",
                snapshot = { nativeSnapshot(runtime, host) + " clickReplyStatus=${clickReply.get()?.status} choiceReplyStatus=${choiceReply.get()?.status}" }) { clickReply.get() != null }
            matchReply("double-click", requireNotNull(clickReply.get()))
            waitUntil(30_000, "groupBRecordedNativeReplyMatchesVisibleState/" + id, "ready frame offers choices or dialogue ended",
                snapshot = { nativeSnapshot(runtime, host) + " clickReplyStatus=${clickReply.get()?.status} choiceReplyStatus=${choiceReply.get()?.status}" }) {
                val ready = runtime.state.value as? StageState.Ready
                ready?.choices?.values?.flatten()?.isNotEmpty() == true || ready?.frame?.ended == true
            }
            val offered = (runtime.state.value as StageState.Ready).choices.values.flatten()
            val expectedLabel = when (id) {
                "Nanika_Atsume" -> "Talk"
                "Snake_Otacon" -> "Talk to me."
                "bancho_jet" -> "Say Something"
                "Yes_Man" -> "Say Something"
                else -> null
            }
            if (offered.isNotEmpty()) {
                assertTrue("$id expected authored choice absent: ${offered.map { it.label }}",
                    offered.any { it.label == expectedLabel })
                val token = offered.first { it.label == expectedLabel }.token
                val before = host.observationForTest().eventCount(requireNotNull(choiceEvent))
                runtime.choose(token)
                waitUntil(30_000, "groupBRecordedNativeReplyMatchesVisibleState/" + id, "native choice reply recorded",
                snapshot = { nativeSnapshot(runtime, host) + " clickReplyStatus=${clickReply.get()?.status} choiceReplyStatus=${choiceReply.get()?.status}" }) { choiceReply.get() != null }
                assertEquals("$id choice native event count", before + 1,
                    host.observationForTest().eventCount(requireNotNull(choiceEvent)))
                matchReply("choice-$expectedLabel", requireNotNull(choiceReply.get()))
                println("M5_DEEP_B_NATIVE id=$id phase=choice-routed event=$choiceEvent label=$expectedLabel")
            } else {
                assertTrue("$id expected menu absent", id in setOf("Nanika_Atsume", "Yes_Man"))
                println("M5_DEEP_B_NATIVE id=$id phase=choice-unoffered")
            }
        } finally {
            try {
                runtime.close()
                waitUntil(90_000, "groupBRecordedNativeReplyMatchesVisibleState/" + id, "runtime finished",
                snapshot = { nativeSnapshot(runtime, host) + " clickReplyStatus=${clickReply.get()?.status} choiceReplyStatus=${choiceReply.get()?.status}" }) { runtime.state.value is StageState.Finished }
            } finally {
                scope.cancel()
            }
        }
        assertEquals("$id native owner after one close", NativeAvailability.Available,
            host.availability.value)
        }
    }

    @Test fun groupADeepInteraction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Run host-staged native interaction probe; required arguments: fixtureId",
            listOf("fixtureId").any { args.containsKey(it) })
        require(listOf("fixtureId").all { !args.getString(it).isNullOrBlank() }) {
            "Provide all nonblank host arguments: fixtureId"
        }
        val id = requireNotNull(args.getString("fixtureId"))
        require(id in setOf("2elf", "earthquake_duo", "lobo_okuajub", "big_red_button")) { "Invalid group A fixtureId: $id" }
        assertTrue("Freshly imported ghost missing: $id", File(context.filesDir, "ghost/$id/ghost/master/descript.txt").isFile)
        val app = context.applicationContext as NanidroidApplication
        val runtime = app.runtime
        val host = NativeShioriHost.process
        host.trackEventsForTest(setOf("OnMouseDoubleClick", "OnAiTalk", "OnAITalk")).use {
        fun frame(): StageState.Ready = runtime.state.value as StageState.Ready
        fun detail(label: String) {
            val ready = frame()
            val sakura = ready.frame.sakura
            val kero = ready.frame.kero
            println("M5_DEEP_A id=$id phase=$label ghost=${ready.ghostName} " +
                "sakura=${sakura.text.take(240).replace('\n', '|')} " +
                "kero=${kero.text.take(240).replace('\n', '|')} " +
                "choices=${ready.choices.values.flatten().map { it.label }} " +
                "surfaces=${sakura.surfaceId},${kero.surfaceId} error=${ready.activationError}")
        }
        fun capture(label: String) {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(requireNotNull(context.getExternalFilesDir(null)), "deep-a-$id-$label.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitUntil(30_000, "groupADeepInteraction/" + id, "ready named ghost",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { (runtime.state.value as? StageState.Ready)?.ghostName?.isNotBlank() == true }
            assertEquals(id, com.cattailsw.nanidroid.data.PreferencesLastGhostStore(
                context.getSharedPreferences("last_ghost", 0)).read())
            assertEquals("Wrong ghost active after fresh import", when (id) {
                "2elf" -> "双子のエルフ"
                "earthquake_duo" -> "Earthquake Duo!"
                "lobo_okuajub" -> "LOBO"
                else -> "Big Red Button"
            }, frame().ghostName)
            assertEquals("Unexpected activation fallback", null, frame().activationError)
            waitUntil(30_000, "groupADeepInteraction/" + id, "active native lease",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { runtime.activeNativeLeaseForTest() != null }
            SystemClock.sleep(1200)
            detail("boot")
            capture("boot")
            val before = host.observationForTest().eventCount("OnMouseDoubleClick")
            runtime.doubleClick(0, 100, 100)
            println("M5_DEEP_A id=$id phase=runtime-double-click events=${host.observationForTest().events.takeLast(8)}")
            waitUntil(15_000, "groupADeepInteraction/" + id, "double-click event count increased",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { host.observationForTest().eventCount("OnMouseDoubleClick") > before }
            SystemClock.sleep(1200)
            if (id == "2elf") {
                waitUntil(15_000, "groupADeepInteraction/" + id, "ready frame displays options text",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { frame().frame.sakura.text.contains("オプション") }
            }
            detail("double-tap")
            capture("double-tap")
            if (id == "big_red_button") {
                assertEquals("Big Red Button authored double-click surface", 1, frame().frame.sakura.surfaceId)
                assertEquals("Big Red Button has no authored double-click dialogue", "",
                    frame().frame.sakura.text + frame().frame.kero.text)
            }
            if (id == "earthquake_duo") {
                waitUntil(15_000, "groupADeepInteraction/" + id, "ready frame offers Say something choice",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { frame().choices.values.flatten().any { it.label == "Say something" } }
                val token = frame().choices.values.flatten().first { it.label == "Say something" }.token
                val talkBefore = host.observationForTest().eventCount("OnAiTalk")
                runtime.choose(token)
                waitUntil(15_000, "groupADeepInteraction/" + id, "OnAiTalk event count increased once",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { host.observationForTest().eventCount("OnAiTalk") == talkBefore + 1 }
                SystemClock.sleep(1300)
                detail("choice-on-ai-talk")
                capture("choice-on-ai-talk")
            }
            if (id == "lobo_okuajub") {
                waitUntil(20_000, "groupADeepInteraction/" + id, "ready frame offers Say Something choice",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { frame().choices.values.flatten().any { it.label == "Say Something" } }
                SystemClock.sleep(700)
                detail("menu-ready")
                capture("menu-ready")
                val token = frame().choices.values.flatten().first { it.label == "Say Something" }.token
                val talkBefore = host.observationForTest().eventCount("OnAITalk")
                runtime.choose(token)
                waitUntil(15_000, "groupADeepInteraction/" + id, "OnAITalk event count increased once",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { host.observationForTest().eventCount("OnAITalk") == talkBefore + 1 }
                SystemClock.sleep(1300)
                assertTrue("LOBO choice produced no visible dialogue",
                    (frame().frame.sakura.text + frame().frame.kero.text).count(Char::isLetter) >= 8)
                detail("choice-on-ai-talk")
                capture("choice-on-ai-talk")
            }
            runtime.selectGhost("nanidroid")
            waitUntil(15_000, "groupADeepInteraction/" + id, "switch prompt targets Nanidroid",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { (runtime.state.value as? StageState.Ready)?.switchPrompt?.directoryId == "nanidroid" }
            runtime.confirmSwitch()
            waitUntil(60_000, "groupADeepInteraction/" + id, "ready Nanidroid ghost",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { (runtime.state.value as? StageState.Ready)?.ghostName == "Nanidroid" }
            println("M5_DEEP_A id=$id phase=switch-away name=${frame().ghostName}")
            runtime.selectGhost(id)
            waitUntil(15_000, "groupADeepInteraction/" + id, "switch prompt targets fixture",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { (runtime.state.value as? StageState.Ready)?.switchPrompt?.directoryId == id }
            runtime.confirmSwitch()
            waitUntil(60_000, "groupADeepInteraction/" + id, "ready ghost differs from Nanidroid",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { (runtime.state.value as? StageState.Ready)?.ghostName != "Nanidroid" }
            SystemClock.sleep(1000)
            detail("switch-back")
            capture("switch-back")
            runtime.close()
            println("M5_DEEP_A id=$id phase=single-close-requested")
            waitUntil(90_000, "groupADeepInteraction/" + id, "runtime finished",
                snapshot = { nativeSnapshot(runtime, host) + " " }) { runtime.state.value is StageState.Finished }
            println("M5_DEEP_A id=$id phase=finished availability=${host.availability.value}")
        }
        assertEquals(NativeAvailability.Available, host.availability.value)
        }
    }

    @Test fun satoriDoubleClick() = probe(NativeKind.SATORI, "satori", "satori.dll",
        ShioriEvent("OnMouseDoubleClick", listOf("100", "100", "0", "0", "hand")))

    @Test fun satoriDoubleClickWithoutCollision() = probe(NativeKind.SATORI, "satori", "satori.dll",
        ShioriEvent("OnMouseDoubleClick", listOf("100", "100", "0", "0", "", "0", "touch")))

    @Test fun kawariAiTalk() = probe(NativeKind.KAWARI, "kawari", "shiori.dll",
        ShioriEvent("OnAITalk"))

    @Test fun yayaAiTalk() = probe(NativeKind.YAYA, "yaya", "yaya.dll",
        ShioriEvent("OnAiTalk"))

    @Test fun yayaDoubleClickWithoutCollision() = probe(NativeKind.YAYA, "yaya", "yaya.dll",
        ShioriEvent("OnMouseDoubleClick", listOf("100", "100", "0", "0", "", "0", "touch")))

    @Test fun snakeV132SingleCloseDiagnostic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val master = File(context.filesDir, "ghost/Snake_Otacon/ghost/master")
        assumeTrue("V1.3.2 corpus installation not staged", File(master, "descript.txt").isFile)
        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("V1.3.2 descriptor fingerprint",
            "6e86641a3ddae983a3cf16373ca40420c5dfd92e218d193ef79b669284eb8293",
            sha256(File(master, "descript.txt")))
        assertEquals("V1.3.2 YAYA configuration fingerprint",
            "2ccc9703c726e5cbe3882972159ea5b4612d501c68556ab29c944dfaedb4da7f",
            sha256(File(master, "yaya.txt")))
        val host = NativeShioriHost.process
        host.trackEventsForTest(setOf("OnClose")).use {
        val before = host.observationForTest()
        assertEquals(NativeAvailability.Available, host.availability.value)
        val delegate = HostNativePort(host)
        val closeReply = AtomicReference<ShioriReply?>()
        val port = object : NativePort by delegate {
            override suspend fun request(lease: NativeLease, event: ShioriEvent): ShioriReply =
                delegate.request(lease, event).also { reply ->
                    if (event.id == "OnClose") closeReply.set(reply)
                }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = BundledGhostRepository(
            openAsset = { context.assets.open("nanidroid.zip") },
            ghostRoot = File(context.filesDir, "ghost"),
        )
        val runtime = GhostRuntime(
            loadGhost = { repository.load(repository.ensureInstalled(), "en") },
            engineFactory = { BuiltInShiori(it.content) },
            bootState = object : BootStateStore {
                override suspend fun recordActivation(directoryId: String) = false
                override suspend fun consumeOnboarding() = false
            },
            scope = scope,
            installedGhosts = InstalledGhostRepository(File(context.filesDir, "ghost")),
            lastGhostStore = object : LastGhostStore {
                override fun read() = "Snake_Otacon"
                override fun write(directoryId: String) = Unit
            },
            nativeHost = port,
        )
        try {
            runBlocking { runtime.start("en") }
            waitUntil(30_000, "snakeV132SingleCloseDiagnostic/" + "Snake_Otacon", "ready Snake and Otacon ghost without activation error and with native lease",
                snapshot = { nativeSnapshot(runtime, host) + " closeReplyStatus=${closeReply.get()?.status}" }) {
                val ready = runtime.state.value as? StageState.Ready
                ready?.ghostName == "Snake and Otacon" && ready.activationError == null &&
                    runtime.activeNativeLeaseForTest() != null
            }
            runtime.setResumed(true)
            val tickerField = GhostRuntime::class.java.getDeclaredField("ticker").apply { isAccessible = true }
            waitUntil(5_000, "snakeV132SingleCloseDiagnostic/" + "Snake_Otacon", "runtime ticker active",
                snapshot = { nativeSnapshot(runtime, host) + " closeReplyStatus=${closeReply.get()?.status}" }) { (tickerField.get(runtime) as? kotlinx.coroutines.Job)?.isActive == true }
            SystemClock.sleep(2_000)
            val loaded = runtime.state.value as StageState.Ready
            println("M5_CLOSE stable ghost=${loaded.ghostName} lease=${runtime.activeNativeLeaseForTest() != null} " +
                "ticker=${(tickerField.get(runtime) as? kotlinx.coroutines.Job)?.isActive} " +
                "text=${loaded.frame.sakura.text + loaded.frame.kero.text} availability=${host.availability.value}")
            val startedAt = SystemClock.elapsedRealtime()
            runtime.close() // This diagnostic intentionally issues exactly one close.
            val stateField = GhostRuntime::class.java.getDeclaredField("sessionState").apply { isAccessible = true }
            var lastSnapshot = ""
            var handledToken: com.cattailsw.nanidroid.runtime.InteractionToken? = null
            var sawClosingBeforeCleanup = false
            var lastFarewellText = ""
            var sawFarewellAdvance = false
            while (SystemClock.elapsedRealtime() - startedAt < 90_000) {
                val state = runtime.state.value
                val session = stateField.get(runtime)
                val ready = state as? StageState.Ready
                val cleanupStarted = runCatching {
                    session.javaClass.getDeclaredField("cleanupStarted")
                        .apply { isAccessible = true }.get(session)
                }.getOrNull()
                val farewellText = ready?.frame?.let { it.sakura.text + it.kero.text }.orEmpty()
                if (session.javaClass.simpleName == "Closing" && cleanupStarted == false) {
                    sawClosingBeforeCleanup = true
                    if (lastFarewellText.isNotEmpty() && farewellText != lastFarewellText) {
                        sawFarewellAdvance = true
                    }
                    lastFarewellText = farewellText
                }
                val snapshot = "elapsed=${SystemClock.elapsedRealtime() - startedAt} state=${state.javaClass.simpleName} " +
                    "session=${session.javaClass.simpleName} cleanup=$cleanupStarted " +
                    "ticker=${(tickerField.get(runtime) as? kotlinx.coroutines.Job)?.isActive} lease=${runtime.activeNativeLeaseForTest() != null} " +
                    "availability=${host.availability.value} ended=${ready?.frame?.ended} " +
                    "text=${ready?.frame?.let { it.sakura.text + it.kero.text }} " +
                    "choices=${ready?.choices?.values?.flatten()?.map { it.label }} input=${ready?.input?.boxId}"
                if (snapshot != lastSnapshot) println("M5_CLOSE $snapshot")
                lastSnapshot = snapshot
                if (state is StageState.Finished) break
                val choice = ready?.choices?.values?.flatten()?.firstOrNull()
                if (choice != null && handledToken != choice.token) {
                    handledToken = choice.token
                    println("M5_CLOSE resolveChoice label=${choice.label} token=${choice.token}")
                    runtime.choose(choice.token)
                } else {
                    val input = ready?.input
                    if (input != null && handledToken != input.token) {
                        handledToken = input.token
                        println("M5_CLOSE resolveInput boxId=${input.boxId} token=${input.token}")
                        runtime.submitInput(input.token, "User")
                    }
                }
                SystemClock.sleep(100)
            }
            val reply = closeReply.get()
            println("M5_CLOSE replyStatus=${reply?.status} replyScript=${reply?.value} " +
                "closeEvents=${host.observationForTest().eventCount("OnClose") - before.eventCount("OnClose")} " +
                "closingBeforeCleanup=$sawClosingBeforeCleanup farewellAdvanced=$sawFarewellAdvance " +
                "final=${runtime.state.value.javaClass.simpleName} availability=${host.availability.value}")
            assertEquals("V1.3.2 authored OnClose status", 200, reply?.status)
            assertTrue("V1.3.2 authored short-session farewell missing: ${reply?.value}",
                listOf("Saying goodbye so soon?", "Did you open us accidentally? Sorry...")
                    .any { reply?.value?.contains(it) == true })
            assertTrue("V1.3.2 authored waits missing", reply?.value?.contains("\\w8\\w8") == true)
            assertTrue("Close skipped farewell session", sawClosingBeforeCleanup)
            assertTrue("Close skipped farewell playback", sawFarewellAdvance)
            assertEquals("Exactly one OnClose", 1L,
                host.observationForTest().eventCount("OnClose") - before.eventCount("OnClose"))
            assertTrue("Single close did not finish within 90 seconds", runtime.state.value is StageState.Finished)
            assertEquals(NativeAvailability.Available, host.availability.value)
        } finally {
            scope.cancel()
        }
        }
    }

    @Test fun earthquakeRuntimeChoiceDispatchesNativeOnAiTalk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureId = InstrumentationRegistry.getArguments().getString("fixtureId") ?: "task7-earthquake"
        require(fixtureId in setOf("task7-earthquake", "earthquake_duo"))
        val master = File(context.filesDir, "ghost/$fixtureId/ghost/master")
        assumeTrue("Earthquake disposable corpus copy not staged", File(master, "descript.txt").isFile)
        val host = NativeShioriHost.process
        host.trackEventsForTest(setOf("OnBoot", "OnFirstBoot", "OnSecondChange", "OnAiTalk")).use {
        val before = host.observationForTest()
        assertEquals("Native owner before Earthquake", NativeAvailability.Available, host.availability.value)
        val delegate = HostNativePort(host)
        val menuReply = AtomicReference<ShioriReply?>()
        val talkReply = AtomicReference<ShioriReply?>()
        val recordingPort = object : NativePort by delegate {
            override suspend fun request(lease: NativeLease, event: ShioriEvent): ShioriReply =
                delegate.request(lease, event).also { reply ->
                    when (event.id) {
                        "OnMouseDoubleClick" -> menuReply.set(reply)
                        "OnAiTalk" -> talkReply.set(reply)
                    }
                }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = BundledGhostRepository(
            openAsset = { context.assets.open("nanidroid.zip") },
            ghostRoot = File(context.filesDir, "ghost"),
        )
        val runtime = GhostRuntime(
            loadGhost = { repository.load(repository.ensureInstalled(), "en") },
            engineFactory = { BuiltInShiori(it.content) },
            bootState = object : BootStateStore {
                override suspend fun recordActivation(directoryId: String) = false
                override suspend fun consumeOnboarding() = false
            },
            scope = scope,
            installedGhosts = InstalledGhostRepository(File(context.filesDir, "ghost")),
            lastGhostStore = object : LastGhostStore {
                override fun read() = fixtureId
                override fun write(directoryId: String) = Unit
            },
            nativeHost = recordingPort,
        )
        try {
            runBlocking { runtime.start("en") }
            waitUntil(30_000, "earthquakeRuntimeChoiceDispatchesNativeOnAiTalk/" + fixtureId, "active native lease",
                snapshot = { nativeSnapshot(runtime, host) + " menuReplyStatus=${menuReply.get()?.status} talkReplyStatus=${talkReply.get()?.status}" }) { runtime.activeNativeLeaseForTest() != null }
            waitUntil(30_000, "earthquakeRuntimeChoiceDispatchesNativeOnAiTalk/" + fixtureId, "boot or first-boot event count increased",
                snapshot = { nativeSnapshot(runtime, host) + " menuReplyStatus=${menuReply.get()?.status} talkReplyStatus=${talkReply.get()?.status}" }) {
                host.observationForTest().let { it.eventCount("OnFirstBoot") + it.eventCount("OnBoot") > before.eventCount("OnFirstBoot") + before.eventCount("OnBoot") }
            }
            runtime.setResumed(true)
            runtime.doubleClick(0, 100, 100)
            waitUntil(30_000, "earthquakeRuntimeChoiceDispatchesNativeOnAiTalk/" + fixtureId, "ready Sakura frame offers OnAiTalk choice",
                snapshot = { nativeSnapshot(runtime, host) + " menuReplyStatus=${menuReply.get()?.status} talkReplyStatus=${talkReply.get()?.status}" }) {
                val ready = runtime.state.value as? StageState.Ready
                ready?.frame?.sakura?.choices?.any { choice -> choice.choiceId == "OnAiTalk" } == true
            }
            val menu = runtime.state.value as StageState.Ready
            val script = requireNotNull(menuReply.get()?.value)
            assertTrue("Earthquake menu has no unbracketed balloon command", script.contains("\\b2"))
            val withoutBalloon = ScriptPlayer(script.replace("\\b2", "")).advanceBy(30_000)
            val original = ScriptPlayer(script).advanceBy(30_000)
            assertEquals("\\b2 left a numeral in Mantle's menu", withoutBalloon.sakura.text, original.sakura.text)
            assertEquals("\\b2 left a numeral in Ridge's menu", withoutBalloon.kero.text, original.kero.text)
            val token = requireNotNull(menu.choices.values.flatten()
                .firstOrNull { choice -> choice.label == "Say something" }).token
            val beforeSeconds = host.observationForTest().eventCount("OnSecondChange")
            SystemClock.sleep(2500)
            assertTrue("Earthquake timer stopped while menu choice was open",
                host.observationForTest().eventCount("OnSecondChange") >= beforeSeconds + 2)
            assertTrue("Earthquake authored choice vanished during timers",
                (runtime.state.value as? StageState.Ready)?.choices?.values?.flatten()?.any { it.token == token } == true)
            val previousTalks = host.observationForTest().eventCount("OnAiTalk")
            runtime.choose(token)
            waitUntil(30_000, "earthquakeRuntimeChoiceDispatchesNativeOnAiTalk/" + fixtureId, "OnAiTalk event count increased once",
                snapshot = { nativeSnapshot(runtime, host) + " menuReplyStatus=${menuReply.get()?.status} talkReplyStatus=${talkReply.get()?.status}" }) {
                host.observationForTest().eventCount("OnAiTalk") == previousTalks + 1
            }
            val talk = requireNotNull(talkReply.get())
            assertEquals("Earthquake selected native OnAiTalk", 200, talk.status)
            val nativeText = ScriptPlayer(requireNotNull(talk.value)).advanceBy(30_000).let {
                it.sakura.text + it.kero.text
            }
            assertTrue("Earthquake OnAiTalk produced no substantive native dialogue: ${talk.value}",
                nativeText.count(Char::isLetter) >= 12)
            val nativeSignature = nativeText.take(20)
            waitUntil(30_000, "earthquakeRuntimeChoiceDispatchesNativeOnAiTalk/" + fixtureId, "ready frame contains native reply signature with no remaining choices",
                snapshot = { nativeSnapshot(runtime, host) + " menuReplyStatus=${menuReply.get()?.status} talkReplyStatus=${talkReply.get()?.status}" }) {
                val ready = runtime.state.value as? StageState.Ready ?: return@waitUntil false
                val text = ready.frame.sakura.text + ready.frame.kero.text
                ready.choices.values.flatten().isEmpty() && text.contains(nativeSignature)
            }
            val text = (runtime.state.value as StageState.Ready).frame.let {
                it.sakura.text + it.kero.text
            }
            println("M4_NATIVE_RUNTIME_MENU selected=OnAiTalk eventCount=${previousTalks + 1} " +
                "visibleLetters=${text.count(Char::isLetter)} nativeLetters=${nativeText.count(Char::isLetter)} " +
                "menuB2=${script.windowed(3).count { it == "\\b2" }} matched=$nativeSignature text=$text")
        } finally {
            try {
                runtime.close()
                runtime.close()
                waitUntil(30_000, "earthquakeRuntimeChoiceDispatchesNativeOnAiTalk/" + fixtureId, "runtime finished",
                snapshot = { nativeSnapshot(runtime, host) + " menuReplyStatus=${menuReply.get()?.status} talkReplyStatus=${talkReply.get()?.status}" }) { runtime.state.value is StageState.Finished }
            } finally {
                scope.cancel()
            }
        }
        assertEquals("Native owner after Earthquake", NativeAvailability.Available, host.availability.value)
        assertEquals("Earthquake native lease was not unloaded once", before.unloads + 1,
            host.observationForTest().unloads)
        }
    }

    @Test fun loboRuntimeChoiceDisplaysItsNativeOnAITalkReply() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val master = File(context.filesDir, "ghost/lobo_okuajub/ghost/master")
        assumeTrue("Verified staged LOBO corpus unavailable: $master", File(master, "descript.txt").isFile)
        val host = NativeShioriHost.process
        host.trackEventsForTest(setOf("OnAITalk")).use {
        assertEquals(NativeAvailability.Available, host.availability.value)
        val delegate = HostNativePort(host)
        val menuReply = AtomicReference<ShioriReply?>()
        val talkReply = AtomicReference<ShioriReply?>()
        val recordingPort = object : NativePort by delegate {
            override suspend fun request(lease: NativeLease, event: ShioriEvent): ShioriReply =
                delegate.request(lease, event).also { reply ->
                    when (event.id) {
                        "OnMouseDoubleClick" -> menuReply.set(reply)
                        "OnAITalk" -> talkReply.set(reply)
                    }
                }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = BundledGhostRepository(
            openAsset = { context.assets.open("nanidroid.zip") },
            ghostRoot = File(context.filesDir, "ghost"),
        )
        val runtime = GhostRuntime(
            loadGhost = { repository.load(repository.ensureInstalled(), "en") },
            engineFactory = { BuiltInShiori(it.content) },
            bootState = object : BootStateStore {
                override suspend fun recordActivation(directoryId: String) = false
                override suspend fun consumeOnboarding() = false
            },
            scope = scope,
            installedGhosts = InstalledGhostRepository(File(context.filesDir, "ghost")),
            lastGhostStore = object : LastGhostStore {
                override fun read() = "lobo_okuajub"
                override fun write(directoryId: String) = Unit
            },
            nativeHost = recordingPort,
        )
        try {
            runBlocking { runtime.start("en") }
            waitUntil(30_000, "loboRuntimeChoiceDisplaysItsNativeOnAITalkReply/" + "lobo_okuajub", "active native lease",
                snapshot = { nativeSnapshot(runtime, host) + " menuReplyStatus=${menuReply.get()?.status} talkReplyStatus=${talkReply.get()?.status}" }) { runtime.activeNativeLeaseForTest() != null }
            waitUntil(30_000, "loboRuntimeChoiceDisplaysItsNativeOnAITalkReply/" + "lobo_okuajub", "ready LOBO ghost",
                snapshot = { nativeSnapshot(runtime, host) + " menuReplyStatus=${menuReply.get()?.status} talkReplyStatus=${talkReply.get()?.status}" }) {
                (runtime.state.value as? StageState.Ready)?.ghostName == "LOBO"
            }
            runtime.setResumed(true)
            runtime.doubleClick(0, 100, 100)
            waitUntil(30_000, "loboRuntimeChoiceDisplaysItsNativeOnAITalkReply/" + "lobo_okuajub", "ready frame offers Say Something choice",
                snapshot = { nativeSnapshot(runtime, host) + " menuReplyStatus=${menuReply.get()?.status} talkReplyStatus=${talkReply.get()?.status}" }) {
                (runtime.state.value as? StageState.Ready)?.choices?.values?.flatten()
                    ?.any { it.label == "Say Something" } == true
            }
            val menu = requireNotNull(menuReply.get())
            assertEquals("LOBO authored menu status", 200, menu.status)
            assertTrue("LOBO authored menu script missing", menu.value?.contains("Say Something") == true)
            val token = (runtime.state.value as StageState.Ready).choices.values.flatten()
                .first { it.label == "Say Something" }.token
            val beforeTalks = host.observationForTest().eventCount("OnAITalk")
            runtime.choose(token)
            waitUntil(30_000, "loboRuntimeChoiceDisplaysItsNativeOnAITalkReply/" + "lobo_okuajub", "native OnAITalk reply recorded",
                snapshot = { nativeSnapshot(runtime, host) + " menuReplyStatus=${menuReply.get()?.status} talkReplyStatus=${talkReply.get()?.status}" }) { talkReply.get() != null }
            assertEquals("LOBO native OnAITalk count", beforeTalks + 1,
                host.observationForTest().eventCount("OnAITalk"))
            val talk = requireNotNull(talkReply.get())
            assertEquals("LOBO authored OnAITalk status", 200, talk.status)
            val script = requireNotNull(talk.value)
            val nativeText = ScriptPlayer(script).advanceBy(30_000).let {
                it.sakura.text + it.kero.text
            }
            assertTrue("LOBO native OnAITalk response was empty: $script",
                nativeText.count(Char::isLetter) >= 12)
            val signature = nativeText.take(20)
            waitUntil(30_000, "loboRuntimeChoiceDisplaysItsNativeOnAITalkReply/" + "lobo_okuajub", "ready frame contains native reply signature",
                snapshot = { nativeSnapshot(runtime, host) + " menuReplyStatus=${menuReply.get()?.status} talkReplyStatus=${talkReply.get()?.status}" }) {
                val ready = runtime.state.value as? StageState.Ready ?: return@waitUntil false
                (ready.frame.sakura.text + ready.frame.kero.text).contains(signature)
            }
            val visible = (runtime.state.value as StageState.Ready).frame.let {
                it.sakura.text + it.kero.text
            }
            println("M5_DEEP_A_LOBO_CHOICE status=${talk.status} eventCount=${beforeTalks + 1} " +
                "nativeLetters=${nativeText.count(Char::isLetter)} matched=$signature visible=$visible")
        } finally {
            try {
                runtime.close()
                waitUntil(90_000, "loboRuntimeChoiceDisplaysItsNativeOnAITalkReply/" + "lobo_okuajub", "runtime finished",
                snapshot = { nativeSnapshot(runtime, host) + " menuReplyStatus=${menuReply.get()?.status} talkReplyStatus=${talkReply.get()?.status}" }) { runtime.state.value is StageState.Finished }
            } finally {
                scope.cancel()
            }
        }
        assertEquals("LOBO native owner after one close", NativeAvailability.Available,
            host.availability.value)
        }
    }

    private fun nativeSnapshot(runtime: GhostRuntime, host: NativeShioriHost): String {
        val state = runtime.state.value
        val observation = host.observationForTest()
        // Never include authored text, private reply scripts, or choice labels.
        val stateSummary = when (state) {
            is StageState.Ready -> "Ready(ghost=${state.ghostName}, token=${state.dialogueToken}, " +
                "letters=${state.frame.sakura.text.count(Char::isLetter) + state.frame.kero.text.count(Char::isLetter)}, " +
                "choices=${state.choices.values.sumOf { it.size }}, error=${state.activationError != null})"
            is StageState.Error -> "Error"
            else -> state.toString()
        }
        return "lastStageState=$stateSummary lease=${runtime.activeNativeLeaseForTest()?.generation} " +
            "availability=${host.availability.value} loads=${observation.loads} unloads=${observation.unloads} " +
            "events=${observation.events.takeLast(8)} counts=${observation.eventCounts}"
    }

    private fun waitUntil(timeoutMs: Long, scenario: String, awaited: String,
        snapshot: () -> String, now: () -> Long = SystemClock::elapsedRealtime,
        pause: (Long) -> Unit = SystemClock::sleep, condition: () -> Boolean) {
        val started = now()
        val deadline = started + timeoutMs
        while (now() < deadline) {
            if (condition()) return
            pause(50)
        }
        val elapsed = now() - started
        val lastSnapshot = snapshot() // Exactly once; never re-evaluate an effectful condition.
        throw AssertionError("Timed out scenario=$scenario awaited=$awaited " +
            "elapsedMs=$elapsed deadlineMs=$timeoutMs absoluteDeadlineMs=$deadline $lastSnapshot")
    }

    @org.junit.Before fun verifyTransientTimeoutDiagnostics() {
        val state = kotlinx.coroutines.flow.MutableStateFlow<StageState>(StageState.Loading)
        var clock = 0L
        var conditions = 0
        var snapshots = 0
        val failure = try {
            waitUntil(100, "synthetic-LOBO", "ready LOBO ghost", snapshot = {
                snapshots++
                "lastStageState=${state.value} lease=null availability=Available observedEvents=0"
            }, now = { clock }, pause = {
                clock += it
                state.value = StageState.Finished
            }) {
                conditions++
                (state.value as? StageState.Ready)?.ghostName == "LOBO"
            }
            error("Never-ready state unexpectedly passed")
        } catch (failure: AssertionError) { failure }
        assertEquals(2, conditions)
        assertEquals(1, snapshots)
        assertTrue(failure.message.orEmpty().contains("scenario=synthetic-LOBO"))
        assertTrue(failure.message.orEmpty().contains("awaited=ready LOBO ghost elapsedMs="))
        assertTrue(failure.message.orEmpty().contains("lastStageState=Finished"))
        assertTrue(failure.message.orEmpty().contains("elapsedMs=100 deadlineMs=100"))
        assertFalse(failure.message.orEmpty().contains("Earthquake"))
        println("WAIT_DIAGNOSTICS_SYNTHETIC ${failure.message}")
    }

    private fun probe(kind: NativeKind, fixture: String, library: String, event: ShioriEvent) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "ghost/$fixture/ghost/master")
        assumeTrue("Fixture unavailable: $directory", File(directory, "descript.txt").isFile)
        val host = NativeShioriHost.process
        val loaded = host.load(kind, directory, library)
        assertTrue("$kind load: $loaded", loaded is NativeLoadResult.Loaded)
        val lease = (loaded as NativeLoadResult.Loaded).lease
        try {
            val reply = host.request(lease, event)
            println("REAL_TALK $kind event=${event.id} status=${reply.status} charset=${reply.charset} value=${reply.value?.take(240)}")
            assertEquals("$kind ${event.id}", 200, reply.status)
            assertFalse("$kind nonboot script missing", reply.value.isNullOrBlank())
        } finally {
            assertEquals(NativeUnloadResult.Unloaded, host.unload(lease))
        }
    }
}
