# Issue 428 implementation and verification

Workspace: `C:/Users/yenchi/.codex/worktrees/bd56/Nanidroid`.
Loaded project instructions: workspace `AGENTS.md`, plus the user-supplied Honcho/project instructions. Implementation base: `7d649d7e` on `codex/428-isolate-instrumentation-fixtures`. Specification: `.superpowers/sdd/issue-426/issue-428.md`; shared plan: `docs/superpowers/plans/2026-10-07-issue-426-test-suite-improvement.md`.

## Change

Only Milestone5CorpusTest, AnimatedSurfaceTest, AuthoredSurfaceUiTest, GhostStageAlwaysLayersTest, StagePolishTest and test-only testing/OwnedFixtureDirectoryRule.kt changed. The positive root-separator archive is unchanged, now imported with GhostImporter under invocation-owned files storage; installed directory presence and canonical containment are asserted. All six neighboring root rejection cases and all UI rendering oracles remain. Task 427's detectedEngineKind evidence field remains.

Each invocation creates a UUID cache parent using exclusive mkdir, then its shell directories inside it. Unsafe static files remain shell siblings beneath the same parent. RuleChain explicitly wraps Compose with the owned-directory rule, so Compose/Activity disposal precedes recursive deletion. Only successfully created invocation roots are deleted. Cleanup failure includes the path and remaining-existence result; when a primary exception exists cleanup is suppressed onto it and separately logged. No production/native/dependency changes, global directory deletion or data-clear operation.

## Toolchain and commands

JDK: `C:/Program Files/Zulu/zulu-17` (JDK 17). SDK: `C:/Users/yenchi/AppData/Local/Android/Sdk`; adb: its `platform-tools/adb.exe`. Pinned Gradle 9.3.1, Kotlin 2.3.20, NDK 28.2.13676358, CMake 3.22.1; minSdk31/compile37/target37 unchanged.

Final offline command (JAVA_HOME/ANDROID_HOME/ANDROID_SDK_ROOT set above):

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
```

Final command exited 0, BUILD SUCCESSFUL in 24s (initial complete build also successful in 1m7s). JVM XML: 380 tests, 376 passed, 4 skipped, 0 failures, 0 errors. Lint/debug/androidTest build tasks passed. These are actual reports, including cached/up-to-date Gradle tasks, not claims every task re-executed.

Authorized NEW disposable emulator only: `emulator-5580`, API31 Google APIs x86_64, verified controller boot/qemu, 1080x2400, 420dpi, font1, portrait. Fresh app and test APKs installed once. No APK reinstall or app-data clear between any successful device invocations.

Exact selector sequence: rootOnlyDirectoryRegression, unsafeRootEntryVariantsStayRejected, rootOnlyDirectoryRegression; each separately selected/executed/passed 1, skipped/failed/incomplete 0. Then identical combined selection twice: those two methods plus all AnimatedSurfaceTest, AuthoredSurfaceUiTest, GhostStageAlwaysLayersTest and StagePolishTest. Each selected/executed/passed 32, skipped/failed/incomplete 0 (class counts 2+8+9+4+9). Repeat elapsed instrumentation times: 51.022s and 55.008s. Total successful device invocations: 67 tests; no observed device assertion failure, duplicate-install refusal, or cleanup failure. Selection transcript uses AndroidJUnitRunner's class/dot report with OK(32 tests); the eventual task429 identity-aware runner is not yet available.

App APK SHA256: `99a25e4d0c597b32787e1a3cbaac8850589cbb54c6c682eb13bfa0923fba2c59`.
Test APK SHA256: `5677f72d68bb4c822214d6309ebb3394049152ddda1ccf1454756582bc1cac24`.

## Ownership evidence

The fresh owned emulator was seeded before tests with unrelated cache/issue428-unrelated-sentinel and files/ghost/root-test/issue428-preexisting-residue. Inventories before and after each repeat show no surviving owned-fixture-* parent or outside-shell files. Sentinel SHA256 remained `d28a4662bdc46daaf03456e0da79e422a75af1b25c2ad76285df54cdbeeff4b1`; preexisting residue SHA256 remained `22e483692f871aa16d1625efb1b3b750700be77a9ed1e6b678fd4b33e99da3d8`. Root-test inventory stayed unchanged. Android runtime added files/profileInstalled, which is unrelated to the owned fixtures. Collected logcat contains exactly 67 successful OWNED_FIXTURE_CLEANUP diagnostics.

A temporary host Kotlin/JUnit harness compiled the actual helper source with only InstrumentationRegistry replaced by a host cache context. Four focused checks passed: success, assertion failure, partial setup failure, and deletion failure. The inner teardown asserts root presence before cleanup; subsequent assertions require its absence and unchanged sibling sentinel. Deletion failure uses a File test double refusing root deletion: the original assertion exception remains identical, exactly one suppressed cleanup exception carries the path, and the harness removes its own residue afterward. No deliberately failing test is committed. A temporary no-cleanup mutant of the actual source failed with exit1 at RuleHarness.kt:29's postcondition, demonstrating the harness detects omitted deletion. This host evidence does not claim Android filesystem deletion failures were induced.

## First attempts, raw evidence and limitations

Raw evidence is ignored under `.superpowers/sdd/issue-426/428-evidence/`; temporary host harness/script/jars and APKs remain outside Git. Build logs: build.stdout/stderr.txt (sandbox attempt), build-escalated.stdout/stderr.txt, build-final.stdout/stderr.txt. Host evidence: harness-final.stdout/stderr.txt, harness-final-compile.stdout/stderr.txt, mutant.stdout/stderr.txt, mutant-compile.stdout/stderr.txt and retained temporary sources. Device evidence: install-app/test.stdout/stderr.txt; seed.stdout/stderr.txt; positive-before-negative.stdout/stderr.txt, negative.stdout/stderr.txt, positive-after-negative.stdout/stderr.txt; repeat-1/2.stdout/stderr.txt; inventory-before.stdout/stderr.txt, inventory-after-repeat-1/2.stdout/stderr.txt; cleanup-logcat.stdout/stderr.txt. Reproduction host script is ignored `.superpowers/sdd/issue-426/428-device-gates.ps1`.

First sandbox ADB attempt failed starting/accessing its daemon (baseline-positive.stderr.txt), with zero tests executed. Escalated baseline attempt found no instrumentation APK on the new emulator (baseline-positive.stdout.txt), also zero executed: pre-fix duplicate-install reproduction was therefore not obtained. First sandbox Gradle attempt failed cache lock access (build.stderr.txt), corrected by authorized escalated toolchain access. No first red was overwritten by retry evidence.

The absent-real-root-test branch was not separately device exercised; preservation of preexisting root-test was. Isolated construction statically avoids real app files storage. SmokeArchive/private corpus and LOBO Pixel setting cycles were not selected. The four JVM skips remain skips; M5 wrong-thread/keyboard and missing LOBO Pixel accepted exceptions are unchanged. No broader suite/CI acceptance claim, publication, push, signing or user-device installation.

Independent review: pending controller review before publication. Controller instructed a focused local commit after the verified gate so its commit-based review helper can inspect the change. `git diff --check 7d649d7e` passed before review. The controller coordinates task427 follow-up fixes separately; this implementation does not edit its scripts.
