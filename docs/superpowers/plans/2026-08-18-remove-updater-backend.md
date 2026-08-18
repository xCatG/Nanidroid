# Remove the Ghost Updater Backend

> Issue: #382. This is PR B immediately following merged PR #392. It completes
> the updater/SSTP deletion slice without starting #384.

## Goal

Delete the now acquisition-unreachable ghost self-update implementation,
startup recovery, network client, terminal-event delivery, and update-only
runtime mutation/reload machinery. Preserve the ordinary process-global SHIORI
session owner, ghost startup and switching, dialogue/surfaces/interaction, local
and remote NAR acquisition, WorkManager durability, and every #384 boundary.

Path A is authoritative: the owner confirmed that no state-capable APK was
released or distributed. Do not add a migration, cleanup worker, tombstone
worker, transaction reader, compatibility flavor, or fake updater.

## Durable schema decision

Remove all updater semantics now:

- `OperationKind.GHOST_UPDATE`;
- `GhostUpdateTerminalEvent`;
- `DurableOperationRecord.pendingGhostUpdateEvent`;
- terminal-event supervisor behavior;
- update attention labels/phases and updater fixtures.

Keep the existing v6 physical store column count, but require the former
terminal-event column to be the reserved `-` sentinel when encoding or decoding.
Delete the divergent v3 terminal-payload decoding branch: a numeric v3 retry
generation remains valid, while a v3 terminal payload is invalid. Do not create
a v7 migration or redesign the archive store immediately before #384 deletes it.
Under Path A, a row containing `GHOST_UPDATE`, a v3 terminal payload, or a
non-sentinel v6 terminal column follows the existing fail-closed quarantine path.

## Fixed boundary

Delete whole production files:

- `durable/GhostUpdateJournal.kt`;
- `durable/GhostUpdateRepository.kt`;
- `durable/GhostUpdateWorker.kt`, including the recovery Worker and daemon
  terminal-delivery executor;
- `util/NetworkUtil.kt`.

Delete updater-only runtime seams:

- `GhostMgr` recovery, blocked-root, scheduling, journal, and update-lock
  integration;
- `Ghost.reloadAfterGhostUpdate` and reload-failure deactivation;
- `GhostSessionCoordinator.withMutation`, `Phase.MUTATING`, and updater
  reload/deactivate helpers;
- `SScriptRunner` terminal replay, update SHIORI dispatch overloads, update
  quiesce/commit, surface rebind, lifecycle invalidation, and production
  mutation bridge;
- the Activity's update surface-rebind observer;
- updater terminal APIs in `DurableOperationSupervisor`;
- updater model/store/attention/resources and live documentation.

Retain unchanged:

- `GhostSessionCoordinator` construction reservation, exact canonical
  root/ghost identity, begin/bind/attach/reuse/transition/abandon,
  unload-before-release, process-global owner/poison, generation fencing,
  root/global gates, `markActiveUnloaded`, and `withGhostGate`;
- normal SHIORI load/request/unload, boot/first-boot/ghost-changed, timers,
  playback, passive mode, dialogue, input, surfaces, collision, and switching;
- incoming content-only NAR ACTION_VIEW, picker import, HTTPS remote NAR entry,
  DownloadManager, archive queue/history, local staging/install, WorkManager,
  Hilt/KSP, durable attention, and notification permission;
- `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, and
  `POST_NOTIFICATIONS`; generic/data-sync foreground-service permissions remain
  absent;
- WorkManager initializer/application configuration, archive receivers/workers,
  NARFS/retained-overlay/transactional installer, build dependencies, manifest
  components, JNI/CMake/native engines, and JNI-visible names;
- outgoing ghost-authored/readme external links;
- SHIORI `X-SSTP-PassThru-*`, native SSTP/DSSTP names, and
  `NarRetainedOverlayPolicy.Error.INCOMPATIBLE_GHOST_UPDATE` as explicitly
  allowlisted protocol/installer concepts;
- the immutable Path A ledger, verifier, and dated historical plans/audits.

## Implementation plan

### 1. Establish ordinary-runtime and deletion contracts

Before production edits, identify the existing focused tests that prove:

- construction always goes through `beginGhostConstruction` and an exact
  reservation;
- outgoing native unload completes before incoming open;
- failed or uncertain unload poisons ownership and prevents a second owner;
- an ordinary ghost switch changes generation exactly once and dispatches boot
  and clock start exactly once;
- pause/resume, dialogue/input, surface interaction, collision, and archive
  acceptance remain independent of update code.

Add only the missing red assertion for an ordinary cross-ghost switch through
the production coordinator/runner seam. Do not create a second runtime or a
test-only production injection API.

Before any production edit, pin exact fixture hashes and capture the complete
Satori -> YAYA -> Kawari -> Satori production-path sequence on base
`15aae15ac13f8a47281bd18bc2319dc869ea789b` for both ABIs. Record every success,
structured incompatibility, and already accepted verified Kawari crash outcome.
Candidate acceptance is exact base equality/no regression plus the ownership
assertions; it does not require turning an accepted base crash into success. If
no base fixture can execute the sequence far enough to prove ownership/unload
ordering, stop before production edits.

Extend generated/hygiene verification to require updater classes and executable
symbols to be absent while archive workers, receivers, WorkManager, Hilt,
permissions, and content-only archive ingress remain.

### 2. Delete the updater implementation and dedicated tests

Delete the four production files listed above and these dedicated tests:

- `src/test/.../durable/GhostUpdateRepositoryTest.kt`;
- `src/androidTest/.../durable/GhostUpdateRecoveryTest.kt`;
- `src/test/.../util/NetworkUtilTest.kt`.

Do not add recovery or network replacements. Do not delete or clean local
developer `.nanidroid-update-*` paths; unsupported local state requires app-data
reset and never establishes cleanup ownership.

### 3. Simplify GhostMgr to ordinary discovery and reserved construction

Remove all updater imports, journal recovery, recovery scheduling, blocked-root
filtering, and update-lock bootstrap exceptions. Rename
`loadGhostsAfterRecovery` to ordinary `loadGhosts` and use
`DirList.parseDataDir` directly.

Keep `createGhost` inside the existing `SScriptRunner` construction reservation
flow. It must never directly create a second native owner. Preserve the fresh
installer staging exemption; unknown files or directories remain fail-closed
for bundled-ghost bootstrap.

### 4. Remove only updater mutation authority

In `GhostSessionCoordinator`, delete only `withMutation`, `MUTATING`, and the
private updater reload/deactivate paths. Preserve ordinary locking, reservation,
attach/transition/unload, poison, generation, and `withGhostGate` behavior.

In `Ghost`, delete only the two updater reload/deactivation helpers.

In `SScriptRunner`, remove:

- update terminal delivery from attach/reuse/start-clock paths;
- `withProductionGhostMutation`;
- update-bound `doShioriEventForGhost` overloads;
- update quiesce/commit methods and surface-rebind observer;
- updater invalidation/lifecycle-dispatcher machinery that becomes callerless.

Collapse dead lifecycle-completion branches only after whole-tree proof. Retain
the constructor/context shape when changing it would broaden the #385 runtime
refactor. Preserve all ordinary runner state, queue, boot, timer, dialogue,
interaction, switching, and `withGhostGate` behavior.

Delete only the corresponding surface-rebind observer from `Nanidroid`.

### 5. Remove updater semantics from the retained durable system

Modify `DurableOperation.kt`, `DurableOperationSupervisor.kt`,
`SharedPreferencesDurableOperationStore.kt`, attention notification/coordinator,
shared supervisor, and their tests to remove updater kinds, terminal events,
terminal supervisor APIs, and updater WorkManager membership.

Keep v6 field count and write `-` in the reserved column. Strictly reject a
non-`-` value; do not decode an updater event. Remove the v3 terminal-payload
fork while preserving the numeric v3 retry-generation layout. Preserve generic
CAS, cancellation, stall, retry, quarantine, notification identity, and archive
round-trip behavior.

Add public-store assertions that every retained kind encodes `-` in field 12,
a v6 non-sentinel row is quarantined and atomically resets primary storage, and
a valid v6 retained-kind row round-trips with retry/progress/keep-waiting
generation fields in their original positions. Also prove that a valid retained
kind with a numeric v3 retry generation still decodes, a v3 terminal payload is
quarantined, and any historical `GHOST_UPDATE` row is quarantined.

Replace tests that used `GHOST_UPDATE` merely as a generic WorkManager fixture
with `NAR_INSTALL` or `LOCAL_NAR`. Delete updater-specific tests; never delete
generic durable coverage just because its fixture used the old enum.

Remove `durable_operation_ghost_update` and `durable_phase_updating` from all
three locales. Rewrite `err_no_ghost_available` so it no longer claims an
interrupted update may be recovering, and rewrite
`durable_store_recovery_message` so it no longer claims update status can be
restored, in English, Japanese, and Traditional Chinese. Preserve the remaining
meaning and cover the live message paths or affected goldens. Update the live
durable transition table to document only the three retained archive operation
kinds and the reserved v6 column. Historical audit/spec/plan documents remain
untouched.

### 6. Surgically prune mixed runtime tests

In `GhostSwitchingCharacterizationTest`, preserve ordinary global ownership,
poison, exact root/ID reservation, abandon/unload, concurrency, and switch
callback ordering; delete only update mutation/reload/rebind cases.

In `SScriptRunnerBootDispatchTest`, preserve boot-once, first activation,
timers, passive mode, input, interaction, and ordinary switching; delete only
terminal-update and update invalidation/reload cases and helpers.

In `SScriptRunnerDialogueObserverTest`, delete only the updater invalidation
case.

In durable/store/attention/Compose tests, substitute retained operation kinds
for generic updater fixtures and keep all cancellation/concurrency/round-trip/
stall/notification assertions.

Update screenshot fixtures and references only where an updater label is
intentionally replaced by a retained archive operation. Keep fixture/audit case
counts stable, regenerate only affected references, inspect every pixel diff,
and require all unrelated references byte-identical.

### 7. Verify the exact committed head

Host gates:

1. Focused JVM suites for ordinary ghost switching, boot dispatch, dialogue,
   GhostMgr startup, durable supervisor/store/attention, archive queue/install;
   then full `testDebugUnitTest` and coverage report.
2. `compileDebugAndroidTestKotlin`, `compileDebugScreenshotTestKotlin`, and
   `assembleDebug` for arm64-v8a and x86_64.
3. `validateDebugScreenshotTest` plus the 64-case visual-audit host self-test;
   case counts stay stable and only intentional updater-label pixels change.
4. `lint`; accept only the established baseline findings, with no new issue.
5. Path A mutation suite and standalone verifier pass unchanged.
6. Generated class/artifact/dependency/manifest checks prove updater and
   `NetworkUtil` classes are absent; archive workers/receivers, WorkManager,
   Hilt, four retained permissions, content-only ingress, and FGS absence remain.
7. Hygiene denies the exact updater surface: `GhostUpdate*`, `GHOST_UPDATE`,
   `pendingGhostUpdateEvent`, `withGhostUpdate*`,
   `setGhostUpdateSurfaceRebindObserver`, `reloadAfterGhostUpdate`,
   `deactivateAfterGhostUpdateReloadFailure`, `SScriptLifecycleDispatcher`, and
   `NetworkUtil`, outside historical documents, the immutable Path A verifier
   and its mutation tests, the one raw serialized `GHOST_UPDATE` corruption
   fixture in `SharedPreferencesDurableOperationStoreTest`, and explicit
   native/installer allowlists. The store exception is serialized test data,
   never an enum/member reference or executable production path. Archive
   recovery, ordinary state mutation, generic durable terminal
   transitions/statuses, and retained installer terminology are explicitly
   allowed. The verifier and ledger remain byte-for-byte unchanged.
8. `git diff --check` and a clean worktree.

Required runtime/device/corpus gates before merge:

9. Connected lifecycle pause/clock/native-session, API 33 permission, archive
   ingress/queue/durable prompt, and ordinary switch tests.
10. Exact deterministic 23-NAR manifest/hash and field-by-field production
    runtime comparison on the supported clean emulator.
11. Repeat the pinned base real-engine production-path sequence Satori -> YAYA
    -> Kawari -> Satori on arm64-v8a and x86_64, proving candidate equality/no
    regression for successes, incompatibilities, and accepted verified crashes,
    plus outgoing close before incoming open, exactly one owner, correct
    generation, and single boot/clock dispatch.
12. Rolling corpus may supplement but never replace the exact 23-NAR gate.

If the required device/corpus infrastructure is unavailable, the branch may be
implemented and reviewed but the PR remains unmerged.

## Review and delivery

- Request exact-plan reviews for runtime/JNI ownership, Android/build/durable
  containment, and adversarial tests/simplification.
- After implementation, request three independent exact-diff reviews plus the
  coordinator review and GitHub Codex review.
- Resolve every validated P0-P2 finding and rerun the affected lane.
- Merge only the unchanged reviewed SHA after CI, device/corpus gates, and zero
  unresolved threads.
- Keep #382 open until this PR is merged. Do not start #384 meanwhile.

## Stop conditions

Stop if:

- ghost construction bypasses reservation or any SHIORI/JNI call bypasses the
  ordinary coordinator/gate;
- a new load can start after failed/uncertain outgoing unload;
- process-global poison, exact root/ID, generation, or unload fencing weakens;
- ordinary boot, timer, pause/resume, dialogue, input, surface, collision,
  interaction, or switching changes;
- retained archive URL/picker/content ingress, DownloadManager, local
  staging/install, WorkManager/Hilt configuration/workers/receivers, permissions,
  authored links, JNI/CMake, or either ABI changes;
- retained `REMOTE_NAR`/`LOCAL_NAR`/`NAR_INSTALL` attention, notification
  identity, cancellation/retry behavior, or external bindings change; deletion
  of updater-only attention/labels/WorkManager membership is explicitly allowed;
- generic durable behavior is lost instead of swapping an updater fixture;
- a v3 terminal payload or non-sentinel v6 terminal column is silently
  accepted, or a numeric retained-kind v3 retry generation stops decoding;
- any 23-NAR classification/field changes or cross-engine sequence fails;
- required device/corpus evidence cannot be executed before merge;
- Path A historical evidence changes.
