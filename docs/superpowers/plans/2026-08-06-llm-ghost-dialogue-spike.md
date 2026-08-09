# Corpus-Conditioned LLM Ghost Dialogue Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone pure-Kotlin spike that reads a supplied 2elf NAR, retrieves authored character examples, asks an OpenAI-compatible model for Japanese and English dialogue, deterministically compiles safe SakuraScript, and writes reviewable reports.

**Architecture:** A Kotlin Multiplatform `core` project owns decoded ghost inputs, SATORI extraction, retrieval, prompting, backend-neutral generation contracts, validation, SakuraScript compilation, similarity checks, and report models. A Kotlin/JVM `cli` project owns ZIP and charset APIs, Ktor/CIO HTTP, filesystem reports, scenario orchestration, and process exit codes; it is invoked as a standalone build and never joins Nanidroid's production Android build.

**Tech Stack:** Kotlin 2.3.21, Gradle 9.5, Kotlin Multiplatform with a JVM compilation target, kotlinx.coroutines 1.11.0, kotlinx.serialization 1.11.0, Ktor Client/CIO 3.5.1, kotlin.test, and JDK `ZipFile`/`HttpServer` test fixtures.

## Global Constraints

- Keep all implementation and tests in Kotlin; add no Java, Python, or custom native code.
- Keep `tools/llm-ghost-spike/` out of Nanidroid's root `settings.gradle.kts` and production APK dependency graph.
- Keep `core/src/commonMain` free of Android, JVM, Java, filesystem, archive, charset, and HTTP types in its public API.
- Treat the supplied NAR as read-only external input; never commit, modify, or redistribute `2elf-2.46.nar`.
- Use `nemotron-3-super` and `http://gx10-5e5d:10101/v1` only as configurable defaults for desktop experimentation; never commit credentials.
- Keep the experimental cleartext endpoint desktop-only; do not change Nanidroid's Android HTTPS policy.
- Do not change production SHIORI selection, renderer, event routing, SakuraScript playback, or Android networking in this spike.
- Track production migration from `HttpsURLConnection` separately in [GitHub issue #259](https://github.com/xCatG/Nanidroid/issues/259).
- Accept one through eight generated turns, at most 500 Unicode code points per turn, and waits from 0 through 2,000 milliseconds.
- Accept only Sakura/Kero speakers and surfaces both observed for that speaker in the canonical corpus and present in the installed shell.
- Never execute SATORI variables, SAORI calls, URLs, choices, or model-authored SakuraScript.
- Normal unit tests must be hermetic; the real endpoint is called only by an explicit CLI invocation.

---

## File Map

The standalone build contains `settings.gradle.kts`, a shared `build.gradle.kts`, `core/build.gradle.kts`, and `cli/build.gradle.kts`.

Portable files under `core/src/commonMain/kotlin/com/cattailsw/nanidroid/llmghost/`:

- `model/GhostCorpus.kt` — decoded source, identity, canonical talk, speaker, scenario, and language values.
- `model/Generation.kt` — backend lifecycle and structured generated-turn values.
- `model/SpikeReport.kt` — serializable report and failure values.
- `corpus/SatoriTalkExtractor.kt` — safe extraction parser over decoded dictionary text.
- `retrieval/CanonicalTalkRetriever.kt` — deterministic ranking and diversity.
- `prompt/GhostPromptRenderer.kt` — Japanese/English structured-output prompts.
- `generation/GeneratedDialogueDecoder.kt` and `GeneratedDialogueValidator.kt` — strict decoding and safety checks.
- `sakura/SakuraScriptCompiler.kt` and `CompiledScriptValidator.kt` — deterministic compilation and emitted-subset round trip.
- `evaluation/CanonicalSimilarity.kt` — exact and normalized-copy warnings.
- `pipeline/GhostDialoguePipeline.kt` — one-case orchestration independent of transport and files.

Desktop files under `cli/src/main/kotlin/com/cattailsw/nanidroid/llmghost/`:

- `archive/NarCorpusLoader.kt` — ZIP reads, charset detection, descriptor identity, and surface inventory.
- `openai/OpenAiCompatibleBackend.kt` and `OpenAiWireModels.kt` — Ktor request/response and streaming normalization.
- `report/FileSpikeReportStore.kt` — immutable JSON and Markdown reports.
- `SpikeCliArguments.kt`, `SpikeScenarioFactory.kt`, `SpikeRunner.kt`, and `Main.kt` — CLI configuration and orchestration.

---

### Task 1: Standalone KMP Build and Portable Contracts

**Files:**
- Create: `tools/llm-ghost-spike/settings.gradle.kts`
- Create: `tools/llm-ghost-spike/build.gradle.kts`
- Create: `tools/llm-ghost-spike/core/build.gradle.kts`
- Create: `tools/llm-ghost-spike/cli/build.gradle.kts`
- Create: `tools/llm-ghost-spike/core/src/commonMain/kotlin/com/cattailsw/nanidroid/llmghost/model/GhostCorpus.kt`
- Create: `tools/llm-ghost-spike/core/src/commonMain/kotlin/com/cattailsw/nanidroid/llmghost/model/Generation.kt`
- Create: `tools/llm-ghost-spike/core/src/commonMain/kotlin/com/cattailsw/nanidroid/llmghost/model/SpikeReport.kt`
- Test: `tools/llm-ghost-spike/core/src/commonTest/kotlin/com/cattailsw/nanidroid/llmghost/model/PortableContractsTest.kt`

**Interfaces:**
- Produces: `GhostSourceFile`, `GhostIdentity`, `GhostCorpusInput`, `CanonicalTalk`, `GenerationScenario`, `GhostGenerationRequest`, `GhostModelBackend`, `GeneratedDialogue`, and `SpikeCaseReport`.
- Produces: standalone commands `gradlew -p tools/llm-ghost-spike :core:jvmTest` and `:cli:test`.

- [ ] **Step 1: Write the failing serialization contract test**

```kotlin
@Test
fun request_round_trips_through_common_json() {
    val request = GhostGenerationRequest(
        scenario = GenerationScenario(ScenarioKind.IDLE, topic = "rain"),
        language = OutputLanguage.JAPANESE,
        examples = listOf(sampleTalk()),
        validSurfaces = mapOf(GhostSpeakerId.SAKURA to setOf(0, 3)),
    )
    val encoded = SpikeJson.encodeToString(request)
    assertEquals(request, SpikeJson.decodeFromString(encoded))
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat -p tools/llm-ghost-spike :core:jvmTest --tests "*PortableContractsTest"`

Expected: FAIL because the standalone build and model types do not exist.

- [ ] **Step 3: Create the standalone build**

Pin plugins in the standalone root:

```kotlin
plugins {
    kotlin("multiplatform") version "2.3.21" apply false
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
}
```

Configure `core` with `jvm()`, `kotlinx-coroutines-core:1.11.0`, and `kotlinx-serialization-json:1.11.0`. Configure `cli` with the built-in `application` plugin, a dependency on `:core`, `kotlinx-coroutines-core:1.11.0`, `kotlinx-serialization-json:1.11.0`, and Ktor 3.5.1 artifacts `ktor-client-core`, `ktor-client-cio`, `ktor-client-content-negotiation`, and `ktor-serialization-kotlinx-json`. Do not edit the repository root settings.

- [ ] **Step 4: Implement the exact common domain contracts**

```kotlin
enum class GhostSpeakerId { SAKURA, KERO }
enum class OutputLanguage { JAPANESE, ENGLISH }
enum class ScenarioKind { IDLE, CONTINUATION, POINTER_EVENT }
enum class TalkCategory { IDLE, TOUCH, EVENT, OTHER }

@Serializable data class GhostSourceFile(val path: String, val text: String)
@Serializable data class GhostIdentity(
    val ghostName: String,
    val sakuraName: String,
    val keroName: String,
    val shellSurfaces: Map<GhostSpeakerId, Set<Int>>,
)
@Serializable data class GhostCorpusInput(
    val identity: GhostIdentity,
    val files: List<GhostSourceFile>,
)
@Serializable data class CanonicalTurn(
    val speaker: GhostSpeakerId,
    val surface: Int?,
    val text: String,
)
@Serializable data class CanonicalTalk(
    val id: String,
    val sourcePath: String,
    val sourceLine: Int,
    val heading: String?,
    val category: TalkCategory,
    val touchSpeaker: GhostSpeakerId? = null,
    val touchRegion: String? = null,
    val turns: List<CanonicalTurn>,
)
@Serializable data class GenerationScenario(
    val kind: ScenarioKind,
    val topic: String = "",
    val touchSpeaker: GhostSpeakerId? = null,
    val touchRegion: String? = null,
    val canonicalTalkId: String? = null,
)
```

Define `GhostModelBackend` with `capabilities`, `prepare(): Flow<ModelPreparation>`, `generate(request): Flow<GenerationEvent>`, and `suspend close()`. Configure `SpikeJson` with unknown keys rejected, explicit nulls disabled, and stable output.

- [ ] **Step 5: Run all core tests**

Run: `./gradlew.bat -p tools/llm-ghost-spike :core:jvmTest`

Expected: PASS.

Run: `rg -n "^(import|.*:) (java\\.|javax\\.|android\\.|io\\.ktor)" tools/llm-ghost-spike/core/src/commonMain`

Expected: no matches, proving the common source boundary has no platform or HTTP imports/types.

- [ ] **Step 6: Commit the build and contracts**

```bash
git add tools/llm-ghost-spike
git commit -m "spike: scaffold portable ghost dialogue core"
```

---

### Task 2: Safe SATORI Talk Extraction

**Files:**
- Create: `tools/llm-ghost-spike/core/src/commonMain/kotlin/com/cattailsw/nanidroid/llmghost/corpus/SatoriTalkExtractor.kt`
- Test: `tools/llm-ghost-spike/core/src/commonTest/kotlin/com/cattailsw/nanidroid/llmghost/corpus/SatoriTalkExtractorTest.kt`
- Test fixture: `tools/llm-ghost-spike/core/src/commonTest/resources/fixtures/2elf-shaped-talks.txt`

**Interfaces:**
- Consumes: `GhostCorpusInput`, `GhostSourceFile`, `GhostSpeakerId`, and `CanonicalTalk`.
- Produces: `CorpusExtractionResult(talks, diagnostics)` and `SatoriTalkExtractor.extract(input)`.

- [ ] **Step 1: Add a synthetic 2elf-shaped fixture**

The fixture is test-authored, not copied from 2elf:

```text
＊
：\0\s[3]今日は森の風が静かね。
：\1\s[19]姉さんが騒がしいだけじゃない？

＊OnMouseDoubleClick
＞リエール頭なで
：\1\s[19]そこは触らないで。

＠危険な式
（call,external.saori）
```

- [ ] **Step 2: Write extraction and safety tests**

```kotlin
@Test fun extracts_speakers_surfaces_categories_and_provenance() {
    val result = extractor.extract(input(fixture("2elf-shaped-talks.txt")))
    assertEquals(2, result.talks.size)
    assertEquals(listOf(GhostSpeakerId.SAKURA, GhostSpeakerId.KERO),
        result.talks.first().turns.map { it.speaker })
    assertEquals(listOf(3, 19), result.talks.first().turns.map { it.surface })
    assertEquals(TalkCategory.TOUCH, result.talks[1].category)
    assertTrue(result.talks.first().sourceLine > 0)
}

@Test fun skips_saori_and_variables_without_evaluating_them() {
    val result = extractor.extract(input(source("＊OnBoot\n：（call,external.saori）\n")))
    assertTrue(result.talks.isEmpty())
    assertTrue(result.diagnostics.any { it.code == "unsupported-control" })
}
```

- [ ] **Step 3: Run the focused test and verify it fails**

Run: `./gradlew.bat -p tools/llm-ghost-spike :core:jvmTest --tests "*SatoriTalkExtractorTest"`

Expected: FAIL with unresolved extractor types.

- [ ] **Step 4: Implement a bounded extraction parser**

Discover `＊` blocks and `：` dialogue lines. Recognize only `\0`/`\h`, `\1`/`\u`, and `\s[n]`; strip safe presentation controls, retain visible text, and diagnose all unsupported constructs. Generate IDs from source path, heading line, and ordinal. Classify pointer headings as `TOUCH`, other `On*` headings as `EVENT`, blank/random headings as `IDLE`, and the rest as `OTHER`.

- [ ] **Step 5: Add malformed-input cases**

Test truncated brackets, unknown scope, empty talks, scope above one, conditions, CRLF, and diagnostic-only files. Assert skipped blocks never throw and every diagnostic includes path and line.

- [ ] **Step 6: Run and commit**

Run: `./gradlew.bat -p tools/llm-ghost-spike :core:jvmTest`

Expected: PASS.

```bash
git add tools/llm-ghost-spike/core
git commit -m "spike: extract canonical SATORI talks"
```

---

### Task 3: Deterministic Retrieval and Prompt Rendering

**Files:**
- Create: `tools/llm-ghost-spike/core/src/commonMain/kotlin/com/cattailsw/nanidroid/llmghost/retrieval/CanonicalTalkRetriever.kt`
- Create: `tools/llm-ghost-spike/core/src/commonMain/kotlin/com/cattailsw/nanidroid/llmghost/prompt/GhostPromptRenderer.kt`
- Test: `tools/llm-ghost-spike/core/src/commonTest/kotlin/com/cattailsw/nanidroid/llmghost/retrieval/CanonicalTalkRetrieverTest.kt`
- Test: `tools/llm-ghost-spike/core/src/commonTest/kotlin/com/cattailsw/nanidroid/llmghost/prompt/GhostPromptRendererTest.kt`

**Interfaces:**
- Consumes: `CanonicalTalk`, `GenerationScenario`, `OutputLanguage`, and valid surfaces.
- Produces: `RetrievedExample(talk, score, reasons)`, `CanonicalTalkRetriever.retrieve(...)`, and `RenderedGhostPrompt(system, user, selectedExamples)`.

- [ ] **Step 1: Write the failing ranking test**

```kotlin
@Test fun touch_scenario_prefers_matching_character_and_region() {
    val result = retriever.retrieve(talks, keroHeadScenario, limit = 3)
    assertEquals("kero-head", result.first().talk.id)
    assertEquals(result, retriever.retrieve(talks, keroHeadScenario, 3))
    assertTrue(result.first().reasons.contains("touch-region"))
}
```

- [ ] **Step 2: Run it and verify failure**

Run: `./gradlew.bat -p tools/llm-ghost-spike :core:jvmTest --tests "*CanonicalTalkRetrieverTest"`

Expected: FAIL with unresolved retriever.

- [ ] **Step 3: Implement transparent scoring and diversity**

Use category `+100`, touch speaker `+80`, touch region `+80`, continuation source `+120`, normalized topic-token overlap `+10` each, and both-speaker talk `+20`. Sort by score descending, then source path, line, and ID. Take at most two examples per source before filling from the ordered tail. Record every nonzero reason.

- [ ] **Step 4: Write prompt snapshot tests**

Assert both languages include the exact JSON schema, `sakura`/`kero`, per-speaker surfaces, labelled examples, scenario, optional recent generated history for continuation, and no-complete-line-copy rule. Assert English requests natural character-preserving adaptation rather than literal translation. Assert no invented persona biography appears.

- [ ] **Step 5: Implement prompt rendering**

```kotlin
data class RenderedGhostPrompt(
    val system: String,
    val user: String,
    val selectedExamples: List<RetrievedExample>,
)
class GhostPromptRenderer {
    fun render(request: GhostGenerationRequest): RenderedGhostPrompt
}
```

Serialize examples with `SpikeJson` instead of hand-quoting corpus text.

- [ ] **Step 6: Run and commit**

Run: `./gradlew.bat -p tools/llm-ghost-spike :core:jvmTest --tests "*CanonicalTalkRetrieverTest" --tests "*GhostPromptRendererTest"`

Expected: PASS.

```bash
git add tools/llm-ghost-spike/core
git commit -m "spike: retrieve corpus examples and render prompts"
```

---

### Task 4: Strict Dialogue Decoding, Validation, and SakuraScript Compilation

**Files:**
- Create: `tools/llm-ghost-spike/core/src/commonMain/kotlin/com/cattailsw/nanidroid/llmghost/generation/GeneratedDialogueDecoder.kt`
- Create: `tools/llm-ghost-spike/core/src/commonMain/kotlin/com/cattailsw/nanidroid/llmghost/generation/GeneratedDialogueValidator.kt`
- Create: `tools/llm-ghost-spike/core/src/commonMain/kotlin/com/cattailsw/nanidroid/llmghost/sakura/SakuraScriptCompiler.kt`
- Create: `tools/llm-ghost-spike/core/src/commonMain/kotlin/com/cattailsw/nanidroid/llmghost/sakura/CompiledScriptValidator.kt`
- Test: `tools/llm-ghost-spike/core/src/commonTest/kotlin/com/cattailsw/nanidroid/llmghost/generation/GeneratedDialogueDecoderTest.kt`
- Test: `tools/llm-ghost-spike/core/src/commonTest/kotlin/com/cattailsw/nanidroid/llmghost/generation/GeneratedDialogueValidatorTest.kt`
- Test: `tools/llm-ghost-spike/core/src/commonTest/kotlin/com/cattailsw/nanidroid/llmghost/sakura/SakuraScriptCompilerTest.kt`

**Interfaces:**
- Consumes: raw assembled model text and `GhostGenerationRequest.validSurfaces`.
- Produces: `DialogueDecodeResult`, `DialogueValidationResult`, `SakuraScriptCompilation`, and `CompiledScriptValidation`.

- [ ] **Step 1: Write strict decoder tests**

Cover a bare JSON object and exactly one fenced `json` block as successes. Reject prose around JSON, two fenced blocks, trailing JSON, malformed JSON, missing turns, unknown keys, and truncation with stable codes `ambiguous-output`, `malformed-json`, and `schema-invalid`.

- [ ] **Step 2: Run decoder tests and verify failure**

Run: `./gradlew.bat -p tools/llm-ghost-spike :core:jvmTest --tests "*GeneratedDialogueDecoderTest"`

Expected: FAIL with unresolved decoder.

- [ ] **Step 3: Implement strict wire decoding**

```kotlin
@Serializable
data class GeneratedTurn(
    val speaker: String,
    val surface: Int,
    val text: String,
    val waitAfterMs: Int = 0,
)

@Serializable
data class GeneratedDialogue(val turns: List<GeneratedTurn>)
```

Strip only surrounding whitespace or one complete fenced block. Decode with unknown keys disabled and never repair arbitrary output.

- [ ] **Step 4: Write semantic validation tests**

Test zero/nine turns; unknown speaker; blank/501-code-point text; negative/2,001 ms waits; corpus-unobserved and shell-missing surfaces; backslashes, controls, URLs, `script:`, and choice-like payloads. Include one valid two-speaker result and assert trusted turns use `GhostSpeakerId`.

- [ ] **Step 5: Implement accumulated validation**

Return all violations as `DialogueViolation(code, turnIndex, detail)` and no trusted dialogue when any exists. Count Unicode scalars with a common-safe surrogate-pair routine and reject unpaired surrogates.

- [ ] **Step 6: Write compiler and round-trip tests**

```kotlin
@Test fun compiles_only_the_supported_subset_and_round_trips() {
    val result = compiler.compile(validatedDialogue)
    assertEquals("\\0\\s[3]Hello\\_w[400]\\1\\s[19]Hi\\_w[0]\\e", result.script)
    assertEquals(validatedDialogue, CompiledScriptValidator.validate(result.script).dialogue)
}
```

- [ ] **Step 7: Implement the trusted compiler boundary**

Emit only `\0`/`\1`, `\s[n]`, escaped text, `\_w[n]`, and one terminal `\e`. The validator parses exactly this emitted grammar, rejects every other command, and reconstructs the validated turns. This is the standalone equivalent seam for Nanidroid's tokenizer; do not copy the full production tokenizer.

- [ ] **Step 8: Run and commit**

Run: `./gradlew.bat -p tools/llm-ghost-spike :core:jvmTest --tests "*GeneratedDialogue*" --tests "*SakuraScriptCompilerTest"`

Expected: PASS.

```bash
git add tools/llm-ghost-spike/core
git commit -m "spike: validate dialogue and compile safe SakuraScript"
```

---

### Task 5: Similarity Evaluation and Portable Pipeline

**Files:**
- Create: `tools/llm-ghost-spike/core/src/commonMain/kotlin/com/cattailsw/nanidroid/llmghost/evaluation/CanonicalSimilarity.kt`
- Create: `tools/llm-ghost-spike/core/src/commonMain/kotlin/com/cattailsw/nanidroid/llmghost/pipeline/GhostDialoguePipeline.kt`
- Test: `tools/llm-ghost-spike/core/src/commonTest/kotlin/com/cattailsw/nanidroid/llmghost/evaluation/CanonicalSimilarityTest.kt`
- Test: `tools/llm-ghost-spike/core/src/commonTest/kotlin/com/cattailsw/nanidroid/llmghost/pipeline/GhostDialoguePipelineTest.kt`

**Interfaces:**
- Consumes: retriever, renderer, backend, decoder, validator, compiler, and canonical talks.
- Produces: `CanonicalSimilarity.evaluate(...)` and `GhostDialoguePipeline.runCase(case): SpikeCaseReport`.

- [ ] **Step 1: Write similarity tests**

Normalize Unicode whitespace/punctuation and lowercase Latin. Assert normalized exact matches fail, character-LCS ratios at or above `0.90` warn, and short incidental overlap stays clean. Record the strongest canonical talk and turn.

- [ ] **Step 2: Implement similarity evaluation**

Define `SimilarityFinding(generatedTurn, canonicalTalkId, canonicalTurn, exact, ratio)` and calculate `2 * lcs / (leftLength + rightLength)`. Add no embeddings or network dependency.

- [ ] **Step 3: Write a pipeline test with a streaming fake**

```kotlin
private class FakeBackend(private val chunks: List<String>) : GhostModelBackend {
    override val capabilities = ModelCapabilities(streaming = true, structuredOutput = false)
    override fun prepare() = flowOf(ModelPreparation.Ready)
    override fun generate(request: GhostGenerationRequest) = flow {
        chunks.forEach { emit(GenerationEvent.TextDelta(it)) }
        emit(GenerationEvent.Completed(usage = null))
    }
    override suspend fun close() = Unit
}

@Test fun report_keeps_prompt_raw_output_script_and_timing() = runTest {
    val report = pipeline(FakeBackend(validJsonChunks)).runCase(spikeCase)
    assertEquals(CaseStatus.PASSED, report.status)
    assertTrue(report.renderedPrompt.user.isNotBlank())
    assertTrue(report.rawResponse.startsWith("{"))
    assertTrue(report.compiledSakuraScript!!.endsWith("\\e"))
}
```

- [ ] **Step 4: Implement one-case orchestration**

Inject `nowMillis: () -> Long`. Collect lifecycle and generation events, preserve partial text, classify expected failures, and return a serializable report rather than throwing. Propagate coroutine cancellation.

- [ ] **Step 5: Add lifecycle edge tests**

Cover completion without text, error after partial text, duplicate completion, missing completion, preparation failure, and cancellation. Assert partial output and stable failure codes are preserved.

- [ ] **Step 6: Run and commit**

Run: `./gradlew.bat -p tools/llm-ghost-spike :core:jvmTest`

Expected: PASS.

```bash
git add tools/llm-ghost-spike/core
git commit -m "spike: assemble portable dialogue evaluation pipeline"
```

---

### Task 6: JVM NAR and Charset Materialization

**Files:**
- Create: `tools/llm-ghost-spike/cli/src/main/kotlin/com/cattailsw/nanidroid/llmghost/archive/NarCorpusLoader.kt`
- Test: `tools/llm-ghost-spike/cli/src/test/kotlin/com/cattailsw/nanidroid/llmghost/archive/NarCorpusLoaderTest.kt`
- Create: `tools/llm-ghost-spike/cli/src/test/resources/nar/README.md`

**Interfaces:**
- Consumes: JVM `Path` only inside `cli`.
- Produces: `NarLoadResult.Success(GhostCorpusInput, entryHashes)` or `NarLoadResult.Failure(code, detail)`.

- [ ] **Step 1: Write runtime-generated archive tests**

Use `ZipOutputStream` to create UTF-8 and Shift_JIS NARs in a temporary directory with descriptors, a synthetic dictionary, and `surfaces.txt`. Commit no real NAR.

```kotlin
@Test fun decodes_shift_jis_and_materializes_identity() {
    val result = loader.load(nar(charset = "Shift_JIS", sakura = "ソフィ", kero = "リエール"))
        as NarLoadResult.Success
    assertEquals("ソフィ", result.input.identity.sakuraName)
    assertTrue(result.input.files.single().text.contains("今日は"))
}
```

- [ ] **Step 2: Run the focused test and verify failure**

Run: `./gradlew.bat -p tools/llm-ghost-spike :cli:test --tests "*NarCorpusLoaderTest"`

Expected: FAIL with unresolved loader.

- [ ] **Step 3: Implement bounded in-place ZIP reading**

Reject absolute/traversal names, duplicate normalized names, unreadable entries, more than 10,000 entries, an entry above 8 MiB, and more than 64 MiB total consumed input. Never extract entries. Compute SHA-256 for every consumed entry.

- [ ] **Step 4: Implement descriptor decoding and metadata**

Scan ASCII-compatible charset declarations; support only UTF-8, Shift_JIS, and Windows-31J aliases; report malformed/unmappable bytes; reject inconsistent declarations. Parse ghost/Sakura/Kero names. Parse shell surface IDs and preserve them separately so common code can intersect them with observed per-speaker corpus surfaces.

- [ ] **Step 5: Add failure tests**

Cover absent/non-ZIP inputs, unsupported/inconsistent encoding, malformed text, traversal, duplicate entries, size limits, missing dictionaries, missing identity, and absent shell inventory. Failure details must not dump archive content.

- [ ] **Step 6: Run and commit**

Run: `./gradlew.bat -p tools/llm-ghost-spike :cli:test`

Expected: PASS.

```bash
git add tools/llm-ghost-spike/cli
git commit -m "spike: load decoded ghost corpus from NAR"
```

---

### Task 7: OpenAI-Compatible Ktor/CIO Backend

**Files:**
- Create: `tools/llm-ghost-spike/cli/src/main/kotlin/com/cattailsw/nanidroid/llmghost/openai/OpenAiCompatibleBackend.kt`
- Create: `tools/llm-ghost-spike/cli/src/main/kotlin/com/cattailsw/nanidroid/llmghost/openai/OpenAiWireModels.kt`
- Test: `tools/llm-ghost-spike/cli/src/test/kotlin/com/cattailsw/nanidroid/llmghost/openai/OpenAiCompatibleBackendTest.kt`

**Interfaces:**
- Consumes: `HttpClient`, `OpenAiBackendConfig(baseUrl, model, apiKey, streaming, connectTimeoutMillis, requestTimeoutMillis)`, and common requests.
- Produces: CLI-private `OpenAiCompatibleBackend : GhostModelBackend` emitting normalized common events.

- [ ] **Step 1: Write fake-server request tests**

Start loopback JDK `HttpServer` on an ephemeral port. Capture `POST /v1/chat/completions`; assert model, messages, optional seed, JSON type, optional bearer header, stream flag, and finite timeouts. Return a non-streaming response and assert text and usage events.

- [ ] **Step 2: Run the test and verify failure**

Run: `./gradlew.bat -p tools/llm-ghost-spike :cli:test --tests "*OpenAiCompatibleBackendTest"`

Expected: FAIL with unresolved backend.

- [ ] **Step 3: Implement Ktor/CIO mapping**

Create one client in CLI wiring and inject it. Configure content negotiation, `HttpTimeout`, `expectSuccess = false`, no redirects, and response-only unknown-field tolerance. Post to `<baseUrl>/chat/completions`. Never log or report the API key.

- [ ] **Step 4: Test and implement incremental SSE**

Cover `data:` events split across byte boundaries, comments, blanks, `[DONE]`, terminal usage, malformed events, EOF before done, and cancellation. Read `ByteReadChannel` incrementally and emit deltas without buffering the full body.

- [ ] **Step 5: Test failure classification and retries**

Cover 401, missing model, 429, 500, invalid JSON, timeout, and refused connection. Permit exactly one retry for 502/503/504 only before text is emitted. Record retries; never retry 4xx, schema failures, or a partially emitted stream.

- [ ] **Step 6: Run and commit**

Run: `./gradlew.bat -p tools/llm-ghost-spike :cli:test --tests "*OpenAiCompatibleBackendTest"`

Expected: PASS without external access.

```bash
git add tools/llm-ghost-spike/cli
git commit -m "spike: add OpenAI-compatible desktop backend"
```

---

### Task 8: Immutable Reports and Required Scenario Matrix

**Files:**
- Create: `tools/llm-ghost-spike/cli/src/main/kotlin/com/cattailsw/nanidroid/llmghost/report/FileSpikeReportStore.kt`
- Create: `tools/llm-ghost-spike/cli/src/main/kotlin/com/cattailsw/nanidroid/llmghost/SpikeScenarioFactory.kt`
- Create: `tools/llm-ghost-spike/cli/src/main/kotlin/com/cattailsw/nanidroid/llmghost/SpikeRunner.kt`
- Test: `tools/llm-ghost-spike/cli/src/test/kotlin/com/cattailsw/nanidroid/llmghost/report/FileSpikeReportStoreTest.kt`
- Test: `tools/llm-ghost-spike/cli/src/test/kotlin/com/cattailsw/nanidroid/llmghost/SpikeRunnerTest.kt`

**Interfaces:**
- Consumes: loaded corpus, extracted talks, portable pipeline, and a report directory.
- Produces: `SpikeRunReport`, immutable case JSON, `summary.json`, `review.md`, and `SpikeRunOutcome(exitCode, reportDirectory)`.

- [ ] **Step 1: Write scenario-matrix tests**

Assert `SpikeScenarioFactory.requiredCases(...)` creates exactly six base cases: idle, continuation, and pointer event crossed with Japanese and English. Continuation must reference a real talk; pointer cases must use an observed touch speaker/region. Insufficient corpus data must produce a preflight failure.

- [ ] **Step 2: Write report-store tests**

Assert JSON includes hashes, scenario, language, seed, sanitized endpoint, model, retrieved scores, exact prompt, raw response, parsed dialogue, script, validation, tokenizer-equivalent result, similarity, latency, usage, retries, and failure classification. Assert stable credential-free filenames, no overwrite, side-by-side Markdown, and readable partial failures.

- [ ] **Step 3: Run focused tests and verify failure**

Run: `./gradlew.bat -p tools/llm-ghost-spike :cli:test --tests "*FileSpikeReportStoreTest" --tests "*SpikeRunnerTest"`

Expected: FAIL with unresolved report store and runner.

- [ ] **Step 4: Implement atomic immutable writes**

Write to a sibling temporary file, close, then atomically move into a new `<UTC timestamp>-<run id>/<case id>-<candidate>` directory. Reject existing final paths. Sanitize endpoint identity to scheme, host, port, and path; remove user info and query.

- [ ] **Step 5: Implement run orchestration**

Extract once, intersect canonical and shell surfaces, preflight all scenarios, execute candidates sequentially, persist each case immediately, then write summaries. Exit `0` only if all cases pass, `1` for reported failures, and propagate cancellation for CLI exit `130`.

- [ ] **Step 6: Test mixed outcomes and partial preservation**

Script fake backends for pass, malformed JSON, invalid surface, exact-copy failure, and transport failure. Assert every completed case is saved and aggregate status is nonzero.

- [ ] **Step 7: Run and commit**

Run: `./gradlew.bat -p tools/llm-ghost-spike :cli:test`

Expected: PASS.

```bash
git add tools/llm-ghost-spike/cli
git commit -m "spike: report ghost dialogue scenario matrix"
```

---

### Task 9: Desktop CLI, Documentation, and Live Nemotron Proof

**Files:**
- Create: `tools/llm-ghost-spike/cli/src/main/kotlin/com/cattailsw/nanidroid/llmghost/SpikeCliArguments.kt`
- Create: `tools/llm-ghost-spike/cli/src/main/kotlin/com/cattailsw/nanidroid/llmghost/Main.kt`
- Test: `tools/llm-ghost-spike/cli/src/test/kotlin/com/cattailsw/nanidroid/llmghost/SpikeCliArgumentsTest.kt`
- Create: `tools/llm-ghost-spike/README.md`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `--nar`, endpoint/model options, candidate count, seed, streaming flag, timeouts, and optional environment API key.
- Produces: runnable `:cli:run`, documented hermetic/live commands, final report path, and meaningful process status.

- [ ] **Step 1: Write argument and secret-precedence tests**

Cover required `--nar`; defaults `--base-url http://gx10-5e5d:10101/v1`, `--model nemotron-3-super`, and `--candidates 1`; optional seed; boolean stream; finite positive timeouts; unknown/duplicate flags; missing values; and `OPENAI_API_KEY` fallback. Help and errors must never contain the key.

- [ ] **Step 2: Run the argument tests and verify failure**

Run: `./gradlew.bat -p tools/llm-ghost-spike :cli:test --tests "*SpikeCliArgumentsTest"`

Expected: FAIL with unresolved parser.

- [ ] **Step 3: Implement the process entry point**

```kotlin
fun main(args: Array<String>) = runBlocking {
    val parsed = SpikeCliArguments.parse(args.toList(), System.getenv())
    val exit = when (parsed) {
        is CliParseResult.Help -> printHelpAndReturnZero(parsed)
        is CliParseResult.Error -> printErrorAndReturnTwo(parsed)
        is CliParseResult.Success -> runSpike(parsed.value)
    }
    exitProcess(exit)
}
```

Install a shutdown hook that cancels the root job, close backend/client in `finally`, send concise progress to stderr, and print the final absolute report directory to stdout.

- [ ] **Step 4: Document commands and safety**

Include these commands:

```powershell
.\gradlew.bat -p tools/llm-ghost-spike :core:jvmTest :cli:test
.\gradlew.bat -p tools/llm-ghost-spike :cli:run --args='--nar C:\path\to\2elf-2.46.nar --base-url http://gx10-5e5d:10101/v1 --model nemotron-3-super --candidates 2 --stream true'
```

Explain read-only local NAR use, report location, desktop-only cleartext, deterministic trust boundary, lack of Android integration, and issue #259.

- [ ] **Step 5: Ignore generated and local inputs**

```gitignore
/tools/llm-ghost-spike/build/
/tools/llm-ghost-spike/**/build/
/tools/llm-ghost-spike/*.nar
```

- [ ] **Step 6: Run hermetic verification**

Run: `./gradlew.bat -p tools/llm-ghost-spike clean :core:jvmTest :cli:test`

Expected: BUILD SUCCESSFUL with no external endpoint contact.

- [ ] **Step 7: Verify expected CLI failure behavior**

Run: `./gradlew.bat -p tools/llm-ghost-spike :cli:run --args='--nar C:\does-not-exist\2elf.nar'`

Expected: nonzero exit with `nar-unreadable`, no expected-failure stack trace, and no secret output.

- [ ] **Step 8: Run the opt-in live Nemotron proof**

Run the documented command with the user's NAR and endpoint. With two candidates, expect twelve reports spanning all scenario/language pairs. Mechanical success requires valid JSON, authorized speakers/surfaces, round-tripped SakuraScript, no exact copies, and exit `0`; fully reported model/artistic failures remain valid spike evidence.

- [ ] **Step 9: Record human review**

Review `review.md`; score character voice, Sophie/Liere relationship, novelty, coherence, and English adaptation as `poor`, `mixed`, or `credible`. Save `human-review.json` beside the summary without editing inputs/outputs. The proof threshold is mechanically valid Japanese and English for every family and a repeatable majority judged credible rather than generic.

- [ ] **Step 10: Run hygiene checks and commit**

Run: `git status --short`

Expected: no NAR, report, API key, or build output tracked.

Run: `git diff --check`

Expected: no whitespace errors.

```bash
git add .gitignore tools/llm-ghost-spike
git commit -m "spike: add desktop LLM ghost dialogue harness"
```

---

## Final Verification Gate

- [ ] Run `./gradlew.bat -p tools/llm-ghost-spike clean :core:jvmTest :cli:test` and capture the successful task summary.
- [ ] Run `./gradlew.bat testDebugUnitTest` to prove the standalone build did not regress Nanidroid JVM tests.
- [ ] Run `./gradlew.bat lint` to prove the standalone tool did not alter Android lint behavior.
- [ ] Run `git diff --check` and `git status --short`.
- [ ] Confirm `git ls-files "*.nar" "*llm-ghost-spike/build*"` prints nothing.
- [ ] Confirm reports contain no API key and sanitize the endpoint.
- [ ] Confirm normal tests pass while the experimental endpoint is unavailable.
- [ ] Request review focused on archive limits, corpus prompt injection, secret redaction, stream cancellation, surface authorization, and copy detection.
