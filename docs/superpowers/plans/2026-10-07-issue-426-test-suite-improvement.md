# Test Suite Improvement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Preserve the approved sequential fresh GPT-6.1-Sol implementer / independent GPT-6-Sol reviewer workflow; do not dispatch concurrent tasks.

**Goal:** Make test evidence truthful, fixtures repeatable, assertions useful and the self-contained device suite executable in CI.

**Architecture:** Improve existing JUnit 4/Compose tests and host scripts within seven bounded issues. A complete method manifest and small portable explicit-serial runner separate self-contained regressions from fixture/host/diagnostic probes. CI consumes the same runner and same-run APK provenance.

**Tech Stack:** Kotlin/Compose, JUnit 4, coroutines-test, PowerShell 7, existing AndroidJUnitRunner, pinned Gradle/Android toolchain, GitHub Actions.

**Spec:** The seven child issues linked above contain the reviewed requirements, ownership, acceptance checks and verification commands.

## Global Constraints

- Work on the current approved recreation revision; pinned `983253b267f56950903e5d9f88c45377ee264471` links are review provenance, not a reset target. Preserve intervening accepted changes.
- One app module, Kotlin/Compose/ViewModel/lifecycle-aware StateFlow/manual constructor injection; `com.cattailsw.nanidroid`, minSdk 31, compile/target 37, arm64-v8a/x86_64.
- Pinned wrapper/catalog, JDK 17, NDK 28.2.13676358, CMake 3.22.1. No production/native/dependency changes, product features, migration, DI/navigation rewrite or broad parity expansion.
- Never read/use superseded implementation/history or adjacent repositories. Preserve fresh-install/no-migration policy and old external files.
- Honor existing session authorization for disposable-emulator verification with exact serial; never infer physical-user-device permission. No push/public PR/merge/publication/signing-key handling is authorized by this plan.
- Keep secrets/private NARs/local.properties/APKs/build outputs/raw evidence outside Git. Local focused commits use `codex/` branches after verification/review.
- M5's missing LOBO Pixel cycles and intermittent Compose wrong-thread/keyboard failures remain accepted exceptions, not passing/fixed results.
- No numerical test-count target or new screenshot/golden/locale framework; related issue #425 is independent.

## Review Focus

- Unknown/empty selection and stale expected archive SHA must fail before any device/build/install side effect (Task 1).
- Persistent pre-existing root-test or outside-shell sibling must remain unchanged while test-owned roots disappear (Task 2).
- New/unclassified/missing/duplicate/assumption-skipped methods cannot silently make a device gate green (Task 3).
- Cancelled queued completion or pending gesture callback must be checked after its real terminal/timing boundary (Task 4).
- Same-length payload corruption and failed/mismatched same-run APK evidence must be detected (Tasks 5 and 7).

## Task order and ownership

| Task | Issue | Owned deliverable | Depends on |
| --- | --- | --- | --- |
| 1 | [#427](https://github.com/xCatG/Nanidroid/issues/427) | Two existing native/corpus runners, small policy/self-check files, current outcome docs | — |
| 2 | [#428](https://github.com/xCatG/Nanidroid/issues/428) | Five named instrumentation fixture files and optional owned-directory test rule | — |
| 3 | [#429](https://github.com/xCatG/Nanidroid/issues/429) | Device runner/complete manifest/self-check, current run docs, guard-only edits and portable host path/serial inputs | 1, 2 |
| 4 | [#430](https://github.com/xCatG/Nanidroid/issues/430) | Two named JVM and three named device wait/diagnostic test files | 3 |
| 5 | [#431](https://github.com/xCatG/Nanidroid/issues/431) | NarArchiveReaderTest byte oracle only | — |
| 6 | [#432](https://github.com/xCatG/Nanidroid/issues/432) | Two confirmed duplicate methods/unused code and their manifest entries | 2, 3 |
| 7 | [#433](https://github.com/xCatG/Nanidroid/issues/433) | Workflow device job, same-run APK provenance and CI docs | 2, 3, 4, 6 |

Execute sequentially in the table's numeric order; independence is a dependency property, not permission for concurrent work. Reuse each responsible implementer for its review fixes.

## Shared interfaces

Task 1 corpus default is `-Mode Diagnostic`; `-Mode Acceptance -ExpectationsPath <json>` requires schemaVersion 1 rows `{label, sha256, expectedClassification, basis}`, complete exact label/SHA mapping before side effects, and exact actual-category matches. Hard failures/unverified cannot be expected; no invented real archive expectations. Both old runners provide host-only `-SelfCheck`.

Task 3 provides:
```powershell
pwsh -NoProfile -File tools/test-device-suite.ps1 -ListOnly -Suite self-contained
pwsh -NoProfile -File tools/test-device-suite.ps1 -SelfCheck
pwsh -NoProfile -File tools/test-device-suite.ps1 -Suite self-contained -Serial $serial -Adb $adb -OutputDirectory $output -SkipBuild
```
List/self-check use no device; execution requires exact owned serial. One sorted method selection runs in one instrumentation invocation with bounded suite deadline; batching only for proven command-size constraint. The four classification names are `self-contained`, `corpus-fixture`, `host-orchestrated`, `diagnostic-device`. Task 6 removes exactly two manifest entries; Task 7 uses SkipBuild with downloaded same-run app/test APKs.

Task 3's summary has `schemaVersion: 1`, `suite/sourceCommit/serial/appApkSha256/testApkSha256` strings, `device:{api:int,abi:string}`, `selectedMethods:string[]` sorted, `expectedCount:int`, `observedMethods:string[]` retaining actual JUnit order and terminal duplicates, `observedCount:int`, `results:[{methodId,status,reason,stdoutPath,stderrPath}]`, `counts:{passed,skipped,failed,incomplete}` integers, `durationSeconds:number`, `cleanupStatus:passed|failed|not-run`, `outcome:passed|failed|incomplete`. Result status is `passed|skipped|failed|incomplete`. Compare identity sets/multisets, not array execution order. Task 7 requires sorted observed IDs equal selected IDs exactly once, all passed, no skipped/failed/incomplete, cleanupStatus passed, matching source/APK provenance.

Task 7 preserves build/check/artifact names, writes `app/build/outputs/apk/artifact-provenance.json` with schemaVersion 1/sourceCommit/apks `[{path,sha256}]`, and downloads `android-apks` to that same APK layout. Device job uses ubuntu-24.04, one API31 Google APIs x86_64 owned emulator on port5554, 1080×2400/420dpi/portrait/font1 with usable fixture viewport >=600dp. No second native/APK build or corpus fixture.

## Execution checkpoints

### Task 1: Truthful runner policy

- [ ] Add host-only selection/record/expectation synthetic failures, including downgrade and no-process-start sentinels.
- [ ] Implement the minimal policy/preflight/summary in the existing runners; preserve synthetic rejection and host-kill guards.
- [ ] Run the three issue-1 host self-check commands; report real fixture verification only when authorized/input evidence exists.

### Task 2: Owned fixture roots

- [ ] Establish repeat positive/negative root and sibling-sentinel expectations.
- [ ] Isolate the positive importer and scoped UI fixtures; cleanup only invocation-owned roots after UI disposal.
- [ ] Build androidTest and run the issue's exact selectors twice without clear/reinstall; record unchanged pre-existing app data.

### Task 3: Complete explicit suite

- [ ] Enumerate every method/class, including extra classes in existing files, and prove inventory/transcript failures with host self checks.
- [ ] Implement the fixed runner/manifest interface, absent-versus-partial argument guards and portable SDK/fixture inputs.
- [ ] Validate list/self-check on Windows/Linux, compile guards, and run the self-contained suite on the authorized owned emulator; report exact selected/observed counts.

### Task 4: Meaningful waits

- [ ] Prove terminal cancellation/event boundaries and synthetic timeout diagnostics.
- [ ] Replace scoped polling/capture delays without erasing real IO/native/gesture timing.
- [ ] Run focused JVM/assembly and the four exact gesture selectors three times; retain every first failure and corpus gaps.

### Task 5: Exact payload oracle

- [ ] Add nonuniform independently expected full bytes and demonstrate same-length mutation failure.
- [ ] Remove callback-count/read-count inference and use owned temporary output.
- [ ] Run the full NarArchiveReaderTest class; retain cancellation/CRC/limit/containment coverage.

### Task 6: Two confirmed duplicate removals

- [ ] Record retained component/engine/Activity coverage mapping.
- [ ] Delete exactly two methods and only newly unused local fixture/import code; remove exactly two selectors.
- [ ] Run focused JVM/assembly/list/self-check and retained four-class instrumentation; keep isolation and all distinct tests.

### Task 7: CI execution and evidence

- [ ] Add same-run source/APK provenance and one explicit owned-emulator job consuming the existing APK artifact.
- [ ] Validate summary/identity/nonzero failure/upload behavior and always preserve logs/reports; no retries.
- [ ] Validate Linux host checks and first-attempt fixture-free emulator execution. Run GitHub CI only under existing publication/dispatch authorization; otherwise report that gate unverified.

Each task ends with actual check evidence, independent review, responsible-worker fixes and a focused local commit when authorized; move to the next task only after that gate. Finish with full JVM/local checks and one self-contained device suite on unchanged integrated code; repeat only for new failures/changes, never erase a first red.


