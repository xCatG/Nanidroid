# Issue 430: condition-based test waits

Workspace: `C:/Users/yenchi/.codex/worktrees/bd56/Nanidroid`; loaded `AGENTS.md` at that root and the supplied session instructions. Brief: `.superpowers/sdd/issue-426/issue-430.md`; plan: `docs/superpowers/plans/2026-10-07-issue-426-test-suite-improvement.md`. Branch `codex/430-condition-based-test-waits`, base `d5d99cf52685bb01c035f306007d6cacb0abeb2b` (accepted issues 427–429 preserved). Controller owns independent review and integration. No push/PR/merge/device selection or data clear performed by this worker.

## Changes and boundaries

Only the five assigned test files changed: `install/ImportCoordinatorTest.kt`, `ui/StageViewModelTest.kt` under JVM tests; `ui/CharacterGestureTest.kt`, `ui/Milestone4CorpusDynamicUiTest.kt`, `ui/NativeTalkProbeTest.kt` under instrumentation tests. No production, native, dependency or product edits.

Import/state waits consume bounded StateFlow predicates and tolerate non-Ready transients. Fake-engine events publish immutable snapshots through a class-local StateFlow; iterators capture a snapshot rather than reading a concurrently modified list. Real IO/Default execution remains. The blocked-provider latch waits on IO with its own real deadline. Finished-session publication waits for the coordinator's actual job to finish. The two former 100 ms negative waits snapshot established long-lived jobs before admission, then join new accepted-operation/install-event jobs after Completed/Finished and gate release. Completed is published after the complete-event successor has been launched, so this snapshot includes the event chain; joining waits for the released request and the dropped successor before absence is asserted. No virtual-time substitute or production queue changes.

Gesture cancellation still changes real `interactionActive` or `dialogueToken`, which are production pointerInput keys. The tests capture `LocalViewConfiguration.current.doubleTapTimeoutMillis` used by production and cross that real-time deadline using the existing Compose `waitUntil` mechanism. Pointer-input timeouts stay real; positive single/double-tap paths remain. Pause and session replacement both assert absence after the deadline and prove a fresh rendered interaction delivers one callback. No fixed 400 ms sleep remains.

Dynamic move removes two 1,500 ms capture pauses. After the authored +10 offset update it waits for rendered rightward bounds and changed pixels; existing +10, movement, changed-pixel assertions and saved captures remain. No private fixture execution or measured real-corpus settling result is claimed.

Every native runtime wait now supplies its test/actual fixture identity, awaited predicate label and snapshot supplier. Timeout keeps the existing deadline/50 ms real poll interval and captures the supplier once without rerunning the predicate. The sanitized snapshot reports last state category/ghost/token, letter/choice counts, error presence, lease generation, availability, load/unload counts, recent event IDs and tracked counts; applicable reply statuses are included. It does not emit private scripts from the new diagnostic helper. Existing temporal native probes/guards are preserved.

The permanent class-local `@Before verifyTransientTimeoutDiagnostics` exercises the actual `waitUntil` helper with synthetic StateFlow Loading→Finished and a controlled monotonic clock. It asserts the correctly labeled non-Earthquake scenario, actual last state, elapsed/deadline and exactly one snapshot/two predicate calls (no extra diagnostic predicate evaluation). This is a helper check, not a new `@Test` or inventory entry. It runs before native prerequisite assumptions. No temporary instrumentation edits were made/restored, and this same committed helper was included in the tested APK.

## Commands and actual evidence

JDK Zulu 17 at `C:/Program Files/Zulu/zulu-17`; SDK/adb `C:/Users/yenchi/AppData/Local/Android/Sdk/platform-tools/adb.exe`. JAVA_HOME, ANDROID_HOME, ANDROID_SDK_ROOT set explicitly for Gradle. Pinned wrapper/catalog/NDK/CMake and min31/compile37/target37 unchanged. Device identity verified: exact authorized disposable `emulator-5580`, API31 Google APIs x86_64, 1080×2400, 420dpi, font1, existing portrait configuration.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.cattailsw.nanidroid.install.ImportCoordinatorTest' --tests 'com.cattailsw.nanidroid.ui.StageViewModelTest' --offline --console=plain
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r -e class $gestureSelectors com.cattailsw.nanidroid.test/androidx.test.runner.AndroidJUnitRunner
```

The exact brief four-selector list was used three times, one unchanged installed APK pair and unchanged data, without retry/reinstall between runs. Selected methods: `pauseCancelsPendingSingleTap`, `sessionReplacementCancelsPendingSingleTap`, `singleTapUsesClickCallback`, `doubleTapUsesDoubleClickCallbackOnly` in CharacterGestureTest. Raw bundles were parsed by the existing issue429 `Read-SuiteTranscript` function extracted from the tracked runner, comparing exact terminal method identities.

| Verification | Selected | Observed/executed | Passed | Skipped | Failed | Incomplete | Time |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Final focused JVM | 31 | 31 | 31 | 0 | 0 | 0 | Import 2.185 s; ViewModel 0.189 s; Gradle 8 s |
| Gesture run 1 | 4 | 4 | 4 | 0 | 0 | 0 | instrumentation 6.669 s; wall 7.521 s |
| Gesture run 2 | 4 | 4 | 4 | 0 | 0 | 0 | instrumentation 7.140 s; wall 8.007 s |
| Gesture run 3 | 4 | 4 | 4 | 0 | 0 | 0 | instrumentation 6.761 s; wall 7.557 s |
| Native helper wrapper method | 1 | 1 | 0 | 1 | 0 | 0 | instrumentation 0.452 s |

Assembly successful, Gradle 8 s. No pre-change measured timing baseline was run; no settling-speed or reliability-rate comparison is claimed. Removal of three 400 ms/two 1,500 ms constants is a source fact, not measured runtime savings.

The first focused JVM attempt was red and retained: Gradle reported 14 results/4 failures, including ImportCoordinator initializationError (18 intended methods did not execute) and three actual StageViewModel timeout failures, with 10 StageViewModel passes. First failure: `InvalidTestClassError: acknowledgingResultKeepsDeferredPrompt() should be void`, caused by the new helper's inferred non-Unit return. Corrected to Unit. StageViewModel `returnedResultIsMarkedBeforeProviderOpensAndDuplicateIsIgnored`, `returnedWorkWaitsForReceivingActivityToStart`, `rotationAfterConsumedMarkKeepsResultInRetainedViewModel` showed `runTest` virtual deadline racing unmodified real Default work; the bounded StateFlow waits now execute with `withContext(Dispatchers.Default)`. Subsequent focused run passed 18+13 methods. No instrumentation first failure occurred in the three gesture runs.

Separately selected `NativeTalkProbeTest#nativeAboutBackKeepsSameReadyLeaseWithoutBootOrClose` with no fixtureId solely to execute @Before. Filtered logcat proves `WAIT_DIAGNOSTICS_SYNTHETIC ... scenario=synthetic-LOBO ... elapsedMs=100 deadlineMs=100 ... lastStageState=Finished lease=null availability=Available observedEvents=0`; its assertions passed before the method's expected missing-host-argument assumption. The raw method terminal status was -4 and strict summary outcome incomplete (one skipped), never native-pass evidence.

## APK/source provenance, ownership and gaps

The build/tests used base HEAD plus all five final working-tree test edits, recorded in ignored `430-evidence/source-base.txt` and `source-working.diff` before installation. App SHA256 `99a25e4d0c597b32787e1a3cbaac8850589cbb54c6c682eb13bfa0923fba2c59`; test APK SHA256 `db96e3152fd1a0c9c9ba30135954ca5fd52716ffe1eb3620e9211a4a44acf74b`. The tested source was not yet committed during the device run; this is honest base-plus-working-diff evidence, not a claimed clean committed-source gate. Controller will run the final integrated committed gate later. Only this public-safe report was added after device execution.

Raw logs (including first-red JVM output), final XML, APK hashes, source diff, strict gesture/native summaries, filtered synthetic diagnostics and timing live under ignored `.superpowers/sdd/issue-426/430-evidence/`; no raw evidence/private fixture/APK is committed. The worker owned no new app fixture roots; no fixture cleanup/deletion was necessary. Pre-existing files were left unchanged, before/after SHA256:

- `cache/issue428-unrelated-sentinel`: `d28a4662bdc46daaf03456e0da79e422a75af1b25c2ad76285df54cdbeeff4b1`.
- `files/ghost/root-test/issue428-preexisting-residue`: `22e483692f871aa16d1625efb1b3b750700be77a9ed1e6b678fd4b33e99da3d8`.

No authorized identity-verified private fixtures were available to this task. Dynamic Earthquake rendering and real private/native fixture cohorts remain unexecuted. No real private archive acceptance map was invented. Existing M5 missing LOBO Pixel cycles and intermittent Compose wrong-thread/IME exceptions remain accepted risks, not fixed/passing results.

Self-review preserved all existing @Test identities and distinct positive/held/translation/resize/cancellation assertions. Working diff check passed exit 0. Full-range `git diff --check d5d99cf52685bb01c035f306007d6cacb0abeb2b..HEAD` and clean-status evidence are recorded at local commit closeout.
