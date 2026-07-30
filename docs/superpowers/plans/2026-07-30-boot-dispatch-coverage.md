# Boot Dispatch Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `SScriptRunner` executable host-JVM coverage proving exactly one boot dispatch per runner lifecycle while preserving ghost-handoff behavior.

**Architecture:** Move the two lifecycle flags into a package-internal, Android-free `BootDispatchState`. `SScriptRunner` remains the owner of scheduling and SHIORI requests, while the state object decides whether a clock start is new and whether it may dispatch boot. A runner-level fake Ghost trace verifies that the state is wired to actual events.

**Tech Stack:** Kotlin, JUnit 4, Android Gradle Plugin host-JVM tests.

## Global Constraints

- Preserve the established production order: `setGhost()` precedes `startClock()` for initial launch and ghost replacement.
- The first clock start of a runner may dispatch boot; repeated starts and starts after `stopClock()` may not dispatch it again.
- A named replacement ghost receives `OnGhostChanged` and must not receive an additional boot when its clock starts.
- A distinct runner models app recreation and may dispatch boot once.
- Tests must be allowlisted in `characterizationTests`; do not broaden `unitTests.isReturnDefaultValues`.
- Follow red-green-refactor: run the new state test and observe its missing-symbol failure before adding production state code.

---

### Task 1: Extract and characterize boot-dispatch lifecycle state

**Files:**

- Create: `src/com/cattailsw/nanidroid/BootDispatchState.kt`
- Create: `test/jvm/com/cattailsw/nanidroid/BootDispatchStateTest.kt`
- Create: `test/jvm/com/cattailsw/nanidroid/SScriptRunnerBootDispatchTest.java`
- Modify: `src/com/cattailsw/nanidroid/SScriptRunner.kt:41-73`
- Modify: `build.gradle.kts:characterizationTests`

**Interfaces:**

- Produces: `internal class BootDispatchState` with `fun startClock(): Start`, `fun stopClock()`, `fun markBootDispatched()`, and `fun resetForNoGhost()`.
- Produces: `internal data class Start(val started: Boolean, val dispatchBoot: Boolean)`.
- Consumes: `SScriptRunner` calls `startClock()` before scheduling, calls `stopClock()` when removing clock messages, marks boot dispatched after a boot or named ghost handoff, and resets the boot state when the ghost is cleared.

- [x] **Step 1: Write the failing pure-state test**

```kotlin
@Test
fun firstStartDispatchesBoot_butRepeatedResumeAndGhostHandoffDoNot() {
    val state = BootDispatchState()
    assertEquals(BootDispatchState.Start(started = true, dispatchBoot = true), state.startClock())
    assertEquals(BootDispatchState.Start(started = false, dispatchBoot = false), state.startClock())
    state.markBootDispatched()
    state.stopClock()
    assertEquals(BootDispatchState.Start(started = true, dispatchBoot = false), state.startClock())
    state.stopClock()
    state.markBootDispatched()
    assertEquals(BootDispatchState.Start(started = true, dispatchBoot = false), state.startClock())
}

@Test
fun freshStateAfterAppRecreationMayDispatchBootOnce() {
    assertEquals(BootDispatchState.Start(started = true, dispatchBoot = true), BootDispatchState().startClock())
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `$env:ANDROID_HOME='C:\Users\yenchi\AppData\Local\Android\Sdk'; .\gradlew.bat testEmulatorUnitTest --tests com.cattailsw.nanidroid.BootDispatchStateTest --console=plain`

Expected: compilation fails because `BootDispatchState` does not exist.

- [x] **Step 3: Write the minimal state implementation and wire the runner**

```kotlin
internal class BootDispatchState {
    data class Start(val started: Boolean, val dispatchBoot: Boolean)
    private var clockStarted = false
    private var bootDispatched = false
    fun startClock(): Start {
        if (clockStarted) return Start(started = false, dispatchBoot = false)
        clockStarted = true
        return Start(started = true, dispatchBoot = !bootDispatched)
    }
    fun stopClock() { clockStarted = false }
    fun markBootDispatched() { bootDispatched = true }
    fun resetForNoGhost() { bootDispatched = false }
}
```

Replace `SScriptRunner` direct flag reads/writes with this object, preserving its existing clock scheduling, restore branch, and SHIORI event order.

- [x] **Step 4: Add a runner event-trace regression test**

Create a `RecordingGhost` that overrides `loadGhostInfo`, `incrementCreateCount`, `getGhostName`, `getCreateCount`, `getGhostId`, and `doShioriEvent`. Assert literal event traces for one initial boot across duplicate start and stop/resume, `OnGhostChanged` for a replacement without a second boot, and one `OnBoot` on a newly constructed runner. Use `new SScriptRunner(null)` per independent lifecycle and `stopClock()` in cleanup.

- [x] **Step 5: Allowlist and verify green**

Add both test paths to `characterizationTests`, then run:

`$env:ANDROID_HOME='C:\Users\yenchi\AppData\Local\Android\Sdk'; .\gradlew.bat testEmulatorUnitTest --tests com.cattailsw.nanidroid.BootDispatchStateTest --tests com.cattailsw.nanidroid.SScriptRunnerBootDispatchTest --console=plain`

Expected: lifecycle tests pass and the isolation verifier accepts exactly the two new sources.

- [x] **Step 6: Run the full host-JVM suite and commit**

Run: `$env:ANDROID_HOME='C:\Users\yenchi\AppData\Local\Android\Sdk'; .\gradlew.bat testEmulatorUnitTest --console=plain`

Expected: all host-JVM tests pass.

```bash
git add build.gradle.kts src/com/cattailsw/nanidroid/BootDispatchState.kt src/com/cattailsw/nanidroid/SScriptRunner.kt test/jvm/com/cattailsw/nanidroid/BootDispatchStateTest.kt test/jvm/com/cattailsw/nanidroid/SScriptRunnerBootDispatchTest.java docs/superpowers/plans/2026-07-30-boot-dispatch-coverage.md
git commit -m "test: cover boot dispatch lifecycle"
```