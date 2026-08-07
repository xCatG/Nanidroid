package com.cattailsw.nanidroid.llmghost

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class SpikeCliArgumentsTest {
    @Test
    fun requiresNarAndUsesDesktopDefaultsWithoutAmbientOpenAiCredential() {
        val missing = SpikeCliArguments.parse(emptyList(), mapOf("OPENAI_API_KEY" to "ambient-secret"))
        assertEquals("nar-required", assertIs<CliParseResult.Error>(missing).code)

        val parsed = assertIs<CliParseResult.Success>(
            SpikeCliArguments.parse(listOf("--nar", "ghost.nar"), mapOf("OPENAI_API_KEY" to "ambient-secret")),
        ).value
        assertEquals(Path.of("ghost.nar"), parsed.nar)
        assertEquals("http://gx10-5e5d:10101/v1", parsed.baseUrl)
        assertEquals("nemotron-3-super", parsed.model)
        assertEquals(1, parsed.candidates)
        assertNull(parsed.apiKey)
    }

    @Test
    fun parsesAllOptionsAndReadsOnlyExplicitlyNamedBearerEnvironmentVariable() {
        val parsed = assertIs<CliParseResult.Success>(
            SpikeCliArguments.parse(
                listOf(
                    "--nar", "ghost.nar",
                    "--base-url", "https://example.test/v1",
                    "--model", "model-a",
                    "--candidates", "2",
                    "--seed", "42",
                    "--stream", "false",
                    "--connect-timeout-ms", "1234",
                    "--request-timeout-ms", "5678",
                    "--report-root", "reports",
                    "--api-key-env", "CUSTOM_BEARER",
                ),
                mapOf("CUSTOM_BEARER" to "explicit-secret", "OPENAI_API_KEY" to "ambient-secret"),
            ),
        ).value
        assertEquals("explicit-secret", parsed.apiKey)
        assertEquals(2, parsed.candidates)
        assertEquals(42, parsed.seed)
        assertFalse(parsed.stream)
        assertEquals(1234, parsed.connectTimeoutMillis)
        assertEquals(5678, parsed.requestTimeoutMillis)
        assertEquals(Path.of("reports"), parsed.reportRoot)
    }

    @Test
    fun rejectsUnsafeUrlsInvalidValuesUnknownDuplicatesAndMissingValues() {
        val invalid = listOf(
            listOf("--nar", "x", "--base-url", "gx10/v1"),
            listOf("--nar", "x", "--base-url", "ftp://example.test/v1"),
            listOf("--nar", "x", "--base-url", "https://user@example.test/v1"),
            listOf("--nar", "x", "--base-url", "https://example.test/v1?q=x"),
            listOf("--nar", "x", "--base-url", "https://example.test/v1#x"),
            listOf("--nar", "x", "--candidates", "0"),
            listOf("--nar", "x", "--stream", "yes"),
            listOf("--nar", "x", "--connect-timeout-ms", "0"),
            listOf("--nar", "x", "--request-timeout-ms", "-1"),
            listOf("--nar", "x", "--wat", "1"),
            listOf("--nar", "x", "--nar", "y"),
            listOf("--nar"),
        )
        invalid.forEach { args -> assertIs<CliParseResult.Error>(SpikeCliArguments.parse(args, emptyMap())) }
    }

    @Test
    fun rejectsJvmInvalidPathsWithoutThrowingOrEchoingThem() {
        val invalidPath = "bad\u0000secret"
        val narError = assertIs<CliParseResult.Error>(
            SpikeCliArguments.parse(listOf("--nar", invalidPath), emptyMap()),
        )
        val reportError = assertIs<CliParseResult.Error>(
            SpikeCliArguments.parse(listOf("--nar", "x", "--report-root", invalidPath), emptyMap()),
        )
        assertEquals("invalid-path", narError.code)
        assertEquals("invalid-path", reportError.code)
        assertFalse(narError.message.contains("secret"))
        assertFalse(reportError.message.contains("secret"))
    }

    @Test
    fun helpAndErrorsNeverContainEnvironmentNamesOrValues() {
        val secret = "s3cr3t-value"
        val help = assertIs<CliParseResult.Help>(
            SpikeCliArguments.parse(listOf("--help"), mapOf("SECRET_NAME" to secret)),
        ).text
        val error = assertIs<CliParseResult.Error>(
            SpikeCliArguments.parse(
                listOf("--nar", "x", "--api-key-env", "SECRET_NAME", "--unknown", secret),
                mapOf("SECRET_NAME" to secret),
            ),
        )
        assertFalse(help.contains(secret))
        assertFalse(help.contains("SECRET_NAME"))
        assertFalse(error.message.contains(secret))
        assertFalse(error.message.contains("SECRET_NAME"))
    }

    @Test
    fun missingExplicitBearerVariableIsASecretFreeParseError() {
        val error = assertIs<CliParseResult.Error>(
            SpikeCliArguments.parse(listOf("--nar", "x", "--api-key-env", "MISSING_SECRET"), emptyMap()),
        )
        assertEquals("api-key-unavailable", error.code)
        assertFalse(error.message.contains("MISSING_SECRET"))
    }

    @Test
    fun processBoundaryUsesOnlyStdoutForFinalDirectory() = runBlocking {
        val output = StringBuilder()
        val errors = StringBuilder()
        val exit = executeCli(
            args = listOf("--nar", "ghost.nar"),
            environment = mapOf("OPENAI_API_KEY" to "ambient-secret"),
            stdout = output,
            stderr = errors,
            execute = {
                assertNull(it.apiKey)
                CliExecutionResult(0, Path.of("reports", "run").toAbsolutePath())
            },
        )
        assertEquals(0, exit)
        assertEquals(Path.of("reports", "run").toAbsolutePath().normalize().toString(), output.toString().trim())
        assertTrue(errors.isEmpty())
        assertFalse((output.toString() + errors).contains("ambient-secret"))
    }

    @Test
    fun processBoundaryMapsParseReportedAndCancellationStatusesWithoutStackTraces() = runBlocking {
        val parseError = StringBuilder()
        assertEquals(
            2,
            executeCli(emptyList(), emptyMap(), StringBuilder(), parseError) { error("not reached") },
        )
        assertTrue(parseError.toString().startsWith("nar-required:"))

        val reportedOutput = StringBuilder()
        val reportedError = StringBuilder()
        assertEquals(
            1,
            executeCli(listOf("--nar", "x"), emptyMap(), reportedOutput, reportedError) {
                CliExecutionResult(1, failureCode = "nar-unreadable")
            },
        )
        assertTrue(reportedOutput.isEmpty())
        assertTrue(reportedError.toString().startsWith("nar-unreadable:"))

        val cancelledError = StringBuilder()
        assertEquals(
            130,
            executeCli(listOf("--nar", "x"), emptyMap(), StringBuilder(), cancelledError) {
                throw CancellationException("synthetic-secret")
            },
        )
        assertEquals("cancelled: Spike execution was cancelled.", cancelledError.toString().trim())
    }
}
