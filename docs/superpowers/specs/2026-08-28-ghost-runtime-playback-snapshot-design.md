# GhostRuntime Playback and Snapshot Ownership Design

Date: 2026-08-28

Issue: #385

Depends on: PR #402 (`GhostRuntime` native/session authority)

## Purpose

Finish the remaining authority handoff in #385 without creating another
coordinator. `GhostRuntime` already owns native SHIORI construction, requests,
generation, attachment, unload, and replacement. This slice moves the state
that still makes `SScriptRunner`, `Nanidroid`, and `GhostMgr` competing runtime
owners:

- SakuraScript queue and playback;
- clock and timer eligibility;
- dialogue input, choices, anchors, and pending authored responses;
- switch-playback and exit operations;
- foreground Activity host identity;
- installed-ghost catalog state; and
- presentation/runtime publication.

The result is one application-scoped runtime with one immutable snapshot. The
Activity renders that snapshot and submits commands. It does not reconstruct,
own, or complete runtime operations.

This is a follow-on stacked change to PR #402. It must not target the
pre-cutover `GhostSessionCoordinator` architecture or merge corpus-tooling work
from PR #394/#395.

## Current Split Authority

At PR #402 head:

- `GhostRuntime` owns native/session identity and generation but exposes only
  `GhostRuntimeIdentity`.
- `SScriptRunner` owns the script queue, mutable playback state, delayed
  playback scheduler, timer clock, dialogue inventory, host callbacks,
  switch-playback marker, exit terminal, and three separate snapshot methods.
- `Nanidroid` owns foreground/top-resumed arbitration and joins runner and
  runtime state manually.
- `GhostMgr` owns an Activity-local mutable catalog and rescans it after
  installation.
- `ComposeGhostStageHost` retains a second mutable presentation/dialogue
  projection.

The split requires several generation, admission, host, and operation leases to
keep the same event from being accepted by one owner after another owner has
retired it. Adding another lease is not an acceptable simplification.

## Considered Approaches

### A. Atomic runtime-owned projection and player cutover — selected

Move playback, timers, dialogue inventory, switch/exit playback, host state,
catalog publication, and the normalized snapshot into `GhostRuntime` in one
reviewable branch. Replace `SScriptRunner` with a small thread-confined
`SakuraScriptPlayer` that is an implementation detail of the runtime.

This is a larger diff, but each event has one owner and one generation fence at
the end of the change. It directly advances every remaining ownership item in
#385.

### B. Move clock and host ownership first

This reduces the recent lifecycle complexity, but playback and dialogue would
still own their own admission generation and switch/exit phases. Runtime host
state would need to call back into runner state, preserving two locks and two
terminal authorities. This approach is rejected because its intermediate
architecture is the split authority this work is intended to delete.

### C. Delete unused prototypes only

The obsolete pixel-layout facade, interaction interpreter, and abandoned
presentation interpreter can remove several hundred lines with low runtime
risk. Deleting them is useful cleanup, but by itself it leaves #385's active
authority split unchanged. These files may be removed as part of the selected
cutover when their retained tests have been mapped to production behavior; they
are not a substitute for the ownership change.

## Ownership Model

### GhostRuntime

`GhostRuntime` is the sole mutable owner of:

- active and pending ghost identity, native adapter, and generation;
- installed catalog and selected ghost identity;
- one `SakuraScriptPlayer` instance for the active generation;
- SHIORI request admission and ordered response delivery;
- playback queue and current authored sequence;
- clock-running state, elapsed timer buckets, and timer-response eligibility;
- dialogue content, pending input, choices, anchors, and action generations;
- switch and exit operations through their final terminal;
- registered/resumed/top-resumed host IDs; and
- the current immutable `RuntimeSnapshot` revision.

`GhostRuntime` owns two internal execution lanes, not two authorities:

- a non-blocking serialized coordination lane owns all runtime/player/catalog/
  host/snapshot mutation; and
- the existing FIFO native lane is the only dedicated OS thread and the only
  lane allowed to construct or call a SHIORI adapter.

The coordination lane never waits for JNI, filesystem IO, or another executor.
It captures generation/operation/request identities, submits native work, and
continues accepting lifecycle, Back, switch, catalog, and fencing commands.
Every native data result is posted back as a separate tagged coordination
command; it is never admitted into player/snapshot state inline on the native
lane. Native work may update only native ownership/poison evidence inline.

Blocking catalog/preparation/archive IO runs on bounded IO and returns
immutable, epoch/generation-tagged results through the coordination lane.
Snapshot construction and publication happen only after native/runtime critical
sections are released.

### SakuraScriptPlayer

`SakuraScriptPlayer` is a stateless runtime-private reducer, not a new authority
layer. `GhostRuntime` owns immutable `PlayerState` for the active generation and
atomically replaces that state when a reducer transition is accepted.

It must not own:

- an executor, Android `Handler`, coroutine scope, or clock;
- a SHIORI adapter or request port;
- Activity, Compose, renderer, callback, or `Context` references;
- switch, exit, catalog, host, or native generation state; or
- independent observable state.

Its input is a player command plus immutable current state. Its output is a
single data-only transition containing the next player state, optional authored
SHIORI intent, and optional timer/playback scheduling request. It retains no
transition or mutable state after returning. `GhostRuntime` validates the whole
transition, commits the next state atomically on the coordination lane, and then
executes the returned effects in command order.

### Activity and Compose

Each Activity receives one opaque `RuntimeHostId` and owns a monotonically
increasing `hostEpoch`. Every register, resumed, top-resumed, and unregister
command carries both. The runtime ignores a non-monotonic epoch for that host.
The latest accepted top-resumed=true command defines the sole foreground host;
a matching or newer top-resumed=false/unregister command revokes it.
Activity-local booleans never decide whether playback, timers, or a terminal are
eligible.

Compose collects `RuntimeSnapshot` with lifecycle-aware collection
(`collectAsStateWithLifecycle` or `repeatOnLifecycle(STARTED)`) and renders it.
Commands use the narrow identity that owns their precondition rather than
blindly fencing every action with the global snapshot revision:

- input/choice/anchor commands carry runtime generation plus stable action ID
  and dialogue incarnation;
- pointer commands carry runtime generation plus surface/geometry identity;
- Back and switch carry runtime generation, host ID/epoch, and expected
  operation/mode preconditions; and
- whole-snapshot revision is used only when the command genuinely depends on
  the complete snapshot.

A stale relevant identity is rejected without effects. Unrelated presentation,
clock, progress, or catalog revisions cannot invalidate a still-current user
action.

The Activity may retain purely local chrome/restoration state such as toolbar
visibility, a currently opened Readme document, and partially typed input text.
An input draft is keyed by stable input action identity plus dialogue/runtime
generation and is restored only when the collected snapshot still contains that
exact pending input. No authoritative runtime dialogue, presentation, clock,
switch, exit, or catalog state is saved in its Bundle.

## RuntimeSnapshot

Expose one read-only `StateFlow<RuntimeSnapshot>`. The snapshot is immutable and
contains only data:

- monotonically increasing `revision`;
- active generation and runtime phase;
- active ghost identity and typed immutable catalog state:
  `Loading(epoch, lastProvenEntries)`, `Ready(epoch, entries)`, or
  `Failed(epoch, lastProvenEntries, failure)`;
- pending replacement identity and progress state;
- normalized presentation frame for both speakers plus an ordered immutable
  inventory of unacknowledged, generation-tagged one-shot presentation cues;
- dialogue content and generation-tagged input/choice/anchor actions;
- runtime mode flags needed by action guards (`playingTalk`,
  `pendingUserAction`, `passive`);
- clock foreground/running state;
- switch state plus any active host terminal delivery lease; and
- a data-only notice/progress state for runtime-owned startup/switch failures.

It never contains `Context`, Activity, ViewModel, Compose state, callbacks,
lambdas, mutable collections/builders, native handles/adapters, raw
`Throwable`, `FileDescriptor`, or synchronization primitives. Failures are
published as typed, user-presentable data with a stable operation identity.

Snapshot equality is value-based except cue and operation identities. A new
revision is emitted only after an authoritative mutation. Collectors cannot
call back while a runtime/native lock is held.

Host-directed terminals are acknowledged protocols, not naked replayed
one-shot events.
For exit, runtime state contains one `ExitReady(operationId, generation)` and at
most one `ExitLease(operationId, leaseId, hostId, hostEpoch)`. Only the exact
top-resumed host may perform the exit-delivery block for the exact observed
lease. That non-suspending main-thread block enqueues `ClaimExit`, calls that
Activity instance's `finish()`, and enqueues `AcknowledgeExit` in `finally`.
Submission is application-owned and is not tied to a lifecycle coroutine. The
runtime processes those commands in enqueue order: an accepted claim marks the
lease delivered, and the exact acknowledgement consumes `ExitReady`. Main-loop
lifecycle callbacks caused by `finish()` can enqueue only after that block, so
they cannot revoke the lease between its accepted claim and acknowledgement.

Top-resumed=false, unregister, or supersession may reassign only an unclaimed
lease whose delivery block has not begun. If the old Activity observes a stale
lease after reassignment, its claim and acknowledgement are no-ops and its
`finish()` can affect only that already-stale Activity instance, never the new
host. A claimed lease is never re-leased to a different Activity. Process death
needs no durable acknowledgement because it also destroys the application-owned
runtime. The guarantee is one runtime terminal and at most one accepted delivery
block, not cross-Activity idempotence of `finish()`.

One-shot presentation cues are leased presentation effects, while the normalized
presentation frame is the durable visual result. Each cue has active generation,
presentation-host lease, and a monotonic `cueId`. While a valid top-resumed host
exists, the snapshot carries the ordered immutable list of every unacknowledged
cue for that lease. `StateFlow` conflation can skip snapshot revisions but cannot
skip a cue because the newest snapshot still contains the whole unacknowledged
suffix. Only that host may acknowledge a contiguous cue prefix; duplicate or
stale acknowledgement is a no-op.

The active-host inventory has a fixed capacity of 64 cues. Reaching capacity may
pause player advancement until acknowledgement, but only while that same valid
presentation host exists. Accepted top-resumed=false, unregister, or host
supersession expires that lease's remaining cues, preserves their final visual
result in the normalized frame, and immediately removes cue backpressure. While
no presentation host exists, authored playback keeps advancing and updates the
normalized frame, but transient one-shot cues are intentionally not enqueued or
replayed. A replacement host therefore receives the current frame and only cues
authored after its own lease begins. Distinct equal-valued cues remain distinct
and ordered within one continuous active-host lease; expired cues never restart
on recreation.

## Commands and Ordering

Commands carry the active generation and, where relevant, host/action/snapshot
identity. The runtime rejects stale commands before invoking the player or
native adapter.

Required ordering remains:

1. `OnGhostChanging` request;
2. authored outgoing response playback completion;
3. outgoing native unload;
4. replacement preparation/load;
5. replacement attachment;
6. exactly one attachment event: `OnFirstBoot` for first activation,
   `OnGhostChanged` for a returning switched ghost, otherwise `OnBoot`; and
7. that event's playback publication.

Exit is one runtime operation. The first accepted Back fences and clears
pre-exit work, admits `OnClose`, preserves authored close playback, and emits
one `ExitReady` operation. Repeated Back is single-flight. Host absence retains
the same-generation terminal; generation retirement cancels it. Delivery uses
the lease/claim/ack protocol above.

Extended dialogue primary/fallback requests remain adjacent in the native
queue. Authored surface-response playback remains ordered before the initiating
sequence resumes. Timer responses, ordinary dialogue replies, and local scripts
are admitted only if their captured generation and operation/action revision
remain current.

Native submission never blocks the coordination lane. A request command records
its generation/operation/request/epoch token and submits work to the FIFO native
lane. Completion posts a separate `NativeResponse` command at the coordination
queue tail. Lifecycle, Back, clear, switch, and catalog commands accepted while
JNI is blocked therefore run before that response can be reduced or published.

### Switch terminal table

| Phase/outcome | Required terminal |
|---|---|
| `OnGhostChanging` known request failure before unload | Clear the switch, retain the outgoing attached generation, publish a retryable typed failure. |
| Successful `204 No Content` | Count authored switch playback complete and proceed to outgoing unload. |
| Authored switch parser/player failure | Clear the switch, retain outgoing, clear captured actions/playback, publish a typed failure. |
| Stale or duplicate playback completion | Reject with no state change. |
| Outgoing unload known success | Retire outgoing before any target preparation/load. |
| Outgoing unload failure/uncertainty | Poison runtime, finish switch fatally, never start replacement preparation/load. |
| Target preparation/load failure with native ownership proven empty | Clear busy/pending state, preserve prior last-run preference, publish retryable failure. |
| Replacement failure with failed/uncertain cleanup | Poison runtime and finish switch fatally; no later load. |
| Target load success | Publish one unattached replacement, commit last-run identity, clear switch intent, then attach exactly once. |

Every switch phase has one parent operation ID. Request, playback, unload,
replacement, attachment, and event completions must consume that exact parent
phase once; no child failure may leave the parent busy indefinitely.

### Exit terminal table

| Phase/outcome | Required terminal |
|---|---|
| No active runtime | Publish `ExitReady` immediately. |
| `OnClose` known request failure | Publish `ExitReady` once for the current exit operation. |
| Successful `204 No Content` | Publish `ExitReady` once. |
| Playable `OnClose` response | Hold exit in authored playback until that exact player terminal. |
| Close parser/player failure | Clear captured playback/actions and publish `ExitReady` once. |
| Repeated Back | Join the first exit operation without a second request or terminal. |
| Same-generation host absence/replacement before delivery block | Revoke and re-lease an unclaimed terminal; stale claimant commands cannot affect the replacement host. |
| Same-generation host loss after accepted claim | The non-suspending delivery block has already enqueued acknowledgement before lifecycle loss; consume the exact terminal and never re-lease it. |
| Generation retirement/replacement | Cancel the stale exit operation; it cannot target the new generation. |

Stale/duplicate response, playback, lease, claim, or acknowledgement cannot
create another exit terminal.

### Cross-operation admission matrix

User/pointer `Clear` means a destructive queue/playback clear. Parent-owned
cleanup commands are distinct internal commands carrying the exact switch/exit
operation ID.

| Current parent phase | Back | Switch | User/pointer `Clear` |
|---|---|---|---|
| Idle/no generation | Accept and publish one no-runtime `ExitReady`. | Reject: no active generation. | Reject/no-op. |
| Attached, no parent operation | Accept: atomically clear pre-exit work and create one exit parent. | Accept: atomically clear ordinary work and create one switch parent. | Accept only with current generation/action identity. |
| Switch pre-unload request/playback | Reject effect-free. | Reject effect-free as busy, including duplicate target. | Reject effect-free; only exact parent completion/failure may clear and settle switch playback. |
| Switch post-unload/replacing/attaching | Reject effect-free; native/preparation work is application-owned and non-cancellable. | Reject effect-free as busy. | Reject effect-free. |
| Exit request/playback | Join the existing exit parent without another request, clear, or terminal. | Reject effect-free. | Reject effect-free; only exact exit response/player terminal may clear and settle. |
| Exit ready/unclaimed lease | Join existing exit and allow normal lease assignment; do not create another terminal. | Reject effect-free. | Reject effect-free. |
| Exit claimed, acknowledgement already enqueued | Join existing exit; consume the exact acknowledgement before any later lifecycle command and never re-lease it. | Reject effect-free. | Reject effect-free. |
| Poisoned | Accept one no-native-call `ExitReady`. | Reject fatally. | Reject/no-op. |

Every accepted parent-owned cleanup consumes or advances the exact parent phase
defined in the switch/exit terminal tables. Every rejected cross-operation
command is effect-free: it cannot clear player state, change epochs, submit
native work, publish a new terminal, or alter last-run/catalog state.

## Clock and Scheduling

Replace the Android `Handler` and runner-owned scheduler with one injected
runtime scheduling port. A delayed callback only submits a tagged coordination
command; it never reduces state itself. Production uses a main-safe application
scheduler, while tests use a deterministic virtual scheduler.

The runtime owns:

- whether any valid top-resumed host exists;
- the clock epoch;
- last delivered elapsed second/minute buckets;
- pending timer request identity; and
- playback delay identity.

Foreground loss stops only clock/timer delivery, increments the clock epoch,
and invalidates pending timer responses. Existing authored playback semantics
remain unchanged. Foreground regain does not replay boot, duplicate queued
talk, or admit an older timer response. A blocked timer native request followed
by foreground loss must enqueue its `NativeResponse` after the loss command and
be rejected by the changed epoch.

No delayed callback mutates player/runtime state directly. It submits an
identity-tagged runtime command, which revalidates current state on the command
queue.

## Catalog and Installation Boundary

Move immutable installed catalog state into `GhostRuntime`. The runtime owns a
monotonic `requestedCatalogEpoch`. Every scan captures that epoch, runs on
bounded IO, and returns immutable entries tagged with it. A result publishes
only when its epoch still equals the latest requested epoch. Only the runtime
can publish or select a catalog entry.

Runtime construction starts the initial catalog scan and publishes
`Loading(initialEpoch, emptyList())`. Startup selection, preferred-ghost
resolution, and bundled-first-ghost eligibility wait for `Ready`. A successful
initial scan publishes `Ready`, then resumes the one joined startup decision.
`shouldInstallBundledGhost` may run only from a proven `Ready(..., emptyList())`
plus its existing storage-entry rule. Initial scan failure publishes `Failed`
and a retry action; it is never interpreted as an empty catalog and cannot
trigger bundled installation or a no-ghost terminal.

Foreground NAR import and bundled-first-ghost installation remain installation
operations, not runtime/session owners. At successful filesystem commit the
installer yields only a data-only publication token and target ID. A
`CatalogChanged(token, targetId)` command advances `requestedCatalogEpoch`,
invalidates every older scan, and marks the catalog dirty. A scan started before
commit can never satisfy the change. Later changes coalesce into exactly one
next scan, but the runtime never joins a pre-commit scan as the post-commit
proof.

Installed-ready presentation and switch actions are withheld until a
post-commit scan at the newest epoch contains the committed target. If that scan
fails or omits the target, the already committed installation is reported as a
typed recovery-required state while the last proven catalog remains visible;
retry advances the epoch and scans again. The active generation is preserved
unless an explicit switch is accepted.

Delete Activity-local `GhostMgr` catalog state. Any retained stateless
installation helper moves beside the installer and cannot expose mutable ghost
selection state.

## Presentation and Callback Deletion

`GhostPresentationRenderer`, `SScriptRunner.UICallback`,
`SScriptRunner.StatusCallback`, host binding callbacks, and dialogue observers
are deleted after their effects are represented in `RuntimeSnapshot` and
commands.

`ComposeGhostStageHost` becomes a state adapter for one snapshot rather than a
second mutable runtime. It may retain measurement and scroll memory that are
strictly view-local; it cannot retain authoritative dialogue, action, surface,
or animation-cue state.

Delete unused alternate implementations only after mapping each retained test
to the production tokenizer/player/snapshot contract:

- `runtime/GhostStageLayout.kt`;
- `runtime/SakuraScriptInteractionEffects.kt`;
- `runtime/SakuraScriptPresentationState.kt`;
- `runtime/SakuraScriptPresentationInterpreter.kt`; and
- unused `GhostPresentationRuntimeEffect` machinery.

Do not replace these with compatibility wrappers or parallel reducers.

### Authority-to-deletion accounting

The implementation plan and final PR evidence must keep this table concrete by
replacing each target with exact final symbols and commit IDs:

| Current production authority | Required runtime replacement | Required deletion at cutover |
|---|---|---|
| `SScriptRunner.msgQueue` and `PlaybackState` | runtime-owned immutable `PlayerState` plus player commands | queue/playback fields and `SScriptRunner.run/loopControl` |
| runner playback scheduler | tagged runtime scheduling commands | `SScriptPlaybackScheduler` ownership in production |
| runner `clockHandler`, clock owner, timer buckets | runtime host/clock epoch and timer commands | production `Handler`/clock/timer fields in runner |
| runner dialogue/input/choice state and observers | snapshot dialogue state plus action-identity commands | separate dialogue snapshots/observers and callback setters |
| runner `pendingSwitch` and completion callback | parent switch operation/phase table in runtime | runner switch operation and Activity completion continuation |
| runner exit operation and `StatusCallback` | `ExitReady` plus runtime lease/claim/ack protocol | runner exit fields and host status callback |
| renderer/UI callback host binding | lifecycle host commands and snapshot collection | `GhostPresentationRenderer`, `UICallback`, callback binding/unbinding |
| Activity `hostResumed`/`hostTopResumed` arbitration | runtime `(hostId, hostEpoch)` registry | Activity-local runtime eligibility decisions |
| Activity-local mutable `GhostMgr.ghosts` | versioned runtime catalog snapshot | `GhostMgr` mutable catalog and direct Activity refresh path |
| mutable Compose runtime projection/effects | normalized snapshot plus view-local measurement/scroll state | authoritative presentation/dialogue/cue retention in Compose host |

Final source/absence contracts must prove production has no `SScriptRunner`, its
callback interfaces, Activity renderer binding, mutable `GhostMgr` catalog,
runner-owned `Handler`, or alternate presentation/interaction reducer. Report
production LOC added and deleted separately. If the cutover adds at least as
much production authority code as it deletes, stop and justify every retained
layer before review; line count alone cannot override correctness, but a “lean”
claim requires evidence that compatibility paths were removed.

## Failure Handling

- Known replayable native/request failure publishes a typed operation failure
  and leaves the proven current generation usable when its contract allows.
- Uncertain native ownership poisons the runtime and prevents replacement load.
- Player/parser failure terminates only the captured playback operation, clears
  its pending actions, settles its parent switch/exit operation according to the
  terminal tables above, and publishes a typed failure without corrupting
  native ownership.
- Catalog scan failure retains the last proven immutable catalog and publishes
  a retryable typed failure.
- A host disappearing never completes or cancels native/session work. It only
  changes foreground clock eligibility and defers host-directed terminals.
- No raw exception crosses the snapshot boundary.

## Migration Shape

The implementation is one final atomic production cutover but may be built in
test-backed internal tasks:

1. characterize the current combined runner/runtime projection and add an
   immutable snapshot oracle;
2. introduce the stateless player reducer and runtime-owned immutable
   `PlayerState`;
3. establish the non-blocking coordination lane/native-result requeue contract;
4. move clock and delayed-command scheduling into coordination commands;
5. move dialogue/action, switch-playback, and exit parent terminals;
6. move epoch-tagged host IDs and replace callback delivery with snapshot
   collection plus consumable terminal leases;
7. move epoch-linearized catalog publication and delete Activity-local
   `GhostMgr` ownership;
8. atomically switch production composition, delete `SScriptRunner` and dead
   prototypes, and refresh exact source/absence contracts; and
9. run whole-branch verification and independent reviews.

Intermediate commits may contain internal adapters for tests, but no commit
presented for merge may have two production owners or a production compatibility
facade.

## Verification

Corpus-independent gates for this slice are mandatory:

- existing `GhostRuntimeTest`, `GhostRuntimeNativeThreadTest`,
  `GhostRuntimeAttachmentTest`, and `GhostRuntimeSwitchTest`;
- migrated `SScriptRunnerDialogueTimingTest`, `SScriptRunnerBootDispatchTest`,
  `SScriptRunnerPresentationTest`, and `SScriptRunnerHostBindingTest` behavior
  against runtime/player/snapshot APIs;
- migrated `SScriptRunnerDialogueObserverTest` and
  `DialogueDialogBindingTest` behavior, including no-talk publication,
  hidden/anchored choice visibility, and input-draft restoration only for the
  exact still-current action identity;
- deterministic virtual-scheduler proofs for playback delay, timer buckets,
  stop/start epochs, stale delayed commands, and authored-response suspension;
- blocked native request proofs in which host loss, Back, clear, and switch
  commands enter the coordination lane before the eventual `NativeResponse`;
- exact attachment-event request counts for first activation (`OnFirstBoot`),
  returning switched activation (`OnGhostChanged`), and ordinary returning
  startup (`OnBoot`), with no second boot/handoff event;
- every row of the switch and exit terminal tables, including no-content,
  parser failure, request failure, poison, stale/duplicate completion, repeated
  Back, host loss before the delivery block, stale old-host claim after
  replacement, claim/`finish()`/acknowledgement enqueue order, lifecycle teardown
  after `finish()` with acknowledgement consumed before reassignment, stale
  claim/ack, and generation retirement;
- two distinct presentation cues published before collector dispatch, ordered
  contiguous-prefix acknowledgement, duplicate/stale acknowledgement, and
  64-cue active-host backpressure without drop or reorder; host loss must expire
  the old cue lease, unblock playback, preserve the normalized final frame, and
  avoid replay on the replacement host; a host-absent script with at least 65
  cues must complete without pausing or accumulating cues;
- every row of the cross-operation admission matrix, including Back during a
  blocked `OnGhostChanging` request, Back after outgoing retirement, switch
  during exit request/playback, and user/pointer clear during authored
  switch/close playback; rejected commands must prove zero mutation/effect;
- blocked pre-install catalog scan followed by install commit and post-commit
  visibility; initial Loading→Ready startup; initial failure that neither
  selects nor installs; bundled installation only after proven Ready-empty;
  coalesced dirty refresh, stale epoch rejection, and recovery-required retry;
- an atomic snapshot consistency matrix across host handoff, queued response,
  switch, exit, and generation retirement;
- API 37 lifecycle recreation/top-resumed tests and main-looper blocked-request
  tests;
- full JVM, lint, debug build, screenshot validation, Android-test compilation,
  architecture/absence contracts, both configured ABI builds, and arm64 ELF
  inspection; and
- independent Android and adversarial reviews plus GitHub automatic review.

The existing canonical 23-NAR and cross-engine real-adapter gates remain
required for #385/#374 merge readiness when artifacts are available. They are
not weakened, fabricated, or treated as passing by this design. Physical arm64
runtime remains explicitly deferred until a qualifying device exists.

## Acceptance Criteria

- `GhostRuntime` is the only production owner of native session, active/pending
  identity, catalog, queue/playback, clock/timers, dialogue actions,
  switch/exit terminals, and foreground host state.
- The coordination lane never waits for native or filesystem work; every native
  data result re-enters as a separately fenced command after lifecycle/fencing
  commands that arrived while JNI was blocked.
- One immutable `StateFlow<RuntimeSnapshot>` is the only runtime/presentation
  state source consumed by Activity/Compose.
- `SScriptRunner`, its callback interfaces, host callback binding, and separate
  runner snapshots are absent from production.
- The runtime-private `SakuraScriptPlayer` is data-only, thread-confined, and
  cannot schedule, call SHIORI, publish, or retain Android/UI objects.
- Activity recreation or overlap cannot create, attach, start, stop, switch,
  complete, or retire a native/playback operation except by submitting a
  validated runtime command.
- Host lifecycle commands are epoch-ordered, and a host terminal has one runtime
  operation plus at most one delivery lease. Foreground loss, unregister, or
  supersession reassigns only a lease whose delivery block has not begun. An
  accepted claim, exact-Activity `finish()`, and acknowledgement are enqueued in
  one non-suspending main-thread block; a claimed lease is never delivered to a
  later Activity.
- Backgrounding stops clock delivery without stopping authored playback or
  duplicating boot, talk, or timer events on return.
- Switch and exit ordering and exactly-once terminals match the established
  contracts.
- Catalog refresh is application-owned, immutable, epoch-linearized, and cannot replace
  the active generation without an explicit switch; an installed-ready state
  requires a newest-epoch post-commit scan containing the installed target.
- Initial startup and bundled installation wait for a proven `Ready` catalog;
  loading or failed catalog state is never interpreted as empty.
- No observer/collector is invoked while a native/runtime lock is held.
- During a continuous active presentation-host lease, StateFlow conflation
  cannot drop one-shot cues: every unacknowledged cue remains ordered until that
  host acknowledges a contiguous prefix. Host loss expires transient cues while
  retaining the normalized final frame; hostless playback never stalls or
  replays expired animations.
- The unused prototype/facade paths are deleted rather than renamed or retained
  as alternative implementations.
- Essential Satori, YAYA, Kawari, NanidroidShiori, installation, dialogue,
  surface, animation, collision, link, and Readme behavior remains.

## Stop Conditions

Stop and redesign if:

- any player, Activity, Compose host, or catalog helper becomes a second mutable
  runtime authority;
- player or snapshot code can directly invoke SHIORI or native APIs;
- a collector/callback runs under a native/runtime/player lock;
- blocking IO or native work runs on the UI thread;
- moving playback onto the runtime queue changes required switch, dialogue,
  surface-response, exit, or timer ordering;
- a host lifecycle event cancels shared startup/switch/native work;
- catalog refresh can mutate the active generation implicitly;
- a delayed command lacks generation plus operation/epoch fencing;
- an intermediate production adapter survives the final cutover; or
- retained behavior is removed merely because the canonical corpus is
  unavailable.

## Relationship to Later Work

This slice completes #385's ownership model but does not perform the #386
ViewModel/UDF-lite Activity cleanup. After this cutover, #386 can reduce
`Nanidroid` around one snapshot collector and command dispatcher without
reconstructing runtime state. Corpus and physical-arm64 closure remain tracked
under #374 and the existing corpus workstreams.
