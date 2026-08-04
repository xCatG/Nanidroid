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
