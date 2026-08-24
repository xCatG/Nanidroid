# Foreground NAR Import Design

## Goal

Replace every user-reachable archive queue, remote-download, and external-intent
entry point with one deliberately small foreground `OpenDocument` flow. A
selected NAR continues through the retained bounded-copy and transactional
installer pipeline, survives Activity recreation while the application process
lives, and makes no process-death continuation promise.

This is the next focused slice of canonical phase `#384`, based on verified
default-branch commit `fc394aac49cdd2466c408f523c246a3084411d92`.

## Product Contract

- The only user archive ingress is `ActivityResultContracts.OpenDocument`.
- The picker accepts a one-shot `content://` URI and does not persist its grant.
- Copy and install run on application-owned bounded IO, not an Activity scope.
- Rotation and Activity recreation reattach to the same in-process attempt.
- Process death abandons the attempt. The next process reconciles exact
  app-owned staging and asks the user to select the archive again when needed.
- One picker result creates at most one import. A second import cannot overlap
  the first.
- Installation remains fresh-install-only. An existing target returns
  `TargetExists` without modifying either tree.
- Success refreshes ghost discovery but does not switch the active ghost.
- Failure and recovery state remains replayable until an operation-token-aware
  action acknowledges it, retries cleanup, or starts a new selection.
- There is no URL entry, incoming NAR `ACTION_VIEW`, archive queue, history,
  notification, automatic retry, replacement flow, or background continuation.

The packaged first-ghost bootstrap is a trusted internal asset installation and
is not external archive ingress. It remains unchanged.

## Compatibility Decision

Phase `#382` selected Path A: no state-capable Nanidroid APK was released or
distributed. This slice therefore targets clean installs and current source
state. It does not preserve or drain developer-device DownloadManager rows,
WorkManager rows, URI grants, notifications, or queue records from unreleased
builds.

The old repository, workers, stores, and dependency graph may remain compiled
for one follow-up deletion pull request, but no production entry point may
start, schedule, recover, observe, or present them after this cutover.

## Rejected Alternatives

### Keep remote acquisition beside the picker

This preserves the queue, receivers, WorkManager ownership, notification
contract, URL validation, and durable-state surface that phase `#384` exists to
remove. It does not reach the approved product contract.

### Delete the entire durable backend in the cutover pull request

This combines lifecycle replacement, UI change, manifest retirement, installer
hardening, thousands of lines of backend deletion, build-plugin removal, and
test deletion. The resulting review surface is too broad to prove the authority
handoff independently. The cutover must first make the backend unreachable;
the next focused pull request can then delete it mechanically.

### Keep import ownership in the Activity or an Activity-scoped executor

That loses work or result delivery across Activity recreation. Ownership must
be process/application-lived even though it is intentionally not durable.

## Authority and Components

### Application-lived import coordinator

`ForegroundNarImportCoordinator` is a process singleton keyed by application
context. `CatTailApplication` initializes it during application startup, and
the Activity obtains the same instance without casting `application` to
`CatTailApplication`. This keeps connected tests compatible with
`HiltTestApplication` and avoids introducing new Hilt ownership immediately
before Hilt deletion.

The coordinator owns:

- a `SupervisorJob` plus IO dispatcher;
- one immutable, replayable `StateFlow<ForegroundNarImportState>`;
- tokens containing a random process nonce plus a monotonically increasing
  in-process sequence;
- the single-flight compare-and-set transition;
- exact import and installer-staging recovery;
- selected-document bounded copy;
- transactional installation;
- cleanup outcome classification; and
- terminal acknowledgement and cleanup retry.

It stores only the application context. It never captures an Activity,
Activity `ContentResolver`, launcher, dialog callback, or Activity lifecycle
scope. The input opener is constructed from the application context and the
returned URI.

### Activity

`Nanidroid` registers `ActivityResultContracts.OpenDocument` unconditionally as
a stable field. Its result callback:

1. rechecks `GuardedAction.IMPORT_INSTALL`, because runtime mode may have
   changed while the platform picker was open;
2. asks the coordinator to consume the picker state with the Activity's saved
   registry-owner token, nullable URI, and guard result;
3. returns that token to `Idle` for cancellation or a rejected guard; and
4. advances a non-null allowed URI to `Copying` only when an armed state existed.

Before calling `launch`, the Activity atomically arms a coordinator-owned
`AwaitingSelection(token)` state. The Activity saves only that picker-owner
token in its instance-state `Bundle`; it does not save or reconstruct import
progress, terminal state, or import dialogs. The token is routing proof for the
`ActivityResultRegistry` owner, not a second source of truth.

`ActivityResultRegistry` owns platform request delivery; `StateFlow` owns the
process identity required to accept that delivery. Same-process recreation
restores the owner token, confirms it still matches `AwaitingSelection`,
registers the launcher, and reconnects observers without relaunching. A new
process has no armed coordinator state, so a registry-restored result from a
picker launched by the dead process is rejected and cannot silently resume the
import contract.

If another Activity starts in the surviving process without a restored owner
token, it must not abandon an `AwaitingSelection` that may still belong to a
live ActivityResultRegistry owner in another task or window. A mismatched
restored owner token is rejected without mutating the current attempt. The
owning Activity abandons only its own token when it is finally destroyed;
configuration destruction preserves the token for registry restoration.

The Activity presents coordinator state and owns the Activity-local `GhostMgr`
refresh. A success presentation must wait for the replacement Activity's
asynchronously created `GhostMgr` to become ready, recheck the success attempt
token, refresh exactly that result, and then expose the installed ghost in the
list. A nullable one-shot `gm?.refreshGhost()` is insufficient because it can
lose a success that races manager construction.
The Activity retains the last refreshed installation token and skips refresh
when cleanup retry returns the same installed primary through
`RecoveryRequired`/`Cleaning` to `Installed`.

### Existing installer primitives

The flow retains and reuses:

- `NarContentUriImport` for scheme enforcement and bounded private copy;
- `NarStagedSource` for the installer-owned staged source;
- `NarInstallPlanValidator` and `NarVerifiedInstallSession` for archive and
  plan validation; and
- `NarTransactionalInstaller` for serialized extraction and atomic
  publication.

The cutover must not create a second archive parser, extraction implementation,
or publication path.

## State Machine

Every picker/import state other than `Idle` and startup-only `Recovering`
contains an `attemptToken` made from a random process nonce plus a monotonically
increasing in-process sequence. A token restored from a dead process can never
equal a new-process token even if both sequences start at one. UI actions carry
the token they observed, and coordinator mutations compare it with the current
token. A stale Activity or dialog callback is therefore a no-op rather than
authority over a newer attempt.

```text
Recovering --nothing found--> Idle
Recovering --owned residue cleaned--> Interrupted --ack--> Idle
Recovering --cleanup failed--> RecoveryRequired(primary = Interrupted)

Idle --arm picker--> AwaitingSelection --cancel/reject--> Idle
AwaitingSelection --accepted content URI--> Copying --> Installing
Copying/Installing --pre-publication failure + clean--> Failed
Installing --publish + clean--> Installed
Any cleanup failure --> RecoveryRequired(primary = Interrupted/Failed/Installed)
RecoveryRequired --retry--> Cleaning --success--> recorded primary terminal
Cleaning --failure--> RecoveryRequired(same token and primary)
```

The concrete states are:

- `Recovering`: startup reconciliation owns both exact staging roots; no import
  may start.
- `Idle`: the only state from which picker launch can allocate a new token.
- `AwaitingSelection`: an in-process picker request is armed for that token.
  Only its one matching callback can consume the state; cancellation returns to
  `Idle`. A newly created Activity with no matching restored registry-owner
  token abandons it to `Idle`.
- `Copying`: the selected URI is being copied into the dedicated import root.
- `Installing`: the retained installer is validating, extracting, and
  publishing.
- `Installed`: publication succeeded, immediate cleanup completed, and the
  state contains `installedPath`, `targetId`, and token.
- `Failed`: publication did not occur and immediate cleanup completed; the
  state contains the typed install failure, user-safe message, and token.
- `Interrupted`: startup found and successfully removed verified staging from
  a dead process. It contains a fresh recovery token and the approved
  interruption/reselection notice until acknowledged.
- `RecoveryRequired`: startup or post-attempt cleanup could not remove one or
  more verified owned artifacts. It wraps the authoritative primary outcome,
  including `Interrupted` for startup recovery or installed path and target ID
  when publication succeeded, so retry can never republish.
- `Cleaning`: a token-aware retry is reconciling owned staging on IO. It keeps
  the same primary outcome and blocks duplicate retry or new import actions.

The coordinator uses one compare-and-set state machine rather than a separate
busy Boolean. `armPicker`,
`consumePickerResult(expectedToken, uri, importAllowed)`,
`abandonPicker(expectedToken)`, `failPickerLaunch(expectedToken, message)`,
`acknowledge(expectedToken)`, and
`retryCleanup(expectedToken)` succeed only from their exact allowed state and
token. The result callback passes its restored registry-owner token into that
single atomic comparison; an Activity-side precheck is not sufficient.
A double tap, stale terminal dialog, new-process registry replay, or overlapping
submission cannot create a second copy or publication. The design relies on
`ActivityResultRegistry`'s single result delivery for the one armed launcher;
it does not invent a second picker queue.

If platform launch throws after `armPicker` succeeds, the Activity sends that
exact returned token through `failPickerLaunch`, atomically producing a
replayable `Failed(SourceUnavailable)` state without an Activity-owned dialog.

An ordinary failed import is not automatically retried. The user acknowledges
it and selects a document again. `Retry cleanup` performs cleanup only. It does
not reopen a URI, recopy a document, or call the installer.

## Import and Publication Flow

1. The Activity atomically changes `Idle` to `AwaitingSelection(token)`, then
   launches `OpenDocument` with `*/*`. NAR MIME assignment is not reliable
   across document providers; explicit user selection plus retained content
   validation is the authoritative archive gate.
2. The registry returns a nullable URI callback to the registered Activity
   instance. A callback without the matching process-only armed token is
   ignored silently; it cannot open the URI, mutate coordinator state, or
   create an Activity-owned import dialog.
3. The Activity passes its registry-owner token, return-time guard result, and
   URI into one atomic consumption operation; an allowed non-null result
   changes that same token to `Copying`, while cancellation or guard rejection
   changes it to `Idle`. A mismatched token performs no transition and never
   opens the URI.
4. `NarContentUriImport` rejects non-`content` schemes and copies at most its
   characterized maximum into
   `noBackupFilesDir/nar-import-v1/nar-import-<random>.zip`.
   If `getExternalFilesDir(null)` is unavailable, the backend returns typed
   storage/recovery failure before opening the URI and never constructs a
   relative ghost path from a null parent.
5. The coordinator changes the same token to `Installing` and invokes the
   retained transactional installer.
6. The installer serializes validation, extraction, cleanup, and publication
   under its install lock.
7. The candidate is proven discoverable as a Nanidroid ghost before rename.
8. Under the install lock, the target ID is compared with every installed
   sibling using the same case-insensitive identity semantics as `GhostMgr`.
   An exact or case-variant conflict returns `TargetExists`.
9. The absent logical target is atomically published. Existing targets remain
   untouched.
10. Both staging obligations are reconciled and classified without changing a
   successful publication into a false installation failure.
11. The coordinator publishes replayable `Installed`, `Failed`, or
    `RecoveryRequired` for the same token.

Backend calls have an `Exception` boundary at the coordinator so startup,
copy/install, or cleanup exceptions cannot strand a running state. The
transactional installer separately guarantees that no exception or false
failure escapes after rename is known successful: a rename that throws after
moving is recognized from the now-present target/absent candidate, and all
post-publication progress and cleanup operations are no-throw. A thrown import
can therefore be classified as pre-publication `Failed`; typed or thrown
cleanup failure retains its authoritative primary outcome in
`RecoveryRequired`.
The outer `NarContentUriImport` source-stage `finally` is also no-throw; failed
or exceptional deletion leaves an exact owned import artifact for immediate
classified recovery without replacing an `Installed` primary.

### Pre-publication discoverability

The current low-level transaction accepts archives that satisfy `install.txt`
validation but omit or corrupt metadata needed by `DirList`/`InfoOnlyGhost`,
notably `ghost/master/descript.txt`. `GhostMgr` can consequently publish a tree
and then report `Failed(InvalidArchive)` because discovery cannot see it. A
retry then returns `TargetExists`.

The cutover closes this false-failure window. Minimum ghost structure and
metadata parsing needed by production discovery must be validated against the
private candidate before atomic rename. A candidate that cannot become a
discoverable ghost returns `InvalidArchive` and is removed without creating the
target. Once rename succeeds, publication is authoritative and the import
result remains `Installed`; catalog refresh and cleanup are separate recovery
concerns and may not invite installation retry.

Fresh-install identity uses `GhostMgr` semantics, not only filesystem
existence. Android app storage is case-sensitive, while ghost lookup is
case-insensitive; publishing `foo` beside `Foo` would create ambiguous
discovery. The installer therefore enumerates top-level target siblings under
the install lock and rejects any name equal to the proposed target ID with
`ignoreCase = true`, immediately before publication. The conflict path does not
modify either tree.

## Exact Recovery and Cleanup Ownership

There are two staging domains:

1. selected-document copies under
   `noBackupFilesDir/nar-import-v1`; and
2. installer transactions under
   `externalFilesDir/ghost/.nanidroid-install-staging`.

Startup reconciliation covers both before exposing `Idle`. Import cleanup alone
is insufficient because process death during validation or extraction can
strand the second domain.

Cleanup rules are closed and non-recursive outside the two owned roots:

- Resolve each configured root and expected parent canonically.
- The import root deletes only top-level regular files whose names match the
  exact coordinator-generated `nar-import-<24 lowercase hex>.zip` form.
- It does not follow symlinks, delete unmatched files, or recursively delete a
  computed parent.
- Installer reconciliation is a `NarTransactionalInstaller` operation under
  the same install lock used for publication.
- It deletes only top-level directories matching the installer-generated
  `candidate-<32 lowercase hex>` form beneath the exact canonical
  `.nanidroid-install-staging` root. It does not follow symlinks, delete an
  unmatched entry, or touch a published sibling target.
- Failure to enumerate, verify, or delete an owned artifact becomes replayable
  recovery state rather than silent success.

If process death occurs before publication, startup removes both kinds of
partial staging and no live ghost is visible. If death occurs after atomic
publication but before source/transaction cleanup, startup removes staging and
preserves every published target. Because the prior in-memory token is gone,
the user-facing notice is deliberately accurate:

> Previous import was interrupted. Installed ghosts are preserved; if the new
> ghost is not listed, select the archive again.

If cleanup fails after a known in-process publication, state retains the
published `targetId` and retry performs cleanup only. It must never call install
again and turn success into `TargetExists`.

## Reachability Cutover

The pull request removes every production root that can enter or awaken the old
archive backend:

- NAR/ZIP `ACTION_VIEW` manifest filter;
- `ArchiveIntentAdapter`, `ArchiveIntentState`, cold-intent handling, saved
  intent state, and `onNewIntent` archive dispatch;
- URL-entry dialog, HTTPS archive validation, and remote enqueue calls;
- archive queue observation, overflow row/badge, dialogs, retry/reselect/delete
  callbacks, and download-state presentation;
- Activity `NarDownloadRepository`, persisted-grant, `NarLiveGrantHandoff`, and
  notification-permission wiring;
- `NarDownloadReceiver`, `NarDownloadRecoveryReceiver`, and
  `DurableOperationAttentionReceiver` manifest entries;
- `SharedDurableOperationSupervisor` application-startup initialization; and
- Nanidroid's own boot, notification, internet, and network-state permission
  declarations. The merged APK continues to contain WorkManager-contributed
  network-state, boot, and wake-lock permissions plus dependency receivers
  until the later dependency-deletion PR; these are not archive ingress roots.

The application retains the WorkManager initializer suppression,
`Configuration.Provider`, worker factory, Hilt annotations, durable/repository
classes, workers, stores, tests, and dependencies only until the next focused
deletion pull request. Merely implementing `Configuration.Provider` does not
schedule work; after the roots above disappear, no production caller initializes
or enqueues the legacy archive workflow.

Generated and source-contract tests must assert the cutover rather than retain
requirements for deleted behavior. The merged manifest, not only the source
manifest, is authoritative.

## UI Contract

The More Ghost surface contains one action labeled **Install from document**.
It has no URL or queue affordance, and the old **Install from SD card** wording
is retired because `OpenDocument` can expose local and provider-backed files.

- `Copying` and `Installing` show non-duplicating progress for the current
  token. The UI does not claim background continuation.
- `Cleaning` shows foreground cleanup progress and exposes no second retry.
- `Installed` waits for manager readiness, refreshes the list, and shows a
  replayable success notice. It does not switch ghosts automatically.
- `Failed` shows the typed user-safe failure with Dismiss and Select another.
- `RecoveryRequired` explains the cleanup problem and offers only Retry cleanup.
  It cannot be dismissed while owned residue remains. A published success
  remains identified.
- `Interrupted` uses the exact process-recovery wording above and remains until
  acknowledged.

Import terminal presentation is derived from coordinator state. It is not
duplicated into the Activity's `Bundle`-restored `NanidroidSimpleDialog` state.

## Testing Strategy

Implementation follows test-driven development at each boundary.

### Coordinator unit tests

- startup remains `Recovering` until both staging roots are reconciled;
- clean startup with no residue becomes `Idle`, while successfully removed
  residue becomes replayable `Interrupted(token)`;
- picker launch arms one process-only token; same-process recreation retains it
  and a fresh coordinator rejects a restored result from a dead process;
- a concurrent Activity without restored registry ownership cannot abandon a
  live in-process `AwaitingSelection`, while a matching recreated owner retains
  it and final owner destruction releases it;
- one valid URI produces the exact `Copying` → `Installing` → `Installed`
  token sequence;
- duplicate and concurrent submissions create one copy and one install;
- non-content, unavailable, revoked, unreadable, oversized, invalid,
  conflicting-target, staging, extraction, and publication failures are typed
  and replayable;
- observer detach/reattach receives the same running or terminal state;
- thrown backend import/recovery calls cannot strand `Recovering`, `Copying`,
  `Installing`, or `Cleaning`;
- acknowledgement and cleanup retry require the matching token;
- a callback for picker token A cannot consume, open, clear, or otherwise
  mutate `AwaitingSelection(B)`;
- stale callbacks cannot clear, retry, or replace a later attempt;
- retry cleanup never reopens the URI or reruns publication;
- failed cleanup is visible and a later successful cleanup resolves it;
- exact-root cleanup rejects mismatched names, descendants outside the
  canonical root, symlinks, and unowned siblings; and
- cancellation closes streams and leaves only a classified cleanup obligation.

### Installer tests

- a candidate lacking required discovery metadata is rejected before rename;
- malformed discovery metadata is rejected before rename;
- exact and case-variant target IDs are rejected under the install lock, with
  the first tree remaining byte-identical;
- publication remains `Installed` even if later catalog refresh or cleanup
  fails;
- rename that moves and then throws, post-rename progress failure, and cleanup
  failure still return `Installed` and leave only a cleanup obligation;
- source-stage deletion that throws after installer success preserves
  `Installed` and becomes installed-primary cleanup recovery;
- process-death fixtures in both staging roots reconcile under the install lock;
- cleanup never touches a published target or unowned path;
- existing-target rejection preserves the existing tree byte-for-byte; and
- retained malicious, ZIP boundary, CRC, entry-count, size, containment,
  descriptor, plan, extraction, cancellation, and atomic-publication suites
  remain green.

### Activity and Compose tests

- the launcher emits `ACTION_OPEN_DOCUMENT`, `CATEGORY_OPENABLE`, and `*/*`,
  and is registered regardless of saved state;
- picker cancellation clears the armed token and performs no import;
- same-process registry delivery consumes the armed picker token once, while
  new-process registry replay is rejected without opening the returned URI;
- closing the task while the picker owns the registry and relaunching in the
  same process abandons the orphaned token and permits a new selection;
- the guard is checked before launch and again when the URI returns;
- recreation does not relaunch the picker or duplicate import work;
- publication while the replacement `GhostMgr` is still initializing becomes
  discoverable after its readiness barrier;
- installed-primary cleanup retry does not refresh the same publication twice;
- a running or terminal token replays to the replacement Activity exactly once;
- More Ghost exposes only the picker action;
- URL entry, queue row/badge/dialog, download actions, notification prompt, and
  archive-intent behavior are absent; and
- success refreshes the list without switching the active ghost.

### Static and removal tests

- the merged manifest has no NAR `ACTION_VIEW`, no Nanidroid archive/durable
  receiver, and no `INTERNET` or `POST_NOTIFICATIONS`; dependency-contributed
  WorkManager permissions/receivers are explicitly inventoried for the later
  deletion PR;
- production search has exactly one external archive ingress launcher and no
  Activity reference to the repository, live-grant handoff, queue, or URL
  enqueue;
- source-contract scripts replace positive assertions for deleted behavior
  with closed absence assertions and an allowlist for the packaged first-ghost
  bootstrap; and
- obsolete intent, queue, dialog, lifecycle, and screenshot expectations are
  deleted or migrated in the same patch.

### Pull-request gates

- focused coordinator, installer, Activity, Compose, and source-contract tests;
- `testDebugUnitTest` and JaCoCo report generation;
- `assembleDebug` and both-ABI APK inventory;
- `lint` compared with the exact known baseline;
- `validateDebugScreenshotTest`, with every changed golden inspected at original
  resolution;
- `connectedDebugAndroidTest` on an API 31–37 device/emulator, including one
  real picker journey;
- fixed 23-NAR corpus: 23/23 archives and all sentinel assertions;
- coordinator full-diff and repository-guideline review;
- independent Android lifecycle/installer area review and adversarial review;
- GitHub CI and exact-head automatic review; and
- inspection of aggregate reviews and every unresolved inline thread before
  merge.

ARM64 native packaging and ELF inventory remain required. A physical ARM64
runtime test remains deferred under the user's approved risk posture until a
device or real-user report makes it necessary.

## Focused Pull-request Sequence

1. **Foreground ingress cutover:** implement this design, harden recovery and
   pre-publication discoverability, remove every live legacy root and UI path,
   and leave the unreachable backend compiled.
2. **Backend deletion:** delete the now-unreachable queue, repository, workers,
   receivers, stores, durable-operation machinery, notifications, and their
   tests/resources.
3. **Build/dependency deletion:** remove WorkManager, Hilt, AndroidX Hilt, KSP,
   custom worker configuration, initializer suppression, and obsolete manifest
   and Gradle surface. Combine this with step 2 only if the approved
   implementation plan proves the diff remains independently reviewable.
4. **Phase exit:** verify issue `#384` from its baseline through final default
   branch, update the canonical issue, and produce the phase-boundary report
   before starting `#385`.

## Stop Conditions

Stop and revise the design or split the pull request if:

- rotation or registry replay can create two imports;
- the coordinator must capture an Activity to open or finish a URI;
- cleanup ownership cannot be proven for both staging roots;
- startup cleanup can race publication or touch an unowned path;
- any path can report retryable failure after atomic publication;
- discovery cannot be validated before publication without weakening retained
  archive validation;
- a manifest, application, receiver, notification, or UI root still awakens the
  old archive backend;
- a current acceptance criterion requires the later deletion pull request to
  restore correctness; or
- the foreground-only flow fails the fixed 23-NAR corpus.

## Acceptance Criteria

1. One picker selection creates at most one application-lived import.
2. Activity recreation neither cancels nor duplicates copy, validation,
   extraction, publication, or terminal presentation.
3. A valid NAR installs, becomes discoverable, and does not switch the active
   ghost.
4. Exact and case-variant existing target IDs, plus every rejected archive,
   leave published trees unchanged.
5. Process death before publication leaves no live partial ghost; startup
   reconciles only verified app-owned staging in both domains.
6. Process death after publication preserves a discoverable installed ghost and
   cleanup never republishes it.
7. Cleanup failure is replayable, token-safe, and retrying it performs cleanup
   only.
8. The merged application has one external archive ingress and no live queue,
   URL, `ACTION_VIEW`, receiver, notification, or durable-startup root.
9. The retained transactional security suite and fixed 23-NAR corpus pass.
10. Local coordinator review, two independent agent reviews, GitHub CI, and
    GitHub exact-head automatic review have no unresolved actionable finding.
