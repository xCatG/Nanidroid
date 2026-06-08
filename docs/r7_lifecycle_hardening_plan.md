# R7 Lifecycle Hardening Plan (TDD)

Closes the two PARTIAL items from the Phase 2 remediation review: **R7-A** (native SHIORI
unload hazards) and **R7-B** (cross-host singleton teardown). The engine `SScriptRunner` is a
**process-wide singleton** (`_self`) shared by hosts. Teardown is currently host-local, which
causes cross-host damage. We fix ownership properly with a host attachment refcount and make the
native unload safe.

> Tooling: use the `android` CLI for code search (`android studio find-usages`, `analyze-file`),
> not grep. Run tests with `./gradlew :app:testDebugUnitTest --console=plain`. Every change is
> TDD: write the failing test first, watch it fail for the right reason, then minimal code to green.

## Background facts (verified)

- `ScriptEngine.setGhost(ghost)` (`ScriptEngine.kt:97`) already unloads the outgoing ghost:
  `if (g != ghost) { g?.unload() }` before `g = ghost`.
- `ScriptEngine.stop()` (`ScriptEngine.kt:276`) ALSO unloads in its `changingPending` branch
  (`ScriptEngine.kt:300`): `g?.unload()` — but it runs on `mainDispatcher` and reads `g` LIVE, so if
  a host already called `setGhost(newGhost)` it unloads the WRONG (new) ghost; and it can
  double-unload the outgoing ghost.
- `Ghost.unload()` (`Ghost.kt:92`) is `shiori?.unloadShiori()` — NOT idempotent (no guard).
- Three callers tear down engine state today:
  - `OverlayMascotService.onDestroy` (`OverlayMascotService.kt:156`) — real rendering host.
  - `InAppMascotView.onDispose` (`MainScreen.kt:420`) — real rendering host; currently guarded by
    `if (!OverlayMascotService.isRunning)` using a non-volatile `@JvmStatic var isRunning` flag.
  - `NanidroidService.onDestroy` (`NanidroidService.kt:117`) — **NOT a rendering host**. It is a
    background update/sensor service that only pushes SHIORI events into the queue; it never calls
    `setViews`/`startClock`. Its teardown of `setGhost(null)`/`clearViews()` is incorrect.

---

## Task 1 — Make `Ghost.unload()` idempotent (R7-A part 1)

**Why:** With two unload call sites that can race, a double `unloadShiori()` re-runs native teardown
(Satori re-fires `OnSatoriUnload` + re-saves; relies on C++ NULL-slot guards). Make the Kotlin layer
the single source of idempotency so a second `unload()` is a no-op.

**Files:** `app/src/main/java/com/cattailsw/nanidroid/Ghost.kt`

**TDD:**
- RED — new test in `app/src/test/java/com/cattailsw/nanidroid/test/` (e.g. `GhostUnloadTest.kt`):
  `unload_calledTwice_unloadsShioriOnce()`. Build a `Ghost` subclass that overrides `loadGhostInfo()`
  (no-op) so no filesystem is needed (mirror the existing `setGhostNull_unloadsGhostNativeResources`
  pattern in `ScriptEngineTest.kt`). Inject a fake `Shiori` whose `unloadShiori()` increments a
  counter. Call `ghost.unload()` twice. Assert the counter == 1.
- GREEN — in `Ghost.unload()`: capture `shiori` into a local, set the field to `null`, then call
  `unloadShiori()` on the local once. Second call sees `shiori == null` → no-op.
- REFACTOR — none expected.

**Spec / done:** `unload()` invokes `unloadShiori()` at most once across repeated calls; `shiori`
field is null after unload. Existing tests stay green.

---

## Task 2 — Single-source native unload; remove the racy `stop()` unload (R7-A part 2)

**Why:** `stop()`'s `changingPending` branch (`ScriptEngine.kt:300`) reads `g` live and can unload the
NEWLY-set ghost (wrong-ghost) or double-unload the outgoing one. `setGhost()` is the correct single
owner of unload (it unloads the OUTGOING ghost before swapping). Centralize there; remove the unload
from `stop()`.

**Files:** `app/src/main/java/com/cattailsw/nanidroid/ScriptEngine.kt`

**Investigation first (use `android studio find-usages`):** confirm that
`StatusCallback.ghostSwitchScriptComplete()` ultimately leads a host to call `setGhost(newGhost)` on a
script-driven ghost change. If it does (expected), removing the `stop()` unload is safe — the
outgoing ghost is unloaded by the subsequent `setGhost`. If you find a ghost-change path that does
NOT result in a `setGhost`, instead of removing, capture the outgoing ghost reference at the moment
`changingPending` is set and unload THAT specific reference (never the live `g`). Pick the option
that preserves "outgoing ghost unloaded exactly once, incoming ghost never unloaded" and document
which path you confirmed.

**TDD:**
- RED — tests in `ScriptEngineTest.kt` proving the invariant through `setGhost` (the single owner):
  - `setGhost_switchingGhosts_unloadsOutgoingOnly()`: `setGhost(ghostA)` then `setGhost(ghostB)` ⇒
    `ghostA.unload()` called exactly once, `ghostB.unload()` NEVER called (use counting Ghost
    subclasses as in Task 1 / the existing unload test).
  - `setGhost_sameGhost_doesNotUnload()`: `setGhost(ghostA)` then `setGhost(ghostA)` ⇒ no unload
    (the `g != ghost` guard).
  - If feasible without excessive machinery, a regression test that the `changingPending` completion
    path does not unload the incoming ghost. If driving `changingPending` is impractical in a unit
    test, state that explicitly and rely on the `setGhost` invariant tests + code removal.
- GREEN — remove `g?.unload()` from `stop()`'s `changingPending` block (or apply the captured-ref
  variant per the investigation). Keep `changingPending` reset + `ghostSwitchScriptComplete()`.
- REFACTOR — none expected.

**Spec / done:** No code path unloads the incoming/current ghost during a switch; the outgoing ghost
is unloaded exactly once (by `setGhost`). Existing `setGhostNull_unloadsGhostNativeResources` and all
other tests stay green.

---

## Task 3 — `NanidroidService` must not tear down shared rendering state (R7-B part 1)

**Why:** `NanidroidService` is a background updater, not a rendering host. Its `onDestroy` nulls the
shared engine's ghost/views, freezing whichever real host (overlay/in-app) is active.

**Files:** `app/src/main/java/com/cattailsw/nanidroid/NanidroidService.kt`

**TDD:**
- RED — Robolectric test (new `NanidroidServiceTeardownTest.kt`, `@RunWith(RobolectricTestRunner)`):
  `onDestroy_doesNotClearSharedEngineGhost()`. Inject a test `SScriptRunner` via
  `SScriptRunner.setTestInstance(...)` (construct it with `StandardTestDispatcher`s as other tests
  do), `setGhost(testGhost)` on it. Build the service with Robolectric
  (`Robolectric.buildService(NanidroidService::class.java)`), drive it to `destroy()`, then assert the
  shared runner still holds the ghost (reflect the engine's `g`, mirroring existing reflection-based
  tests). Remember to `setTestInstance(null)` in teardown.
- GREEN — remove the `runner?.let { setGhost(null); stopClock(); clearMsgQueue(); clearViews() }`
  block from `NanidroidService.onDestroy`. Keep `mHandler.removeMessages(...)` and
  `serviceScope.cancel()` (its own resources).
- REFACTOR — `runner` field may become assignment-only; leave as-is if still used by the update tasks.

**Spec / done:** `NanidroidService.onDestroy` does not touch shared engine ghost/views; its own
handler/scope are still cleaned up. Existing tests green.

---

## Task 4 — Host attachment refcount replaces the `isRunning` flag (R7-B part 2)

**Why:** Only ONE rendering host should drive the engine at a time, but transitions overlap. A
host-attachment refcount makes teardown happen exactly when the LAST host detaches — fixing the
reverse-direction freeze (overlay destroyed while in-app composed), eliminating the non-volatile
`isRunning` race, and giving a single correct teardown path.

**Files:** `SScriptRunner.kt`, `OverlayMascotService.kt`, `MainScreen.kt` (`InAppMascotView`).

**Design / contract:**
- Add to `SScriptRunner`:
  - `fun attach()` — increments an active-host count (thread-safe).
  - `fun detach(): Boolean` — decrements; when the count reaches 0, performs the engine teardown
    (`setGhost(null)`, `stopClock()`, `clearMsgQueue()`, `clearViews()`) and returns `true`;
    otherwise returns `false`. Count must never go below 0 (clamp).
  - Use a `synchronized` block (or `AtomicInteger` with the teardown guarded so decrement-to-zero
    and teardown are atomic) so concurrent attach/detach are correct. The count is **instance**
    state on the singleton.
- `OverlayMascotService`: `attach()` in `onCreate`; in `onDestroy` call
  `if (runner.detach()) { LayoutManager.getInstance(this).clearViews() }` and keep the overlay-view
  removal + `stopForeground`. **Remove** the `@JvmStatic var isRunning` flag and its assignments.
- `InAppMascotView`: `attach()` in the `DisposableEffect` body (on enter); in `onDispose` call
  `if (runner.detach()) { LayoutManager.getInstance(context).clearViews() }`. **Remove** the
  `if (!OverlayMascotService.isRunning)` guard.
- After removing `isRunning`, confirm no other references remain (`android studio find-usages`).

**TDD (in `ScriptEngineTest.kt` or new `HostRefcountTest.kt`, unit-level on `SScriptRunner`):**
Construct `SScriptRunner` with `StandardTestDispatcher`s (as the existing reflection tests do) and
register via `setTestInstance` if helpful; assert teardown via reflection on the engine `g` field
and the runner's `sakuraRef` (existing tests already reflect these).
- RED:
  - `attachThenDetach_tearsDownAtZero()`: `setGhost(g)`, `setViews(...)`, `attach()`, then `detach()`
    returns `true` and the ghost is null + view refs cleared.
  - `twoAttach_singleDetach_doesNotTearDown()`: `attach(); attach();` set ghost/views; `detach()`
    returns `false` and ghost/views are retained. A second `detach()` returns `true` and tears down.
  - `detachBelowZero_isClampedAndDoesNotThrow()`: `detach()` with count 0 returns `false`/no-op,
    does not throw, count stays 0.
- GREEN — implement `attach()/detach()` per the contract.
- REFACTOR — keep the four teardown calls in one private helper invoked by `detach()`.

**Spec / done:** teardown runs exactly once when the last host detaches; never while another host is
attached; `isRunning` flag fully removed; all three hosts behave correctly (overlay+in-app via
refcount, NanidroidService never attaches). Full suite green.

---

## Definition of done (whole plan)
- All four tasks landed test-first, each with new failing→green tests.
- `./gradlew :app:testDebugUnitTest` green; no `isRunning` references remain.
- Native unload is idempotent and single-sourced; no path unloads the incoming ghost.
- Cross-host teardown only at refcount zero; `NanidroidService` no longer tears down rendering state.
