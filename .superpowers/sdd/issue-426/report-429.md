# Issue 429 implementation and verification

Workspace: `C:/Users/yenchi/.codex/worktrees/bd56/Nanidroid`.
Loaded instructions: that workspace's `AGENTS.md` and the user-supplied Honcho/project instructions. Binding spec: `.superpowers/sdd/issue-426/issue-429.md`; shared plan: `docs/superpowers/plans/2026-10-07-issue-426-test-suite-improvement.md`. Branch: `codex/429-explicit-device-suites`; implementation base: `7e85a8c32864c98820bbfc0a28db4cc961a59a3d`. Independent review is coordinated by the controller.

## Change and scope

New `tools/test-device-suite.ps1`, `docs/testing/device-suites.json`, and `tools/tests/test-device-suite.ps1` provide the specified schema/interface. The complete inventory has **165 exact method IDs**: **119 self-contained, 29 host-orchestrated, 16 corpus-fixture, one diagnostic-device**. Discovery includes NativeDialogSessionTest, RealCorpusImportUiTest and GhostActivityRecreationTest alongside the other classes in their source files; Java provider infrastructure contributes no test method. Unsupported/nested/aliased test syntax, unknown/missing/duplicate inventory entries and new unclassified methods fail. Synthetic drift checks operate on temporary source copies.

The exact sorted 119-method selection runs in one normal sequential `am instrument -w -r` invocation with a 1,800-second instrumentation deadline. adb operations have bounded process deadlines; there are no per-method cold starts or retries. Summary schemaVersion 1 has the specified selected/observed identity arrays, counts, status/reason/path records, source/APK identity, API/ABI, duration, cleanup status and outcome. Actual JUnit order is retained; missing/extra/duplicate identities, selected skips, crash/timeout and missing/duplicate instrumentation terminal results cannot pass. Failure diagnostics and cleanup errors remain separate. Cleanup releases the named app process and checks the named emulator without app-data clear or file deletion.

The three existing host runners now use explicit emulator serials, portable adb/SDK/Gradle paths, and explicit CorpusRoot for real corpus/native archive paths. Existing SHA contracts, raw `-w -r` invocations, outcome expectations and primary/cleanup failure policy remain. Narrow controller-authorized argument-only changes in `tools/tests/test-runner-outcomes.ps1` keep its positive and negative process sentinels meaningful with the newly explicit portable inputs; no assertion/policy was weakened.

Only prerequisite guards changed in these ten instrumentation files: corpus/Milestone5CorpusTest.kt; engine/NativePersistenceTest.kt and NativeShioriRealTest.kt; install/GhostImportInstrumentationTest.kt; ui/AboutDialogTest.kt, GhostImportUiTest.kt, GhostInteractionUiTest.kt, NativeRotationTest.kt, NativeTalkProbeTest.kt and RealGhostInteractionTest.kt. All absent host arguments yield explanatory assumptions; partial/blank/malformed host values fail before fixture mutation/hold. Existing staged-default corpus assumptions remain, including the fixed LOBO probe's staged prerequisite. Field-specific error messages clarify existing regex/enum failures. Fixture contents/assertions, issue428 owned roots/RuleChains/oracles and issue427 policy remain intact.

`docs/testing.md` leads with JVM/local and explicit serial commands; documents fixture/key shapes and staged prerequisites via the complete manifest; marks broad connected commands historical; links accepted M5 exceptions. No production/native/dependency/product, parser framework, new annotations, screenshot/golden/locale, migration or unrelated source changes.

## Toolchain and host/local evidence

Windows PowerShell **7.6.6**; JDK **Zulu 17.0.20.1+1** at `C:/Program Files/Zulu/zulu-17`. SDK/adb: `C:/Users/yenchi/AppData/Local/Android/Sdk/platform-tools/adb.exe`; JAVA_HOME, ANDROID_HOME and ANDROID_SDK_ROOT were explicitly set. Pinned Gradle 9.3.1/Kotlin 2.3.20/NDK 28.2.13676358/CMake 3.22.1 and min 31/compile 37/target 37 unchanged.

Commands executed:

```powershell
pwsh -NoProfile -File tools/test-device-suite.ps1 -ListOnly -Suite self-contained
pwsh -NoProfile -File tools/test-device-suite.ps1 -SelfCheck
pwsh -NoProfile -File tools/tests/test-device-suite.ps1
pwsh -NoProfile -File tools/tests/test-runner-outcomes.ps1
pwsh -NoProfile -File tools/test-native-persistence.ps1 -SelfCheck
pwsh -NoProfile -File tools/test-milestone5-corpus.ps1 -SelfCheck
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
```

Windows list selected 119; both new checker entry points passed with 165 inventoried. The synthetic transcripts cover reordered pass, assumption/ignored, zero/missing/extra/duplicate methods, failed status, nonzero process exit, explicit crash message, timeout and absent/duplicate instrumentation terminal results; inventory copies cover valid unclassified addition, unsupported method/alias syntax, missing/duplicate/stale entries and unknown/empty selection.

Existing runner outcome checks passed **86 policy checks/124 total harness assertions**; both native/corpus SelfCheck passed 86. Final retained Windows logs: `list-429-final.log`, `host-429-final.log`, `host-429-direct-final.log`, `host-427-compat-429.log`, `host-429-native.log`, `host-429-corpus.log`, under `.superpowers/sdd/issue-426/`.

The controller ran all six host/list commands on **WSL Ubuntu-24.04 / PowerShell7.6.6**, using `/tmp/nanidroid-426-pwsh/pwsh`, with exit0:165 inventoried / 119 selected,86 policy/124 assertions, both existing SelfChecks86. Retained `linux-429.sh` and `429-linux-{list,selfcheck,host,outcomes,native,corpus}.log`. Final new synthetic assertions were also checked with exit0 using `linux-429-new.sh` and `429-linux-final-{list,selfcheck,host}.log`. No WSL Git metadata issue occurred for these host-only paths. Linux real adb/build execution remains unexecuted.

Initial offline build passed in 20s; final full offline gate after guard message edits passed in 16s. JVM XML: **380 total, 376 passed, 4 skipped, 0 failures, 0 errors**. Lint:0 errors, 26 warnings, 1 hint. Debug/app-test assembly passed. These counts include cached/up-to-date tasks/results; no claim that every JVM task re-executed. Logs: `build-429-first.log`, `build-429-guard-messages.log` (guard compilation passed in 11s), and `build-429-final.log`.

First host red observations are retained in session tool outputs and copied, explicitly labeled excerpts at `host-429-first-red-excerpts.txt`: the missing initial runner and an initial compatibility mirror parse failure caused by nested Gradle Join-Path AST replacement. The executable path call was corrected without changing the predecessor injector/assertions; final 124-assertion compatibility passes. No offline build or full self-contained device invocation failed.

## First device suite and guard evidence

Only the authorized task-owned **emulator-5580**, API 31 Google APIs **x86_64**, boot 1 / qemu 1, controller-provisioned 1080x2400/420dpi/font 1/portrait, was used. No user/physical device selection, private fixture staging, pm clear or unrestricted connected Gradle invocation.

```powershell
pwsh -NoProfile -File tools/test-device-suite.ps1 -Suite self-contained -Serial emulator-5580 -Adb C:/Users/yenchi/AppData/Local/Android/Sdk/platform-tools/adb.exe -OutputDirectory .superpowers/sdd/issue-426/device-429-first -SkipBuild
```

The **first attempt exited0**:119 selected / 119 terminal observed / **119 passed, 0 skipped, 0 failed, 0 incomplete**, cleanup passed, duration 159.4976179s. Exact sorted selected IDs and actual terminal IDs are in `device-429-first/summary.json`; identities match exactly once, without requiring JUnit order equality. Retained first stdout/stderr, logcat, install/device identity and cleanup logs are in the same ignored directory. There was one combined instrumentation process, no retry.

Its raw summary records only base sourceCommit **7e85a8c32864c98820bbfc0a28db4cc961a59a3d**; execution also included the issue429 working diff. The report manifest supplies that additional evidence, but the summary alone was insufficient provenance; the external-review fix below prevents this dirty-checkout ambiguity. The first executed 86-file Kotlin/Java source manifest is `source-429-first-manifest.txt`, SHA256 **b71d0c8a515dc374c8db1b42cf00f02bbc2e16185d6011a38b8808fffe6b46d9**. First app APK SHA256: **99a25e4d0c597b32787e1a3cbaac8850589cbb54c6c682eb13bfa0923fba2c59**; first test APK: **6fe8218b2b7f043290d7bad610c23f72dc66fe90467696234ff4bf2ab12f3047**.

Representative guard controls ran sequentially afterward using the saved ignored `verify-guards-429.ps1`:

- One absent-arguments invocation selected all 29 host methods:29 terminal observed / 29 explicitly skipped, 0 passed/failed/incomplete. These expected host prerequisite skips are not passing regressions.
- Partial measurement (`measureLabel` only) and partial native hold (`runId` only) each selected/observed 1 and failed at the all-nonblank required-argument check, before archive operations/JNI hold.
- Malformed corpus supplied valid corpusLabel/corpusPath plus an invalid corpusSha256; malformed recreation supplied valid repetition/draft plus invalid variant. Each selected/observed 1 and failed its malformed-field guard, not a missing prerequisite or fixture assertion.

All raw stdout/stderr, parsed identity/count results and sentinel/cleanup/logcat are in `guards-429-first/`; `results.json` retains the29 skips and four failures. The first malformed corpus failure was the existing generic hash requirement at Milestone5CorpusTest.kt:94. After adding field-specific guard messages, the final test APK was compiled/installed with `install -r`, and the two complete malformed controls were executed on that new APK via `verify-guard-messages-429.ps1`: both observed 1/failed 1; corpus now explicitly says `corpusSha256 must be 64 hex characters`, recreation says `Unexpected variant: invalid`. Raw evidence is `guards-429-message-check/`, with installation log `install-test-429-guard-messages.log`. These deliberate failed controls remain failed results and are not counted as suite passes.

Final 86-file Kotlin/Java source manifest: `source-429-manifest.txt`, SHA256 **4e8f8465c910f785d2a60cc254a43016ab304551d089038906cc39f3b6e91058**. Final app hash unchanged; final compiled/tested guard APK SHA256 **f77bfba76a4dddd49c08cf288a5ba20dc0c98a81aa00e669112b1f8b0bc185a8**. The119-method suite was not repeated after diagnostic-only host guard message edits; the plan's final integrated suite will cover committed final-source APK provenance.

## Ownership, cleanup and remaining gaps

Both issue428 synthetic sentinels remained unchanged after full suite/guard checks: cache/issue428-unrelated-sentinel SHA256 **d28a4662bdc46daaf03456e0da79e422a75af1b25c2ad76285df54cdbeeff4b1**; files/ghost/root-test/issue428-preexisting-residue SHA256 **22e483692f871aa16d1625efb1b3b750700be77a9ed1e6b678fd4b33e99da3d8**. Both guard evidence directories retain `sentinels.stdout.log`; `owned-roots.stdout.log` is empty (zero surviving owned-fixture-* cache roots). The suite cleanup passed and only force-stopped the named test app; no global deletion or data clear. Existing external files were preserved.

The corpus/native/diagnostic suites were listed/classified, not executed as real fixture cohorts. The recovery corpus path's existence is metadata only, with no inferred reviewed expectations/staging permission. No real private-ghost parity, physical notification-shade proof, native corpus acceptance, Linux device/build gate or committed integrated final 119-method execution is claimed. The 4 JVM skips stay skips. M5 missing LOBO Pixel setting cycles and intermittent Compose wrong-thread/keyboard exceptions remain accepted exceptions, not fixed/passing results. No push, public PR, merge, signing-key handling, publication or user-device installation was performed by this worker.

Only this public-safe report follows the established tracked report convention under .superpowers. All raw/build/device/private evidence remains ignored; no evidence directory was broadly added. Focused local commit and full `git diff --check 7e85a8c3..HEAD` are reported to the controller; independent review remains its gate.
## External-review fix wave

Addressed review comments 4214295185 and 4214295193 plus the local diagnostic-accounting note. Execution now verifies a clean Git index/worktree, including untracked files, before output creation, build or adb, records the verified HEAD, and rechecks the same clean commit before APK installation. ListOnly/SelfCheck still require no Git. The schema is unchanged. SkipBuild remains a caller assertion that supplied APKs were built for that commit; APK hashes identify bytes, not independently their source origin. Same-run CI artifact provenance belongs to issue433.

Process-death setup recognizes either phase or runId before creating its disposable root. Setup trace events prove the partial-host path creates no fixture. Absent-host method skips and normal self-contained fixture setup retain their behavior. Logcat acquisition failure now marks diagnostic outcome/results incomplete with a reason while retaining actual cleanupStatus and any existing primary failure.

Host TDD retained dirty-source and missing-helper red logs (`429-fix-provenance-red-contract.log`, `429-fix-diagnostics-red.log`). Synthetic actual-entrypoint checks cover modified, staged, untracked and Git-error rejection before any build/adb sentinel or output directory; clean control reaches the sentinel and records the commit. A final synthetic instrument success/logcat failure verifies the real finally wiring: all selected methods observed, outcome incomplete, cleanupStatus passed, diagnostic error retained. Windows commands `pwsh -File tools/test-device-suite.ps1 -ListOnly`, `-SelfCheck`, `pwsh -File tools/tests/test-device-suite.ps1`, `pwsh -File tools/tests/test-runner-outcomes.ps1`, and the native/corpus runners with `-SelfCheck` all exited 0. Logs are `429-fix-windows-{list,selfcheck,outcomes,native,corpus}.log` and final `429-fix-host-final.log`: inventory165/selected119, predecessor policy86/assertions124. Real dirty-checkout preflight also rejected before adb resolution/output creation (`429-fix-real-dirty-preflight.log`). The controller ran all six Linux commands with PowerShell7.6.6, exit0 (`429-linux-external-fix-*.log`), then reran the final amended SelfCheck/checker, both exit0 (`429-linux-external-diag-{selfcheck,host}.log`). No Linux device/build evidence is claimed.

Android commands used JAVA_HOME Zulu17 and the approved SDK: `./gradlew.bat --offline :app:assembleDebugAndroidTest`, then explicit adb `-s emulator-5580 install -r` of the test APK. Both red-trace and fixed builds exited 0; the fixed build reported BUILD SUCCESSFUL in5s (`429-fix-setup-green-build.log`). Fixed test APK SHA256 **76933f7a3507bdd41701b8afb9435a5c5992dfbb538a0b3734d1675fd6c3ba9e**. App APK unchanged. Builds executed base d5d99cf52685bb01c035f306007d6cacb0abeb2b plus the reviewed fix working diff; the clean execution runner itself was not used to claim committed provenance for these focused diagnostic controls.

The pre-fix runId-only control selected/observed1 and failed its method guard, but its setup trace proved one fixture creation (`429-fix-setup-red-{instrumentation,logcat}.log`). Fixed focused controls, each with one selected/observed identity, produced: partial runId failed1; blank phase failed1; malformed runId failed1; all three had zero fixture creations and one skipped setup event with rootInitialized=false. Wholly absent host arguments skipped1 and ordinary setup created1; `unknownSizeProviderSuppliesExactBytesForFreshInstall` passed1 and ordinary setup created1. Raw reasons identify all-nonblank arguments or the32-lowercase-hex constraint. Evidence is `429-fix-setup-green/partial-runId.json` and `429-fix-setup-green-complete/results.json`, using bounded raw `am instrument -w -r` calls. The first blank-phase control lost its empty argument through remote-shell quoting and observed zero methods (`429-fix-setup-green-control.log`); it remains a control-driver failure. Explicit remote empty quoting corrected that control; no application failure was relabeled as passing.

Both issue428 sentinel hashes above remained identical in the focused before/after records. Named-app force-stop cleanup succeeded. No full119 suite retry, private cohort, global deletion or data clear was performed. The committed integrated gate remains outstanding under the shared plan; the original first full suite evidence remains unchanged. Only these bounded fixes, tests, documentation and this public-safe report are committed; raw evidence remains ignored.
