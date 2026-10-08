# Testing and toolchain

## Current local and explicit device checks

Use PowerShell 7, JDK 17 and the pinned Android SDK/NDK/CMake. Set
`ANDROID_HOME` and `ANDROID_SDK_ROOT` to your SDK. On Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
$adb = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools/adb.exe'
pwsh -NoProfile -File tools/test-device-suite.ps1 -ListOnly -Suite self-contained
pwsh -NoProfile -File tools/test-device-suite.ps1 -SelfCheck
pwsh -NoProfile -File tools/tests/test-device-suite.ps1
# Set $serial to the exact authorized, disposable emulator-<port>, and $output
# to a new ignored directory. No default or first-attached-device selection.
pwsh -NoProfile -File tools/test-device-suite.ps1 -Suite self-contained -Serial $serial -Adb $adb -OutputDirectory $output -SkipBuild
```

Linux uses `bash ./gradlew` for the same tasks and `platform-tools/adb`.
Offline execution requires the dependency cache; an empty host must resolve its
own dependencies first. Omit `-SkipBuild` to build both debug APKs in the runner.
The runner resolves paths from its own location and adb from `-Adb`, SDK
environment variables, then PATH. Execution requires a clean Git checkout,
including the index and untracked files, before output/build/adb work; list and
self-check modes need no Git. The runner rechecks unchanged clean source before
installation and records that commit. `-SkipBuild` assumes the caller supplies
APKs built for that commit: hashes identify the supplied APK bytes, and this
option does not independently derive their source revision. CI must validate
same-run artifact provenance before using it. Execution verifies the named booted emulator,
API >=31 and x86_64 before installing. Provision portrait 1080×2400 at 420 dpi,
font scale 1 (usable test viewport >=600 dp) for viewport-dependent regressions.
Do not use an unrestricted connected Gradle task with attached user devices.

[device-suites.json](testing/device-suites.json) is the complete schemaVersion 1
method inventory. Each entry gives exact class/method selectors, suite,
required key/value shapes, staged-file prerequisites and classification reason.
List-only works for all four suites, validates all source methods and inventory
entries, and needs no device/build/SDK/private data. Unknown, duplicate, missing,
stale or unsupported discovery syntax fails. The Java provider is infrastructure.

The initial executable `self-contained` suite selects **119 of 165 methods**;
`host-orchestrated` has 29, `corpus-fixture` 16 and `diagnostic-device` one.
One raw `am instrument -w -r` invocation runs sorted exact selectors sequentially,
with a 30-minute instrumentation deadline and bounded adb operations. It performs
no fixture staging or app-data clear. It retains first stdout/stderr, device
identity, logcat, APK hashes, source commit and cleanup diagnostics in the new
output directory. `summary.json` records selected IDs, actual terminal IDs in
JUnit order (including duplicates), result statuses and counts. Only exact
identity completeness, all selected passes and successful cleanup exit zero;
selected assumption/ignored skips are visible and make the outcome incomplete.
Timeout/crash/missing terminal records cannot pass. Cleanup force-stops only the
named test app and checks the emulator, preserving existing files.

Host probes skip with an explicit prerequisite when all relevant arguments are
absent during accidental broad discovery. Partial, blank or malformed arguments
fail before host fixture mutation or hold. Optional arguments count as supplied
configuration, so they cannot hide a missing required key. Staged-default corpus
assumptions remain; no general `fixtureId` can substitute for all fixed paths.
The manifest documents `native-fixtures/{satori,kawari,yaya}/master`, fixed
`satori`, `Snake_Otacon`, `lobo_okuajub`, `task7-lobo` and `task7-earthquake`
staged trees and per-entry overrides. Real inputs must be licensed by the user;
retain private archives/evidence outside Git and use the original archive hashes.
The self-contained suite establishes no private real-ghost parity.

For host orchestration, preserve the outcome policy below. Both real corpus
runners require explicit `-CorpusRoot`; their fixed relative archive paths and
SHA contracts remain authoritative. Native persistence requires explicit
`-Serial` and accepts `-Adb`; corpus uses its existing `-DeviceSerial` and now
accepts `-Adb`. Process-death uses explicit `-Serial`/portable `-Adb` and synthetic
fixtures. Both native/corpus `-SelfCheck` modes need no CorpusRoot or device.
Native/real-corpus cohorts require separate staging authorization and a reviewed
acceptance map where applicable. The notification-shade probe remains diagnostic
because PAUSE without STOP depends on the environment. Screenshot issue #425 is
separate. [Accepted M5 exception packet](testing/2026-10-01-m5-focused-acceptance-packet.md)
retains missing LOBO Pixel setting cycles and intermittent Compose wrong-thread/
keyboard failures; this suite does not establish that those exceptions are fixed.

## Current native/corpus outcome policy

Both host runners provide `-SelfCheck`, with no device, SDK, fixture, build or
output-directory work. Run `pwsh -NoProfile -File tools/tests/test-runner-outcomes.ps1`
for synthetic policy checks and process-start sentinels. These checks do not
establish device passes.

Native `-Only` names are exact scenario names. Omission selects all; explicit
empty/blank, unknown or mixed valid/unknown selections fail before side effects.
Repeated valid names resolve once. Success requires every selected scenario,
its expected method terminal results and its cleanup/health checks to pass.
`OK (1 test)` alone and assumption/ignored methods never establish a pass. The
host-kill writer is intentionally killed; its readiness, live PID, kill/exit and
fresh-reader boundaries retain their separate host evidence contract.

Corpus defaults to `-Mode Diagnostic`: complete trustworthy classifications are
findings, and the message is `diagnostic complete`. All seven categories are
counted separately. Missing/stale evidence, unverified results, transport,
timeouts or unsafe cleanup/health exit nonzero. Complete `in-scope-failure` and
`native-failure` findings can complete a diagnostic but always fail acceptance.

`-Mode Acceptance -ExpectationsPath <json>` requires reviewed exact label/archive
SHA coverage before any device/build/install work. The schema is
`{"schemaVersion":1,"rows":[{"label":"exact-manifest-label","sha256":"64-lowercase-hex","expectedClassification":"supported-smoke","basis":"reviewed evidence URL and scope rationale"}]}`.
Only `supported-smoke`, `expected-rejection`, `partial-unsupported` and
`unsupported-engine` may be expected. Actual classifications must match exactly;
a supported-to-limited downgrade fails. Inventory names, families and deep flags
are not outcome oracles. No real-row expectations are shipped: callers supply
reviewed public-safe maps and retain private evidence outside Git. Internal
synthetic success/rejection/wrong-hash guards keep their explicit expectations;
the wrong-hash instrumentation must fail and is not a passing test.

The host refines the completed device result using its recorded current
`EngineSelector` kind and actual stage/boot evidence. A detected `UNSUPPORTED`
engine with installed own-stage render/close and no activation error is
`unsupported-engine`. A known native engine with empty first/later dialogue and
a successful real-lease boot replay (status 200/204, empty or nonempty text value) is
`partial-unsupported`: initial boot dialogue was not observed. A separate replay
is diagnostic and cannot establish earlier activation rendering. Intentional authored silence
can receive that conservative limitation; it does not prove unsupported authored
scripts. Missing/failed replay, missing kind, activation error or incomplete close
cannot establish a limited pass. Raw hard-failure categories stay failures, and
each refinement retains its basis alongside the unchanged device JSON.

Use a fresh ignored output directory (or a temporary directory outside the
repository). Each executed run writes `summary.json` with source, selection,
counts, outcome/reasons and retained scenario/row records; corpus summaries also
include expected/actual classifications and expectation-file SHA identity.
Native scenario counts include unstarted scenarios as incomplete, with separate
instrumentation counts. Preflight failures create no output directory or summary.
Runtime failure retains raw evidence and exits nonzero. `supported-smoke` proves
only import/own-stage/boot-render/close with identity guards, not all authored
routes or engines. LOBO `replace`, AYA5 authored-script, audio/SSP exclusions and
unsampled branches remain unchanged. M5's accepted exceptions remain exceptions.

## Milestone 5 final-source gate (2026-09-29; acceptance open)

Source `06a82ec0cacf94d78901bb55c1c43d1060c50e7d` produced debug APK SHA-256 `bf56e2ed8cb1e6d410001e4e061e1fe78043c3bb36500d888b1658e881f2eed9`, test APK `6ddb9561f9a81cf06519e4aff6f4c6219ae0a626cfa4717eee8e0a61cbca7a1f`, and unsigned release APK `08a23b3b9ce07ad227cf712b02bb23978af7d18c0f1bba48674d1729021a561b`. Source manifest hash is `da03c17b43797623dfe6fb75eb4d5491a22328974fc95fbf748fd20751f7c14d`. The final offline five-task gate passed **373 JVM tests, four skips, zero failures/errors**, lint with zero errors/13 warnings/one hint, and debug/test/release assembly. See [final raw build log](../app/build/task7-evidence/final-api31-06a82ec/offline-gate-escalated.raw.log) and [Milestone 5 evidence](milestone-5-evidence.md#task-7-final-source-synthesis-2026-09-29) for per-archive compatibility and limits.

The explicitly serial API 31 x86_64 instrumentation gate selected 147 methods: **111 passed, 36 fixture/host assumption skips, zero failures**. Seven host-argument methods were excluded from that broad run and separately staged where applicable; [method results](../app/build/task7-evidence/final-api31-06a82ec/api31-method-results.json) and [exclusion list](../app/build/task7-evidence/final-api31-06a82ec/api31-suite-exclusions-current.txt) are retained. The literal `:app:connectedDebugAndroidTest` Gradle task was not run because it could select the attached Pixel. API 37 x86_64 post-layout UI passed **37/37**, and true-landscape 640×360 dp/font 2.0 and portrait 360×640 dp/font 2.0 each passed **1/1** with captures. The current APK also passed an API 31 Earthquake smoke/native interaction and AYA5/YAYA selected-shell UI reruns. The earlier product APK passed 23 corpus rows (18 structural smokes, five expected rejections), eight Snake UI rows, 26 distinct deep/native methods and nine persistence cohorts; only affected visual rows were rerun after the layout-only product change. Pixel 7 API 37 arm64 accepted `install -r` without data clear and showed the bundled stage, Ghosts, About, and return. A later [Pixel native-corpus follow-up](../app/build/task7-evidence/pixel-native-corpus/README.md) used that same app APK and real system-picker imports of unchanged 2elf/Satori, Earthquake/YAYA and LOBO/Kawari NARs, each loaded and switched away twice with own-stage captures and no data clear. Retained logcat confirms Satori and YAYA arm64 library loads; LOBO has visible authored text but no retained Kawari loader line. Direct lease, numeric reply and broad parity remain unverified. The author approved deferring human spoken TalkBack traversal; semantics/focus evidence is retained, and no spoken pass is claimed. The [acceptance reconciliation](milestone-5-evidence.md#task-7-acceptance-reconciliation-2026-09-29) maps all 36 skips and seven exclusions to targeted evidence or remaining gaps. Final adversarial and quality reviews, plus independent read-only Pixel evidence review, passed their bounded scopes. The literal Gradle connected command, prior-APK versus final-APK corpus coverage, unexplained historical API 31 draft loss and physical-shade PAUSE scenario still need author disposition. Keep earlier failed/red logs separate from final passes; milestone 5 remains open.

## Milestone 4 integrated gate, 2026-09-26

Historical commands and dated evidence below are retained for provenance. Broad connected snippets are historical only; use the current explicit device runner above.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --offline --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest --offline --console=plain
```

The Task 7 raw root is ignored `app/build/task7-20260926/`; [Milestone 4 evidence](milestone-4-evidence.md) maps the baseline requirements to JVM, bitmap, device, and unchanged native-corpus observations. The focused `Milestone4CorpusSurfaceTest` uses verified disposable LOBO and Earthquake trees staged privately and the instrumentation class filter. Its API 31 run passed 2/2: LOBO 0/10 and Earthquake static composition have bitmap checks; Earthquake `move` is checked as authored animator offsets only, without a rendered dynamic-frame assertion. Broad connected runs deliberately skip tests lacking their private fixture, so use the focused corpus run and `tools/test-native-persistence.ps1` for those claims. The host persistence gate completed separately with fresh hash-verified 2elf, LOBO, and Earthquake copies; retain its failed and corrected raw runs when diagnosing test synchronization.

The first full Task 7 connected attempt ended with a `GhostActivityRecreationTest` timeout and then a launcher ANR/system watchdog. Its partial XML, Gradle output, and logcat are retained under that root. After one clean emulator restart without a wipe, the focused recreation test passed and a full connected rerun passed 126 XML cases: 100 passed, 26 fixture/host skips, zero failures/errors. After Task 7 test-only additions, the first pre-review full run had one ActivityScenario teardown timeout (128 cases, 26 skips, one failure); the method passed 1/1 in isolation, and one same-emulator full retry passed **128 XML cases, 28 fixture/host skips, zero failures/errors**. Preserve all three full-run logs and diagnostics. The pre-review offline gate passed 332 JVM tests with 4 skips and no failures/errors, lint, debug app/test and release assembly.

An actual post-renderer import check then installed the pre-review debug APK fresh, used Android OpenDocument to select a SHA-verified disposable Earthquake Duo NAR from device Download, showed the readme/explicit switch prompt, and rendered the composed duo with native dialogue after confirmation. The prompt/render captures and hashes are in [Milestone 4 evidence](milestone-4-evidence.md). This is separate from the synthetic imported built-in ghost instrumentation test and from the historical duplicate refusal hash proof. API 31 x86_64 is the executed device; arm64 packaging is not execution. Release minification remains disabled.

**Post-review correction status:** `GhostInputDialog.submit` now rejects runtime-invalid input before consuming the request. Focused API31 red/green was 10 tests with 2 expected failures, then 10/10 passing; the focused console runs were not separately teed. The first post-fix full connected XML retained at `build/reports/verification/m4-input-reject-full-first-failed.xml` has **130 tests, 28 skips, one failure** in an existing Activity recreation link wait after one input event. A focused rerun on unchanged code aborted with `INSTRUMENTATION_ABORTED: System has crashed` (`build/reports/verification/m4-input-reject-activity-focused.log`), so device work stopped. The synthetic link fixture then gained `\_w[10000]` before `\e` to address its one-second completion window. The final five-task offline gate after that edit passed **332 JVM tests, 4 skips, zero failures/errors**, lint, and debug/test/release builds (`build/reports/verification/m4-task7-final-offline-after-fixture.log`).

The user authorized a separate fresh `Nanidroid_M4_Fresh_API31` AVD using the installed API31 x86_64 image. After stopping the damaged old AVD without wiping it, only the fresh `emulator-5556` was attached. The **single** full post-fix connected gate on committed `2566ec4` passed **130 XML tests, 28 skips, zero failures/errors** in 3 min 25 sec. Raw `build/reports/verification/m4-task7-fresh-api31-connected.log` and copied `m4-task7-fresh-api31-connected.xml` are retained. The new androidTest APK was executed there; its SHA-256 and post-gate health are in [Milestone 4 evidence](milestone-4-evidence.md). The earlier failed run and system crash remain separate evidence. API31 x86_64 is the executed ABI; the outstanding native animation/2elf limits remain.

To close the rendered-scene evidence gap without changing production code, `Milestone4CorpusDynamicUiTest` uses a hash-verified disposable Earthquake shell, the authored `surface1` always-move frames, `SurfaceAnimator`, and a test-supplied `StageState.Ready` through real Compose `GhostStage`. Run it on an isolated healthy device after staging that shell privately, using `'-Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.ui.Milestone4CorpusDynamicUiTest'`. The final focused API31 XML passed **1/1**, zero skips/failures/errors (`build/reports/verification/m4-task7-dynamic-focused-final.xml`), and retained root PNGs and a raw device clip under the same verification directory. The first passing 1/1 run lost its app-external PNGs during Gradle test cleanup, so the test harness was narrowed to save via MediaStore Downloads and focused rerun once. Authored offset 0→10 moved the rendered Sakura Canvas 254→260 screen px; the full-root captures differ by 60,697 pixels, including runonce overlay changes. Exact hashes, paths, and inspection limits are in [Milestone 4 evidence](milestone-4-evidence.md). This proves `SurfaceAnimator` plus Compose rendering with a synthetic driver, not native event-driven animation; the full 130-case gate was not repeated for this test-only addition.

## Milestone 3 import gate, 2026-09-25

Historical broad connected commands (do not use as the current suite interface):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest --offline --console=plain
```

The test-only `GhostImportInstrumentationTest` uses `GhostImportTestProvider` in the androidTest APK for content URI input with unknown size, slow reads and read failure. Five focused API 37 provider cases passed after the IO-close fix, including cancellation during an active provider read. At `6a64751`, the offline gate passed JVM **219 total, 4 skipped, zero failures/errors**, lint and debug/androidTest/release APK builds; raw output is ignored `.superpowers/sdd/2026-09-24-ghost-import/task5/gradle-final-6a64751.log`. The Gradle connected task still hits a Windows lock on its prior crash-report output. A wiped healthy API 37 emulator passed direct `adb shell am instrument` at `51f42e8`: **78 total, 52 passed, 26 fixture/host skips, zero failures**; see ignored `task5/final-connected/clean-recovery/adb-full-raw.log`. Later test-only additions passed focused device runs; no full direct suite rerun was made. An earlier damaged-emulator run had watchdog/system_server crash and ActivityScenario teardown failures (`task5/final-connected/post-host-skip/adb-instrumentation-raw.log` and `crash-diagnosis.txt`); a separate prior run failed three tests (`task5/final-connected/adb-instrumentation-raw.log`). The direct result does not make the Gradle wrapper gate green. Fixture skips do not prove native persistence.

The actual document picker was exercised with a SHA-verified disposable copy of Earthquake Duo, including readme/explicit switch, native YAYA save restoration from that UI-imported tree after a fresh process, and duplicate plus case-only refusal with 112 hashes unchanged. The original corpus archive remains read only. API 31 x86_64 passed the provider cases and an actual tiny OpenDocument import/switch-prompt smoke (`task5/api31/result-summary.txt`). A broad connected run does not stage private native fixtures; `tools/test-native-persistence.ps1` passed separately after the ticker fix (`task5/native-persistence-after-ticker-fix/`). The earlier Activity/orientation timeout and diagnosis remain in the Task 5 evidence. Active-copy rotation and physical Home/POWER cancellation scenarios passed. Focused API 37 tests then passed extraction rotation with partially staged payload and one eventual completion/prompt (`task5/extraction-rotation/committed-08e4468-run.log`), PAUSE without STOP during a blocked provider read (`task5/pause-only/second-run.log`), and repeated picker launch/callback admission with ordered Begin/Complete through a recording ShioriEngine (`task5/repeated-callback/summary.txt`). The physical notification shade remained RESUMED and its case was assumption-skipped; the final focused cleanup run had five passes and one such skip (`task5/cleanup-settle/prompt-aware-focused.log`). See [milestone 3 evidence](milestone-3-evidence.md) for process-death boundary logs, raw-path root, and scoped Task 5 acceptance. JNI native event count was not directly asserted. Release minification is disabled; arm64 is packaged but not device-executed.

## Native persistence checkpoint, 2026-09-23

Run `tools/test-native-persistence.ps1` on a disposable API 31+ emulator with the three verified NARs at the paths in [the fixture table](testing/native-persistence-fixtures.md). The script hashes each archive, stages unique app-private copies, runs real engine and Activity scenarios, orchestrates a live-process force-stop, and retains raw logs outside Git. See [native persistence evidence](native-persistence-evidence.md) for the observed values, device, APK hashes, and limits. Run the full wrapper gate separately:

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain
.\gradlew.bat :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest --offline --console=plain
```

Fixture tests deliberately skip in a broad connected run without `fixtureId`; a green broad suite alone does not prove persistence. The host script must complete its targeted tests and live-PID kill sequence.

## Task 1, 2026-09-23

- Run local behavior tests: `./gradlew.bat :app:testDebugUnitTest`.
- Build the debug APK: `./gradlew.bat :app:assembleDebug`.
- Verified result after independent review fixes: 14 local tests passed; debug APK assembled. This is a template plus bundled ghost loader and built-in engine, not a completed walking skeleton.
- Bundled test resource `app/src/test/resources/nanidroid.zip` matches the approved asset SHA-256 `2ebf24a2be8255011c5e004459a58dd1317eb7b12a55adcbe6b8b2977c89dc2d`.

Toolchain: Android Gradle Plugin 9.1.1; Gradle 9.3.1; Kotlin/Compose compiler 2.3.20; Compose BOM 2026.03.01; JDK 17 (Zulu 17.0.20.1); compile/target SDK 37; min SDK 31. Android CLI 1.0.16261425 reports SDK at `C:/Users/yenchi/AppData/Local/Android/Sdk`. The CLI lists six existing emulators, including `Nanidroid_API_37`; no emulator was started or modified for Task 1. The CLI's documented SDK/emulator checks succeeded after a sandbox escalation.

The generated AGP 9.0.1 supports only API 36.1. [Android's AGP 9.1.1 notes](https://developer.android.com/build/releases/agp-9-1-0-release-notes) state support for API 37 and minimum Gradle 9.3.1/JDK 17. [Gradle's checksum list](https://gradle.org/release-checksums/) provides the pinned 9.3.1 distribution hash. Dependency versions are pinned in `gradle/libs.versions.toml`; the original scaffold already included Compose, lifecycle runtime/ViewModel Compose, JUnit 4, coroutines-test, AndroidX test, and Compose test dependencies. No extra dependency version was guessed. Navigation 3 dependencies remain solely because the generated Activity/Navigation scaffold still uses them; Task 3 will remove that scaffold and those dependencies.

The temporary manifest launcher points to the generated `com.example.nanidroid.MainActivity` while the namespace and application ID are `com.cattailsw.nanidroid`. Task 3 will replace the Activity and launcher declaration together. The merged debug manifest was checked for that full launcher class name.

The untouched template build was completed during setup and is recorded in [scaffold-verification.md](scaffold-verification.md). Device and UI tests remain for Tasks 3 and 4.

## Milestone 1 device proof, 2026-09-23

Historical Milestone 1 instructions: from the repository root, run `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` followed by `./gradlew.bat :app:connectedDebugAndroidTest` with an API 31+ emulator. The connected suite includes Compose stage tests and `WalkingSkeletonInstrumentationTest`. Its test-only composition uses `ActivityScenario`, a retained ViewModel, the production runtime and bundled archive, and an event-recording engine decorator. It checks one first-boot dispatch after loading recreation, dialogue progress after recreation and orientation change, and no duplicate boot event.

Manual APK checks and exact evidence are in [milestone-1-evidence.md](milestone-1-evidence.md). Generated screenshots, layout dumps, reports, and APKs stay under `app/build` and are excluded from Git.
