# Implementation Plan: Engine Modernization (Test-Driven)

This plan addresses the findings from the adversarial review of the `modernize-nanidroid` branch and the rejected Gemini Compose-migration proposal. The Compose/Hilt/Navigation **surface** is done; the **engine** underneath (`SScriptRunner` + the SHIORI/JNI bridge) is still the legacy main-thread `Handler` machine. This plan modernizes the engine **test-first**: every behavioral change lands as a failing test (Red), a minimal implementation (Green), then a cleanup (Refactor).

> [!IMPORTANT]
> **Ordering rationale.** The View→Compose rendering swap is the *last* step, not the first. Ownership and threading are the structural foundation; correctness quick-wins go first because they are low-risk, independently shippable, and they harden the test harness for the larger refactors. Do not start Phase 4 (rendering) until Phases 1–3 are green.

> [!NOTE]
> **Tooling.** Use the `android` CLI for code search and docs (`android studio find-usages`, `android studio analyze-file --project Nanidroid <path>`, `android docs search`), not grep. Run unit tests with `./gradlew test`; native/build verification with `./gradlew assembleDebug` + APK inspection.

---

## Test Harness Foundations (Phase 0)

The existing JVM suite (`app/src/test/.../SSParserTest.kt`) drives `SScriptRunner` via `setNoWaitMode(true)`, which collapses the `Handler` loop into synchronous recursion, and observes behavior through `DummySakuraView` / `DummyKeroView` / `DummyBalloon` subclasses. We extend, not replace, this harness.

#### [ADD] `app/src/test/.../support/FakeShiori.kt`
- A `Shiori` test double returning scripted responses for given event IDs (e.g. `mapOf("OnFirstBoot" to "\\h\\s[0]hi\\e")`), recording every `request()` call **and the thread/dispatcher it ran on**. Enables asserting "JNI never runs on Main" without the native library.
- **Injection path (verified):** the engine never calls `Shiori` directly — it calls `g.doShioriEvent()` → `Ghost.doShioriEvent` → `shiori.request(String)` (note `Shiori.request` is `String`→`String`; the `ByteArray` marshalling lives *below* `Shiori`, in the JNI impls). So tests must build a `Ghost` whose `shiori` field is the `FakeShiori` and call `runner.setGhost(g)`. The dispatcher-recording only becomes meaningful after Phase 2 moves the call off-Main.

#### [ADD] `app/src/test/.../support/RecordingUiCallback.kt` and view fakes
- Promote the inner `Dummy*View`/`DummyBalloon` classes from `SSParserTest` into shared test fixtures so every engine test reuses them.
- A `CountingUIEventCallback` that records `(type, x, y, collId)` tuples for touch tests.

#### [MODIFY] `app/build.gradle.kts` + `gradle/libs.versions.toml`
- `kotlinx.coroutines.test` (1.10.2) and `mockk`/`robolectric` are already present.
- **Add an explicit `implementation(libs.kotlinx.coroutines.core)`** (and a `coroutines-core` alias in the TOML) *before* building the engine on coroutines. Today `kotlinx-coroutines-core` is only resolved **transitively** (1.9.0, via androidx lifecycle/compose `-android`); Phase 2 main-code use of `delay`/`withContext`/`StateFlow`/`Channel` compiles now but is fragile — an unrelated dependency bump could drop or downgrade it.

---

## Phase 1 — Correctness quick-wins (independent, ship first)

### 1A. `\q` selection must pause the engine and stop dumping choice text

**Bug:** `handle_selection()` (`SScriptRunner.kt:561`) always returns `false` (confirmed by `android studio analyze-file`: *"Method 'handle_selection()' always returns 'false'"*). Callers expect `true` to break the parse loop, and unlike `openUserInputBox` it never sets `paused = true`. Result: the choice dialog shows but the engine keeps parsing and dumps the choice labels into the balloon, and `MainScreenViewModel.onSelectionSelect` resumes an engine that was never paused.

- **Red** — `app/src/test/.../SelectionPauseTest.kt`. NB: `paused` is `private` with no getter, so assert it **behaviorally**, not directly:
  - `choice_pausesRunner_andDoesNotDumpLabelsIntoBalloon()`: feed `"\\0abcde\\q[fgh,id1]ijk\\q[lmno,id2]\\e"`; assert the balloon shows only `"abcde"` (pre-choice text), **not** `"abcdefghijklmno"`, and that `StatusCallback.stop()` did **not** fire and no post-choice text arrived (the proxies for "paused").
  - `choiceSelect_discardsPausedScript_andFiresOnChoiceSelect()`: after `doOnChoiceSelect(<id>)`, assert a `FakeShiori` `OnChoiceSelect` request fired with the chosen **id** (the *second* `\q[label,id]` group — e.g. `id1`/`id2`, not the label). Do **not** assert "resume": `doOnChoiceSelect` calls `clearMsgQueue()` → `stop()` first, so the paused script is **discarded**, not continued. Assert the prior script's remaining text never reaches the balloon.
  - Note: the existing `testChoiceWithRunner` asserts the **old buggy** behavior (`dispText == "abcdefghijklmno"`); update it to the corrected expectation as part of this change.
- **Green** — in `handle_selection()`: after `ucb?.showUserSelection(...)`, set `paused = true` and `return true`, mirroring `openUserInputBox`. Reconcile the full corrected contract explicitly: **pause on `\q`, then discard-and-fire-`OnChoiceSelect` on select** (the engine does not resume the pre-choice script). Consider adding a private `@VisibleForTesting` `isPaused()` accessor to make the pause assertion direct.
- **Refactor** — extract the `paused = true; return true` idiom shared with `openUserInputBox` into one helper; document the "every handler returns true to break the parse loop" contract.

### 1B. Touch events must not storm SHIORI or wipe the queue on every motion sample

**Bug:** `SakuraView.onTouchEvent` (`SakuraView.kt:224`) fires `mUCB.onHit(TYPE_DOUBLE_CLICK, …)` for **every** `MotionEvent` (no `action` filter), and `cbKero.onHit` (`SScriptRunner.kt:815`) calls `clearMsgQueue()` unconditionally. One tap = many `OnMouseDoubleClick` events + repeated mid-sentence queue wipes. Single/wheel/move branches are dead code.

#### [MODIFY] `SakuraView.kt`
> The single-vs-double-tap distinction is **not** reliably unit-testable through `GestureDetector` under Robolectric (double-tap detection depends on real inter-tap timing delivered via an internal `Handler`/`ViewConfiguration` timeout, requiring `shadowOf(Looper.getMainLooper()).idleFor(...)` and synthesized `eventTime`s). So split the logic from the gesture source and test them separately.
- **Red** — `app/src/test/.../TouchEventTest.kt` (Robolectric):
  - `onlyActionUp_emitsExactlyOneOnHit()`: dispatch `ACTION_DOWN`→`ACTION_MOVE`→`ACTION_UP` to a `SakuraView` with a `CountingUIEventCallback`; assert exactly **one** `onHit`, on `ACTION_UP`. (`SakuraView` is already instantiable under Robolectric — the existing `Dummy*View` subclasses prove it.)
  - Tap-classification is tested as a **pure function** (below), not via two synthetic taps in one test.
- **Red (pure)** — `TapClassifierTest.kt`: an injectable `classifyTap(now, lastTapTime, doubleTapTimeout): TapType` returns `DOUBLE` when `now - lastTapTime <= timeout`, else `SINGLE`. Assert both branches with explicit timestamps — no looper, no `GestureDetector`.
- **Green** — gate emission on `ACTION_UP`; feed `event.eventTime` into `classifyTap(...)` so `cbSakura`/`cbKero`'s existing `TYPE_SINGLE_CLICK`/`TYPE_DOUBLE_CLICK` branches become reachable. Add a `performClick()` override (also flagged by `analyze-file`). (A `GestureDetector` is an option for production polish, but keep the *decision* in the pure classifier so it stays testable.)
- **Refactor** — collapse the duplicated coordinate-mapping math (`SakuraView.kt:229-238`) into one `mapToSurfaceCoords()` and unit-test it directly (pure function).

#### [MODIFY] `SScriptRunner.kt`
- **Red** — `keroTouch_doesNotClearQueueMidScript()`: with a running multi-line script queued, deliver a Kero single-click; assert the queue/`msg` are **not** cleared (only `OnMouseClick` is dispatched).
- **Green** — remove the unconditional `clearMsgQueue()` from `cbKero.onHit`; if a clear is desired for a specific interaction, scope it to that event type explicitly.

---

## Phase 2 — Move the engine off the main thread

**Bug:** `loopHandler`/`clockHandler` are both `Handler(Looper.getMainLooper())` (`SScriptRunner.kt:105,117`). The 1 Hz clock and every script step call blocking JNI (`Kawari.requestFromJNI`) and O(W·H) bitmap color-keying (`ShellSurface.createTransparentBmp`) on the UI thread — canonical ANR/jank setup (`android docs`: *"Advanced Coroutines on Android"*, *"Find the unresponsive thread in an ANR stack dump"*).

**Strategy:** replace the dual `Handler` state machine with a single owning coroutine. The script-wait `sendEmptyMessageDelayed(RUN, waitTime)` becomes `delay(waitTime)`; the SHIORI/JNI call runs in `withContext(engineDispatcher)`; UI mutations are emitted as immutable state and applied on `Dispatchers.Main`. Inject the dispatcher so tests use `StandardTestDispatcher` and virtual time.

#### [ADD] `ScriptEngine` (coroutine-based) alongside `SScriptRunner`
- **Red** — `app/src/test/.../ScriptEngineTest.kt` using `runTest`:
  - `waitTag_suspendsForVirtualTime()`: `"\\habc\\w5def\\e"` advances virtual time by `5 * WAIT_UNIT` between segments (`advanceTimeBy`); assert ordering of emitted balloon text vs. time.
  - `shioriRequest_runsOffMainDispatcher()`: with a `FakeShiori` recording its dispatcher, assert no `request()` ran on the Main dispatcher.
  - `cancellation_stopsClockAndInFlightWork()`: cancel the engine scope; assert the per-second loop stops and no further `FakeShiori` requests occur.
  - Port the existing parse assertions (surface change, speak, animation, ignore-tags) from `SSParserTest` to prove behavioral parity.
- **Dispatcher-injection rule (per `android docs` "Testing Kotlin coroutines"):** virtual-time skipping (`advanceTimeBy`/`advanceUntilIdle`) only works if `delay()` runs on the **injected** `TestDispatcher`'s scheduler — a real `withContext(Dispatchers.Default)` boundary will *not* skip delays. So `waitTag_suspendsForVirtualTime` must run `delay(waitTime)` on the injected engine dispatcher. And `shioriRequest_runsOffMainDispatcher` cannot be proven if Main and the engine share one `StandardTestDispatcher`; inject a **distinct, named/recording** dispatcher for `engineDispatcher` (separate from the `Dispatchers.Main` test override) and have `FakeShiori` record `Thread.currentThread()`/dispatcher identity.
- **Green** — implement `ScriptEngine(shiori, engineDispatcher = <single-thread confined>)` exposing `suspend fun run()` and a `tick` loop; the queue becomes a `Channel<String>` (Phase 2B); UI is a `StateFlow<MascotUiState>`. **Dispatcher choice:** use a single-threaded confined dispatcher (`Dispatchers.Default.limitedParallelism(1)`), not bare `Default`. The SHIORI interpreter is CPU-bound *and* does dict file I/O / HTTP, and the native engine state is process-global — confining every native call to one thread doubles as the Phase 5 **A4** safety fix. In tests this is replaced by the injected `TestDispatcher`.
- **Refactor (DECIDED: replace, not adapt)** — once parity is proven, **delete** the `Handler` machinery from `SScriptRunner`: remove `RUN`/`STOP`/`INC_CLOCK` message plumbing, the two main-looper `Handler`s, `@Volatile isRunning`, and the `no_wait_mode` recursion (`SScriptRunner.kt:209-228`, also a stack-overflow risk on long scripts). `SScriptRunner` either disappears into `ScriptEngine` or remains only as a parser/state holder with no scheduling.
  > **Test migration (required by the replace decision):** the **8** existing tests that drive the engine through `setNoWaitMode(true)` synchronous recursion — `testSakuraSpeak`, `testSakuraSpeakNormal`, `testKeroSpeak`, `testIgnoreCommands`, `testSurfaceChangeSakura`, `testAnimation`, `testCallback`, `testChoiceWithRunner` — are **ported** to `ScriptEngineTest` using `runTest` + the injected `TestDispatcher` (with `advanceUntilIdle()` replacing the recursion). They are not kept on the old API. The remaining pure-regex tests in `SSParserTest` are unaffected.

### 2B. Replace the static `ConcurrentLinkedQueue` with a `Channel`
- **Bug:** `mMsgQueue` is a process-static `companion` field (`SScriptRunner.kt:37`) shared across hosts/sessions; the poll-and-reschedule logic exists only because the queue can't suspend.
- **Red** — `engine_suspendsWhenQueueEmpty_resumesOnSend()`: assert the engine parks (no busy-loop) on an empty queue and resumes when a message is sent.
- **Green** — `Channel<String>` owned by the engine instance; `addMsgToQueue` becomes `trySend`/`send`; `clearMsgQueue` drains + cancels the current job.

### 2C. Clock idempotency + boot-once
- **Bug:** `startClock()` never `removeMessages(INC_CLOCK)` first (`SScriptRunner.kt:233`). The duplicate-clock *stacking* is specifically the **overlay** path — `OverlayMascotService.kt:145` calls `startClock()` with no preceding `stopClock()`; the in-app path already guards with `stopClock()` before `startClock()` (`MainScreen.kt:409`). Separately, `InAppMascotView` re-runs `setGhost/startClock/run` on **every** `ON_RESUME` (`MainScreen.kt:391`), and `startClock()`→`doBoot()` re-fires `OnBoot`/`OnFirstBoot` (incrementing `createCount`).
- **Red (engine unit test):** `clockStartedTwice_firesOnSecondChangeOncePerSecond()` — make the clock idempotent at the engine layer; this is `runTest`-able.
- **Red (UI-lifecycle test, separate harness):** `attachOnResume_doesNotRebootGhost()` asserts `OnFirstBoot` fires once across two `ON_RESUME`s. **This is not an engine unit test** — the reboot logic lives in the `InAppMascotView` Composable, so it needs a Robolectric Compose/`LifecycleEventObserver` host. Either file it as a UI test, **or** (preferred) move the boot-once gate out of the Composable into the engine/coordinator so it becomes unit-testable.
- **Green** — make clock start idempotent (single coroutine; second start is a no-op); split "attach views" (resume-safe) from "boot ghost" (one-shot, keyed by ghost id), with the gate owned by the engine/coordinator rather than the Composable.

---

## Phase 3 — Single ownership (retire the process singletons)

**Bug:** `SScriptRunner` and `LayoutManager` are process singletons (`SScriptRunner.getInstance`/`_self`). `find-usages` confirms **both** `OverlayMascotService.setupOverlay()` and `MainScreen.InAppMascotView` drive the same instance; the overlay's `onDestroy` → `clearViews()`/`stopClock()` can kill the still-visible in-app mascot. The singleton also holds strong refs to `g` (a `Ghost` + native SHIORI handle), `ucb`, `cb` that are never cleared.

> [!IMPORTANT]
> **Prerequisite — wire Hilt first (it is NOT wired today).** Hilt is on the classpath (plugin + deps in `build.gradle.kts:6,96-101`) but **completely unwired**: there is no `@HiltAndroidApp` Application, `AndroidManifest.xml`'s `<application>` has no `android:name`, and there is no `@AndroidEntryPoint`/`@HiltViewModel`/module anywhere in `app/src/main`. Before any `@Inject`/`@Singleton` coordinator can exist, add (as the first commits of Phase 3):
> 1. `class NanidroidApplication : Application()` annotated `@HiltAndroidApp`.
> 2. `android:name=".NanidroidApplication"` on the manifest `<application>`.
> 3. `@AndroidEntryPoint` on `MainActivity` **and** `OverlayMascotService` (a `Service` is a supported Hilt entry point).
> 4. `@HiltViewModel` + `@Inject constructor` on `MainScreenViewModel` (it is currently `AndroidViewModel(application)`; switching changes how it gets context, and the Compose call site must move to `hiltViewModel()`).
> 5. A `@Module @InstallIn(SingletonComponent::class)` providing the coordinator.
> Each of these is a small, independently verifiable step (build + run); do them before the coordinator work below.

#### [ADD] `MascotEngineCoordinator` (single-owner arbiter), Hilt-provided
- **Red** — `app/src/test/.../OwnershipTest.kt`:
  - `inAppAndOverlay_cannotRunConcurrently()`: acquiring the engine for the overlay while the in-app host holds it either hands off cleanly or is rejected — never silently shares view refs.
  - `release_clearsGhostAndCallbacks()`: after release, `g`/`ucb`/`cb` are null and `Ghost.unload()` was called (assert via `FakeShiori.terminate`/mockk verify).
  - `releaseRacingAttach_nullsLosingHostViewRefs()`: simulate host A losing the grant while host B attaches; assert A's view refs are nulled regardless of ordering. **This test must exist before the WeakReference removal below.**
- **Green** — introduce a coordinator that owns one `ScriptEngine` instance and grants it to exactly one host at a time; convert `SScriptRunner`/`LayoutManager` from `companion _self` to instances obtained from the coordinator. **Scope it `@InstallIn(SingletonComponent::class)` / `@Singleton`** — *not* `ServiceComponent`/`@ServiceScoped`, which would re-create the per-host split this phase exists to remove. Remove the unused `mCtx` field (`SScriptRunner.kt:21`).
- **Refactor** — drop the `WeakReference` view juggling (it was a band-aid for the singleton) **only after** the deterministic clear-on-release + race test above is green; with a process singleton, the weak refs are currently the only thing preventing a detached View from leaking, and `toggleOverlay` can start/stop the overlay independently of the Activity lifecycle (so a View can outlive or underlive the grant).

---

## Phase 4 — Rendering / jank (after the engine is sound)

### 4A. Off-thread bitmap decode + transparency, with a bounded cache
- **Bug:** `ShellSurface.createTransparentBmp` (`ShellSurface.kt:711`) decodes + scans every pixel + allocates a second bitmap on the UI thread; `getSurfaceDrawable`'s `opt.outHeight/outWidth` "resize" is a **no-op** (those are output fields) and `surfaceDrawable` is cached so `resize()` never re-decodes.
- **Red** — `SurfaceLoaderTest.kt` (JVM/Robolectric): `colorKey_replacesTopLeftPixel_withTransparent()` on a small fixture PNG; `downscale_honorsTargetSize()` (asserts decoded dimensions actually shrink via `inSampleSize`); `cache_isBoundedAndEvictsOnGhostSwitch()`.
- **Green** — extract a pure `SurfaceLoader`/`suspend fun loadSurfaceBitmap(...)` (on `Dispatchers.Default`) that does decode + color-key + correct `inSampleSize` downscaling; back it with an LRU cache released on ghost switch (`android docs`: *"Caching bitmaps"* / *"Loading large bitmaps efficiently"*).
- **Refactor** — `ShellSurface` becomes a data holder; views/Composables receive ready `Bitmap`s from state.

### 4B. (Later) Compose rendering — Canvas layering, not per-frame bitmaps
- Migrate the in-app host to a stateless Composable that collects `StateFlow<MascotUiState>` and composites layers on a Compose `Canvas` (the one genuinely good idea from the Gemini "Approach 2"); **avoid** Approach 1's per-frame combined-bitmap allocation.
- For the **overlay** host: it is a `WindowManager` `Service` with no `ViewModelStoreOwner`/lifecycle. If kept on Compose, it requires a hand-wired `ComposeView` with `setViewTreeLifecycleOwner` / `…SavedStateRegistryOwner` / `…ViewModelStoreOwner` + a `Recomposer`. Simpler interim: keep the overlay on the existing `AndroidView`-wrapped Views and migrate only the in-app host first.
- **Test** — Roborazzi screenshot tests for surface render + balloon layout; Compose UI test for tap → collision-id routing. **Setup needed:** Roborazzi deps/plugin are present (`build.gradle.kts:7,116-118`) but there is **no** `RoborazziRule`, no screenshot test, and no Roborazzi config yet — and `buildConfig = false` (`build.gradle.kts:40`), which some Roborazzi output-dir setups rely on. Add a first-time harness (a `RoborazziRule` + Robolectric graphics mode) as a sub-step before writing the screenshot assertions.

---

## Phase 5 — Native / JNI + build hardening

> [!NOTE]
> Build-config items below are not unit-testable; verify via build + APK inspection and (optionally) a Gradle assertion in CI.

#### [MODIFY] `app/build.gradle.kts`
- **Pin the NDK** (`A7`): add `ndkVersion = "<r28+>"` (latest stable per `android studio version-lookup`, e.g. `29.0.14206865`). 16 KB page-size support is a hard Play requirement for Android 15+; AGP 9.0.1 makes it likely-by-default but **unverified** while the NDK is unpinned.
- **Verify** with `./gradlew assembleDebug`, then check **two different things**: (1) `.so` ELF LOAD-segment alignment via `llvm-objdump -p <lib>.so | Select-String LOAD` — every LOAD must be `align 2**14` (16 KB), not `2**13`/`2**12`; and (2) zip alignment via `zipalign.exe -v -c -P 16 4 <apk>` (Windows path under `<sdk>/build-tools/37.0.0/`). These verify different layers; objdump is the authoritative per-library check. APK Analyzer's Alignment column is the cheapest manual look. Add to CI. (build-tools 37.0.0 satisfies the docs' ≥35.0.0 requirement.)

#### [MODIFY] `shiori/JNIShiori.kt`, `Kawari.kt`, `SatoriPosixShiori.kt`, and the cpp bridges
- **Byte-array marshalling (`A2`) — scope corrected**: the charset hazard (Modified UTF-8 vs Shift_JIS) applies to request/response **content**, which already crosses as `ByteArray` (`Kawari.requestFromJNI(ByteArray)`, `SatoriPosixShiori.requestFromJNI2(ByteArray)`) — those are correct. The only live `jstring` request path is the base `JNIShiori.requestFromJNI(String)`, which both concrete engines **override away**, so it is effectively dead (clean it up, but it is not a live content bug).
  - **Do NOT convert `load(path)` to `ByteArray` for charset reasons** — it is a filesystem path in the ASCII app sandbox, not Shift_JIS content. More importantly, `SatoriPosixShiori_load` `malloc`s a fresh copy of `pPath` precisely because the native C `load()` **`free()`s it immediately** (`SakuraDLLHost.cpp:15`). Any refactor here MUST keep handing native a freshly `malloc`'d/`strcpy`'d buffer; passing a `GetByteArrayElements`/`GetPrimitiveArrayCritical` pointer into a function that frees it is a **double-free / heap corruption**. (Kawari's `load` passes a `std::string` by value — safe either way.)
  - **Red** — `ShioriCharsetTest.kt`: round-trip a Shift_JIS request/response through a `FakeShiori`/`EchoShiori` and assert non-ASCII (Japanese) survives intact; assert `modResponseWithCharSet` honors the declared `Charset:` (extend existing `ShioriResponseTest`). This covers the *content* path; the `load()` ownership contract is verified by build + run, not a JVM test.
  - **Green** — keep request/response on bytes end-to-end; convert at the Kotlin boundary. Leave `load()`'s malloc-copy contract intact.
- **Remove dead `external` decls (`A3`)**: `JNIShiori.getModuleNameFromJNI`/`terminateFromJNI` have **no** backing native symbols (confirmed: neither `.cpp` defines them) → latent `UnsatisfiedLinkError`. `find-usages` shows `Shiori.getModuleName`/`terminate` have **zero** project callers (cleanup happens via `unloadShiori()`→`unload()`, which *is* backed). When deleting the externals, also remove the `getModuleName()`/`terminate()` members from the `Shiori` interface and the `JNIShiori` overrides — otherwise a different latent `UnsatisfiedLinkError` stays reachable.
- **Single-thread native confinement (`A4`) — co-dependent with Phase 3**: the C state is process-global and shared across *both* engines and *both* hosts (`SO_HANDLE h` at `kawari_jni.cpp:21`; `extern Satori gSatori` → single def `satori.cpp:15`). Confining to one `engineDispatcher` thread prevents concurrency *within* a session, but two hosts (in-app + overlay) holding native engines still collide on the same globals. So A4 only fully holds **once Phase 3 enforces single ownership** — land them together, not A4 alone (`android docs`: *"JNI tips"* — minimize threads touching JNI).

---

## Verification Plan

### Automated tests (`./gradlew test`)
- **Decided strategy: replace + port.** Phase 2 deletes the Handler machinery, so the 8 `no_wait_mode`-driven tests are *ported* to `ScriptEngineTest` (`runTest` + injected `TestDispatcher`), not kept on the old API. The pure-regex tests in `SSParserTest` and all other existing tests stay green. `testChoiceWithRunner` is updated for the corrected 1A behavior as part of its port.
- New suites: `SelectionPauseTest`, `TouchEventTest`, `TapClassifierTest`, `ScriptEngineTest`, `OwnershipTest`, `SurfaceLoaderTest`, `ShioriCharsetTest`.
- Coroutine tests use `runTest` + `StandardTestDispatcher`; inject a **distinct** recording dispatcher for `engineDispatcher` (separate from the `Dispatchers.Main` override) so "JNI off Main" is provable and virtual-time `delay` stays deterministic.
- Add an explicit `kotlinx-coroutines-core` dependency before Phase 2 (currently transitive-only).

### Prerequisites that gate later phases (do not skip)
- **Phase 3 is blocked until Hilt is wired** (Application + manifest `android:name` + `@AndroidEntryPoint` on MainActivity/OverlayMascotService + `@HiltViewModel` + a `SingletonComponent` module). See the Phase 3 prerequisite box.
- **Phase 4B screenshot tests are blocked until Roborazzi is configured** (rule + Robolectric graphics mode; deps already present).

### Build & native (`./gradlew assembleDebug`)
- Native libraries build; APK `.so` segments are 16 KB-aligned; pinned `ndkVersion` recorded.

### Manual emulator verification
- Grant overlay permission: `adb shell appops set com.cattailsw.nanidroid SYSTEM_ALERT_WINDOW allow`.
- Confirm: tapping the mascot fires interaction **once** (no storm) and choices pause/resume correctly; switching ghosts releases the previous engine (no double clock, no leaked session); enabling the overlay while the app is foreground does not corrupt the in-app mascot; surface changes no longer hitch the UI thread.

---

## Phase 2 — Remediation Checklist (post-implementation review)

Phase 2 landed (commits `1fc6f58`, `8b1c09b`) and is a real architectural win — `StateFlow` UI, a `Channel`-serialized queue that removed the original cross-thread races, working clock idempotency, and a clean touch fix. But an independent multi-reviewer pass (3 agents here + Gemini's 2 subagents) found it **not yet shippable**. Fix in this order, **test-first** (write the failing test, then fix). Severity: **B**=blocker, **M**=major, **L**=leak, **T**=test, **C**=cleanup.

- [x] **R1 (B) — `runBlocking` on the main thread.** `ScriptEngine.getStringValueFromShiori` (`ScriptEngine.kt:695`) uses `runBlocking(engineDispatcher)`; the caller is a Compose click handler (`MainScreen.kt:254`), so it blocks the UI thread on the single engine thread — the exact ANR Phase 2 set out to remove, with a latent main↔engine deadlock. **Fix:** make it `suspend fun … = withContext(engineDispatcher){ g?.getStringFromShiori(id) }` and call from `rememberCoroutineScope().launch`. **Test:** value is returned without blocking; call site is in a coroutine.
- [x] **R2 (B) — `\!` user-input hangs the whole engine.** `openUserInputBox` sets `paused=true`; `executeScript` suspends on `_pausedFlow.first{!it}` and `run()` blocks on `job.join()`. `MainScreenViewModel.onInputSubmit` calls only `doUserInput` — nothing calls `resumeEvt()` (only `onInputCancel` does), so submit never resumes and the queued reply never plays. **Fix:** on input submit, call `resumeEvt()` (or `clearMsgQueue()`), mirroring the choice path. **Test:** pause on `\!`, submit input, assert the engine resumes and the `OnUserInput` reply script plays.
- [x] **R3 (M) — idle-reset / balloon-hide lost on normal completion.** `run()`'s drain branch calls the bare callback `cb?.stop()`, not the engine `stop()` (`ScriptEngine.kt:203-208`), so after a normal `\e` the balloon stays visible forever and the 5s surface reset is never scheduled (only the `clearMsgQueue` touch/choice path triggers it). **Fix:** call the engine `stop()` on drain; guard against double-`stop()` when the drain was caused by `clearMsgQueue` cancelling `scriptJob`. **Test:** after an `\e`-terminated script, balloon hidden; advancing 5s virtual time resets surfaces to `0`/`10`.
- [x] **R4 (M) — idle-reset race clobbers a new script.** `run()` never calls `cancelResetTimeout()` when a new message starts, so a `resetSurfaceJob` scheduled by a prior `stop()` can fire mid-playback and yank surfaces to `0`/`10`. **Fix:** call `cancelResetTimeout()` at the start of each message in `run()` (or first thing in `reset()`). **Test:** queue a second script inside the 5s window; assert its `\s[n]` surface is not overwritten.
- [x] **R5 (M) — JNI thread-pinning.** `engineDispatcher = Dispatchers.Default.limitedParallelism(1)` guarantees mutual exclusion but **not** a pinned thread — across each `delay()`/`yield()` a JNI call may resume on a different pool thread, which can crash the Satori/Kawari C++ engines (process globals / TLS / per-thread `JNIEnv`). This corrects the original plan's `limitedParallelism(1)` recommendation. **Fix:** `private val engineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "NanidroidJniThread") }.asCoroutineDispatcher()` (also satisfies Phase 5 **A4**). **Test:** two sequential JNI calls separated by a `delay` record the **same** thread identity.
- [x] **R6 (L) — `MainScreenViewModel` leaked via uncleared callback.** An anonymous `UICallback` (strong implicit outer ref) is registered on the process-singleton and never cleared. **Fix:** `override fun onCleared(){ super.onCleared(); SScriptRunner.getInstance(getApplication()).setUICallback(null) }`. **Test:** `onCleared` nulls the engine's UICallback.
- [x] **R7 (L) — Ghost retained on teardown.** No host calls `setGhost(null)`; the singleton keeps the heavy `Ghost` (bitmaps + native SHIORI handle) alive. **Fix:** `OverlayMascotService.onDestroy` and `InAppMascotView.onDispose` call `setGhost(null)` and stop the engine's jobs (interim until Phase 3 ownership).
- [x] **R8 (C) — remove the 16 `println("DEBUG ENGINE…")`** statements in `ScriptEngine.kt`'s hot loop (they ship to production, spam logs, and leak script content).
- [x] **R9 (T) — harden the tests (they currently overstate confidence):**
  - Off-Main is not actually tested — 12/13 engine tests inject the **same** `testDispatcher` for main and engine. Add at least one test with a **distinct** engine dispatcher asserting Shiori ran on a thread `!==` the collector thread (identity, not name-substring); drop the flaky real `delay(200)` in `shioriRequest_runsOffMainDispatcher` for a latch/`CompletableDeferred`.
  - `testChoiceWithRunner` was silently loosened — assert the **full** expected balloon string (`"abcde"` → `"abcdechosen!"`), not substrings.
  - `keroTouch_doesNotClearQueueMidScript` asserts on the wrong balloon and doesn't isolate the property — set `OnMouseClick`→`""` and assert the pre-queued script survives, on `bKero`.
  - Restore `testCallback`'s dropped negative branch (no callback registered ⇒ not invoked).
  - Add `TapClassifier` boundary cases (`delta==300`⇒DOUBLE, `301`⇒SINGLE).
- [x] **R10 (C) — dead/no-op cleanup & micro-perf.** Remove or `@VisibleForTesting`/document the now-misleading no-ops (`run()`, `setNoWaitMode`, `cancelResetTimeout`, `getScriptEngine` + its needless `@OptIn(InternalCoroutinesApi)`); remove dead `doMouseWheel`/`doMouseMove` + `TYPE_WHEEL`/`TYPE_MOVE` branches; avoid `currentScript.substring(charIndex)` per char in the parse loop (use `matcher.find(charIndex)` / region) to cut GC; idiom nits (`c.digitToInt()`, `sb.clear()`, `bSakuraId != "-1"`). Also add `@OptIn(ExperimentalCoroutinesApi)` where `Channel.isEmpty`/`limitedParallelism` are used, and stop gating drain-completion on `msgQueue.isEmpty` (racy with concurrent `trySend`).

**Done = all of R1–R7 fixed with new failing-then-green tests, R8–R10 applied, `./gradlew test` green, and the off-Main property genuinely asserted.**

---

## Out of scope / explicitly not doing
- Rewriting the SHIORI parser logic (`parseMsg`) — it is correct; only its side effects are relocated to state emission.
- Swapping `java.util.Vector`/`Hashtable` purely for style — defer until the owning file is touched, and only after Phase 2 establishes the threading model (their incidental synchronization currently masks cross-thread access).
- Following the Gemini `MascotViewModel.kt` sample code — it is functionally broken (frozen animation, wrong-thread "background" assembly, dead touch routing, impossible `SavedStateHandle` bitmap claim) and is superseded by this plan.
