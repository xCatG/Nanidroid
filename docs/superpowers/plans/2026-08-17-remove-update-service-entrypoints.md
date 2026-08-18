# Remove Update and Bottle Service Entrypoints

> Issue: #382. This is PR A of two consecutive phase-1 PRs. PR B removes the
> acquisition-unreachable updater backend, its still-live startup recovery,
> and update-only runtime mutation machinery.

## Goal

Remove every user/acquisition entrypoint for ghost self-update and the obsolete
SSTP Bottle polling service without touching archive durability, the updater
backend, normal ghost runtime ownership, JNI, or later #384–#386 work.

The result must have no Update menu item, no app service capable of starting an
update or polling Bottle, and no service-to-Activity notification-permission
handoff. The compiled update backend remains temporarily for the immediately
following #382 PR. New update acquisition becomes unreachable in PR A, but
`GhostMgr` startup recovery remains live until PR B. Path A proves no released
job or journal needs a migration.

## Fixed boundary

Delete:

- `NanidroidService.kt`, `SSTPBottleSensor.kt`, and `BottleLogSensor.kt`;
- the Update overflow action, Activity callback, SHIORI `homeurl` lookup, and
  `OnUpdateBegin` dispatch;
- service start/stop lifecycle helpers and their call sites;
- the Bottle-only `Collection<String>` runner queue overload and the now-unused
  `getStringValueFromShiori` runner helper;
- `GuardedAction.UPDATE`;
- the process-local `DurableNotificationPermissionAcceptance` service handoff,
  while retaining direct archive calls to `onUserDurableWorkAccepted()`;
- the app manifest service entry and both foreground-service permissions;
- service/update-only strings in all three locales;
- sensor/service behavior tests and obsolete source-text contracts.

Retain:

- `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, and
  `POST_NOTIFICATIONS` until #384;
- WorkManager's merged `SystemForegroundService`, archive receivers, Hilt/KSP,
  queue/store/workers, DownloadManager, local picker, and content ACTION_VIEW;
- `notification.png`, which durable attention still uses;
- `GhostUpdateJournal`, `GhostUpdateRepository`, `GhostUpdateWorker`,
  `NetworkUtil`, `GhostMgr` recovery, and update-only runtime mutation until PR B;
- outgoing ghost-authored dialogue/readme links;
- SHIORI `X-SSTP-PassThru-*` capability headers and SSTP-named native engine
  sources, which are protocol/engine compatibility rather than Bottle polling;
- normal runner clock lifecycle, ghost construction/reservation/attachment,
  switching, unload, `withGhostGate`, dialogue, surfaces, and collision behavior;
- the immutable Path A shipped-state ledger/verifier, including its historical
  record of `NanidroidService` and notification identity.

## Implementation plan

### 1. Establish red product-boundary tests

Modify `src/androidTest/java/com/cattailsw/nanidroid/compose/NanidroidComposeShellTest.kt`:

- open the overflow and assert Readme and Archive Queue remain;
- assert the `update` node and localized update label are absent;
- keep the existing loading/menu-dismiss behavior using a retained menu item.

Add an ActivityScenario lifecycle assertion to
`NanidroidLifecycleInstrumentationTest.kt` that moves a resumed Activity to a
paused/stopped state and proves the existing runner stops its clock without
replacing the runner or ghost, changing the native session generation, or
redispatching boot. This is a wiring test for the retained `onPause` behavior,
not a new production test API.

Add a second ActivityScenario assertion for an accepted `NarUserEnqueueResult`.
Centralize the three existing `acceptedActive` call sites behind one small
Activity helper, invoke that boundary while the Activity is STARTED, and prove
the notification request is pending; then resume and prove the pending request
is consumed/launched. This preserves direct URL, picker, and incoming-content
archive permission wiring without retaining the deleted service handoff.
Make this an explicit API 33+ clean-install gate with `POST_NOTIFICATIONS`
initially denied, use an assumption to skip incompatible/pre-granted local
devices, and retain the existing permission-controller dismissal/cleanup
strategy. CI must run it on a fresh permission-denied emulator and confirm it
executed rather than skipped. Do not add production permission-state injection
for the test.

Replace the useful parts of the deleted API/security source contracts with
behavior and generated-artifact checks:

- extend lifecycle coverage so invalid initial and warm `file`/HTTP intents are
  rejected, while `onNewIntent` still updates the Activity intent;
- retain the executable `ArchiveIntentAdapterTest` matrix for action, content
  scheme, MIME, and read-grant validation;
- after assemble/lint, inspect generated lint metadata for compile target 37
  and the packaged manifest for min/target 31/37, exported `singleTop`
  Activity, the exact content-only NAR/ZIP filters, and absence of broad storage,
  cleartext deep links, the removed service, and both FGS permissions;
- add a focused `NetworkUtil` behavior test that accepts only HTTPS with a
  nonblank host while that backend remains live through PR A;
- verify the dependency/source outputs contain no Apache HTTP bridge or legacy
  compatibility directory.

Run the focused tests before production edits and record the expected failures.

### 2. Remove the Compose/Activity update entrypoint

Modify:

- `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidComposeShell.kt`
- `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/GhostActionGuard.kt`

Remove the `onUpdate` API and menu item, `Nanidroid.onUpdate()`, service
start/stop helpers and calls, and `GuardedAction.UPDATE`. Preserve
`runner?.stopClock()` in `onPause`, archive executor shutdown in `onDestroy`,
direct archive notification-permission requests, all remaining toolbar actions,
and external-link boundaries.

Mechanically remove the obsolete `onUpdate` argument from:

- `NanidroidComposeShellTest.kt`;
- `NanidroidComposeShellUiAutomatorTest.kt`;
- `RenderedTransformContractTest.kt`;
- `AdaptiveGhostStageScreenshotRenderer.kt`;
- production preview/call sites.

### 3. Delete the service and Bottle implementation

Delete:

- `src/main/kotlin/com/cattailsw/nanidroid/NanidroidService.kt`;
- `src/main/kotlin/com/cattailsw/nanidroid/SSTPBottleSensor.kt`;
- `src/main/kotlin/com/cattailsw/nanidroid/BottleLogSensor.kt`;
- `src/test/java/com/cattailsw/nanidroid/SensorPollingTest.kt`;
- `src/test/java/com/cattailsw/nanidroid/SSTPBottleSensorCharacterizationTest.kt`;
- `tools/test_kotlin_nanidroid_service_contract.py`;
- `tools/test_kotlin_bottle_sensors_contract.py`;
- `tools/test_target36_security_contract.py`;
- `tools/test_api36_compile_contract.py`.

There is no `sstpbottlelog.log` asset to remove. Do not add a fake or disabled
replacement.

### 4. Remove service-only runner and permission handoff code

Modify `SScriptRunner.kt` to remove only:

- `addMsgToQueue(Collection<String>)`;
- `getStringValueFromShiori`.

Delete `durable/DurableNotificationPermissionAcceptance.kt` and remove only its
observer/import from `Nanidroid.kt`. Trim its service-handoff recreation case
from `NanidroidLifecycleInstrumentationTest.kt`. Retain
`pendingDurableNotificationPermission`, the launcher,
`onUserDurableWorkAccepted()`, and archive-triggered permission behavior.
Route every retained `NarUserEnqueueResult` through the single accepted-work
helper covered in step 1; do not add a general event/effect abstraction.

### 5. Remove manifest and resource surface

Modify `src/main/AndroidManifest.xml` to remove `.NanidroidService` and
`FOREGROUND_SERVICE_DATA_SYNC`. Remove the generic `FOREGROUND_SERVICE`
permission with an explicit manifest-merger removal marker because WorkManager
otherwise contributes it transitively. Keep WorkManager's merged
`SystemForegroundService` component and all archive/durable permissions,
components, and intent filters unchanged. No retained worker may call
`setForeground` or construct `ForegroundInfo`.

Remove these strings from `values`, `values-ja`, and `values-zh-rTW`:

- `download_channel_name`;
- `download_in_progress`;
- `update_btn_text`;
- `check_updates_btn_text`.

Do not delete notification drawables.

### 6. Align remaining tests without reducing retained coverage

Remove the service-dispatch-only case from `NanidroidGhostStartupTest.kt`.
Retain all startup, clock, archive, dialogue, surface, and ghost-switch tests.
Do not modify updater repository/worker/recovery tests in this PR.

Delete the four obsolete source-text contracts listed in step 3 rather than
rewriting implementation-string assertions. Preserve the executable
archive-intent and external-link behavior tests; use compilation, lint,
generated lint/manifest inspection, the focused `NetworkUtil` test, and hygiene
searches for SDK, manifest, TLS, service, and Apache guarantees.

### 7. Verify exact head

Required gates:

1. The remaining Python contract suite passes, and all four obsolete source
   contracts listed in step 3 are absent.
2. Focused Compose, archive-intent, accepted-work permission, lifecycle, and
   `NetworkUtil` behavior tests plus the full JVM suite.
3. `compileDebugAndroidTestKotlin` and `compileDebugScreenshotTestKotlin`.
4. `assembleDebug` for arm64-v8a and x86_64.
5. `validateDebugScreenshotTest`; all 31 references must remain byte-identical.
6. UI visual-audit `-DryRun -HostSelfTest`; catalog remains 64 cases.
7. Path A shipped-state verifier passes unchanged.
8. Merged-manifest checks prove no `NanidroidService`, generic
   `FOREGROUND_SERVICE`, or data-sync FGS type, while archive
   receivers/permissions and WorkManager's component remain. Generated
   artifact checks also prove compile target 37, min/target 31/37, exported
   `singleTop`, exact content-only NAR/ZIP filters, and no broad storage or
   cleartext deep-link surface.
9. Hygiene finds no production Update menu/service/Bottle/start-stop symbols;
   historical audit evidence and retained SHIORI/native protocol names are
   allowlisted.
10. `lint`; only the existing five `archive_queue_overflow_*`
    `MissingTranslation` failures are accepted.
11. `git diff --check` and a clean worktree.

Connected toolbar execution is desirable when a device is available; lack of a
device must be reported and must not be misrepresented as a pass.

## Review and delivery

- Request three exact-diff reviews: Android/service/security, runtime/phase
  containment, and tests/UI/simplification.
- Address every validated P0–P2 finding and rerun affected reviews.
- Open a draft PR linked to #382, request GitHub Codex review, and wait for CI.
- Merge only the unchanged reviewed SHA with zero unresolved threads.
- Keep #382 open and begin PR B only after PR A is merged and recorded.

## Stop conditions

Stop if this PR requires changes to updater repository/workers/recovery,
`NetworkUtil`, archive ingress/download or unrelated durable code,
WorkManager/Hilt, JNI, normal session ownership, `INTERNET`, archive receivers,
or screenshot pixels. Stop if any retained Worker uses foreground APIs.
Stop if pause no longer stops the runner clock, archive notification permission
cannot be requested, outgoing links disappear, or a remaining production caller
still reaches the deleted service/Bottle API. Stop if deleting a source-text
contract leaves its retained behavior or generated-artifact guarantee unproved.
