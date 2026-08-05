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

The adaptive ghost-stage suite contains exactly 34 named cases: nine window-size
grid cases, 19 product-state cases, and six pairwise theme/direction/font/density
cases. Its deterministic Layoutlib fixtures exercise the production shell,
stage, bubbles, compositor, collision overlay, debug content, and durable-prompt
content without reading files, using the network, or depending on a clock. The
debug-sheet, full-modal, and durable-prompt previews use static Layoutlib hosts;
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

The versioned, deterministic 67-case manifest is generated before capture and
combines three authoritative sources (12 live profiles, 34 fixtures, and 21 NAR
representative/profile cases):

- live production `CatTailApplication` captures through Android CLI at the eight
  required dp sizes, font scales 1.0/1.5/2.0, and native-density passes;
- all 34 current Compose screenshot fixtures after
  `validateDebugScreenshotTest`; and
- Task 17 production-stage probes for exactly `2elf-2.46`, `Snake and Otacon
  V1.3.2`, `Nanika Atsume 1.0.1`, `Watchdog Bancho`, `Big Red Button`,
  `Earthquake Rescue Duo`, and `tewire-sen`, captured in portrait,
  compact-landscape, and tablet profiles.

The live path uses Android CLI `run`, `layout --pretty`, `screen capture`, and
annotated `screen capture -a`. For every live case it also runs `uiautomator
dump`, retains the pulled XML beside the Android CLI layout as
`<case>.layout.uiautomator.xml`, and reads the measured `ghost-stage` bounds from
that XML. A missing or ambiguous stage resource ID, empty XML, or root bounds
that disagree with the settled logical display is a failure. NAR cases use the
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
- `summary.json` and `summary.md`; and
- `manual-inspection.md`.

The capture command exits after writing `captured-awaiting-manual-inspection`;
that status is not a passing audit. The executing reviewer owns
`manual-inspection.md`. Open every fresh PNG at its original resolution and fill
one result row per manifest case, including the exact screenshot SHA-256 and the
requested/measured window and stage evidence. Set `Audit status: complete` only
after every row is an explicit `pass`. Then complete the
interaction checklist for touch, mouse single/double click, keyboard and D-pad,
bubble scrolling/actions, debug presentations, rotation/recreation, input IME,
the passive stall prompt, TalkBack plus Switch Access or Voice Access, collision
custom actions, focus recovery, and exact SHIORI diagnostics. The audit fails on
case-count mismatch or any unresolved visual/interaction result; automated pixel
comparison is supporting evidence, not a substitute for this inspection.

Finish with the fail-closed verifier. It checks the current manifest hash, the
capture summary and cleanup status, all 67 unique artifact rows, all 12 interaction
checks, and refuses any blank, stale, duplicate, unchecked, or non-pass result. It
is the only mode that changes the summary status to `complete`:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/run-ui-visual-audit.ps1 `
  -VerifyManualInspection
```
