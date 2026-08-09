# Archive Intent Runner Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure a recreated Activity evaluates archive intents against the retained runner's actual interaction mode (#201).

**Architecture:** Make the process-retained `SScriptRunner` available before cold-start intent ingress. Preserve the existing later renderer/callback/ghost attachment sequence and archive state machine.

**Tech Stack:** Kotlin, JUnit 4, Android instrumentation only when a device is available.

## Global Constraints

- Strict TDD: observe Red before production code.
- Preserve archive content-URI validation, restored-delivery deduplication, warm `onNewIntent` semantics, and ghost reservation attachment.
- Make no timer (#202) or input presentation (#213) changes.
- Simplify only the archive-ingress eligibility seam; do not reorder unrelated startup work.

---

### Task 1: Prove retained passive runner bypasses early archive ingress

**Files:**
- Modify: `src/test/java/com/cattailsw/nanidroid/ArchiveIntentStateTest.kt` or create a focused pure ingress test beside it.
- Modify if needed: `src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt`.

**Interfaces:** Test the same `GuardedAction.IMPORT_INSTALL` decision used by `Nanidroid.handleIncomingIntent`; retained passive runner must reject while normal retained runner admits a granted content archive.

- [ ] **Step 1: Write Red tests**

Extract only if needed a package-visible ingress decision function that accepts `SScriptRunner?`/a runtime-mode snapshot. Add `recreatedPassiveRunnerRejectsArchiveIngress` and `recreatedIdleRunnerAcceptsArchiveIngress`; assert the former never produces `ArchiveIntentState.Reception.Pending` and the latter does.

- [ ] **Step 2: Verify Red**

Run the exact new JVM test. Expected: passive case fails because `runner` is null during early `onCreate` and `allows(IMPORT_INSTALL)` returns true.

### Task 2: Resolve the retained runner before cold intent handling

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Test: the Task 1 test.

**Interfaces:** `onCreate` assigns `runner = SScriptRunner.getInstance(this)` immediately before `handleIncomingIntent(intent)`; later initialization must reuse that same field.

- [ ] **Step 1: Implement minimal Green behavior**

Move only the existing singleton lookup from below view/storage setup to directly before `handleIncomingIntent(intent)`. Remove the later duplicate assignment. Do not call `setGhostToRunner`, set UI callbacks, or create ghosts early.

- [ ] **Step 2: Verify Green**

Run the Task 1 tests plus `ArchiveIntentStateTest`. Expected: passive ingress is rejected, idle ingress remains pending/admitted, and archive state behavior remains unchanged.

- [ ] **Step 3: Constrained simplify**

If Task 1 extracted a decision seam, give it an ingress-specific name and keep all URI parsing in `ArchiveIntentAdapter`; otherwise retain the existing `allows` contract without broader edits.

- [ ] **Step 4: Commit**

Stage only the Nanidroid and focused test files; commit `fix: guard recreated archive intents`.

### Task 3: Verify and review

- [ ] Run `./gradlew.bat testDebugUnitTest lint` and `git diff --check`.
- [ ] Check `adb devices -l`; when an API 31–37 device exists, run the focused lifecycle instrumentation test or `connectedDebugAndroidTest`.
- [ ] Obtain independent review of startup ordering, retained runner mode semantics, archive validation, deduplication, and no startup-side-effect expansion.
