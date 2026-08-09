# Ghost Update Network Retries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retry transient ghost-update transport failures without changing durable identity.

**Architecture:** Replace nullable network opens with typed outcomes, map retryable outcomes to the existing interruption result, and require network connectivity on the existing request.

**Tech Stack:** Kotlin, WorkManager, JUnit 4.

## Global Constraints

- Preserve exact operation ID, attempt ID, and WorkManager binding on retry.
- Keep user cancellation distinct from system/transport interruption.
- Treat only definitive absence as not found.

---

### Task 1: Type network outcomes

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateRepository.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateWorker.kt`
- Test: `src/test/java/com/cattailsw/nanidroid/durable/GhostUpdateRepositoryTest.kt`

- [ ] Write a failing test proving a retryable open becomes `Interrupted` and preserves the running record.
- [ ] Run it to observe the old terminal failure.
- [ ] Add typed network outcomes and the minimal repository mapping.
- [ ] Run the focused tests.

### Task 2: Constrain and verify worker replay

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateWorker.kt`
- Test: `src/test/java/com/cattailsw/nanidroid/durable/GhostUpdateRepositoryTest.kt`

- [ ] Add a failing request-builder test for `NetworkType.CONNECTED` and retry identity.
- [ ] Add the constraint without changing request identity.
- [ ] Run focused tests and `./gradlew.bat testDebugUnitTest`.
