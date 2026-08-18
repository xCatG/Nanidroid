# Fixed 23-NAR Corpus Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair the Snake regression lifecycle and add a fail-closed comparator for summary, all 23 raw results, and all 23 screenshots.

**Architecture:** Keep the device probe, host runner, comparison command, and static comparison contract separate. The comparator validates report shape and declared build identities before recursively comparing typed JSON with path-scoped normalization and reviewed stochastic hashes.

**Tech Stack:** Kotlin/JUnit 4 Android instrumentation tests, PowerShell 7, JSON, SHA-256, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-17-fixed-23-nar-corpus-comparison-design.md`

## Global Constraints

- Do not modify production Kotlin, JNI, CMake, or `src/main/AndroidManifest.xml`.
- Do not change `docs/testing/nar-corpus-manifest.json` or its schema.
- Keep the new contract separate from PR #394 rolling metadata.
- Accept stochastic inequality only for the six exact labels and reviewed hashes in the spec.
- Normalize only enumerated run IDs, timestamps, durations, report roots, and run-owned paths after validating their declared JSON kinds. Scope the embedded dialogue run-ID path only to the exact Yes Man row and path shape.
- Validate explicit base/candidate production commit/debug APK identities and one shared harness commit/runner/instrumentation/test APK identity before excluding those exact leaves from behavioral equality.
- Do not add, modify, commit, download, or copy `.nar` payloads.
- Do not run the full device corpus before focused host verification and review.

---

### Task 1: Correct the authored Snake lifecycle

**Files:**
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/corpus/NarCorpusRuntimeTest.kt`
- Modify: `scripts/run-nar-corpus-audit.ps1`

**Interfaces:**
- Consumes: parsed `choiceIds`, primary `OnChoiceSelectEx`, and the current playable-response fallback.
- Produces: a post-name `titlenone` transaction whose response visibly contains `beginnerStart` and `beginnerEnd`.

- [ ] **Step 1: Write failing Kotlin expectations**

Replace every post-name FAQ expectation with this literal transaction:

```kotlin
"OnChoiceSelectEx" to listOf("Nope", "titlenone")
```

Add a test whose `OnNameTeach` response contains only `titlenone`, whose title response contains `beginnerStart` and `beginnerEnd`, and whose request list contains no `faq`. Add a second test that hides `titlenone` and stops after `OnNameTeach`.

- [ ] **Step 2: Compile to prove RED**

Run: `.\gradlew.bat compileDebugAndroidTestKotlin`

Expected: failure because the title constants and beginner-choice validation do not exist.

- [ ] **Step 3: Implement the minimum Kotlin lifecycle**

Replace FAQ constants with:

```kotlin
const val SNAKE_TITLE_NONE_ID = "titlenone"
const val SNAKE_TITLE_NONE_LABEL = "Nope"
val SNAKE_BEGINNER_CHOICE_IDS = setOf("beginnerStart", "beginnerEnd")
```

After playable `OnNameTeach`, require visible `titlenone`, dispatch only it through `probeChoice`, and require both beginner IDs in the playable title response.

- [ ] **Step 4: Write failing host fixtures**

Use this exact valid post-name step in DryRun and post-interaction fixtures:

```powershell
[pscustomobject]@{
    eventId = 'OnChoiceSelectEx'; status = 200; hasExactValue = $true
    references = @('Nope', 'titlenone'); choiceIds = @('beginnerStart', 'beginnerEnd')
}
```

Add invalid fixtures for `faq`, hidden `titlenone`, and a title response missing either beginner ID.

- [ ] **Step 5: Run exact-root DryRun to prove RED**

Run:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/run-nar-corpus-audit.ps1 -DryRun -CorpusRoots 'C:\Users\yenchi\.codex\worktrees\27f9\Nanidroid\build\reports\pr393-corpus\2elf-2.46.nar','C:\Users\yenchi\.codex\worktrees\27f9\Nanidroid\build\reports\pr393-corpus\direct','C:\Users\yenchi\.codex\worktrees\27f9\Nanidroid\build\reports\pr393-corpus\pcPets\Ukagakas'
```

Expected: failure at the new title/beginner assertion while the host helper still models FAQ.

- [ ] **Step 6: Align host lifecycle and sentinels**

Make `Get-SnakeDialogueLifecycle` require next identifier `titlenone`, a playable effective response, and visible `beginnerStart`/`beginnerEnd`. Rename FAQ locals and sentinel text to title/beginner terms. Preserve primary/fallback envelopes and References 0-6.

- [ ] **Step 7: Prove GREEN and commit**

Run the commands from Steps 2 and 5, then:

```powershell
git add src/androidTest/java/com/cattailsw/nanidroid/corpus/NarCorpusRuntimeTest.kt scripts/run-nar-corpus-audit.ps1
git commit -m "test: follow authored Snake title lifecycle"
```

### Task 2: Define the static comparison contract

**Files:**
- Create: `docs/testing/nar-corpus-comparison-contract.json`

**Interfaces:**
- Consumes: exact manifest labels/archive hashes and twelve recovered UTF-8 hashes.
- Produces: schema `1`, exact normalization paths, and six `stochasticDialogueValues` rows.

- [ ] **Step 1: Add a failing wrong-archive-SHA host case**

Invoke the future comparator with a stochastic row bound to a wrong SHA and expect nonzero exit plus `stochastic archive SHA`.

- [ ] **Step 2: Run host tests to prove RED**

Run: `pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/test-compare-nar-corpus-runs.ps1`

Expected: failure because the comparator and contract do not exist.

- [ ] **Step 3: Write the contract**

Use this shape for all six exact labels; the 2elf row additionally declares its
required-evidence and exact-name sentinel mirrors:

```json
{
  "label": "Watchdog Bancho",
  "archiveSha256": "8a3f1dcaa4c34a625bf16c0a0ada2e3dff2d49fc029e014807aafb164f196dca",
  "jsonPath": "dialogueProbe.value",
  "allowedUtf8Sha256": [
    "169a2b0b07526e9892419399a4fde6e4c3608c11ffe35b5ac59bf20a9a2609c1",
    "bcdb3aeec739afa5e74dac507c8cc8ad21661a06ba2b81d81b707f3df844da2e"
  ],
  "source": { "archiveEntry": "ghost/master/ghost-bootend.kis", "lineRanges": ["44-45"], "reviewedEvidence": ["pr393 base", "pr393 candidate"] }
}
```

```json
"summaryMirrors": {
  "requiredEvidence": true,
  "sentinelName": "slice2-2elf-dialogue-value-nonblank",
  "sentinelProperty": "observed"
}
```

Copy all hashes and provenance from the spec. Enumerate scalar metadata paths and run-owned string paths; do not use property-name wildcards.

- [ ] **Step 4: Validate JSON and manifest binding**

Run a PowerShell check that parses both JSON files, requires six rows, finds each label exactly once in the manifest, and compares its archive SHA exactly. Expected: exit 0.

### Task 3: Record separate production and harness identities

**Files:**
- Modify: `scripts/run-nar-corpus-audit.ps1`

**Interfaces:**
- Consumes: optional verified production checkout, fixed-harness test APK, production commit, and harness commit.
- Produces: separate `production` and `harness` summary objects with source/APK hashes.

- [ ] **Step 1: Add failing DryRun argument-contract probes**

Invoke the runner recursively with an obsolete injected production APK, a partial
production-checkout identity, a malformed/wrong production commit, and a wrong
harness commit. Require each invocation to fail before corpus/device work. Add
clean and dirty temporary checkout probes plus missing/ambiguous deterministic
debug-APK selection probes.

- [ ] **Step 2: Run exact-root DryRun to prove RED**

Run the Task 1 Step 5 command. Expected: failure in the new external-identity probes because the parameters and validator do not exist.

- [ ] **Step 3: Implement external fixed-harness mode**

Add `-ProductionCheckoutPath`, `-ProductionCommit`, and `-HarnessCommit`.
Require all three or none, including in DryRun, and reject obsolete external
production/test-APK parameters. In external mode require the full lowercase
production commit to equal the clean production checkout `HEAD`, build
`assembleDebug` there, require exactly one resulting `*-debug.apk`, hash it, then
independently require `HarnessCommit` to equal the clean harness checkout `HEAD`
and build `assembleDebugAndroidTest` there. In legacy one-tree mode retain the
current combined build for standalone audits but mark production and harness as
the same current commit. Re-read the production and harness identities after
their builds, require exact equality with pre-build identity, and only then hash
the selected APKs; ignored Gradle outputs remain permitted.

- [ ] **Step 4: Record auditable identity objects**

Write:

```powershell
production = @{ commit = $ProductionCommit; debugApkSha256 = $apkInfo.DebugApkSha256 }
harness = @{
    commit = $HarnessCommit
    tree = (git rev-parse "$HarnessCommit`^{tree}").Trim()
    runnerSha256 = (Get-FileHash $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    instrumentationSourceSha256 = (Get-FileHash $instrumentationSource -Algorithm SHA256).Hash.ToLowerInvariant()
    testApkSha256 = $apkInfo.TestApkSha256
}
```

Keep legacy `git`/`apks` report detail for local diagnostics, but the comparator uses the new identity objects.

- [ ] **Step 5: Prove GREEN and commit**

Run exact-root DryRun, then commit the runner change with `git commit -m "test: record fixed corpus harness identity"`.

### Task 4: Implement exhaustive comparison test-first

**Files:**
- Create: `scripts/compare-nar-corpus-runs.ps1`
- Create: `scripts/test-compare-nar-corpus-runs.ps1`

**Interfaces:**
- Consumes: comparison kind, base/candidate roots, manifest, contract, two production identity pairs, one shared five-field harness identity, required successful base/base report in base/candidate mode, optional output path.
- Produces: exit 0 plus `{ passed, identities, comparedLabels, rawResultsCompared, screenshotsCompared, differences }`, or nonzero with bounded diagnostics.

- [ ] **Step 1: Build complete synthetic fixtures**

Create 23 rows from the real manifest. Write `<safeLabel>/result.json`, mirror every manifest-required top-level property into `requiredEvidencePayload`, and write deterministic screenshot bytes.

- [ ] **Step 2: Write failing exactness and identity cases**

Require independently collected successful roots in `BaseBase` mode to pass and
reject byte-identical copied evidence roots in both `BaseBase` and
`BaseCandidate` before canonical comparison, including a copied root whose JSON
differs only by whitespace. Require the runner's top-level run ID, all result-row
mirrors, and declared raw run-owned-path mirrors to agree within each root, then
require distinct run IDs across roots. Require missing, wrong, and swapped
base/candidate production declarations to fail. Require missing or different
harness commit, runner hash, instrumentation hash, or test APK hash to fail.
Mutate both roots to the same sentinel failure and require failure.

- [ ] **Step 3: Write failing base/base prerequisite cases**

Run `BaseCandidate` without `-BaseBaseReportPath`, with a failed prerequisite,
and with prerequisite base identity, device, manifest, contract, or harness
changed one at a time. Require each case to exit before candidate behavioral
comparison. Require `BaseBase` to reject distinct production identities.

- [ ] **Step 4: Write failing inventory and hidden-difference cases**

Require failures for a renamed raw directory, missing/duplicate label, extra raw result, extra screenshot, and a candidate-only raw field absent from summary.

- [ ] **Step 5: Write failing normalization cases**

Require success when only declared run IDs, timestamps, durations, report roots, and run-owned paths differ. Require Yes Man's exact embedded run path to normalize, but reject a run ID in another dialogue value. Reject duration/path scalar-kind changes. Change adjacent `classification` and require failure at `classification`.

- [ ] **Step 6: Write failing stochastic and screenshot cases**

For Watchdog use the two recovered reviewed values and require success. Add a 2elf case that sets its raw value, required-evidence mirror, and exact-name sentinel mirror to the two reviewed values and requires success. Break each mirror independently and require failure. Require failure for an unlisted value, archive-SHA mismatch, and one changed screenshot byte.

- [ ] **Step 7: Run the host suite to prove RED**

Run the Task 2 Step 2 command. Expected: named missing behavior fails.

- [ ] **Step 8: Implement validation helpers**

Implement `Read-JsonObject`, `Get-ManifestRowsByLabel`, `Assert-DeclaredIdentity`, `Assert-SuccessfulRun`, `Get-ReportInventory`, and `Assert-RequiredEvidenceMirror`. Reject failed sentinels, failures, abort/timeout, unverified cleanup/residue, failed rows, duplicate labels, path/label/SHA mismatch, missing/extra artifacts, malformed contract rows, and identity mismatch before comparing behavior.

- [ ] **Step 9: Implement typed recursive comparison**

Implement `Compare-JsonValue` over null, object, array, string, boolean, and number. Compare property sets and array lengths before recursion. At scalar leaves apply only exact contract normalization or label/SHA-bound stochastic hashes, appending structured differences.

- [ ] **Step 10: Enforce order, hash screenshots, and emit the report**

Hash all 23 PNGs and compare exact sets/hashes. In `BaseBase`, require identical production identities and emit passed status, device, manifest hash, contract hash, production identity, and harness identity. In `BaseCandidate`, validate that report before candidate comparison. Write bounded JSON to `-OutputPath` and throw when differences exist.

- [ ] **Step 11: Prove GREEN, simplify, and commit**

Run the focused suite. Remove duplication only inside the new comparator/test diff, rerun the suite, then:

```powershell
git add docs/testing/nar-corpus-comparison-contract.json scripts/compare-nar-corpus-runs.ps1 scripts/test-compare-nar-corpus-runs.ps1
git commit -m "test: compare complete NAR corpus evidence"
```

### Task 5: Document reproducibility

**Files:**
- Modify: `docs/testing/nar-corpus.md`
- Modify: `docs/testing.md`

**Interfaces:**
- Consumes: comparator CLI.
- Produces: copyable clean base/base then base/candidate protocol.

- [ ] **Step 1: Document comparator input and output**

Add one fail-fast PowerShell sequence. First invoke `BaseBase` with an output report, inspect `$LASTEXITCODE`, and throw before continuing on failure. Only then invoke `BaseCandidate` with `-BaseBaseReportPath` plus both roots, manifest, contract, two production identity pairs, the shared four-field harness identity, and output report. State that summary, 23 raw results, and 23 screenshots are exhaustive inputs.

- [ ] **Step 2: Document clean-run ordering**

Require pristine base and candidate debug APK builds, one committed fixed harness that builds its own byte-identical test APK for each invocation, clean base/base first, snapshot restore, then base/candidate with the same fixed runner/harness identity, emulator/fingerprint, API, ABI, density, corpus, manifest, and ordering. Forbid dirty overlays, caller-supplied test APKs, old probes, selective retries, and favorable random-output selection.

- [ ] **Step 3: Separate rolling metadata and commit**

State that PR #394 rolling metadata is neither modified nor consulted, then:

```powershell
git add docs/testing.md docs/testing/nar-corpus.md
git commit -m "docs: define fixed NAR comparison protocol"
```

### Task 6: Verify and audit scope

**Files:**
- Verify all files from Tasks 1-4.

**Interfaces:**
- Consumes: completed harness changes.
- Produces: fresh host, DryRun, compile, whitespace, and scope evidence.

- [ ] **Step 1: Run focused PowerShell tests**

Run: `pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/test-compare-nar-corpus-runs.ps1`

- [ ] **Step 2: Run exact recovered-root DryRun**

Run the Task 1 Step 5 command.

- [ ] **Step 3: Compile Android instrumentation tests**

Run: `.\gradlew.bat compileDebugAndroidTestKotlin`

- [ ] **Step 4: Check whitespace and forbidden scope**

```powershell
git diff --check 15aae15a..HEAD
git diff --name-only 15aae15a..HEAD
git status --short
```

Expected: no whitespace errors; no `src/main/jni`, CMake, application manifest, NAR manifest, or `.nar` changes.
