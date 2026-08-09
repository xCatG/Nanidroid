# Background NAR Progress Polling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move remote NAR polling and durable progress persistence from the main looper to a background scheduler without changing queue or recovery semantics.

**Architecture:** `DownloadManagerProgressObserver` retains its exact-handle runnable ownership map. Its default `NarProgressScheduler` becomes a daemon, single-thread scheduled executor; the existing injectable scheduler remains the deterministic seam for cancellation/race tests. Every poll reads bytes, reports the bound durable progress, and checks status on the scheduler thread; only the same current runnable may schedule a later poll.

**Tech Stack:** Kotlin, Android `DownloadManager`, `ScheduledExecutorService`, JUnit 4, existing durable operation supervisor/store fakes.

## Global Constraints

- Scope is GitHub issue #173 only; do not change the durable record schema, state table, queue UI, or backup policy.
- Preserve the 1,000 ms cadence, exact `OperationHandle` + `DownloadManager` binding fence, and stop/replacement behavior.
- Scheduler callbacks are ephemeral; process-death recovery remains repository reconciliation.
- Keep simplification local: remove the main-looper path and avoid duplicate scheduler implementations.
- Use TDD: each production behavior below begins with a focused failing JVM test.

---

### Task 1: Prove production polling is background-owned

**Files:**

- Modify: `src/test/java/com/cattailsw/nanidroid/install/NarDownloadRepositoryTest.kt` near the existing production stop-reconciliation thread test and fake download gateway.
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/DownloadManagerProgressObserver.kt` at `MainLooperProgressScheduler` and the observer default constructor.

**Interfaces:**

- Consumes: `NarProgressScheduler.post(task: Runnable, delayMillis: Long)` and `DownloadManagerProgressObserver.start(handle, downloadManagerId)`.
- Produces: a default scheduler whose gateway calls and `DurableOperationSupervisor.reportProgress` run on a named, non-caller daemon thread.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun productionRemoteProgressPollingRunsQueriesAndPersistenceOffTheCallerThread() {
    val completed = CountDownLatch(1)
    val caller = Thread.currentThread()
    val queriedOn = AtomicReference<Thread>()
    val storedOn = AtomicReference<Thread>()
    val observer = DownloadManagerProgressObserver(
        ThreadRecordingDownloadGateway(queriedOn, completed),
        ThreadRecordingSupervisor(storedOn),
    )

    observer.start(handle, 32L)

    assertTrue(completed.await(5, TimeUnit.SECONDS))
    assertNotEquals(caller, queriedOn.get())
    assertEquals(queriedOn.get(), storedOn.get())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.install.NarDownloadRepositoryTest.productionRemoteProgressPollingRunsQueriesAndPersistenceOffTheCallerThread`

Expected: FAIL because the current default `MainLooperProgressScheduler` posts work to the main looper rather than a background executor.

- [ ] **Step 3: Write minimal implementation**

```kotlin
private class BackgroundProgressScheduler : NarProgressScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nanidroid-download-progress").apply { isDaemon = true }
    }

    override fun post(task: Runnable, delayMillis: Long) {
        executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
    }

    override fun cancel(task: Runnable) {
        // Track and cancel the exact scheduled future for this task.
    }
}
```

Replace the default `MainLooperProgressScheduler()` with this scheduler, use a synchronized future map so cancellation/reposting cannot leave a later future untracked, and remove the `Handler`/ `Looper` imports and implementation.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.install.NarDownloadRepositoryTest.productionRemoteProgressPollingRunsQueriesAndPersistenceOffTheCallerThread`

Expected: PASS; recorded query and persistence threads are the same non-caller thread named `nanidroid-download-progress`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/cattailsw/nanidroid/install/DownloadManagerProgressObserver.kt src/test/java/com/cattailsw/nanidroid/install/NarDownloadRepositoryTest.kt
git commit -m "fix: poll NAR downloads off the main looper"
```

### Task 2: Fence cancelled and superseded callbacks

**Files:**

- Modify: `src/test/java/com/cattailsw/nanidroid/install/NarDownloadRepositoryTest.kt` near `FakeProgressScheduler`.
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/DownloadManagerProgressObserver.kt` in the scheduler and observer `start`/`stop` only if the test exposes an ownership gap.

**Interfaces:**

- Consumes: `NarProgressScheduler.cancel(task: Runnable)` and the observer’s per-handle `observations` identity map.
- Produces: cancelled/replaced callbacks cannot create a new scheduled poll, including when cancellation races with a completed poll.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun stoppedOrReplacedProgressObservationCannotRescheduleAfterItsPollRuns() {
    val scheduler = CapturingProgressScheduler()
    val observer = DownloadManagerProgressObserver(downloads, supervisor, scheduler)

    observer.start(handle, 32L)
    val stale = scheduler.takeNext()
    observer.stop(handle)
    stale.run()

    assertEquals(0, scheduler.pendingCount)
}
```

Keep the existing repeated-byte and terminal-row test to show that a still-owned in-progress observation retains exactly one pending callback.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.install.NarDownloadRepositoryTest.stoppedOrReplacedProgressObservationCannotRescheduleAfterItsPollRuns`

Expected: FAIL only if the implementation schedules after stop; if it already passes, extend it to a replacement race until it proves an actual missing ownership/future-tracking behavior before changing production code.

- [ ] **Step 3: Write minimal implementation**

```kotlin
synchronized(this) {
    if (observations[handle] !== observation) return@synchronized
    if (shouldContinue) scheduler.post(observation, POLL_INTERVAL_MILLIS)
    else observations.remove(handle)
}
```

Preserve this ownership check around every reschedule. If futures are tracked, replacement cancels the old future before retaining the new one; `cancel` removes/cancels the exact tracked future.

- [ ] **Step 4: Run focused observer tests to verify they pass**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.install.NarDownloadRepositoryTest.remoteProgressPollingContinuesAcrossRepeatedByteCountsUntilTerminalRow --tests com.cattailsw.nanidroid.install.NarDownloadRepositoryTest.stoppedOrReplacedProgressObservationCannotRescheduleAfterItsPollRuns`

Expected: PASS; live polls retain one callback, terminal/stopped/replaced polls retain none.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/cattailsw/nanidroid/install/DownloadManagerProgressObserver.kt src/test/java/com/cattailsw/nanidroid/install/NarDownloadRepositoryTest.kt
git commit -m "test: fence NAR progress polling callbacks"
```

### Task 3: Validate the issue-sized change

**Files:**

- Verify only: `src/main/kotlin/com/cattailsw/nanidroid/install/DownloadManagerProgressObserver.kt`
- Verify only: `src/test/java/com/cattailsw/nanidroid/install/NarDownloadRepositoryTest.kt`

- [ ] **Step 1: Run the focused repository unit suite**

Run: `./gradlew.bat testDebugUnitTest --tests com.cattailsw.nanidroid.install.NarDownloadRepositoryTest`

Expected: PASS with no test failures.

- [ ] **Step 2: Run the complete JVM suite and lint**

Run: `./gradlew.bat testDebugUnitTest lint`

Expected: PASS; no new Android lint findings.

- [ ] **Step 3: Inspect scope and simplify locally**

Run: `git diff --check; git diff -- src/main/kotlin/com/cattailsw/nanidroid/install/DownloadManagerProgressObserver.kt src/test/java/com/cattailsw/nanidroid/install/NarDownloadRepositoryTest.kt`

Expected: no whitespace errors, no main-looper scheduler remains, and no changes outside #173’s observer/tests/spec/plan.

- [ ] **Step 4: Commit any final verification-only adjustment**

```bash
git add src/main/kotlin/com/cattailsw/nanidroid/install/DownloadManagerProgressObserver.kt src/test/java/com/cattailsw/nanidroid/install/NarDownloadRepositoryTest.kt
git commit -m "test: verify background NAR polling"
```

