package com.cattailsw.nanidroid.llmghost

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CanonicalTurn
import com.cattailsw.nanidroid.llmghost.model.GenerationEvent
import com.cattailsw.nanidroid.llmghost.model.GhostCorpusInput
import com.cattailsw.nanidroid.llmghost.model.GhostGenerationRequest
import com.cattailsw.nanidroid.llmghost.model.GhostIdentity
import com.cattailsw.nanidroid.llmghost.model.GhostModelBackend
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.ModelCapabilities
import com.cattailsw.nanidroid.llmghost.model.ModelPreparation
import com.cattailsw.nanidroid.llmghost.model.OutputLanguage
import com.cattailsw.nanidroid.llmghost.model.ScenarioKind
import com.cattailsw.nanidroid.llmghost.model.TalkCategory
import com.cattailsw.nanidroid.llmghost.report.FileSpikeReportStore
import com.cattailsw.nanidroid.llmghost.pipeline.GhostDialoguePipeline
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpikeRunnerTest {
    @Test
    fun requiredCasesCrossThreeScenariosWithBothLanguages() {
        val result = SpikeScenarioFactory.requiredCases(corpus().identity, talks(), seed = 40)

        val matrix = assertIs<ScenarioMatrixResult.Success>(result)
        assertEquals(6, matrix.cases.size)
        assertEquals(
            setOf(
                ScenarioKind.IDLE to OutputLanguage.JAPANESE,
                ScenarioKind.IDLE to OutputLanguage.ENGLISH,
                ScenarioKind.CONTINUATION to OutputLanguage.JAPANESE,
                ScenarioKind.CONTINUATION to OutputLanguage.ENGLISH,
                ScenarioKind.POINTER_EVENT to OutputLanguage.JAPANESE,
                ScenarioKind.POINTER_EVENT to OutputLanguage.ENGLISH,
            ),
            matrix.cases.map { it.case.request.scenario.kind to it.case.request.language }.toSet(),
        )
        val continuation = matrix.cases.first { it.case.request.scenario.kind == ScenarioKind.CONTINUATION }
        assertEquals("idle-real", continuation.case.request.scenario.canonicalTalkId)
        val pointer = matrix.cases.first { it.case.request.scenario.kind == ScenarioKind.POINTER_EVENT }
        assertEquals(GhostSpeakerId.KERO, pointer.case.request.scenario.touchSpeaker)
        assertEquals("head", pointer.case.request.scenario.touchRegion)
        assertEquals((40L..45L).toList(), matrix.cases.map { it.seed })
    }

    @Test
    fun generatedSurfaceAuthorityIsObservedIntersectionWhileAllTalksRemainExamples() {
        val authoredOnMissingSurface = CanonicalTalk(
            id = "missing-surface-talk",
            sourcePath = "ghost/master/dic-extra.txt",
            sourceLine = 4,
            heading = null,
            category = TalkCategory.IDLE,
            turns = listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 99, "Keep this authored voice.")),
        )

        val matrix = assertIs<ScenarioMatrixResult.Success>(
            SpikeScenarioFactory.requiredCases(corpus().identity, talks() + authoredOnMissingSurface, seed = 1),
        )

        val request = matrix.cases.first().case.request
        assertTrue(request.examples.any { it.id == "missing-surface-talk" })
        assertEquals(setOf(0), request.validSurfaces[GhostSpeakerId.SAKURA])
        assertEquals(setOf(1), request.validSurfaces[GhostSpeakerId.KERO])
    }

    @Test
    fun missingContinuationTouchOrAuthorizedSurfacesIsAStablePreflightFailure() {
        val noTouch = talks().filter { it.category != TalkCategory.TOUCH }
        val result = SpikeScenarioFactory.requiredCases(corpus().identity, noTouch, seed = 1)

        val failure = assertIs<ScenarioMatrixResult.Failure>(result)
        assertEquals("missing-pointer-example", failure.code)
    }

    @Test
    fun runnerPersistsMixedOutcomesSequentiallyAndReturnsOne() = runBlocking {
        val root = Files.createTempDirectory("spike-runner")
        val visited = mutableListOf<String>()
        val scriptedEvents = listOf(
            completedJson("Fresh one"),
            completed("{broken"),
            completedJson("Wrong surface", surface = 99),
            completedJson("The forest is quiet."),
            listOf(GenerationEvent.TextDelta("partial"), GenerationEvent.Failed("connection-failed", "offline")),
            completedJson("Fresh six"),
        )
        var index = 0
        var clock = 1_000L
        val runner = SpikeRunner(
            scenarioFactory = SpikeScenarioFactory,
            reportStore = FileSpikeReportStore(root),
            executeCase = { case, _ ->
                visited += case.caseId
                val eventIndex = index++
                val report = GhostDialoguePipeline(
                    backend = ScriptedBackend(scriptedEvents[eventIndex]),
                    nowMillis = { clock++ },
                ).runCase(case)
                SpikeCaseExecution(
                    report = report,
                    retryCount = if (eventIndex == 4) 1 else 0,
                )
            },
            now = { Instant.parse("2026-08-07T01:02:03Z") },
        )

        val outcome = runner.run(
            SpikeRunRequest(
                corpus = corpus(),
                talks = talks(),
                entryHashes = mapOf("ghost/master/dic.txt" to "abc123"),
                endpoint = "http://token@example.test:10101/v1?secret=yes",
                model = "nemotron-3-super",
                seed = 10,
                candidateCount = 1,
                runId = "mixed",
            ),
        )

        assertEquals(1, outcome.exitCode)
        assertEquals(6, visited.size)
        assertEquals(visited.distinct(), visited)
        assertTrue(Files.exists(outcome.reportDirectory.resolve("summary.json")))
        assertTrue(Files.exists(outcome.reportDirectory.resolve("review.md")))
        assertEquals(6, Files.list(outcome.reportDirectory).use { paths ->
            paths.filter { Files.isDirectory(it) }.count().toInt()
        })
        val summary = Files.readString(outcome.reportDirectory.resolve("summary.json"))
        listOf(
            "decoding-failed",
            "dialogue-validation-failed",
            "canonical-exact-copy",
            "generation-failed",
        ).forEach { assertTrue(summary.contains(it), "missing mixed outcome $it") }
        assertTrue(summary.contains("\"retryCount\": 1"))
        assertTrue(summary.contains("http://example.test:10101/v1"))
        assertTrue(!summary.contains("token@example.test") && !summary.contains("secret=yes"))
    }

    @Test
    fun preflightFailureStillProducesReadableSummaryAndReview() = runBlocking {
        val root = Files.createTempDirectory("spike-preflight")
        val runner = SpikeRunner(
            scenarioFactory = SpikeScenarioFactory,
            reportStore = FileSpikeReportStore(root),
            executeCase = { _, _ -> error("must not execute") },
            now = { Instant.parse("2026-08-07T01:02:03Z") },
        )

        val outcome = runner.run(
            SpikeRunRequest(
                corpus = corpus(),
                talks = talks().filter { it.category != TalkCategory.TOUCH },
                entryHashes = emptyMap(),
                endpoint = "http://example.test/v1",
                model = "model",
                seed = 1,
                runId = "preflight",
            ),
        )

        assertEquals(1, outcome.exitCode)
        assertTrue(Files.readString(outcome.reportDirectory.resolve("summary.json")).contains("missing-pointer-example"))
        assertTrue(Files.readString(outcome.reportDirectory.resolve("review.md")).contains("Preflight failure"))
    }

    @Test
    fun unexpectedExecutorFailureIsReportedWithoutLeakingItsMessage() = runBlocking {
        val root = Files.createTempDirectory("spike-exception")
        val runner = SpikeRunner(
            scenarioFactory = SpikeScenarioFactory,
            reportStore = FileSpikeReportStore(root),
            executeCase = { _, _ ->
                throw IllegalStateException("Bearer secret-token at http://user:password@example.test")
            },
            now = { Instant.parse("2026-08-07T01:02:03Z") },
        )

        val outcome = runner.run(
            SpikeRunRequest(corpus(), talks(), emptyMap(), "http://example.test/v1", "model", 1, runId = "safe"),
        )

        val summary = Files.readString(outcome.reportDirectory.resolve("summary.json"))
        assertTrue(summary.contains("runner-exception"))
        assertTrue(!summary.contains("secret-token") && !summary.contains("user:password"))
    }

    @Test
    fun cancellationPropagatesWithoutBeingReportedAsAnOrdinaryFailure() {
        val root = Files.createTempDirectory("spike-cancel")
        val cancelled = CancellationException("stop unchanged")
        var executions = 0
        val runner = SpikeRunner(
            scenarioFactory = SpikeScenarioFactory,
            reportStore = FileSpikeReportStore(root),
            executeCase = { case, _ ->
                if (executions++ == 0) {
                    SpikeCaseExecution(
                        GhostDialoguePipeline(
                            ScriptedBackend(completedJson("Saved before cancellation")),
                            nowMillis = { 1_000L + executions },
                        ).runCase(case),
                    )
                } else {
                    throw cancelled
                }
            },
            now = { Instant.parse("2026-08-07T01:02:03Z") },
        )

        val thrown = assertFailsWith<CancellationException> {
            runBlocking {
                runner.run(
                    SpikeRunRequest(
                        corpus(), talks(), emptyMap(), "http://example.test/v1", "model", 1, runId = "cancel",
                    ),
                )
            }
        }
        assertTrue(thrown === cancelled)
        val recovery = Files.list(root).use { it.toList().single() }
        assertTrue(Files.exists(recovery.resolve("idle-japanese-1/case.json")))
        assertTrue(Files.exists(recovery.resolve("recovery.json")))
        assertTrue(!FileSpikeReportStore.isPublishedRun(recovery))
        assertFailsWith<FileAlreadyExistsException> {
            FileSpikeReportStore(root).beginRun(Instant.parse("2026-08-07T01:02:03Z"), "cancel")
        }
    }

    private fun corpus() = GhostCorpusInput(
        identity = GhostIdentity(
            ghostName = "Fixture",
            sakuraName = "Sophie",
            keroName = "Liere",
            shellSurfaces = mapOf(
                GhostSpeakerId.SAKURA to setOf(0, 2),
                GhostSpeakerId.KERO to setOf(1, 3),
            ),
        ),
        files = emptyList(),
    )

    private fun talks() = listOf(
        CanonicalTalk(
            id = "idle-real",
            sourcePath = "ghost/master/dic.txt",
            sourceLine = 1,
            heading = null,
            category = TalkCategory.IDLE,
            turns = listOf(
                CanonicalTurn(GhostSpeakerId.SAKURA, 0, "The forest is quiet."),
                CanonicalTurn(GhostSpeakerId.KERO, 1, "For once."),
            ),
        ),
        CanonicalTalk(
            id = "touch-real",
            sourcePath = "ghost/master/dic-touch.txt",
            sourceLine = 8,
            heading = "OnMouseDoubleClick",
            category = TalkCategory.TOUCH,
            touchSpeaker = GhostSpeakerId.KERO,
            touchRegion = "head",
            turns = listOf(CanonicalTurn(GhostSpeakerId.KERO, 1, "Don't touch there.")),
        ),
    )

    private fun completedJson(text: String, surface: Int = 0) = completed(
        "{\"turns\":[{\"speaker\":\"sakura\",\"surface\":$surface," +
            "\"text\":\"$text\",\"waitAfterMs\":0}]}",
    )

    private fun completed(text: String) = listOf(
        GenerationEvent.TextDelta(text),
        GenerationEvent.Completed(null),
    )

    private class ScriptedBackend(private val events: List<GenerationEvent>) : GhostModelBackend {
        override val capabilities = ModelCapabilities(streaming = true, structuredOutput = false)
        override fun prepare(): Flow<ModelPreparation> = flowOf(ModelPreparation.Ready)
        override fun generate(request: GhostGenerationRequest): Flow<GenerationEvent> = flowOf(*events.toTypedArray())
        override suspend fun close() = Unit
    }
}
