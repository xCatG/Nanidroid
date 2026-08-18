# Testing Nanidroid

Run all commands from the repository root with the Gradle wrapper on Windows.

## Local JVM and coverage

```powershell
.\gradlew.bat testDebugUnitTest jacocoTestReport
```

The JaCoCo HTML report is written to `build/reports/jacoco/testDebugUnitTestCoverage/`.

## Connected Android tests

Use an API 31–37 emulator or device, then run:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

The suite uses `NanidroidTestRunner`, which installs Hilt's test application.

## Compose screenshot tests

Screenshot previews live in `src/screenshotTest/`; committed references live in
`src/screenshotTestDebug/reference/`.

```powershell
.\gradlew.bat updateDebugScreenshotTest
.\gradlew.bat validateDebugScreenshotTest
```

Inspect every changed/generated reference PNG before committing it. CI validates
existing references only; updating a baseline requires human image-diff review.
After a screenshot-plugin upgrade, regenerate and review every golden. The
HTML comparison report is `build/reports/screenshotTest/preview/debug/index.html`.

The adaptive ghost-stage suite contains exactly 31 named cases: nine window-size
grid cases, 16 product-state cases, and six pairwise theme/direction/font/density
cases. Its deterministic Layoutlib fixtures exercise the production shell,
stage, bubbles, compositor, collision overlay, and durable-prompt
content without reading files, using the network, or depending on a clock. The
durable-prompt previews use static Layoutlib hosts;
they do not replace connected tests of the real platform modal surfaces.

## NAR corpus (introduced in Task 17)

With the dedicated disposable emulator running, use:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-nar-corpus-audit.ps1 -DeviceSerial emulator-5554
```

Run a host-only preflight check:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-nar-corpus-audit.ps1 -DryRun
```

For explicit roots that include file inputs (for example `.\\2elf-2.46.nar`) and directories, pass
`-CorpusRoots` explicitly:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-nar-corpus-audit.ps1 -DryRun -CorpusRoots .\2elf-2.46.nar, .\build\ui-audit
```

If `apksigner` is not in `PATH`, pass it explicitly:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-nar-corpus-audit.ps1 -DeviceSerial emulator-5554 -ApkSignerPath "C:\path\to\apksigner.bat"
```

The corpus runner and its manifest are added in Task 17; do not place local
archives or generated corpus reports under version control.

- `docs/testing/nar-corpus-manifest.json` contains manifest labels, versions, expected
  package kinds, required evidence, allowed classification, and canonical hashes.
- `docs/testing/nar-corpus.md` defines script behavior and per-run output.
- `docs/testing/nar-corpus-comparison-contract.json` defines the closed set of
  reviewed stochastic dialogue hashes. Follow the base/base-first, fixed-harness
  protocol in `docs/testing/nar-corpus.md`; PR #394 rolling metadata is separate
  and is not a behavioral comparison input.

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
powershell -ExecutionPolicy Bypass -File scripts/run-nar-corpus-audit.ps1 -DeviceSerial emulator-5554
```

Expected outputs:

- `build/reports/nar-corpus/summary.json`
- `build/reports/nar-corpus/summary.md`
- `build/reports/nar-corpus/screenshots/<label>.png`
- `build/reports/nar-corpus/<label>/result.json`
- `build/reports/nar-corpus/failures/<label>.txt` (on failures)

The report run intentionally refuses:

- missing required arguments
- non-emulator devices
- API outside 31–37
- unsupported ABI
- pre-existing target or test package installation/data
- pre-existing app-owned storage
- missing manifest hash matches
- missing results or timeouts

The five-minute per-archive timeout belongs only to this disposable host harness.
It does not change Nanidroid's runtime hang policy: the app never cancels a ghost
automatically and exposes the explicit Stop action after 30 seconds. A device-side
adb timeout produces a partial report and stops the corpus run without attempting
more commands through the unresponsive transport.

Expected incompatible and unsupported archives count as passing audit rows only
when their structured classification and diagnostics match the manifest. The sole
accepted native-crash path additionally requires the exact known Kawari target,
`SIGSEGV`, and `libkawari8` evidence contract. Summary sentinels verify all 23 rows,
host/device cleanup, representative parser and dialogue behavior, authored collision
geometry, optical bounds, asymmetric stages, and unsupported non-ghost packages.

## Phase 1 shipped-state audit

The compatibility decision for removing the unshipped durable workflows is
recorded in `docs/modernization/phase1-shipped-state-ledger.json`. Verify its
Path-A-only schema, exact audited head, Git ancestry, writer epochs, application
identity, exact audit/observation dates and GitHub limitation, exact required
evidence, closed schema-v1 object keys, persistent-resource contracts, and
owner-attestation requirement offline. Unrelated generic-valid evidence remains
the explicit extension point:

```powershell
python -m unittest tools.test_verify_phase1_shipped_state_audit
python tools/verify_phase1_shipped_state_audit.py
```

The verifier requires full Git history for the three effective writer commits.
It makes no network, device, APK, WorkManager, DownloadManager, URI-grant, or
filesystem-cleanup calls. Refreshing dated GitHub observations requires an
explicit schema revision and is not part of routine verification.

## Full verification

```powershell
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest
.\gradlew.bat validateDebugScreenshotTest jacocoTestReport
```

## Adaptive UI visual audit (Task 18)

The final hands-on audit is driven by `scripts/run-ui-visual-audit.ps1`. It is
an emulator-only, fail-closed workflow: it starts its own `Nanidroid_API_37`
instance from an existing immutable snapshot, refuses a running/reused device or
pre-existing Nanidroid data, captures the original display configuration before
any mutation, and restores and verifies that configuration in `finally`.
PowerShell 7 or newer is required; invoke the runner with `pwsh`, not Windows
PowerShell 5.1 (`powershell.exe`).

Provision a clean `default_boot` snapshot for `Nanidroid_API_37` before running
the audit. The runner loads that snapshot with `-no-snapshot-save` and
`-read-only`; it never creates, overwrites, or deletes an AVD snapshot. The
snapshot must contain no installed `com.cattailsw.nanidroid` or
`com.cattailsw.nanidroid.test` package and no retained app data. Stop any running
instance of the AVD first because the runner will not take ownership of an
existing emulator.

Run the host-only contract checks first. Dry-run performs no build, device,
emulator, report-directory, or snapshot mutation:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/run-ui-visual-audit.ps1 -DryRun `
  -CorpusRoots C:\work\src\Nanidroid\2elf-2.46.nar, `
    C:\work\src\Nanidroid\build\ui-audit\ghosts, `
    C:\work\src\Nanidroid\build\ui-audit\pcPets
```

Run the complete capture workflow with the same corpus roots:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/run-ui-visual-audit.ps1 `
  -DeviceSerial emulator-5554 `
  -AvdName Nanidroid_API_37 `
  -SnapshotName default_boot `
  -CorpusRoots C:\work\src\Nanidroid\2elf-2.46.nar, `
    C:\work\src\Nanidroid\build\ui-audit\ghosts, `
    C:\work\src\Nanidroid\build\ui-audit\pcPets
```

The runner records and restores the physical/override `wm size` and `wm
density`, automatic and user rotation, display rotation, `font_scale` (including
an originally absent setting), theme, locale, and network state. The 160 dpi
overrides are used only inside the reversible workflow. Native-density phone and
tablet passes retain the physical density. Each profile must settle across two
`wm` readings, match the requested logical size from `dumpsys window displays`,
retain the intended orientation lock, and match the root UIAutomator bounds;
physical display dimensions are not treated as logical orientation evidence.
Locale evidence uses `persist.sys.locale`, falling back when blank to
`ro.product.locale` and then the activity configuration locale.

A transport deadline aborts the run, writes partial host evidence, and disables
every later ADB command. Native-command timeouts terminate the exact owned
process tree, wait for it, drain its redirected streams, and dispose it. The
emulator is launched only with `-read-only` and `-no-snapshot-save`. Immediately
after launch, a hidden, non-redirected watchdog binds the audit host PID/start
time and emulator PID/start time. If the host disappears, the watchdog kills
only that exact emulator tree; it never matches a process name or command-line
pattern. Normal `finally` cleanup stops the watchdog first, restores device
state, asks the owned emulator to exit, and then enforces exact-tree cleanup.
Failure to establish the watchdog handshake aborts the audit.

The versioned, deterministic manifest contains 64 automated cases plus 2
required fresh live interaction artifacts. The automated cases combine three
authoritative sources (12 live profiles, 31 fixtures, and 21 NAR
representative/profile cases):

- live production `CatTailApplication` captures through Android CLI at the eight
  required dp sizes, font scales 1.0/1.5/2.0, and native-density passes;
- all 31 current Compose screenshot fixtures after
  `validateDebugScreenshotTest`; and
- Task 17 production-stage probes for exactly `2elf-2.46`, `Snake and Otacon
  V1.3.2`, `Nanika Atsume 1.0.1`, `Watchdog Bancho`, `Big Red Button`,
  `Earthquake Rescue Duo`, and `tewire-sen`, captured in portrait,
  compact-landscape, and tablet profiles.

The live path uses Android CLI `run`, `layout --pretty`, `screen capture`, and
annotated `screen capture -a`. For every live case it also runs `uiautomator
dump` and retains the pulled XML beside the Android CLI layout as
`<case>.layout.uiautomator.xml`. UiAutomator XML must contain exactly one
`ghost-safe-stage`, whose exact bounds become the measured stage. Independently,
the Android CLI JSON and UiAutomator XML must each contain exactly one
`list-ghost`; the CLI integer center must equal the floor center of the XML
bounds. Normal live profiles apply the same independent center/bounds check to
exactly one `surface-kero` and `surface-sakura` and require both verified centers
inside the safe stage. The 480x230 and 230x400 tiny fallback profiles explicitly
require both surface nodes to be absent from both sources while retaining the
toolbar-anchor cross-check. A missing or duplicate required node, wrong tiny-mode
presence, mismatched center, out-of-stage surface center, empty capture, or root
bounds that disagree with the settled logical display is a failure. NAR cases use the
Task 17 probe's measured layout and screenshot evidence and do not claim an
Android CLI annotation that was never produced. Each Task 17 invocation has a
180-minute parent budget so it exceeds the build plus all 23 five-minute child
deadlines. The runner retains the validated Task 17 summary independently for
each profile at `nar/<profile>/task17-summary.json` before the next profile can
replace `build/reports/nar-corpus/summary.json`. Fixture cases are clearly
labeled as validated Layoutlib renders rather than production-window captures.

Generated evidence is under `build/reports/ui-audit/` and must not be committed:

- `case-manifest.json` and its SHA-256 in `summary.json`;
- `live/`, `fixtures/`, and `nar/<profile>/` screenshots, annotations, layouts,
  retained Task 17 summaries, and per-representative result evidence;
- `interaction/extracted-choice-surface.png` and
  `interaction/snake-otacon-input-ime-visible.png`, captured manually after the
  automated run at the exact manifest-declared paths;
- `summary.json` and `summary.md`; and
- `manual-inspection.md`.

### Live interaction checkpoint

The capture command does **not** exit before the two interaction PNGs are made.
After the automated and NAR profiles finish, it installs the audited APK, starts
the owned emulator session, prints `Capture the two required interaction PNGs
from this owned emulator session, then press Enter.`, and blocks at that prompt.
Leave that terminal running. In a second terminal, interact with that same owned
`emulator-5554` session and create these files before returning to the prompt:

```powershell
android screen capture --device=emulator-5554 -o build\reports\ui-audit\interaction\extracted-choice-surface.png
android screen capture --device=emulator-5554 -o build\reports\ui-audit\interaction\snake-otacon-input-ime-visible.png
```

The first image must show the extracted choice surface; the second must show the
Snake/Otacon input and IME. Do not copy older artifacts or capture a different
emulator. Press Enter only after both paths exist: the runner immediately
rehashes them, records `interaction-capture.json`, and cleans up its owned
session.

### Snake/Otacon checkpoint setup

Task 17 deliberately removes every corpus archive and app install after each
profile. The UI-audit runner then installs only the audited APK before this
checkpoint, so stage the pinned Snake/Otacon archive from the same corpus roots
in the second terminal before taking the input/IME screenshot. Select the
archive whose SHA-256 is
`1c62ce50ca0daca3a9e14e6d870b02d4df9511dd5b586a7f4da49b402d56cbd5`:

```powershell
$snakeNar = Get-ChildItem C:\work\src\Nanidroid\2elf-2.46.nar, C:\work\src\Nanidroid\build\ui-audit\ghosts, C:\work\src\Nanidroid\build\ui-audit\pcPets -Recurse -File -Include *.nar |
  Where-Object { (Get-FileHash $_ -Algorithm SHA256).Hash.ToLowerInvariant() -eq '1c62ce50ca0daca3a9e14e6d870b02d4df9511dd5b586a7f4da49b402d56cbd5' } |
  Select-Object -First 1
if ($null -eq $snakeNar) { throw 'Pinned Snake and Otacon V1.3.2 archive was not found in the supplied corpus roots.' }
adb -s emulator-5554 push $snakeNar.FullName /sdcard/Download/snake-and-otacon-v1.3.2.nar
```

On that owned emulator, in Nanidroid choose **List Ghosts** → **More Ghost** →
**Install from SD card**, select
`Download/snake-and-otacon-v1.3.2.nar`, and wait for the local install to finish.
Open **List Ghosts** again, select Snake and Otacon, and confirm its switch. Start
the dialogue and take the first choice to open its `OnNameTeach` input; leave its
IME visible for `snake-otacon-input-ime-visible.png`. These steps use the app's
normal local-import and ghost-switch flow while the audit process remains paused;
they do not rerun the corpus harness or replace the audited APK.

The capture command exits after writing `captured-awaiting-manual-inspection`;
that status is not a passing audit. The executing reviewer owns
`manual-inspection.md`. Open every fresh PNG at its original resolution and fill
one result row per automated manifest case, including the exact screenshot
SHA-256 and the requested/measured window and stage evidence. An automated row
marked `pass` must have an empty Defect cell. Capture the two
required interaction PNGs from the current build, then fill their exact manifest
identity, path, SHA-256, invariant text, explicit `pass`, and empty Defect cell
in the separate interaction-evidence table. Set `Audit status: complete` only
after all 64 automated rows and both interaction rows are explicit passes. Then complete the
interaction checklist for touch, mouse single/double click, keyboard and D-pad,
bubble scrolling/actions, collision-overlay alignment, rotation/recreation, input IME,
the passive stall prompt, TalkBack plus Switch Access or Voice Access, collision
custom actions, focus recovery, and exact SHIORI event identity and diagnostics. The audit fails on
case-count mismatch or any unresolved visual/interaction result; automated pixel
comparison is supporting evidence, not a substitute for this inspection.

Capture and completion both require a clean tracked worktree. The capture summary
records the exact git HEAD, resolved debug APK path and SHA-256, and capture start
time. Before report initialization, capture preflights both required interaction
paths; an accidental rerun with either artifact already present aborts without
rewriting the prior summary in `finally`. Finish with the fail-closed verifier. It requires the same current HEAD and
APK, rehashes the exact current report PNG set (64 screenshots, 12 annotations,
and 2 fresh interaction artifacts), rejects extra or stale PNGs, checks all 64
automated rows, both interaction-evidence rows, and all 12 exact checklist labels,
and refuses any blank, stale, duplicate, unchecked, defect-bearing, or non-pass
result. It is the only mode that changes the summary status to `complete`:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/run-ui-visual-audit.ps1 `
  -VerifyManualInspection
```
