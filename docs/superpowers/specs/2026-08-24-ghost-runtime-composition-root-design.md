# GhostRuntime Composition Root Design

**Date:** 2026-08-24
**Issue:** #385
**Phase:** Lean app phase 3, entry slice
**Status:** Approved after written-spec multi-agent review

## Purpose

Create one application-scoped `GhostRuntime` as the production composition
root for the existing native-session and SakuraScript authorities. This is the
first authority handoff in #385. It removes singleton, construction, and
process-static SakuraScript queue authority from `SScriptRunner` without
changing observable startup, switching, playback, timer, rendering, or SHIORI
behavior.

The slice deliberately does not claim the final dedicated-runtime-thread
invariant. It creates the single boundary required to move construction, load,
charset discovery, request, close, and unload onto that thread atomically in
the next slice. Moving only some native calls now would create the dual
authority that #385 forbids.

## Current Problem

Production authority is process-global but assembled in the wrong place:

- `SScriptRunner` owns its own static singleton identity, a static
  `GhostSessionCoordinator`, and a static SakuraScript message queue.
- `GhostMgr` reaches construction and reuse through static `SScriptRunner`
  methods even though catalog discovery is not playback.
- `Nanidroid` obtains the runner singleton directly and creates an
  Activity-local `GhostMgr` that performs `Ghost` construction on its current
  IO coroutine.
- `Ghost` construction selects and loads SHIORI through `ShioriFactory`, while
  later requests normally arrive from runner/main-loop work. The coordinator
  serializes ownership but does not confine native operations to one OS thread.

This shape hides application lifetime inside a playback class and provides no
single seam for the remaining #385 handoff.

## Approaches Considered

### 1. Application composition root first — selected

Create one `GhostRuntime` from `CatTailApplication`. Move the one production
coordinator and runner into it, inject that runtime into `GhostMgr`, and delete
the production singleton/static construction surface from `SScriptRunner`.

This is the smallest real authority transfer. It preserves behavior, removes
misplaced static ownership, and creates one receiver for the next native-thread
slice.

### 2. Dedicated native thread immediately

Create `GhostRuntime` and simultaneously move all native construction, load,
charset, bootstrap request, ordinary request, close, and unload calls onto its
thread.

This reaches a major #385 invariant sooner, but it crosses construction,
switching, synchronous request, error, lifecycle, and test boundaries at once.
If any direct `Ghost` or runner path remains, it also creates two authorities.
This approach is deferred until the composition root exists.

### 3. Playback or snapshot migration first

Move SakuraScript state or presentation snapshots into a new runtime while
leaving native/session identity in `SScriptRunner`.

This would introduce a second coordinator-shaped object without reducing the
most dangerous ownership ambiguity. It is rejected.

## Scope

### In scope

- Add one application-scoped `GhostRuntime`.
- Make it own exactly one production `GhostSessionCoordinator` and one
  production `SScriptRunner`.
- Initialize that runtime from `CatTailApplication`.
- Make every `Nanidroid` instance use the application's runtime and runner.
- Inject the same runtime into each Activity-local `GhostMgr`.
- Route construction reservation and active-ghost reuse through
  `GhostRuntime`.
- Remove `SScriptRunner.getInstance`, `self`,
  `productionSessionCoordinator`, `beginGhostConstruction`, and
  `reuseActiveGhost` from its companion.
- Remove the companion `reserveGhostForAttachment` test helper and the
  one-argument runner constructor that implicitly selects the production
  coordinator; tests construct runners with an explicit coordinator instead.
- Move `msgQueue` from the `SScriptRunner` companion to each runner instance.
- Preserve direct runner/coordinator construction only as explicit test seams.
- Replace singleton-reset tests with isolated runtime instances.

### Out of scope

- Adding the dedicated runtime OS thread or command queue.
- Moving SHIORI load, charset, request, close, or unload between threads.
- Moving `GhostMgr` catalog state into the runtime.
- Moving active `Ghost`, switch state, timers, SakuraScript playback, dialogue,
  renderer, or snapshots out of `SScriptRunner`.
- Changing `Nanidroid` startup or ghost-switch ordering.
- Changing `Ghost`, `ShioriFactory`, native adapters, JNI names, C/C++, or
  shrink rules.
- Adding a second host, actor, session manager, app coordinator, renderer
  callback, or presentation coordinator.

## Ownership After This Slice

`CatTailApplication` owns one lazily constructed, thread-safe `GhostRuntime`
and forces its creation during `Application.onCreate`. The runtime is never
Activity-scoped and is not recreated when an Activity is recreated or replaced.

`GhostRuntime` owns:

- one `GhostSessionCoordinator`;
- one `SScriptRunner` constructed with that exact coordinator; and
- the narrow construction/reuse entry points currently misplaced on the
  runner companion.

`SScriptRunner` continues to own the active `Ghost`, its instance-local
SakuraScript queue, playback, dialogue, timers, switch/exit flags, renderer
state, and its current testable behavior. It no longer owns production
singleton identity, process-static queued scripts, or creates the production
session coordinator.

`GhostMgr` remains Activity-local for this slice. It owns its existing catalog
view and installation helpers, but it receives `GhostRuntime` explicitly and
uses that runtime for construction reservations and active-owner reuse.

`Nanidroid` obtains both runtime and runner from `CatTailApplication`. It keeps
its current startup, lifecycle, Compose, and switch calls unchanged.

## Construction and Access

The production relationship is:

```text
CatTailApplication
  └── GhostRuntime
        ├── GhostSessionCoordinator
        └── SScriptRunner(exact coordinator)

Nanidroid ──uses──> application GhostRuntime / runner
GhostMgr  ──uses──> injected GhostRuntime construction authority
```

`CatTailApplication.ghostRuntime` uses synchronized lazy initialization. The
application accesses it during `onCreate`, so normal production startup creates
the runtime before any Activity. The synchronized lazy contract also makes
concurrent test access deterministic without a second singleton registry.

`GhostRuntime` has an internal constructor that accepts the application context
and optional explicit test dependencies. Production passes the application
context. Unit tests construct isolated runtimes or continue constructing
`SScriptRunner` with an explicit coordinator; they never mutate process-static
runner state. Every explicitly constructed runner receives its own message
queue through ordinary instance initialization.

The runtime exposes only the minimum transitional surface:

- its exact runner to the Activity host;
- begin construction for an exact ghost ID/canonical root; and
- reuse the exact active ghost when the coordinator permits it.

Reservation binding, attachment, abandonment, transition, live request gates,
and unload remain on the existing coordinator/runner paths in this slice. The
runtime must not duplicate that state.

## Data and Control Flow

### Startup

1. `CatTailApplication.onCreate` initializes `GhostRuntime` once.
2. `Nanidroid.onCreate` reads that runtime and its runner.
3. Activity initialization creates `GhostMgr` with the same runtime.
4. `GhostMgr.createGhost` asks the runtime to reuse the active owner or begin an
   exact construction reservation.
5. Existing `Ghost` construction, reservation binding, runner attachment,
   boot dispatch, and presentation proceed unchanged.

### Activity recreation

After an active ghost has been attached:

1. The replacement Activity reads the existing application runtime.
2. It receives the identical runner and coordinator authority.
3. Its new `GhostMgr` may rediscover catalog information, but active-ghost
   reuse returns the existing session rather than constructing another owner.
4. Recreation does not replace the runtime, runner, active native session, or
   generation identity.

Recreation while startup preparation is still in flight remains outside this
slice. The current Activity-owned `lifecycleScope` can be cancelled and its
reservation abandoned before the replacement retries startup. Joining that
same in-flight startup without another load or generation belongs to the next
atomic native-thread slice.

### Ghost switch

The existing `OnGhostChanging` script, authored completion callback, outgoing
unload, replacement construction, attachment, `OnFirstBoot`/`OnGhostChanged`,
and clock restart ordering remains unchanged. Only the object through which
`GhostMgr` obtains construction authority changes.

## Failure and Concurrency Behavior

- Runtime construction has no recoverable partial state. The coordinator is
  created before the runner and both become visible together through the lazy
  value.
- Concurrent application callers receive the same completed runtime instance.
- Existing coordinator reservation, exact-token, poison, and unload failures
  remain authoritative; `GhostRuntime` does not catch, translate, or retry them.
- An Activity destroyed during ghost preparation still abandons the same exact
  reservation through the runner/coordinator path.
- Tests use isolated runtime instances instead of clearing shared production
  singletons. This removes cross-test global-state coupling.
- Queue operations affect only the owning runner. One runtime cannot enqueue,
  drain, inspect, or clear another runtime's pending SakuraScript.

## Testing

### New focused tests

- Concurrent access to `CatTailApplication.ghostRuntime` returns the identical
  runtime and runner.
- A runtime's runner and construction methods use the same coordinator; a
  reservation created through the runtime can be attached only by that
  runtime's runner.
- Two isolated runtimes do not share runner or coordinator authority.
- Two isolated runtimes do not share queued SakuraScript; enqueuing or clearing
  one runner leaves the other runner's queue and playback state unchanged.
- Static architecture coverage proves production contains no
  `SScriptRunner.getInstance`, `self`, `productionSessionCoordinator`, or
  runner-companion construction/reservation/reuse/queue authority, and no
  runner constructor can silently create or select a production coordinator.
- After an active ghost is attached, `ActivityScenario.recreate()` preserves
  the exact runtime, runner, active ghost, and native generation identity.

### Retained JVM suites

- `GhostSwitchingCharacterizationTest`
- `GhostSwitchRequestTest`
- `SScriptRunnerBootDispatchTest`
- `SScriptRunnerPresentationTest`
- `SScriptRunnerDialogueTimingTest`
- `SScriptRunnerDialogueObserverTest`
- `SakuraScriptCharacterizationTest`
- `GhostShioriTrafficTest`

### Retained device coverage

- `NanidroidLifecycleInstrumentationTest`, especially pause behavior and the
  new active-session recreation identity/generation assertion. Blocked-startup
  recreation remains a next-slice test.
- Existing startup, Compose, packaging, and foreground-import regression gates.

## Acceptance Criteria

- `CatTailApplication` is the only production creator of `GhostRuntime`.
- One application runtime owns exactly one production runner and coordinator.
- Every Activity uses that same runtime and runner.
- `GhostMgr` cannot reach construction authority through static
  `SScriptRunner` methods.
- `SScriptRunner` has no process singleton, production coordinator, or static
  mutable queue state.
- Isolated runtimes cannot observe, drain, or clear each other's queued
  SakuraScript.
- Activity recreation after active attachment preserves runtime, runner,
  active native session, and generation identity.
- Existing startup, switch, boot, timer, SakuraScript, dialogue, and
  presentation behavior remains green.
- No new thread, queue, coordinator layer, or public API is introduced beyond
  `GhostRuntime` itself; the existing queue only changes ownership.

## Stop Conditions

Stop and redesign this slice if:

- production can construct more than one `GhostRuntime`;
- runtime and runner can receive different coordinators;
- two runtime instances can share queued SakuraScript or queue mutation;
- `GhostMgr` retains a static route to session ownership;
- Activity recreation after active attachment creates or replaces
  runtime/session authority;
- behavior requires two live runtime or coordinator instances;
- startup or switch ordering changes; or
- the slice begins moving only a subset of native calls to another thread.

## Next Slice Boundary

After this slice merges, the next atomic design moves all native-facing
construction, load, charset discovery, request, close, and unload work onto one
dedicated `GhostRuntime` OS thread and command queue. Its red tests must prove:

- concurrent startup callers join one load and generation;
- every native-facing operation runs on the same non-caller thread;
- outgoing close/unload completes before replacement load;
- failed load leaves ownership empty and a later valid load succeeds;
- uncertain close/unload poisons the runtime and forbids reload; and
- Activity recreation during blocked startup joins the same startup rather than
  loading or booting twice.

The current slice must not pre-implement or weaken those requirements.
