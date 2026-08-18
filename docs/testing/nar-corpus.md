# NAR corpus runtime audit

`run-nar-corpus-audit.ps1` executes a deterministic, manifest-driven compatibility
run. For each archive, the script installs/builds target and test APKs once, copies
the archive into one per-run private staging path, invokes
`com.cattailsw.nanidroid.corpus.NarCorpusRuntimeTest` with:

- `narCorpusPath`: private input/output directory for the archive and per-run reports
- `narCorpusSha256`: expected archive digest (required)
- `narCorpusLabel`: manifest label for output grouping

The runner enforces emulator-only execution (`ro.kernel.qemu=1`), API 31–37
targets, supported ABIs (`x86_64`, `arm64-v8a`), debug builds, and a fixed
per-run run-id rooted at `/data/local/tmp/nanidroid-corpus/<run-id>`. It copies each
archive under one constant filename, applies mode `0644`, and removes all per-run
device copies in `finally` blocks.

## Inputs

- `-DeviceSerial`: connected emulator serial. Omit with `-DryRun`.
- `-CorpusRoots` (optional): one or more roots containing `.nar` files. A root can be
  a directory or an individual `.nar` file path.
  Default: `.` and `build/ui-audit`.
- `-ApkSignerPath` (optional): path to `apksigner.bat` or `apksigner`; required
  when it is not discoverable on `PATH`.
- `-ManifestPath` (optional): manifest path.
  Default: `docs/testing/nar-corpus-manifest.json`.
- `-ProductionDebugApkPath`, `-HarnessTestApkPath`, `-ProductionCommit`, and
  `-HarnessCommit` (optional as one all-or-none group): run a separately built,
  pristine production APK with the committed fixed-harness test APK. The runner
  does not build in this mode. It requires `HarnessCommit` to be the checked-out
  clean harness commit with no tracked or untracked overlays and records its Git tree plus the runner, instrumentation
  source, and test APK hashes. Omit all four only for a standalone one-tree audit.
- `-PerArchiveTimeoutMinutes` (optional): hard timeout in minutes for each
  `am instrument` run. This is a host-side corpus safety bound, not the app's
  user-visible script-hang policy. Nanidroid does not cancel a running ghost
  automatically; its explicit Stop action appears after 30 seconds.
- `-BuildTimeoutMinutes` (optional): Gradle assemble timeout in minutes.
- `-DevicePathProbeTimeoutSeconds` (optional): deadline for the run-owned
  device-path absence probes before and after each archive. Defaults to 60 seconds
  and rejects values below 60 so emulator `adbd` can recover after native-crash
  processing without weakening the fail-closed transport cutoff.
- `-DryRun` (optional): preflight-only manifest/hash validation.
- `-MinimumFreeBytes` (optional): guard before test execution, defaults to 3 GB.

Each run removes the exact manifest labels' prior local `result.json` and
screenshot evidence before starting the device work. This prevents an aborted
run from satisfying later sentinel checks with artifacts from an older run.
If `am instrument` reports success with empty stdout and stderr, the audit
classifies it as `instrumentation-empty-protocol`, captures process, Activity
Manager, phase-marker, and logcat diagnostics, and fails closed. It does not
automatically retry that envelope because an early runner or app-process death
must remain visible until the retained evidence proves otherwise.

The script requires:

- connected serial and working `adb`
- existing and valid manifest
- zero pre-existing `com.cattailsw.nanidroid` and
  `com.cattailsw.nanidroid.test` installs
- clear free storage on `/data`
- successful `run-as com.cattailsw.nanidroid`
- build/install once per run

For comparison evidence, use external fixed-harness mode. A locally built
one-tree run is useful for standalone diagnosis, but it cannot prove that pristine
base and candidate production APKs were exercised by an identical probe.

## Output

- `build/reports/nar-corpus/summary.json`
- `build/reports/nar-corpus/summary.md`
- `build/reports/nar-corpus/failures/` (per-archive evidence on failures)
- `build/reports/nar-corpus/screenshots/<label>.png`
- `build/reports/nar-corpus/<label>/result.json`

## Behavior in detail

- Loads manifest entries and requires exact hash-set agreement with discovered local
  archives.
- Pushes each archive once per archive to `/data/local/tmp/nanidroid-corpus/<run-id>/`,
  then copies to app-owned private corpus input path for test invocation.
- Invokes `am instrument` with:
  - `class='com.cattailsw.nanidroid.corpus.NarCorpusRuntimeTest#probesArchive'`
  - `narCorpusPath`
  - `narCorpusSha256`
  - `narCorpusLabelBase64` (the UTF-8 manifest label encoded by the host so adb
    argument boundaries cannot alter it)
- Copies back JSON result and screenshot, validates required evidence fields, and
  records a summary record.
- Forces stage cleanup via `run-as`, guarded `adb rm`, and `am force-stop` in
  every normal completion path, then proves that the app-private input, external
  result, and host temporary paths are absent. If adb itself times out, the runner
  writes the partial report and deliberately avoids issuing further device cleanup
  commands through the hung transport.
- Treats a root `install.txt` as the authoritative package descriptor. Descriptor
  files below that package root are payload; a single depth-two wrapper descriptor
  is accepted only when no root descriptor exists. Ambiguous wrappers and deep-only
  descriptors remain invalid.
- Runs strict global and representative-ghost sentinels over the collected JSON.
  These checks cover result count, zero failures, cleanup, parser provenance,
  authored collision geometry and routing, dialogue sequences, optical bounds,
  asymmetric Sakura/Kero surfaces, and intentionally unsupported package kinds.
- Persists fixed metadata in `summary.json`/`summary.md`, including:
  - git commit
  - separate production commit/debug APK identity
  - fixed harness commit/tree, runner source, instrumentation source, and test APK identity
  - manifest hash
  - manifest fingerprint/rows
  - device fingerprint/API/ABI/density
  - run duration
  - per-archive outcomes

The run fails for:

- malformed/missing required args
- manifest mismatch
- device gate or security violations
- missing result payload or screenshot
- unsupported classification transitions
- unexpected instrument crashes and per-archive timeout
- any failed global or representative-ghost sentinel

Intentionally incompatible and unsupported packages are successful audit rows when
their classification and structured diagnostics match the manifest. A native crash
is accepted only for an exact manifest row with `allowNativeKawariCrash: true` when
the instrument output, crash buffer, target process, `SIGSEGV`, and `libkawari8`
markers all agree and the row permits `incompatible`; every other native crash fails
the run. If any ADB process exceeds its host deadline, the runner records the partial
result and stops issuing device commands because the transport is no longer trusted;
cleanup is reported as unverified for that run.

## Fixed-23 reproducibility comparison

The comparison contract is
`docs/testing/nar-corpus-comparison-contract.json`. It is intentionally separate
from both `nar-corpus-manifest.json` and the rolling PR #394 metadata ledger. The
contract allows only the six reviewed `dialogueProbe.value` hash sets; it does not
change archive membership, classification, or required-evidence policy.

Prepare three retained evidence roots: two complete runs of the pristine base
debug APK, then one complete run of the pristine candidate debug APK. Build the
two production APKs in clean worktrees at their declared commits. Build the test
APK once from the clean committed fixed-harness worktree, and invoke this runner
from that same harness worktree for all three runs using the four external-mode
arguments. Use the same freshly reset emulator, corpus files, manifest, API, ABI,
density, and harness APK for every run. After each complete run, copy the entire
`build/reports/nar-corpus` directory to its retained root. Do not retry individual
archives or substitute rows/screenshots. If any run must be repeated, discard the
three retained roots and repeat the complete base/base-first sequence.

Each of the three full runs uses this shape, changing only the pristine production
APK path and its declared commit:

```powershell
& .\scripts\run-nar-corpus-audit.ps1 -DeviceSerial '<emulator-serial>' `
  -CorpusRoots '<exact-corpus-root-1>','<exact-corpus-root-2>' `
  -ProductionDebugApkPath '<pristine-production-debug.apk>' `
  -ProductionCommit '<production-commit>' `
  -HarnessTestApkPath '<fixed-harness-androidTest.apk>' `
  -HarnessCommit '<fixed-harness-commit>'
```

After recording the expected identities from those builds, compare in this
fail-fast order (values below are explicit placeholders):

```powershell
$common = @(
  '-ManifestPath', 'docs/testing/nar-corpus-manifest.json',
  '-ContractPath', 'docs/testing/nar-corpus-comparison-contract.json',
  '-BaseProductionCommit', '<base-commit>',
  '-BaseDebugApkSha256', '<base-debug-apk-sha256>',
  '-HarnessCommit', '<fixed-harness-commit>',
  '-HarnessTree', '<fixed-harness-tree>',
  '-HarnessRunnerSha256', '<runner-source-sha256>',
  '-HarnessInstrumentationSourceSha256', '<instrumentation-source-sha256>',
  '-HarnessTestApkSha256', '<test-apk-sha256>'
)

& pwsh -NoProfile -File scripts/compare-nar-corpus-runs.ps1 @common `
  -ComparisonKind BaseBase `
  -BaseRoot '<retained-base-run-1>' -CandidateRoot '<retained-base-run-2>' `
  -CandidateProductionCommit '<base-commit>' `
  -CandidateDebugApkSha256 '<base-debug-apk-sha256>' `
  -OutputPath '<retained-base-base-comparison.json>'
if ($LASTEXITCODE -ne 0) { throw 'Base/base prerequisite failed; candidate comparison is forbidden.' }

& pwsh -NoProfile -File scripts/compare-nar-corpus-runs.ps1 @common `
  -ComparisonKind BaseCandidate `
  -BaseRoot '<retained-base-run-1>' -CandidateRoot '<retained-candidate-run>' `
  -CandidateProductionCommit '<candidate-commit>' `
  -CandidateDebugApkSha256 '<candidate-debug-apk-sha256>' `
  -BaseBaseReportPath '<retained-base-base-comparison.json>' `
  -OutputPath '<retained-base-candidate-comparison.json>'
if ($LASTEXITCODE -ne 0) { throw 'Base/candidate corpus comparison failed.' }
```

The comparator independently requires both run summaries to be successful,
then exhaustively compares `summary.json`, the exact 23 raw `result.json` files,
and the exact 23 screenshot hashes. A base/candidate invocation cannot proceed
without a passing base/base report bound to the same base, harness, manifest,
contract, and emulator identity. That proof includes an exact evidence
fingerprint, and every comparison atomically replaces its output with either a
passing report or a bounded structured failure report.
The comparator supports PowerShell 7.0 and preserves JSON scalar kinds with its
own strict reader; it does not require the PowerShell 7.5 `-DateKind` option.
