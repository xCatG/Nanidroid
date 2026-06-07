# Phase 2 Remediation Brief — `ScriptEngine.kt` / `SScriptRunner.kt`

Handoff to the implementer. An independent review (3 review agents + 2 Gemini subagents) found Phase 2 (commits `1fc6f58`, `8b1c09b`) to be a real architectural improvement — `StateFlow` UI, a `Channel`-serialized queue that removed the original cross-thread races, working clock idempotency, a clean touch fix — but **not shippable yet**.

Fix the items below **test-first** (write the failing test, then the fix), in this order. Keep `./gradlew test` green throughout. **Do not start Phase 3 (Hilt)** — these are all Phase 2 corrections. This brief mirrors the "Phase 2 — Remediation Checklist" in `engine_modernization_tdd_plan.md`.

> Search code with `android studio find-usages` / `analyze-file`, not grep.

---

## Blockers

### R1 — `runBlocking` on the main thread
- **Where:** `ScriptEngine.getStringValueFromShiori` (`ScriptEngine.kt:695`) uses `runBlocking(engineDispatcher)`; caller is a Compose click handler (`MainScreen.kt:254`).
- **Why it's wrong:** blocks the UI thread on the single engine thread → the exact ANR Phase 2 set out to remove, plus a latent main↔engine deadlock.
- **Fix:** `suspend fun getStringValueFromShiori(id: String): String? = withContext(engineDispatcher) { g?.getStringFromShiori(id) }`; call it from `rememberCoroutineScope().launch { … }` at the call site.
- **Test:** value is returned without blocking Main; the call site is inside a coroutine.

### R2 — `\!` user-input hangs the whole engine
- **Where:** `openUserInputBox` sets `paused=true`; `executeScript` suspends on `_pausedFlow.first{!it}`; `run()` blocks on `job.join()`. `MainScreenViewModel.onInputSubmit` calls only `doUserInput` — nothing calls `resumeEvt()` (only `onInputCancel` does).
- **Why it's wrong:** on submit the paused script never resumes and the queued `OnUserInput` reply never plays; the blocking `job.join()` freezes the whole engine.
- **Fix:** on input submit, call `resumeEvt()` (or `clearMsgQueue()`), mirroring the choice path.
- **Test:** pause on `\!`, submit input, assert the engine resumes and the `OnUserInput` reply script plays.

---

## Major / correctness

### R3 — idle-reset + balloon-hide lost on normal completion
- **Where:** `run()`'s drain branch calls bare `cb?.stop()` instead of the engine `stop()` (`ScriptEngine.kt:203-208`).
- **Why it's wrong:** after a normal `\e`, the balloon never hides and the 5s surface reset is never scheduled (only the `clearMsgQueue` touch/choice path triggers it). Regresses the "Idle Surface Reset Timeout" feature.
- **Fix:** call the engine `stop()` on drain; guard against double-`stop()` when the drain was caused by `clearMsgQueue` cancelling `scriptJob`.
- **Test:** after an `\e`-terminated script, balloon hidden; advancing 5s virtual time resets surfaces to `0`/`10`.

### R4 — idle-reset race clobbers a new script
- **Where:** `run()` never calls `cancelResetTimeout()` when a new message starts.
- **Why it's wrong:** a `resetSurfaceJob` scheduled by a prior `stop()` can fire mid-playback and yank surfaces to `0`/`10`.
- **Fix:** call `cancelResetTimeout()` at the start of each message in `run()` (or first thing in `reset()`).
- **Test:** queue a second script inside the 5s window; assert its `\s[n]` surface is not overwritten.

### R5 — JNI thread-pinning
- **Where:** `engineDispatcher = Dispatchers.Default.limitedParallelism(1)` (`SScriptRunner.kt:13`).
- **Why it's wrong:** `limitedParallelism(1)` guarantees mutual exclusion but **not** a pinned thread — across each `delay()`/`yield()` a JNI call may resume on a different pool thread, which can crash the Satori/Kawari C++ engines (process globals / TLS / per-thread `JNIEnv`). (Corrects the original plan's `limitedParallelism(1)` recommendation.)
- **Fix:** `private val engineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "NanidroidJniThread") }.asCoroutineDispatcher()`. Also satisfies Phase 5 **A4**.
- **Test:** two sequential JNI calls separated by a `delay` record the **same** thread identity.

---

## Leaks

### R6 — `MainScreenViewModel` leaked via uncleared callback
- **Where:** an anonymous `UICallback` (strong implicit outer ref) is registered on the process-singleton and never cleared.
- **Fix:** `override fun onCleared() { super.onCleared(); SScriptRunner.getInstance(getApplication()).setUICallback(null) }`.
- **Test:** `onCleared` nulls the engine's UICallback.

### R7 — Ghost retained on teardown
- **Where:** no host calls `setGhost(null)`; the singleton keeps the heavy `Ghost` (bitmaps + native SHIORI handle) alive.
- **Fix:** `OverlayMascotService.onDestroy` and `InAppMascotView.onDispose` call `setGhost(null)` and stop the engine's jobs (interim until Phase 3 ownership).

---

## Tests (the suite currently overstates confidence — fix before claiming done)

### R8 — actually test the off-Main property
- 12/13 engine tests inject the **same** `testDispatcher` for main and engine, so `withContext(engineDispatcher)` is a no-op hop. Add a test with a **distinct** engine dispatcher asserting Shiori ran on a thread `!==` the collector thread (identity, not name-substring). Drop the flaky real `delay(200)` in `shioriRequest_runsOffMainDispatcher` for a latch/`CompletableDeferred`.

### R9 — restore the assertions that were dropped/loosened
- `testChoiceWithRunner`: assert the **full** expected balloon string (`"abcde"` → `"abcdechosen!"`), not substrings.
- `keroTouch_doesNotClearQueueMidScript`: asserts the wrong balloon and doesn't isolate the property — set `OnMouseClick`→`""` and assert the pre-queued script survives, on `bKero`.
- `testCallback`: restore the dropped negative branch (no callback registered ⇒ not invoked).
- `TapClassifier`: add boundary cases (`delta==300`⇒DOUBLE, `301`⇒SINGLE).

---

## Cleanup

### R10 — dead/no-op surface & micro-perf
- Remove the **16 `println("DEBUG ENGINE…")`** statements in `ScriptEngine.kt` (they ship to production, spam logs, leak script content).
- Remove or `@VisibleForTesting`/document the misleading no-ops: `run()`, `setNoWaitMode`, `cancelResetTimeout`, `getScriptEngine` (+ its needless `@OptIn(InternalCoroutinesApi)`).
- Remove dead `doMouseWheel`/`doMouseMove` + `TYPE_WHEEL`/`TYPE_MOVE` branches (no producer).
- Stop gating drain-completion on the racy `msgQueue.isEmpty`; track outstanding work explicitly. Add `@OptIn(ExperimentalCoroutinesApi)` where `Channel.isEmpty`/`limitedParallelism` are used.
- Avoid per-char `currentScript.substring(charIndex)` in the parse loop (use `matcher.find(charIndex)` / region) to cut GC.
- Idiom nits: `c.digitToInt()`, `sb.clear()`, `bSakuraId != "-1"`.

---

## Definition of done
R1–R7 fixed with new failing→green tests, R8–R10 applied, `./gradlew test` green, and the off-Main property genuinely asserted.
