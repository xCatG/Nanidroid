# LLM Ghost Dialogue Spike

This standalone desktop harness asks whether a large OpenAI-compatible model can extend a ghost's shipped conversations while preserving its demonstrated character voices. It reads a supplied NAR without modifying or extracting it, runs Japanese and English idle, continuation, and pointer-event cases, validates structured dialogue, compiles a deliberately small SakuraScript subset, and writes immutable evidence for review.

It is an experiment, not an Android feature and not a SHIORI replacement. Nothing here changes Nanidroid's production ghost selection, networking, event routing, or playback.

## Build and hermetic tests

From the Nanidroid repository root in PowerShell:

```powershell
.\gradlew.bat -p tools/llm-ghost-spike :core:jvmTest :cli:test
```

The normal test suite uses local fake servers and synthetic archives. It does not contact the configured model endpoint.

To confirm expected handling of an unreadable input:

```powershell
.\gradlew.bat -p tools/llm-ghost-spike :cli:run --args='--nar C:\does-not-exist\2elf.nar'
```

This exits nonzero with `nar-unreadable`, without a stack trace.

## Opt-in live run

```powershell
.\gradlew.bat -p tools/llm-ghost-spike :cli:run --args='--nar C:\path\to\2elf-2.46.nar --base-url http://gx10-5e5d:10101/v1 --model nemotron-3-super --candidates 2 --stream true'
```

The cleartext default is for this desktop/LAN experiment only. It does not weaken Android's HTTPS policy. The NAR remains read-only and must not be committed or redistributed.

No ambient credential is sent. `OPENAI_API_KEY` is deliberately ignored for the default and custom endpoint. If an endpoint requires a bearer token, name its environment variable explicitly without putting the token on the command line:

```powershell
$env:LOCAL_LLM_BEARER = '...'
.\gradlew.bat -p tools/llm-ghost-spike :cli:run --args='--nar C:\path\ghost.nar --base-url https://example.test/v1 --api-key-env LOCAL_LLM_BEARER'
```

The variable's name and value are not written to progress messages or reports. Remove it from the environment when finished.

Useful options are `--seed`, `--connect-timeout-ms`, `--request-timeout-ms`, and `--report-root`. Run with `--help` for the complete list.

## Reports and trust boundary

The CLI prints progress to stderr. When report or recovery evidence exists, stdout contains only its final absolute directory. The default root is `build/reports/llm-ghost-spike/` relative to the standalone invocation. A published run has a `.complete` logical commit marker; every completed case has `.case-complete`. Directories without `.complete` are recovery/in-progress evidence and must not be treated as published results. Generated reports and `human-review.json` are ignored by Git.

Reports include the corpus hashes, retrieved authored examples, exact prompt, raw response, mechanical validation, SakuraScript, similarity checks, usage when supplied, and retry count. Model text is never executed as SakuraScript. Only strict JSON using `sakura`/`kero`, corpus-observed surfaces also present in the active shell, bounded text/waits, and no actions, URLs, choices, or control syntax can cross the deterministic compiler boundary.

## Portability boundary

`core` is pure Kotlin Multiplatform common code. It owns corpus parsing after decoding, retrieval, prompts, backend-neutral generation flows, validation, compilation, copy detection, and report models. `cli` is the JVM adapter: ZIP and Shift_JIS/Windows-31J decoding, files, Ktor/CIO, command-line processing, and desktop reports.

That common `GhostModelBackend` seam is intended for later Android implementations. A Gemini Nano adapter can use the ML Kit Prompt API for availability, download, warmup, token budgeting, and structured output. A sideloaded-model adapter can use LiteRT-LM for engine/conversation lifecycle and accelerator fallback. Both can emit the same normalized generation events without bringing their SDK types into common code. MediaPipe LLM Inference is not targeted by this spike.

Nanidroid's existing production `HttpsURLConnection` migration is separately tracked in [issue #259](https://github.com/xCatG/Nanidroid/issues/259); this harness does not depend on it.
