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

## Review fix round 1

Addressed `.superpowers/sdd/issue-426/review-430.md` on `9547ff3d39801cd1b41c71e95d77b9a42f7145a9`. Replaced all 45 native runtime awaited labels with complete short semantic condition names. Compound predicates name their complete requirements (for example, ready Earthquake ghost with native lease and boot event); choice alternatives, recorded replies and rendered signatures are explicit. Removed only the unused `ready` local in nativeSnapshot. The actual-helper synthetic assertion now requires the complete `awaited=ready LOBO ghost elapsedMs=` diagnostic field and actual last state. A normalized before/after diff check confirmed predicates, deadlines and other behavior were unchanged; its output was `Normalized scoped diff: predicates, deadlines and other behavior unchanged` (exit 0).

Covering commands, with the same explicit JDK/SDK and owned emulator:

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest --offline --console=plain
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r -e class 'com.cattailsw.nanidroid.ui.NativeTalkProbeTest#nativeAboutBackKeepsSameReadyLeaseWithoutBootOrClose' com.cattailsw.nanidroid.test/androidx.test.runner.AndroidJUnitRunner
```

Assembly output: `BUILD SUCCESSFUL in 5s`, exit 0. Install: `Success`. One selected/observed wrapper method, 0 passed/1 skipped/0 failed/0 incomplete method records; strict outcome remains incomplete due to missing fixtureId. Instrumentation output: terminal method status -4 with the expected absent-fixtureId assumption, terminal instrumentation code -1, time 0.389 s, wall 1.722 s, adb exit 0. The permanent @Before actual-helper assertions completed successfully before that assumption; filtered logcat output proves:

```text
WAIT_DIAGNOSTICS_SYNTHETIC Timed out scenario=synthetic-LOBO awaited=ready LOBO ghost elapsedMs=100 deadlineMs=100 absoluteDeadlineMs=100 lastStageState=Finished lease=null availability=Available observedEvents=0
```

No private fixture/native cohort was run or counted as passing. No JVM, gesture or full-suite repetitions were needed for these diagnostics-only edits. Both protected sentinel SHA256 values remain unchanged. No owned fixture roots were created or deleted.

APK/source evidence: base `9547ff3d39801cd1b41c71e95d77b9a42f7145a9` plus final NativeTalkProbeTest working diff at build time, captured in ignored `430-evidence/fix1-source-base.txt` and `fix1-source-working.diff`; only this report was appended afterward. App APK hash unchanged `99a25e4d0c597b32787e1a3cbaac8850589cbb54c6c682eb13bfa0923fba2c59`; rebuilt test APK SHA256 `bd66bc389a3c434eb59886b006f5fee50a04242d7ef02facd52baab10dc1a6d8`. Raw assembly/instrumentation/diagnostic/timing/hash evidence is ignored under `430-evidence/fix1-*`. This is base-plus-working-diff verification, not the later clean integrated committed gate.

Working and full-range `git diff --check d5d99cf52685bb01c035f306007d6cacb0abeb2b..HEAD` return exit 0; clean local status is checked at fix commit closeout. Independent scoped re-review remains controller work.

## CI regression: registered worker boundary

Resumed sequentially on clean branch430 revision `5269f58448b2face5a92c5bf7e99216ca3a3a5da`, after the controller's issue432 local gate. Only `ImportCoordinatorTest.kt` and this report changed. First CI failure remains retained: PR437 run `37727440576`, source `5269f584`, log `.superpowers/sdd/issue-426/ci-437-37727440576-failed.log` and HTML under `ci-437-37727440576-reports/tests/testDebugUnitTest/com.cattailsw.nanidroid.install.ImportCoordinatorTest/cancellationBeforeInstallBeginEmitsNoInstallEvent.html`. CI logged `380 tests completed, 1 failed`; the HTML's exact assertion was `expected:<Cancelled> but was:<Installed(directoryId=visitor)>`. No unchanged CI retry occurred.

Diagnosis from the current accepted source: `ImportCoordinator.acceptResult` sets Running, then executes `running = scope.launch { ... }`. Default/IO may execute the fake provider's `writeSourceChunk` callback before launch returns and assigns `running`. The test's early callback then calls `cancelBeforePublication` while `running` is null; that cancellation cannot cancel the intended job. The failure is a test admission-order assumption. This change establishes the needed provider/registered-worker boundary without changing production/runtime, swallowing failures, adding sleeps/retries, or weakening cancellation/event assertions.

A temporary deterministic old-order control delegated actual work to Default but held the launching thread inside dispatch until the original write hook attempted cancellation. The hook asserted `coordinatorJob == null` and emitted `CONTROL_EARLY_CANCEL runningJob=null cancellationAttempted=true`. The single selected test failed exactly `Cancelled` versus `Installed(visitor)`, matching CI (1 executed/0 passed/1 failed/0 skipped/0 incomplete; JUnit 0.298 s, Gradle 17 s). The retained source/log/XML are `430-evidence/ci-regression/control-old-order.kt`, `.log`, `.xml`. This temporary unsafe callback-before-registration control was removed by restoring the captured pre-control test source before implementing the fix; it is not committed.

The permanent regression now forces real IO provider entry before acceptResult returns, then blocks provider admission on a bounded CountDownLatch until the launched job has been registered. This tests the same early-worker scheduling while preventing the fault callback from outrunning registration. The class-local `acceptWithRegisteredWorker` gate applies to the three callback-driven cancellation methods only. Each records and asserts that its fault hook cancelled an active registered job. `cancellationBeforeInstallBeginEmitsNoInstallEvent` still cancels in the first source write before validation/install-begin and asserts Cancelled, exactly OnBoot, and no installed visitor. `cancellationAfterInstallBeginEmitsFailureWithoutReferences` still asserts Cancelled, exact begin/failure order, empty failure references and no installed visitor. `cancellationAfterMoveStillReportsInstalled` still asserts Installed and published files; its explicit cancellation observation prevents a no-op cancel from passing, including when afterMove's production fault wrapper catches an exception. The latter two share the identical callback/registration race and are the only justified sibling adjustments. The pre-begin and post-begin negative assertions now join newly admitted operation/event work after Completed, before asserting absence/exact events. Actual Default/IO execution remains; no virtual dispatcher replaces provider IO.

Covering commands, explicit existing JDK17/SDK environment:

```powershell
# Temporary deterministic old ordering: expected red, retained once.
.\gradlew.bat :app:testDebugUnitTest --tests 'com.cattailsw.nanidroid.install.ImportCoordinatorTest.cancellationBeforeInstallBeginEmitsNoInstallEvent' --rerun-tasks --offline --console=plain
# Corrected full affected class.
.\gradlew.bat :app:testDebugUnitTest --tests 'com.cattailsw.nanidroid.install.ImportCoordinatorTest' --offline --console=plain
# One forced full JVM execution on final source.
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --offline --console=plain
```

Corrected focused output: `BUILD SUCCESSFUL in 6s`; XML 18 executed/18 passed/0 skipped/0 failed/0 incomplete, class duration 1.637 s. A newly introduced unnecessary-safe-call warning was removed before the full run; final full run covered that cleanup. Forced full output: `BUILD SUCCESSFUL in 27s`, all 24 actionable tasks executed, exit 0. Final XML totals: 26 classes, 380 executed/376 passed/4 skipped/0 failed/0 incomplete. The three cancellation methods took 0.015 s (before begin), 0.055 s (after begin), 0.057 s (after move). No reliability rate or Linux result is inferred.

Four full-run skips retain their actual Windows symlink-privilege assumptions and are not passes:

- `InstalledGhostRepositoryTest#symlinkCandidatesCannotEscapePrivateRoot`.
- `NativeProfileDirectoryTest#rejectsEscapingProfileSymlink`.
- `ImportStagingTest#linkedRootAndLinkedAttemptAreRejectedWithoutTraversal`.
- `ImportStagingTest#linkedChildInsideAttemptBlocksRecoveryWithoutDeletingOutside`.

Existing nullable-file/compiler warnings outside the owned test remain visible in the retained full log; no unrelated files were edited. The final ImportCoordinatorTest introduces no compiler warning. All @Test method identities and original outcome/file/event assertions are preserved. Windows host-only JVM verification used temporary JUnit roots; no device/APK/gesture/private fixture operations or global cleanup occurred. Linux/updated CI execution was not performed by this worker and remains controller evidence, distinct from the successful Windows gate.

Raw control/focused/full logs, separate control/focused XML, all final full XML, pre-control source and aggregate counts remain ignored under `.superpowers/sdd/issue-426/430-evidence/ci-regression/`. No raw evidence is staged. Working/full-range `git diff --check 7cb7106a..HEAD` return exit 0 at local commit closeout; clean status and focused local commit are reported to the controller for independent scoped review.

## Hosted instrumentation regression: settled orientation before touch

Workspace/root AGENTS unchanged; controller placed clean branch430 on `3fa83a5f9a6b3eb06e099e86a4513199a9fba054`, preserving later branch heads. Concrete scope ruling: the controller explicitly authorized the originally out-of-five `app/src/androidTest/java/com/cattailsw/nanidroid/WalkingSkeletonInstrumentationTest.kt` for this observed failure, stating to ensure stable expected orientation/runtime before input and await restoration so the rotation selector cannot leave pending recreation. Only that file and this report changed; no production/native/runner/CI changes or broad suite extension.

First hosted failure is retained, not retried unchanged: run `37735151175`, downloaded summary/raw log under `.superpowers/sdd/issue-426/hosted-final-37735151175/device/reports/device-self-contained/runner/`; console `.superpowers/sdd/issue-426/ci-440-37735151175-failed.log`. Hosted summary source `27f602f1acf03f3f385cea5b8914fd76860d1d67`: 117 terminal methods, 116 passed/1 failed/0 skipped/0 incomplete, cleanup passed, duration 244.315 s. Exact failed method `WalkingSkeletonInstrumentationTest#bundledKeroTapDispatchesOnceWithSurfaceCoordinatesAndNoReply`: `ComposeTimeoutException: Condition still not satisfied after 3000 ms` at source line57, the existing `eventCount("OnMouseClick") == 1` wait after real touch input. This is a distinct existing test prerequisite boundary, not one of the five prior scoped waits or evidence to weaken the exactly-once/coordinates/204/no-dialogue product assertions.

Read-only retained log diagnosis: the immediately preceding `rotationMidDialogueKeepsPlaybackAndDoesNotBootAgain` finished at 06:10:47.991, followed by the tap method at 47.993. The tap Activity was CREATED/RESUMED at 06:10:48.080/.081, then PAUSED/DESTROYED at 06:10:53.066/.075 and recreated/resumed at 53.092–53.112. Portrait restoration was still being applied at 53.680, causing another pause/destroy/recreate/resume at 53.702–53.750. The original rotation test requested landscape but had no configuration/lifecycle restoration boundary before teardown; the tap only required a Ready state and an existing semantics node. Production pointer-input disposal/token changes cancel pending taps and runtime click admission requires resumed state. These observed late configuration transactions substantiate the missing stable-input prerequisite. The failed bundle did not record the event count or exact injected-touch timestamp, so it does not independently prove which particular cancellation/admission edge suppressed the callback; no broader product defect is claimed. Relevant timestamps are retained in ignored `hosted-tap/hosted-lifecycle-excerpt.log`.

The tap test now requests portrait and awaits the actual current Activity's portrait configuration, RESUMED lifecycle, window input focus and matching measured decor dimensions before releasing loading/performing input. A class-local condition helper also synchronizes Compose idle and asserts the settled state. The rotation test now waits for actual initial portrait and requested landscape, then in finally requests/awaits portrait restoration before Activity teardown. This directly controls the observed pending configuration boundary without replacing runtime or touch callbacks. The helper has a bounded 10 s configuration deadline matching existing fixture readiness waits; the event wait remains exactly 3,000 ms. No sleep, blanket timeout increase, retry, quarantine or swallowed assertion was added. Existing recreation/rotation boot counts, playback continuity and the tap's exactly-one-event, `117,100` authored coordinates, 204 reply, empty text and invisible balloons remain unchanged. The landscape/portrait lifecycle-and-dimension assertions are the focused deterministic configuration boundary check; no speculative fault injection/new @Test identities or separate unchanged old-code device retry was needed because the hosted first failure and lifecycle evidence already exist.

Covering commands with explicit JDK17/SDK and exact verified owned `emulator-5580` (qemu1/API31/x86_64, 1080×2400/420dpi/font1):

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r -e class 'com.cattailsw.nanidroid.WalkingSkeletonInstrumentationTest#rotationMidDialogueKeepsPlaybackAndDoesNotBootAgain,com.cattailsw.nanidroid.WalkingSkeletonInstrumentationTest#bundledKeroTapDispatchesOnceWithSurfaceCoordinatesAndNoReply' com.cattailsw.nanidroid.test/androidx.test.runner.AndroidJUnitRunner
```

One final build/install and one focused sequence, no broad119/117 suite or repeated green/unchanged CI run: assembly `BUILD SUCCESSFUL in 5s`, exit0; both installs `Success`; instrumentation terminal code -1, `OK (2 tests)`, 5.182 s instrumentation/6.669 s wall, adb exit0. Existing strict `Read-SuiteTranscript` parser reports selected2/observed2/passed2/skipped0/failed0/incomplete0. Actual terminal execution order from raw bundles is rotation method first, bundled tap second; it was explicitly compared with the intended sequence, not inferred from selector ordering. Existing retained host failure remains red evidence, separate from this Windows emulator verification; hosted fixed CI/integrated validation remains controller work.

Filtered actual boundary output (all assertions passed, input focus also checked): 23:18:43.684 portrait1/RESUMED/1080×2400; 44.789 landscape2/RESUMED/2400×1080; 45.283 restoredportrait1/RESUMED/1080×2400; 45.890 tapfixtureportrait1/RESUMED/1080×2400. This sequence confirms restoration precedes the next tap method in the same instrumentation process. No reliability-rate inference.

APK provenance is base `3fa83a5f` plus this final WalkingSkeleton working diff recorded before installation; only the report was appended afterward. App SHA256 `e03ae86f3897576808e14210d46b95700ea7dfcd0caeb7a4190046c204942906`; test APK `eeb473c7028f1dc45baf3edb78d3889ac019927ae0bfbafd34d7eb8d08ee6562`. Raw assembly/source diff/APK hashes/transcripts/strict summary/timing/boundary diagnostics/sentinel hashes stay ignored under `.superpowers/sdd/issue-426/430-evidence/hosted-tap/`. Both issue428 sentinel hashes are unchanged from the recorded values above. No app data clear, fixture deletion, private corpus or user-device installation occurred; only existing bundled fixture data was used.

Working/full-range `git diff --check 7cb7106a..HEAD` exit0 and clean status are checked at focused commit closeout. Independent scoped review and later-branch integration remain controller work.

## Hosted JVM regression: immediate prompt dismissal on one real owner

Workspace/root AGENTS unchanged, clean branch430 start `4138cdf64ed3641d85a09ea8ceb5473cdcf9a850`; later branches preserved by the controller. Only `ImportCoordinatorTest#resolvingShownPromptCompletesAttempt` and this report changed. No shared helper, production/native, device/APK, runner or CI edits.

First failure is retained: PR440 run `37737403047`, log `.superpowers/sdd/issue-426/ci-440-37737403047-failed.log`, downloaded HTML `.superpowers/sdd/issue-426/ci-440-37737403047-reports/tests/testDebugUnitTest/com.cattailsw.nanidroid.install.ImportCoordinatorTest/resolvingShownPromptCompletesAttempt.html`. Build logged 380 tests/1 failure and the device job was skipped. Exact stack is `TimeoutCancellationException: Timed out waiting for 10000 ms` at ImportCoordinatorTest.kt:460, waiting `coordinator.state.first { it == ImportState.Idle }` after `runtime.dismissSwitch()`. The preceding Completed.Shown observation succeeded. This test uses runBlocking, so the 10 s timeout is real; the earlier virtual-clock hypothesis does not apply. Existing same-code successful CI runs `37737317070` and `37737380494` are historical evidence, not a retry/fixed result. No unchanged CI retry was performed.

Current source contract: `ImportCoordinator.offerPrompt` updates Completed.Shown only after `runtime.offerImportedGhost`/`offerSelection` publishes the actual prompt and returns Shown. A user may dismiss that prompt immediately; waiting for all import/event work before dismissal would remove that behavior from the test and was rejected. `NanidroidApplication` owns the production runtime/coordinator through `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`. The old JVM fixture used a multithreaded Default scope plus direct entry points on a separate runBlocking event-loop thread. Runtime `publish` snapshots mutable session/prompt data without multithread synchronization; parallel publication and observer/offer scheduling in that fixture does not model the application's serialized state owner. This mismatch is the supported diagnosis, but the hosted failure contains no last-state/scheduling trace and the original local timeout was not reproduced. The exact losing interleaving remains inferred; this report does not claim a deterministic original timeout reproduction or prove a production user-dismiss race.

The final test runs its runBlocking caller on one real `Dispatchers.Default.limitedParallelism(1)` instance and gives scope jobs that exact owner context. Thus ALL direct mutable coordinator/runtime entry points and background state-owner jobs share the serialized owner, not just the background jobs. Actual GhostImporter Dispatchers.IO provider work, runtime IO validation, real timeouts and suspended engine requests remain. This affects only the failing fixture/test; the other concurrent ImportCoordinator tests/helper behavior are unchanged.

Deterministic boundary control: the existing fake-engine gate suspends OnInstallBegin, and the test observes its actual event entry before awaiting public Completed.Shown. It asserts matching attempt/outcome and actual visitor switch prompt, confirms the install gate is unresolved and OnInstallComplete absent, then immediately dismisses without waiting for any install terminal boundary. It observes actual prompt removal and coordinator Idle while the request is still pending. Only AFTER Idle does it release the gate, await OnInstallComplete and join accepted event/operation work. It then asserts coordinator remains Idle, runtime prompt remains null, and exact OnBoot/OnInstallBegin/OnInstallComplete events. This preserves the original Shown→dismiss→Idle assertion and strengthens later-publication protection; it does not paper over immediate dismissal by waiting first. No deadline increase, sleeps, retries, quarantine, skips or assertion weakening.

Covering command (explicit existing JDK17/SDK, offline pinned cache):

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.cattailsw.nanidroid.install.ImportCoordinatorTest' --offline --console=plain
```

An intermediate scope-only serialization attempt passed 18/18, `BUILD SUCCESSFUL in 6s`; its log `hosted-prompt/focused.log` and XML `focused-background-only.xml` are retained. Controller review correctly required direct caller entry points to use the same owner too; the final correction moved runBlocking onto that owner and re-used its interceptor for scope jobs. The changed final source was verified once more, rather than repeating unchanged green code: `BUILD SUCCESSFUL in 6s`, exit0, 18 executed/18 passed/0 skipped/0 failed/0 incomplete; final class 1.564 s and controlled method 0.088 s. No local focused attempt failed for this regression; the sole original first-red evidence remains the hosted failure above. Final log/XML retained as `hosted-prompt/focused-final.log`/`.xml`. No forced full JVM/device/assembly/full instrumentation suite was justified or run because no shared helper/product code changed; later integrated/hosted verification remains controller work.

Actual tested source is base `4138cdf64ed3641d85a09ea8ceb5473cdcf9a850` plus the final method-only diff, captured in ignored `hosted-prompt/source-base.txt` and `source-final.diff`; only the report was appended afterward. Pre-fix source and all intermediate/final evidence remain ignored under `.superpowers/sdd/issue-426/430-evidence/hosted-prompt/`. JUnit-owned temporary roots handle this host-only verification; no external/private fixture/data cleanup was performed. All @Test identities remain unchanged. Working/full-range `git diff --check 7cb7106a..HEAD` return exit0 and clean status are checked at focused commit closeout; independent scoped review follows.

## Hosted JVM regression: shared import fixture owner

Workspace `C:/Users/yenchi/.codex/worktrees/bd56/Nanidroid`, root `AGENTS.md` loaded; clean start `506a88cb65233185d094747619038c28b0a6d364` on branch430. Only ImportCoordinatorTest and this report changed. Later branches remain controller-owned.

First failure retained: PR440 run `37738954237`, log `ci-440-37738954237-failed.log` and downloaded reports `ci-440-37738954237-reports` under this report directory. Hosted Build recorded 380 tests/1 failure; device job skipped. The exact failing stack is TimeoutCancellationException after the unchanged real 10000 ms deadline at ImportCoordinatorTest.kt:141, awaiting StageState.Finished in blockedBeginCannotDelayPublicationAndCloseDropsQueuedComplete after runtime.close. Installed outcome and publication/file assertions already succeeded while OnInstallBegin remained suspended. The previously corrected immediate-dismiss test passed. No scheduling/last-state trace identifies the exact losing interleaving; the original hosted timeout was not reproduced locally. The fixture ownership mismatch is supported by current source, while attribution of that exact timeout remains an inference.

The application uses one Main.immediate owner for mutable runtime/coordinator entry points and scope jobs. The class audit found 17 ordinary fixtures still using a multithread Default scope plus a separate runBlocking caller. A class-local runOnRuntimeOwner now executes the caller on a real Default.limitedParallelism(1) owner, and ownerScope uses that exact caller interceptor. The deliberate cancellationBeforeInstallBeginEmitsNoInstallEvent early-worker fault fixture keeps its custom dispatcher and registration proof byte unchanged. This explicitly authorized exception must remain concurrent to exercise its intended fault. Actual provider Dispatchers.IO work, suspendable engine gates, queued-close/drop assertions, callback fault injections, event ordering, and immediate Shown dismissal all remain intact. No production changes, deadline increases, sleeps, retries or skips were introduced. A normalized diff proves all test bodies/assertions/callbacks/deadlines unchanged except ownership; the pending-event immediate-dismiss proof remains intact.

Covering commands with the established JDK17/SDK and offline pinned cache:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.cattailsw.nanidroid.install.ImportCoordinatorTest' --offline --console=plain
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --offline --console=plain
```

Focused class: BUILD SUCCESSFUL in 6s, exit0, 18 executed/18 passed/0 skipped/0 failed/errors, class 1.657s. Because the shared fixture changed, one forced full JVM run followed on the same source: BUILD SUCCESSFUL in 26s, exit0, 24 tasks executed, 26 classes/380 tests/376 passed/4 skipped/0 failed/errors/incomplete. Named boundary durations: blockedBeginCannotDelayPublicationAndCloseDropsQueuedComplete 0.090s; resolvingShownPromptCompletesAttempt 0.096s; deliberate early-worker cancellation 0.015s. No local failed attempt occurred for this correction. The first hosted red evidence remains retained; no unchanged CI retry, Linux run, device/APK/assembly or broad instrumentation run was performed.

The four existing Windows symlink privilege skips were InstalledGhostRepositoryTest#symlinkCandidatesCannotEscapePrivateRoot, NativeProfileDirectoryTest#rejectsEscapingProfileSymlink, ImportStagingTest#linkedRootAndLinkedAttemptAreRejectedWithoutTraversal, and ImportStagingTest#linkedChildInsideAttemptBlocksRecoveryWithoutDeletingOutside. Existing unrelated compiler warnings remain; they are not new owned warnings or passing evidence for skipped cases.

Actual tested source is base `506a88cb65233185d094747619038c28b0a6d364` plus the final ImportCoordinatorTest working diff; only this report was appended afterward. Ignored raw evidence in `430-evidence/hosted-owner-audit/` retains source-before.kt, source-base.txt, source-final.diff, owner-methods.txt, scope-check.txt, focused.log/XML, full-jvm.log, full-xml and full-counts.json. No temporary mutation/control was introduced or left behind in this correction. Earlier controls and first failures remain in their original evidence directories. JUnit-owned temporary roots handle host fixtures; no private/external/device cleanup occurred. Working and full-range `git diff --check 7cb7106a..HEAD` with explicit exit0 and clean status are checked at commit closeout. Independent scoped review remains controller work.

## Hosted surface-publication boundary: explicit scope ruling

Workspace `C:/Users/yenchi/.codex/worktrees/bd56/Nanidroid`, root `AGENTS.md` reloaded, systematic-debugging/test-design applied. Read-only diagnosis began on controller branch433 `e66f863f`; controller then switched clean branch430 to `dd877d5b753e7c9bdc3f32592bb1f81482c20b6d`. Explicit controller ruling expands #430 ownership narrowly to AuthoredSurfaceUiTest and StagePolishTest plus this report for the observed surface-publication boundary. No production/API/runner/native/workflow or other worker files changed.

First hosted evidence is preserved under `hosted-final-37740527204/device/reports/device-self-contained/runner/`: summary.json, instrumentation.stdout.log and logcat.stdout.log. Run37740527204 JVM380/380 passed; device117 completed/115 passed/2 failed, cleanup passed. AuthoredSurfaceUiTest#numberedStaticAlwaysRemainsVisibleWhenElementZeroIsAuthored failed its first assertExists at helper line150; StagePolishTest#debugBoundsDrawsBothAuthoredCollisionsAndTracksDisplayedBase failed the initial bitmap capture at line123 called from131. Both reported no merged sakura node, followed by one matching unmerged node in the diagnostic query. They failed before their pixel/collision or input assertions. WalkingSkeleton passed.

Current GhostStage initially publishes LoadedImages.Loading; produceState calls loadSurfaces, including actual Dispatchers.IO fixture-path validation, before publishing images/CharacterSurface. Neither failing test observed that completion. No production ancestor mergeDescendants/clearAndSetSemantics explains stable merged exclusion; the later diagnostic lookup finding a node is consistent with asynchronous publication between queries. The exact hosted interleaving remains inferred, not captured or claimed deterministically reproduced. The correction awaits exactly one actual merged sakura node with positive rendered width/height before the original assertExists/capture. Each new wait is bounded at2000ms; existing deadlines and every original exact pixel/collision/input assertion remain unchanged. No unmerged-tree substitution, capture sleep, timeout inflation, retry, or production behavior change.

Controlled boundary evidence is permanent within the existing element-zero method identity: its suspendable SurfaceImageLoader announces entry and awaits a CompletableDeferred gate. After entry, the original immediate assertExists is executed and required to fail with AssertionError. The actual readiness predicate must then observe an empty merged-node list before releasing the loader; subsequent polls await the measured character and proceed through the original green/blue pixel oracle. This demonstrates old immediate ordering fails with loading pending and the new publication wait handles that boundary, without claiming that forced gate reproduces the hosted scheduling interleaving. The later-element sibling exercises the same readiness wait without the gate. No added test identity or temporary instrumentation mutation was needed.

Covering commands, established JDK17/SDK offline cache and exact authorized disposable emulator:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r -e class 'com.cattailsw.nanidroid.ui.AuthoredSurfaceUiTest#numberedStaticAlwaysRemainsVisibleWhenElementZeroIsAuthored,com.cattailsw.nanidroid.ui.AuthoredSurfaceUiTest#numberedStaticAlwaysRemainsVisibleWhenLaterElementIsAuthored,com.cattailsw.nanidroid.ui.StagePolishTest#debugBoundsDrawsBothAuthoredCollisionsAndTracksDisplayedBase' com.cattailsw.nanidroid.test/androidx.test.runner.AndroidJUnitRunner
```

Emulator verified before install: emulator-5580, AVD Nanidroid_Issue426_API31, API31,1080x2400,420dpi. One assembly/install/focused run: BUILD SUCCESSFUL in5s, exit0; both installs Success; instrumentation OK(3tests),6.073s, terminal code-1, adb exit0. Actual raw terminal order is later-element sibling, element-zero controlled case, then StagePolish collision case;3 completed/3 passed/0 failed/skipped/incomplete. Logcat control at10-08 00:20:09.851 states pending loader/merged sakura absent before release. All original pixel/collision/touch checks passed. No unchanged full suite/CI retry, broader JVM/device suite or private corpus run. First hosted failures remain retained, separate from focused local evidence.

APK/source identity: tested base dd877d5b plus the final two-file working diff, captured before installation; only this report was appended afterward. App SHA256 e03ae86f3897576808e14210d46b95700ea7dfcd0caeb7a4190046c204942906; test APK d13f6420d068ffcfc493b812a414c101b62f0153e53f12dedbd2c853d33bc55e. Raw ignored `430-evidence/hosted-surface/` contains assembly/install/transcript/control logs, terminal-counts.json, source-base.txt/source-final.diff and APK hashes. An initial UTF16 transcript parsing attempt failed due to UTF8 encoding; corrected decoding preserved the raw transcript and verified all three terminal bundles. No test attempt failed locally. Both previously recorded issue428 sentinel hashes remain unchanged; JUnit owned fixture cleanup applies, with no data clear/global/private deletion. Working/full-range `git diff --check 7cb7106a..HEAD` explicit exit0 and clean status are checked at focused commit closeout; independent scoped review and hosted integration remain controller work.

## External P1: test-owned worker admission without reflection

Clean branch430 start faa54ce0cdc6dd21c0991482f8f2f608d875e0fa; workspace/root AGENTS unchanged. First external P1 review comment4215860011 on PR437 identified private ImportCoordinator.running reflection, independently confirmed by reviewer430. Controller authorized only ImportCoordinatorTest and this report. No production test seam, dispatcher, provider IO, device/native/runner/workflow or other worker file change.

Removed coordinatorJob/getDeclaredField/isAccessible entirely. The admission helper snapshots the test-owned scope's existing children before acceptResult. Its real IO provider still signals the deliberately early-worker control before awaiting launchReturned. After acceptResult returns, the helper requires exactly one new active child while provider progress is held, and publishes that exact Job through AtomicReference before counting down the latch. Fault callbacks read the safely published test-owned job, invoke public cancelBeforePublication, and preserve the active-before/cancelled-after proof outside importer callback exception handling. The custom early-worker dispatcher, registration order gate, original outcome/event/file assertions, real IO, owner serialization and10s deadlines remain. Finished-session settling snapshots children before acceptance and uses existing bounded settleAcceptedWork after releasing the provider; file retention and Idle assertions remain.

Meaningful negative control: temporarily replace only the helper's public cancellation call with a no-op, run cancellationAfterMoveStillReportsInstalled once, and retain the first red log/XML. It fails specifically with AssertionError 'Fault hook did not cancel an active registered worker',1 executed/1 failed/0 skipped/errors, BUILD FAILED in5s, Gradle exit1. This matters because Installed remains a valid outcome after the move even when cancellation is absent; the job transition assertion still discriminates that fault. The mutation was restored byte-for-byte from saved final source before verification and is absent from the commit.

Commands with established JDK17/SDK and offline cache:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.cattailsw.nanidroid.install.ImportCoordinatorTest.cancellationAfterMoveStillReportsInstalled' --offline --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests 'com.cattailsw.nanidroid.install.ImportCoordinatorTest' --offline --console=plain
```

The restored final class passed18/18,0 skipped/failed/errors/incomplete, class1.626s; BUILD SUCCESSFUL in6s, exit0. Actual tested source is base faa54ce0 plus the final ImportCoordinatorTest working diff; only this report followed. Raw ignored `430-evidence/public-worker/` retains final source, noop.log/XML and focused.log/XML. No unchanged retry/fullJVM/device/APK run; ongoing controller hosted37743603204 results are separate evidence, not credited to this new fixture correction. Public scope-child admission proves this controlled test operation boundary rather than production internals; no original hosted interleaving is newly claimed reproduced. Earlier first failures remain retained. No external/private/device cleanup was needed. Working/full-range git diff --check7cb7106a..HEAD explicit exit0 and clean status are verified at commit closeout; independent scoped review remains required.
