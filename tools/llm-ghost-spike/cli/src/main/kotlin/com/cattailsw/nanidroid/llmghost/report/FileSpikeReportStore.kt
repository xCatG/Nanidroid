package com.cattailsw.nanidroid.llmghost.report

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CaseStatus
import com.cattailsw.nanidroid.llmghost.model.CompiledScriptValidationReport
import com.cattailsw.nanidroid.llmghost.model.GhostGenerationRequest
import com.cattailsw.nanidroid.llmghost.model.GhostIdentity
import com.cattailsw.nanidroid.llmghost.model.SpikeCaseReport
import com.cattailsw.nanidroid.llmghost.model.SpikeFailure
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RetrievedExampleEvidence(
    val talk: CanonicalTalk,
    val score: Int,
    val reasons: List<String>,
)

@Serializable
data class CaseValidationEvidence(
    val status: CaseStatus,
    val failure: SpikeFailure?,
)

@Serializable
data class SpikeCaseEvidence(
    val corpusIdentity: GhostIdentity,
    val entryHashes: Map<String, String>,
    val request: GhostGenerationRequest,
    val seed: Long,
    val endpoint: String,
    val model: String,
    val candidate: Int,
    val retrievedExamples: List<RetrievedExampleEvidence>,
    val report: SpikeCaseReport,
    val retryCount: Int,
    val validation: CaseValidationEvidence = CaseValidationEvidence(report.status, report.failure),
    val tokenizerEquivalentResult: CompiledScriptValidationReport? = report.compiledScriptValidation,
)

@Serializable
data class SpikeRunSummary(
    val runId: String,
    val endpoint: String,
    val model: String,
    val startedAtUtc: String,
    val cases: List<SpikeCaseEvidence>,
    val preflightFailure: SpikeFailure? = null,
)

class FileSpikeReportStore(private val root: Path) {
    fun beginRun(startedAt: Instant, runId: String): OpenSpikeRun {
        Files.createDirectories(root)
        val stableRunId = stableName(runId)
        val timestamp = RUN_TIMESTAMP.format(startedAt)
        val finalDirectory = root.resolve("$timestamp-$stableRunId")
        if (Files.exists(finalDirectory)) throw FileAlreadyExistsException(finalDirectory.toString())
        val temporary = Files.createTempDirectory(root, ".$stableRunId-")
        try {
            moveAtomically(temporary, finalDirectory)
        } catch (exception: Exception) {
            Files.deleteIfExists(temporary)
            throw exception
        }
        return OpenSpikeRun(finalDirectory)
    }

    class OpenSpikeRun internal constructor(val directory: Path) {
        fun writeCase(evidence: SpikeCaseEvidence, candidate: Int): Path {
            require(candidate > 0) { "Candidate numbers must be positive." }
            val caseName = "${stableName(evidence.report.caseId)}-$candidate"
            val finalDirectory = directory.resolve(caseName)
            if (Files.exists(finalDirectory)) throw FileAlreadyExistsException(finalDirectory.toString())
            val temporary = Files.createTempDirectory(directory, ".$caseName-")
            try {
                val sanitized = evidence.copy(endpoint = sanitizeEndpoint(evidence.endpoint))
                Files.newBufferedWriter(
                    temporary.resolve(CASE_FILE),
                    StandardCharsets.UTF_8,
                ).use { writer -> writer.write(REPORT_JSON.encodeToString(sanitized)) }
                moveAtomically(temporary, finalDirectory)
            } catch (exception: Exception) {
                temporary.toFile().deleteRecursively()
                throw exception
            }
            return finalDirectory
        }

        fun finish(summary: SpikeRunSummary) {
            val summaryPath = directory.resolve(SUMMARY_FILE)
            val reviewPath = directory.resolve(REVIEW_FILE)
            if (Files.exists(summaryPath)) throw FileAlreadyExistsException(summaryPath.toString())
            if (Files.exists(reviewPath)) throw FileAlreadyExistsException(reviewPath.toString())
            val sanitized = summary.copy(
                endpoint = sanitizeEndpoint(summary.endpoint),
                cases = summary.cases.map { it.copy(endpoint = sanitizeEndpoint(it.endpoint)) },
            )
            writeAtomic(summaryPath, REPORT_JSON.encodeToString(sanitized))
            writeAtomic(reviewPath, renderReview(sanitized))
        }
    }

    companion object {
        fun sanitizeEndpoint(value: String): String {
            val uri = try {
                URI(value)
            } catch (_: Exception) {
                return "invalid-endpoint"
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return "invalid-endpoint"
            val host = uri.host ?: return "invalid-endpoint"
            val authority = if (uri.port >= 0) "$host:${uri.port}" else host
            val path = uri.rawPath.orEmpty().ifBlank { "/" }
            return "$scheme://$authority$path"
        }

        private fun renderReview(summary: SpikeRunSummary): String = buildString {
            appendLine("# LLM ghost dialogue spike review")
            appendLine()
            appendLine("- Model: `${summary.model}`")
            appendLine("- Endpoint: `${summary.endpoint}`")
            summary.preflightFailure?.let { failure ->
                appendLine()
                appendLine("## Preflight failure")
                appendLine()
                appendLine("`${failure.code}`: ${failure.detail}")
            }
            summary.cases.forEach { evidence ->
                appendLine()
                appendLine("## ${evidence.report.caseId} — candidate ${evidence.candidate}")
                appendLine()
                appendLine("Status: `${evidence.report.status}`")
                evidence.report.failure?.let { appendLine("Failure: `${it.code}` — ${it.detail}") }
                appendLine()
                appendLine("### Canonical examples")
                evidence.retrievedExamples.forEach { example ->
                    appendLine()
                    appendLine("`${example.talk.id}` (score ${example.score})")
                    example.talk.turns.forEach { turn -> appendLine("> ${turn.text.replace("\n", " ")}") }
                }
                appendLine()
                appendLine("### Generated dialogue")
                evidence.report.generatedDialogue?.turns?.forEach { turn ->
                    appendLine("- ${turn.speaker}: ${turn.text.replace("\n", " ")}")
                } ?: appendLine("_No parsed dialogue; inspect the raw response in case.json._")
                appendLine()
                appendLine("Review: Character voice · Relationship · Novelty · Coherence · English adaptation")
            }
        }

        private fun writeAtomic(finalPath: Path, value: String) {
            if (Files.exists(finalPath)) throw FileAlreadyExistsException(finalPath.toString())
            val temporary = finalPath.resolveSibling(".${finalPath.fileName}.${UUID.randomUUID()}.tmp")
            try {
                Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { it.write(value) }
                moveAtomically(temporary, finalPath)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        private fun moveAtomically(source: Path, target: Path) {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        }

        private fun stableName(value: String): String {
            val stable = value.lowercase(Locale.ROOT)
                .replace(NON_NAME, "-")
                .trim('-')
                .take(MAX_NAME_LENGTH)
            require(stable.isNotEmpty()) { "A stable report identifier is required." }
            return stable
        }

        private const val CASE_FILE = "case.json"
        private const val SUMMARY_FILE = "summary.json"
        private const val REVIEW_FILE = "review.md"
        private const val MAX_NAME_LENGTH = 80
        private val NON_NAME = Regex("[^a-z0-9-]+")
        private val RUN_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
        private val REPORT_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
