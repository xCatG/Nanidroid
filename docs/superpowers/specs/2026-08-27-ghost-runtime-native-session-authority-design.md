# GhostRuntime Native Session Authority Design

**Date:** 2026-08-27
**Issue:** #385
**Phase:** Lean app phase 3, native/session authority slice
**Status:** Approved after independent architecture, lifecycle, native, and
adversarial review

## Purpose

Replace Nanidroid's transitional multi-lock native-session coordinator with one
application-owned `GhostRuntime` command thread. The runtime becomes the only
production authority that may create, load, query, request, or unload SHIORI.
Concurrent Activity startup joins one application-owned operation instead of
creating an Activity-owned reservation.

This is a replacement deletion, not an executor wrapper. The slice deletes
`GhostSessionCoordinator`, `GhostConstructionReservation`, `ReservedGhost`, the
singleton `ShioriFactory` route, direct SHIORI ownership in `Ghost`, and tests
that exist only to preserve those transitional APIs. It retains user-visible
ghost discovery, startup, switching, SakuraScript/dialogue, surfaces, balloons,
pointer interaction, foreground NAR import, and authored external links.

## Preconditions and Decision Authority

- Phase #382 is closed. Its Path A decision proves no state-capable signed APK
  was released or distributed, so no compatibility shim is required.
- Phase #384 is closed. Installed-ghost replacement and updater root mutation
  are absent; retained foreground installation is fresh-install-only and rejects
  an existing logical target.
- PRs #394 and #395 are independent corpus workstreams and do not block this
  production slice. PR #395 modifies `NarCorpusRuntimeTest`, whose direct
  `ShioriFactory` ownership must be ported to the runtime boundary here; the
  branches therefore require a semantic rebase rather than a weakened test
  bypass.
- The owner authorized removal of as much nonessential code as needed and
  delegated design/plan approval to independent agents. The spec and plan must
  be iterated until architecture, lifecycle, and adversarial reviews are clean.

## Current Problem

`GhostRuntime` currently constructs one `GhostSessionCoordinator` and one
`SScriptRunner`, but it does not own native execution. Production SHIORI work is
split across caller threads:

- `GhostMgr.createGhost` constructs `Ghost` on an arbitrary `Dispatchers.IO`
  worker.
- `Ghost` parses descriptors and surfaces, selects `Shiori`, performs native
  load in adapter constructors, and immediately runs capability probes.
- runner UI, playback, timer, and dialogue paths invoke requests from their
  caller threads, normally the main thread.
- `GhostSessionCoordinator` invokes unload while holding nested root/global
  monitors.

The coordinator serializes ownership but cannot establish native thread
affinity. Its reservation and lock hierarchy also prevents same-root startup
from joining: a replacement Activity either collides with pending construction
or later abandons the loaded reservation from its cancelled lifecycle scope.

Native adapters also lack the lifecycle evidence required by the runtime:

- Satori and YAYA load functions can silently replace a pre-existing native
  owner and do not return typed load status to Kotlin.
- Kawari load/unload return no status to Kotlin; unload does not clear its
  global handle and request does not reject an unloaded handle.
- `Shiori.terminate()` duplicates unload but has no production caller.

Wrapping these authorities in an executor would preserve split state, retain
roughly 380 lines of coordinator synchronization, and create lock inversion
between waiting callers and the command thread.

## Selected Approach: Atomic Authority Replacement

`GhostRuntime` directly owns:

- one dedicated, named OS thread backed by a single-thread executor;
- the executor's FIFO command queue;
- one application-lived, failure-isolating bounded-IO coroutine scope for ghost
  preparation;
- the one optional in-flight startup operation;
- the one optional active native session;
- the monotonically increasing native generation;
- process poison state after uncertain native teardown; and
- the exact production `SScriptRunner`.

The old coordinator and reservation types are deleted. Queue confinement, not
cross-thread monitor composition, serializes native ownership.

### Rejected alternatives

1. **Keep `GhostSessionCoordinator` under an executor.** Rejected because
   native requests currently run while coordinator monitors are held. Waiting
   for a command that re-enters coordinator state deadlocks; avoiding re-entry
   leaves two authorities.
2. **Use `Dispatchers.IO.limitedParallelism(1)`.** Rejected because serialized
   coroutines are not guaranteed to execute on one persistent OS thread.
3. **Move load, request, or unload separately.** Rejected because any partial
   migration deliberately creates two native-thread authorities and violates
   #385's stop conditions.
4. **Keep SHIORI inside `Ghost`.** Rejected because production callers could
   continue bypassing the runtime and because `Ghost` would still mix
   presentation metadata with native lifetime.

## Ownership Model

```text
CatTailApplication
  └── GhostRuntime
        ├── bounded IO preparation scope
        ├── dedicated native command thread / FIFO queue
        ├── in-flight startup/switch operation (zero or one)
        ├── active native session (zero or one)
        ├── poison / generation state
        └── SScriptRunner

GhostMgr ──catalog/root selection──> GhostRuntime.startOrJoin(id, root)
SScriptRunner ──generation-tagged commands──> GhostRuntime
GhostRuntime ──data-only results──> caller / SScriptRunner
```

No `NativeHost`, actor, session manager, app coordinator, renderer callback, or
presentation coordinator is introduced. Small immutable command/result values
are data, not authorities.

## Data Boundaries

### Prepared ghost

`GhostRuntime` owns one application-context preparer dependency. Production
callers supply only exact identity and canonical root; they never supply a
closure that can capture an Activity or Activity-local `GhostMgr`.

Bounded IO produces an immutable, operation-tagged `PreparedGhost` containing
only:

- the exact runtime-owned operation ID;
- canonical root and exact ghost ID;
- parsed ghost and shell descriptors;
- a frozen surface/collision/alias catalog;
- engine selection, including the precomputed Kawari marker decision;
- already-read built-in `NanidroidShiori` content where applicable; and
- immutable display/readme metadata required by the existing UI.

Preparation performs no `ShioriFactory` call, JNI load, charset query, SHIORI
request, or unload. Mutable surface builders are not published. The production
mutation methods used by `SurfaceReader` become builder/internal-only, and the
published catalog has no mutation surface.

The runtime thread discards a prepared value unless its operation ID and root
still match the exact current in-flight operation. A superseded or late parser
result can never enqueue a native load.

`Ghost` becomes the immutable prepared/display domain value. It contains no
`Shiori`, native unload/request method, capability mutation, or test-only SHIORI
setter.

Catalog scanning no longer constructs a mutable `InfoOnlyGhost` subclass.
`GhostMgr` holds small immutable installed-ghost metadata values. `InfoOnlyGhost`
is deleted; because `DirList` has one production caller, its scan is folded into
the catalog/preparation code and `DirList` is deleted when call-graph validation
confirms no other behavior owner.

### Runtime session

A private queue-confined session holds:

- the prepared `Ghost`;
- the concrete `Shiori` adapter;
- the immutable pointer-event capability result;
- canonical identity; and
- native generation.

The adapter never leaves `GhostRuntime`. UI, runner, snapshots, callbacks, and
tests cannot retain or invoke it.

### Handle and completion

Callers receive a small immutable handle containing the prepared `Ghost`,
immutable pointer-event capabilities, and generation, never the adapter. Every
request/unload command carries the exact expected generation. Every response
completion carries that same generation.

The runtime rejects a stale command before native invocation. The runner
revalidates its playback/dialogue generation before admitting a returned script
or presentation effect. A queued request is never interpreted as "send to
whichever ghost is active when this runs."

## Runtime Commands

The minimum internal command surface is:

- `startOrJoin(ghostId, canonicalRoot)` — application-owned
  asynchronous startup/switch preparation and native load;
- `beginSwitch(expectedGeneration, targetGhostId, targetRoot)` — records one
  runtime-owned switch intent and returns its operation ID before authored
  `OnGhostChanging` playback;
- `completeSwitchPlayback(expectedGeneration, switchOperationId)` — consumes
  the exact authored-playback completion once, unloads the outgoing generation,
  and starts the recorded target without an Activity continuation;
- `failSwitchBeforeUnload(expectedGeneration, switchOperationId, failure)` —
  clears a known pre-unload authored-request failure while keeping the proven
  outgoing owner active;
- `attachHost(expectedGeneration)` — joins or starts one runtime-owned
  attachment operation and returns whether presentation is newly attached or
  already attached;
- `request(expectedGeneration, request)` — synchronous-to-caller,
  queue-confined native request returning an immutable tagged response;
- `unload(expectedGeneration)` — synchronous-to-caller, queue-confined typed
  teardown; and
- read-only runtime/session identity for instrumentation and attachment.

`request` input is already formatted immutable SHIORI intent/protocol data.
Command execution never calls runner, Activity, Compose, renderer, observer, or
callback code. Callers must not hold the runner monitor while waiting for a
runtime result.

Bootstrap capability discovery executes directly inside the load command on
the runtime thread. It must not submit a nested command to its own executor.
YAYA's charset query before request encoding and after native response remains
inside the same runtime-thread command; it is not cached at load time.

Ordinary unsupported, malformed, or failed optional capability probes produce
the existing immutable `UNKNOWN` capability result and keep the proven-loaded
session. Only an adapter result that makes native ownership uncertain enters
typed cleanup/poison handling.

### State resolution

`startOrJoin` resolves state atomically before starting work:

| Runtime state | Exact requested root | Result |
|---|---|---|
| Active | same | Return the identical handle immediately; no preparation, load, generation, activation, or boot |
| Active | different | Reject as busy unless it is the exact target of the runtime-owned switch flow after outgoing unload |
| In flight | same | Join the exact operation |
| In flight | different | Reject as busy unless the runtime-owned switch intent explicitly superseded a still-pre-native operation |
| Idle | any valid root | Allocate one operation ID and start preparation |
| Poisoned | any | Reject without native work |

Supersession is permitted only before native load begins. It marks the previous
operation ID stale; its IO result is discarded and cannot load. Once native
load begins, the command is non-cancellable and must settle/clean up before any
different root is considered.

### Attachment linearization

`attachHost` is an application-owned shared operation, not an Activity-owned
sequence. Its queue-confined claim transitions the exact generation from
`Unattached` to `Attaching` and allocates one attachment operation ID. Concurrent
or replacement hosts join that operation. The application scope then:

1. persists activation count once (retaining the current best-effort failure
   behavior);
2. issues the exact `OnFirstBoot`, `OnBoot`, or `OnGhostChanged` request through
   the generation-tagged runtime command;
3. hands the tagged response and attachment operation ID to the
   application-owned runner outside the native command; and
4. completes the runtime state as `Attached`.

Runner admission is idempotent by attachment operation ID. It may queue the
boot script while no presentation host is live, but it does not begin visual
playback until an Activity attaches/replaces the renderer and resumes the
runner. A caller cancelled after the claim merely stops waiting: the
application operation continues, and a replacement joins or observes the
completed attachment. Cancellation between response handoff and completion
cannot duplicate playback because the runner rejects the same attachment ID.

Later same-generation `attachHost` calls return `AlreadyAttached`; they replace
only Activity presentation bindings and never persist activation or dispatch
boot again. Native command execution never calls runner/UI code; the
application orchestration applies the data-only result after leaving the
command thread.

`Attaching` retains these bits until it reaches a terminal state:

- `activationCommitted` — set after the one best-effort persistence attempt;
- `bootAttempted` — set after one exact-generation boot request attempt;
- a tagged boot outcome: either the exact response or a typed
  `BootAttemptFailed`/no-script result for a known request failure; and
- `runnerAdmissionCommitted` — deduplicated by attachment operation ID.

A known boot-request failure with native ownership still certain records the
typed no-script outcome and completes attachment; it never repeats activation
or boot and never fabricates a SHIORI response. Runner admission attaches the
generation without queuing a script for that outcome. An ownership-uncertain
boot failure poisons the runtime and completes the attachment operation as fatal
without presenting the session as usable. A runner-admission failure keeps the
same `Attaching` operation, committed bits, and cached outcome so a later host
retries admission only. If admission actually committed before an exception,
the runner's operation-ID dedupe makes the retry a no-op; after successful
admission the runtime becomes `Attached`.

## Startup and Activity Recreation

1. The first Activity selects a canonical ghost root through its catalog view
   and calls `GhostRuntime.startOrJoin`.
2. Under a small non-native state lock, the runtime returns an exact active
   handle, joins an exact in-flight operation, or installs one in-flight
   operation with a monotonic operation ID before starting any work.
3. Preparation runs once on the runtime-owned bounded-IO scope.
4. The prepared value is submitted to the dedicated command thread.
5. The runtime verifies it is unpoisoned and has no active owner, creates the
   selected adapter, performs native load, and runs capability probes.
6. Only a fully loaded/probed session becomes the active generation. The shared
   operation completes with its data-only handle.
7. A fully loaded session is initially `Unattached`. The first valid host joins
   one `attachHost` operation that commits activation, boot request, idempotent
   runner admission, and `Attached` state once. Later same-generation hosts
   replace presentation attachment only.

Activity `lifecycleScope` cancellation detaches only that waiter. It cannot
cancel the producer, unload the result, abandon a token, or clear the in-flight
entry. A replacement Activity joins the same operation and generation.

If every waiter disappears, the application-owned operation still settles.
The runtime retains a successful `Unattached` session for the next Activity and
dispatches neither activation nor boot without a host. The first later host
attaches and commits both exactly once. Waiters never own cleanup authority.

The preparation scope uses `SupervisorJob` semantics and per-operation
exception capture. Preparation/load failure completes the shared result but
cannot cancel the application scope or prevent a later retry.

## Ghost Switching and Exit

Before requesting `OnGhostChanging`, `beginSwitch` records the exact target
root, outgoing generation, and one switch operation ID. `nextGhostId` and
switch-step ownership are removed from the Activity. The application-owned
runner submits the exact operation ID to `completeSwitchPlayback` after authored
playback, or immediately after a known successful no-content response. It does
not retain an Activity callback/continuation.

Every new Activity first queries the runtime's current attachment state. It
reattaches to the outgoing active generation while authored playback continues,
or joins the exact pending replacement after outgoing unload. It never selects
the old last-run preference against an in-flight switch. A mismatched catalog
request is rejected as busy until the exact switch settles.

The observable switch order remains:

1. request `OnGhostChanging` against the exact outgoing generation;
2. play the authored response to completion;
3. submit `completeSwitchPlayback` once outside runner locks;
4. let that queue-confined runtime transition unload the outgoing generation;
5. prepare and load the replacement;
6. attach the accepted replacement generation;
7. dispatch `OnFirstBoot` or `OnGhostChanged` exactly once; and
8. restart the clock.

No replacement preparation can publish a live session before outgoing unload
returns known success. A stale completion cannot attach, request, unload, or
boot a replacement generation.

Switch success updates last-run identity as runtime-owned committed state before
the replacement becomes attachable. Recreation after outgoing unload therefore
discovers the pending/active replacement without Activity-local saved state.

Switch terminal rules are:

- a known authored-request transport failure before unload invokes
  `failSwitchBeforeUnload`, clears the intent, keeps outgoing active, and
  publishes a replayable data-only failure;
- a successful no-content authored response counts as completed playback and
  proceeds through `completeSwitchPlayback`;
- a stale or duplicate completion is rejected without state change;
- unload known success retires outgoing before any target preparation/load;
- unload failure or uncertainty poisons the runtime, terminates the switch as
  fatal, and never starts target preparation/load;
- target preparation failure or any replacement-load failure that settles
  native ownership proven empty—including successful cleanup after a load may
  have occurred—clears busy/pending switch state, preserves the previous
  last-run preference, and publishes a replayable failure so a recreated host
  can retry startup or choose another ghost;
- replacement-load failure followed by failed or uncertain cleanup poisons the
  runtime, completes the switch as fatal, preserves the native ownership
  evidence for diagnostics, and never starts another load; and
- target load success publishes one `Unattached` replacement, updates committed
  last-run identity, and clears the switch intent before host attachment.

`OnClose` remains an authored SHIORI request. It is not a second native close
primitive. After authored completion, process exit uses the single typed
adapter unload. The dead `Shiori.terminate()` alias is deleted.

## Native Adapter Contract

JNI and Kotlin adapter changes are atomic and preserve JNI-visible package,
class, and native method names unless the corresponding Kotlin declaration and
C/C++ registration/export are changed in the same commit.

For Satori, YAYA, and Kawari:

- load reports known success or a typed load failure;
- load rejects an already-loaded owner instead of silently unloading/replacing
  it;
- unload reports known success or typed failure;
- duplicate unload is idempotent known success;
- successful unload clears all native global handles/loaded flags;
- request and YAYA charset lookup reject the unloaded state;
- a load failure leaves the native loaded flag/handle empty; and
- unexpected native/linkage failure is surfaced without fabricating success.

Kawari additionally clears global `h` after successful `DisposeInstance` and
honors its Boolean failure result.

The Kotlin `Shiori` interface retains one request and one unload operation. It
deletes `terminate()`. `ShioriFactory.kt` and its singleton identity are deleted;
engine-to-adapter construction becomes a private `GhostRuntime` function.
Isolated runtime tests may inject an adapter-producing function into the
runtime constructor. Corpus instrumentation must drive an internal test
`GhostRuntime` and may not retain or invoke an adapter directly.

## Failure and Poison Rules

### Load

- A typed load failure that proves native ownership empty completes all joined
  waiters with the same failure, removes the in-flight entry, and permits a
  later valid retry.
- Failure after native load may have succeeded triggers one runtime-thread
  cleanup attempt.
- If cleanup returns known success, ownership becomes empty and retry is
  allowed.
- If cleanup is uncertain or fails, the runtime becomes process-poisoned and
  forbids every later load/request/unload claim except idempotent diagnostic
  inspection.

### Request

- A stale generation is rejected before native invocation.
- A typed request failure does not fabricate a response. It keeps the exact
  owner unless the adapter reports ownership uncertainty.
- Completion admission remains generation-fenced in the runner.

### Unload

- Known success clears the active session only after the adapter confirms
  teardown and increments/retire-fences the generation.
- Duplicate unload of the already retired exact generation is idempotent and
  cannot affect a replacement.
- Failure or uncertainty preserves evidence and poisons the runtime. Another
  engine is never loaded in that process.

Blocking JNI is non-cancellable. Cancellation stops only a caller's wait; it
does not interrupt an in-flight command, report a false Stop, or start another
owner concurrently.

## Deletion Scope

Delete rather than migrate:

- `GhostSessionCoordinator.kt`, including both reservation types;
- `InfoOnlyGhost.kt` and, after exact call-graph confirmation, single-caller
  `DirList.kt`, replaced by immutable installed-ghost metadata;
- coordinator construction/plumbing in `GhostRuntime` and `SScriptRunner`;
- runner attach/abandon/gate/test helpers that exist only for reservations;
- Activity reservation ownership, claimed flags, and `finally` abandonment;
- direct SHIORI field/load/request/unload/capability mutation in `Ghost`;
- `Ghost.setShioriForTesting`, `sendOnSecondChange`, and
  `sendOnMinuteChange`;
- `Shiori.terminate()` and every implementation/test override;
- the production `ShioriFactory.kt` boundary and every direct factory caller;
- zero-caller `GhostMgr.hasSameGhostId`, general `installGhost` overloads,
  `getLastInstallError`, and their backing error state when exact call-graph
  verification remains empty; and
- tests/assertions whose only contract is coordinator locks, reservation token
  identity, or the deleted constructor shape.

Isolated test runtimes implement `AutoCloseable` or an equivalent internal
`closeForTesting` contract. Cleanup queue-confined-unloads any known session,
cancels the preparation scope, terminates the executor, and fails the test if
the thread does not settle. The production application runtime remains
process-lived and is never closed by an Activity.

Retain or rewrite tests that prove user-visible ordering, one owner, generation
fencing, unload-before-reload, failure safety, queue isolation, dialogue,
surfaces, timers, switching, startup, or recreation. Test-line deletion is not
accepted as coverage deletion for an essential behavior.

## Essential Behavior Preserved

- bundled first ghost installation and fresh local NAR import;
- installed ghost discovery, display names, Readmes, and selection;
- Satori, YAYA, Kawari, NanidroidShiori, and unsupported-engine behavior;
- exact SHIORI request formatting and response parsing;
- capability discovery and pointer-event negotiation;
- SakuraScript ordering, dialogue input/choice/anchor behavior, surfaces,
  balloons, animations, collision routing, and authored links;
- `OnGhostChanging`, authored playback completion, unload, replacement load,
  `OnFirstBoot`/`OnBoot`/`OnGhostChanged`, and exactly-once handoff ordering;
- timer eligibility and clock-only foreground/background pause; and
- activation counting only after one accepted attachment.

`UICallback` is not deleted in this slice. Its production methods appear empty,
but callback presence still controls input pause/resume and choice publication.
It may be removed only after those effects become unconditional player/runtime
state in the later playback/snapshot slice.

## Out of Scope

- moving SakuraScript playback, timers, catalog ownership, renderer state, or
  the final immutable `RuntimeSnapshot` into `GhostRuntime`;
- deleting `SScriptRunner` or `UICallback` before their behavioral effects move;
- the #386 ViewModel/UDF-lite Activity boundary;
- changing foreground NAR import, archive validation, or installed-tree policy;
- adopting Hilt, WorkManager, another DI framework, or another Gradle module;
- claiming physical arm64 runtime coverage without a qualifying device; and
- merging or replacing PR #394/#395 corpus work.

## Testing Strategy

### Native lifecycle tests

For Satori, YAYA, and Kawari, prove:

- typed load success/failure;
- duplicate unload is safe;
- request and charset lookup after unload reject;
- unload failure is observable;
- load rejects an already-loaded owner; and
- the next valid load succeeds after a proven-empty failed load.

Kawari tests additionally prove its global handle is cleared.

### Runtime thread and generation tests

Using an injected internal adapter factory and deterministic hooks, prove:

- load, bootstrap requests, YAYA charset queries, ordinary requests, and unload
  all record the same named non-caller OS thread;
- two concurrent same-root callers join one blocked preparation/load and receive
  one ghost/generation;
- cancelling one waiter does not cancel the producer or another waiter;
- cancelling the sole waiter lets load settle `Unattached`; a later host then
  commits activation and boot exactly once;
- cancellation after an attachment claim cannot lose or duplicate activation,
  boot request, or runner admission;
- known boot-request failure completes attachment once with a typed no-script
  outcome and without repeating activation/boot, while a transient
  runner-admission failure retries only the cached idempotent admission;
- joined callers observe the same load failure, and a later retry starts one new
  generation;
- a stale superseded preparation result cannot enqueue native load;
- exact active-root reuse performs no preparation, load, generation, activation,
  or boot;
- unload completes before replacement load;
- switch pre-unload failure, unload poison, each proven-empty replacement
  failure path, uncertain replacement cleanup, success, stale completion, and
  duplicate completion reach the exact terminal states defined above;
- stale queued requests/completions are rejected across switch;
- uncertain unload poisons and forbids reload; and
- isolated test runtimes do not share thread, session, generation, or queue.

### Activity instrumentation

- Preserve attached-session recreation identity.
- Add recreation while initial load is blocked: the replacement joins, load
  count remains one, native generation remains one, and boot/activation commits
  once.
- Add recreation while a switch is blocked after outgoing unload: the
  replacement Activity discovers the runtime-owned target, joins one replacement
  load/generation, and observes one `OnGhostChanged`/`OnFirstBoot`.
- Preserve pause/resume clock behavior without duplicate boot/timer events.

The runtime owns load hooks; Activity hooks orchestrate recreation only. No
Hilt test application or reflection into deleted coordinator state is added.

### Retained suites and gates

- all JVM tests and JaCoCo report;
- debug APK assembly, lint, screenshot validation, and Android-test compilation;
- lifecycle and focused native instrumentation on API 37 x86_64;
- retained switch, request, SakuraScript, dialogue, surface, and corpus suites;
- source/artifact contracts proving no coordinator/reservation/static factory or
  production SHIORI bypass remains;
- both-ABI APK native inventory and AArch64 ELF inspection;
- an exact Satori → YAYA → Kawari → Satori device transition with one request
  after each load and predecessor-unload-before-successor-load evidence on
  x86_64;
- the available fixed corpus gate without claiming unavailable rows; and
- physical arm64 device execution when available, otherwise explicit deferral.

The same exact cross-engine transition is required on arm64-v8a when a
qualifying physical or virtual device is available. The owner has explicitly
allowed arm64 runtime deferral; if no device is attached, the PR records
arm64-v8a build/ELF/package evidence, leaves the runtime transition unclaimed,
does not close #385, and carries the device gate to #374.

Every implementation task receives an independent requirement review. The final
exact diff receives Android and adversarial reviews plus GitHub automatic Codex
review. Findings are fixed and re-reviewed before merge readiness.

## Acceptance Criteria

- Exactly one application-owned OS thread executes every production SHIORI
  load, charset lookup, request, and unload.
- `GhostRuntime` is the only production adapter/factory/native-session authority.
- `GhostSessionCoordinator`, both reservation types, their test helpers, and
  Activity abandonment plumbing are absent.
- `Ghost` contains no SHIORI/native lifetime authority.
- Concurrent startup and blocked-startup recreation join one operation, load,
  ghost, generation, activation, and boot dispatch.
- Same-root recreation after completion returns the identical active handle
  without another preparation/load/generation/activation/boot.
- Runtime-owned switch intent survives Activity recreation before and after
  outgoing unload; Activity-local `nextGhostId`/step-two authority is absent.
- Caller cancellation never cancels or unloads a shared producer.
- Every command and completion is exact-generation fenced.
- Known load failure leaves ownership empty and permits retry.
- Uncertain unload poisons the runtime and prevents replacement load.
- Outgoing authored completion and successful unload precede replacement load.
- Satori, YAYA, and Kawari lifecycle status is observable; Kawari unload clears
  its handle and unloaded requests reject.
- Essential retained behavior and validation gates pass with no new product or
  compatibility layer.
- The reviewed diff removes the transitional authority and dead APIs rather than
  preserving them under renamed wrappers.

## Stop Conditions

Stop and redesign if:

- any production SHIORI operation bypasses the runtime thread;
- filesystem parsing or mutable surface building blocks the native thread;
- a runtime command calls back into runner/UI/Compose or waits on their locks;
- a queued request can target a different generation from the one captured;
- Activity cancellation can cancel/unload shared startup;
- adapter load/unload reports success without native evidence;
- failed/uncertain unload permits another engine load;
- switch/boot/dialogue/timer ordering changes;
- essential surface, collision, dialogue, local import, or retained-engine
  behavior is removed; or
- the implementation adds a second authority layer to compensate for retained
  coordinator or `Ghost` ownership.

## Next Slice Boundary

After this slice, `GhostRuntime` already owns authoritative active ghost identity
and metadata. `SScriptRunner` retains only a generation-tagged data projection
needed by current playback. The next #385 design moves that projection, timers,
SakuraScript playback, dialogue/input/choice inventory, host foreground IDs,
and one immutable `RuntimeSnapshot` into `GhostRuntime`; then it deletes the remaining
`SScriptRunner` callback authority and reduces playback to a small
thread-confined `SakuraScriptPlayer`. That later slice must preserve the native
thread and generation contracts established here rather than reintroducing a
second coordinator.
