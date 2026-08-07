package com.cattailsw.nanidroid.llmghost.report

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CaseStatus
import com.cattailsw.nanidroid.llmghost.model.CompiledScriptValidationReport
import com.cattailsw.nanidroid.llmghost.model.GenerationEvent
import com.cattailsw.nanidroid.llmghost.model.GhostGenerationRequest
import com.cattailsw.nanidroid.llmghost.model.GhostIdentity
import com.cattailsw.nanidroid.llmghost.model.ModelPreparation
import com.cattailsw.nanidroid.llmghost.model.SpikeCaseReport
import com.cattailsw.nanidroid.llmghost.model.SpikeFailure
import com.cattailsw.nanidroid.llmghost.model.SpikeWarning
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun interface ArtifactWriter {
    fun write(finalPath: Path, value: String)
}

internal fun interface RunCommitter {
    fun commit(runDirectory: Path)
}

internal fun interface CaseCommitter {
    fun commit(caseDirectory: Path)
}

internal object JvmCreateNewArtifactWriter : ArtifactWriter {
    override fun write(finalPath: Path, value: String) {
        Files.newBufferedWriter(
            finalPath,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { it.write(value) }
    }
}

internal object JvmCreateNewRunCommitter : RunCommitter {
    override fun commit(runDirectory: Path) {
        createMarker(runDirectory.resolve(RUN_COMPLETION_MARKER))
    }
}

internal object JvmCreateNewCaseCommitter : CaseCommitter {
    override fun commit(caseDirectory: Path) {
        createMarker(caseDirectory.resolve(CASE_COMPLETION_MARKER))
    }
}

private fun createMarker(path: Path) {
    Files.newByteChannel(
        path,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    ).use { }
}

private const val RUN_COMPLETION_MARKER = ".complete"
private const val CASE_COMPLETION_MARKER = ".case-complete"
private const val RECOVERY_MARKER = ".recovery"

class SpikeReportPublicationException(
    recoveryDirectory: Path,
    failureCode: String,
) : IOException(
    "Report publication failed (${stablePersistenceFailureCode(failureCode)}); " +
        "completed evidence remains in the recovery directory.",
) {
    val recoveryDirectory: Path = recoveryDirectory.toAbsolutePath().normalize()
    val failureCode: String = stablePersistenceFailureCode(failureCode)
}

private fun stablePersistenceFailureCode(value: String): String =
    value.takeIf(PERSISTENCE_FAILURE_CODES::contains) ?: "report-persistence-failed"

private val PERSISTENCE_FAILURE_CODES = setOf(
    "case-directory-failed",
    "case-write-failed",
    "case-completion-marker-failed",
    "summary-write-failed",
    "review-write-failed",
    "publication-target-exists",
    "completion-marker-failed",
    "report-persistence-failed",
)

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

@Serializable
private data class RecoveryEvidence(val failureCode: String)

/**
 * Reserves the stable run directory with create-new directory semantics.
 *
 * Cases become logically complete when their `.case-complete` marker exists.
 * A run becomes published only when `.complete` is created after every report
 * artifact has been closed. A directory without `.complete` is recovery state,
 * not a final report, and is never automatically overwritten or deleted.
 */
class FileSpikeReportStore private constructor(
    private val root: Path,
    private val artifactWriter: ArtifactWriter,
    private val caseCommitter: CaseCommitter,
    private val runCommitter: RunCommitter,
) {
    constructor(root: Path) : this(
        root,
        JvmCreateNewArtifactWriter,
        JvmCreateNewCaseCommitter,
        JvmCreateNewRunCommitter,
    )

    internal constructor(
        root: Path,
        artifactWriter: ArtifactWriter,
    ) : this(root, artifactWriter, JvmCreateNewCaseCommitter, JvmCreateNewRunCommitter)

    internal constructor(
        root: Path,
        caseCommitter: CaseCommitter,
    ) : this(root, JvmCreateNewArtifactWriter, caseCommitter, JvmCreateNewRunCommitter)

    internal constructor(
        root: Path,
        artifactWriter: ArtifactWriter,
        caseCommitter: CaseCommitter,
    ) : this(root, artifactWriter, caseCommitter, JvmCreateNewRunCommitter)

    internal constructor(
        root: Path,
        runCommitter: RunCommitter,
    ) : this(root, JvmCreateNewArtifactWriter, JvmCreateNewCaseCommitter, runCommitter)

    fun beginRun(startedAt: Instant, runId: String): OpenSpikeRun {
        Files.createDirectories(root)
        val stableRunId = stableName(runId)
        val timestamp = RUN_TIMESTAMP.format(startedAt)
        val finalDirectory = root.resolve("$timestamp-$stableRunId")
        Files.createDirectory(finalDirectory)
        return OpenSpikeRun(
            directory = finalDirectory,
            artifactWriter = artifactWriter,
            caseCommitter = caseCommitter,
            runCommitter = runCommitter,
        )
    }

    class OpenSpikeRun internal constructor(
        val directory: Path,
        private val artifactWriter: ArtifactWriter,
        private val caseCommitter: CaseCommitter,
        private val runCommitter: RunCommitter,
    ) {
        private var state = RunState.OPEN

        @get:Synchronized
        val recoveryDirectory: Path?
            get() = directory.takeIf { state == RunState.RECOVERY }

        @Synchronized
        fun writeCase(evidence: SpikeCaseEvidence, candidate: Int): Path {
            requireOpen()
            require(candidate > 0) { "Candidate numbers must be positive." }
            val caseName = "${stableName(evidence.report.caseId)}-$candidate"
            val caseDirectory = directory.resolve(caseName)
            try {
                Files.createDirectory(caseDirectory)
            } catch (exists: FileAlreadyExistsException) {
                throw exists
            } catch (exception: Exception) {
                throw persistenceFailure("case-directory-failed")
            }
            val sanitized = sanitizeEvidence(evidence)
            try {
                artifactWriter.write(caseDirectory.resolve(CASE_FILE), REPORT_JSON.encodeToString(sanitized))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                throw persistenceFailure("case-write-failed")
            }
            try {
                caseCommitter.commit(caseDirectory)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                if (isCommittedCase(caseDirectory)) return caseDirectory
                throw persistenceFailure("case-completion-marker-failed")
            }
            return caseDirectory
        }

        @Synchronized
        fun finish(summary: SpikeRunSummary) {
            requireOpen()
            val summaryPath = directory.resolve(SUMMARY_FILE)
            val reviewPath = directory.resolve(REVIEW_FILE)
            val sanitized = sanitizeSummary(summary)
            var stage = PublicationStage.SUMMARY
            try {
                artifactWriter.write(summaryPath, REPORT_JSON.encodeToString(sanitized))
                stage = PublicationStage.REVIEW
                artifactWriter.write(reviewPath, renderReview(sanitized))
                stage = PublicationStage.RUN
                runCommitter.commit(directory)
                if (!isPublishedRun(directory)) {
                    throw IOException("The run committer did not create the completion marker.")
                }
                state = RunState.PUBLISHED
            } catch (cancelled: CancellationException) {
                if (stage == PublicationStage.RUN && isPublishedRun(directory)) {
                    state = RunState.PUBLISHED
                }
                throw cancelled
            } catch (exception: Exception) {
                if (stage == PublicationStage.RUN && isPublishedRun(directory)) {
                    state = RunState.PUBLISHED
                    return
                }
                throw persistenceFailure(stage.failureCode(exception))
            }
        }

        @Synchronized
        fun abort(failureCode: String): Path {
            requireOpen()
            transitionToRecovery(stableDiagnosticCode(failureCode))
            return directory
        }

        private fun persistenceFailure(failureCode: String): SpikeReportPublicationException {
            val code = stablePersistenceFailureCode(failureCode)
            transitionToRecovery(code)
            return SpikeReportPublicationException(directory, code)
        }

        private fun transitionToRecovery(failureCode: String) {
            state = RunState.RECOVERY
            runCatching { createMarker(directory.resolve(RECOVERY_MARKER)) }
            runCatching { Files.deleteIfExists(directory.resolve(RUN_COMPLETION_MARKER)) }
            runCatching {
                artifactWriter.write(
                    directory.resolve(RECOVERY_FILE),
                    REPORT_JSON.encodeToString(RecoveryEvidence(failureCode)),
                )
            }
        }

        private fun requireOpen() {
            if (state != RunState.OPEN) throw FileAlreadyExistsException(directory.toString())
        }

        private fun isCommittedCase(caseDirectory: Path): Boolean =
            Files.isRegularFile(caseDirectory.resolve(CASE_COMPLETION_MARKER))

        private enum class RunState { OPEN, PUBLISHED, RECOVERY }

        private enum class PublicationStage {
            SUMMARY,
            REVIEW,
            RUN,
            ;

            fun failureCode(exception: Exception): String = when {
                this == SUMMARY -> "summary-write-failed"
                this == REVIEW -> "review-write-failed"
                exception is FileAlreadyExistsException -> "publication-target-exists"
                else -> "completion-marker-failed"
            }
        }
    }

    companion object {
        /** The only supported reader check for distinguishing final reports from recovery directories. */
        fun isPublishedRun(directory: Path): Boolean =
            Files.isDirectory(directory) &&
                Files.isRegularFile(directory.resolve(RUN_COMPLETION_MARKER)) &&
                Files.isRegularFile(directory.resolve(SUMMARY_FILE)) &&
                Files.isRegularFile(directory.resolve(REVIEW_FILE)) &&
                Files.notExists(directory.resolve(RECOVERY_MARKER)) &&
                Files.notExists(directory.resolve(RECOVERY_FILE))

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
                evidence.report.failure?.let { failure ->
                    appendLine("Failure: `${failure.code}` — ${failure.detail}")
                    failure.sourceCode?.let { appendLine("Source: `$it`") }
                }
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

        private fun sanitizeSummary(summary: SpikeRunSummary): SpikeRunSummary = summary.copy(
            endpoint = sanitizeEndpoint(summary.endpoint),
            cases = summary.cases.map(::sanitizeEvidence),
            preflightFailure = summary.preflightFailure?.sanitized(),
        )

        private fun sanitizeEvidence(evidence: SpikeCaseEvidence): SpikeCaseEvidence {
            val report = evidence.report.sanitized()
            return evidence.copy(
                entryHashes = evidence.entryHashes.toSortedMap(),
                endpoint = sanitizeEndpoint(evidence.endpoint),
                report = report,
                validation = CaseValidationEvidence(report.status, report.failure),
                tokenizerEquivalentResult = report.compiledScriptValidation,
            )
        }

        private fun SpikeCaseReport.sanitized(): SpikeCaseReport {
            val scriptValidation = compiledScriptValidation?.let { validation ->
                validation.copy(violationCodes = validation.violationCodes.map(::stableDiagnosticCode))
            }
            return copy(
                preparationEvents = preparationEvents.map { event ->
                    when (event) {
                        is ModelPreparation.Failed -> stableDiagnosticCode(event.code).let { code ->
                            ModelPreparation.Failed(code, stableDiagnosticDetail(code))
                        }
                        else -> event
                    }
                },
                generationEvents = generationEvents.map { event ->
                    when (event) {
                        is GenerationEvent.Failed -> stableDiagnosticCode(event.code).let { code ->
                            GenerationEvent.Failed(code, stableDiagnosticDetail(code))
                        }
                        else -> event
                    }
                },
                warnings = warnings.map { it.sanitized() },
                compiledScriptValidation = scriptValidation,
                failure = failure?.sanitized(),
            )
        }

        private fun SpikeFailure.sanitized(): SpikeFailure {
            val code = stableDiagnosticCode(code)
            return copy(
                code = code,
                detail = stableDiagnosticDetail(code),
                sourceCode = sourceCode?.let(::stableDiagnosticCode),
            )
        }

        private fun SpikeWarning.sanitized(): SpikeWarning {
            val code = stableDiagnosticCode(code)
            return copy(code = code, detail = stableDiagnosticDetail(code))
        }

        private fun stableDiagnosticCode(value: String): String =
            value.takeIf(SAFE_DIAGNOSTIC_CODES::contains) ?: REDACTED_DIAGNOSTIC_CODE

        private fun stableDiagnosticDetail(code: String): String =
            "Diagnostic detail omitted at persistence boundary; classification=$code."

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
        private const val RECOVERY_FILE = "recovery.json"
        private const val MAX_NAME_LENGTH = 80
        private const val REDACTED_DIAGNOSTIC_CODE = "redacted-diagnostic-code"
        private val NON_NAME = Regex("[^a-z0-9-]+")
        private val SAFE_DIAGNOSTIC_CODES = setOf(
            REDACTED_DIAGNOSTIC_CODE,
            "ambiguous-output",
            "archive-not-found",
            "archive-size-limit",
            "archive-unreadable",
            "canonical-exact-copy",
            "canonical-near-copy",
            "compilation-exception",
            "compiled-script-invalid",
            "connection-failed",
            "decoding-exception",
            "decoding-failed",
            "dialogue-validation-exception",
            "dialogue-validation-failed",
            "duplicate-entry",
            "empty-talk",
            "entry-count-limit",
            "entry-read-failed",
            "entry-size-limit",
            "forbidden-backslash",
            "forbidden-choice",
            "forbidden-control",
            "forbidden-invisible-format",
            "forbidden-script-scheme",
            "forbidden-url",
            "generation-duplicate-completion",
            "generation-empty-output",
            "generation-event-after-completion",
            "generation-exception",
            "generation-failed",
            "generation-missing-completion",
            "http-client-error",
            "http-error",
            "http-redirect",
            "incomplete-stream",
            "inconsistent-charset",
            "invalid-archive",
            "invalid-grammar",
            "invalid-response",
            "invalid-shell-inventory",
            "invalid-stream-event",
            "invalid-timeout",
            "invalid-unicode",
            "malformed-json",
            "malformed-control",
            "malformed-text",
            "missing-authorized-surface",
            "missing-charset",
            "missing-continuation-example",
            "missing-corpus",
            "missing-dictionary",
            "missing-identity",
            "missing-model",
            "missing-pointer-example",
            "missing-shell-inventory",
            "missing-speaker",
            "model-not-found",
            "nfkd-expansion-limit",
            "preparation-exception",
            "preparation-failed",
            "preparation-incomplete",
            "rate-limited",
            "rendering-exception",
            "request-timeout",
            "response-too-large",
            "runner-exception",
            "run-cancelled",
            "schema-invalid",
            "server-error",
            "service-unavailable",
            "similarity-exception",
            "similarity-budget-exceeded",
            "similarity-unsafe-text",
            "surface-not-allowed",
            "text-blank",
            "text-too-long",
            "transport-error",
            "turn-count",
            "unauthorized",
            "unknown-speaker",
            "unsafe-entry-name",
            "unsupported-charset",
            "unsupported-command",
            "unsupported-control",
            "wait-out-of-range",
        )
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
