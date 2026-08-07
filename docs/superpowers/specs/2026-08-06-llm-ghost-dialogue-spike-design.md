# Corpus-Conditioned LLM Ghost Dialogue Spike

## Summary

Build a Kotlin spike that proves a large language model can extend an installed
ghost's authored conversations while preserving its characters, speaker roles,
surface vocabulary, and SakuraScript presentation contract. The first subject
is `2elf-2.46.nar`; the first inference backend is the OpenAI-compatible
Nemotron endpoint at `http://gx10-5e5d:10101/v1`, using the supplied
`nemotron-3-super` model ID by default. Both values remain configurable.

The spike does not replace Nanidroid's renderer, surface system, event routing,
or SakuraScript playback. It explores a new dialogue-producing brain whose
output can eventually sit behind the same conceptual boundary as SHIORI.

The immediate question is deliberately narrow: can a high-capability model use
the conversations shipped inside 2elf as canonical behavioral examples and
produce new, valid, recognizably in-character Japanese and English dialogue?
Only after establishing that ceiling should the project measure smaller
on-device models through Gemini Nano or LiteRT-LM.

## Goals

- Read a user-supplied NAR without modifying or redistributing it.
- Extract useful canonical dialogue examples from 2elf's SATORI dictionaries.
- Preserve the distinction between Sakura/Sophie and Kero/Liere in generated
  conversations.
- Generate new idle talks, continuations, and event reactions in Japanese and
  English.
- Convert model output into trusted SakuraScript using deterministic Kotlin
  code rather than executing unrestricted model-authored commands.
- Record prompts, selected examples, raw responses, compiled scripts,
  validation results, similarity warnings, and timing for inspection.
- Keep corpus processing, request construction, result validation, and
  SakuraScript compilation independent of the initial HTTP backend so Android
  adapters can reuse them later.

## Non-goals

- Shipping LLM support in the production Android app.
- Replacing existing SHIORI engines for installed ghosts.
- Implementing Gemini Nano or LiteRT-LM in this spike.
- Supporting MediaPipe LLM Inference, which is maintenance-only and superseded
  for new Android LLM integrations by LiteRT-LM.
- Fine-tuning, LoRA training, embeddings, or a vector database.
- Automatically modifying a NAR or treating generated text as canonical.
- Persistent long-term user memory or autonomous background inference.
- Accepting arbitrary SakuraScript, URLs, executable actions, or unknown
  surfaces from the model.

## Architectural Boundary

The spike is divided into a model-independent dialogue pipeline and a model
backend:

```text
NAR file
  -> corpus reader
  -> canonical talk parser
  -> scenario-aware example retrieval
  -> generation request
  -> model backend
  -> structured dialogue parser
  -> semantic validator
  -> SakuraScript compiler
  -> Nanidroid tokenizer validation
  -> report writer
```

The common boundary models generation as a lifecycle rather than an HTTP call:

```kotlin
interface GhostModelBackend : AutoCloseable {
    val capabilities: ModelCapabilities

    fun prepare(): Flow<ModelPreparation>

    fun generate(request: GhostGenerationRequest): Flow<GenerationEvent>
}
```

The spike implements `OpenAiCompatibleBackend`. It posts chat-completion
requests to a configurable base URL and model ID, supports non-streaming or
streaming responses as the endpoint permits, and normalizes results into
`GenerationEvent` values. Endpoint and model configuration are command-line or
environment inputs and are never committed.

The interface intentionally does not expose HTTP messages, ML Kit types, or
LiteRT-LM conversations. A future `MlKitPromptBackend` can perform AICore
availability, download, warmup, token-count, and structured-output checks. A
future `LiteRtLmBackend` can own a long-lived `Engine`, a ghost-scoped
`Conversation`, a selected `.litertlm` model path, and CPU/GPU fallback. Both
will consume the same `GhostGenerationRequest` and return the same normalized
events.

## Corpus Ingestion

The NAR path is an explicit harness argument. The harness reads ZIP entries in
place and detects the declared character encoding from descriptors. For 2elf,
the relevant source is Shift_JIS text under `ghost/master/`, including the root
dictionaries and character/event subdirectories.

The parser is intentionally an extraction parser, not a reimplementation of
SATORI. It identifies useful authored talk blocks and retains:

- source archive entry and approximate line position;
- event or talk heading when present;
- ordered speaker turns;
- authored surface numbers and the scope in which each surface was used;
- text after removing or classifying control syntax;
- variables and conditions as provenance rather than evaluating them;
- whether the sample is idle talk, touch reaction, dated event, or another
  recognized category.

Blocks the parser cannot interpret safely are skipped with diagnostics. The
spike must not execute SATORI variables, SAORI calls, browser actions, or other
embedded commands.

Speaker attribution is derived from SATORI scope switches and checked against
the ghost descriptor names. Parser tests use small synthetic excerpts and
representative 2elf-shaped fixtures; the 2elf archive itself remains an
external corpus input.

## Retrieval and Prompt Construction

The shipped dialogue is the primary behavioral specification. Hand-authored
persona prose is limited to operational instructions such as preserving the
demonstrated character voices and returning the required structure.

For each generation, the harness ranks canonical talks using deterministic,
lightweight signals suitable for later on-device use:

- matching scenario or event category;
- matching touch character and region where applicable;
- lexical overlap with the current topic or starting talk;
- presence of both speakers for two-character scenarios;
- diversity across source talks and surface choices.

No embedding model or external retrieval service is required. The harness
selects a bounded set of examples and records the ranking explanation in its
report.

The prompt contains:

1. the required structured output shape and allowed speakers;
2. the requested Japanese or English output language;
3. the scenario and current event or user input;
4. the selected canonical conversations with source labels;
5. recent generated conversation history when a continuation requires it;
6. valid surface IDs observed for each character in the canonical corpus and
   confirmed to exist in the installed shell;
7. instructions to preserve demonstrated behavior, avoid unsupported lore, and
   avoid copying complete canonical lines.

Japanese generation imitates the canonical language directly. English
generation is a character-preserving adaptation: it uses the same Japanese
examples but requests natural English that preserves the contrasting voices,
relationship, pacing, and intent rather than literal line-by-line translation.

## Generation Schema and SakuraScript Compilation

The success path requests a small JSON document:

```json
{
  "turns": [
    {
      "speaker": "kero",
      "surface": 19,
      "text": "...",
      "waitAfterMs": 400
    },
    {
      "speaker": "sakura",
      "surface": 3,
      "text": "...",
      "waitAfterMs": 400
    }
  ]
}
```

The portable parser accepts a bare JSON object or one fenced JSON block and
rejects ambiguous surrounding output. It does not repair arbitrary malformed
responses beyond harmless whitespace or code fences.

Validation requires:

- between one and eight turns;
- only the declared Sakura and Kero speaker identifiers;
- a surface ID observed for that speaker in canonical talks and present in the
  installed shell;
- nonblank text of at most 500 Unicode characters per turn;
- waits from zero through 2,000 milliseconds;
- no raw SakuraScript control characters in text;
- no URLs, executable actions, or model-invented choices in the initial spike.

The compiler escapes dialogue text, emits speaker and surface commands, maps
bounded waits to supported SakuraScript waits, and terminates the script. The
result is then passed through Nanidroid's existing SakuraScript tokenizer or an
equivalent extracted validation seam. Compilation, never model output, is the
trusted source of executable SakuraScript.

## Scenarios

Each live run covers both Japanese and English for three scenario families:

1. **New idle talk**: create a short original Sophie/Liere exchange using
   retrieved idle conversations.
2. **Canonical continuation**: start from a real shipped exchange and add new
   turns consistent with its topic and speaker dynamics.
3. **Event reaction**: generate a response to a representative pointer event
   using canonical reactions for the same character and region when available.

The command may request multiple candidates or seeds. Each candidate is stored
as a separate immutable report case.

## Reports and Evaluation

Reports are written beneath `build/reports/llm-ghost-spike/`, which remains
uncommitted. Every case records:

- corpus identity and entry hashes;
- scenario, language, seed, endpoint, and model identifier;
- retrieved examples and their selection scores;
- exact rendered prompt;
- raw model response;
- parsed structured dialogue;
- compiled SakuraScript;
- validation and tokenizer results;
- generation latency and usage metadata when supplied;
- exact and normalized similarity to canonical lines;
- warnings and failure classifications.

Automatic checks establish mechanical validity, not artistic success. A small
side-by-side review report presents the canonical examples and generated talk
for human judgment of character voice, relationship, novelty, coherence, and
English adaptation quality.

The spike succeeds when the configured large model repeatedly produces valid
Japanese and English talks for all three scenario families, uses only valid
speaker/surface controls, avoids reproducing complete canonical conversations,
and gives a human reviewer credible evidence that the output resembles 2elf
rather than a generic assistant.

## Error Handling

Expected failures are represented in reports and return a failing process exit
code for affected requested cases:

- unreadable or invalid NAR;
- unsupported or inconsistent encoding;
- no extractable canonical talks;
- endpoint discovery, connection, timeout, or HTTP errors;
- missing model or incompatible response shape;
- truncated, malformed, or schema-invalid output;
- unknown speaker or surface;
- unsafe text or SakuraScript compilation failure;
- cancellation.

The HTTP adapter uses finite connect and request timeouts. It does not retry
schema failures automatically. A small bounded retry may be used only for
clearly transient transport failures and must be recorded. Partial reports are
preserved for diagnosis.

## Testing

Local JVM tests cover:

- Shift_JIS and UTF-8 archive entry decoding;
- talk-block extraction and speaker switching;
- skipped unsupported SATORI constructs and diagnostics;
- deterministic retrieval and diversity;
- Japanese and English prompt construction;
- backend capability mapping and streamed-response assembly using a fake
  backend;
- JSON extraction and rejection of ambiguous output;
- speaker, surface, text, and wait validation;
- deterministic SakuraScript compilation and escaping;
- similarity warnings and report serialization.

An opt-in live smoke command calls the configured Nemotron endpoint and runs the
six required scenario-language combinations. Live network inference is not
part of the normal unit-test task.

## Future Android Adapters

The spike leaves two deliberate extension points:

- **Gemini Nano through ML Kit Prompt API**: use `Generation.getClient()`,
  feature status/download handling, `warmup()`, runtime token budgeting, system
  instructions or caching when available, and typed structured output with a
  JSON fallback.
- **Sideloaded models through LiteRT-LM**: copy a selected content URI durably
  into app-private model storage, initialize `Engine` off the main thread,
  attempt the chosen accelerator with a supported fallback, create a
  ghost-scoped `Conversation`, stream results, and close conversation/engine at
  the appropriate session boundary.

Before either adapter ships, it needs device-specific availability, thermal,
memory, cancellation, process-death, model-download, licensing, and durable-copy
design. Those concerns must not distort the initial question of whether a large
model can imitate the authored ghost corpus.

## References

- [ML Kit GenAI Prompt API](https://developers.google.com/ml-kit/genai/prompt/android)
- [ML Kit structured output](https://developers.google.com/ml-kit/genai/prompt/android/structured-output)
- [LiteRT-LM Kotlin API](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md)
- [Official LiteRT samples](https://github.com/google-ai-edge/litert-samples)
- [MediaPipe LLM Inference maintenance notice](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android)
