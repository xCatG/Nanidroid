package com.cattailsw.nanidroid.llmghost.report

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CanonicalTurn
import com.cattailsw.nanidroid.llmghost.model.CaseStatus
import com.cattailsw.nanidroid.llmghost.model.CompiledScriptValidationReport
import com.cattailsw.nanidroid.llmghost.model.GeneratedDialogue
import com.cattailsw.nanidroid.llmghost.model.GeneratedTurn
import com.cattailsw.nanidroid.llmghost.model.GenerationScenario
import com.cattailsw.nanidroid.llmghost.model.GenerationEvent
import com.cattailsw.nanidroid.llmghost.model.GenerationUsage
import com.cattailsw.nanidroid.llmghost.model.GhostGenerationRequest
import com.cattailsw.nanidroid.llmghost.model.GhostIdentity
import com.cattailsw.nanidroid.llmghost.model.GhostModelBackend
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.ModelCapabilities
import com.cattailsw.nanidroid.llmghost.model.ModelPreparation
import com.cattailsw.nanidroid.llmghost.model.OutputLanguage
import com.cattailsw.nanidroid.llmghost.model.RenderedPromptReport
import com.cattailsw.nanidroid.llmghost.model.ScenarioKind
import com.cattailsw.nanidroid.llmghost.model.SpikeCaseReport
import com.cattailsw.nanidroid.llmghost.model.SpikeCase
import com.cattailsw.nanidroid.llmghost.model.SpikeWarning
import com.cattailsw.nanidroid.llmghost.model.TalkCategory
import com.cattailsw.nanidroid.llmghost.pipeline.GhostDialoguePipeline
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileSpikeReportStoreTest {
    @Test
    fun preexistingFinalDirectoryIsNeverModifiedByProductionBeginRun() {
        val root = Files.createTempDirectory("preexisting-final")
        val expected = root.resolve("20260807T010203.000Z-existing")
        Files.createDirectory(expected)
        Files.writeString(expected.resolve("external.txt"), "external-owner")

        assertFailsWith<FileAlreadyExistsException> {
            FileSpikeReportStore(root).beginRun(Instant.parse("2026-08-07T01:02:03Z"), "existing")
        }

        assertEquals("external-owner", Files.readString(expected.resolve("external.txt")))
        assertTrue(!FileSpikeReportStore.isPublishedRun(expected))
    }

    @Test
    fun completionMarkerIsTheLastLogicalCommit() {
        val root = Files.createTempDirectory("logical-commit")
        val run = FileSpikeReportStore(root).beginRun(Instant.parse("2026-08-07T01:02:03Z"), "commit")
        run.writeCase(evidence(), 2)
        assertTrue(!FileSpikeReportStore.isPublishedRun(run.directory))

        run.finish(summary(model = "model-commit"))

        assertTrue(Files.exists(run.directory.resolve("summary.json")))
        assertTrue(Files.exists(run.directory.resolve("review.md")))
        assertTrue(FileSpikeReportStore.isPublishedRun(run.directory))
    }

    @Test
    fun completionMarkerFailureLeavesACompleteUnpublishedRecoveryDirectory() {
        val root = Files.createTempDirectory("commit-failure")
        val run = FileSpikeReportStore(root, runCommitter = OmittingRunCommitter)
            .beginRun(Instant.parse("2026-08-07T01:02:03Z"), "commit-failure")
        run.writeCase(evidence(), 2)

        val failure = assertFailsWith<SpikeReportPublicationException> {
            run.finish(summary(model = "model-commit"))
        }

        assertEquals("completion-marker-failed", failure.failureCode)
        assertEquals(run.directory, failure.recoveryDirectory)
        assertTrue(Files.exists(run.directory.resolve("idle-english-2/case.json")))
        assertTrue(Files.exists(run.directory.resolve("summary.json")))
        assertTrue(Files.exists(run.directory.resolve("review.md")))
        assertTrue(!FileSpikeReportStore.isPublishedRun(run.directory))
    }

    @Test
    fun summaryOrReviewWriteFailurePreservesCompletedCasesAtRecoveryLocation() {
        listOf("summary.json", "review.md").forEach { failingName ->
            val root = Files.createTempDirectory("write-recovery")
            val store = FileSpikeReportStore(
                root,
                artifactWriter = FailingArtifactWriter(failingName),
            )
            val instant = Instant.parse("2026-08-07T01:02:03Z")
            val run = store.beginRun(instant, "write-failure-${failingName.substringBefore('.')}")
            run.writeCase(evidence(), 2)

            val failure = assertFailsWith<SpikeReportPublicationException> {
                run.finish(summary(model = "model-write"))
            }

            assertEquals("${failingName.substringBefore('.')}-write-failed", failure.failureCode)
            assertEquals(run.recoveryDirectory, failure.recoveryDirectory)
            assertTrue(Files.exists(failure.recoveryDirectory.resolve("idle-english-2/case.json")))
            assertTrue(Files.isDirectory(run.directory))
            assertTrue(!FileSpikeReportStore.isPublishedRun(run.directory))
            assertTrue(Files.list(failure.recoveryDirectory).use { paths ->
                paths.noneMatch { it.fileName.toString().endsWith(".tmp") }
            })

            assertFailsWith<FileAlreadyExistsException> {
                FileSpikeReportStore(root).beginRun(
                    instant,
                    "write-failure-${failingName.substringBefore('.')}",
                )
            }
        }
    }

    @Test
    fun externallyAppearingSummaryIsNeverOverwrittenAndRunBecomesRecovery() {
        val root = Files.createTempDirectory("target-race")
        val store = FileSpikeReportStore(root)
        val run = store.beginRun(Instant.parse("2026-08-07T01:02:03Z"), "target-race")
        run.writeCase(evidence(), 2)
        Files.writeString(
            run.directory.resolve("summary.json"),
            "external-owner",
            StandardOpenOption.CREATE_NEW,
        )

        val failure = assertFailsWith<SpikeReportPublicationException> {
            run.finish(summary(model = "model-race"))
        }

        assertEquals("summary-write-failed", failure.failureCode)
        assertTrue(Files.exists(failure.recoveryDirectory.resolve("idle-english-2/case.json")))
        assertEquals("external-owner", Files.readString(run.directory.resolve("summary.json")))
        assertTrue(!Files.exists(run.directory.resolve("review.md")))
        assertTrue(!FileSpikeReportStore.isPublishedRun(run.directory))
        assertFailsWith<FileAlreadyExistsException> {
            FileSpikeReportStore(root).beginRun(Instant.parse("2026-08-07T01:02:03Z"), "target-race")
        }
    }

    @Test
    fun pipelineDiagnosticDetailsAreSanitizedInEveryArtifactWhileExactEvidenceRemains() = runBlocking {
        val secret = "Bearer secret-token at http://user:password@example.test/private"
        val base = evidence()
        val report = GhostDialoguePipeline(
            backend = GenerationThrowingBackend(secret),
            nowMillis = sequenceOf(1_000L, 1_001L).iterator()::next,
        ).runCase(SpikeCase(base.report.caseId, base.request))
        val nestedReport = report.copy(
            preparationEvents = report.preparationEvents + ModelPreparation.Failed("Bearer-secret-token", secret),
            generationEvents = report.generationEvents + GenerationEvent.Failed("Bearer-secret-token", secret),
            warnings = listOf(SpikeWarning("Bearer-secret-token", secret)),
            failure = report.failure?.copy(sourceCode = "Bearer-secret-token"),
        )
        val evidence = base.copy(
            report = nestedReport,
            validation = CaseValidationEvidence(nestedReport.status, nestedReport.failure),
        )
        val run = FileSpikeReportStore(Files.createTempDirectory("sanitized-report"))
            .beginRun(Instant.parse("2026-08-07T01:02:03Z"), "sanitized")

        run.writeCase(evidence, 1)
        run.finish(
            SpikeRunSummary("sanitized", evidence.endpoint, evidence.model, "2026-08-07T01:02:03Z", listOf(evidence)),
        )

        val artifacts = listOf(
            run.directory.resolve("idle-english-1/case.json"),
            run.directory.resolve("summary.json"),
            run.directory.resolve("review.md"),
        ).map(Files::readString)
        artifacts.forEach { artifact ->
            assertTrue(!artifact.contains("secret-token"), artifact)
            assertTrue(!artifact.contains("user:password"), artifact)
            assertTrue(artifact.contains("generation-exception"), artifact)
        }
        assertTrue(artifacts[0].contains("Generate a short ghost dialogue"))
        assertTrue(artifacts[0].contains("Fresh generated line"))
        assertTrue(artifacts[0].contains("Canonical line"))
    }

    @Test
    fun concurrentIdenticalRunCreationHasExactlyOneWinner() {
        val root = Files.createTempDirectory("concurrent-run")
        val store = FileSpikeReportStore(root)
        val instant = Instant.parse("2026-08-07T01:02:03Z")

        val results = race(8) { store.beginRun(instant, "same") }

        val winners = results.filterIsInstance<RaceResult.Success<FileSpikeReportStore.OpenSpikeRun>>()
        assertEquals(1, winners.size)
        assertEquals(7, results.filterIsInstance<RaceResult.AlreadyExists>().size)
        winners.single().value.finish(
            SpikeRunSummary("same", "http://example.test/v1", "model", instant.toString(), emptyList()),
        )
    }

    @Test
    fun concurrentIdenticalCasePublicationNeverOverwritesTheWinner() {
        val root = Files.createTempDirectory("concurrent-case")
        val run = FileSpikeReportStore(root).beginRun(Instant.parse("2026-08-07T01:02:03Z"), "case")

        val results = race(8) { run.writeCase(evidence(), 2) }

        assertEquals(1, results.filterIsInstance<RaceResult.Success<*>>().size)
        assertEquals(7, results.filterIsInstance<RaceResult.AlreadyExists>().size)
        run.finish(
            SpikeRunSummary("case", "http://example.test/v1", "model", "2026-08-07T01:02:03Z", listOf(evidence())),
        )
        assertTrue(Files.exists(run.directory.resolve("idle-english-2/case.json")))
    }

    @Test
    fun concurrentFinishPublishesOneMatchingSummaryReviewPair() {
        val root = Files.createTempDirectory("concurrent-finish")
        val run = FileSpikeReportStore(root).beginRun(Instant.parse("2026-08-07T01:02:03Z"), "finish")
        run.writeCase(evidence(), 2)
        val summaries = listOf(
            SpikeRunSummary("finish", "http://example.test/v1", "model-a", "2026-08-07T01:02:03Z", listOf(evidence())),
            SpikeRunSummary("finish", "http://example.test/v1", "model-b", "2026-08-07T01:02:03Z", listOf(evidence())),
        )
        val next = java.util.concurrent.atomic.AtomicInteger()

        val results = race(2) { run.finish(summaries[next.getAndIncrement()]) }

        assertEquals(1, results.filterIsInstance<RaceResult.Success<*>>().size)
        assertEquals(1, results.filterIsInstance<RaceResult.AlreadyExists>().size)
        val json = Files.readString(run.directory.resolve("summary.json"))
        val review = Files.readString(run.directory.resolve("review.md"))
        val publishedModel = if (json.contains("model-a")) "model-a" else "model-b"
        assertTrue(json.contains(publishedModel))
        assertTrue(review.contains(publishedModel))
        assertTrue(!review.contains(if (publishedModel == "model-a") "model-b" else "model-a"))
    }

    @Test
    fun endpointSanitizationRetainsAValidBracketedIpv6Authority() {
        assertEquals(
            "http://[2001:db8::1]:10101/v1",
            FileSpikeReportStore.sanitizeEndpoint("http://user:pass@[2001:db8::1]:10101/v1?q=secret"),
        )
    }

    @Test
    fun caseJsonContainsCompleteCredentialFreeEvidenceAndStableNames() {
        val root = Files.createTempDirectory("report-store")
        val store = FileSpikeReportStore(root)
        val run = store.beginRun(Instant.parse("2026-08-07T01:02:03.456Z"), "run_7")

        val caseDirectory = run.writeCase(evidence(), candidate = 2)

        assertEquals("idle-english-2", caseDirectory.fileName.toString())
        assertEquals(listOf(".case-complete", "case.json"), Files.list(caseDirectory).use { paths ->
            paths.map { it.fileName.toString() }.sorted().toList()
        })
        val json = Files.readString(caseDirectory.resolve("case.json"))
        listOf(
            "entryHashes", "scenario", "language", "seed", "endpoint", "model",
            "retrievedExamples", "score", "renderedPrompt", "rawResponse", "generatedDialogue",
            "compiledSakuraScript", "validation", "tokenizerEquivalentResult", "compiledScriptValidation",
            "similarityFindings", "elapsedMillis",
            "usage", "retryCount", "failure",
        ).forEach { assertTrue(json.contains("\"$it\""), "missing $it") }
        assertTrue(json.contains("http://example.test:10101/v1"))
        assertTrue(!json.contains("user:password") && !json.contains("secret=yes"))
    }

    @Test
    fun finalPathsAreImmutableAndExistingRunOrCaseIsRejected() {
        val root = Files.createTempDirectory("immutable-report")
        val store = FileSpikeReportStore(root)
        val instant = Instant.parse("2026-08-07T01:02:03Z")
        val run = store.beginRun(instant, "same")
        run.writeCase(evidence(), 1)

        assertFailsWith<FileAlreadyExistsException> { run.writeCase(evidence(), 1) }
        assertFailsWith<FileAlreadyExistsException> { store.beginRun(instant, "same") }
    }

    @Test
    fun summaryAndReviewShowCanonicalAndGeneratedTextIncludingFailures() {
        val root = Files.createTempDirectory("review-report")
        val run = FileSpikeReportStore(root).beginRun(Instant.parse("2026-08-07T01:02:03Z"), "review")
        val complete = evidence()
        run.writeCase(complete, 1)

        run.finish(
            SpikeRunSummary(
                runId = "review",
                endpoint = "http://example.test:10101/v1",
                model = "model-x",
                startedAtUtc = "2026-08-07T01:02:03Z",
                cases = listOf(complete),
            ),
        )

        val review = Files.readString(run.directory.resolve("review.md"))
        assertTrue(review.contains("Canonical line"))
        assertTrue(review.contains("Fresh generated line"))
        assertTrue(review.contains("Character voice"))
        assertTrue(Files.readString(run.directory.resolve("summary.json")).contains("idle-english"))
        assertFailsWith<FileAlreadyExistsException> {
            run.finish(
                SpikeRunSummary("review", "http://example.test/v1", "model-x", "2026-08-07T01:02:03Z", listOf(complete)),
            )
        }
    }

    private fun evidence(): SpikeCaseEvidence {
        val talk = CanonicalTalk(
            id = "canonical-1",
            sourcePath = "ghost/master/dic.txt",
            sourceLine = 2,
            heading = null,
            category = TalkCategory.IDLE,
            turns = listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, "Canonical line")),
        )
        val request = GhostGenerationRequest(
            scenario = GenerationScenario(ScenarioKind.IDLE, topic = "forest"),
            language = OutputLanguage.ENGLISH,
            examples = listOf(talk),
            validSurfaces = mapOf(GhostSpeakerId.SAKURA to setOf(0), GhostSpeakerId.KERO to setOf(1)),
        )
        return SpikeCaseEvidence(
            corpusIdentity = GhostIdentity(
                "Fixture", "Sophie", "Liere",
                mapOf(GhostSpeakerId.SAKURA to setOf(0), GhostSpeakerId.KERO to setOf(1)),
            ),
            entryHashes = mapOf("ghost/master/dic.txt" to "abc123"),
            request = request,
            seed = 77,
            endpoint = "http://user:password@example.test:10101/v1?secret=yes",
            model = "model-x",
            candidate = 2,
            retrievedExamples = listOf(
                RetrievedExampleEvidence(talk, 120, listOf("category", "both-speakers")),
            ),
            report = SpikeCaseReport(
                caseId = "idle-english",
                status = CaseStatus.PASSED,
                rawResponse = "{\"turns\":[{\"text\":\"Fresh generated line\"}]}",
                compiledSakuraScript = "\\0\\s[0]Fresh generated line\\_w[0]\\e",
                renderedPrompt = RenderedPromptReport("system exact", "user exact", listOf("canonical-1")),
                elapsedMillis = 45,
                usage = GenerationUsage(10, 5, 15),
                generatedDialogue = GeneratedDialogue(listOf(GeneratedTurn("sakura", 0, "Fresh generated line"))),
                compiledScriptValidation = CompiledScriptValidationReport(valid = true),
            ),
            retryCount = 1,
        )
    }

    private fun summary(model: String) = SpikeRunSummary(
        runId = "fixture",
        endpoint = "http://example.test/v1",
        model = model,
        startedAtUtc = "2026-08-07T01:02:03Z",
        cases = listOf(evidence()),
    )

    private fun <T> race(count: Int, operation: () -> T): List<RaceResult<T>> {
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(count)
        return try {
            val futures = (1..count).map {
                executor.submit(
                    Callable {
                        ready.countDown()
                        start.await()
                        try {
                            RaceResult.Success(operation())
                        } catch (_: FileAlreadyExistsException) {
                            RaceResult.AlreadyExists
                        }
                    },
                )
            }
            ready.await()
            start.countDown()
            futures.map { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    private sealed interface RaceResult<out T> {
        data class Success<T>(val value: T) : RaceResult<T>
        data object AlreadyExists : RaceResult<Nothing>
    }

    private class GenerationThrowingBackend(private val detail: String) : GhostModelBackend {
        override val capabilities = ModelCapabilities(streaming = false, structuredOutput = false)
        override fun prepare(): Flow<ModelPreparation> = flowOf(ModelPreparation.Ready)
        override fun generate(request: GhostGenerationRequest) = flow {
            emit(com.cattailsw.nanidroid.llmghost.model.GenerationEvent.TextDelta("Fresh generated line"))
            throw IllegalStateException(detail)
        }
        override suspend fun close() = Unit
    }

    private class FailingArtifactWriter(private val failingName: String) : ArtifactWriter {
        override fun write(finalPath: java.nio.file.Path, value: String) {
            if (finalPath.fileName.toString() == failingName) throw IOException("fixture write failure")
            JvmCreateNewArtifactWriter.write(finalPath, value)
        }
    }

    private object OmittingRunCommitter : RunCommitter {
        override fun commit(runDirectory: java.nio.file.Path) = Unit
    }
}
