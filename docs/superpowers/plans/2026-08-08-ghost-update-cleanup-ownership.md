# Ghost Update Cleanup Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve exact cleanup ownership when user cancellation cannot remove pre-commit ghost-update staging.

**Architecture:** Reuse the existing `PREPARED` journal and its cancelled rollback recovery. The repository writes that journal only after deletion fails, using the request's operation, attempt, and WorkManager identities; recovery already validates and deletes the matching transaction root.

**Tech Stack:** Kotlin, JUnit 4 local JVM tests, existing durable-operation journal.

## Global Constraints

- Keep the PR limited to issue #187; do not alter #183/#184 draft branches.
- Preserve exact operation, attempt, WorkManager UUID, cleanup scope, live-tree state, and stop semantics.
- Do not add dependencies or journal formats/phases.

---

### Task 1: Prove durable cancellation cleanup ownership

**Files:**
- Modify: `src/test/java/com/cattailsw/nanidroid/durable/GhostUpdateRepositoryTest.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateRepository.kt`

**Interfaces:**
- Consumes: `GhostUpdateRequest`, `GhostUpdateFileOperations`, `GhostUpdateJournalIo`.
- Produces: a `PREPARED` journal with the request identities if `deleteTree(transactionRoot)` fails during user cancellation.

- [ ] **Step 1: Write the failing test**

```kotlin
val result = fixture.repository(fileOperations = failingDelete).run(fixture.request()) { cancelled }
assertEquals(GhostUpdateResult.Cancelled, result)
assertTrue(File(fixture.transactionRoot(), GhostUpdateJournalStore.FILE_NAME).isFile)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.durable.GhostUpdateRepositoryTest`

Expected: failure because cancellation removes no durable evidence after its staging deletion fails.

- [ ] **Step 3: Write minimal implementation**

```kotlin
if (!fileOperations.deleteTree(transactionRoot)) {
    persistPreparedCleanupJournal(transactionRoot, request)
}
return GhostUpdateResult.Cancelled
```

The helper creates only the existing canonical candidate/backup paths and writes an empty-file `PREPARED` journal.

- [ ] **Step 4: Run the focused test and recovery regression tests**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.durable.GhostUpdateRepositoryTest`

Expected: pass, including exact cancelled recovery cleanup.

- [ ] **Step 5: Simplify and commit**

Keep the new helper local to pre-journal cleanup and reuse current journal validation/recovery. Commit only issue #187 files.

## Self-Review

- Scope coverage: the test and helper cover failed user-cancellation cleanup, exact journal identity, and replay cleanup; existing system-interruption tests retain retry behavior.
- Placeholder scan: none.
- Type consistency: helper accepts `GhostUpdateRequest` and returns `GhostUpdateResult`; it writes `GhostUpdateJournal` already consumed by repository recovery.
