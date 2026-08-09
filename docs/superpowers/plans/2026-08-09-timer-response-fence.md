# Timer Response Fence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent a stale timer GET response from playing after an intervening runner interaction (#202).

**Architecture:** `SScriptRunner` owns a synchronized eligibility generation. The timer GET captures it, then admits its 200 response only if the runner is still idle, the target remains pinned, and that generation did not change.

**Tech Stack:** Kotlin, JUnit 4, Gradle JVM tests.

## Global Constraints

- Follow Red → Green → constrained Refactor TDD, observing every focused test fail first.
- Preserve timer cadence, notify-mode behavior, and all #267 interaction contracts.
- Keep #202 separate from #201 and #213.
- Simplify only the timer branch into one named eligibility decision.

---

### Task 1: Add deterministic stale-response regressions

**Files:**
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerBootDispatchTest.kt`

**Interfaces:** Reuse `dispatchClockTickForTesting()` and the existing raw-response fake from `timerUsesSleepInclusiveClockHoursAndPlaysOnlyIdleGetResponses`.

- [ ] **Step 1: Write Red test**

Add `timerGetDoesNotPlayAfterAnInterveningTalkReturnsIdle`. During `OnSecondChange` GET, the fake starts and finishes `\\hIntervening\\e`, then returns `Value: \\hStaleTimer\\e`; assert only `Intervening` renders.

- [ ] **Step 2: Run Red**

Run `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.SScriptRunnerBootDispatchTest.timerGetDoesNotPlayAfterAnInterveningTalkReturnsIdle`.

Expected: FAIL because old pre/post `canTalk` snapshots both return true.

- [ ] **Step 3: Add preservation tests**

Add `timerGetPlaysWhenIdleEligibilityNeverChanges` and `timerGetStillDropsWhenInterveningTalkRemainsActive` using the same fixture.

- [ ] **Step 4: Run all three tests**

Expected before implementation: only the completed-intervening-talk test fails.

### Task 2: Add the eligibility-generation fence

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`
- Test: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerBootDispatchTest.kt`

**Interfaces:** Add private `runtimeModeGeneration: Long` and private `timerResponseIsEligible(target: Ghost, capturedGeneration: Long): Boolean`.

- [ ] **Step 1: Implement minimal Green behavior**

Increment the generation at existing locked transitions that invalidate an idle timer request: playable talk start/queue, pending input/action publication or claim, passive transitions, and ghost/session replacement or clear. Capture `wasIdle` plus generation before GET. Replace the old post-response condition with this single helper:

```kotlin
private fun timerResponseIsEligible(target: Ghost, capturedGeneration: Long): Boolean = synchronized(this) {
    runtimeModeSnapshot().canTalk && runtimeModeGeneration == capturedGeneration && isPinnedDialogueGhost(target)
}
```

Parse the response only when `wasIdle && timerResponseIsEligible(target, capturedGeneration)`.

- [ ] **Step 2: Run Green**

Run `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.SScriptRunnerBootDispatchTest`.

Expected: PASS, including current passive and ghost-update timer tests.

- [ ] **Step 3: Simplify**

Use intention-revealing local names `wasIdle` and `capturedGeneration`; retain one named eligibility predicate and do not change clock bucket arithmetic.

- [ ] **Step 4: Re-run tests and commit**

Run the Task 2 command, then stage `SScriptRunner.kt` and `SScriptRunnerBootDispatchTest.kt` and commit `fix: fence stale timer responses`.

### Task 3: Verify and review

- [ ] Run `./gradlew.bat testDebugUnitTest lint` and `git diff --check`; expect success.
- [ ] Run `adb devices -l`; run connected tests only with an API 31–37 device, otherwise record unavailable.
- [ ] Obtain independent review focused on generation mutation sites, pinned ghost preservation, and unchanged timer cadence.
