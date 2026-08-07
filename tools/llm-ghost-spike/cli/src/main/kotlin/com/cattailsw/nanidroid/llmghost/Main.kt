package com.cattailsw.nanidroid.llmghost

import com.cattailsw.nanidroid.llmghost.archive.NarCorpusLoader
import com.cattailsw.nanidroid.llmghost.archive.NarLoadResult
import com.cattailsw.nanidroid.llmghost.corpus.SatoriTalkExtractor
import com.cattailsw.nanidroid.llmghost.openai.OpenAiBackendConfig
import com.cattailsw.nanidroid.llmghost.openai.OpenAiCompatibleBackend
import com.cattailsw.nanidroid.llmghost.openai.createOpenAiHttpClient
import com.cattailsw.nanidroid.llmghost.pipeline.GhostDialoguePipeline
import com.cattailsw.nanidroid.llmghost.report.FileSpikeReportStore
import com.cattailsw.nanidroid.llmghost.report.SpikeReportPublicationException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

data class CliExecutionResult(
    val exitCode: Int,
    val reportDirectory: Path? = null,
    val failureCode: String? = null,
)

fun main(args: Array<String>) {
    val exitCode = runBlocking {
        val rootJob = coroutineContext[Job]!!
        val shutdownHook = Thread({ rootJob.cancel(CancellationException("Process shutdown requested.")) }, "llm-ghost-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            executeCli(
                args = args.toList(),
                environment = System.getenv(),
                stdout = System.out,
                stderr = System.err,
                execute = { runConfiguredSpike(it, System.err) },
            )
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }
    exitProcess(exitCode)
}

internal suspend fun executeCli(
    args: List<String>,
    environment: Map<String, String>,
    stdout: Appendable,
    stderr: Appendable,
    execute: suspend (SpikeCliConfig) -> CliExecutionResult,
): Int = when (val parsed = SpikeCliArguments.parse(args, environment)) {
    is CliParseResult.Help -> {
        stdout.appendLine(parsed.text)
        0
    }
    is CliParseResult.Error -> {
        stderr.appendLine("${parsed.code}: ${parsed.message}")
        2
    }
    is CliParseResult.Success -> {
        try {
            val result = execute(parsed.value)
            result.reportDirectory?.toAbsolutePath()?.normalize()?.let { stdout.appendLine(it.toString()) }
            result.failureCode?.let { stderr.appendLine("$it: Spike execution did not complete successfully.") }
            result.exitCode
        } catch (_: CancellationException) {
            stderr.appendLine("cancelled: Spike execution was cancelled.")
            130
        } catch (failure: SpikeReportPublicationException) {
            stdout.appendLine(failure.recoveryDirectory.toAbsolutePath().normalize().toString())
            stderr.appendLine("${failure.failureCode}: Report publication failed; recovery evidence was preserved.")
            1
        } catch (_: Exception) {
            stderr.appendLine("spike-failed: Spike execution failed unexpectedly.")
            1
        }
    }
}

private suspend fun runConfiguredSpike(config: SpikeCliConfig, stderr: Appendable): CliExecutionResult {
    if (!Files.isRegularFile(config.nar) || !Files.isReadable(config.nar)) {
        return CliExecutionResult(1, failureCode = "nar-unreadable")
    }
    stderr.appendLine("Loading read-only NAR corpus...")
    val loaded = when (val result = NarCorpusLoader().load(config.nar)) {
        is NarLoadResult.Success -> result
        is NarLoadResult.Failure -> return CliExecutionResult(1, failureCode = result.code)
    }
    val extraction = SatoriTalkExtractor().extract(loaded.input)
    stderr.appendLine("Extracted ${extraction.talks.size} canonical talks; running the scenario matrix...")

    val client = createOpenAiHttpClient()
    try {
        val runner = SpikeRunner(
            scenarioFactory = SpikeScenarioFactory,
            reportStore = FileSpikeReportStore(config.reportRoot.toAbsolutePath().normalize()),
            executeCase = { case, seed ->
                val backend = OpenAiCompatibleBackend(
                    client = client,
                    config = OpenAiBackendConfig(
                        baseUrl = config.baseUrl,
                        model = config.model,
                        apiKey = config.apiKey,
                        streaming = config.stream,
                        connectTimeoutMillis = config.connectTimeoutMillis,
                        requestTimeoutMillis = config.requestTimeoutMillis,
                        seed = seed,
                    ),
                )
                try {
                    val report = GhostDialoguePipeline(
                        backend = backend,
                        nowMillis = System::currentTimeMillis,
                    ).runCase(case)
                    SpikeCaseExecution(report, retryCount = backend.retryCount)
                } finally {
                    backend.close()
                }
            },
            now = Instant::now,
        )
        val outcome = runner.run(
            SpikeRunRequest(
                corpus = loaded.input,
                talks = extraction.talks,
                entryHashes = loaded.entryHashes,
                endpoint = config.baseUrl,
                model = config.model,
                seed = config.seed,
                candidateCount = config.candidates,
                runId = UUID.randomUUID().toString(),
            ),
        )
        return CliExecutionResult(outcome.exitCode, outcome.reportDirectory)
    } finally {
        client.close()
    }
}
