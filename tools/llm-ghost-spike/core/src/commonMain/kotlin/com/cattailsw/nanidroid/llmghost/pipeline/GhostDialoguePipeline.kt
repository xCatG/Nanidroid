package com.cattailsw.nanidroid.llmghost.pipeline

import com.cattailsw.nanidroid.llmghost.evaluation.CanonicalSimilarity
import com.cattailsw.nanidroid.llmghost.evaluation.SimilarityFinding
import com.cattailsw.nanidroid.llmghost.generation.GeneratedDialogueDecoder
import com.cattailsw.nanidroid.llmghost.generation.GeneratedDialogueValidator
import com.cattailsw.nanidroid.llmghost.model.CaseStatus
import com.cattailsw.nanidroid.llmghost.model.CanonicalTalk
import com.cattailsw.nanidroid.llmghost.model.CompiledScriptValidationReport
import com.cattailsw.nanidroid.llmghost.model.GeneratedDialogue
import com.cattailsw.nanidroid.llmghost.model.GeneratedTurn
import com.cattailsw.nanidroid.llmghost.model.GenerationEvent
import com.cattailsw.nanidroid.llmghost.model.GenerationUsage
import com.cattailsw.nanidroid.llmghost.model.GhostModelBackend
import com.cattailsw.nanidroid.llmghost.model.ModelPreparation
import com.cattailsw.nanidroid.llmghost.model.RenderedPromptReport
import com.cattailsw.nanidroid.llmghost.model.SpikeCase
import com.cattailsw.nanidroid.llmghost.model.SpikeCaseReport
import com.cattailsw.nanidroid.llmghost.model.SpikeFailure
import com.cattailsw.nanidroid.llmghost.model.SpikeWarning
import com.cattailsw.nanidroid.llmghost.prompt.GhostPromptRenderer
import com.cattailsw.nanidroid.llmghost.sakura.CompiledScriptValidator
import com.cattailsw.nanidroid.llmghost.sakura.SakuraScriptCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext

class GhostDialoguePipeline(
    private val backend: GhostModelBackend,
    private val renderer: GhostPromptRenderer = GhostPromptRenderer(),
    private val decoder: GeneratedDialogueDecoder = GeneratedDialogueDecoder(),
    private val validator: GeneratedDialogueValidator = GeneratedDialogueValidator(),
    private val compiler: SakuraScriptCompiler = SakuraScriptCompiler(),
    private val nowMillis: () -> Long,
    private val similarityEvaluator: (
        generatedTurns: List<GeneratedTurn>,
        canonicalTalks: List<CanonicalTalk>,
    ) -> List<SimilarityFinding> = CanonicalSimilarity::evaluate,
) {
    suspend fun runCase(case: SpikeCase): SpikeCaseReport {
        val startedAtMillis = nowMillis()
        val preparationEvents = mutableListOf<ModelPreparation>()
        val generationEvents = mutableListOf<GenerationEvent>()
        val rawResponse = StringBuilder()
        var renderedPrompt = RenderedPromptReport()
        var stage = PipelineStage.RENDERING
        var usage: GenerationUsage? = null

        try {
            val prompt = renderer.render(case.request)
            renderedPrompt = RenderedPromptReport(
                system = prompt.system,
                user = prompt.user,
                selectedExampleIds = prompt.selectedExamples.map { it.talk.id },
            )

            stage = PipelineStage.PREPARATION
            backend.prepare()
                .takeWhile { event ->
                    preparationEvents += event
                    event !is ModelPreparation.Failed
                }
                .collect()
            val preparationFailure = preparationEvents
                .filterIsInstance<ModelPreparation.Failed>()
                .firstOrNull()
            if (preparationFailure != null) {
                return failureReport(
                    case = case,
                    startedAtMillis = startedAtMillis,
                    renderedPrompt = renderedPrompt,
                    rawResponse = rawResponse.toString(),
                    preparationEvents = preparationEvents,
                    generationEvents = generationEvents,
                    code = "preparation-failed",
                    detail = preparationFailure.detail,
                    sourceCode = preparationFailure.code,
                )
            }
            if (preparationEvents.none { it is ModelPreparation.Ready }) {
                return failureReport(
                    case = case,
                    startedAtMillis = startedAtMillis,
                    renderedPrompt = renderedPrompt,
                    rawResponse = rawResponse.toString(),
                    preparationEvents = preparationEvents,
                    generationEvents = generationEvents,
                    code = "preparation-incomplete",
                    detail = "Model preparation did not become ready.",
                )
            }

            stage = PipelineStage.GENERATION
            backend.generate(case.request)
                .takeWhile { event ->
                    generationEvents += event
                    when (event) {
                        is GenerationEvent.TextDelta -> rawResponse.append(event.text)
                        is GenerationEvent.Completed -> usage = event.usage
                        is GenerationEvent.Failed -> Unit
                    }
                    event !is GenerationEvent.Failed
                }
                .collect()

            val completionIndexes = generationEvents.mapIndexedNotNull { index, event ->
                index.takeIf { event is GenerationEvent.Completed }
            }
            if (completionIndexes.size > 1) {
                return lifecycleFailure(
                    case,
                    startedAtMillis,
                    renderedPrompt,
                    rawResponse.toString(),
                    preparationEvents,
                    generationEvents,
                    "generation-duplicate-completion",
                    "Generation emitted more than one completion event.",
                    usage,
                )
            }
            if (completionIndexes.singleOrNull()?.let { it != generationEvents.lastIndex } == true) {
                return lifecycleFailure(
                    case,
                    startedAtMillis,
                    renderedPrompt,
                    rawResponse.toString(),
                    preparationEvents,
                    generationEvents,
                    "generation-event-after-completion",
                    "Generation emitted an event after completion.",
                    usage,
                )
            }
            val generationFailure = generationEvents
                .filterIsInstance<GenerationEvent.Failed>()
                .firstOrNull()
            if (generationFailure != null) {
                return failureReport(
                    case = case,
                    startedAtMillis = startedAtMillis,
                    renderedPrompt = renderedPrompt,
                    rawResponse = rawResponse.toString(),
                    preparationEvents = preparationEvents,
                    generationEvents = generationEvents,
                    code = "generation-failed",
                    detail = generationFailure.detail,
                    sourceCode = generationFailure.code,
                    usage = usage,
                )
            }
            if (completionIndexes.isEmpty()) {
                return lifecycleFailure(
                    case,
                    startedAtMillis,
                    renderedPrompt,
                    rawResponse.toString(),
                    preparationEvents,
                    generationEvents,
                    "generation-missing-completion",
                    "Generation ended without a completion event.",
                    usage,
                )
            }
            if (rawResponse.isEmpty()) {
                return lifecycleFailure(
                    case,
                    startedAtMillis,
                    renderedPrompt,
                    rawResponse.toString(),
                    preparationEvents,
                    generationEvents,
                    "generation-empty-output",
                    "Generation completed without text.",
                    usage,
                )
            }

            stage = PipelineStage.DECODING
            val decodeResult = decoder.decode(rawResponse.toString())
            val decoded = decodeResult.dialogue
                ?: return failureReport(
                    case = case,
                    startedAtMillis = startedAtMillis,
                    renderedPrompt = renderedPrompt,
                    rawResponse = rawResponse.toString(),
                    preparationEvents = preparationEvents,
                    generationEvents = generationEvents,
                    code = "decoding-failed",
                    detail = decodeResult.error?.detail ?: "Dialogue decoding failed.",
                    sourceCode = decodeResult.error?.code,
                    usage = usage,
                )
            stage = PipelineStage.VALIDATION
            val validation = validator.validate(decoded, case.request.validSurfaces)
            val trusted = validation.dialogue
                ?: return failureReport(
                    case = case,
                    startedAtMillis = startedAtMillis,
                    renderedPrompt = renderedPrompt,
                    rawResponse = rawResponse.toString(),
                    preparationEvents = preparationEvents,
                    generationEvents = generationEvents,
                    code = "dialogue-validation-failed",
                    detail = validation.violations.joinToString { it.detail },
                    sourceCode = validation.violations.firstOrNull()?.code,
                    usage = usage,
                    generatedDialogue = decoded,
                )
            stage = PipelineStage.COMPILATION
            val compilation = compiler.compile(trusted)
            val compiledValidation = CompiledScriptValidator.validate(compilation.script)
            val compiledReport = CompiledScriptValidationReport(
                valid = compiledValidation.violations.isEmpty(),
                violationCodes = compiledValidation.violations.map { it.code },
            )
            if (!compiledReport.valid) {
                return failureReport(
                    case = case,
                    startedAtMillis = startedAtMillis,
                    renderedPrompt = renderedPrompt,
                    rawResponse = rawResponse.toString(),
                    preparationEvents = preparationEvents,
                    generationEvents = generationEvents,
                    code = "compiled-script-invalid",
                    detail = "Compiled SakuraScript failed validation.",
                    sourceCode = compiledReport.violationCodes.firstOrNull(),
                    usage = usage,
                    generatedDialogue = decoded,
                    compiledSakuraScript = compilation.script,
                    compiledScriptValidation = compiledReport,
                )
            }

            stage = PipelineStage.SIMILARITY
            val findings = similarityEvaluator(decoded.turns, case.request.examples)
            val exactCopy = findings.firstOrNull { it.exact }
            if (exactCopy != null) {
                return failureReport(
                    case = case,
                    startedAtMillis = startedAtMillis,
                    renderedPrompt = renderedPrompt,
                    rawResponse = rawResponse.toString(),
                    preparationEvents = preparationEvents,
                    generationEvents = generationEvents,
                    code = "canonical-exact-copy",
                    detail = "Generated text exactly matches canonical talk ${exactCopy.canonicalTalkId} after normalization.",
                    usage = usage,
                    generatedDialogue = decoded,
                    compiledSakuraScript = compilation.script,
                    compiledScriptValidation = compiledReport,
                    similarityFindings = findings,
                )
            }
            val warnings = findings
                .filter { it.ratio >= CanonicalSimilarity.NEAR_COPY_THRESHOLD }
                .map { finding ->
                    SpikeWarning(
                        code = "canonical-near-copy",
                        detail = "Generated text is near canonical talk ${finding.canonicalTalkId} " +
                            "(ratio=${finding.ratio}).",
                    )
                }
            return SpikeCaseReport(
                caseId = case.caseId,
                status = CaseStatus.PASSED,
                rawResponse = rawResponse.toString(),
                compiledSakuraScript = compilation.script,
                renderedPrompt = renderedPrompt,
                startedAtMillis = startedAtMillis,
                elapsedMillis = elapsedSince(startedAtMillis),
                preparationEvents = preparationEvents,
                generationEvents = generationEvents,
                usage = usage,
                generatedDialogue = decoded,
                similarityFindings = findings,
                warnings = warnings,
                compiledScriptValidation = compiledReport,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            return failureReport(
                case = case,
                startedAtMillis = startedAtMillis,
                renderedPrompt = renderedPrompt,
                rawResponse = rawResponse.toString(),
                preparationEvents = preparationEvents,
                generationEvents = generationEvents,
                code = stage.exceptionCode,
                detail = exception.message ?: "Pipeline failed.",
                usage = usage,
            )
        } finally {
            try {
                withContext(NonCancellable) { backend.close() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // A completed case report remains useful even if backend cleanup fails.
            }
        }
    }

    private fun failureReport(
        case: SpikeCase,
        startedAtMillis: Long,
        renderedPrompt: RenderedPromptReport,
        rawResponse: String,
        preparationEvents: List<ModelPreparation>,
        generationEvents: List<GenerationEvent>,
        code: String,
        detail: String,
        sourceCode: String? = null,
        usage: GenerationUsage? = null,
        generatedDialogue: GeneratedDialogue? = null,
        compiledSakuraScript: String? = null,
        compiledScriptValidation: CompiledScriptValidationReport? = null,
        similarityFindings: List<SimilarityFinding> = emptyList(),
        warnings: List<SpikeWarning> = emptyList(),
    ) = SpikeCaseReport(
        caseId = case.caseId,
        status = CaseStatus.FAILED,
        rawResponse = rawResponse,
        compiledSakuraScript = compiledSakuraScript,
        renderedPrompt = renderedPrompt,
        startedAtMillis = startedAtMillis,
        elapsedMillis = elapsedSince(startedAtMillis),
        preparationEvents = preparationEvents,
        generationEvents = generationEvents,
        usage = usage,
        generatedDialogue = generatedDialogue,
        similarityFindings = similarityFindings,
        warnings = warnings,
        compiledScriptValidation = compiledScriptValidation,
        failure = SpikeFailure(code, detail, sourceCode),
    )

    private fun lifecycleFailure(
        case: SpikeCase,
        startedAtMillis: Long,
        renderedPrompt: RenderedPromptReport,
        rawResponse: String,
        preparationEvents: List<ModelPreparation>,
        generationEvents: List<GenerationEvent>,
        code: String,
        detail: String,
        usage: GenerationUsage?,
    ) = failureReport(
        case = case,
        startedAtMillis = startedAtMillis,
        renderedPrompt = renderedPrompt,
        rawResponse = rawResponse,
        preparationEvents = preparationEvents,
        generationEvents = generationEvents,
        code = code,
        detail = detail,
        usage = usage,
    )

    private fun elapsedSince(startedAtMillis: Long): Long =
        (nowMillis() - startedAtMillis).coerceAtLeast(0)

    private enum class PipelineStage(val exceptionCode: String) {
        RENDERING("rendering-exception"),
        PREPARATION("preparation-exception"),
        GENERATION("generation-exception"),
        DECODING("decoding-exception"),
        VALIDATION("dialogue-validation-exception"),
        COMPILATION("compilation-exception"),
        SIMILARITY("similarity-exception"),
    }
}
