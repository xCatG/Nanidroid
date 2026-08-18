# Updater Backend Deletion Baseline

This report pins the currently executable pre-edit evidence for issue #382 PR B.
It is not a green-baseline claim and does not replace the missing arm64 or exact
23-NAR runtime gates.

## Identity

- Production base: `15aae15ac13f8a47281bd18bc2319dc869ea789b`.
- Evidence-only plan commit: `8564023d72ca20a7812d0e10b42f9b55de6431d8`.
- Device: `emulator-5554`, AVD `Nanidroid_API_37`.
- Fingerprint:
  `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:user/release-keys`.
- API: 37.
- Runtime ABI: `x86_64`.
- `ro.kernel.qemu`: `1`.
- Debug APK SHA-256:
  `92443d919eb1dcf9b2ac94894ccf3e5cb6ad7599fccf051c4321a263bcd062f5`.
- Android-test APK SHA-256:
  `a3121a9090b0e27c062335da87413b137ad8e783bae99548e5d64d384b2f8f33`.

The connected build compiled the configured `arm64-v8a` and `x86_64` native
targets successfully. Only x86_64 executed. Compilation is not arm64 runtime
evidence.

## Host baseline

The following exact pre-edit lanes passed:

- `testDebugUnitTest`, `jacocoTestReport`,
  `compileDebugAndroidTestKotlin`, `compileDebugScreenshotTestKotlin`,
  `assembleDebug`, and `validateDebugScreenshotTest`: `BUILD SUCCESSFUL`;
  66 actionable tasks, 6 executed and 60 up-to-date. The connected build also
  rebuilt both configured native ABIs before device execution.
- `run-ui-visual-audit.ps1 -DryRun -HostSelfTest`: schema 2, case set
  `2026-08-17.1`, 64 cases, manifest SHA-256
  `209798911e813809013f7e37aefa85bb4a20eced34e16144e6517ab5e282faad`.
- `python -m unittest tools.test_verify_phase1_shipped_state_audit`: 76 tests
  passed.
- `python tools/verify_phase1_shipped_state_audit.py`: compatibility Path A
  verified.
- `lint`: expected nonzero result with exactly 5 errors, 65 warnings, and 1
  hint. All five errors are `MissingTranslation` on the English archive queue
  overflow resources: `archive_queue_overflow_empty`,
  `archive_queue_overflow_active`, `archive_queue_overflow_complete`,
  `archive_queue_overflow_attention`, and `archive_queue_overflow_other`.
  The 36,196-byte text report had SHA-256
  `4aed35525853d052bc14724e68cd033a640bfeaf08860e60d254c092e52b86b2`.
  Candidate lint may remove findings owned by its resource deletion, but must
  add no error, warning, or hint identity that is absent from this baseline.

`git diff --check` passed for the evidence delta, apart from the working-copy
line-ending notice.

## Connected baseline

Command:

```powershell
.\gradlew.bat connectedDebugAndroidTest --stacktrace
```

JUnit XML: 175 tests, 10 failures, 0 errors, one skip, 863.398 seconds. The
Gradle console printed `Finished 176 tests`; candidate comparison must use the
JUnit XML inventory and names, not that inconsistent console aggregate.
The generated XML was 51,047 bytes with SHA-256
`4e716692edc3c119d894116691a1659ea55118ca8604c6b8101e56396fdfd59e`.
Because `build/` is ephemeral, the durable comparison contract is the fully
qualified identity, exact diagnostic below, and relevant top source frame.

Known base failures:

1. `NanidroidLifecycleInstrumentationTest#recreatedActivityWithNullRunnerRejectsArchiveIntentWhenRetainedRunnerIsPassive`
   — `java.lang.AssertionError: expected null, but was:<content://archives/recreated.nar>`;
   `NanidroidLifecycleInstrumentationTest.kt:102`.
2. `NanidroidLifecycleInstrumentationTest#invalidWarmFileAndHttpArchiveIntentsAreIgnoredAndBecomeCurrentIntent`
   — `java.lang.AssertionError: Activity never becomes requested state
   "[DESTROYED]" (last lifecycle transition = "RESUMED")`;
   `NanidroidLifecycleInstrumentationTest.kt:212`.
3. `NanidroidLifecycleInstrumentationTest#acceptedArchiveWorkDefersNotificationPermissionUntilStartedActivityResumes`
   — `java.lang.AssertionError: Notification permission dialog was not launched`;
   `NanidroidLifecycleInstrumentationTest.kt:167`.
4. `NanidroidComposeShellTest#real_160dp_display_keeps_explicit_actions_at_minimum_touch_width`
   — `java.lang.AssertionError: Cancel target is too narrow: 97.0px`;
   `NanidroidComposeShellTest.kt:1271` from test call at line 1033.
5. `NanidroidComposeShellTest#real_narrow_display_keeps_cancel_and_submit_independently_reachable`
   — `java.lang.AssertionError: Cancel target is too narrow: 117.0px`;
   `NanidroidComposeShellTest.kt:1271` from test call at line 1050.
6. `NanidroidSimpleDialogsTest#passwordInputUsesPasswordKeyboardSemantics`
   — `androidx.compose.ui.test.ComposeTimeoutException: Condition still not
   satisfied after 5000 ms`; `NanidroidSimpleDialogsTest.kt:59`.
7. `GhostBubbleInteractionTest#standardCompactAbsentAndFontTwoLayoutsKeepFixedBubbleCellsUsable`
   — `java.lang.AssertionError: Activity never becomes requested state
   "[DESTROYED]" (last lifecycle transition = "PAUSED")`; teardown entered
   through `ActivityScenarioRule.after` (no project source frame in the stack).
8. `NarCorpusRuntimeTest#snakeBootLifecycleRetainsFailedPrimaryAndFallbackChoiceEvidence`
   — `org.json.JSONException: No value for references`;
   `NarCorpusRuntimeTest.kt:290`.
9. `NarCorpusRuntimeTest#snakeBootLifecycleStopsBeforeInputWhenFallbackChoiceIsUnplayable`
   — `java.lang.AssertionError: expected:<[OnFirstBoot, OnChoiceSelectEx,
   OnChoiceSelect]> but was:<[OnFirstBoot]>`; `NarCorpusRuntimeTest.kt:361`.
10. `GhostUpdateRecoveryTest#recoveryWorkerQueriesExactDurableWorkIdentityBeforeRollingBackPreparedUpdate`
    — `java.lang.AssertionError: expected:<SUCCEEDED> but was:<ENQUEUED>`;
    `GhostUpdateRecoveryTest.kt:79`.

Skipped:

- `NarCorpusRuntimeTest#probesArchive`, because no external corpus arguments or
  payloads were supplied. This skip supplies no compatibility evidence.

Candidate x86_64 acceptance requires no new failures and no additional skips.
Each retained red test must either turn green or preserve its recorded
exception/assertion message and relevant stack identity; a changed failure
signature is a regression even if the test name remains red. The updater-
recovery test is intentionally deleted by the change, so comparison must
reconcile tests by fully qualified name and behavior instead of requiring the
same aggregate count.

## Missing hard merge gates

- No `.nar` file matching the exact 23-entry manifest exists in the documented
  external corpus location or common local corpus locations.
- No prior `build/reports/nar-corpus/summary.json` remains available.
- Installed AVD system images and devices are x86_64; no arm64 runtime target is
  currently available.
- Therefore no pre-edit 23-NAR field-by-field run or real Satori -> YAYA ->
  Kawari -> Satori paired sequence was claimed.

The immutable production base can be recreated later in a clean worktree.
Before merge, run the pinned base and unchanged candidate back-to-back with the
same recovered hash-matching corpus and both runtime ABIs. Failure to recover
that evidence keeps the draft unmergeable; it does not justify weakening the
gate or starting #384.
