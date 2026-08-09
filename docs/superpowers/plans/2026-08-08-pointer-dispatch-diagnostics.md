# Pointer Dispatch Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the adaptive debug panel show a resolved pointer-event candidate separately from its actual runtime dispatch outcome.

**Architecture:** Keep the diagnostic value immutable and local to the existing debug package. The runner resolves the event candidate and dispatches it within the same live-session gate, then returns both facts to `Nanidroid` for the diagnostic record. The debug composable only renders that record with resource-backed labels; it never infers dispatch success.

**Tech Stack:** Kotlin, Jetpack Compose, Android string resources, JUnit 4, Compose instrumentation tests.

## Global Constraints

- Scope is GitHub issue #222 only; do not combine #219, #220, #223, #225, #229, or #259 work.
- Preserve pointer routing, runner call order, session gating, and SHIORI behavior.
- Resolve the candidate once inside the runner's live-session gate; return that same candidate with its actual dispatch outcome for diagnostics.
- Every user-visible outcome string belongs in `values/`, `values-ja/`, and `values-zh-rTW/` resources.
- Follow TDD: observe each new test fail before production implementation.
- Run `simplify` only on the #222 diff after tests are green; retain exact behavior.

---

### Task 1: Model the factual dispatch result

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/debug/DebugPanelState.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/compose/debug/DebugPanelStateTest.kt`

**Interfaces:**
- Consumes: a nullable resolved event name and the runner's nullable dispatch result.
- Produces: `PointerDispatchOutcome` and a `SurfacePointerDebugEvent` whose candidate and outcome cannot contradict each other.

- [ ] **Step 1: Write the failing model tests**

Add tests that call a new mapping function with these exact cases:

```kotlin
assertEquals(PointerDispatchOutcome.NOT_RESOLVED, pointerDispatchOutcome(null, null))
assertEquals(PointerDispatchOutcome.REJECTED, pointerDispatchOutcome("OnMouseClick", false))
assertEquals(PointerDispatchOutcome.ACCEPTED, pointerDispatchOutcome("OnMouseClick", true))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.compose.debug.DebugPanelStateTest`

Expected: compilation fails because `PointerDispatchOutcome` and `pointerDispatchOutcome` do not exist.

- [ ] **Step 3: Write minimal implementation**

In `DebugPanelState.kt`, add this internal enum and mapping:

```kotlin
internal enum class PointerDispatchOutcome { NOT_RESOLVED, REJECTED, ACCEPTED }

internal fun pointerDispatchOutcome(candidateEvent: String?, dispatchAccepted: Boolean?): PointerDispatchOutcome = when {
    candidateEvent == null -> PointerDispatchOutcome.NOT_RESOLVED
    dispatchAccepted == true -> PointerDispatchOutcome.ACCEPTED
    else -> PointerDispatchOutcome.REJECTED
}
```

Leave `SurfacePointerDebugEvent` unchanged in this task so its existing callers keep compiling. Task 2 replaces its ambiguous `eventName` field when it updates every caller in the same commit.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.compose.debug.DebugPanelStateTest`

Expected: all `DebugPanelStateTest` cases pass.

- [ ] **Step 5: Commit**

Run: `git add src/main/kotlin/com/cattailsw/nanidroid/compose/debug/DebugPanelState.kt src/test/java/com/cattailsw/nanidroid/compose/debug/DebugPanelStateTest.kt; git commit -m "feat: model pointer dispatch diagnostics"`

### Task 2: Capture and render actual dispatch state

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/debug/GhostDebugSurface.kt`
- Modify: `src/main/res/values/strings.xml`
- Modify: `src/main/res/values-ja/strings.xml`
- Modify: `src/main/res/values-zh-rTW/strings.xml`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/compose/debug/GhostDebugSurfaceTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SurfaceInteractionProtocolTest.kt`

**Interfaces:**
- Consumes: the candidate and outcome returned together by the runner's live-session-gated dispatch operation.
- Produces: one rendered candidate-event row and one localized dispatch-outcome row in every debug presentation.

- [ ] **Step 1: Write the failing Compose regression**

Update `debug_surface_shows_surface_input_and_shiori_data` to provide `candidateEvent = "OnMouseClick"` and `dispatchOutcome = PointerDispatchOutcome.REJECTED`; assert both `OnMouseClick` and `Rejected` are displayed after `performScrollTo()`. Add the same rejected fixture in `FULL_STAGE_MODAL` presentation and assert the rows after scrolling.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.debug.GhostDebugSurfaceTest`

Expected: compilation fails because the new record fields and outcome row do not exist.

- [ ] **Step 3: Write minimal implementation**

In this task, extend `SurfacePointerDebugEvent` with nullable `candidateEvent` and non-null `dispatchOutcome`, replacing `eventName`, and update every caller. Add a runner diagnostic dispatch result that contains its gated candidate and actual outcome without changing the Boolean contract of `dispatchSurfaceInteraction`. In `Nanidroid.kt`, call that runner operation once and record its returned facts afterward:

```kotlin
val result = runner?.dispatchSurfaceInteractionForDebug(effect)
lastPointerDebugEvent = effect.toPointerDebugEvent(
    candidateEvent = result?.candidateEvent,
    dispatchAccepted = result?.accepted,
)
```

In `GhostDebugSurface.kt`, render resource-backed labels for `Candidate event` and `Dispatch outcome`, plus resource-backed `Not resolved`, `Rejected`, and `Accepted` values. Add translations to all three resource files and render the empty-value resource when `candidateEvent` is null.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.debug.GhostDebugSurfaceTest`

Expected: `GhostDebugSurfaceTest` passes, including rejected-outcome assertions.

- [ ] **Step 5: Add the runner/session-gate regression**

Alongside the existing rejected-Kero interaction tests, establish a mouse-click candidate, take the existing session-gate rejection path through the diagnostic runner operation, and assert its returned candidate/outcome faithfully report the runner's gated resolution and rejection. Add a changing-capabilities test double proving the diagnostic candidate is the same candidate used by dispatch.

- [ ] **Step 6: Run relevant JVM tests**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionProtocolTest --tests com.cattailsw.nanidroid.compose.debug.DebugPanelStateTest`

Expected: both classes pass with no failures.

- [ ] **Step 7: Commit**

Run: `git add src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/main/kotlin/com/cattailsw/nanidroid/compose/debug/GhostDebugSurface.kt src/main/res/values/strings.xml src/main/res/values-ja/strings.xml src/main/res/values-zh-rTW/strings.xml src/androidTest/java/com/cattailsw/nanidroid/compose/debug/GhostDebugSurfaceTest.kt src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SurfaceInteractionProtocolTest.kt; git commit -m "fix: report pointer dispatch outcomes"`

### Task 3: Simplify and verify the focused issue

**Files:**
- Review only files changed by Tasks 1–2.

**Interfaces:**
- Consumes: green outcome, UI, and session-gate regression tests.
- Produces: the same behavior with clearer local ownership and no duplicate event resolution.

- [ ] **Step 1: Establish the behavior-preservation boundary**

The candidate resolves once; runner dispatch occurs at most once; a null candidate maps to `NOT_RESOLVED`, a false result to `REJECTED`, and a true result to `ACCEPTED`.

- [ ] **Step 2: Apply only safe local simplifications**

Inspect the #222 diff for duplicate capability lookup, repeated event resolution, and nested conditionals. Prefer named locals (`candidateEvent`, `dispatchAccepted`) and the single mapping function. Do not alter runner logic, interaction timing, or unrelated debug paths.

- [ ] **Step 3: Re-run focused checks after simplify**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.compose.debug.DebugPanelStateTest --tests com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionProtocolTest; ./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.debug.GhostDebugSurfaceTest; git diff --check master...HEAD`

Expected: all targeted tests and whitespace validation pass.

- [ ] **Step 4: Run final verification**

Run: `./gradlew.bat testDebugUnitTest; ./gradlew.bat assembleDebug; git status --short`

Expected: unit suite and debug build exit successfully; status contains only focused #222 changes or is clean after their commits.

- [ ] **Step 5: Commit simplify-only changes if any**

If Task 3 changes the diagnostic model or activity wiring, run: `git add src/main/kotlin/com/cattailsw/nanidroid/compose/debug/DebugPanelState.kt src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt; git commit -m "refactor: simplify pointer diagnostics"`. If Task 3 leaves the diff unchanged, create no extra commit.
