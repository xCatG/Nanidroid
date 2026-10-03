package com.cattailsw.nanidroid.ui

import android.content.pm.ActivityInfo
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.MainActivity
import com.cattailsw.nanidroid.NanidroidApplication
import com.cattailsw.nanidroid.engine.NativeAvailability
import com.cattailsw.nanidroid.engine.NativeShioriHost
import com.cattailsw.nanidroid.engine.NativeReplyObservation
import com.cattailsw.nanidroid.install.NarArchiveReader
import com.cattailsw.nanidroid.runtime.GhostRuntime
import com.cattailsw.nanidroid.runtime.ScriptPlayer
import com.cattailsw.nanidroid.runtime.PlaybackFrame
import com.cattailsw.nanidroid.runtime.StageState
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Authored routes through the resumed Activity, with native ownership checked at teardown. */
@RunWith(AndroidJUnit4::class)
class RealGhostInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun readyPollingWaitsThroughTransientStates() {
        val speaker = com.cattailsw.nanidroid.runtime.SpeakerFrame(0, true, "ready", true)
        val expected = StageState.Ready(PlaybackFrame(speaker, speaker, true), "probe", "sakura", "kero", emptyMap())
        val samples = listOf(StageState.Loading, StageState.Error("transition"), StageState.Finished, expected)
        var reads = 0
        var matches = 0
        waitForReady(2_000, { samples[(reads++).coerceAtMost(samples.lastIndex)] }) {
            matches++
            it == expected
        }
        assertEquals(4, reads)
        assertEquals("Only Ready reaches the predicate", 1, matches)
    }

    @Test fun readyPollingTimeoutReportsLastState() {
        val failure = runCatching { waitForReady(50, { StageState.Loading }) { true } }.exceptionOrNull()
        assertTrue("Non-Ready must time out", failure is AssertionError)
        assertTrue("Timeout must report duration and state", failure?.message?.let {
            "50ms" in it && "Loading" in it
        } == true)
        assertTrue("Keep Compose timeout as the cause", failure?.cause is androidx.compose.ui.test.ComposeTimeoutException)
    }

    private fun waitForReady(timeout: Long, state: () -> StageState, predicate: (StageState.Ready) -> Boolean) {
        var lastState: StageState? = null
        try {
            compose.waitUntil(timeout) {
                val snapshot = state()
                lastState = snapshot
                (snapshot as? StageState.Ready)?.let(predicate) == true
            }
        } catch (timeoutFailure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Ready condition not met within ${timeout}ms; last state=$lastState", timeoutFailure)
        }
    }

    @Test fun fixtureIdentityRejectsTamperingAndAllowsRuntimeSaves() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        if (args.containsKey("fixtureId") || args.containsKey("fixtureSha256")) {
            val id = requireNotNull(args.getString("fixtureId")) { "Exact fixtureId required" }
            val journey = JOURNEYS.single { it.id == id }
            verifyFixtureIdentity(File(context.filesDir, "fixture-identity/$id.nar"),
                File(context.filesDir, "ghost/$id"), journey.hash, args.getString("fixtureSha256"))
        }
        val probe = File(context.cacheDir, "fixture-identity-probe-${System.nanoTime()}").apply { mkdirs() }
        try {
            val archive = File(probe, "original.nar")
            val installed = File(probe, "satori").apply { mkdirs() }
            val payload = mapOf(
                "install.txt" to "type,ghost\ndirectory,probe\n",
                "ghost/master/descript.txt" to "name,Identity probe\nshiori,Nanidroid\n",
                "shell/master/surfaces.txt" to "surface0 {}\n",
            )
            ZipOutputStream(archive.outputStream()).use { zip ->
                payload.forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
                    File(installed, path).apply { parentFile!!.mkdirs(); writeText(content) }
                }
            }
            val hash = fixtureSha256(archive)
            verifyFixtureIdentity(archive, installed, hash, hash)
            File(installed, "ghost/master/satori_savedata.txt").writeText("changed setting")
            verifyFixtureIdentity(archive, installed, hash, hash)
            val addedDictionary = File(installed, "ghost/master/extra-dictionary.txt").apply { writeText("added script") }
            assertTrue("Additional dictionary files must fail fixture identity",
                runCatching { verifyFixtureIdentity(archive, installed, hash, hash) }.isFailure)
            check(addedDictionary.delete())
            val descriptor = File(installed, "ghost/master/descript.txt")
            descriptor.appendText("tampered")
            assertTrue("Installed payload tampering must fail despite a correct hash argument",
                runCatching { verifyFixtureIdentity(archive, installed, hash, hash) }.isFailure)
            descriptor.writeText(payload.getValue("ghost/master/descript.txt"))
            archive.appendBytes(byteArrayOf(1))
            assertTrue("Changed archive bytes must fail despite a correct hash argument",
                runCatching { verifyFixtureIdentity(archive, installed, hash, hash) }.isFailure)
        } finally { probe.deleteRecursively() }
    }

    private fun fixtureSha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var count = input.read(buffer)
        while (count >= 0) {
            digest.update(buffer, 0, count)
            count = input.read(buffer)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyFixtureIdentity(archive: File, installed: File, expected: String, claimed: String?) {
        require(claimed?.equals(expected, true) == true) { "Original archive SHA-256 required" }
        check(archive.isFile) {
            "Stage the original NAR in app-private ${archive.absolutePath}; fixtureSha256 alone is insufficient"
        }
        check(fixtureSha256(archive).equals(expected, true)) { "Original archive bytes do not match pinned SHA-256" }
        val reader = NarArchiveReader()
        val metadata = reader.inspect(archive)
        val pristine = File.createTempFile("fixture-identity-", "", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        check(pristine.delete() && pristine.mkdir())
        try {
            reader.extract(archive, metadata, pristine) {}
            val originalPaths = mutableSetOf<String>()
            pristine.walkTopDown().filter(File::isFile).forEach { original ->
                val path = original.relativeTo(pristine).invariantSeparatorsPath
                val target = File(installed, path)
                check(target.canonicalPath.startsWith(installed.canonicalPath + File.separator) &&
                    target.isFile && fixtureSha256(target) == fixtureSha256(original)) {
                    "Installed original fixture payload differs: $path"
                }
                originalPaths += path
            }
            // Exact new outputs observed on the eight pinned fixtures; never exempt archived files.
            val runtimeOutputs = when (installed.name) {
                "satori" -> setOf("satori_savedata.txt")
                "earthquake_duo", "Snake_Otacon" -> setOf("yaya_variable.cfg")
                "Yes_Man" -> setOf("yaya_variable.cfg.ays")
                "Nanika_Atsume" -> setOf("dict-keeps-savedata.txt")
                "big_red_button", "lobo_okuajub" -> setOf("profile/dict-savedata.txt", "profile/dict-bakdata.txt", "kawari.log")
                "bancho_jet" -> setOf("profile/dict-savedata.txt", "profile/dict-bakdata.txt")
                else -> emptySet()
            }.map { "ghost/master/$it" }.toSet()
            installed.walkTopDown().filter(File::isFile).forEach { target ->
                val path = target.relativeTo(installed).invariantSeparatorsPath
                check(target.canonicalPath.startsWith(installed.canonicalPath + File.separator) &&
                    (path in originalPaths || path in runtimeOutputs)) {
                    "Unexpected installed fixture file: $path"
                }
            }
            check(originalPaths.isNotEmpty()) { "Original fixture payload is empty" }
            println("M5_FIXTURE_IDENTITY archiveSha256=$expected originalFiles=${originalPaths.size} installed=${installed.name}")
        } finally { pristine.deleteRecursively() }
    }

    private data class Journey(val code: String, val id: String, val hash: String,
        val name: String, val choice: String? = null, val event: String? = null)

    @Test fun authoredEightFamilyJourney() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Host fixture arguments absent", listOf("fixtureId", "fixtureSha256", "landscapeChoice")
            .any { args.containsKey(it) })
        val id = requireNotNull(args.getString("fixtureId")) { "Exact fixtureId required" }
        val journey = JOURNEYS.single { it.id == id }
        require(!args.containsKey("landscapeChoice") ||
            args.getString("landscapeChoice") in listOf("true", "false")) { "landscapeChoice must be true or false" }
        val landscapeChoice = args.getString("landscapeChoice") == "true"
        require(!landscapeChoice || id == "earthquake_duo") { "Landscape choice is G02 only" }
        require(args.getString("fixtureSha256")?.equals(journey.hash, true) == true) {
            "Original archive SHA-256 required for ${journey.code}"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as NanidroidApplication
        verifyFixtureIdentity(File(app.filesDir, "fixture-identity/$id.nar"),
            File(app.filesDir, "ghost/$id"), journey.hash, args.getString("fixtureSha256"))
        val master = File(app.filesDir, "ghost/$id/ghost/master")
        check(File(master, "descript.txt").isFile && File(app.filesDir,
            "ghost/$id/shell/master/surfaces.txt").isFile) { "Original fixture not staged: $id" }
        check(app.getSharedPreferences("last_ghost", 0).edit().putString("last_ghost", id).commit())
        val runtime = app.runtime
        val host = NativeShioriHost.process
        check(host.availability.value == NativeAvailability.Available)
        val eventTracking = host.trackEventsForTest(setOf("OnMouseDoubleClick", "OnSecondChange", "OnChoiceSelect", "OnMouseClick", "OnAssignTalkRate", "OnClose", "OnAiTalk", "OnAITalk"))
        val baseline = host.observationForTest()
        val talkReply = AtomicReference<Pair<Long, NativeReplyObservation>>()
        val talkReplyCount = AtomicInteger()
        if (journey.code == "G02" || journey.code == "G06") host.setReplyObserverForTest { observed ->
            if (observed.eventId == "OnAiTalk") {
                talkReply.set(SystemClock.elapsedRealtime() to observed)
                talkReplyCount.incrementAndGet()
            }
        }
        var scenario: ActivityScenario<MainActivity>? = null
        var failure: Throwable? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val activity = requireNotNull(scenario)
            compose.waitUntil(45_000) { (runtime.state.value as? StageState.Ready)
                ?.ghostName?.startsWith(journey.name) == true && runtime.activeNativeLeaseForTest() != null }
            val firstLease = requireNotNull(runtime.activeNativeLeaseForTest())
            var settingExpected: String? = null
            assertEquals(baseline.loads + 1, host.observationForTest().loads)
            assertEquals(null, ready(runtime).activationError)
            compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("sakura"))
                .fetchSemanticsNodes().isNotEmpty() }
            capture(journey, "boot")
            println("M5_JOURNEY ${journey.code} boot name=${ready(runtime).ghostName} " +
                "surfaces=${ready(runtime).frame.sakura.surfaceId},${ready(runtime).frame.kero.surfaceId} " +
                "text=${visible(runtime).take(180)}")
            val beforeTouch = host.observationForTest().eventCount("OnMouseDoubleClick")
            if (id == "satori") doubleTapSatoriBody(activity, runtime)
            else doubleTapBody(activity)
            compose.waitUntil(25_000) { host.observationForTest().eventCount("OnMouseDoubleClick") == beforeTouch + 1 }
            if (id == "big_red_button") {
                waitForReady(20_000, { runtime.state.value }) { state -> state.frame.sakura.surfaceId == 1 }
                assertEquals("", visible(runtime))
            } else {
                waitForReady(25_000, { runtime.state.value }) { state -> visible(state).isNotBlank() }
                if (journey.choice != null) try {
                    waitForReady(25_000, { runtime.state.value }) { state -> labels(state).contains(journey.choice) }
                } catch (timeout: Throwable) {
                    capture(journey, "missing-choice")
                    throw AssertionError("${journey.code} offered ${labels(runtime)}; " +
                        "text=${visible(runtime).take(300)} token=${ready(runtime).dialogueToken} " +
                        "events=${host.observationForTest().events.drop(baseline.events.size)}", timeout)
                }
            }
            capture(journey, "action")
            println("M5_JOURNEY ${journey.code} action token=${ready(runtime).dialogueToken} " +
                "choices=${labels(runtime)} text=${visible(runtime).take(180)}")
            val token = ready(runtime).dialogueToken
            val ticksBefore = host.observationForTest().eventCount("OnSecondChange")
            if (journey.choice != null) compose.waitUntil(12_000) {
                host.observationForTest().eventCount("OnSecondChange") >= ticksBefore + 5
            }
            activity.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitOrientation(activity, Configuration.ORIENTATION_LANDSCAPE)
            assertTrue("Rotation replaced native lease", runtime.activeNativeLeaseForTest() === firstLease)
            if (journey.choice != null) {
                assertEquals("Rotation changed pending choice token", token, ready(runtime).dialogueToken)
                assertTrue("Choice missing after rotation", labels(runtime).contains(journey.choice))
            } else if (id == "big_red_button") assertEquals(1, ready(runtime).frame.sakura.surfaceId)
            if (!landscapeChoice) {
                activity.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                awaitOrientation(activity, Configuration.ORIENTATION_PORTRAIT)
            }
            compose.waitUntil(20_000) {
                var focused = false
                activity.onActivity { focused = it.hasWindowFocus() }
                focused && compose.onAllNodes(hasTestTag("sakura")).fetchSemanticsNodes().isNotEmpty()
            }
            if (journey.choice != null) {
                assertTrue("Choice missing after rotation", labels(runtime).contains(journey.choice))
                val eventsBefore = host.observationForTest().eventCount(requireNotNull(journey.event))
                val ordinalBeforeChoice = host.observationForTest().requestOrdinal
                val offered = ready(runtime).choices.values.flatten().single { it.label == journey.choice }
                compose.waitUntil(20_000) {
                    var focused = false
                    activity.onActivity { focused = it.hasWindowFocus() }
                    focused && compose.onAllNodes(hasTestTag("choice-${offered.token.itemKey}"))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("choice-${offered.token.itemKey}").performScrollTo()
                println("M5_CHOICE_TARGET ${journey.code} label=${offered.label} key=${offered.token.itemKey} " +
                    "tagBounds=${compose.onNodeWithTag("choice-${offered.token.itemKey}").fetchSemanticsNode().boundsInWindow} " +
                    "textNodes=${compose.onAllNodesWithText(offered.label).fetchSemanticsNodes().size}")
                if (landscapeChoice) { printViewport(activity, "G02-choice"); capture(journey, "landscape-choice-action") }
                if (journey.code == "G02") {
                    val frame = ready(runtime).frame
                    assertTrue("Both authored speakers must be visible at the G02 menu",
                        frame.sakura.visible && frame.kero.visible &&
                            listOf("sakura", "kero").all { tag ->
                                compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
                                    .any { it.boundsInWindow.width > 0 && it.boundsInWindow.height > 0 }
                            })
                }
                val frameBeforeChoice = if (journey.code == "G02" || journey.code == "G06") ready(runtime).frame else null
                val artBefore = if (journey.code == "G06") listOf(
                    artPixels(activity, "sakura"), artPixels(activity, "kero")) else emptyList()
                compose.onNodeWithTag("choice-${offered.token.itemKey}").performClick()
                try { compose.waitUntil(25_000) { host.observationForTest().eventCount(requireNotNull(journey.event)) == eventsBefore + 1 } }
                catch (timeout: Throwable) {
                    capture(journey, "missing-reply")
                    throw AssertionError("${journey.code} choice=${offered.label} expected=${journey.event} " +
                        "events=${host.observationForTest().events.drop(baseline.events.size)} " +
                        "text=${visible(runtime)} choices=${labels(runtime)}", timeout)
                }
                if (id == "satori") {
                    val current = awaitChoice(runtime) { it.matches(Regex(".*見切れ：現在 (ON|OFF).*")) }
                    val old = if (current.contains(" ON")) "ON" else "OFF"
                    clickAuthoredChoice(runtime, current)
                    val opposite = if (old == "ON") "見切れOFF" else "見切れON"
                    val toggle = awaitChoice(runtime) { it.contains(opposite) }
                    clickAuthoredChoice(runtime, toggle)
                    waitForReady(25_000, { runtime.state.value }) { state -> visible(state).contains("見切れ") }
                    capture(journey, "setting-reply")
                    doubleTapSatoriBody(activity, runtime, expectedSurface = 0)
                    val menuAgain = awaitChoice(runtime) { it == journey.choice }
                    clickAuthoredChoice(runtime, menuAgain)
                    val changed = awaitChoice(runtime) { it.contains("見切れ：現在 ${if (old == "ON") "OFF" else "ON"}") }
                    settingExpected = if (old == "ON") "OFF" else "ON"
                    println("M5_SETTING S01 before=$current action=$toggle after=$changed")
                } else if (journey.code == "G02" || journey.code == "G06") {
                    compose.waitUntil(10_000) { talkReplyCount.get() == 1 }
                    val (replyAt, observed) = requireNotNull(talkReply.get())
                    assertTrue("Reply ordinal must follow choice", observed.ordinal > baseline.requestOrdinal)
                    assertTrue("Reply ordinal must follow selected choice", observed.ordinal > ordinalBeforeChoice)
                    assertEquals(200, observed.reply.status)
                    val script = requireNotNull(observed.reply.value) { "OnAiTalk had no script" }
                    val poseOnly = Regex("""(?:\\[01]\\s\[\d+])+""").matches(script)
                    if (journey.code == "G02") {
                        val player = ScriptPlayer(script)
                        var longest = "" to ""
                        for (step in 1..200) {
                            val frame = player.advanceBy(50)
                            if (frame.sakura.text.length > longest.first.length) longest = frame.sakura.text to longest.second
                            if (frame.kero.text.length > longest.second.length) longest = longest.first to frame.kero.text
                            if (frame.ended) break
                        }
                        val expected = listOf(longest.first, longest.second).mapIndexedNotNull { speaker, text ->
                            text.takeIf { it.count(Char::isLetter) >= 8 }?.take(8)?.let { speaker to it }
                        }
                        assertTrue("OnAiTalk had no substantive authored speaker text: $script", expected.isNotEmpty())
                        var matched: Pair<Long, PlaybackFrame>? = null
                        waitForReady(15_000, { runtime.state.value }) { state ->
                            val frame = state.frame
                            val frameAt = SystemClock.elapsedRealtime()
                            if (state.dialogueToken != token && frame != frameBeforeChoice &&
                                expected.any { (speaker, prefix) ->
                                    val shown = if (speaker == 0) frame.sakura else frame.kero
                                    val balloon = "balloon-${if (speaker == 0) state.sakuraName else state.keroName}"
                                    shown.balloonVisible && shown.text.count(Char::isLetter) >= 8 &&
                                        prefix in shown.text &&
                                        compose.onAllNodes(hasText(prefix, substring = true) and
                                            hasAnyAncestor(hasTestTag(balloon)), useUnmergedTree = true)
                                            .fetchSemanticsNodes().isNotEmpty()
                                }) matched = frameAt to frame
                            matched != null
                        }
                        val (frameAt, frame) = requireNotNull(matched)
                        println("M5_NATIVE_REPLY G02 ordinal=${observed.ordinal} status=${observed.reply.status} " +
                            "replyAt=$replyAt frameAt=$frameAt expected=$expected " +
                            "sakura=${frame.sakura.text} kero=${frame.kero.text} script=$script")
                        assertEquals("Exactly one returned OnAiTalk", 1, talkReplyCount.get())
                    } else if (poseOnly) {
                        for (pose in Regex("""\\([01])\\s\[(\d+)]""").findAll(script)) {
                            val speaker = pose.groupValues[1].toInt()
                            val surfaceId = pose.groupValues[2].toInt()
                            waitForReady(10_000, { runtime.state.value }) { state ->
                                val shown = state.frame.let { if (speaker == 0) it.sakura else it.kero }
                                shown.visible && shown.surfaceId == surfaceId &&
                                    state.surfaces[surfaceId]?.isFile == true &&
                                    compose.onAllNodes(hasTestTag(if (speaker == 0) "sakura" else "kero"))
                                        .fetchSemanticsNodes().any { it.boundsInWindow.width > 0 && it.boundsInWindow.height > 0 }
                            }
                            assertTrue("Authored pose $surfaceId was already selected", frameBeforeChoice!!.let { (if (speaker == 0) it.sakura else it.kero).surfaceId != surfaceId })
                            val afterArt = artPixels(activity, if (speaker == 0) "sakura" else "kero", artBefore[speaker].first).second
                            assertTrue("Authored pose $surfaceId did not change $speaker art", artBefore[speaker].second.indices.count { artBefore[speaker].second[it] != afterArt[it] } > 100)
                        }
                        capture(journey, "pose-reply")
                    } else {
                        val player = ScriptPlayer(script)
                        var longest = "" to ""
                        for (step in 1..200) {
                            val frame = player.advanceBy(50)
                            if (frame.sakura.text.length > longest.first.length) longest = frame.sakura.text to longest.second
                            if (frame.kero.text.length > longest.second.length) longest = longest.first to frame.kero.text
                            if (frame.ended) break
                        }
                        val speaker = if (longest.first.length >= longest.second.length) 0 else 1
                        val expected = (if (speaker == 0) longest.first else longest.second).take(16)
                        check(expected.isNotBlank()) { "OnAiTalk had no visible text: $script" }
                        waitForReady(10_000, { runtime.state.value }) { state ->
                            val shown = state.frame.let { if (speaker == 0) it.sakura else it.kero }
                            shown.balloonVisible && expected in shown.text &&
                                compose.onAllNodesWithText(expected, substring = true, useUnmergedTree = true)
                                    .fetchSemanticsNodes().isNotEmpty()
                        }
                    }
                    println("M5_NATIVE_REPLY ${journey.code} ordinal=${observed.ordinal} status=${observed.reply.status} " +
                        "value=$script poseOnly=$poseOnly surfaces=${ready(runtime).frame.sakura.surfaceId},${ready(runtime).frame.kero.surfaceId} " +
                        "visible=${visible(runtime)}")
                    assertEquals("Exactly one returned OnAiTalk", 1, talkReplyCount.get())
                } else {
                    try { waitForReady(25_000, { runtime.state.value }) { state -> visible(state).count(Char::isLetter) >= 8 } }
                    catch (timeout: Throwable) {
                        capture(journey, "short-reply")
                        throw AssertionError("${journey.code} selected=${offered.label} " +
                            "sakura=${ready(runtime).frame.sakura.text} kero=${ready(runtime).frame.kero.text} " +
                            "events=${host.observationForTest().events.drop(baseline.events.size)}", timeout)
                    }
                    val speakers = ready(runtime).frame.let { listOf(it.sakura.text, it.kero.text) }
                    val prefixes = speakers.filter { it.count(Char::isLetter) >= 8 }.map { it.take(8) }
                    check(prefixes.isNotEmpty()) { "No substantive authored reply for ${journey.code}" }
                    compose.waitUntil(15_000) { prefixes.any { prefix ->
                        compose.onAllNodesWithText(prefix, substring = true, useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    } }
                }
            }
            capture(journey, "reply")
            if (landscapeChoice) {
                capture(journey, "landscape-choice-reply")
                activity.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                awaitOrientation(activity, Configuration.ORIENTATION_PORTRAIT)
                assertTrue("Landscape choice replaced native lease", runtime.activeNativeLeaseForTest() === firstLease)
            }
            println("M5_JOURNEY ${journey.code} reply token=${ready(runtime).dialogueToken} " +
                "sakura=${ready(runtime).frame.sakura.text.take(160)} kero=${ready(runtime).frame.kero.text.take(160)} events=${host.observationForTest().events.drop(baseline.events.size)}")
            if (id == "earthquake_duo") {
                doubleTapBody(activity)
                clickAuthoredChoice(runtime, awaitChoice(runtime) { it == "Config Menu" })
                waitForReady(20_000, { runtime.state.value }) { state -> labels(state).count { it == "Change it" } >= 2 }
                val old = visible(runtime)
                val changes = ready(runtime).choices.values.flatten().filter { it.label == "Change it" }
                check(changes.size >= 2) { "S02 talk-rate choice absent: ${labels(runtime)}" }
                compose.onNodeWithTag("choice-${changes.first().token.itemKey}").performScrollTo().performClick()
                val target = if (old.contains("every 3 minutes")) "Every 5 minutes" else "Every 3 minutes"
                clickAuthoredChoice(runtime, awaitChoice(runtime) { it == target })
                settingExpected = if (target == "Every 3 minutes") "3 minutes" else "5 minutes"
                val spoken = if (target == "Every 3 minutes") "three" else "five"
                try { waitForReady(25_000, { runtime.state.value }) { state -> visible(state).contains("We will speak every $spoken minutes.") } }
                catch (timeout: Throwable) {
                    throw AssertionError("S02 action=$target old=$old now=${visible(runtime)} " +
                        "choices=${labels(runtime)} events=${host.observationForTest().events.drop(baseline.events.size)}", timeout)
                }
                doubleTapBody(activity)
                clickAuthoredChoice(runtime, awaitChoice(runtime) { it == "Config Menu" })
                waitForReady(25_000, { runtime.state.value }) { state -> visible(state).contains("Talk Rate: - every $settingExpected") }
                println("M5_SETTING S02 before=${old.take(160)} action=$target after=${visible(runtime)}")
            } else if (id == "lobo_okuajub") {
                doubleTapBody(activity)
                awaitChoice(runtime) { it == "1m" }
                val old = visible(runtime)
                val target = if (old.contains("1 minute")) "3m" else "1m"
                val assignedBefore = host.observationForTest().eventCount("OnAssignTalkRate")
                clickAuthoredChoice(runtime, target)
                compose.waitUntil(20_000) { host.observationForTest().eventCount("OnAssignTalkRate") == assignedBefore + 1 }
                settingExpected = if (target == "1m") "1 minute" else "3 minutes"
                doubleTapBody(activity)
                awaitChoice(runtime) { it == "1m" }
                try { waitForReady(25_000, { runtime.state.value }) { state -> visible(state).contains(settingExpected!!) } }
                catch (timeout: Throwable) {
                    throw AssertionError("G03 setting action=$target old=$old now=${visible(runtime)} " +
                        "choices=${labels(runtime)} events=${host.observationForTest().events.drop(baseline.events.size)}", timeout)
                }
                println("M5_SETTING G03 before=${old.take(160)} action=$target after=${visible(runtime)}")
            } else if (id == "Yes_Man") {
                doubleTapBody(activity)
                awaitChoice(runtime) { it == "Config Menu" }
                val beforeNested = host.observationForTest()
                clickAuthoredChoice(runtime, "Config Menu")
                val back = awaitChoice(runtime) { it == "Back to Main Menu" }
                compose.waitUntil(20_000) { compose.onAllNodesWithText(back).fetchSemanticsNodes().isNotEmpty() }
                capture(journey, "nested-back")
                clickAuthoredChoice(runtime, back)
                awaitChoice(runtime) { it == "Nevermind" }
                val last = ready(runtime).choices.values.flatten().last()
                assertEquals("Authored final menu choice", "Nevermind", last.label)
                compose.waitUntil(20_000) { compose.onAllNodesWithText(last.label).fetchSemanticsNodes().isNotEmpty() }
                capture(journey, "last-choice-action")
                compose.onNodeWithTag("choice-${last.token.itemKey}").performScrollTo().performClick()
                compose.waitUntil(20_000) { host.observationForTest().eventCount("OnChoiceSelect") - beforeNested.eventCount("OnChoiceSelect") == 3L }
                val afterNested = host.observationForTest()
                val routed = setOf("OnChoiceSelect", "OnMouseClick", "OnMouseDoubleClick").associateWith { afterNested.eventCount(it) - beforeNested.eventCount(it) }
                assertEquals("Nested choices must not hit characters", mapOf("OnChoiceSelect" to 3L, "OnMouseClick" to 0L, "OnMouseDoubleClick" to 0L), routed)
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Nevermind").fetchSemanticsNodes().isEmpty() }
                capture(journey, "last-choice-reply")
                println("M5_NESTED G08 last=${last.label} routed=$routed text=${visible(runtime).take(160)}")
            }
            assertEquals("Rotation booted a second native session", baseline.loads + 1,
                host.observationForTest().loads)
            switchThroughUi(runtime, "nanidroid", "Nanidroid")
            switchThroughUi(runtime, id, journey.name)
            if (settingExpected != null) {
                if (id == "satori") {
                    doubleTapSatoriBody(activity, runtime, expectedSurface = 0)
                    clickAuthoredChoice(runtime, awaitChoice(runtime) { it == journey.choice })
                    awaitChoice(runtime) { it.contains("見切れ：現在 $settingExpected") }
                } else {
                    doubleTapBody(activity)
                    if (id == "earthquake_duo") clickAuthoredChoice(runtime,
                        awaitChoice(runtime) { it == "Config Menu" })
                    waitForReady(25_000, { runtime.state.value }) { state -> visible(state).contains(settingExpected!!) }
                }
                println("M5_SETTING ${journey.code} reload=$settingExpected text=${visible(runtime).take(180)}")
            }
            capture(journey, "reload")
            println("M5_JOURNEY ${journey.code} reload name=${ready(runtime).ghostName} " +
                "loads=${host.observationForTest().loads} unloads=${host.observationForTest().unloads}")
            val closesBefore = host.observationForTest().eventCount("OnClose")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(90_000) { runtime.state.value is StageState.Finished }
            compose.waitUntil(45_000) { host.availability.value == NativeAvailability.Available &&
                runtime.activeNativeLeaseForTest() == null }
            assertEquals("Ordinary Back must request one native OnClose", closesBefore + 1,
                host.observationForTest().eventCount("OnClose"))
            println("M5_JOURNEY ${journey.code} finished loads=${host.observationForTest().loads} " +
                "unloads=${host.observationForTest().unloads}")
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            if (journey.code == "G02" || journey.code == "G06") host.setReplyObserverForTest(null)
            try { closeScenarioPreservingFailure(runtime, host, scenario, failure, 90_000) } finally { eventTracking.close() }
        }
    }

    private fun closeScenarioPreservingFailure(runtime: GhostRuntime, host: NativeShioriHost,
        scenario: ActivityScenario<MainActivity>?, primary: Throwable?, finishedTimeout: Long) {
        var cleanup: Throwable? = null
        try {
            runtime.close()
            compose.waitUntil(finishedTimeout) { runtime.state.value is StageState.Finished }
            compose.waitUntil(45_000) { host.availability.value == NativeAvailability.Available &&
                runtime.activeNativeLeaseForTest() == null }
        } catch (caught: Throwable) { cleanup = caught }
        try { scenario?.close() } catch (caught: Throwable) {
            if (cleanup == null) cleanup = caught else cleanup.addSuppressed(caught)
        }
        if (cleanup != null) {
            if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
        }
    }

    private fun ready(runtime: GhostRuntime): StageState.Ready {
        val state = runtime.state.value
        return state as? StageState.Ready ?: throw AssertionError("Expected Ready for assertion; state=$state")
    }
    private fun visible(runtime: GhostRuntime) = visible(ready(runtime))
    private fun visible(state: StageState.Ready) = state.frame.let { it.sakura.text + it.kero.text }
    private fun labels(runtime: GhostRuntime) = labels(ready(runtime))
    private fun labels(state: StageState.Ready) = state.choices.values.flatten().map { it.label }

    private fun clickAuthoredChoice(runtime: GhostRuntime, label: String) {
        val choice = ready(runtime).choices.values.flatten().single { it.label == label }
        compose.onNodeWithTag("choice-${choice.token.itemKey}").performScrollTo().performClick()
    }

    private fun switchThroughUi(runtime: GhostRuntime, id: String, name: String) {
        compose.onNodeWithTag("ghosts-action").performClick()
        compose.onNodeWithTag("ghost-$id").performScrollTo().performClick()
        compose.waitUntil(15_000) { (runtime.state.value as? StageState.Ready)?.switchPrompt?.directoryId == id }
        compose.onNodeWithContentDescription("Confirm ghost switch").performClick()
        compose.waitUntil(90_000) { (runtime.state.value as? StageState.Ready)
            ?.ghostName?.startsWith(name) == true &&
                (id == "nanidroid" || runtime.activeNativeLeaseForTest() != null) }
    }

    private fun capture(journey: Journey, phase: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val output = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "m5-${journey.code}-$phase.png")
        try { output.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { screenshot.recycle() }
        println("M5_SCREENSHOT ${journey.code} $phase ${output.absolutePath}")
    }

    private fun artPixels(scenario: ActivityScenario<MainActivity>, tag: String,
        fixed: Rect? = null): Pair<Rect, IntArray> {
        val region = fixed ?: run {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
            val origin = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
            Rect((origin[0] + bounds.left).toInt(), (origin[1] + bounds.top).toInt(),
                (origin[0] + bounds.right).toInt(), (origin[1] + bounds.bottom).toInt())
        }
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            check(region.width() > 0 && region.height() > 0 && region.left >= 0 && region.top >= 0 && region.right <= screenshot.width && region.bottom <= screenshot.height)
            val pixels = IntArray(region.width() * region.height())
            screenshot.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
            return region to pixels
        } finally { screenshot.recycle() }
    }

    private fun printViewport(scenario: ActivityScenario<MainActivity>, phase: String) {
        scenario.onActivity {
            val bars = it.window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
            println("M5_VIEWPORT $phase px=${it.window.decorView.width}x${it.window.decorView.height} " +
                "dp=${it.resources.configuration.screenWidthDp}x${it.resources.configuration.screenHeightDp} " +
                "font=${it.resources.configuration.fontScale} bars=$bars focus=${it.hasWindowFocus()}")
        }
    }

    private fun doubleTapBody(scenario: ActivityScenario<MainActivity>) {
        val bounds = compose.onNodeWithTag("sakura").fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2)
        scenario.onActivity {
            assertTrue("MainActivity must have focus for window touch", it.hasWindowFocus())
            it.window.decorView.getLocationOnScreen(origin)
        }
        val x = origin[0] + bounds.center.x
        val y = origin[1] + bounds.center.y
        val delta = injectTimedDoubleTap(x, y, 70)
        println("M5_AUTHORED_TOUCH screen=($x,$y) downToDown=${delta}ms " +
            "api=${Build.VERSION.SDK_INT} focus=true")
    }

    private fun injectTimedDoubleTap(x: Float, y: Float, gapMs: Long): Long {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun send(action: Int, down: Long, at: Long) {
            val event = MotionEvent.obtain(down, at, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { assertTrue("Touch injection failed", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        val first = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, first, first)
        SystemClock.sleep(40)
        send(MotionEvent.ACTION_UP, first, SystemClock.uptimeMillis())
        SystemClock.sleep(gapMs)
        val second = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, second, second)
        SystemClock.sleep(40)
        send(MotionEvent.ACTION_UP, second, SystemClock.uptimeMillis())
        return second - first
    }

    @Test fun satoriAuthoredInputWithImeAndRotation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Host fixture arguments absent", listOf("fixtureId", "fixtureSha256", "submitMode", "inputName", "landscapeSubmit")
            .any { args.containsKey(it) })
        val id = requireNotNull(args.getString("fixtureId")) { "Exact fixtureId required" }
        require(id == "satori") { "Expected staged 2elf fixture: $id" }
        val archiveHash = requireNotNull(args.getString("fixtureSha256")) { "Original archive SHA-256 required" }
        require(archiveHash.equals(SATORI_ARCHIVE_SHA256, ignoreCase = true)) {
            "Unexpected archive hash: $archiveHash"
        }
        val mode = requireNotNull(args.getString("submitMode")) { "submitMode required" }
        require(mode == "ok" || mode == "done" || mode == "cancel") { "Unexpected submitMode: $mode" }
        require(!args.containsKey("landscapeSubmit") ||
            args.getString("landscapeSubmit") in listOf("true", "false")) { "landscapeSubmit must be true or false" }
        val landscapeSubmit = args.getString("landscapeSubmit") == "true"
        require(!landscapeSubmit || mode == "done") { "Landscape submit requires IME Done" }
        val name = requireNotNull(args.getString("inputName")) { "Unique inputName required" }
        require(name.matches(Regex("[A-Za-z0-9]{4,24}"))) { "Use a one-line ASCII test name" }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as NanidroidApplication
        verifyFixtureIdentity(File(app.filesDir, "fixture-identity/$id.nar"),
            File(app.filesDir, "ghost/$id"), SATORI_ARCHIVE_SHA256, archiveHash)
        val master = File(app.filesDir, "ghost/$id/ghost/master")
        check(File(master, "descript.txt").isFile && File(master, "satori.dll").isFile &&
            File(app.filesDir, "ghost/$id/shell/master/surfaces.txt").isFile) {
            "Exact staged 2elf fixture missing: $id"
        }
        check(app.getSharedPreferences("last_ghost", 0).edit().putString("last_ghost", id).commit())
        val runtime = app.runtime
        val host = NativeShioriHost.process
        check(host.availability.value == NativeAvailability.Available) {
            "Native host unavailable before authored input: ${host.availability.value}"
        }
        val eventTracking = host.trackEventsForTest(setOf("OnFirstBoot", "OnBoot", "OnUserInput", "OnChoiceSelect"))
        val before = host.observationForTest()
        val inputReply = AtomicReference<NativeReplyObservation>()
        val inputReplyCount = AtomicInteger()
        host.setReplyObserverForTest { observed ->
            if (observed.eventId == "OnUserInput") {
                inputReply.set(observed)
                inputReplyCount.incrementAndGet()
            }
        }
        var primary: Throwable? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val activeScenario = requireNotNull(scenario)
                compose.waitUntil(30_000) { (runtime.state.value as? StageState.Ready)?.ghostName == "双子のエルフ" &&
                    runtime.activeNativeLeaseForTest() != null }
                compose.waitUntil(30_000) { (runtime.state.value as? StageState.Ready)?.frame?.sakura?.surfaceId == 103 }
                compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("sakura")).fetchSemanticsNodes().isNotEmpty() }
                val lease = requireNotNull(runtime.activeNativeLeaseForTest())
                val boot = host.observationForTest()
                assertEquals(before.loads + 1, boot.loads)
                assertEquals(1L, boot.eventCount("OnFirstBoot") - before.eventCount("OnFirstBoot") + boot.eventCount("OnBoot") - before.eventCount("OnBoot"))
                doubleTapSatoriBody(activeScenario, runtime)
                val afterTouch = runtime.state.value as StageState.Ready
                println("M5_AFTER_TOUCH token=${afterTouch.dialogueToken} " +
                    "text=${afterTouch.frame.sakura.text} choices=${afterTouch.choices.values.flatten().map { it.label }} " +
                    "events=${host.observationForTest().events.drop(boot.events.size)}")
                val menu = awaitChoice(runtime) { it.contains("情報変更") || it.contains("情報を変える") }
                clickAuthoredChoice(runtime, menu)
                val changeName = awaitChoice(runtime) { it.startsWith("名前　…") }
                clickAuthoredChoice(runtime, changeName)
                compose.waitUntil(10_000) { (runtime.state.value as? StageState.Ready)?.input?.boxId == "ユー名" }
                val token = (runtime.state.value as StageState.Ready).input!!.token
                compose.onNodeWithTag("ghost-input").performClick()
                compose.waitUntil(10_000) { imeShown() }
                compose.onNodeWithTag("ghost-input").performTextInput(name)
                assertDraftExactly(name)
                activeScenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                awaitOrientation(activeScenario, Configuration.ORIENTATION_LANDSCAPE)
                assertEquals(token, (runtime.state.value as StageState.Ready).input?.token)
                assertDraftExactly(name)
                if (!landscapeSubmit) {
                    activeScenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                    awaitOrientation(activeScenario, Configuration.ORIENTATION_PORTRAIT)
                    assertEquals(token, (runtime.state.value as StageState.Ready).input?.token)
                    assertDraftExactly(name)
                } else {
                    compose.waitUntil(15_000) { runCatching {
                        val box = compose.onNodeWithTag("ghost-input").fetchSemanticsNode().boundsInWindow
                        val cancel = compose.onNodeWithContentDescription("Input Cancel").fetchSemanticsNode().boundsInWindow
                        val ok = compose.onNodeWithContentDescription("Input OK").fetchSemanticsNode().boundsInWindow
                        box.width > box.height && box.right <= cancel.left && cancel.right <= ok.left
                    }.getOrDefault(false) }
                    compose.onNodeWithTag("ghost-input").performClick()
                    var displayId = Display.DEFAULT_DISPLAY
                    activeScenario.onActivity { displayId = it.window.decorView.display?.displayId ?: Display.DEFAULT_DISPLAY }
                    compose.waitUntil(15_000) { runCatching {
                        compose.onNodeWithTag("ghost-input").assertIsFocused()
                        imeShown() && imeWindowVisible(displayId)
                    }.getOrDefault(false) }
                    compose.onNodeWithTag("ghost-input").assertIsFocused()
                    assertDraftExactly(name)
                    printViewport(activeScenario, "I01-ime-landscape")
                    capture(JOURNEYS.first(), "landscape-input-action")
                }
                assertTrue("Rotation replaced native lease", runtime.activeNativeLeaseForTest() === lease)
                if (mode == "cancel") {
                    compose.onNodeWithContentDescription("Input Cancel").performClick()
                    compose.waitUntil(10_000) { (runtime.state.value as? StageState.Ready)?.input == null }
                    assertEquals(0L, host.observationForTest().eventCount("OnUserInput") - boot.eventCount("OnUserInput"))
                } else {
                    if (mode == "ok") compose.onNodeWithContentDescription("Input OK").performClick()
                    else compose.onNodeWithTag("ghost-input").performImeAction()
                    compose.waitUntil(20_000) {
                        host.observationForTest().eventCount("OnUserInput") - boot.eventCount("OnUserInput") == 1L
                    }
                    assertEquals(1L, host.observationForTest().eventCount("OnUserInput") - boot.eventCount("OnUserInput"))
                    compose.waitUntil(20_000) { inputReplyCount.get() == 1 }
                    assertEquals("One native OnUserInput reply", 1, inputReplyCount.get())
                    val submittedReply = requireNotNull(inputReply.get())
                    assertEquals("Native OnUserInput references", listOf("ユー名", name), submittedReply.references)
                    assertEquals("Native OnUserInput status", 200, submittedReply.reply.status)
                    println("M5_I01_NATIVE ordinal=${submittedReply.ordinal} " +
                        "references=${submittedReply.references} status=${submittedReply.reply.status} " +
                        "replyCount=${inputReplyCount.get()}")
                    compose.waitUntil(20_000) {
                        (runtime.state.value as? StageState.Ready)?.let { ready ->
                            ready.frame.sakura.text.contains(name) || ready.frame.kero.text.contains(name) ||
                                ready.choices.values.flatten().any { it.label.contains(name) }
                        } == true
                    }
                    val honorific = awaitChoice(runtime) { it == "さん" }
                    val choiceCountBefore = host.observationForTest().eventCount("OnChoiceSelect")
                    clickAuthoredChoice(runtime, honorific)
                    try {
                        compose.waitUntil(20_000) { host.observationForTest().eventCount("OnChoiceSelect") == choiceCountBefore + 1 }
                    } catch (timeout: Throwable) {
                        throw AssertionError("Honorific click did not yield one OnChoiceSelect: " +
                            "events=${host.observationForTest().events.drop(boot.events.size)} " +
                            "state=${(runtime.state.value as? StageState.Ready)?.let { it.dialogueToken to it.frame.sakura.text }}",
                            timeout)
                    }
                    compose.waitUntil(20_000) { (runtime.state.value as? StageState.Ready)?.let { ready ->
                        ready.frame.sakura.text.contains("${name}さん") ||
                            ready.frame.kero.text.contains("${name}さん")
                    } == true }
                    assertEquals("One native OnUserInput after named reply", 1, inputReplyCount.get())
                    assertEquals("One OnUserInput dispatch after named reply", 1L,
                        host.observationForTest().eventCount("OnUserInput") - boot.eventCount("OnUserInput"))
                    if (landscapeSubmit) {
                        capture(JOURNEYS.first(), "landscape-input-reply")
                        activeScenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                        awaitOrientation(activeScenario, Configuration.ORIENTATION_PORTRAIT)
                        assertTrue("Landscape submit replaced native lease", runtime.activeNativeLeaseForTest() === lease)
                    }
                    println("M5_AUTHORED_INPUT mode=$mode token=$token name=$name " +
                        "honorific=$honorific events=${host.observationForTest().events.drop(boot.events.size)}")
                }
                assertEquals(before.loads + 1, host.observationForTest().loads)
                assertEquals(before.unloads, host.observationForTest().unloads)
                runtime.close()
                compose.waitUntil(45_000) { runtime.state.value is StageState.Finished }
                compose.waitUntil(45_000) { host.availability.value == NativeAvailability.Available &&
                    runtime.activeNativeLeaseForTest() == null }
                assertEquals(before.unloads + 1, host.observationForTest().unloads)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            host.setReplyObserverForTest(null)
            try { closeScenarioPreservingFailure(runtime, host, scenario, primary, 45_000) } finally { eventTracking.close() }
        }
    }

    private fun awaitChoice(runtime: GhostRuntime, predicate: (String) -> Boolean): String {
        try {
            compose.waitUntil(20_000) {
                (runtime.state.value as? StageState.Ready)?.choices?.values?.flatten()
                    ?.any { predicate(it.label) } == true
            }
        } catch (timeout: Throwable) {
            val ready = runtime.state.value as? StageState.Ready
            throw AssertionError("Authored choice absent: token=${ready?.dialogueToken} " +
                "text=${ready?.frame?.sakura?.text} " +
                "choices=${ready?.choices?.values?.flatten()?.map { it.label }}", timeout)
        }
        return (runtime.state.value as StageState.Ready).choices.values.flatten()
            .single { predicate(it.label) }.label
    }

    private fun assertDraftExactly(expected: String) {
        val actual = compose.onNodeWithTag("ghost-input").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        assertEquals("Exact authored input draft", expected, actual)
    }

    private fun imeShown(): Boolean {
        val dump = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys input_method"))
            .bufferedReader().use { it.readText() }
        return dump.contains("mInputShown=true")
    }

    private fun imeWindowVisible(displayId: Int): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val service = automation.serviceInfo
        if (service.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            service.flags = service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = service
        }
        return automation.windows.any { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                (Build.VERSION.SDK_INT >= 33 && window.displayId != displayId)) false else {
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                !bounds.isEmpty
            }
        }
    }

    private fun awaitOrientation(scenario: ActivityScenario<MainActivity>, wanted: Int) {
        compose.waitUntil(15_000) {
            var actual = 0
            scenario.onActivity { actual = it.resources.configuration.orientation }
            actual == wanted
        }
    }

    private fun doubleTapSatoriBody(scenario: ActivityScenario<MainActivity>, runtime: GhostRuntime,
        expectedSurface: Int? = null) {
        val ready = runtime.state.value as StageState.Ready
        val surfaceId = ready.frame.sakura.surfaceId
        if (expectedSurface != null) assertEquals(expectedSurface, surfaceId)
        val surface = requireNotNull(ready.surfaces[surfaceId])
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(surface.absolutePath, dimensions)
        assertTrue(dimensions.outWidth > 204 && dimensions.outHeight > 200)
        val definition = requireNotNull(ready.shell?.definitions?.definition(surfaceId))
        assertTrue("Authored point hits a collision on surface $surfaceId",
            definition.collisions.none { 204 in it.left..it.right && 200 in it.top..it.bottom })
        val bounds = compose.onNodeWithTag("sakura").fetchSemanticsNode().boundsInWindow
        val windowOrigin = IntArray(2)
        scenario.onActivity {
            assertTrue("MainActivity must have focus for window touch", it.hasWindowFocus())
            it.window.decorView.getLocationOnScreen(windowOrigin)
        }
        val x = windowOrigin[0] + bounds.left + 204f * bounds.width / dimensions.outWidth
        val y = windowOrigin[1] + bounds.top + 200f * bounds.height / dimensions.outHeight
        val delta = injectTimedDoubleTap(x, y, 120)
        println("M5_AUTHORED_TOUCH screen=($x,$y) authored=(204,200) " +
            "surface=$expectedSurface downToDown=${delta}ms api=${Build.VERSION.SDK_INT} focus=true")
    }

    private companion object {
        const val SATORI_ARCHIVE_SHA256 = "a50830e18def75be051a3638c7375c7e2d96cb18f7b3f26d0037d84a0fc20be0"
        val JOURNEYS = listOf(
            Journey("G01", "satori", SATORI_ARCHIVE_SHA256, "双子のエルフ", " 見切れ・重なり・消滅表示", "OnChoiceSelect"),
            Journey("G02", "earthquake_duo", "06db71e7e8293b4af0b5127dd73402d4ed90fecc5fdcebf4f0d34337ccb66538", "Earthquake Duo!", "Say something", "OnAiTalk"),
            Journey("G03", "lobo_okuajub", "f4e90615cf40801d4a7a7170762b6c0d6dddf18324f9ba146f4a700cbe2bebf7", "LOBO", "Say Something", "OnAITalk"),
            Journey("G04", "big_red_button", "36ad0500958d88175d9e2530f4aa6e085a2d8579bbb200c1e2d2f9ac0785d21d", "Big Red Button"),
            Journey("G05", "Nanika_Atsume", "9b5ffc161abc489bce332702a1945f3f7d5ec6d66def3b521299ff36d91f290c", "Nanika Atsume", "Talk", "OnChoiceSelect"),
            Journey("G06", "Snake_Otacon", "1c62ce50ca0daca3a9e14e6d870b02d4df9511dd5b586a7f4da49b402d56cbd5", "Snake and Otacon", "Talk to me.", "OnAiTalk"),
            Journey("G07", "bancho_jet", "8a3f1dcaa4c34a625bf16c0a0ada2e3dff2d49fc029e014807aafb164f196dca", "Watchdog Banch", "Say Something", "OnAITalk"),
            Journey("G08", "Yes_Man", "aa6383f564fc2d89cbbc926cd672f481d2e8aafa48ec235b07ba0cbdf77912e8", "Yes Man", "Say Something", "OnAiTalk"),
        )
    }
}
