# Issue 432: two confirmed duplicate removals

Workspace: `C:/Users/yenchi/.codex/worktrees/bd56/Nanidroid`. Loaded workspace `AGENTS.md`, `.superpowers/sdd/issue-426/issue-432.md`, and `docs/superpowers/plans/2026-10-07-issue-426-test-suite-improvement.md`; no deeper scoped AGENTS files found. Base: `2fcc59e47db34d68903b8424498bb2a36d4ad709`. Branch: `codex/432-remove-specified-test-duplicates`.

## Change and retained coverage

Deleted only `GhostStageTest.builtInEngineReceivesTapOnceAndReturnsNoScript` and `StagePolishTest.aboutDismissReturnsToStageWithoutClosing`, their two manifest entries, and the newly unused BuiltInShiori/KeyEvent imports. No helper simplification follows: the remaining runtime/stage helpers, InstrumentationRegistry uses and issue-428 fixture RuleChain remain necessary. All remaining assertions and test boundaries are unchanged. AboutDialogTest, WalkingSkeletonInstrumentationTest and BuiltInShioriTest were not edited.

Retained `GhostStageTest.keroTapSendsOneUnscaledMouseClick` verifies exactly one event with full unscaled references; its double-tap case independently verifies coordinates and absence of single clicks. `BuiltInShioriTest` retains engine no-reply coverage. `WalkingSkeletonInstrumentationTest.bundledKeroTapDispatchesOnceWithSurfaceCoordinatesAndNoReply` retains the bundled Activity/runtime boundary. `AboutDialogTest.aboutFromControlsDismissesToSameReadyStageWithoutClose` retains visible dismissal, Back, same stage/controls and no-close callback with supplied version/notice. Loader states, pending native dialog session, viewport/control/bounds and rotation cases remain intact. No production, native, dependency, product or extra fixture changes.

## Verification

Toolchain: pinned Zulu JDK 17 (`C:/Program Files/Zulu/zulu-17`), Gradle 9.3.1, Android SDK `C:/Users/yenchi/AppData/Local/Android/Sdk`; JAVA_HOME, ANDROID_HOME and ANDROID_SDK_ROOT set explicitly for Gradle. Existing pinned NDK/CMake configuration unchanged.

- `.\gradlew.bat :app:testDebugUnitTest --tests 'com.cattailsw.nanidroid.engine.BuiltInShioriTest' :app:assembleDebugAndroidTest --offline --console=plain`: exit 0, 8.030 host seconds (Gradle 7s), selected/executed/passed 3 JVM methods, skipped/failed/incomplete 0; JVM XML duration 0.197s. Android test Kotlin compilation and packaging executed.
- `pwsh -NoProfile -File tools/test-device-suite.ps1 -ListOnly -Suite self-contained`: exit 0, 117 listed. Immediate prechange inventory 165 total/119 self-contained; after exactly the two removals 163/117.
- `pwsh -NoProfile -File tools/test-device-suite.ps1 -SelfCheck`: exit 0; completeness verified at 163 methods.

Before device execution, manifest-derived intended selection: GhostStageTest 9 self-contained, StagePolishTest 8 self-contained, AboutDialogTest 2 self-contained, WalkingSkeletonInstrumentationTest 4 self-contained; total 23, host-orchestrated methods in these four classes 0. `NativeDialogSessionTest` is a separate class in AboutDialogTest.kt, preserved and not selected; no private fixture coverage claimed.

Authorized exact disposable serial `emulator-5580`: API31 Google APIs x86_64, 1080x2400, 420dpi, portrait/font scale 1.0. Used SDK platform-tools/adb.exe. One `adb -s emulator-5580 install -r` for each existing app APK and newly assembled test APK succeeded. No clear, private staging, retry or other-device command.

Command: `adb -s emulator-5580 shell am instrument -w -r -e class com.cattailsw.nanidroid.ui.GhostStageTest,com.cattailsw.nanidroid.ui.StagePolishTest,com.cattailsw.nanidroid.ui.AboutDialogTest,com.cattailsw.nanidroid.WalkingSkeletonInstrumentationTest com.cattailsw.nanidroid.test/androidx.test.runner.AndroidJUnitRunner`.

First and only attempt: selected 23, observed/executed 23, passed 23, skipped 0, failed 0, incomplete 0; adb exit 0, instrumentation code -1, `OK (23 tests)`, runner 48.666s, host 50.093s. Parsed terminal class/method identities match all 23 manifest IDs exactly once, with no unexpected terminal statuses. No first failure occurred.

Device source identity is base plus this working test/manifest diff, not a claim that a subsequently committed integrated suite ran. App SHA256: `99a25e4d0c597b32787e1a3cbaac8850589cbb54c6c682eb13bfa0923fba2c59` (existing app APK, unchanged production); test APK SHA256: `0fe403d03c0e10899490c3d785bcd40abfc21a40507509b14ed735658d907c00` (newly assembled). Paths: `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. No same-run app rebuild/provenance claim.

## Isolation and evidence limits

Before and after hashes unchanged: `cache/issue428-unrelated-sentinel` = `d28a4662bdc46daaf03456e0da79e422a75af1b25c2ad76285df54cdbeeff4b1`; `files/ghost/root-test/issue428-preexisting-residue` = `22e483692f871aa16d1625efb1b3b750700be77a9ed1e6b678fd4b33e99da3d8`. Post-run `run-as com.cattailsw.nanidroid find cache -maxdepth 1 -name 'owned-fixture-*'` returned no roots. StagePolish's existing fixture rule remains intact; its stdout cleanup messages are not present in the instrumentation transcript, so cleanup evidence is the direct absence check and unchanged sentinel hashes. No global deletion performed.

Ignored local evidence: `.superpowers/sdd/issue-426/432-evidence/` contains `build.log`, `list.log`, `selfcheck.log`, `install-app.log`, `install-test.log`, `instrumentation.stdout.log`, `instrumentation.stderr.log`, `observed-methods.txt`, `cleanup.log`. JVM results: `app/build/test-results/testDebugUnitTest/TEST-com.cattailsw.nanidroid.engine.BuiltInShioriTest.xml`. Raw evidence and APKs remain outside Git. Self-review confirms only the two approved methods/imports/selectors were removed; independent review and final committed integrated-suite gate belong to controller.

M5 LOBO Pixel cycles and intermittent Compose wrong-thread/keyboard exceptions remain accepted gaps; this run does not prove them fixed. No full suite, private corpus, push, public PR, merge, signing or user-device installation performed.
