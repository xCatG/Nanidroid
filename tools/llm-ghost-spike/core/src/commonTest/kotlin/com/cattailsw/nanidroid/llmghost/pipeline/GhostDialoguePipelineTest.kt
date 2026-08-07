package com.cattailsw.nanidroid.llmghost.pipeline

import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CanonicalTurn
import com.cattailsw.nanidroid.llmghost.model.CaseStatus
import com.cattailsw.nanidroid.llmghost.model.GenerationEvent
import com.cattailsw.nanidroid.llmghost.model.GenerationScenario
import com.cattailsw.nanidroid.llmghost.model.GenerationUsage
import com.cattailsw.nanidroid.llmghost.model.GhostGenerationRequest
import com.cattailsw.nanidroid.llmghost.model.GhostModelBackend
import com.cattailsw.nanidroid.llmghost.model.GhostSpeakerId
import com.cattailsw.nanidroid.llmghost.model.ModelCapabilities
import com.cattailsw.nanidroid.llmghost.model.ModelPreparation
import com.cattailsw.nanidroid.llmghost.model.OutputLanguage
import com.cattailsw.nanidroid.llmghost.model.ScenarioKind
import com.cattailsw.nanidroid.llmghost.model.SpikeCase
import com.cattailsw.nanidroid.llmghost.model.SpikeCaseReport
import com.cattailsw.nanidroid.llmghost.model.SpikeJson
import com.cattailsw.nanidroid.llmghost.model.TalkCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhostDialoguePipelineTest {
    @Test
    fun report_keeps_prompt_raw_output_script_validation_and_timing() = runBlocking {
        val usage = GenerationUsage(promptTokens = 10, completionTokens = 8, totalTokens = 18)
        val backend = EventBackend(
            generationEvents = listOf(
                GenerationEvent.TextDelta("{\"turns\":[{\"speaker\":\"sakura\","),
                GenerationEvent.TextDelta("\"surface\":0,\"text\":\"A fresh thought\",\"waitAfterMs\":25}]}"),
                GenerationEvent.Completed(usage),
            ),
        )
        val clock = SequenceClock(1_000, 1_075)

        val report = GhostDialoguePipeline(backend = backend, nowMillis = clock::now)
            .runCase(spikeCase())

        assertEquals(CaseStatus.PASSED, report.status)
        assertTrue(report.renderedPrompt.user.isNotBlank())
        assertTrue(report.rawResponse.startsWith("{"))
        assertTrue(assertNotNull(report.compiledSakuraScript).endsWith("\\e"))
        assertEquals(1_000, report.startedAtMillis)
        assertEquals(75, report.elapsedMillis)
        assertEquals(usage, report.usage)
        assertEquals(1, report.preparationEvents.size)
        assertEquals(3, report.generationEvents.size)
        assertEquals(1, assertNotNull(report.generatedDialogue).turns.size)
        assertTrue(assertNotNull(report.compiledScriptValidation).valid)
        assertTrue(report.similarityFindings.none { it.exact })
        assertTrue(report.warnings.isEmpty())
        assertNull(report.failure)
        assertTrue(SpikeJson.encodeToString<SpikeCaseReport>(report).contains("A fresh thought"))
        assertTrue(backend.closed)
    }

    @Test
    fun completion_without_text_has_a_stable_failure_code() = runBlocking {
        val report = pipeline(
            EventBackend(generationEvents = listOf(GenerationEvent.Completed(null))),
        ).runCase(spikeCase())

        assertFailure(report, "generation-empty-output")
        assertEquals("", report.rawResponse)
        assertEquals(1, report.generationEvents.size)
    }

    @Test
    fun backend_error_after_partial_text_preserves_the_partial_response() = runBlocking {
        val report = pipeline(
            EventBackend(
                generationEvents = listOf(
                    GenerationEvent.TextDelta("{\"turns\":["),
                    GenerationEvent.Failed("quota-exhausted", "No quota remains."),
                ),
            ),
        ).runCase(spikeCase())

        assertFailure(report, "generation-failed", sourceCode = "quota-exhausted")
        assertEquals("{\"turns\":[", report.rawResponse)
        assertEquals(2, report.generationEvents.size)
    }

    @Test
    fun duplicate_completion_is_rejected_before_decoding() = runBlocking {
        val report = pipeline(
            EventBackend(
                generationEvents = listOf(
                    GenerationEvent.TextDelta(validJson("A fresh thought")),
                    GenerationEvent.Completed(null),
                    GenerationEvent.Completed(null),
                ),
            ),
        ).runCase(spikeCase())

        assertFailure(report, "generation-duplicate-completion")
        assertNull(report.generatedDialogue)
    }

    @Test
    fun missing_completion_is_rejected_even_when_json_is_complete() = runBlocking {
        val report = pipeline(
            EventBackend(
                generationEvents = listOf(GenerationEvent.TextDelta(validJson("A fresh thought"))),
            ),
        ).runCase(spikeCase())

        assertFailure(report, "generation-missing-completion")
        assertEquals(validJson("A fresh thought"), report.rawResponse)
    }

    @Test
    fun preparation_failure_skips_generation_and_preserves_backend_code() = runBlocking {
        val backend = EventBackend(
            preparationEvents = flowOf(
                ModelPreparation.Downloading(40),
                ModelPreparation.Failed("model-unavailable", "No compatible model."),
            ),
            generationEvents = listOf(GenerationEvent.TextDelta("must not run")),
        )

        val report = pipeline(backend).runCase(spikeCase())

        assertFailure(report, "preparation-failed", sourceCode = "model-unavailable")
        assertFalse(backend.generationRequested)
        assertEquals(2, report.preparationEvents.size)
    }

    @Test
    fun preparation_exception_has_a_stage_specific_failure_code() = runBlocking {
        val backend = EventBackend(
            preparationEvents = flow { throw IllegalStateException("adapter broke") },
            generationEvents = emptyList(),
        )

        val report = pipeline(backend).runCase(spikeCase())

        assertFailure(report, "preparation-exception")
        assertTrue(assertNotNull(report.failure).detail.contains("adapter broke"))
    }

    @Test
    fun preparation_failure_stops_collection_before_the_backend_throws() = runBlocking {
        var continuedAfterFailure = false
        val backend = EventBackend(
            preparationEvents = flow {
                emit(ModelPreparation.Downloading(75))
                emit(ModelPreparation.Failed("prepare-terminal", "Preparation stopped."))
                continuedAfterFailure = true
                throw IllegalStateException("must not replace terminal failure")
            },
            generationEvents = emptyList(),
        )

        val report = pipeline(backend).runCase(spikeCase())

        assertFailure(report, "preparation-failed", sourceCode = "prepare-terminal")
        assertFalse(continuedAfterFailure)
        assertEquals(2, report.preparationEvents.size)
    }

    @Test
    fun generation_cancellation_propagates_and_still_closes_the_backend() {
        val backend = EventBackend(
            generationEvents = listOf(GenerationEvent.TextDelta("partial")),
            generationFailure = CancellationException("cancelled by caller"),
        )

        assertFailsWith<CancellationException> {
            runBlocking { pipeline(backend).runCase(spikeCase()) }
        }
        assertTrue(backend.closed)
    }

    @Test
    fun cancellation_from_backend_close_is_not_converted_to_a_report() {
        val backend = EventBackend(
            generationEvents = listOf(
                GenerationEvent.TextDelta(validJson("A fresh thought")),
                GenerationEvent.Completed(null),
            ),
            closeFailure = CancellationException("cleanup cancelled"),
        )

        assertFailsWith<CancellationException> {
            runBlocking { pipeline(backend).runCase(spikeCase()) }
        }
        assertTrue(backend.closed)
    }

    @Test
    fun ordinary_generation_exception_preserves_partial_output() = runBlocking {
        val backend = EventBackend(
            generationEvents = listOf(GenerationEvent.TextDelta("partial")),
            generationFailure = IllegalStateException("adapter disconnected"),
        )

        val report = pipeline(backend).runCase(spikeCase())

        assertFailure(report, "generation-exception")
        assertEquals("partial", report.rawResponse)
    }

    @Test
    fun generation_failure_stops_collection_before_the_backend_throws() = runBlocking {
        val backend = EventBackend(
            generationEvents = listOf(
                GenerationEvent.TextDelta("partial"),
                GenerationEvent.Failed("generation-terminal", "Generation stopped."),
            ),
            generationFailure = IllegalStateException("must not replace terminal failure"),
        )

        val report = pipeline(backend).runCase(spikeCase())

        assertFailure(report, "generation-failed", sourceCode = "generation-terminal")
        assertEquals("partial", report.rawResponse)
        assertEquals(2, report.generationEvents.size)
    }

    @Test
    fun events_after_generation_failure_are_not_accepted() = runBlocking {
        val backend = EventBackend(
            generationEvents = listOf(
                GenerationEvent.TextDelta("partial"),
                GenerationEvent.Failed("generation-terminal", "Generation stopped."),
                GenerationEvent.TextDelta("must-not-be-collected"),
                GenerationEvent.Completed(null),
            ),
        )

        val report = pipeline(backend).runCase(spikeCase())

        assertFailure(report, "generation-failed", sourceCode = "generation-terminal")
        assertEquals("partial", report.rawResponse)
        assertEquals(2, report.generationEvents.size)
    }

    @Test
    fun text_after_completion_is_rejected_as_an_invalid_event_sequence() = runBlocking {
        val backend = EventBackend(
            generationEvents = listOf(
                GenerationEvent.TextDelta(validJson("A fresh thought")),
                GenerationEvent.Completed(null),
                GenerationEvent.TextDelta("trailing"),
            ),
        )

        val report = pipeline(backend).runCase(spikeCase())

        assertFailure(report, "generation-event-after-completion")
        assertTrue(report.rawResponse.endsWith("trailing"))
    }

    @Test
    fun failure_after_completion_is_classified_as_an_invalid_event_sequence() = runBlocking {
        val backend = EventBackend(
            generationEvents = listOf(
                GenerationEvent.TextDelta(validJson("A fresh thought")),
                GenerationEvent.Completed(null),
                GenerationEvent.Failed("late-error", "Failure arrived after completion."),
            ),
        )

        val report = pipeline(backend).runCase(spikeCase())

        assertFailure(report, "generation-event-after-completion")
    }

    @Test
    fun malformed_completed_output_is_classified_as_decoding_failure() = runBlocking {
        val report = pipeline(
            EventBackend(
                generationEvents = listOf(
                    GenerationEvent.TextDelta("{broken"),
                    GenerationEvent.Completed(null),
                ),
            ),
        ).runCase(spikeCase())

        assertFailure(report, "decoding-failed", sourceCode = "malformed-json")
    }

    @Test
    fun unauthorized_surface_is_classified_as_dialogue_validation_failure() = runBlocking {
        val report = pipeline(
            EventBackend(
                generationEvents = listOf(
                    GenerationEvent.TextDelta(validJson("A fresh thought", surface = 99)),
                    GenerationEvent.Completed(null),
                ),
            ),
        ).runCase(spikeCase())

        assertFailure(report, "dialogue-validation-failed", sourceCode = "surface-not-allowed")
        assertNotNull(report.generatedDialogue)
        assertNull(report.compiledSakuraScript)
    }

    @Test
    fun normalized_exact_canonical_copy_fails_after_safe_compilation() = runBlocking {
        val report = pipeline(
            EventBackend(
                generationEvents = listOf(
                    GenerationEvent.TextDelta(validJson("hello world。")),
                    GenerationEvent.Completed(null),
                ),
            ),
        ).runCase(spikeCase(canonicalText = "Hello,\u3000WORLD!"))

        assertFailure(report, "canonical-exact-copy")
        assertTrue(report.similarityFindings.single().exact)
        assertNotNull(report.compiledSakuraScript)
        assertTrue(assertNotNull(report.compiledScriptValidation).valid)
    }

    @Test
    fun near_canonical_copy_passes_with_a_warning() = runBlocking {
        val canonical = "12345678901234567890"
        val generated = "123456789012345678XX"
        val report = pipeline(
            EventBackend(
                generationEvents = listOf(
                    GenerationEvent.TextDelta(validJson(generated)),
                    GenerationEvent.Completed(null),
                ),
            ),
        ).runCase(spikeCase(canonicalText = canonical))

        assertEquals(CaseStatus.PASSED, report.status)
        assertEquals("canonical-near-copy", report.warnings.single().code)
        assertFalse(report.similarityFindings.single().exact)
        assertEquals(0.9, report.similarityFindings.single().ratio, absoluteTolerance = 0.000_001)
    }

    @Test
    fun downstream_exception_after_completion_preserves_usage() = runBlocking {
        val usage = GenerationUsage(promptTokens = 3, completionTokens = 5, totalTokens = 8)
        val backend = EventBackend(
            generationEvents = listOf(
                GenerationEvent.TextDelta(validJson("A fresh thought")),
                GenerationEvent.Completed(usage),
            ),
        )
        val report = GhostDialoguePipeline(
            backend = backend,
            nowMillis = SequenceClock(4_000, 4_010)::now,
            similarityEvaluator = { _, _ -> throw IllegalStateException("similarity broke") },
        ).runCase(spikeCase())

        assertFailure(report, "similarity-exception")
        assertEquals(usage, report.usage)
    }

    private fun pipeline(backend: GhostModelBackend) = GhostDialoguePipeline(
        backend = backend,
        nowMillis = SequenceClock(2_000, 2_010)::now,
    )

    private fun assertFailure(
        report: SpikeCaseReport,
        code: String,
        sourceCode: String? = null,
    ) {
        assertEquals(CaseStatus.FAILED, report.status)
        val failure = assertNotNull(report.failure)
        assertEquals(code, failure.code)
        assertEquals(sourceCode, failure.sourceCode)
    }

    private fun validJson(text: String, surface: Int = 0): String =
        "{\"turns\":[{\"speaker\":\"sakura\",\"surface\":$surface," +
            "\"text\":\"$text\",\"waitAfterMs\":25}]}"

    private fun spikeCase(canonicalText: String = "How about the weather?"): SpikeCase = SpikeCase(
        caseId = "idle-english",
        request = GhostGenerationRequest(
            scenario = GenerationScenario(ScenarioKind.IDLE, topic = "weather"),
            language = OutputLanguage.ENGLISH,
            examples = listOf(
                CanonicalTalk(
                    id = "canonical-1",
                    sourcePath = "dic/talk.txt",
                    sourceLine = 1,
                    heading = null,
                    category = TalkCategory.IDLE,
                    turns = listOf(CanonicalTurn(GhostSpeakerId.SAKURA, 0, canonicalText)),
                ),
            ),
            validSurfaces = mapOf(
                GhostSpeakerId.SAKURA to setOf(0),
                GhostSpeakerId.KERO to setOf(1),
            ),
        ),
    )

    private class EventBackend(
        private val preparationEvents: Flow<ModelPreparation> = flowOf(ModelPreparation.Ready),
        private val generationEvents: List<GenerationEvent>,
        private val generationFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
    ) : GhostModelBackend {
        override val capabilities = ModelCapabilities(streaming = true, structuredOutput = false)
        var closed = false
        var generationRequested = false

        override fun prepare(): Flow<ModelPreparation> = preparationEvents

        override fun generate(request: GhostGenerationRequest): Flow<GenerationEvent> = flow {
            generationRequested = true
            generationEvents.forEach { emit(it) }
            generationFailure?.let { throw it }
        }

        override suspend fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }

    private class SequenceClock(vararg values: Long) {
        private val remaining = values.toMutableList()

        fun now(): Long = remaining.removeAt(0)
    }
}
