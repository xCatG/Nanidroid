package com.cattailsw.nanidroid.corpus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.Setup
import com.cattailsw.nanidroid.SurfaceHitTarget
import com.cattailsw.nanidroid.SurfaceManager
import com.cattailsw.nanidroid.ShellSurface
import com.cattailsw.nanidroid.DescReader
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.ShioriFactory
import com.cattailsw.nanidroid.ShioriResponse
import com.cattailsw.nanidroid.SurfaceReader
import com.cattailsw.nanidroid.SurfaceTransparencyPolicy
import com.cattailsw.nanidroid.compose.ComposeGhostStageHost
import com.cattailsw.nanidroid.compose.SurfaceInteractionPort
import com.cattailsw.nanidroid.install.ArchiveInstallResult
import com.cattailsw.nanidroid.install.NarInstallError
import com.cattailsw.nanidroid.install.NarInstallPlanValidator
import com.cattailsw.nanidroid.install.NarTransactionalInstaller
import com.cattailsw.nanidroid.compose.currentStageInputSnapshot
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionProtocol
import com.cattailsw.nanidroid.runtime.stage.StageInputRouter
import com.cattailsw.nanidroid.runtime.stage.StageInputTarget
import com.cattailsw.nanidroid.runtime.stage.StageLayoutDp
import com.cattailsw.nanidroid.runtime.stage.StageLayoutPx
import com.cattailsw.nanidroid.runtime.stage.StageDpRect
import com.cattailsw.nanidroid.runtime.stage.CollisionRegionPx
import com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx
import com.cattailsw.nanidroid.compose.stage.StageMeasuredSnapshot
import com.cattailsw.nanidroid.compose.stage.StageSurfaceSnapshot
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.ShioriMethod
import com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptTokenizer
import com.cattailsw.nanidroid.shiori.NotSupportedShiori
import com.cattailsw.nanidroid.shiori.Shiori
import com.cattailsw.nanidroid.surface.CollisionShape
import com.cattailsw.nanidroid.surface.ParsedSurfaceEntry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.floor
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class NarCorpusRuntimeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val probeContent = NarCorpusProbeContent()

    @Test
    fun snakeBootLifecycleDoesNotFallbackWhenOnFirstBootReturnsContent() {
        val requests = mutableListOf<Pair<String, List<String>>>()

        snakeBootLifecycleSequence("Solid Shell") { eventId, references ->
            requests += eventId to references
            JSONObject()
                .put("eventId", eventId)
                .put("status", 200)
                .put("outcome", "success")
                .put("value", "playable")
                .put("choiceIds", JSONArray().put("faq"))
        }

        assertEquals(
            listOf(
                "OnFirstBoot" to listOf("0"),
                "OnChoiceSelectEx" to listOf("he/him", "choicefirsthehim"),
                "OnNameTeach" to listOf("Nanidroid", ""),
                "OnChoiceSelectEx" to listOf("faq", "faq"),
            ),
            requests,
        )
    }

    @Test
    fun snakeBootLifecycleFallsBackToOnBootOnlyAfterOnFirstBootReturns204() {
        val requests = mutableListOf<String>()

        snakeBootLifecycleSequence("Solid Shell") { eventId, _ ->
            requests += eventId
            JSONObject()
                .put("eventId", eventId)
                .put("status", if (eventId == "OnFirstBoot") 204 else 200)
                .put("outcome", "success")
        }

        assertEquals(listOf("OnFirstBoot", "OnBoot"), requests)
    }

    @Test
    fun snakeBootLifecycleDoesNotProbeFaqWhenInputDoesNotExposeFaqChoice() {
        val requests = mutableListOf<String>()

        snakeBootLifecycleSequence("Solid Shell") { eventId, _ ->
            requests += eventId
            JSONObject()
                .put("eventId", eventId)
                .put("status", 200)
                .put("outcome", "success")
                .put("value", "playable")
                .put("choiceIds", JSONArray())
        }

        assertEquals(listOf("OnFirstBoot", "OnChoiceSelectEx", "OnNameTeach"), requests)
    }

    @Test
    fun parsedChoiceIdsExcludeChoicesClearedFromVisibleDialogue() {
        val evidence = parseShioriSegments("\\q[faq,faq]\\cAfter clear", mutableListOf())

        assertEquals(0, evidence.getJSONArray("choiceIds").length())
    }

    @Test
    fun parsedChoiceIdsPreserveChoicesAcrossSpeakerChanges() {
        val evidence = parseShioriSegments("\\h\\q[faq,faq]\\uReply\\hAgain", mutableListOf())

        assertEquals("faq", evidence.getJSONArray("choiceIds").getString(0))
    }

    @Test
    fun parsedPassiveTransitionsSurviveDialogueClears() {
        val evidence = parseShioriSegments("\\![enter,passivemode]\\cAfter clear", mutableListOf())

        assertTrue(evidence.getJSONArray("passiveTransitions").getBoolean(0))
    }

    @Test
    fun snakeBootLifecycleRetainsFailedPrimaryAndFallbackChoiceEvidence() {
        val requests = mutableListOf<Pair<String, List<String>>>()

        val sequence = snakeBootLifecycleSequence("Solid Shell") { eventId, references ->
            requests += eventId to references
            val primaryIsUnplayable = eventId == "OnChoiceSelectEx" && references[1] == "choicefirsthehim"
            JSONObject()
                .put("eventId", eventId)
                .put("status", if (primaryIsUnplayable) 204 else 200)
                .put("outcome", "success")
                .put("value", if (primaryIsUnplayable) "" else "playable")
                .put("choiceIds", JSONArray().put("faq"))
        }

        assertEquals(
            listOf(
                "OnFirstBoot" to listOf("0"),
                "OnChoiceSelectEx" to listOf("he/him", "choicefirsthehim"),
                "OnChoiceSelect" to listOf("choicefirsthehim"),
                "OnNameTeach" to listOf("Nanidroid", ""),
                "OnChoiceSelectEx" to listOf("faq", "faq"),
            ),
            requests,
        )
        assertEquals(
            listOf("OnFirstBoot", "OnChoiceSelectEx", "OnChoiceSelect", "OnNameTeach", "OnChoiceSelectEx"),
            (0 until sequence.length()).map { sequence.getJSONObject(it).getString("eventId") },
        )
        assertEquals(204, sequence.getJSONObject(1).getInt("status"))
        assertEquals("", sequence.getJSONObject(1).getString("value"))
        assertEquals(
            listOf("he/him", "choicefirsthehim"),
            sequence.getJSONObject(1).getJSONArray("references").let { references ->
                (0 until references.length()).map(references::getString)
            },
        )
        assertEquals(200, sequence.getJSONObject(2).getInt("status"))
        assertEquals("playable", sequence.getJSONObject(2).getString("value"))
        assertEquals(
            listOf("choicefirsthehim"),
            sequence.getJSONObject(2).getJSONArray("references").let { references ->
                (0 until references.length()).map(references::getString)
            },
        )
    }

    @Test
    fun snakeBootLifecycleFallsBackWhenPrimaryOnlyHasLowercaseValueHeader() {
        val requests = mutableListOf<String>()

        snakeBootLifecycleSequence("Solid Shell") { eventId, _ ->
            requests += eventId
            JSONObject()
                .put("eventId", eventId)
                .put("status", 200)
                .put("outcome", "success")
                .put("value", "playable")
                .put(
                    "hasExactValue",
                    eventId != "OnChoiceSelectEx",
                )
                .put("choiceIds", JSONArray())
        }

        assertEquals(
            listOf("OnFirstBoot", "OnChoiceSelectEx", "OnChoiceSelect", "OnNameTeach"),
            requests,
        )
    }

    @Test
    fun snakeBootLifecycleStopsBeforeFaqWhenInputOnlyHasLowercaseValueHeader() {
        val requests = mutableListOf<String>()

        snakeBootLifecycleSequence("Solid Shell") { eventId, _ ->
            requests += eventId
            JSONObject()
                .put("eventId", eventId)
                .put("status", 200)
                .put("outcome", "success")
                .put("value", "playable")
                .put(
                    "hasExactValue",
                    eventId != SNAKE_NAME_TEACH_ID,
                )
                .put(
                    "choiceIds",
                    JSONArray().put(SNAKE_FAQ_ID),
                )
        }

        assertEquals(
            listOf("OnFirstBoot", "OnChoiceSelectEx", "OnNameTeach"),
            requests,
        )
    }

    @Test
    fun structuredChoiceEvidenceUsesTheAuthoredChoiceIdentifier() {
        assertEquals("choicefirsthehim", postInteractionIdentifier("OnChoiceSelectEx", listOf("he/him", "choicefirsthehim")))
        assertEquals("choicefirsthehim", postInteractionIdentifier("OnChoiceSelect", listOf("choicefirsthehim")))
    }

    @Test
    fun namedCollisionProbeDoesNotCountAnOverlappingWrongTargetAsDirect() {
        assertEquals(
            0,
            successfulProbeCountAfterRouting(
                currentCount = 0,
                intendedSpeaker = SurfaceSpeaker.SAKURA,
                routedSpeaker = SurfaceSpeaker.SAKURA,
                intendedId = 7,
                intendedIdentifier = "Hand",
                routedId = 8,
                routedIdentifier = "Hand",
            ),
        )
    }

    @Test
    fun namedCollisionProbeDoesNotAcceptMatchingCollisionFromOtherSpeaker() {
        assertEquals(
            0,
            successfulProbeCountAfterRouting(
                currentCount = 0,
                intendedSpeaker = SurfaceSpeaker.SAKURA,
                routedSpeaker = SurfaceSpeaker.KERO,
                intendedId = 3,
                intendedIdentifier = "Ear",
                routedId = 3,
                routedIdentifier = "Ear",
            ),
        )
    }

    @Test
    fun namedCollisionProbePrefersLaterPointThatRoutesToIntendedCollision() {
        val region = CollisionRegionPx(
            rects = listOf(com.cattailsw.nanidroid.runtime.stage.DoubleRect(0.0, 0.0, 3.0, 1.0)),
            boundarySegments = emptyList(),
        )

        val selected = region.preferredIntegerStageRoutingCandidate(
            resolve = { point -> if (point.x == 2) "Ear" else "Head" },
            isDirectHit = { it == "Ear" },
        )

        assertNotNull(selected)
        assertEquals(androidx.compose.ui.unit.IntOffset(2, 0), selected?.stagePoint)
        assertEquals("Ear", selected?.routing)
        assertTrue(selected?.directHit == true)
    }

    @Test
    fun namedCollisionProbeReportsFirstCandidateWhenShapeIsFullyOccluded() {
        val region = CollisionRegionPx(
            rects = listOf(com.cattailsw.nanidroid.runtime.stage.DoubleRect(4.0, 5.0, 6.0, 6.0)),
            boundarySegments = emptyList(),
        )

        val selected = region.preferredIntegerStageRoutingCandidate(
            resolve = { "Head" },
            isDirectHit = { it == "Ear" },
        )

        assertNotNull(selected)
        assertEquals(androidx.compose.ui.unit.IntOffset(4, 5), selected?.stagePoint)
        assertEquals("Head", selected?.routing)
        assertFalse(selected?.directHit == true)
        assertEquals("fully-occluded", collisionProbeResolutionOutcome(selected?.directHit == true))
    }

    @Test
    fun probesArchive() {
        phase("start")
        composeRule.setContent { probeContent.Content() }
        val args = InstrumentationRegistry.getArguments()
        val keys = listOf(ARG_PATH, ARG_SHA256, ARG_LABEL_BASE64, ARG_LABEL)
        val hasAnyCorpusArgument = keys.any { !args.getString(it).isNullOrBlank() }
        val isExplicitInvocation = !args.getString("class").isNullOrBlank()
        if (!hasAnyCorpusArgument && isExplicitInvocation) {
            requiredArgument(args, ARG_PATH)
        }
        assumeTrue(
            "Corpus probe is opt-in; no corpus arguments were supplied.",
            hasAnyCorpusArgument,
        )

        val path = requiredArgument(args, ARG_PATH)
        val expectedSha256 = requiredArgument(args, ARG_SHA256).lowercase(Locale.ROOT)
        val label = requiredLabelArgument(args)
        val source = File(path)
        validatePath(path, source)
        validateSha256(expectedSha256)
        assertEquals(
            "Source archive SHA mismatch before any mutation for label=$label",
            expectedSha256,
            sha256(source),
        )
        val sourceParent = requireNotNull(source.parentFile) {
            "Source archive path has no parent directory: $path"
        }
        assertTrue("Source parent is not readable/writable: $sourceParent", sourceParent.isDirectory && sourceParent.canRead() && sourceParent.canWrite())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val safeLabel = sanitizeLabel(label)
        assertProbeArchiveLocation(source, context, safeLabel)
        val sourceBytes = source.length()
        val inputRoot = createOwnedRoot(sourceParent, "probe-input")
        val installRoot = createOwnedRoot(sourceParent, "probe-install")
        val copiedArchive = File(inputRoot, ARCHIVE_FILE_NAME)
        val result = baseResult(label, path, expectedSha256, sourceBytes)
        var shioriGhost: TestShioriGhost? = null
        var host: ComposeGhostStageHost? = null
        var failure: Throwable? = null

        try {
            copyArchive(source, copiedArchive)
            phase("archive-copied")
            assertEquals(
                "Copied archive SHA mismatch for label=$label",
                expectedSha256,
                sha256(copiedArchive),
            )

            val observedKind = observePackageKind(copiedArchive)
            result.put("observedKind", observedKind)
            val plan = NarInstallPlanValidator().validate(
                copiedArchive,
                installRoot,
                "corpus-${expectedSha256.take(16)}",
            )
            phase("install-plan-validated")
            result.put(
                "parserDiagnostics",
                JSONArray().put(
                    JSONObject()
                        .put("observedKind", observedKind)
                        .put("planSuccess", plan.isSuccess())
                        .put("error", plan.error?.name ?: JSONObject.NULL)
                        .put("detail", plan.detail),
                ),
            )

            if (observedKind != "ghost") {
                assertFalse("Non-ghost package unexpectedly produced an install plan", plan.isSuccess())
                assertEquals(
                    "Non-ghost package must be rejected as UNSUPPORTED_TYPE",
                    NarInstallError.UNSUPPORTED_TYPE,
                    plan.error,
                )
                result.put("classification", "unsupported")
                result.put("installOutcome", "unsupported:$observedKind")
                result.put("ghostLoadOutcome", "not-applicable")
                result.put("renderOutcome", "not-applicable")
                result.put("inputOutcome", "not-applicable")
                result.put("shioriOutcome", "not-applicable")
                result.put("surfaceCount", 0)
                result.put("passed", true)
            } else {
                if (!plan.isSuccess() && plan.error == NarInstallError.INVALID_PATH) {
                    result.put("installPlanError", plan.error.name)
                    result.put("installPlanDetail", plan.detail)
                    result.put("installOutcome", "invalid-path")
                    result.put("surfaceCount", 0)
                    result.put("surfaceKeys", JSONArray())
                    result.put(
                        "dialogueProbe",
                        JSONObject()
                            .put("outcome", "not-applicable:install-rejected")
                            .put("module", JSONObject.NULL)
                            .put("status", JSONObject.NULL)
                            .put("value", JSONObject.NULL)
                            .put("observedAnchorId", JSONObject.NULL)
                            .put("observedInputId", JSONObject.NULL)
                            .put("passiveTransitions", JSONArray())
                            .put("method", JSONObject.NULL)
                            .put("eventId", JSONObject.NULL)
                            .put(
                                "references",
                                JSONArray(),
                            )
                            .put("tokenizerDiagnostics", JSONArray())
                            .put("failure", JSONObject.NULL),
                    )
                    result.put(
                        "evidence",
                        JSONObject().put(
                            "productionStage",
                            JSONObject.NULL,
                        ).put("installOutcome", "invalid-path"),
                    )
                    result.put("namedCollisionProbes", JSONArray())
                    result.put("ghostLoadOutcome", "not-applicable:install-rejected")
                    result.put("renderOutcome", "not-applicable:install-rejected")
                    result.put("inputOutcome", "not-applicable:install-rejected")
                    result.put("shioriOutcome", "not-applicable:install-rejected")
                    result.put("classification", "incompatible")
                    result.put("passed", true)
                } else {
                    assertTrue(
                        "Ghost install plan failed: ${plan.error} ${plan.detail}",
                        plan.isSuccess(),
                    )
                    val latestProgress = linkedMapOf<String, Long>()
                    val install = NarTransactionalInstaller.install(
                        copiedArchive,
                        installRoot,
                        "corpus-${expectedSha256.take(16)}",
                        { false },
                        { phase, completed -> latestProgress[phase] = completed },
                    )
                    phase("archive-installed")
                    result.put(
                        "installProgress",
                        JSONArray().also { progress ->
                            latestProgress.forEach { (phase, completed) ->
                                progress.put(
                                    JSONObject()
                                        .put("phase", phase)
                                        .put("completed", completed),
                                )
                            }
                        },
                    )
                    val installed = install as? ArchiveInstallResult.Installed
                        ?: error("Transactional install failed: $install")
                    result.put("installOutcome", "installed")
                    result.put("installedTargetId", installed.targetId ?: JSONObject.NULL)

                    val production = buildStageManager(
                        installedPath = installed.installedPath,
                        ghostKey = installed.targetId ?: "corpus-${expectedSha256.take(16)}",
                    )
                    val manager = production.surfaceManager
                    val reader = production.surfaceReader
                    phase("ghost-created")
                    val exactSakura = manager.getSurface("0")
                    val exactKero = manager.getSurface("10")
                    assertNotNull("Installed ghost lacks exact default Sakura surface 0", exactSakura)
                    host = ComposeGhostStageHost(SurfaceInteractionPort { })
                    host.setSurfaceManager(manager, installed.targetId ?: "corpus-${expectedSha256.take(16)}")
                    composeRule.runOnIdle {
                        probeContent.showStage(host)
                    }
                    phase("stage-shown")
                    composeRule.waitUntil(timeoutMillis = 15_000) {
                        host.latestMeasuredSnapshot?.sakura?.composedSurface?.surfaceKey?.surfaceId == 0
                    }
                    val measured = requireNotNull(host.latestMeasuredSnapshot) {
                        "Production stage never reported any measured snapshot for $label"
                    }
                    phase("stage-measured")
                    val measuredSakura = requireNotNull(measured.sakura) {
                        "Production stage never measured Sakura for $label"
                    }
                    val namedCollisionProbeResults = buildNamedCollisionProbes(
                        measured = measured,
                        ghostKey = installed.targetId ?: "corpus-${expectedSha256.take(16)}",
                        manager = manager,
                        result = result,
                    )
                    phase("collisions-probed")
                    assertEquals(
                        "Sakura default surface must be exactly 0",
                        0,
                        measuredSakura.composedSurface.surfaceKey.surfaceId,
                    )
                    measured.kero?.let { assertEquals(10, it.composedSurface.surfaceKey.surfaceId) }
                    namedCollisionProbeResults.firstSuccessfulSpeaker?.let { speaker ->
                        composeRule.runOnIdle { probeContent.showCollisionOverlay(speaker) }
                        composeRule.waitForIdle()
                    }
                    val screenshot = composeRule.onNodeWithTag("nar-corpus-probe-screenshot-root")
                        .captureToImage()
                        .asAndroidBitmap()
                    FileOutputStream(screenshotFile(context, safeLabel)).use { output ->
                        check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                    screenshot.recycle()
                    phase("screenshot-captured")

                    result.put(
                        "surfaceKeys",
                        JSONArray().also { keysJson ->
                            manager.getSurfaceKeys().sorted().forEach(keysJson::put)
                        },
                    )
                    result.put("surfaceCount", manager.getTotalSurfaceCount())
                    result.put("sakura", surfaceEvidence(exactSakura))
                    result.put("kero", surfaceEvidence(exactKero))
                    val shellName = loadShellName(installed.installedPath)
                    val inputOutcome = if (namedCollisionProbeResults.successfulProbeCount > 0) {
                        "named-collisions-routed:${namedCollisionProbeResults.successfulProbeCount}"
                    } else {
                        "no-named-collisions"
                    }
                    result.put(
                        "evidence",
                        JSONObject()
                            .put(
                                "productionStage",
                                measuredStageEvidence(
                                    measured = measured,
                                    exactSakura = requireNotNull(exactSakura),
                                    exactKero = exactKero,
                                ),
                            )
                            .put("productionSurfaceReader", surfaceReaderEvidence(reader, manager))
                            .put("sourceSyntax", sourceSyntaxEvidence(installed.installedPath)),
                    )
                    result.put("ghostLoadOutcome", "surface-loaded")
                    result.put("renderOutcome", "production-stage-rendered")
                    result.put("inputOutcome", inputOutcome)
                    result.put("shioriOutcome", "pending-real-shiori")
                    result.put(
                        "dialogueProbe",
                        JSONObject()
                            .put("outcome", "pending-real-shiori")
                            .put("module", JSONObject.NULL)
                            .put("status", JSONObject.NULL)
                            .put("value", JSONObject.NULL)
                            .put("observedAnchorId", JSONObject.NULL)
                            .put("observedInputId", JSONObject.NULL)
                            .put("passiveTransitions", JSONArray())
                            .put("method", ShioriMethod.GET.name)
                            .put("eventId", "OnBoot")
                            .put("references", JSONArray().put(shellName))
                            .put("tokenizerDiagnostics", JSONArray())
                            .put("failure", JSONObject.NULL),
                    )
                    result.put("classification", "incompatible")
                    result.put("passed", true)
                    setCheckpoint(result, "before-real-shiori")
                    writeArtifacts(context, safeLabel, result)
                    phase("before-real-shiori")
                    try {
                        shioriGhost = loadShiori(
                            installed.installedPath,
                            installed.targetId ?: "corpus-${expectedSha256.take(16)}",
                            loadGhostDesc(installed.installedPath),
                            context,
                        )
                        val finalDialogue = probeShioriOnBoot(shioriGhost, shellName, label)
                        phase("shiori-probed")
                        result.put("dialogueProbe", finalDialogue)
                        val shioriOutcome = finalDialogue.optString("outcome", "probe-failure")
                        result.put("shioriOutcome", shioriOutcome)
                        result.put("ghostLoadOutcome", "loaded")
                        result.put("renderOutcome", "production-stage-rendered")
                        result.put("inputOutcome", inputOutcome)
                        result.put("classification", if (shioriOutcome == "success") "compatible" else "partiallyCompatible")
                        result.put("passed", true)
                        setCheckpoint(result, "complete")
                    } catch (error: LinkageError) {
                        result.put("checkpointPhase", "kotlin-load-linkage-error")
                        result.put("classification", "incompatible")
                        result.put("passed", true)
                        result.put("error", error.stackTraceToString())
                        result.put("ghostLoadOutcome", "not-applicable:shiori-load-failure")
                        result.put("renderOutcome", "production-stage-rendered")
                        result.put("inputOutcome", inputOutcome)
                        result.put("shioriOutcome", "shiori-load-failure")
                    } catch (error: Exception) {
                        result.put("checkpointPhase", "kotlin-load-exception")
                        result.put("classification", "incompatible")
                        result.put("passed", true)
                        result.put("error", error.stackTraceToString())
                        result.put("ghostLoadOutcome", "not-applicable:shiori-load-failure")
                        result.put("renderOutcome", "production-stage-rendered")
                        result.put("inputOutcome", inputOutcome)
                        result.put("shioriOutcome", "shiori-load-failure")
                    }
                }
            }
        } catch (error: Throwable) {
            failure = error
            result.put("passed", false)
            result.put("classification", "probe-failure")
            result.put("error", error.stackTraceToString())
        } finally {
            phase("cleanup-started")
            host?.let {
                composeRule.runOnIdle { probeContent.showStage(null) }
                composeRule.waitForIdle()
            }
            runCatching { shioriGhost?.unload() }
                .exceptionOrNull()
                ?.let { result.put("unloadError", it.stackTraceToString()) }
            deleteOwnedRoot(inputRoot, sourceParent)
            deleteOwnedRoot(installRoot, sourceParent)
            val remaining = JSONArray()
            listOf(inputRoot, installRoot)
                .filter(File::exists)
                .forEach { remaining.put(it.absolutePath) }
            result.put(
                "cleanup",
                JSONObject().put("remainingTestOwnedPaths", remaining),
            )
            writeArtifacts(context, safeLabel, result)
            phase("cleanup-finished")
        }

        failure?.let { throw it }
    }

    private fun baseResult(label: String, sourcePath: String, sha256: String, archiveBytes: Long) = JSONObject()
        .put("schemaVersion", 1)
        .put("label", label)
        .put("sha256", sha256)
        .put("archiveBytes", archiveBytes)
        .put(ARG_PATH, sourcePath)
        .put("passed", false)
        .put("classification", "probe-failure")
        .put("installOutcome", "not-run")
        .put("ghostLoadOutcome", "not-run")
        .put("renderOutcome", "not-run")
        .put("inputOutcome", "not-run")
        .put("shioriOutcome", "not-run")
        .put("surfaceCount", 0)
        .put("namedCollisionProbes", JSONArray())
        .put("dialogueProbe", JSONObject())
        .put("checkpointPhase", "not-run")
        .put("evidence", JSONObject())

    private class TestShioriGhost(
        private val path: String,
        private val ghostIdentity: String,
        masterDesc: Map<String, String>,
        context: Context?,
    ) {
        private val shiori: Shiori = ShioriFactory.getInstance().getShiori(path, masterDesc, context)

        fun getShioriModuleName(): String? = shiori.getModuleName()
        fun getGhostIdentity(): String = ghostIdentity
        fun isShioriNotSupported(): Boolean = shiori is NotSupportedShiori

        fun requestRaw(
            method: ShioriMethod,
            eventId: String,
            references: List<String> = emptyList(),
        ): ShioriResponse {
            val request = StringBuilder()
                .append(method.name)
                .append(" SHIORI/3.0\r\nSender: ")
                .append(Setup.NANIDROID)
                .append("\r\n")
                .append("SecurityLevel: local\r\n")
                .append("ID: ").append(eventId).append("\r\n")
                .also { builder ->
                    references.forEachIndexed { index, value ->
                        builder.append("Reference").append(index).append(": ").append(value).append("\r\n")
                    }
                }
                .append("\r\n")
            val responseText = shiori.request(request.toString())
            return ShioriResponse(java.io.BufferedReader(StringReader(responseText)))
        }

        fun unload() = shiori.unloadShiori()
    }

    private fun buildStageManager(installedPath: String, ghostKey: String): ProductionSurfaceRuntime {
        val masterGhost = File("$installedPath/ghost/master/descript.txt")
        val masterShell = File("$installedPath/shell/master")
        DescReader(masterGhost.absolutePath).parse()
        val shellDescriptorPath = File(masterShell, "descript.txt")
        val surfacePolicyDesc = runCatching {
            DescReader(shellDescriptorPath.absolutePath).parse()
        }.getOrDefault(mapOf())
        val manager = SurfaceManager(ghostKey)
        val reader = SurfaceReader(
            manager,
            "${masterShell.absolutePath}${File.separator}",
            File(masterShell, "surfaces.txt").absolutePath,
            SurfaceTransparencyPolicy.fromShellDescriptor(surfacePolicyDesc),
        )
        return ProductionSurfaceRuntime(
            surfaceManager = manager,
            surfaceReader = reader,
        )
    }

    private fun surfaceReaderEvidence(
        reader: SurfaceReader,
        manager: SurfaceManager,
    ): JSONObject {
        val diagnostics = JSONArray()
        reader.diagnostics.forEachIndexed { index, diagnostic ->
            if (index >= MAX_READER_DIAGNOSTICS) return@forEachIndexed
            diagnostics.put(
                JSONObject()
                    .put("file", diagnostic.file)
                    .put("line", diagnostic.line)
                    .put("reason", diagnostic.reason.name)
                    .put("source", diagnostic.source.take(MAX_READER_DIAGNOSTIC_SOURCE_CHARS))
                    .put(
                        "sourceTruncated",
                        diagnostic.source.length > MAX_READER_DIAGNOSTIC_SOURCE_CHARS,
                    ),
            )
        }
        var totalEntriesIncluded = 0
        var totalEntriesTruncated = false
        val parsedSurfaceEntries = JSONArray()
        for (surfaceId in PRODUCTION_SURFACE_READER_SURFACE_IDS) {
            val entries = manager.getParsedSurfaceEntries(surfaceId.toString())
            val surfaceEntries = JSONArray()
            var surfaceEntriesIncluded = 0
            var surfaceTruncated = false
            for (entry in entries) {
                if (surfaceEntriesIncluded >= MAX_READER_SURFACE_ENTRIES_PER_SURFACE) {
                    surfaceTruncated = true
                    break
                }
                if (totalEntriesIncluded >= MAX_READER_SURFACE_ENTRIES_TOTAL) {
                    totalEntriesTruncated = true
                    break
                }
                surfaceEntries.put(collisionProvenance(entry))
                surfaceEntriesIncluded++
                totalEntriesIncluded++
            }
            parsedSurfaceEntries.put(
                JSONObject()
                    .put("surfaceId", surfaceId)
                    .put("entryCount", entries.size)
                    .put("includedEntryCount", surfaceEntriesIncluded)
                    .put(
                        "truncated",
                        surfaceTruncated || totalEntriesTruncated,
                    )
                    .put("entries", surfaceEntries),
            )
            if (totalEntriesTruncated) {
                break
            }
        }
        return JSONObject()
            .put("error", reader.error)
            .put("diagnosticCount", reader.diagnostics.size)
            .put("diagnostics", diagnostics)
            .put(
                "parsedSurfaceEntries",
                JSONObject()
                    .put("surfaceIds", PRODUCTION_SURFACE_READER_SURFACE_IDS.toJsonArray())
                    .put(
                        "maxEntriesPerSurface",
                        MAX_READER_SURFACE_ENTRIES_PER_SURFACE,
                    )
                    .put("maxEntriesTotal", MAX_READER_SURFACE_ENTRIES_TOTAL)
                    .put("totalTruncated", totalEntriesTruncated)
                    .put("surfaces", parsedSurfaceEntries),
            )
    }

    private fun collisionProvenance(entry: ParsedSurfaceEntry?) = entry?.let {
        JSONObject()
            .put("file", it.source.file)
            .put("line", it.source.number)
            .put("collisionSort", it.fileDirectives.collisionSort.name)
            .put("authoredOrder", it.authoredOrder)
            .put(
                "source",
                JSONObject()
                    .put("text", it.source.text.take(MAX_PROVENANCE_SOURCE_CHARS))
                    .put("truncated", it.source.text.length > MAX_PROVENANCE_SOURCE_CHARS),
            )
    }

    private fun CollisionShape.authoredGeometry() = when (this) {
        is CollisionShape.Rectangle ->
            JSONObject()
                .put("kind", "rectangle")
                .put(
                    "bounds",
                    JSONObject().put("left", bounds.left).put("top", bounds.top).put("right", bounds.right)
                        .put("bottom", bounds.bottom).put("width", bounds.width).put("height", bounds.height),
                )
        is CollisionShape.Ellipse ->
            JSONObject()
                .put("kind", "ellipse")
                .put(
                    "bounds",
                    JSONObject().put("left", bounds.left).put("top", bounds.top).put("right", bounds.right)
                        .put("bottom", bounds.bottom).put("width", bounds.width).put("height", bounds.height),
                )
        is CollisionShape.Circle ->
            JSONObject()
                .put("kind", "circle")
                .put("radius", radius)
                .put("center", JSONObject().toIntOffsetJson(center))
                .put(
                    "bounds",
                    JSONObject().put("left", bounds.left).put("top", bounds.top).put("right", bounds.right)
                        .put("bottom", bounds.bottom).put("width", bounds.width).put("height", bounds.height),
                )
        is CollisionShape.Polygon ->
            JSONObject()
                .put("kind", "polygon")
                .put(
                    "points",
                    JSONArray().also { pointsJson ->
                        points.forEach { point ->
                            pointsJson.put(JSONObject().toIntOffsetJson(point))
                        }
                    },
                )
                .put(
                    "bounds",
                    JSONObject().put("left", bounds.left).put("top", bounds.top).put("right", bounds.right)
                        .put("bottom", bounds.bottom).put("width", bounds.width).put("height", bounds.height),
                )
    }

    private data class ProductionSurfaceRuntime(
        val surfaceManager: SurfaceManager,
        val surfaceReader: SurfaceReader,
    )

    private fun loadGhostDesc(installedPath: String): Map<String, String> =
        DescReader("$installedPath/ghost/master/descript.txt").parse()

    private fun loadShellName(installedPath: String): String {
        val shellDesc = runCatching {
            DescReader("$installedPath/shell/master/descript.txt").parse()
        }.getOrNull()
        return shellDesc?.get("name") ?: if (shellDesc == null) "master" else ""
    }

    private fun loadShiori(
        installedPath: String,
        ghostIdentity: String,
        ghostDesc: Map<String, String>,
        context: Context?,
    ): TestShioriGhost = TestShioriGhost("$installedPath/ghost/master/", ghostIdentity, ghostDesc, context)

    private fun setCheckpoint(result: JSONObject, phase: String) {
        result.put("checkpointPhase", phase)
    }

    private fun probeShioriOnBoot(
        shiori: TestShioriGhost,
        shellName: String,
        label: String,
    ): JSONObject {
        phase("shiori-started")
        if (label != SNAKE_AND_OTACON_LABEL) {
            return probeShioriOnBootLegacy(
                shiori = shiori,
                method = ShioriMethod.GET,
                eventId = "OnBoot",
                references = listOf(shellName),
            )
        }

        val sequence = snakeBootLifecycleSequence(shellName) { eventId, references ->
            probeShioriEvent(
                shiori = shiori,
                method = ShioriMethod.GET,
                eventId = eventId,
                references = references,
            )
        }

        var overallOutcome = "success"
        for (index in 0 until sequence.length()) {
            val step = sequence.getJSONObject(index)
            val stepOutcome = step.optString("outcome")
            if (stepOutcome != "success") {
                overallOutcome = stepOutcome
                break
            }
        }

        val firstStep = sequence.getJSONObject(0)
        var firstFailure: Any = JSONObject.NULL
        for (index in 0 until sequence.length()) {
            val step = sequence.getJSONObject(index)
            val stepOutcome = step.optString("outcome")
            if (stepOutcome != "success") {
                firstFailure = step.opt("failure") ?: JSONObject.NULL
                break
            }
        }

        return JSONObject()
            .put("outcome", overallOutcome)
            .put("module", shiori.getShioriModuleName() ?: JSONObject.NULL)
            .put(
                "status",
                firstStep.opt("status") ?: JSONObject.NULL,
            )
            .put(
                "value",
                firstStep.opt("value") ?: JSONObject.NULL,
            )
            .put(
                "valueTruncated",
                firstStep.optBoolean("valueTruncated", false),
            )
            .put(
                "observedAnchorId",
                (firstStep.opt("observedAnchorId") ?: JSONObject.NULL),
            )
            .put(
                "observedInputId",
                (firstStep.opt("observedInputId") ?: JSONObject.NULL),
            )
            .put(
                "passiveTransitions",
                firstStep.optJSONArray("passiveTransitions") ?: JSONArray(),
            )
            .put("method", firstStep.optString("method", ShioriMethod.GET.name))
            .put("eventId", firstStep.optString("eventId", "OnFirstBoot"))
            .put("references", firstStep.optJSONArray("references") ?: JSONArray())
            .put(
                "tokenizerDiagnostics",
                firstStep.optJSONArray("tokenizerDiagnostics") ?: JSONArray(),
            )
            .put("failure", firstFailure)
            .put("sequence", sequence)
            .put(
                "postInteractionEvidence",
                JSONArray().apply {
                    for (index in 0 until sequence.length()) {
                        val entries = sequence.getJSONObject(index).optJSONArray("postInteractionEvidence")
                        if (entries != null) for (entryIndex in 0 until entries.length()) put(entries.get(entryIndex))
                    }
                },
            )
    }

    private fun snakeBootLifecycleSequence(
        shellName: String,
        probe: (eventId: String, references: List<String>) -> JSONObject,
    ): JSONArray {
        val sequence = JSONArray()
        val onFirstBoot = probe("OnFirstBoot", listOf("0"))
        sequence.put(onFirstBoot)

        if (onFirstBoot.optInt("status", -1) == 204) {
            sequence.put(probe("OnBoot", listOf(shellName)))
            return sequence
        }
        if (onFirstBoot.optString("outcome") != "success") {
            return sequence
        }

        fun probeChoice(label: String, id: String): JSONObject {
            val primary = probe("OnChoiceSelectEx", listOf(label, id))
            sequence.put(primary)
            val hasExactValue = primary.optBoolean("hasExactValue", primary.optString("value").isNotEmpty())
            return if (primary.optInt("status", -1) == 200 && hasExactValue) {
                primary
            } else {
                probe("OnChoiceSelect", listOf(id)).also(sequence::put)
            }
        }

        val firstChoice = probeChoice(SNAKE_CHOICE_FIRST_HE_HIM_LABEL, SNAKE_CHOICE_FIRST_HE_HIM_ID)
        if (firstChoice.optString("outcome") == "success") {
            val input = probe(SNAKE_NAME_TEACH_ID, listOf(SNAKE_NAME_TEACH_VALUE, ""))
            sequence.put(input)
            val inputChoiceIds = input.optJSONArray("choiceIds")
            val inputExposesFaq = inputChoiceIds?.let { choiceIds ->
                (0 until choiceIds.length()).any { choiceIds.optString(it) == SNAKE_FAQ_ID }
            } == true
            val inputHasExactValue = input.optBoolean("hasExactValue", input.optString("value").isNotEmpty())
            if (input.optInt("status", -1) == 200 && inputHasExactValue && inputExposesFaq) {
                probeChoice(SNAKE_FAQ_LABEL, SNAKE_FAQ_ID)
            }
        }
        return sequence
    }

    private fun probeShioriOnBootLegacy(
        shiori: TestShioriGhost,
        method: ShioriMethod,
        eventId: String,
        references: List<String>,
    ): JSONObject {
        val diagnostics = mutableListOf<String>()
        val probe = JSONObject()
            .put("outcome", "pending-real-request")
            .put("module", shiori.getShioriModuleName() ?: JSONObject.NULL)
            .put("status", JSONObject.NULL)
            .put("value", JSONObject.NULL)
            .put("observedAnchorId", JSONObject.NULL)
            .put("observedInputId", JSONObject.NULL)
            .put("passiveTransitions", JSONArray())
            .put("method", method.name)
            .put("eventId", eventId)
            .put(
                "references",
                JSONArray().apply {
                    references.forEach(this::put)
                },
            )
            .put("tokenizerDiagnostics", JSONArray())
            .put("failure", JSONObject.NULL)
        return try {
            val response = shiori.requestRaw(method, eventId, references)
            val value = response.getKeyIgnoreCase("Value").orEmpty()
            val segments = SakuraScriptTokenizer.tokenize(value, diagnostics::add)
                .flatMap(DialogueContent::segments)
            val passiveTransitions = JSONArray()
            var observedAnchorId: String? = null
            var observedInputId: String? = null

            for (segment in segments) {
                if (observedAnchorId == null && segment is DialogueSegment.Anchor) {
                    observedAnchorId = when (segment.action) {
                        is AnchorAction.Normal -> segment.action.id
                        is AnchorAction.DirectEvent -> segment.action.eventId
                    }
                }
                if (observedInputId == null && segment is DialogueSegment.InputBox) {
                    observedInputId = when (segment.spec.dispatch) {
                        is InputDispatch.Normal -> segment.spec.dispatch.id
                        is InputDispatch.DirectEvent -> segment.spec.dispatch.eventId
                    }
                }
                if (segment is DialogueSegment.PassiveMode) {
                    passiveTransitions.put(segment.entering)
                }
            }

            if (shiori.isShioriNotSupported()) {
                probe.put("outcome", "not-supported-shiori")
            } else if (response.getStatusCode() !in 200..299) {
                probe.put("outcome", "error-status")
            } else {
                probe.put("outcome", "success")
            }
            probe
                .put("status", response.getStatusCode())
                .put("value", value)
                .put("observedAnchorId", observedAnchorId ?: JSONObject.NULL)
                .put("observedInputId", observedInputId ?: JSONObject.NULL)
                .put("passiveTransitions", passiveTransitions)
                .put("tokenizerDiagnostics", JSONArray().apply { diagnostics.forEach(this::put) })
        } catch (error: LinkageError) {
            probe.put("outcome", "native-linkage-error")
                .put("failure", error.stackTraceToString())
        } catch (error: Exception) {
            probe.put("outcome", "request-exception")
                .put("failure", error.stackTraceToString())
        }
    }

    private fun probeShioriEvent(
        shiori: TestShioriGhost,
        method: ShioriMethod,
        eventId: String,
        references: List<String>,
    ): JSONObject {
        return try {
            val diagnostics = mutableListOf<String>()
            val response = shiori.requestRaw(method, eventId, references)
            val value = response.getKeyIgnoreCase("Value").orEmpty()
            val hasExactValue = !response.getKey("Value").isNullOrEmpty()
            val segmentEvidence = parseShioriSegments(value, diagnostics)

            val probe = JSONObject()
                .put("module", shiori.getShioriModuleName() ?: JSONObject.NULL)
                .put(
                    "postInteractionEvidence",
                    structuredPostInteractionEvidence(shiori, method, eventId, references),
                )
                .put("method", method.name)
                .put("eventId", eventId)
                .put(
                    "references",
                    JSONArray().apply {
                        references.forEach(this::put)
                    },
                )
                .put("status", response.getStatusCode())
                .put("value", value.take(MAX_SHIORI_RESPONSE_VALUE_CHARS))
                .put("hasExactValue", hasExactValue)
                .put("valueTruncated", value.length > MAX_SHIORI_RESPONSE_VALUE_CHARS)
                .put("observedAnchorId", segmentEvidence.opt("observedAnchorId") ?: JSONObject.NULL)
                .put("observedInputId", segmentEvidence.opt("observedInputId") ?: JSONObject.NULL)
                .put("passiveTransitions", segmentEvidence.optJSONArray("passiveTransitions") ?: JSONArray())
                .put(
                    "tokenizerDiagnostics",
                    JSONArray().apply { diagnostics.forEach(this::put) },
                )
                .put(
                    "choiceIds",
                    segmentEvidence.optJSONArray("choiceIds") ?: JSONArray(),
                )
                .put(
                    "anchorIds",
                    segmentEvidence.optJSONArray("anchorIds") ?: JSONArray(),
                )
                .put("inputSpecs", segmentEvidence.optJSONArray("inputSpecs") ?: JSONArray())
                .put("failure", JSONObject.NULL)

            if (shiori.isShioriNotSupported()) {
                probe.put("outcome", "not-supported-shiori")
            } else if (response.getStatusCode() !in 200..299) {
                probe.put("outcome", "error-status")
            } else {
                probe.put("outcome", "success")
            }
            probe
        } catch (error: LinkageError) {
            JSONObject()
                .put("postInteractionEvidence", structuredPostInteractionEvidence(shiori, method, eventId, references))
                .put("method", method.name)
                .put("eventId", eventId)
                .put(
                    "references",
                    JSONArray().apply {
                        references.forEach(this::put)
                    },
                )
                .put("status", JSONObject.NULL)
                .put("value", JSONObject.NULL)
                .put("valueTruncated", false)
                .put("observedAnchorId", JSONObject.NULL)
                .put("observedInputId", JSONObject.NULL)
                .put("passiveTransitions", JSONArray())
                .put("tokenizerDiagnostics", JSONArray())
                .put("choiceIds", JSONArray())
                .put("anchorIds", JSONArray())
                .put("inputSpecs", JSONArray())
                .put("failure", error.stackTraceToString())
                .put("outcome", "native-linkage-error")
        } catch (error: Exception) {
            JSONObject()
                .put("postInteractionEvidence", structuredPostInteractionEvidence(shiori, method, eventId, references))
                .put("method", method.name)
                .put("eventId", eventId)
                .put(
                    "references",
                    JSONArray().apply {
                        references.forEach(this::put)
                    },
                )
                .put("status", JSONObject.NULL)
                .put("value", JSONObject.NULL)
                .put("valueTruncated", false)
                .put("observedAnchorId", JSONObject.NULL)
                .put("observedInputId", JSONObject.NULL)
                .put("passiveTransitions", JSONArray())
                .put("tokenizerDiagnostics", JSONArray())
                .put("choiceIds", JSONArray())
                .put("anchorIds", JSONArray())
                .put("inputSpecs", JSONArray())
                .put("failure", error.stackTraceToString())
                .put("outcome", "request-exception")
        }
    }

    private fun structuredPostInteractionEvidence(
        shiori: TestShioriGhost,
        method: ShioriMethod,
        eventId: String,
        references: List<String>,
    ): JSONArray {
        if (eventId !in setOf("OnChoiceSelect", "OnChoiceSelectEx", SNAKE_NAME_TEACH_ID)) return JSONArray()
        return JSONArray().put(
            JSONObject()
                .put("ghostIdentity", shiori.getGhostIdentity())
                .put("method", method.name)
                .put("eventId", eventId)
                .put("scope", "dialogue")
                .put("coordinates", JSONObject.NULL)
                .put(
                    "identifier",
                    postInteractionIdentifier(eventId, references) ?: JSONObject.NULL,
                )
                .put("button", JSONObject.NULL)
                .put("source", if (eventId == SNAKE_NAME_TEACH_ID) "input" else "choice")
                .put(
                    "references",
                    JSONArray().apply {
                        for (index in 0..6) put(references.getOrNull(index) ?: JSONObject.NULL)
                    },
                ),
        )
    }

    private fun postInteractionIdentifier(eventId: String, references: List<String>): String? =
        when (eventId) {
            SNAKE_NAME_TEACH_ID -> eventId
            "OnChoiceSelectEx" -> references.getOrNull(1)
            else -> references.firstOrNull()
        }

    private fun parseShioriSegments(
        value: String,
        diagnostics: MutableList<String>,
    ): JSONObject {
        val passiveTransitions = JSONArray()
        val choiceIds = linkedSetOf<String>()
        val anchorIds = linkedSetOf<String>()
        val inputSpecs = JSONArray()
        var observedAnchorId: String? = null
        var observedInputId: String? = null

        val dialogue = SakuraScriptTokenizer.tokenize(value, diagnostics::add)
        dialogue.asSequence()
            .flatMap { it.segments.asSequence() }
            .filterIsInstance<DialogueSegment.PassiveMode>()
            .forEach { passiveTransitions.put(it.entering) }
        val visibleSegments = GhostSpeaker.entries.flatMap { speaker ->
            dialogue
                .asSequence()
                .filter { it.speaker == speaker }
                .flatMap { it.segments.asSequence() }
                .fold(mutableListOf<DialogueSegment>()) { visible, segment ->
                    if (segment is DialogueSegment.Clear) {
                        visible.clear()
                    } else if (segment is DialogueSegment.SpeakerChangeClear) {
                        visible.removeAll { it !is DialogueSegment.Choice && it !is DialogueSegment.InputBox }
                    } else {
                        visible += segment
                    }
                    visible
                }
        }
        visibleSegments
            .forEach { segment ->
                when (segment) {
                    is DialogueSegment.Choice -> {
                        when (val action = segment.action) {
                            is DialogueAction.Normal -> choiceIds.add(action.id)
                            is DialogueAction.DirectEvent -> choiceIds.add(action.eventId)
                            is DialogueAction.Script -> {}
                        }
                    }
                    is DialogueSegment.Anchor -> {
                        val anchorId = when (val action = segment.action) {
                            is AnchorAction.Normal -> action.id
                            is AnchorAction.DirectEvent -> action.eventId
                        }
                        if (observedAnchorId == null) {
                            observedAnchorId = anchorId
                        }
                        anchorIds.add(anchorId)
                    }
                    is DialogueSegment.InputBox -> {
                        val dispatch = segment.spec.dispatch
                        if (observedInputId == null) {
                            observedInputId = when (dispatch) {
                                is InputDispatch.Normal -> dispatch.id
                                is InputDispatch.DirectEvent -> dispatch.eventId
                            }
                        }
                        val inputDispatchId = when (dispatch) {
                            is InputDispatch.Normal -> dispatch.id
                            is InputDispatch.DirectEvent -> dispatch.eventId
                        }
                        val inputSpec = JSONObject()
                            .put(
                                "dispatchType",
                                when (dispatch) {
                                    is InputDispatch.Normal -> "Normal"
                                    is InputDispatch.DirectEvent -> "DirectEvent"
                                },
                            )
                            .put(
                                "dispatchId",
                                inputDispatchId,
                            )
                            .put(
                                "dispatchEvent",
                                when (dispatch) {
                                    is InputDispatch.Normal -> JSONObject.NULL
                                    is InputDispatch.DirectEvent -> dispatch.eventId
                                },
                            )
                            .put("timeout", segment.spec.timeoutMillis ?: JSONObject.NULL)
                            .put(
                                "options",
                                JSONArray().apply {
                                    segment.spec.behaviorOptions.forEach { option -> put(option.name) }
                                },
                            )
                        inputSpecs.put(inputSpec)
                    }
                    else -> {}
                }
            }

        return JSONObject()
            .put(
                "observedAnchorId",
                observedAnchorId ?: JSONObject.NULL,
            )
            .put(
                "observedInputId",
                observedInputId ?: JSONObject.NULL,
            )
            .put("passiveTransitions", passiveTransitions)
            .put(
                "choiceIds",
                JSONArray().also { array -> choiceIds.forEach(array::put) },
            )
            .put(
                "anchorIds",
                JSONArray().also { array -> anchorIds.forEach(array::put) },
            )
            .put("inputSpecs", inputSpecs)
    }

    private fun requiredArgument(arguments: Bundle, key: String): String {
        val value = arguments.getString(key)
        assertNotNull("$key is required", value)
        assertFalse("Required instrumentation arg '$key' must be non-empty", value!!.isBlank())
        return value
    }

    private fun requiredLabelArgument(arguments: Bundle): String {
        val labelBase64 = arguments.getString(ARG_LABEL_BASE64)
        if (!labelBase64.isNullOrBlank()) {
            val bytes = try {
                Base64.getDecoder().decode(labelBase64)
            } catch (error: IllegalArgumentException) {
                throw AssertionError("Invalid base64 value for $ARG_LABEL_BASE64", error)
            }
            val decodedLabel = bytes.toString(StandardCharsets.UTF_8)
            assertFalse(
                "Corpus label decoded from $ARG_LABEL_BASE64 must be non-empty",
                decodedLabel.isBlank(),
            )
            return decodedLabel
        }

        return requiredArgument(arguments, ARG_LABEL)
    }

    private fun validatePath(path: String, source: File) {
        assertTrue("Archive file does not exist: $path", source.exists())
        assertTrue("Archive path is not a readable file: $path", source.isFile && source.canRead())
    }

    private fun validateSha256(sha: String) {
        assertTrue("narCorpusSha256 must be 64 lowercase hex chars", Regex("^[0-9a-f]{64}$").matches(sha))
    }

    private fun assertCollisionIntrinsicPointMatchesTransform(
        representative: androidx.compose.ui.unit.IntOffset,
        mapped: androidx.compose.ui.unit.IntOffset,
        actual: androidx.compose.ui.unit.IntOffset,
        shape: CollisionShape,
    ): androidx.compose.ui.unit.IntOffset {
        assertEquals(
            "Named collision routing must use the shared transform's exact inverse",
            mapped,
            actual,
        )
        assertTrue("Mapped intrinsic point must remain inside the intended collision", shape.contains(actual))
        val deltaX = abs(actual.x - representative.x)
        val deltaY = abs(actual.y - representative.y)
        return androidx.compose.ui.unit.IntOffset(deltaX, deltaY)
    }

    private data class IntegerStageRoutingCandidate<T>(
        val stagePoint: androidx.compose.ui.unit.IntOffset,
        val routing: T,
        val directHit: Boolean,
    )

    private fun <T> CollisionRegionPx.preferredIntegerStageRoutingCandidate(
        resolve: (androidx.compose.ui.unit.IntOffset) -> T,
        isDirectHit: (T) -> Boolean,
    ): IntegerStageRoutingCandidate<T>? {
        var firstCandidate: IntegerStageRoutingCandidate<T>? = null
        rects.forEach { rect ->
            val left = ceil(rect.left).toInt()
            val top = ceil(rect.top).toInt()
            val rightExclusive = ceil(rect.right).toInt()
            val bottomExclusive = ceil(rect.bottom).toInt()
            for (y in top until bottomExclusive) {
                for (x in left until rightExclusive) {
                    val stagePoint = androidx.compose.ui.unit.IntOffset(x, y)
                    if (!contains(Offset(x.toFloat(), y.toFloat()))) continue
                    val routing = resolve(stagePoint)
                    val candidate = IntegerStageRoutingCandidate(
                        stagePoint = stagePoint,
                        routing = routing,
                        directHit = isDirectHit(routing),
                    )
                    if (candidate.directHit) return candidate
                    if (firstCandidate == null) firstCandidate = candidate
                }
            }
        }
        return firstCandidate
    }

    private fun assertProbeArchiveLocation(source: File, context: Context, safeLabel: String) {
        val canonicalArchive = source.canonicalFile
        val safeLabelDir = requireNotNull(canonicalArchive.parentFile) {
            "Archive path has no parent directory: ${source.path}"
        }
        val runIdDir = requireNotNull(safeLabelDir.parentFile) {
            "Archive path missing run-id segment: ${source.path}"
        }
        val hostDir = requireNotNull(runIdDir.parentFile) {
            "Archive path missing host root segment: ${source.path}"
        }
        val runId = runIdDir.name

        assertEquals(
            "Archive run-id segment must be non-blank",
            false,
            runId.isBlank(),
        )
        assertEquals(
            "Archive filename must be fixed as nanidroid-corpus.nar",
            ARCHIVE_FILE_NAME,
            canonicalArchive.name,
        )
        assertEquals(
            "Archive safe-label directory does not match sanitized label",
            safeLabel,
            safeLabelDir.name,
        )
        assertEquals(
            "Archive path must be rooted under target-context cache",
            File(context.cacheDir, CORPUS_HOST_DIR).canonicalPath,
            hostDir.canonicalPath,
        )
        assertEquals(
            "Archive path must match <cacheDir>/nar-corpus-host/<runId>/<safeLabel>/$ARCHIVE_FILE_NAME",
            File(File(File(context.cacheDir, CORPUS_HOST_DIR), runId), safeLabel)
                .resolve(ARCHIVE_FILE_NAME)
                .canonicalFile,
            canonicalArchive,
        )
    }

    private fun observePackageKind(archive: File): String = ZipFile(archive).use { zip ->
        val descriptor = zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .filter { INSTALL_ENTRY.matches(it.name.replace('\\', '/')) }
            .minByOrNull { it.name.length }
            ?: error("Archive does not contain install.txt")
        require(descriptor.size in 0..MAX_DESCRIPTOR_BYTES.toLong()) {
            "install.txt exceeds the bounded descriptor limit: ${descriptor.size}"
        }
        val bytes = zip.getInputStream(descriptor).use { it.readNBytes(MAX_DESCRIPTOR_BYTES + 1) }
        require(bytes.size <= MAX_DESCRIPTOR_BYTES) { "install.txt exceeds the bounded descriptor limit" }
        val text = bytes.toString(StandardCharsets.ISO_8859_1)
        TYPE_LINE.find(text)?.groupValues?.get(1)?.trim()?.lowercase(Locale.ROOT)
            ?: error("install.txt does not declare type")
    }

    private fun surfaceEvidence(surface: ShellSurface?): Any = surface?.let {
        JSONObject()
            .put("id", it.surfaceId)
            .put("width", it.origW)
            .put("height", it.origH)
            .put("collisionCount", it.collisionCount)
            .put("animationCount", it.animationCount)
            .put("source", it.selfFilename ?: JSONObject.NULL)
    } ?: JSONObject.NULL

    private fun measuredStageEvidence(
        measured: StageMeasuredSnapshot,
        exactSakura: ShellSurface,
        exactKero: ShellSurface?,
    ) = JSONObject()
        .put(
            "exactDefaultSurfaceIds",
            JSONObject()
                .put("sakura", exactSakura.surfaceId)
                .put("kero", exactKero?.surfaceId ?: JSONObject.NULL),
        )
        .put("layoutDp", measured.layoutDp.toJson())
        .put("layoutPx", measured.layoutPx.toJson())
        .put("sakura", measured.sakura?.toProductionEvidence() ?: JSONObject.NULL)
        .put("kero", measured.kero?.toProductionEvidence() ?: JSONObject.NULL)

    private fun sourceSyntaxEvidence(installedPath: String): JSONObject {
        val installedRoot = try {
            File(installedPath).canonicalFile.toPath()
        } catch (_: IOException) {
            return JSONObject().put("scanError", true)
        } catch (_: SecurityException) {
            return JSONObject().put("scanError", true)
        }

        val sourceLineFileEvidence = JSONArray()
        val surfaceSelectorIds = linkedSetOf<Int>()
        val excludedSurfaceSelectorIds = linkedSetOf<Int>()
        var scannedFileCount = 0
        var scannedByteCount = 0L
        var filesTruncated = false
        var bytesTruncated = false
        var surfaceSelectorIdsTruncated = false
        var hasSurfaceLineComments = false
        var hasCommaSelectors = false
        var hasRangeSelectors = false
        var hasExclusionSelectors = false
        var hasSurfaceAppend = false
        var hasAnchor = false
        var hasPassiveMode = false
        var hasStructuredInputbox = false

        try {
            Files.walk(installedRoot).use { sourceFiles ->
                val iterator = sourceFiles.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (scannedFileCount >= MAX_SOURCE_SYNTAX_FILE_COUNT) {
                        filesTruncated = true
                        break
                    }
                    if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate)) {
                        continue
                    }
                    val fileName = candidate.fileName?.toString() ?: continue
                    if (!isSourceSyntaxCandidate(fileName)) {
                        continue
                    }
                    val canonicalPath = try {
                        candidate.toRealPath()
                    } catch (_: IOException) {
                        candidate
                    }
                    if (!canonicalPath.startsWith(installedRoot)) {
                        continue
                    }
                    val relativePath = try {
                        installedRoot.relativize(canonicalPath).toString()
                    } catch (_: IllegalArgumentException) {
                        canonicalPath.fileName?.toString() ?: canonicalPath.toString()
                    }
                    val remainingBytes = MAX_SOURCE_SYNTAX_TOTAL_BYTES - scannedByteCount
                    if (remainingBytes <= 0L) {
                        bytesTruncated = true
                        break
                    }
                    val perFileBudget = if (MAX_SOURCE_SYNTAX_BYTES_PER_FILE < remainingBytes) {
                        MAX_SOURCE_SYNTAX_BYTES_PER_FILE
                    } else {
                        remainingBytes.toInt()
                    }
                    val read = readBoundedSourceFile(candidate, perFileBudget)
                    if (read.failed) {
                        continue
                    }
                    val fileBytes = if (read.truncated) {
                        bytesTruncated = true
                        read.bytes.copyOfRange(0, perFileBudget)
                    } else {
                        read.bytes
                    }
                    scannedFileCount++
                    scannedByteCount += fileBytes.size.toLong()
                    val asciiSource = sanitizedSourceString(fileBytes)
                    var fileHasSurfaceSelectors = false
                    var fileHasLineComments = false
                    var fileHasCommaSelectors = false
                    var fileHasRangeSelectors = false
                    var fileHasExclusionSelectors = false
                    var fileHasSurfaceAppend = false

                    asciiSource.lineSequence().forEachIndexed { index, rawLine ->
                        hasAnchor = hasAnchor || rawLine.contains("\\_a[")
                        hasPassiveMode = hasPassiveMode ||
                            rawLine.contains("\\![enter,passivemode]") ||
                            rawLine.contains("\\![leave,passivemode]")
                        hasStructuredInputbox = hasStructuredInputbox || rawLine.contains("\\![open,inputbox,")
                        if (rawLine.contains("//")) {
                            fileHasLineComments = true
                            hasSurfaceLineComments = true
                        }

                        val declaration = NarCorpusSourceSyntaxInspector.inspect(
                            file = relativePath,
                            lineNumber = index + 1,
                            rawLine = rawLine,
                        ) ?: return@forEachIndexed
                        fileHasSurfaceSelectors = true
                        fileHasSurfaceAppend = fileHasSurfaceAppend || declaration.isAppend
                        fileHasCommaSelectors = fileHasCommaSelectors || declaration.hasCommaSelectors
                        fileHasRangeSelectors = fileHasRangeSelectors || declaration.hasRangeSelectors
                        fileHasExclusionSelectors = fileHasExclusionSelectors || declaration.hasExclusionSelectors
                        declaration.includedIds.forEach { id ->
                            if (surfaceSelectorIds.size >= MAX_SOURCE_SYNTAX_SELECTOR_IDS) {
                                surfaceSelectorIdsTruncated = true
                                return@forEach
                            }
                            surfaceSelectorIds += id
                        }
                        declaration.excludedIds.forEach { id ->
                            if (excludedSurfaceSelectorIds.size >= MAX_SOURCE_SYNTAX_SELECTOR_IDS) {
                                surfaceSelectorIdsTruncated = true
                                return@forEach
                            }
                            excludedSurfaceSelectorIds += id
                        }
                        hasCommaSelectors = hasCommaSelectors || fileHasCommaSelectors
                        hasRangeSelectors = hasRangeSelectors || fileHasRangeSelectors
                        hasExclusionSelectors = hasExclusionSelectors || fileHasExclusionSelectors
                        hasSurfaceAppend = hasSurfaceAppend || fileHasSurfaceAppend
                    }

                    hasSurfaceLineComments = hasSurfaceLineComments || fileHasLineComments
                    sourceLineFileEvidence.put(
                        JSONObject()
                            .put("path", relativePath)
                            .put("bytesScanned", fileBytes.size)
                            .put("lineComments", fileHasLineComments)
                            .put("surfaceSelectors", fileHasSurfaceSelectors)
                            .put("surfaceAppend", fileHasSurfaceAppend),
                    )
                }
            }
        } catch (_: IOException) {
            return JSONObject().put("scanError", true)
        } catch (_: SecurityException) {
            return JSONObject().put("scanError", true)
        }

        return JSONObject()
            .put("scanLimit", JSONObject()
                .put("maxFiles", MAX_SOURCE_SYNTAX_FILE_COUNT)
                .put("maxBytesPerFile", MAX_SOURCE_SYNTAX_BYTES_PER_FILE)
                .put("maxTotalBytes", MAX_SOURCE_SYNTAX_TOTAL_BYTES))
            .put("scanRoot", installedRoot.toString())
            .put("fileCount", sourceLineFileEvidence.length())
            .put("byteCount", scannedByteCount)
            .put("filesTruncated", filesTruncated)
            .put("bytesTruncated", bytesTruncated)
            .put("surfaceSelectorIdsTruncated", surfaceSelectorIdsTruncated)
            .put("hasSurfaceLineComment", hasSurfaceLineComments)
            .put("hasCommaSelectors", hasCommaSelectors)
            .put("hasRangeSelectors", hasRangeSelectors)
            .put("hasExclusionSelectors", hasExclusionSelectors)
            .put("hasSurfaceAppend", hasSurfaceAppend)
            .put("hasAnchor", hasAnchor)
            .put("hasPassiveMode", hasPassiveMode)
            .put("hasStructuredInputbox", hasStructuredInputbox)
            .put("files", sourceLineFileEvidence)
            .put(
                "surfaceKeys",
                JSONObject()
                    .put("included", surfaceSelectorIds.sorted().toJsonArray())
                    .put(
                        "excluded",
                        excludedSurfaceSelectorIds.sorted().toJsonArray(),
                    ),
            )
    }

    private fun isSourceSyntaxCandidate(fileName: String): Boolean =
        SURFACE_SOURCE_FILE.matches(fileName) || DIC_FILE.matches(fileName)

    private fun readBoundedSourceFile(path: Path, maxBytes: Int): SourceReadResult {
        if (maxBytes <= 0) return SourceReadResult(ByteArray(0), truncated = false, failed = false)
        return try {
            Files.newInputStream(path).use { input ->
                val readBytes = input.readNBytes(maxBytes + 1)
                SourceReadResult(
                    bytes = readBytes.take(minOf(maxBytes, readBytes.size)).toByteArray(),
                    truncated = readBytes.size > maxBytes,
                    failed = false,
                )
            }
        } catch (_: IOException) {
            SourceReadResult(ByteArray(0), truncated = false, failed = true)
        } catch (_: SecurityException) {
            SourceReadResult(ByteArray(0), truncated = false, failed = true)
        }
    }

    private fun sanitizedSourceString(bytes: ByteArray): String = bytes
        .toString(StandardCharsets.ISO_8859_1)
        .replace(Regex("[^\\x20-\\x7E\\r\\n\\t]")) { "?" }

    private fun Collection<Int>.toJsonArray(): JSONArray = JSONArray().also { array ->
        forEach(array::put)
    }

    private fun StageSurfaceSnapshot.toProductionEvidence() = JSONObject()
        .put("speaker", speaker.name)
        .put("surfaceId", composedSurface.surfaceKey.surfaceId)
        .put("canvas", JSONObject()
            .put("width", composedSurface.surfaceKey.canvasSize.width)
            .put("height", composedSurface.surfaceKey.canvasSize.height))
        .put("revision", composedSurface.revision)
        .put("intrinsic", JSONObject().put("width", transform.intrinsicSize.width).put("height", transform.intrinsicSize.height))
        .put("visiblePixelBounds", composedSurface.visiblePixelBounds.toJson())
        .put("opticalBounds", transform.opticalBounds(composedSurface.visiblePixelBounds).toJson())
        .put("renderedBounds", transform.renderedBounds.toJson())
        .put("scale", transform.scale)
        .put("stageToRoot", JSONObject().put("x", transform.stageToRoot.x).put("y", transform.stageToRoot.y))

    private fun SurfaceTransformPx.opticalBounds(visiblePixelBounds: androidx.compose.ui.unit.IntRect?): androidx.compose.ui.unit.IntRect? {
        if (visiblePixelBounds == null || intrinsicSize.width <= 0 || intrinsicSize.height <= 0) {
            return null
        }
        val scaledLeft = renderedBounds.left.toDouble() +
            floor(visiblePixelBounds.left.toDouble() * renderedBounds.width.toDouble() / intrinsicSize.width.toDouble())
        val scaledTop = renderedBounds.top.toDouble() +
            floor(visiblePixelBounds.top.toDouble() * renderedBounds.height.toDouble() / intrinsicSize.height.toDouble())
        val scaledRight = renderedBounds.left.toDouble() +
            ceil(visiblePixelBounds.right.toDouble() * renderedBounds.width.toDouble() / intrinsicSize.width.toDouble())
        val scaledBottom = renderedBounds.top.toDouble() +
            ceil(visiblePixelBounds.bottom.toDouble() * renderedBounds.height.toDouble() / intrinsicSize.height.toDouble())
        return androidx.compose.ui.unit.IntRect(
            scaledLeft.toInt(),
            scaledTop.toInt(),
            scaledRight.toInt(),
            scaledBottom.toInt(),
        )
    }

    private data class SourceReadResult(
        val bytes: ByteArray,
        val truncated: Boolean,
        val failed: Boolean,
    )

    private fun buildNamedCollisionProbes(
        measured: StageMeasuredSnapshot,
        ghostKey: String,
        manager: SurfaceManager,
        result: JSONObject,
    ): NamedCollisionProbeResult {
        val probes = JSONArray()
        val snapshot = currentStageInputSnapshot(
            measured = measured,
            blocking = false,
            ghostKey = ghostKey,
            ghostIdentity = manager,
        )
        var successfulProbeCount = 0
        var firstSuccessfulSpeaker: SurfaceSpeaker? = null

        buildList {
            measured.sakura?.let(this::add)
            measured.kero?.let(this::add)
        }.forEach { surface ->
            val surfaceProvenanceByOrder = manager.getParsedSurfaceEntries(
                surface.composedSurface.surfaceKey.surfaceId.toString(),
            ).associateBy { it.authoredOrder.toInt() }
            val namedCollisions = surface.composedSurface.effectiveCollisions
                .filter { it.identifier.isNotBlank() }

            namedCollisions.forEach { collision ->
                val probe = JSONObject()
                    .put("speaker", surface.speaker.name)
                    .put("surfaceId", surface.composedSurface.surfaceKey.surfaceId)
                    .put("id", collision.id)
                    .put("authoredId", collision.id)
                    .put("authoredIdentifier", collision.identifier)
                    .put("authoredOrder", collision.authoredOrder)
                    .put("shapeKind", collision.shape.shapeKind())
                    .put("authoredGeometry", collision.shape.authoredGeometry())
                    .put(
                        "authoredProvenance",
                        collisionProvenance(surfaceProvenanceByOrder[collision.authoredOrder]),
                    )

                val intrinsicPoint = surface.overlayTransform.representativeIntrinsicPoint(collision.shape)
                    ?: run {
                        probes.put(
                            probe
                                .put("representable", false)
                                .put("representableReason", "representative-point-unavailable"),
                        )
                        return@forEach
                    }
                val overlayRegion = surface.overlayTransform.toStageRegion(collision.shape)
                val routingCandidate = overlayRegion.preferredIntegerStageRoutingCandidate(
                    resolve = { candidate ->
                        StageInputRouter.resolve(
                            snapshot = snapshot,
                            stagePoint = Offset(candidate.x.toFloat(), candidate.y.toFloat()),
                            source = PointerSource.TOUCH,
                            button = 0,
                        )
                    },
                    isDirectHit = { candidateResolution ->
                        val candidateSurface = candidateResolution.target as? StageInputTarget.Surface
                        val candidateCollision = candidateSurface?.hit as? SurfaceHitTarget.Collision
                        candidateCollision != null && isDirectNamedCollisionHit(
                            intendedSpeaker = surface.speaker,
                            routedSpeaker = candidateSurface.speaker,
                            intendedId = collision.id,
                            intendedIdentifier = collision.identifier,
                            routedId = candidateCollision.id,
                            routedIdentifier = candidateCollision.identifier,
                        )
                    },
                )
                    ?: run {
                        probes.put(
                            probe
                                .put("representable", false)
                                .put("representativePoint", JSONObject().put("x", intrinsicPoint.x).put("y", intrinsicPoint.y))
                                .put("representableReason", "integer-stage-point-unavailable"),
                        )
                        return@forEach
                    }
                val stagePoint = routingCandidate.stagePoint
                val stageOffset = Offset(stagePoint.x.toFloat(), stagePoint.y.toFloat())
                val resolution = routingCandidate.routing

                probe
                    .put(
                        "intrinsicPoint",
                        JSONObject().put("x", intrinsicPoint.x).put("y", intrinsicPoint.y),
                    )
                    .put(
                        "renderedPoint",
                        JSONObject().put("x", stagePoint.x).put("y", stagePoint.y),
                    )
                    .put("representable", true)
                    .put("overlayContainsPoint", overlayRegion.contains(stageOffset))
                    .put("overlayExact", overlayRegion.isExact)
                    .put("overlayFallbackReason", overlayRegion.fallbackReason ?: JSONObject.NULL)

                val target = resolution.target
                val surfaceHit = target as? StageInputTarget.Surface
                if (!routingCandidate.directHit) {
                    val routedCollision = (surfaceHit?.hit as? SurfaceHitTarget.Collision)?.let { routed ->
                        JSONObject()
                            .put("routedId", routed.id)
                            .put("routedIdentifier", routed.identifier)
                    } ?: JSONObject.NULL
                    probe
                        .put("resolutionOutcome", collisionProbeResolutionOutcome(directHit = false))
                        .put("intendedCollisionWasDirectlyHit", false)
                        .put("targetSpeaker", surfaceHit?.speaker?.name ?: JSONObject.NULL)
                        .put("surfaceHitType", surfaceHit?.hit?.javaClass?.simpleName ?: JSONObject.NULL)
                        .put("routedCollision", routedCollision)
                        .put("effect", resolution.effect?.let(::interactionEffectEvidence) ?: JSONObject.NULL)
                    probes.put(probe)
                    return@forEach
                }
                if (surfaceHit == null) {
                    probes.put(
                        probe
                            .put("resolutionOutcome", "non-surface-target")
                            .put("intendedCollisionWasDirectlyHit", false)
                            .put("routedCollision", JSONObject.NULL)
                            .put("effect", JSONObject.NULL),
                    )
                    return@forEach
                }

                val collisionTarget = surfaceHit.hit as? SurfaceHitTarget.Collision
                if (collisionTarget == null) {
                    probes.put(
                        probe
                            .put("resolutionOutcome", "non-collision-target")
                            .put("targetSpeaker", surfaceHit.speaker.name)
                            .put("surfaceHitType", surfaceHit.hit::class.java.simpleName)
                            .put("intendedCollisionWasDirectlyHit", false)
                            .put("routedCollision", JSONObject.NULL)
                            .put("effect", JSONObject.NULL),
                    )
                    return@forEach
                }

                val directHit = isDirectNamedCollisionHit(
                    intendedSpeaker = surface.speaker,
                    routedSpeaker = surfaceHit.speaker,
                    intendedId = collision.id,
                    intendedIdentifier = collision.identifier,
                    routedId = collisionTarget.id,
                    routedIdentifier = collisionTarget.identifier,
                )
                successfulProbeCount = successfulProbeCountAfterRouting(
                    currentCount = successfulProbeCount,
                    intendedSpeaker = surface.speaker,
                    routedSpeaker = surfaceHit.speaker,
                    intendedId = collision.id,
                    intendedIdentifier = collision.identifier,
                    routedId = collisionTarget.id,
                    routedIdentifier = collisionTarget.identifier,
                )
                val routedCollision = JSONObject()
                    .put("routedId", collisionTarget.id)
                    .put("routedIdentifier", collisionTarget.identifier)

                probe
                    .put("targetSpeaker", surfaceHit.speaker.name)
                    .put(
                        "resolutionOutcome",
                        collisionProbeResolutionOutcome(directHit),
                    )
                    .put("intendedCollisionWasDirectlyHit", directHit)
                    .put("routedCollision", routedCollision)
                    .put("hitIdentifier", collisionTarget.identifier)

                if (!directHit) {
                    probes.put(
                        probe.put("effect", resolution.effect?.let(::interactionEffectEvidence) ?: JSONObject.NULL),
                    )
                    return@forEach
                }

                val effect = requireNotNull(resolution.effect) {
                    "Named collision ${collision.identifier} on surface ${surface.composedSurface.surfaceKey.surfaceId} produced no interaction effect"
                }
                assertEquals(surface.speaker, surfaceHit.speaker)
                assertEquals(collisionTarget.identifier, effect.collisionIdentifier)
                assertEquals(collisionTarget.id, effect.diagnosticCollisionId)
                assertEquals("TOUCH", effect.source.name)
                assertEquals(0, effect.button)
                val mappedIntrinsic = requireNotNull(surface.overlayTransform.toIntrinsic(stageOffset)) {
                    "Representative stage point unexpectedly fell outside the surface transform"
                }
                val intrinsicDelta = assertCollisionIntrinsicPointMatchesTransform(
                    representative = intrinsicPoint,
                    mapped = mappedIntrinsic,
                    actual = effect.intrinsic,
                    shape = collision.shape,
                )
                probe
                    .put("intrinsicDeltaX", intrinsicDelta.x)
                    .put("intrinsicDeltaY", intrinsicDelta.y)
                    .put(
                        "mappedIntrinsicPoint",
                        JSONObject().put("x", mappedIntrinsic.x).put("y", mappedIntrinsic.y),
                    )
                    .put("intrinsicMappingContract", "exact-shared-transform-inverse")

                assertTrue(
                    "Named collision ${collision.identifier} for surface ${surface.composedSurface.surfaceKey.surfaceId} should be inside exact overlay region",
                    overlayRegion.contains(stageOffset),
                )
                assertTrue(
                    "Named collision ${collision.identifier} for surface ${surface.composedSurface.surfaceKey.surfaceId} should use exact overlay region",
                    overlayRegion.isExact,
                )

                if (firstSuccessfulSpeaker == null) {
                    firstSuccessfulSpeaker = surface.speaker
                }

                probe.put("effect", interactionEffectEvidence(effect))
                probes.put(probe)
            }
        }

        result.put("namedCollisionProbes", probes)
        return NamedCollisionProbeResult(successfulProbeCount, firstSuccessfulSpeaker)
    }

    private fun isDirectNamedCollisionHit(
        intendedSpeaker: SurfaceSpeaker,
        routedSpeaker: SurfaceSpeaker,
        intendedId: Int,
        intendedIdentifier: String,
        routedId: Int,
        routedIdentifier: String,
    ): Boolean = intendedSpeaker == routedSpeaker && intendedId == routedId && intendedIdentifier == routedIdentifier

    private fun successfulProbeCountAfterRouting(
        currentCount: Int,
        intendedSpeaker: SurfaceSpeaker,
        routedSpeaker: SurfaceSpeaker,
        intendedId: Int,
        intendedIdentifier: String,
        routedId: Int,
        routedIdentifier: String,
    ): Int = if (
        isDirectNamedCollisionHit(
            intendedSpeaker = intendedSpeaker,
            routedSpeaker = routedSpeaker,
            intendedId = intendedId,
            intendedIdentifier = intendedIdentifier,
            routedId = routedId,
            routedIdentifier = routedIdentifier,
        )
    ) {
        currentCount + 1
    } else {
        currentCount
    }

    private fun collisionProbeResolutionOutcome(directHit: Boolean): String =
        if (directHit) "direct-hit" else "fully-occluded"

    private fun interactionEffectEvidence(effect: SurfaceInteractionEffect): JSONObject = JSONObject()
        .put("kind", effect.kind.name)
        .put(
            "intrinsic",
            JSONObject().put("x", effect.intrinsic.x).put("y", effect.intrinsic.y),
        )
        .put("button", effect.button)
        .put("source", effect.source.name)
        .put("collisionIdentifier", effect.collisionIdentifier ?: JSONObject.NULL)
        .put("diagnosticCollisionId", effect.diagnosticCollisionId)
        .put(
            "references",
            JSONArray().apply {
                SurfaceInteractionProtocol.references(effect).forEach { reference ->
                    put(reference)
                }
            },
        )

    private fun StageLayoutPx.toJson() = JSONObject()
        .put("mode", mode.name)
        .put("content", content.toJson())
        .put("keroLane", keroLane.toJson())
        .put("sakuraLane", sakuraLane.toJson())
        .put("keroBubble", keroBubble.toJson())
        .put("sakuraBubble", sakuraBubble.toJson())
        .put("keroSurfaceRegion", keroSurfaceRegion.toJson())
        .put("sakuraSurfaceRegion", sakuraSurfaceRegion.toJson())
        .put("keroSurface", keroSurface.toJson())
        .put("sakuraSurface", sakuraSurface.toJson())
        .put("stageToRoot", JSONObject().put("x", stageToRoot.x).put("y", stageToRoot.y))

    private fun StageLayoutDp.toJson() = JSONObject()
        .put("mode", mode.name)
        .put("content", content.toJson())
        .put("keroLane", keroLane.toJson())
        .put("sakuraLane", sakuraLane.toJson())
        .put("keroBubble", keroBubble.toJson())
        .put("sakuraBubble", sakuraBubble.toJson())
        .put("keroSurfaceRegion", keroSurfaceRegion.toJson())
        .put("sakuraSurfaceRegion", sakuraSurfaceRegion.toJson())
        .put("keroSurface", keroSurface.toJson())
        .put("sakuraSurface", sakuraSurface.toJson())
        .put("tinyFallback", tinyFallback)
        .put("sizingBaseline", JSONObject()
            .put("sharedAuthoredScale", sizingBaseline.sharedAuthoredScale)
            .put("geometryMode", sizingBaseline.geometryKey.mode.name)
            .put("tinyFallback", tinyFallback))

    private fun androidx.compose.ui.unit.IntRect?.toJson() = this?.let {
        JSONObject()
            .put("left", it.left)
            .put("top", it.top)
            .put("right", it.right)
            .put("bottom", it.bottom)
            .put("width", it.width)
            .put("height", it.height)
    } ?: JSONObject.NULL

    private fun StageDpRect?.toJson() = this?.let {
        JSONObject()
            .put("left", it.left.value)
            .put("top", it.top.value)
            .put("right", it.right.value)
            .put("bottom", it.bottom.value)
            .put("width", it.width.value)
            .put("height", it.height.value)
    } ?: JSONObject.NULL

    private fun CollisionShape.shapeKind(): String = when (this) {
        is CollisionShape.Rectangle -> "rectangle"
        is CollisionShape.Ellipse -> "ellipse"
        is CollisionShape.Circle -> "circle"
        is CollisionShape.Polygon -> "polygon"
    }

    private fun JSONObject.toIntOffsetJson(offset: androidx.compose.ui.unit.IntOffset): JSONObject =
        put("x", offset.x).put("y", offset.y)

    private data class NamedCollisionProbeResult(
        val successfulProbeCount: Int,
        val firstSuccessfulSpeaker: SurfaceSpeaker?,
    )

    private fun createOwnedRoot(parent: File, child: String): File {
        require(parent.isDirectory) { "Owned test parent is not a directory: ${parent.absolutePath}" }
        val root = File(parent, child)
        require(!root.exists()) { "Owned test root already exists: ${root.absolutePath}" }
        assertTrue("Could not create owned root ${root.absolutePath}", root.mkdir())
        return root
    }

    private fun screenshotFile(context: Context, safeLabel: String): File {
        val externalRoot = context.getExternalFilesDir("nar-corpus")
        assertNotNull("Target external files directory is unavailable", externalRoot)
        val resultDir = File(externalRoot, safeLabel)
        assertTrue(
            "Could not create result directory ${resultDir.absolutePath}",
            resultDir.exists() || resultDir.mkdirs(),
        )
        return File(resultDir, "screenshot.png")
    }

    private fun copyArchive(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use(input::copyTo)
        }
    }

    private fun writeArtifacts(context: Context, safeLabel: String, result: JSONObject) {
        val externalRoot = context.getExternalFilesDir("nar-corpus")
        assertNotNull("Target external files directory is unavailable", externalRoot)
        val resultDir = File(externalRoot, safeLabel)
        assertTrue(
            "Could not create result directory ${resultDir.absolutePath}",
            resultDir.exists() || resultDir.mkdirs(),
        )
        File(resultDir, "result.json").writeText(result.toString(2))
        val screenshotFile = File(resultDir, "screenshot.png")
        if (screenshotFile.exists()) {
            return
        }

        val bitmap = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(24, 27, 34))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
        }
        canvas.drawText(result.optString("label"), 32f, 64f, paint)
        canvas.drawText("classification: ${result.optString("classification")}", 32f, 112f, paint)
        canvas.drawText("install: ${result.optString("installOutcome")}", 32f, 160f, paint)
        canvas.drawText("surfaces: ${result.optInt("surfaceCount")}", 32f, 208f, paint)
        FileOutputStream(screenshotFile).use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteOwnedRoot(root: File, expectedParent: File) {
        if (!root.exists()) return
        val owned = root.canonicalFile.toPath()
        val expected = expectedParent.canonicalFile.toPath()
        require(owned.parent == expected) {
            "Refusing cleanup outside the exact expected corpus parent: $owned"
        }
        Files.walkFileTree(
            owned,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    error?.let { throw it }
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun sanitizeLabel(label: String): String =
        label.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifEmpty { "archive" }

    private fun phase(name: String) {
        Log.i(LOG_TAG, "phase:$name")
    }

    private companion object {
        const val ARG_PATH = "narCorpusPath"
        const val ARG_SHA256 = "narCorpusSha256"
        const val ARG_LABEL_BASE64 = "narCorpusLabelBase64"
        const val ARG_LABEL = "narCorpusLabel"
        const val ARCHIVE_FILE_NAME = "nanidroid-corpus.nar"
        const val CORPUS_HOST_DIR = "nar-corpus-host"
        const val MAX_DESCRIPTOR_BYTES = 64 * 1024
        const val MAX_SOURCE_SYNTAX_FILE_COUNT = 200
        const val MAX_SOURCE_SYNTAX_BYTES_PER_FILE = 160 * 1024
        const val MAX_SOURCE_SYNTAX_TOTAL_BYTES = 1024 * 1024
        const val MAX_SOURCE_SYNTAX_SELECTOR_IDS = 512
        const val MAX_READER_DIAGNOSTICS = 128
        const val MAX_READER_DIAGNOSTIC_SOURCE_CHARS = 256
        const val MAX_READER_SURFACE_ENTRIES_PER_SURFACE = 64
        const val MAX_READER_SURFACE_ENTRIES_TOTAL = 256
        const val MAX_SHIORI_RESPONSE_VALUE_CHARS = 4096
        const val MAX_PROVENANCE_SOURCE_CHARS = 256
        const val SNAKE_AND_OTACON_LABEL = "Snake and Otacon V1.3.2"
        const val SNAKE_CHOICE_FIRST_HE_HIM_ID = "choicefirsthehim"
        const val SNAKE_CHOICE_FIRST_HE_HIM_LABEL = "he/him"
        const val SNAKE_FAQ_ID = "faq"
        const val SNAKE_FAQ_LABEL = "faq"
        const val SNAKE_NAME_TEACH_ID = "OnNameTeach"
        const val SNAKE_NAME_TEACH_VALUE = "Nanidroid"
        val SURFACE_SOURCE_FILE = Regex("(?i)^surfaces[^/\\\\]*\\.txt$")
        val PRODUCTION_SURFACE_READER_SURFACE_IDS = listOf(0, 8, 9, 19, 40)
        val DIC_FILE = Regex("(?i)^.+\\.dic$")
        val INSTALL_ENTRY = Regex("(?i)(^|.*/)install\\.txt$")
        val TYPE_LINE = Regex("(?im)^\\s*type\\s*,\\s*([^\\r\\n]+)")
        const val LOG_TAG = "NarCorpusProbe"
    }
}
