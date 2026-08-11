# Ghost Update Terminal Event Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve a headless ghost update's terminal SHIORI event until the exact ghost is attached to the runner.

**Architecture:** Add a nullable, versioned terminal-event payload to the existing durable operation record and expose exact-handle compare-and-set helpers on the supervisor. The worker defers only undeliverable terminal events; runner attachment attempts delivery only for the matching canonical ghost root and ID, clearing the payload only after dispatch succeeds.

**Tech Stack:** Kotlin, SharedPreferences durable store, WorkManager, JUnit 4.

## Global Constraints

- Bind persistence and delivery to the existing operation ID, attempt ID, canonical root, and ghost ID.
- Never dispatch to a current ghost that does not exactly match the persisted event.
- Keep persistence after a process restart and clear only after successful dispatch.
- No changes to network retry, staging cleanup, WorkManager identity, or Compose behavior.

---

### Task 1: Persist and claim exact terminal events

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperation.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/durable/SharedPreferencesDurableOperationStore.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperationSupervisor.kt`
- Test: `src/test/java/com/cattailsw/nanidroid/durable/DurableOperationSupervisorTest.kt`

**Interfaces:**
- Produces: `GhostUpdateTerminalEvent(ghostId, canonicalRoot, name, references)`.
- Produces: supervisor helpers that defer and clear a payload only when the current record equals the exact handle and binding.

- [ ] **Step 1: Write failing persistence tests**

```kotlin
assertTrue(supervisor.deferTerminalEvent(handle, binding, event))
assertEquals(event, supervisor.snapshot().single().pendingGhostUpdateEvent)
assertTrue(supervisor.clearTerminalEvent(handle, binding, event))
```

- [ ] **Step 2: Run the focused test and verify the missing helper failure.**

- [ ] **Step 3: Add the nullable record field, backward-compatible encoding, and exact CAS helpers.**

- [ ] **Step 4: Re-run the focused test and verify it passes.**

### Task 2: Defer headless worker events and replay only after matching attachment

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateWorker.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`
- Test: `src/test/java/com/cattailsw/nanidroid/durable/GhostUpdateRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1's event payload and exact persistence helpers.
- Produces: `deliverPendingTerminalEvent` that calls its dispatch lambda only for a matching canonical root/ID and clears only after `true`.

- [ ] **Step 1: Write failing delivery tests**

```kotlin
assertTrue(deliverPendingTerminalEvent(supervisor, handle, binding, ghostId, root) { event -> calls += event; true })
assertFalse(deliverPendingTerminalEvent(supervisor, handle, binding, otherGhostId, root) { true })
assertEquals(listOf(event), calls)
```

- [ ] **Step 2: Run the focused test and verify the helper is absent.**

- [ ] **Step 3: Make the worker defer only terminal events rejected by the bound sink; invoke delivery after runner attachment.**

- [ ] **Step 4: Re-run focused tests and verify duplicate/mismatched/restart coverage.**

### Task 3: Verify and document the focused change

**Files:**
- Modify: `docs/superpowers/specs/2026-08-08-ghost-update-terminal-events-design.md` only if implementation changes the approved contract.

- [ ] **Step 1: Run `git diff --check`.**
- [ ] **Step 2: Run the focused JVM test classes.**
- [ ] **Step 3: Run `./gradlew.bat testDebugUnitTest`; if environment configuration prevents it, record the exact blocker.**
- [ ] **Step 4: Commit with `fix: recover headless ghost update events`.**
