package com.cattailsw.nanidroid.llmghost

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CaseStatus
import com.cattailsw.nanidroid.llmghost.model.GhostCorpusInput
import com.cattailsw.nanidroid.llmghost.model.SpikeCase
import com.cattailsw.nanidroid.llmghost.model.SpikeCaseReport
import com.cattailsw.nanidroid.llmghost.model.SpikeFailure
import com.cattailsw.nanidroid.llmghost.report.FileSpikeReportStore
import com.cattailsw.nanidroid.llmghost.report.SpikeCaseEvidence
import com.cattailsw.nanidroid.llmghost.report.SpikeRunSummary
import com.cattailsw.nanidroid.llmghost.report.RetrievedExampleEvidence
import com.cattailsw.nanidroid.llmghost.report.SpikeReportPublicationException
import com.cattailsw.nanidroid.llmghost.retrieval.CanonicalTalkRetriever
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class SpikeRunRequest(
    val corpus: GhostCorpusInput,
    val talks: List<CanonicalTalk>,
    val entryHashes: Map<String, String>,
    val endpoint: String,
    val model: String,
    val seed: Long,
    val candidateCount: Int = 1,
    val runId: String,
)

data class SpikeCaseExecution(
    val report: SpikeCaseReport,
    val retryCount: Int = 0,
)

data class SpikeRunOutcome(
    val exitCode: Int,
    val reportDirectory: Path,
)

class SpikeRunner(
    private val scenarioFactory: SpikeScenarioFactory,
    private val reportStore: FileSpikeReportStore,
    private val executeCase: suspend (SpikeCase, seed: Long) -> SpikeCaseExecution,
    private val now: () -> Instant,
    private val retriever: CanonicalTalkRetriever = CanonicalTalkRetriever(),
    private val onRecovery: (Path) -> Unit = {},
) {
    suspend fun run(request: SpikeRunRequest): SpikeRunOutcome {
        require(request.candidateCount > 0) { "At least one candidate is required." }
        val startedAt = now()
        val openRun = reportStore.beginRun(startedAt, request.runId)
        try {
            val matrix = scenarioFactory.requiredCases(request.corpus.identity, request.talks, request.seed)
            if (matrix is ScenarioMatrixResult.Failure) {
                openRun.finish(
                    SpikeRunSummary(
                        runId = request.runId,
                        endpoint = request.endpoint,
                        model = request.model,
                        startedAtUtc = startedAt.toString(),
                        cases = emptyList(),
                        preflightFailure = SpikeFailure(matrix.code, matrix.detail),
                    ),
                )
                return SpikeRunOutcome(1, openRun.directory)
            }

            val completed = mutableListOf<SpikeCaseEvidence>()
            val baseCases = (matrix as ScenarioMatrixResult.Success).cases
            for (candidate in 1..request.candidateCount) {
                for (seeded in baseCases) {
                    val caseSeed = seeded.seed + (candidate - 1L) * baseCases.size
                    val execution = try {
                        executeCase(seeded.case, caseSeed)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SpikeCaseExecution(
                            SpikeCaseReport(
                                caseId = seeded.case.caseId,
                                status = CaseStatus.FAILED,
                                rawResponse = "",
                                failure = SpikeFailure(
                                    code = "runner-exception",
                                    detail = "Case execution failed unexpectedly.",
                                ),
                            ),
                        )
                    }
                    val selected = retriever.retrieve(
                        seeded.case.request.examples,
                        seeded.case.request.scenario,
                        limit = 3,
                    )
                    val evidence = SpikeCaseEvidence(
                        corpusIdentity = request.corpus.identity,
                        entryHashes = request.entryHashes,
                        request = seeded.case.request,
                        seed = caseSeed,
                        endpoint = request.endpoint,
                        model = request.model,
                        candidate = candidate,
                        retrievedExamples = selected.map {
                            RetrievedExampleEvidence(it.talk, it.score, it.reasons)
                        },
                        report = execution.report,
                        retryCount = execution.retryCount,
                    )
                    openRun.writeCase(evidence, candidate)
                    completed += evidence
                }
            }
            openRun.finish(
                SpikeRunSummary(
                    runId = request.runId,
                    endpoint = request.endpoint,
                    model = request.model,
                    startedAtUtc = startedAt.toString(),
                    cases = completed,
                ),
            )
            return SpikeRunOutcome(
                exitCode = if (completed.all { it.report.status == CaseStatus.PASSED }) 0 else 1,
                reportDirectory = openRun.directory,
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { openRun.abort("run-cancelled") }
                    .onSuccess { recovery -> runCatching { onRecovery(recovery) } }
            }
            throw cancelled
        } catch (failure: SpikeReportPublicationException) {
            runCatching { onRecovery(failure.recoveryDirectory) }
            throw failure
        }
    }
}
