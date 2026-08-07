package com.cattailsw.nanidroid.llmghost.report

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CanonicalTurn
import com.cattailsw.nanidroid.llmghost.model.CaseStatus
import com.cattailsw.nanidroid.llmghost.model.CompiledScriptValidationReport
import com.cattailsw.nanidroid.llmghost.model.GeneratedDialogue
import com.cattailsw.nanidroid.llmghost.model.GeneratedTurn
import com.cattailsw.nanidroid.llmghost.model.GenerationScenario
import com.cattailsw.nanidroid.llmghost.model.GenerationUsage
import com.cattailsw.nanidroid.llmghost.model.GhostGenerationRequest
import com.cattailsw.nanidroid.llmghost.model.GhostIdentity
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.OutputLanguage
import com.cattailsw.nanidroid.llmghost.model.RenderedPromptReport
import com.cattailsw.nanidroid.llmghost.model.ScenarioKind
import com.cattailsw.nanidroid.llmghost.model.SpikeCaseReport
import com.cattailsw.nanidroid.llmghost.model.TalkCategory
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileSpikeReportStoreTest {
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
        assertEquals(listOf("case.json"), Files.list(caseDirectory).use { paths ->
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
}
