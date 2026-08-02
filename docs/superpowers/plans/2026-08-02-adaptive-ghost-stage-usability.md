# Adaptive Ghost Stage and Usability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved adaptive Sakura/Kero stage, reliable multimodal interaction and structured dialogue, usable debug tools, and recoverable durable operations, verified against real NAR packages and representative Android windows.

**Architecture:** Keep parsing, durable state, layout, geometry, and protocol behavior as pure Kotlin policies with narrow Android adapters. Compose measures the final stage once and publishes one immutable pixel transform per speaker; drawing, hit-testing, collision overlays, semantics, and diagnostics all consume that same transform. An application-scoped durable-operation supervisor owns progress and user-requested cancellation across DownloadManager, WorkManager, local copies, installs, and transactional ghost updates.

**Tech Stack:** Kotlin 2.3.21, Android API 31–37, Jetpack Compose with Material 3, AndroidX Window 1.5.1, WorkManager 2.11.2, Hilt 2.60.1 with KSP 2.3.10, JUnit 4, MockK, Compose UI testing, UI Automator 2.4.0, Compose Preview Screenshot Testing 0.0.1-alpha16, and JaCoCo.

## Global Constraints

- Use the current safe app window, never the physical display; reserve canonical app-bar height so chrome, IME, and debug visibility cannot reclassify the stage.
- `isWide` means `width >= height * 1.2`; tiny-wide is below `420 x 240 dp`, tiny-tall is below `240 x 320 dp`, compact-height landscape is at least `420 dp` wide and `240..<480 dp` high, and all remaining supported windows are standard/tall.
- Cap stage content at `960 dp`; use fixed physical Kero-left/Sakura-right lanes in LTR and RTL.
- Compact-height landscape is `Kero | Kero bubble over Sakura bubble | Sakura`, with `120 dp` outer-lane and `180 dp` center-lane minima; the transition to equal thirds is `540 dp`.
- Never crop, distort, or cross speaker lanes. Preserve the full authored canvas and shared scale, with a best-effort `96 dp` visible shorter-side floor and at most `2x` independent boost.
- Convert dp to px exactly once and share the resulting half-open `SurfaceTransformPx` between rendering, pointer routing, collision overlay, labels, and diagnostics.
- Preserve case-sensitive authored collision names at SHIORI `Reference4`; numeric collision IDs are diagnostic only. Support rectangle plus `collisionex` rect, ellipse, circle, and polygon; diagnose image-mask region and animation-scoped collisions.
- Route pointer input modal/debug, bubble actions/content/frame, named collision, generic canvas, then empty stage. Surface activation never toggles chrome; empty-stage activation never dispatches SHIORI.
- A touch tap sends exactly one event using the approved capability table. Mouse/pen/eraser primary clicks use the platform double-click window and emit exactly one click or double-click event.
- SHIORI hover/petting, `OnMouseWheel`, drag, right-click, long-press, enter/leave, image-mask collision regions, animation-scoped collisions, tertiary visible speakers, and Chromebook overlay click-through remain out of scope. Standard Compose mouse-wheel/trackpad scrolling remains enabled in bubbles, action surfaces, menus, and debug panels.
- Choice pop-outs and Material controls have at least `48 dp` targets. Exact authored collision geometry is not inflated; semantics custom actions provide the accessible alternative.
- A durable operation is stalled only after `30,000 ms` without a real phase or progress heartbeat. Offer `Keep waiting` and operation-specific `Stop operation`; never cancel automatically.
- Cancellation must be cooperative and idempotent. Fresh NAR install and network ghost update must never expose a partial published tree; update recovery completes or rolls back before boot.
- Keep minSdk `31`, compile/target SDK `37`, JUnit 4, and the repository's source-set conventions. Do not add a test coverage threshold.
- Do not commit third-party `.nar` archives. Corpus tests consume local files and publish generated reports under `build/reports/nar-corpus/`.
- Before every planned commit, stage only the paths named by that task, inspect `git diff --cached`, run `git diff --cached --check`, and confirm `git status --short`; the shown `git add` commands are path allowlists and must never absorb unrelated user work.

---

## Current-State Map

- `GhostStageLayoutPolicy` only scales down raw pixel sizes; `GhostPresentationStage` converts those pixel results to dp and creates separate coordinate calculations.
- `ComposeGhostStageHost.SurfaceNode` has a nested tap detector, dispatches only a double-click effect, and then calls `onSurfaceTap`, which also toggles chrome.
- `SurfaceReader` hardcodes Shift-JIS, cannot recover or expand real selectors, and aliases invalid comma tokens to surface `0`.
- `SurfaceCollision` is rectangle-only and `toSurfaceDefinition()` sorts away authored order.
- `SakuraScriptInteractionInterpreter` flattens choices into parallel label/ID lists and greedily parses input boxes.
- `NanidroidComposeShell` renders a horizontally scrolling button row plus a second debug row; `onAnimate()` and `onShowCollision()` are no-ops.
- NAR downloads and installs already have durable records, private staging, and cooperative `isStopped` checks. `GhostUpdateTask` is still an `AsyncTask` that deletes and renames live files one at a time.
- Tests use JUnit 4, MockK, Compose instrumentation, and API 31+ devices. No DI framework, WorkManager test artifact, screenshot source set, UI Automator, or coverage report is configured.
- The local corpus currently contains 23 NAR files: 2elf, tewire-sen, Yes Man, Big Red Button, Earthquake Rescue Duo, Haiidrate, Hareraiser, Kitsune no Ocha, LOBO, three Nanika Atsume packages, ten Snake/Otacon packages, The Petpet Puddle, and Watchdog Bancho.

## Planned File Boundaries

- `runtime/stage/`: stable window classification, lane policy, optical sizing, immutable transforms, and stage routing.
- `surface/`: decoded surface-source files, selector expansion, ordered collision shapes, diagnostics, and geometry hit-testing.
- `runtime/dialogue/`: ordered SakuraScript dialogue tokens, actions, capability discovery, passive state, and typed interaction effects.
- `durable/`: operation state/store/supervisor, transactional update journal, and Android cancellation adapters.
- `compose/stage/`: measured stage layout, balloons, collision overlay, semantics, and input modifier.
- `compose/debug/`: bounded diagnostic log and adaptive debug sheet/overlay/panel.
- `src/screenshotTest/`: deterministic screenshot-only fixtures and reviewed goldens.
- `scripts/`: host-side NAR corpus runner and visual-audit runner; neither copies archives into source control.

## Milestone 1 — Durable-work safety

### Task 1: Establish deterministic Android test infrastructure

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `gradle.properties`
- Modify: `src/main/AndroidManifest.xml`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/InstallNarWorker.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/di/PlatformClockModule.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/NanidroidTestRunner.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/DependencyInjectionSmokeTest.kt`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt`
- Create: `src/screenshotTest/kotlin/com/cattailsw/nanidroid/compose/ScreenshotHarness.kt`
- Create: `src/screenshotTestDebug/reference/`
- Create: `docs/testing.md`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: Existing `ComponentActivity` Compose tests and constructor-injected archive gateways.
- Produces: Hilt-enabled application/activity, `NanidroidTestRunner`, `ScreenshotHarness(content: @Composable () -> Unit)`, screenshot and JaCoCo Gradle tasks, and documented test commands.

- [ ] **Step 1: Write the failing infrastructure checks**

Add a small `@PreviewTest` sanity preview and an `@HiltAndroidTest` smoke test. The smoke test declares `HiltAndroidRule` as rule order 0, any Activity/Compose rule as order 1, calls `hiltRule.inject()`, injects this binding, and asserts `clock.nowMillis() >= 0`:

```kotlin
fun interface MonotonicClock { fun nowMillis(): Long }

@Module
@InstallIn(SingletonComponent::class)
object PlatformClockModule {
    @Provides fun monotonicClock(): MonotonicClock =
        MonotonicClock { SystemClock.elapsedRealtime() }
}
```

- [ ] **Step 2: Verify the checks fail before setup**

Run: `.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest validateDebugScreenshotTest`

Expected: the Hilt/screenshot symbols or tasks are missing; record that red result in the task notes.

- [ ] **Step 3: Configure the pinned test stack**

Add catalog/plugin entries for Hilt `2.60.1`, AndroidX Hilt `1.4.0`, KSP `2.3.10`, Material 3 Adaptive `1.2.0`, Window/Window Testing `1.5.1`, Work Testing `2.11.2`, UI Automator `2.4.0`, and screenshot testing `0.0.1-alpha16`. Enable `android.experimental.enableScreenshotTest=true`, apply `com.android.compose.screenshot`, Hilt, KSP, and JaCoCo, and add:

```kotlin
android {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    defaultConfig.testInstrumentationRunner =
        "com.cattailsw.nanidroid.NanidroidTestRunner"
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.compose.material3.adaptive)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.window.testing)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
```

Annotate `CatTailApplication` with `@HiltAndroidApp`, implement `Configuration.Provider`, and inject `HiltWorkerFactory`; annotate `Nanidroid` with `@AndroidEntryPoint`; use `@HiltWorker` plus assisted injection for new workers; and implement the test runner with `newApplication(..., HiltTestApplication::class.java.name, ...)`. In the main manifest, remove only the `androidx.work.WorkManagerInitializer` metadata from `androidx.startup.InitializationProvider` with `tools:node="remove"`, so the application provider supplies production configuration. WorkManager integration tests initialize one synchronous test instance with the intended worker factory and close/reset it between tests; never race production auto-initialization. Keep existing constructor interfaces as the preferred unit-test seams; Hilt replaces only application-scoped Android dependencies in connected tests.

- [ ] **Step 4: Add deterministic screenshot and coverage harnesses**

`ScreenshotHarness` must apply Nanidroid's `MaterialTheme`, a fixed background, visible corner sentinels that make blank/stale renders fail review, and no real network/filesystem state. Set `android.compose.screenshot.maxHeapSize=4g`. Configure JaCoCo to generate `build/reports/jacoco/testDebugUnitTestCoverage/` without a threshold. Document local, connected, screenshot, corpus, and full verification commands in `docs/testing.md`, and link it from `AGENTS.md`.

- [ ] **Step 5: Verify infrastructure and commit**

Run `.\gradlew.bat dependencies --configuration screenshotTestImplementation` first and require resolution of screenshot `0.0.1-alpha16`, which was reported by the Android CLI version lookup. Then run unit/connected tests, generate the sanity reference, open it for human inspection, and only then validate:

```text
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest
.\gradlew.bat updateDebugScreenshotTest
.\gradlew.bat validateDebugScreenshotTest jacocoTestReport
```

Expected: dependency resolution and every task PASS; the inspected sanity image is nonblank and contains both corner sentinels.

Commit:

```text
git add AGENTS.md build.gradle.kts gradle.properties gradle/libs.versions.toml docs/testing.md src/main/AndroidManifest.xml src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/main/kotlin/com/cattailsw/nanidroid/di src/main/kotlin/com/cattailsw/nanidroid/install/InstallNarWorker.kt src/androidTest/java/com/cattailsw/nanidroid/NanidroidTestRunner.kt src/androidTest/java/com/cattailsw/nanidroid/DependencyInjectionSmokeTest.kt src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt src/screenshotTest src/screenshotTestDebug/reference
git commit -m "test: establish adaptive UI test infrastructure"
```

### Task 2: Model durable operations and the 30-second supervisor

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperation.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperationStore.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/durable/SharedPreferencesDurableOperationStore.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperationSupervisor.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/durable/DurableOperationSupervisorTest.kt`
- Create: `docs/modernization/durable-operation-transition-table.md`

**Interfaces:**
- Consumes: `MonotonicClock` from Task 1.
- Produces: `OperationId`, `DurableOperationRecord`, `DurableOperationStore`, and `DurableOperationSupervisor` used by all long-running adapters.

- [ ] **Step 1: Write the state-transition table and failing clock tests**

The table must cover acceptance, external side effect, duplicate callback, process death before/after persistence, keep-waiting, stop request, terminal cleanup, and stale replay for each operation kind. Add tests with this fake:

```kotlin
class FakeMonotonicClock(var value: Long = 0L) : MonotonicClock {
    override fun nowMillis(): Long = value
}

@Test fun prompts_at_30000_without_cancelling() {
    supervisor.start(
        OperationHandle(OperationId("nar-1"), AttemptId(1)),
        OperationKind.NAR_INSTALL,
        "Extracting",
        8,
    )
    clock.value = 29_999
    assertFalse(supervisor.snapshot().single().showStallPrompt)
    clock.value = 30_000
    assertTrue(supervisor.snapshot().single().showStallPrompt)
    assertEquals(OperationStatus.RUNNING, supervisor.snapshot().single().status)
    verify(exactly = 0) { cancellation.cancel(any()) }
}
```

Also test real progress versus repeated status, phase change, `Keep waiting`, idempotent operation-specific stop, recreation, `CancelRequested`, terminal cleanup, and a second stalled `Stopping...` observation window.

- [ ] **Step 2: Run the supervisor tests red**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.DurableOperationSupervisorTest"`

Expected: FAIL because the durable model does not exist.

- [ ] **Step 3: Implement the pure transition model**

Use these stable types:

```kotlin
@JvmInline value class OperationId(val value: String)
@JvmInline value class AttemptId(val value: Long)
data class OperationHandle(val operationId: OperationId, val attemptId: AttemptId)
sealed interface ExternalJobBinding {
    data class DownloadManager(val id: Long) : ExternalJobBinding
    data class WorkManager(val uuid: String) : ExternalJobBinding
}
enum class OperationKind { REMOTE_NAR, LOCAL_NAR, NAR_INSTALL, GHOST_UPDATE }
enum class OperationStatus { RUNNING, CANCEL_REQUESTED, COMPLETED, FAILED, CANCELLED }
data class OperationProgress(val phase: String, val completed: Long)
data class DurableOperationRecord(
    val id: OperationId,
    val attemptId: AttemptId,
    val kind: OperationKind,
    val externalJob: ExternalJobBinding?,
    val progress: OperationProgress,
    val status: OperationStatus,
    val showStallPrompt: Boolean,
    val diagnostics: String? = null,
    val previousExternalJob: ExternalJobBinding? = null,
)
interface DurableOperationStore {
    fun read(): List<DurableOperationRecord>
    fun putIfAbsent(record: DurableOperationRecord): Boolean
    fun compareAndSet(handle: OperationHandle, expected: OperationStatus, updated: DurableOperationRecord): Boolean
}
fun interface OperationCancellation {
    fun cancel(handle: OperationHandle, binding: ExternalJobBinding)
}
```

`NarDownload.id` is the canonical `OperationId` for archive work; the durable store extends that existing record instead of maintaining a second unsynchronized archive state machine. Every retry increments `AttemptId`, binds the exact DownloadManager row or Work UUID, and requires compare-and-set on `(OperationId, AttemptId)` before callbacks mutate state. `SharedPreferencesDurableOperationStore` is the source for non-archive updates, stored in `durable_operations_v1`; it writes accepted state before invoking an external side effect. `DurableOperationSupervisor` keeps `lastProgressAt` only in memory, persists status/progress/cancel requests, starts a fresh 30-second observation window for restored running work, immediately resumes cancellation for restored bound `CANCEL_REQUESTED` records, and cancels pending requests as soon as their binding is persisted. Repeating the same phase and completed value is not a heartbeat. Test cancel-then-retry, a late callback from the prior attempt, stale worker replay, and external-job rebinding.

- [ ] **Step 4: Run the focused and full durable tests**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.durable.*"`

Expected: PASS with no timing sleeps.

- [ ] **Step 5: Commit**

```text
git add docs/modernization/durable-operation-transition-table.md src/main/kotlin/com/cattailsw/nanidroid/durable src/test/java/com/cattailsw/nanidroid/durable
git commit -m "feat: supervise stalled durable operations"
```

### Task 3: Connect NAR download, copy, and install cancellation

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/NarDownload.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadStore.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadRepository.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/InstallNarWorker.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/install/StageLocalNarWorker.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/install/DownloadManagerProgressObserver.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/NarLocalArchiveStager.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/NarStagedSource.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/NarTransactionalInstaller.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/install/NarDownloadRepositoryTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/install/NarTransactionalInstallerTest.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/install/InstallNarWorkerCancellationTest.kt`

**Interfaces:**
- Consumes: `DurableOperationSupervisor`, `OperationId`, and `OperationCancellation` from Task 2.
- Produces: phase/progress heartbeats and cooperative cancellation for every existing NAR path.

- [ ] **Step 1: Add failing boundary and replay tests**

Use fake DownloadManager, WorkManager, content streams, and filesystem gateways to assert cancellation during remote download, retained-URI copy, central-directory preflight, extraction chunks, verification, pre-commit, publish, and cleanup. Verify increasing `COLUMN_BYTES_DOWNLOADED_SO_FAR` heartbeats, repeated byte counts do not, cancel-then-retry gets a new attempt, and a late receiver/worker callback cannot mutate the new attempt. Include this ownership assertion:

```kotlin
@Test fun stop_install_removes_only_staging_and_preserves_live_ghost() {
    val result = installer.install(record, staging, isStopped = { stopRequested })
    assertEquals(ArchiveInstallResult.Cancelled, result)
    assertArrayEquals(previousTree, snapshot(installedGhost))
    assertFalse(staging.exists())
}
```

Add WorkManager integration coverage using `WorkManagerTestInitHelper`, `SynchronousExecutor`, and the real unique work name; cancel only that request and assert `CANCELLED` plus repository `Cancelled` state.

- [ ] **Step 2: Run focused tests red**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.NarDownloadRepositoryTest" --tests "*.NarTransactionalInstallerTest"`

Expected: FAIL because phases and supervisor cancellation are not wired.

- [ ] **Step 3: Publish bounded heartbeats and cancellation checks**

Extend installer entry points with a progress callback while retaining existing overloads:

```kotlin
fun install(
    archive: File,
    installRoot: File,
    forcedId: String?,
    isCancelled: () -> Boolean,
    onProgress: (phase: String, completed: Long) -> Unit,
): ArchiveInstallResult
```

Call it after each real byte-count increase and phase boundary; check cancellation before/after external actions and between bounded copy/extraction chunks. Move the Activity-owned local copy from its `AsyncTask` into `StageLocalNarWorker`, persisting the accepted record and persistable URI permission before enqueue; when a provider cannot grant durable access, finish the bounded private copy while the grant is live and make the resulting private file the durable handoff. `DownloadManagerProgressObserver` reads the exact bound row and heartbeats only when downloaded bytes increase. `InstallNarWorker.onStopped()` and `StageLocalNarWorker.onStopped()` record cancellation for their matching attempt, and owned resources close in `use`/`finally`. Preserve existing queue/retry/delete semantics and idempotent reconciliation.

- [ ] **Step 4: Run unit and connected cancellation suites**

Run: `.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.install.InstallNarWorkerCancellationTest`

Expected: PASS; no test waits 30 real seconds.

- [ ] **Step 5: Commit**

```text
git add src/main/kotlin/com/cattailsw/nanidroid/install src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/test/java/com/cattailsw/nanidroid/install src/androidTest/java/com/cattailsw/nanidroid/install
git commit -m "feat: make NAR operations cooperatively stoppable"
```

### Task 4: Replace live-file ghost updates with a recoverable transaction

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateJournal.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateRepository.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/durable/GhostUpdateWorker.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/NanidroidService.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/util/NetworkUtil.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/NarRelativePathPolicy.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/durable/GhostUpdateRepositoryTest.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/durable/GhostUpdateRecoveryTest.kt`

**Interfaces:**
- Consumes: Task 2 supervisor/store and Task 3 cooperative phase contract.
- Produces: `GhostUpdateRepository.run(request, isCancelled)`, a durable journal, and boot-time `recoverBeforeGhostLoad(ghostRoot)`.

- [ ] **Step 1: Write failing transaction and crash-point tests**

Table-drive cancellation/process death at manifest fetch, candidate download, digest verification, journal persistence, each commit rename, and cleanup. Assert every recovery outcome is classified:

```kotlin
sealed interface RecoveryResult {
    data object NoJournal : RecoveryResult
    data object RolledBack : RecoveryResult
    data object CompletedCommit : RecoveryResult
    data class Failed(val diagnostic: String) : RecoveryResult
}
```

The old tree must boot after rollback; a completed candidate must boot only after journal completion. Duplicate worker execution must adopt/reconcile the same operation ID rather than start a second update. Two distinct operation IDs targeting the same canonical ghost must serialize through one ghost-keyed unique-work name and filesystem lock; the second request adopts or is rejected without staging concurrently.

- [ ] **Step 2: Run update tests red**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.GhostUpdateRepositoryTest"`

Expected: FAIL because `GhostUpdateTask` writes into the live tree.

- [ ] **Step 3: Implement stage, verify, journal, and bounded commit**

Use app-private sibling directories created with `File(ghostRoot.parentFile, ".nanidroid-update-${request.operationId.value}")`, with `candidate` and `backup` children, and persist this journal before the first live-tree mutation:

```kotlin
data class GhostUpdateRequest(
    val operationId: OperationId,
    val ghostId: String,
    val ghostRoot: File,
    val baseUri: Uri,
)
data class GhostUpdateJournal(
    val operationId: OperationId,
    val ghostRoot: String,
    val candidateRoot: String,
    val backupRoot: String,
    val phase: CommitPhase,
    val files: List<String>,
)
enum class CommitPhase { PREPARED, BACKED_UP, PUBLISHED, CLEANED }
```

Normalize every update-manifest entry with `NarRelativePathPolicy`, reject absolute/traversal/duplicate/case-colliding paths, persist only normalized relative paths, and repeat the validation during recovery before resolving any child. Download and verify every candidate outside the live tree. Ignore stop during the bounded rename-only commit, then complete or roll back from the journal. Key unique WorkManager work and an exclusive journal/filesystem lock by canonical ghost identity, preserve SHIORI update events, close network/file streams on stop, and call recovery before `Ghost` construction.

- [ ] **Step 4: Run crash recovery and legacy event tests**

Run: `.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.durable.GhostUpdateRecoveryTest`

Expected: PASS, including recovery after simulated process death at every journal phase.

- [ ] **Step 5: Commit**

```text
git add src/main/kotlin/com/cattailsw/nanidroid/durable src/main/kotlin/com/cattailsw/nanidroid/NanidroidService.kt src/main/kotlin/com/cattailsw/nanidroid/util/NetworkUtil.kt src/main/kotlin/com/cattailsw/nanidroid/install/NarRelativePathPolicy.kt src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt src/test/java/com/cattailsw/nanidroid/durable src/androidTest/java/com/cattailsw/nanidroid/durable
git commit -m "feat: make ghost updates transactional"
```

## Milestone 2 — Compatibility foundation

### Task 5: Decode and recoverably parse real surface selector files

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/surface/SurfaceSourceFile.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/surface/SurfaceSelector.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/surface/SurfaceParser.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SurfaceReader.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SurfaceManager.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/surface/SurfaceSourceDecoderTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/surface/SurfaceSelectorTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/surface/SurfaceParserRecoveryTest.kt`
- Create: `src/test/resources/ghost-fixtures/snake-otacon/surfaces.txt`
- Create: `src/test/resources/ghost-fixtures/nanika-atsume/surfaces.txt`

**Interfaces:**
- Consumes: Existing `SurfaceManager.addSurface()` and `ShellSurface` entry parsing.
- Produces: decoded ordered `SurfaceSourceFile` values, expanded selectors, typed diagnostics, and replacement/append blocks.

- [ ] **Step 1: Extract minimal legal corpus fixtures and write failing parser tests**

Fixtures must be short, attributed excerpts or newly synthesized equivalents, not complete third-party files. Cover per-file charset, UTF-8 validation then Windows-31J fallback, filename order, indentation, comment-before-brace, trailing selector comments, comma IDs, inclusive ranges, `!` exclusions, accumulating normal definitions, `surface.append` existing-only behavior, malformed tokens, missing braces, and later-file recovery.

```kotlin
data class SurfaceParseDiagnostic(
    val file: String,
    val line: Int,
    val source: String,
    val reason: SurfaceDiagnosticReason,
)
enum class SurfaceDiagnosticReason { DECODE, SELECTOR, MISSING_BRACE, ENTRY, UNSUPPORTED }
data class SourceLine(val file: String, val number: Int, val text: String)

@Test fun invalid_token_is_skipped_and_never_aliases_surface_zero() {
    val result = parser.parse(
        listOf(source("surface0,broken,2 { collision0,0,0,1,1,Face }")),
        SurfaceParseSeed(emptySet()),
    )
    assertEquals(setOf(0, 2), result.surfaces.keys)
    assertEquals(1, result.diagnostics.size)
}
```

- [ ] **Step 2: Run parser tests red**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.surface.Surface*Test"`

Expected: FAIL on ranges, append, decoding, or recovery.

- [ ] **Step 3: Implement decoding, selection, and top-level resynchronization**

Use these types and keep entry text ordered for Task 6:

```kotlin
data class SurfaceSourceFile(val name: String, val charset: Charset, val lines: List<String>)
data class SurfaceSelection(val included: LinkedHashSet<Int>, val excluded: Set<Int>)
enum class SurfaceBlockMode { DEFINE, APPEND_EXISTING }
data class SurfaceParseSeed(val pngSurfaceIds: Set<Int>)
enum class CollisionSort { ASCEND, DESCEND, NONE }
data class SurfaceFileDirectives(val collisionSort: CollisionSort = CollisionSort.NONE)
data class ParsedSurfaceEntry(
    val source: SourceLine,
    val fileDirectives: SurfaceFileDirectives,
    val authoredOrder: Long,
)
data class ParsedSurfaceBlock(
    val selection: SurfaceSelection,
    val mode: SurfaceBlockMode,
    val entries: List<ParsedSurfaceEntry>,
)
data class SurfaceParseResult(
    val surfaces: Map<Int, List<ParsedSurfaceEntry>>,
    val diagnostics: List<SurfaceParseDiagnostic>,
)
fun SurfaceParser.parse(
    files: List<SurfaceSourceFile>,
    seed: SurfaceParseSeed,
): SurfaceParseResult
```

Read `surfaces.txt` and `surfaces*.txt` in case-insensitive-then-ordinal filename order and diagnose case-colliding names. Inspect the raw BOM/ASCII-compatible first line for a valid `charset` declaration before decoding that file; otherwise require strict UTF-8 decoding and then try Windows-31J/Shift-JIS. Process files and blocks in authored order. A normal `surface` block creates each missing target and appends its ordered entries; it never discards unrelated earlier entries. `surface.append` applies only to IDs already established by an earlier normal block or an existing `surface*.png`, never creates a target, and is not retroactive. Parse each file's `descript` block and attach its default-`NONE` `collision-sort` directive to every entry from that file. An exclusion remains excluded even if a later token in the same selector includes it. Accept audited `surface30{` and `surface30 {` as compatibility forms with one bounded diagnostic. Resynchronize only at a top-level selector and continue into later files.

- [ ] **Step 4: Adapt `SurfaceReader` without dual parser authority**

`SurfaceReader(manager, shellRoot, descriptorPath)` must scan PNG IDs first, discover all surface source files, pass `SurfaceParseSeed`, call the new parser once, materialize `ShellSurface` values, preserve PNG casing, and retain bounded diagnostics. Tests must cover two normal blocks accumulating, append applying only to existing normal/PNG targets, non-retroactive append, exclusions, two files with different collision sorts, inline braces, and recovery. Remove `getSurfaceIds()` and the hardcoded SJIS reader after characterization tests pass.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.Surface*Test"`

Expected: PASS for old characterization and new real-grammar fixtures.

```text
git add src/main/kotlin/com/cattailsw/nanidroid/surface src/main/kotlin/com/cattailsw/nanidroid/SurfaceReader.kt src/main/kotlin/com/cattailsw/nanidroid/SurfaceManager.kt src/test/java/com/cattailsw/nanidroid/surface src/test/resources/ghost-fixtures
git commit -m "feat: parse real surface selector grammar"
```

### Task 6: Preserve ordered named collision geometry

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SurfaceDefinition.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/ShellSurface.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/PatternHolders.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SurfaceHitTest.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/surface/CollisionGeometry.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/surface/CollisionGeometryTest.kt`
- Create: `src/test/resources/ghost-fixtures/bancho/collisionex.txt`

**Interfaces:**
- Consumes: Task 5 ordered `ParsedSurfaceEntry` values with per-file `collision-sort` directives.
- Produces: `SurfaceCollision(id, identifier, shape, authoredOrder)`, `CollisionSort`, shared `contains()`/`path()` geometry, and exact named hit results.

- [ ] **Step 1: Write failing shape, endpoint, order, and diagnostic tests**

Test rectangle normalization with reversed inclusive endpoints, ellipse/circle edges, ordinary and self-intersecting polygon fill/hit agreement, polygon vertices/edges, overlap under per-file `ascend`, `descend`, and default `none`, case-preserved identifiers, duplicate collision-ID rejection without losing siblings, transparent named hits, malformed siblings, and explicit unsupported-region/animation diagnostics.

```kotlin
@Test fun authored_rectangle_endpoints_become_one_half_open_shape() {
    val shape = CollisionShape.Rectangle.fromAuthored(10, 20, 0, 5)
    assertEquals(IntRect(0, 5, 11, 21), shape.bounds)
    assertTrue(shape.contains(IntOffset(10, 20)))
    assertFalse(shape.contains(IntOffset(11, 20)))
}
```

- [ ] **Step 2: Run collision tests red**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.CollisionGeometryTest"`

Expected: FAIL because only rectangle width/height and sorted numeric IDs exist.

- [ ] **Step 3: Implement one geometry model for hit and overlay**

```kotlin
sealed interface CollisionShape {
    val bounds: IntRect
    fun contains(point: IntOffset): Boolean
    fun representativePoint(): IntOffset?
    data class Rectangle(override val bounds: IntRect) : CollisionShape
    data class Ellipse(override val bounds: IntRect) : CollisionShape
    data class Circle(val center: IntOffset, val radius: Int) : CollisionShape
    data class Polygon(val points: List<IntOffset>) : CollisionShape
}
data class SurfaceCollision(
    val id: Int,
    val identifier: String,
    val shape: CollisionShape,
    val authoredOrder: Int,
)
```

Parse legacy `collisionN` and supported `collisionexN` entries individually. Preserve the source-file directive through materialization and resolve each file's entries by that sort while retaining authored order for `NONE`; characterize cross-file precedence instead of collapsing directives early. Reject a duplicate numeric ID individually and diagnose it. Polygon fill and hit use the same even-odd rule, including self-intersections. `representativePoint()` scans authored integer bounds through `contains()` and returns null when no valid interior pixel exists. Generate debug overlay paths from the same shape objects; never approximate ellipse/polygon hit tests as rectangles.

- [ ] **Step 4: Update mapping and preserve compatibility deliberately**

Update `ShellSurface.toSurfaceDefinition()` and `findSurfaceHit()` to return both the numeric diagnostic ID and exact identifier. Keep generic canvas fallback when no named shape matches. Delete numeric sorting from the mapper.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.SurfaceDefinitionCharacterizationTest" --tests "*.CollisionGeometryTest"`

Expected: PASS; update characterization expectations only where the approved authored-order behavior changes them.

```text
git add src/main/kotlin/com/cattailsw/nanidroid src/test/java/com/cattailsw/nanidroid/surface src/test/resources/ghost-fixtures/bancho
git commit -m "feat: support named collision geometry"
```

### Task 7: Tokenize structured dialogue, choices, anchors, and input boxes

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/DialogueContent.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizer.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/runtime/SakuraScriptInteractionEffects.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/runtime/GhostPresentationState.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SakuraScriptTokenizerTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/runtime/SakuraScriptInteractionInterpreterTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt`

**Interfaces:**
- Consumes: Existing frame speaker selection and runtime rendering callbacks.
- Produces: ordered per-speaker `DialogueContent` and exact `DialogueAction` values retained across host recreation.

- [ ] **Step 1: Write failing tokenization and dispatch fixtures**

Cover normal and extended choices, direct `On...` events, `script:` actions, `\_a` anchors, external URLs, structured input options and timeout/cancellation, balanced passive commands, unknown/truncated command recovery, and scopes `2+`. Assert no control text leaks into visible text and every extra reference remains ordered.

```kotlin
sealed interface DialogueAction {
    data class Normal(val label: String, val id: String, val extraReferences: List<String>) : DialogueAction
    data class DirectEvent(val label: String, val eventId: String, val references: List<String>) : DialogueAction
    data class Script(val label: String, val sakuraScript: String) : DialogueAction
}
sealed interface AnchorAction {
    data class Normal(val label: String, val id: String, val extraReferences: List<String>) : AnchorAction
    data class DirectEvent(val label: String, val eventId: String, val references: List<String>) : AnchorAction
}
sealed interface InputDispatch {
    data class Normal(val id: String) : InputDispatch
    data class DirectEvent(val eventId: String) : InputDispatch
}
enum class InputBehavior { PASSWORD, MULTILINE, NO_EMPTY, NO_CANCEL }
data class InputBoxSpec(
    val dispatch: InputDispatch,
    val timeoutMillis: Long?,
    val initialText: String,
    val behaviorOptions: Set<InputBehavior>,
    val supplement: String,
    val extraReferences: List<String>,
    val unknownOptions: List<String>,
)
```

- [ ] **Step 2: Run dialogue tests red**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.SakuraScriptTokenizerTest" --tests "*.SakuraScriptInteractionInterpreterTest"`

Expected: FAIL because regex extraction loses arguments, speaker ownership, and token order.

- [ ] **Step 3: Implement a balanced, incremental tokenizer**

```kotlin
sealed interface DialogueSegment {
    data class Text(val value: String) : DialogueSegment
    data object NewLine : DialogueSegment
    data class Wait(val millis: Long) : DialogueSegment
    data object Clear : DialogueSegment
    data class Choice(val action: DialogueAction) : DialogueSegment
    data class Anchor(val action: AnchorAction) : DialogueSegment
    data class ExternalUrl(val label: String, val uri: String) : DialogueSegment
    data class InputBox(val spec: InputBoxSpec) : DialogueSegment
}
data class DialogueContent(val speaker: GhostSpeaker, val segments: List<DialogueSegment>)
```

Tokenize brackets with escaping, quoted commas, doubled quotes, empty arguments, and balanced recovery; for example `\q[Label,OnPick,"a,b","c""d"]` retains references `a,b` and `c"d`. Parse positional and `--timeout=`, `--text=`, `--option=`, and repeated `--reference=` input arguments without discarding unknown options. Consume recognized unsupported presentation tokens with a debug diagnostic. For scope `2+`, consume presentation and emit one bounded diagnostic per talk. Store pending actions in runtime state rather than Activity-only dialog state.

- [ ] **Step 4: Implement exact action dispatch**

Normal choices send `OnChoiceSelectEx(label, id, extras...)`, then `OnChoiceSelect(id)` only when the first response has no playable talk. Direct choices send their event/references only; script choices enqueue locally. Normal anchors send `OnAnchorSelectEx(label, id, extras...)`, then `OnAnchorSelect(id)` only on no talk; direct anchors send only their authored event/references. Normal input submit sends `OnUserInput(id, value, supplement, extras...)`; an `On...` input ID sends that event with `(value, supplement, extras...)`. Close or timeout always sends `OnUserInputCancel(id, "close"|"timeout", supplement, extras...)`; only an unanswered timeout falls back to `OnUserInput(id, "timeout", supplement, extras...)`. Preserve remaining timeout across recreation and emit once. Add raw method/event/header assertions, not only boolean callbacks.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.runtime.dialogue.*" --tests "*.SScriptRunner*"`

Expected: PASS with existing presentation cadence preserved.

```text
git add src/main/kotlin/com/cattailsw/nanidroid/runtime src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt src/test/java/com/cattailsw/nanidroid/runtime src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt
git commit -m "feat: retain structured SakuraScript actions"
```

### Task 8: Discover pointer capabilities and emit exact SHIORI references

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/GhostEventCapabilities.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/SurfaceInteractionEffect.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/ShioriResponse.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/SurfacePointerInteraction.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/GhostEventCapabilitiesTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SurfaceInteractionProtocolTest.kt`

**Interfaces:**
- Consumes: Task 6 named collision results.
- Produces: cached tri-state click capabilities and a source-neutral interaction effect that maps to exact References 0–6.

- [ ] **Step 1: Write all capability-table and raw-header tests**

Cover `Get_Supported_Events` and local `Has_Event` on 204 responses with valid, absent, and malformed pass-through headers; do not infer support from ordinary interaction responses. Table-drive all seven approved touch combinations and physical pointer unsupported combinations.

```kotlin
enum class Support { SUPPORTED, UNSUPPORTED, UNKNOWN }
enum class ShioriMethod { GET, NOTIFY }
data class PointerEventCapabilities(val click: Support, val doubleClick: Support)
enum class PointerSource { TOUCH, MOUSE, PEN, ERASER }
enum class PointerEventKind { CLICK, DOUBLE_CLICK, MOVE, ENTER, LEAVE, WHEEL, DRAG }
data class SurfaceInteractionEffect(
    val kind: PointerEventKind,
    val speaker: SurfaceSpeaker,
    val intrinsic: IntOffset,
    val button: Int,
    val source: PointerSource,
    val collisionIdentifier: String?,
    val diagnosticCollisionId: Int?,
    val wheelDelta: Int = 0,
)
```

- [ ] **Step 2: Run protocol tests red**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.GhostEventCapabilitiesTest" --tests "*.SurfaceInteractionProtocolTest"`

Expected: FAIL because the current path always emits double-click, numeric `Reference4`, and `touch`.

- [ ] **Step 3: Implement raw response discovery and cache lifetime**

Add `Ghost.requestRaw(method: ShioriMethod, eventId: String, references: List<String> = emptyList()): ShioriResponse` and use it at ghost load/reload and for Task 9 timer NOTIFY requests. Serialize every supplied reference in order, including empty strings such as generic-canvas `Reference4`; do not collapse them into missing headers. Prefer `Get_Supported_Events`; query `Has_Event` with the event ID in `Reference0` only when needed. Cache per ghost session and clear on reload/unload.

- [ ] **Step 4: Implement exact one-event mapping**

Map touch through the approved table. Map named collision identifier or empty string to `Reference4`, button to `Reference5`, and the event-local source string to `Reference6`. Never send numeric `-1`, both events, or a replay fallback. Keep deferred event kinds typed but undispatched.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.GhostEventCapabilitiesTest" --tests "*.SurfaceInteractionProtocolTest" --tests "*.SurfacePointerInteractionTest"`

Expected: PASS with one SHIORI request per resolved gesture.

```text
git add src/main/kotlin/com/cattailsw/nanidroid src/test/java/com/cattailsw/nanidroid/runtime/dialogue src/test/java/com/cattailsw/nanidroid/compose/SurfacePointerInteractionTest.kt
git commit -m "feat: dispatch capability-aware pointer events"
```

### Task 9: Implement passive runtime state and origin-aware action guards

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/GhostRuntimeMode.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/GhostActionGuard.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/GhostRuntimeModeTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerBootDispatchTest.kt`

**Interfaces:**
- Consumes: Task 7 passive tokens/pending actions and Task 2 recovery controls.
- Produces: one `canTalk` decision and `GhostActionGuard.allows(action, origin)` for every Nanidroid-owned action.

- [ ] **Step 1: Write failing clock, passive, and guard tests**

Cover idle versus busy talk, pending choice/input, repeated idempotent passive enter/leave, sleep-inclusive OS continuous uptime hours, GET/NOTIFY, References 0–3, response playback suppression, persistent dialogue/actions, surface responses, user-versus-script origin, unload reset, and active recovery controls while passive.

```kotlin
enum class ActionOrigin { USER, SAKURA_SCRIPT, RECOVERY }
enum class GuardedAction { SWITCH_GHOST, MINIMIZE, EXIT, UPDATE, IMPORT_INSTALL, UNINSTALL }
data class GhostRuntimeMode(
    val playingTalk: Boolean,
    val pendingUserAction: Boolean,
    val passive: Boolean,
) { val canTalk: Boolean get() = !playingTalk && !pendingUserAction && !passive }
```

- [ ] **Step 2: Run runtime tests red**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.GhostRuntimeModeTest" --tests "*.SScriptRunnerBootDispatchTest"`

Expected: FAIL because the runner uses elapsed time since its own start and has no passive state.

- [ ] **Step 3: Implement the unified timer contract**

Use the injected sleep-inclusive clock backed by `SystemClock.elapsedRealtime() / 3_600_000L` for `Reference0`; add a fake-clock case where deep-sleep-equivalent elapsed time advances. Passive enter/leave are idempotent booleans and unload clears passive. Idle sends `ShioriMethod.GET` with `Reference3=1` and may play the response; busy/pending/passive sends `ShioriMethod.NOTIFY` with `Reference3=0` and ignores response scripts. Preserve References 1–2 and never let a surface response replace an active passive sequence.

- [ ] **Step 4: Guard every owned entry point**

Route toolbar, back/exit, switch, update, import/install, and uninstall through `GhostActionGuard`. Permit SakuraScript-originated and `RECOVERY` stop/keep-waiting actions. Entering passive mode does not cancel already-running operations.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.GhostRuntimeModeTest" --tests "*.SScriptRunner*"`

Expected: PASS for normal and passive timer/action paths.

```text
git add src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/test/java/com/cattailsw/nanidroid/runtime/dialogue src/test/java/com/cattailsw/nanidroid/SScriptRunnerBootDispatchTest.kt
git commit -m "feat: protect passive ghost interactions"
```

## Milestone 3 — Adaptive stage

### Task 10: Classify stable windows and calculate adaptive lanes

**Files:**
- Replace: `src/main/kotlin/com/cattailsw/nanidroid/runtime/GhostStageLayout.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/StageEnvironment.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/GhostStageLayoutPolicy.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/SurfaceSizingPolicy.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/SurfaceRenderPlan.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/SurfaceCompositor.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/runtime/GhostStageLayoutPolicyTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/stage/StageEnvironmentTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/stage/SurfaceSizingPropertyTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/compose/SurfaceCompositorTest.kt`

**Interfaces:**
- Consumes: selected surface definitions, elements, and supported animation frames.
- Produces: pure `StageLayoutDp` with mode, content bounds, physical lanes, bubble cells, surface placements, and tiny fallback.

- [ ] **Step 1: Write the complete viewport and sizing matrix red**

Parameterize `360x720`, `720x360`, `400x1000`, `610x500`, `800x1280`, `1280x800`, `480x230`, and `230x400 dp`. Add immediately-below/at/above tests for width `420`, heights `240/320/480`, ratio `1.2`, width `540`, and cap `960`. Run these exact intrinsic pairs: `250x400 + 235x200`, `270x378 + 239x380`, `427x640 + 1x1`, `210x140 + 210x140`, `772x535 + 422x377`, `93x95 + 200x200`, `450x750 + 450x750`, and `300x501 + 210x420`. Also cover absent, hidden, zero, transparent, transparent-with-collision, opaque `8x8`, `8/9 px`, elongated, optical-bound, and surface-change cases.

```kotlin
enum class StageMode { TINY, COMPACT_LANDSCAPE, STANDARD }
enum class StagePosture { FLAT, BOOK, TABLETOP }
data class StageDpRect(val left: Dp, val top: Dp, val right: Dp, val bottom: Dp)
data class StageDisplayFeature(
    val bounds: StageDpRect,
    val separating: Boolean,
    val occluding: Boolean,
)
data class StageInputCapabilities(
    val touch: Boolean,
    val mouse: Boolean,
    val stylus: Boolean,
    val hardwareKeyboard: Boolean,
)
data class ComposedSurfaceMetrics(
    val canvasSize: IntSize,
    val visiblePixelBounds: IntRect?,
    val collisions: List<SurfaceCollision>,
    val explicitlyHidden: Boolean,
)
data class StageEnvironment(
    val safeSize: DpSize,
    val density: Float,
    val fontScale: Float,
    val canonicalAppBarHeight: Dp,
    val posture: StagePosture,
    val displayFeatures: List<StageDisplayFeature>,
    val inputCapabilities: StageInputCapabilities,
)
data class StageLayoutDp(
    val mode: StageMode,
    val content: StageDpRect,
    val keroLane: StageDpRect?,
    val sakuraLane: StageDpRect?,
    val keroBubble: StageDpRect?,
    val sakuraBubble: StageDpRect?,
    val keroSurface: StageDpRect?,
    val sakuraSurface: StageDpRect?,
)
```

- [ ] **Step 2: Run policy tests red**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.GhostStageLayoutPolicyTest" --tests "*.stage.*"`

Expected: FAIL because the current pixel policy never scales up or creates the center bubble lane.

- [ ] **Step 3: Implement stable classification and occlusion selection**

Reserve the app bar whether visible or hidden; exclude IME/debug surfaces from classification. Subtract each separating/occluding feature from the safe window into maximal non-overlapping axis-aligned rectangles, discard any rectangle crossing a feature, then choose the largest rectangle that satisfies the applicable lane minima with deterministic top/left tie-breaking. Test partial-width features, multiple features, inset-offset features, vertical book and horizontal tabletop hinges, and feature removal. Return `TINY` when no representable rectangle qualifies. Flat/non-occluding features use the full safe window.

- [ ] **Step 4: Implement lane and hybrid surface sizing**

Standard uses two equal physical lanes, a centered `<=960 dp` content box, and a `64%` ordinary-dialogue height cap. Keep each stable bubble cell above its own visible artwork, authored collisions, and the other speaker; if transparent canvas cannot host it, reduce the owning surface within its lane instead of overlapping active content. Compact landscape uses fixed `180 dp` center from `420..<540 dp`, then equal thirds, and full-height outer surfaces. Apply shared aspect-fit scale, exclude true hidden/placeholders, then independently raise visible content toward `96 dp` subject to the lane, crop, and `2x` limits.

Return cached `ComposedSurfaceMetrics` with stable authored canvas size, alpha-derived visible-pixel bounds, and exact collision geometry. Classify hidden/placeholder only from explicit hidden selection, absent speaker, or no visible pixels and no active collision. Composite supported frames into the selected surface canvas; if a supported operation genuinely changes that canvas, publish the new render image, metrics, transform, hit target, and overlay in one immutable stage snapshot. In Task 11, recheck bubble-versus-visible/collision separation after final pixel rounding so the dp policy cannot create a one-pixel overlap.

- [ ] **Step 5: Verify properties and commit**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.runtime.stage.*" --tests "*.GhostStageLayoutPolicyTest"`

Expected: PASS; no random/property seed may be time-dependent.

```text
git add src/main/kotlin/com/cattailsw/nanidroid/runtime src/main/kotlin/com/cattailsw/nanidroid/compose/SurfaceRenderPlan.kt src/main/kotlin/com/cattailsw/nanidroid/compose/SurfaceCompositor.kt src/test/java/com/cattailsw/nanidroid/runtime src/test/java/com/cattailsw/nanidroid/compose/SurfaceCompositorTest.kt
git commit -m "feat: calculate adaptive ghost stage lanes"
```

### Task 11: Materialize one shared pixel transform for draw, hit, and overlay

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/SurfaceTransformPx.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/StageEnvironmentProvider.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/GhostPresentationStage.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/SurfacePointerInteraction.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/CollisionOverlay.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/stage/SurfaceTransformPxTest.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/RenderedTransformContractTest.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/StageEnvironmentProviderTest.kt`

**Interfaces:**
- Consumes: Task 10 `StageLayoutDp` and Task 6 collision shapes.
- Produces: immutable `SurfaceTransformPx` atomically shared by renderer, router, overlay, semantics, and debug state.

- [ ] **Step 1: Write failing rounding and boundary tests**

Cover densities `1.0/1.5/2.0/3.0`, fractional origins, every rendered edge and one pixel outside, resize/rotation freshness, and root-coordinate equality between visible overlay and active hit target. With `WindowLayoutInfoPublisherRule`, publish flat, vertical separating, horizontal tabletop, partial occlusion, multiple features, and feature removal; assert lifecycle stop/restart and posture-only relayout without Activity recreation. Screenshot foldable cases remain policy/render fixtures, not end-to-end posture proof.

```kotlin
data class SurfaceTransformPx(
    val intrinsicSize: IntSize,
    val renderedBounds: IntRect,
    val scale: Float,
    val stageToRoot: IntOffset,
) {
    fun toIntrinsic(stagePoint: IntOffset): IntOffset?
    fun toStage(shape: CollisionShape): Path
}
```

Assert uniform scale within final integer rounding tolerance and inverse mapping `floor(local * intrinsic / rendered)` with no acceptance outside the half-open bounds.

- [ ] **Step 2: Run transform tests red**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.SurfaceTransformPxTest"`

Expected: FAIL because `StageNode` and `SurfacePointerTransform` reconstruct sizes independently.

- [ ] **Step 3: Implement a custom measured stage layout**

Add `StageEnvironmentProvider` backed by `currentWindowAdaptiveInfo()`, current window insets, and AndroidX `WindowInfoTracker`; it supplies canonical window size classes for general adaptive chrome plus posture, separating/occluding features, and pointer/keyboard capabilities without using deprecated `Display` metrics. The stage's approved content-specific thresholds remain pure and explicit. Replace `BoxWithConstraints` plus dp reconversion with a Compose `Layout` that receives `StageLayoutDp`, rounds each final `IntRect` once, and publishes a single immutable transform per visible speaker. Pass that instance into `SurfaceCompositorImage`, pointer router, collision overlay, semantic actions, and debug diagnostics via explicit parameters; remove `SurfacePointerTransform` and `onSizeChanged` reconstruction.

- [ ] **Step 4: Draw overlays from authored geometry through the shared transform**

Cache transformed paths by `(surfaceDefinition, SurfaceTransformPx)`, draw exact rectangle/ellipse/circle/polygon outlines and labels, and mark the overlay decorative with `clearAndSetSemantics { }`. Do not decode or composite bitmaps during dialogue-character recomposition.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.SurfaceTransformPxTest" connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.stage.RenderedTransformContractTest,com.cattailsw.nanidroid.compose.stage.StageEnvironmentProviderTest`

Expected: PASS after portrait, landscape, and post-resize edge probes.

```text
git add src/main/kotlin/com/cattailsw/nanidroid/runtime/stage src/main/kotlin/com/cattailsw/nanidroid/compose src/test/java/com/cattailsw/nanidroid/runtime/stage src/androidTest/java/com/cattailsw/nanidroid/compose/stage
git commit -m "feat: share measured surface transforms"
```

### Task 12: Route touch, mouse, pen, semantics, and empty-stage activation centrally

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/StageInputRouter.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/stage/PhysicalClickSequencer.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/StagePointerInput.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidComposeShell.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/compose/SurfacePointerInteractionTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/stage/StageInputRouterTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/stage/PhysicalClickSequencerTest.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/StagePointerInputTest.kt`

**Interfaces:**
- Consumes: Task 8 typed effects/capabilities and Task 11 transforms.
- Produces: one stage-level pointer sequence resolver and exact-one-event physical click sequencer.

- [ ] **Step 1: Write routing, consumption, slop, and sequencing tests red**

Test modal/debug, bubble action/content/frame, named hit, generic transparent canvas, empty stage, absent reserved bubble, cancellation, leaving original scope, unsupported button, surface change mid-gesture, single delay, double suppression, and updated transform after resize.

```kotlin
sealed interface BubbleInteractionTarget {
    data class Choice(val action: DialogueAction) : BubbleInteractionTarget
    data class Anchor(val id: String, val arguments: List<String>) : BubbleInteractionTarget
    data class ExternalUrl(val uri: String) : BubbleInteractionTarget
    data class Input(val input: DialogueSegment.InputBox) : BubbleInteractionTarget
    data class Scroll(val speaker: SurfaceSpeaker) : BubbleInteractionTarget
    data class Frame(val speaker: SurfaceSpeaker) : BubbleInteractionTarget
}
data class MeasuredBubbleHitRegion(val bounds: IntRect, val target: BubbleInteractionTarget)
fun interface BubbleHitRegionRegistry {
    fun resolve(stagePoint: IntOffset): BubbleInteractionTarget?
}
sealed interface StageInputTarget {
    data object Modal : StageInputTarget
    data class Bubble(val target: BubbleInteractionTarget) : StageInputTarget
    data class Surface(val speaker: SurfaceSpeaker, val hit: SurfaceHitTarget) : StageInputTarget
    data object EmptyStage : StageInputTarget
}
interface ClickDeadlineScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle
}
fun interface CancellationHandle { fun cancel() }
data class PhysicalClickKey(
    val source: PointerSource,
    val button: Int,
    val speaker: SurfaceSpeaker,
    val collisionIdentifier: String?,
)
```

- [ ] **Step 2: Run input tests red**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.StageInputRouterTest" --tests "*.PhysicalClickSequencerTest"`

Expected: FAIL because nested tap handlers currently determine behavior and a surface tap toggles chrome.

- [ ] **Step 3: Implement central pointer-sequence ownership**

Use `awaitEachGesture` at the stage root, retain event-local `PointerType`, primary button, down transform/target, `ViewConfiguration.scaledTouchSlop`, and `ViewConfiguration.getDoubleTapTimeout()`. Task 12 defines the measured `BubbleHitRegionRegistry` contract and tests it with fixtures; Task 13 publishes the real choice/anchor/URL/input/scroll/frame bounds. Bubble/modal bounds consume before surface resolution. A moved/cancelled/out-of-scope sequence emits nothing. Empty-stage activation calls only `toggleChrome`; surface activation dispatches only the typed effect.

- [ ] **Step 4: Implement deterministic physical click sequencing**

Touch maps immediately through Task 8. Retain every first mouse/pen/eraser activation through the double-click window even when single-click is unsupported. Pair a second click only when `PhysicalClickKey` matches and its intrinsic point is within platform slop; cross-speaker, cross-collision, cross-button, different-source, or distant clicks remain separate sequences. A matched second primary click cancels the pending single and emits one `OnMouseDoubleClick`; explicit unsupported double-click emits nothing and still suppresses that pair's pending single. Use an injected scheduler in unit tests and Android's platform timeout in production; never sleep in tests.

- [ ] **Step 5: Verify all input sources and commit**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.StageInputRouterTest" --tests "*.PhysicalClickSequencerTest" connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.stage.StagePointerInputTest`

Expected: PASS for injected touch, mouse, stylus, and eraser MotionEvents.

```text
git add src/main/kotlin/com/cattailsw/nanidroid/runtime/stage src/main/kotlin/com/cattailsw/nanidroid/compose src/test/java/com/cattailsw/nanidroid src/androidTest/java/com/cattailsw/nanidroid/compose/stage
git commit -m "feat: centralize ghost stage input routing"
```

## Milestone 4 — Usability completion

### Task 13: Build adaptive bubbles and extracted action surfaces

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/GhostBubble.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/DialogueActionSurface.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/GhostPresentationStage.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidSimpleDialogs.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/GhostBubbleInteractionTest.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/DialogueActionSurfaceTest.kt`

**Interfaces:**
- Consumes: Task 7 ordered dialogue/actions and Task 10 fixed bubble cells.
- Produces: fixed-cell scrolling bubbles and reopenable compact/expanded action surfaces with `48 dp` rows.

- [ ] **Step 1: Write failing bubble and action-surface tests**

Assert compact center top Kero/bottom Sakura pointers, fixed half heights, reserved absent half, one/two/long bubbles, follow-newest until manual scroll, new-talk reset, bubble-frame consumption, action priority, `Choose...` ownership, all action arguments, 48 dp minimum rows, mouse-wheel/trackpad scrolling without SHIORI dispatch, Page Up/Down, arrows, Tab/Shift-Tab, Enter/Space, Escape/back, restoration, and no action loss on recreation. Focus must never enter an absent bubble half or decorative overlay.

```kotlin
data class BubbleUiState(
    val speaker: SurfaceSpeaker,
    val content: DialogueContent,
    val pendingChoices: List<DialogueAction>,
    val scrollPosition: Int,
    val userScrolledThisTalk: Boolean,
)
```

- [ ] **Step 2: Run bubble tests red**

Run: `.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.stage.GhostBubbleInteractionTest,com.cattailsw.nanidroid.compose.stage.DialogueActionSurfaceTest`

Expected: FAIL because `ClickableText` has no structured actions or stable cell contract.

- [ ] **Step 3: Implement fixed bubble cells and scroll ownership**

Render ordered text/newline content inside the policy cell; keep the pointer/frame fixed while content scrolls. Consume all pointer input inside a visible frame. Track manual scrolling per talk ID; auto-follow new text until manual scroll and re-enable on the next talk.

- [ ] **Step 4: Implement responsive extracted actions**

Compact/touch presentation uses a scrollable full-width dialog or sheet; expanded width uses a capped centered dialog/popover. Keep one `Choose...` action in the owning bubble while choices remain pending. Dispatch Task 7 actions without appending speaker metadata to SHIORI references. Distinguish external URLs and require explicit activation.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.stage.GhostBubbleInteractionTest,com.cattailsw.nanidroid.compose.stage.DialogueActionSurfaceTest`

Expected: PASS at font scales `1.0`, `1.5`, and `2.0` through `DeviceConfigurationOverride`.

```text
git add src/main/kotlin/com/cattailsw/nanidroid/compose src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/androidTest/java/com/cattailsw/nanidroid/compose/stage
git commit -m "feat: add adaptive ghost dialogue actions"
```

### Task 14: Replace two toolbar rows with one app bar and adaptive debug tools

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidComposeShell.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/compose/debug/DebugPanelState.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/compose/debug/GhostDebugSurface.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/BoundedShioriLog.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Modify: `src/main/res/values/strings.xml`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/compose/NanidroidComposeShellTest.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/compose/debug/GhostDebugSurfaceTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/BoundedShioriLogTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/compose/debug/DebugAvailabilityPolicyTest.kt`

**Interfaces:**
- Consumes: Task 2 operation state, Task 6 overlay toggle, Task 8 event diagnostics, and Task 11 transforms.
- Produces: one Material app bar and deterministic compact overlay/bottom sheet/side panel debug presentation.

- [ ] **Step 1: Write failing chrome, release-absence, and debug-control tests**

Assert Ghosts primary action; update/readme/preferences/help and archive-queue access in overflow; a queue count/status remains discoverable when work exists; debuggable-only labeled bug icon; no second row; compact landscape full-stage modal; standard width `<840 dp` bottom sheet; standard width `>=840 dp` capped side panel; unchanged stage classification; live state behind debug; all control effects. Make debug availability a pure policy driven by injected `isDebuggable` and assert both debug/release values; `assembleRelease` then proves the release wiring compiles without hidden debug semantics.

```kotlin
data class DebugPanelState(
    val visible: Boolean,
    val selectedSpeaker: SurfaceSpeaker,
    val showCollisionOverlay: Boolean,
)
enum class DebugPresentation { FULL_STAGE_MODAL, BOTTOM_SHEET, SIDE_PANEL }
```

- [ ] **Step 2: Run chrome/debug tests red**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.BoundedShioriLogTest" --tests "*.DebugAvailabilityPolicyTest" connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.debug.GhostDebugSurfaceTest`

Expected: FAIL because debug remains a no-op second row.

- [ ] **Step 3: Implement bounded diagnostics and real controls**

Keep at most 100 events and truncate each displayed request/response to `64 KiB`; never save the log in a Bundle. Surface section is read-only and shows scope, ID, intrinsic/composed dimensions, visible bounds, and animation diagnostics. Collision/input section toggles the real overlay and shows viewport/intrinsic coordinates, identifier/diagnostic ID, button/source/event. Runtime tools expose NAR test and the bounded SHIORI log. Remove previous/next surface override, dump, and no-op collision callbacks from production UI.

- [ ] **Step 4: Implement one Material app bar and adaptive containers**

Use a single top app bar with physical stage reservation independent of visibility. Put Check updates, Readme, and Preferences in overflow. Select debug presentation from stable stage mode/width; a side panel may reduce visible content space but must not feed back into classification.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat testDebugUnitTest --tests "*.BoundedShioriLogTest" --tests "*.DebugAvailabilityPolicyTest" connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.NanidroidComposeShellTest,com.cattailsw.nanidroid.compose.debug.GhostDebugSurfaceTest`

Expected: PASS for both injected availability values, connected debug behavior, and `assembleRelease` at the final gate.

```text
git add src/main/kotlin/com/cattailsw/nanidroid/compose src/main/kotlin/com/cattailsw/nanidroid/runtime src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/main/res/values/strings.xml src/test/java/com/cattailsw/nanidroid/runtime src/androidTest/java/com/cattailsw/nanidroid/compose
git commit -m "feat: reorganize app chrome and debug tools"
```

### Task 15: Add accessibility, state restoration, tiny fallback, and stalled-operation UI

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/compose/stage/GhostStageSemantics.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/compose/durable/StalledOperationPrompt.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/GhostPresentationStage.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidComposeShell.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/NanidroidService.kt`
- Modify: `src/main/res/values/strings.xml`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/GhostStageAccessibilityTest.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/compose/stage/GhostStageRestorationTest.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/compose/durable/StalledOperationPromptTest.kt`

**Interfaces:**
- Consumes: Task 2 supervisor, Task 6 named shapes, Task 10 tiny mode, Task 12 router, and Task 14 chrome/debug state.
- Produces: semantic activation parity, saveable UI state, named in-app/notification recovery actions, and the agreed tiny-window message.

- [ ] **Step 1: Write failing semantics, restoration, and prompt tests**

Use semantic matchers before test tags. On API 34+, enable Compose accessibility checks and invoke them after each stable screen. Assert localized Sakura/Kero identity and activation; logically ordered named-collision custom actions using representative intrinsic points; duplicate names disambiguated by speaker and stable ordinal; null representative points omitted with a diagnostic; polite dialogue live region; separate visible and semantic `48 dp` bounds; keyboard/D-pad focus/activation; overlay exclusion; empty-stage show/hide-controls action; no hidden tiny interactions; rotation/recreation persistence; prompt at exactly 30 seconds; Keep waiting; operation-specific Stop; passive availability; notification parity; release debug absence.

```kotlin
fun accessibilityEffectOrNull(
    speaker: SurfaceSpeaker,
    collision: SurfaceCollision,
): SurfaceInteractionEffect? {
    val point = collision.shape.representativePoint() ?: return null
    return SurfaceInteractionEffect(
        kind = PointerEventKind.CLICK,
        speaker = speaker,
        intrinsic = point,
        button = 0,
        source = PointerSource.TOUCH,
        collisionIdentifier = collision.identifier,
        diagnosticCollisionId = collision.id,
    )
}
```

- [ ] **Step 2: Run connected tests red**

Run: `.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.stage.GhostStageAccessibilityTest,com.cattailsw.nanidroid.compose.stage.GhostStageRestorationTest,com.cattailsw.nanidroid.compose.durable.StalledOperationPromptTest`

Expected: FAIL because custom surfaces lack semantics/restoration and no stall prompt exists.

- [ ] **Step 3: Implement semantics and restoration**

Provide manual semantics for low-level surface drawing, named collision custom actions, labeled show/hide controls, links, choices, input, debug, and fallback. Save app-bar visibility, debug visibility, selected diagnostic scope, overlay switch, and bubble scroll state; retain runtime frame/dialogue in the runtime owner. Never save bitmaps or logs.

- [ ] **Step 4: Implement fallback and recovery UI**

Tiny mode renders exactly `This window is too small for Nanidroid. Make it a little bigger 💦`, removes stage semantics/pointer input, and restores the untouched frame above the boundary. A named stalled prompt and ongoing notification expose `Keep waiting`, `Stop operation`, and diagnostics. Stop transitions visibly to `Stopping...`; a further 30-second stall may offer wait/diagnostics but cannot force-kill.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.stage.GhostStageAccessibilityTest,com.cattailsw.nanidroid.compose.stage.GhostStageRestorationTest,com.cattailsw.nanidroid.compose.durable.StalledOperationPromptTest`

Expected: PASS with TalkBack semantics tree, keyboard, touch, and notification actions agreeing.

```text
git add src/main/kotlin/com/cattailsw/nanidroid/compose src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/main/kotlin/com/cattailsw/nanidroid/NanidroidService.kt src/main/res/values/strings.xml src/androidTest/java/com/cattailsw/nanidroid/compose
git commit -m "feat: complete accessible stage recovery UI"
```

### Task 16: Add reviewed adaptive screenshot goldens

**Files:**
- Create: `src/screenshotTest/kotlin/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTest.kt`
- Create: `src/screenshotTest/kotlin/com/cattailsw/nanidroid/compose/AdaptiveGhostStageFixtures.kt`
- Create: `src/screenshotTestDebug/reference/`
- Modify: `docs/testing.md`

**Interfaces:**
- Consumes: Tasks 10–15 pure fixture state and Compose components; no installed ghost, network, clock, or filesystem.
- Produces: deterministic named screenshot previews, references, and HTML diff reports.

- [ ] **Step 1: Define the reviewed golden table before generating images**

Commit an explicit 34-case table: nine `400/610/900 dp` width by `400/500/1000 dp` height screen cases; 19 named product states spanning `360x720`, `720x360`, `400x1000`, `610x500`, `800x1280`, `1280x800`, `480x230`, and `230x400`; and six pairwise variations covering LTR/RTL, light/dark, font `1.0/1.5/2.0`, and densities `160/320 dpi`. Product states cover one/two/long/empty bubbles; debug modal/sheet/panel; stalled prompt normal/passive; one combined rectangle/ellipse/polygon overlay; flat/separating foldable policy fixtures; and tiny fallback. Record full app-window size separately from expected safe-stage size.

```kotlin
data class StageScreenshotCase(
    val name: String,
    val windowSizeDp: DpSize,
    val expectedSafeStageDp: DpSize,
    val fontScale: Float,
    val densityDpi: Int,
    val theme: ScreenshotTheme,
    val layoutDirection: LayoutDirection,
    val posture: StagePosture,
    val expectedInvariants: Set<ScreenshotInvariant>,
    val state: StageFixtureState,
)
enum class ScreenshotTheme { LIGHT, DARK }
enum class ScreenshotInvariant { KERO_LEFT, SAKURA_RIGHT, CENTER_SPLIT, NO_CLIP, TINY_ONLY, DEBUG_MODAL }
data class StageFixtureState(
    val presentation: GhostPresentationState,
    val sakura: ScreenshotSurfaceFixture,
    val kero: ScreenshotSurfaceFixture,
    val debug: DebugPanelState,
    val stalledOperation: DurableOperationRecord?,
)
data class ScreenshotSurfaceFixture(
    val definition: SurfaceDefinition,
    val image: SurfacePixelImage,
)
```

- [ ] **Step 2: Add previews and verify references are missing**

Annotate each deterministic preview with `@PreviewTest` and explicit preview/device specifications matching the versioned table. Verify the table contains exactly 34 unique names before rendering. Run: `.\gradlew.bat validateDebugScreenshotTest`

Expected: FAIL with missing references.

- [ ] **Step 3: Generate and personally inspect every initial reference**

Run: `.\gradlew.bat updateDebugScreenshotTest`

Open all 34 generated PNGs with the local image viewer, not only the HTML status. Reject blank/missing corner sentinels, clipping, unexpected whitespace, speaker reversal, illegible center cells, bubble-pointer mismatch, stretched content, false overlay bounds, tiny hidden content, and inaccessible-looking action density. Regenerate only after fixing production/fixture code and recording the reason. These are static render fixtures only; IME, semantics, pointer, lifecycle, and real WindowManager behavior remain connected-test obligations.

- [ ] **Step 4: Validate references and document review rules**

Run: `.\gradlew.bat validateDebugScreenshotTest`

Expected: PASS and `build/reports/screenshotTest/preview/debug/index.html` contains no diffs. Document that CI runs only validate, baseline updates require human image-diff review, and plugin upgrades require full golden review.

- [ ] **Step 5: Commit**

```text
git add src/screenshotTest src/screenshotTestDebug/reference docs/testing.md
git commit -m "test: add adaptive ghost stage goldens"
```

### Task 17: Exercise the real local NAR corpus through install, parse, render, and input

**Files:**
- Modify: `.gitignore`
- Create: `scripts/run-nar-corpus-audit.ps1`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/corpus/NarCorpusRuntimeTest.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/corpus/NarCorpusProbeActivity.kt`
- Modify: `src/androidTest/AndroidManifest.xml`
- Create: `docs/testing/nar-corpus.md`
- Create: `docs/testing/nar-corpus-manifest.json`
- Modify: `docs/testing.md`

**Interfaces:**
- Consumes: local archives under `2elf-2.46.nar`, `build/ui-audit/ghosts/`, and `build/ui-audit/pcPets/`; production `NarTransactionalInstaller`, `Ghost`, `SurfaceManager`, compositor, stage policy, router, and SHIORI adapter.
- Produces: per-archive JSON runtime records and representative screenshots under `build/reports/nar-corpus/`, without source-controlling archives.

- [ ] **Step 1: Write a failing single-archive runtime probe**

Read instrumentation arguments `narCorpusPath`, `narCorpusSha256`, and `narCorpusLabel`. Copy the archive into a fresh target-context cache directory, install into a test-owned root, and write this schema to the target-context external report file:

```kotlin
data class NarCorpusResult(
    val label: String,
    val sha256: String,
    val archiveBytes: Long,
    val installOutcome: String,
    val ghostLoadOutcome: String?,
    val surfaceCount: Int?,
    val parserDiagnostics: List<String>,
    val sakuraIntrinsic: IntSize?,
    val keroIntrinsic: IntSize?,
    val renderOutcome: String?,
    val inputOutcome: String?,
    val shioriOutcome: String?,
    val evidence: NarCorpusEvidence,
)
data class NarCorpusEvidence(
    val defaultSurfaceIds: List<Int>,
    val parsedSelectorIds: List<Int>,
    val namedCollisionProbes: List<CollisionProbe>,
    val dialogueProbe: DialogueProbe?,
    val remainingTestOwnedPaths: List<String>,
)
data class CollisionProbe(
    val surfaceId: Int,
    val identifier: String,
    val intrinsicPoint: IntOffset,
    val renderedPoint: IntOffset,
    val overlayContainsPoint: Boolean,
    val hitIdentifier: String?,
)
data class DialogueProbe(
    val observedAnchorId: String?,
    val observedInputId: String?,
    val passiveTransitions: List<Boolean>,
    val method: ShioriMethod?,
    val eventId: String?,
    val references: List<String>,
)
```

Classify non-ghost/balloon packages explicitly rather than failing the entire run. Structured evidence—not an `"ok"` string—must carry observed anchor/input/passive transitions, exact SHIORI method/event/references, collision probe point/path/hit, and cleanup inventory. Always remove only the test-owned install/cache roots in `finally`; never touch the user's production ghost directory.

- [ ] **Step 2: Run the probe without arguments and verify it fails clearly**

Run: `.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.corpus.NarCorpusRuntimeTest`

Expected: FAIL with `narCorpusPath is required`, proving a missing corpus cannot silently pass.

- [ ] **Step 3: Implement the host corpus orchestrator**

Commit `nar-corpus-manifest.json` with the current 23 logical labels, versions, SHA-256 values, expected package kind, required evidence, and allowed classification; archive bytes remain local. The runner resolves only manifest hashes, fails on missing/substituted entries, and reports additional discovered archives separately. Add `*.nar` to `.gitignore`. The PowerShell runner must:

```powershell
[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [string[]]$CorpusRoots = @('.', 'build/ui-audit')
)

$archives = Get-ChildItem -LiteralPath $CorpusRoots -Recurse -File -Filter '*.nar' |
    Sort-Object FullName -Unique
if ($archives.Count -eq 0) { throw 'No .nar archives found' }
```

Build/install target and test APKs once; for each archive compute SHA-256, push it under one constant filename in `/data/local/tmp/`, apply mode `0644`, copy it into the app's test-owned private corpus path with `run-as`, invoke only `NarCorpusRuntimeTest` with arguments, collect its JSON/result screenshot, then delete both device copies. Continue after an expected incompatible archive but stop on crashes, orphaned staging, unexpected install mutation, or missing results. Emit `summary.json`, `summary.md`, and `failures/` artifacts.

Before installation, require `ro.kernel.qemu=1`, API 31–37, a supported ABI, debug signing, sufficient free space, working `run-as`, and absence of a pre-existing target package; refuse physical devices and existing app data. Use a run-specific path built as `"/data/local/tmp/nanidroid-corpus/$runId"`, outer host `try/finally`, per-archive timeout/force-stop recovery, fixed clock/random seeds, disabled network, and pre/post filesystem snapshots. Record Git commit, APK SHA-256, manifest SHA-256, device fingerprint/API/ABI/density, and duration. Invoke the already-installed test APK once per archive rather than rebuilding:

```powershell
& adb -s $DeviceSerial shell am instrument -w -r `
    -e class 'com.cattailsw.nanidroid.corpus.NarCorpusRuntimeTest' `
    -e narCorpusPath $privatePath `
    -e narCorpusSha256 $archiveSha256 `
    -e narCorpusLabel $manifestEntry.label `
    'com.cattailsw.nanidroid.test/com.cattailsw.nanidroid.NanidroidTestRunner'
```

- [ ] **Step 4: Assert known compatibility sentinels and then run all 23 archives**

Run the corpus only on a cold-booted, clean, dedicated emulator profile with no personal accounts or user ghost data; do not open external URLs or grant third-party scripts access to host files. The aggregate test must require:

- 2elf: installs, renders its actual default pair, maps a face collision by authored name, and produces a real dialogue response when its SHIORI engine is supported;
- Snake/Otacon: comments, comma/range selectors, append, anchors, passive mode, and structured input are represented without parser abort;
- Nanika Atsume: range/exclusion expansion and optical bounds survive;
- Watchdog Bancho: polygon collision paths and hits agree;
- one 1x1/absent Kero case does not shrink Sakura;
- balloon-only/incompatible archives are explicitly classified; and
- every archive leaves no staging/test install tree after cleanup.

Run: `powershell -ExecutionPolicy Bypass -File scripts/run-nar-corpus-audit.ps1 -DeviceSerial emulator-5554`

Expected: all 23 manifest entries receive one result row, no unmanifested file satisfies an expected entry, and there is no unexplained crash, parser abort, partial install, or coordinate mismatch.

- [ ] **Step 5: Inspect representative corpus screenshots and commit the harness**

Personally inspect screenshots for the smallest, tallest, widest, most asymmetric, polygon-collision, and dual-character packages. Commit scripts/tests/docs only; confirm `git status --short` does not stage `.nar` or generated reports.

```text
git add .gitignore scripts/run-nar-corpus-audit.ps1 src/androidTest/java/com/cattailsw/nanidroid/corpus src/androidTest/AndroidManifest.xml docs/testing.md docs/testing/nar-corpus.md docs/testing/nar-corpus-manifest.json
git commit -m "test: audit real NAR runtime compatibility"
```

### Task 18: Perform hands-on device usability inspection and final verification

**Files:**
- Create: `scripts/run-ui-visual-audit.ps1`
- Modify: `docs/testing.md`
- Generated, not committed: `build/reports/ui-audit/`

**Interfaces:**
- Consumes: debug APK, screenshot goldens, `Nanidroid_API_37` AVD, Task 17 corpus probe, and all automated suites.
- Produces: fresh screenshots, annotated layout captures, a completed human checklist, and final verification evidence for the PR.

- [ ] **Step 1: Implement a reversible visual-audit runner**

The script must cold-start `Nanidroid_API_37` from a disposable snapshot, record original `wm size`, `wm density`, orientation, and `font_scale`, then restore them with the appropriate `wm ... reset` or original value in `finally`; reset the disposable snapshot after the run as protection against host-process termination. It must refuse physical devices and pre-existing app data, install the current debug APK with the Android CLI, perform a UI Automator startup smoke against the real `CatTailApplication`, and capture both layout JSON and screenshots:

```powershell
& 'C:\ProgramData\AndroidCLI\android.exe' run --debug --device=$DeviceSerial --apks=$DebugApk
& 'C:\ProgramData\AndroidCLI\android.exe' layout --pretty --device=$DeviceSerial -o $LayoutPath
& 'C:\ProgramData\AndroidCLI\android.exe' screen capture --device=$DeviceSerial -o $ScreenshotPath
& 'C:\ProgramData\AndroidCLI\android.exe' screen capture --annotate --device=$DeviceSerial -o $AnnotatedPath
```

Exercise `360x720`, `720x360`, `400x1000`, `610x500`, `800x1280`, `1280x800`, `480x230`, and `230x400 dp`, plus font scales `1.0`, `1.5`, and `2.0`. Use `wm size`/`wm density 160` only inside the reversible script, retain native-density phone and tablet passes, and read measured safe-stage bounds from layout capture rather than assuming display override equals app content.

- [ ] **Step 2: Capture scripted fixture states and real corpus representatives**

Capture bundled ghost plus 2elf, one Snake/Otacon, Nanika Atsume, Watchdog Bancho, the smallest/placeholder pair, the tallest pair, and the widest pair. For each representative capture portrait, compact landscape, and the most stressful applicable tablet/multi-window case, including one/two/long bubbles, extracted choices, input IME, overlay, each debug presentation, passive stall prompt, and tiny fallback.

- [ ] **Step 3: Personally inspect every required image with `view_image`**

Generate a versioned case manifest before capture. The executing agent must open every fresh PNG itself and record one row per case in `build/reports/ui-audit/manual-inspection.md`, including artifact SHA-256, measured window/stage, density, font scale, theme, locale, expected invariants, result, and defect link; case-count mismatch fails the audit. Automated pixel comparison alone is not completion. Check:

- Sakura/Kero prominence, aspect ratio, lane containment, bottom alignment, and absence of suspicious whitespace;
- physical Kero-left/Sakura-right ordering in LTR and RTL;
- compact center half ownership and pointer directions;
- bubble readability, scroll affordance, 48 dp action density, IME avoidance, and tablet bounds;
- exact collision outline alignment at face/edge/polygon regions;
- app bar/overflow/debug organization and no stage reclassification when toggled;
- tiny fallback and restoration; and
- absence of clipped content at font scale `2.0`.

Any visual defect returns to the owning TDD task, updates the appropriate golden, and repeats this inspection.

- [ ] **Step 4: Manually probe interaction usability on the current build**

On real rendered corpus ghosts, activate named regions and generic transparent canvas by touch; inject mouse primary single/double clicks; scroll/click bubble content; reopen choices; use keyboard Tab/Shift-Tab/arrows/Page Up/Page Down/Enter/Space/Escape/D-pad; toggle chrome only through empty stage or its labeled semantic action; open/close debug; rotate/resize; and recreate the Activity. Enable TalkBack and Switch Access (or Voice Access), traverse merged/unmerged semantics, invoke collision custom actions, and verify focus recovery after rotation and dialog dismissal. Verify SHIORI diagnostics show exact coordinate, scope, identifier, button, source, and event, with no bubble/surface/chrome leakage.

- [ ] **Step 5: Run final automated verification**

Run, in order:

```text
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lint
.\gradlew.bat assembleDebug assembleRelease
.\gradlew.bat validateDebugScreenshotTest
.\gradlew.bat connectedDebugAndroidTest
powershell -ExecutionPolicy Bypass -File scripts/run-nar-corpus-audit.ps1 -DeviceSerial emulator-5554
git diff --check
git status --short
```

Expected: every command exits zero; the NAR report covers all discovered archives; screenshot HTML has no diff; the manual report has no unresolved failures; no `.nar`, local corpus, generated screenshot, or unrelated user file is staged.

- [ ] **Step 6: Update testing documentation and commit the audit runner**

Document emulator prerequisites, reversible size controls, screenshot/report locations, corpus expectations, and manual inspection ownership. Attach selected fresh screenshots and the command/result summary to the PR.

```text
git add scripts/run-ui-visual-audit.ps1 docs/testing.md
git commit -m "test: document adaptive stage usability audit"
```

## Android Guidance Applied

The implementation and acceptance criteria above incorporate the official documents found through `android docs search` and fetched through the Android CLI:

- `kb://android/develop/ui/compose/layouts/adaptive/adaptive-dos-and-donts`: classify the current app window, remain resizable, support portrait/landscape and multi-window, use panes deliberately, and cap content instead of stretching controls across large windows.
- `kb://android/develop/ui/compose/touch-input/input-compatibility-on-large-screens`: test mouse, keyboard, trackpad/stylus paths and focus navigation. Nanidroid implements click/focus support now while explicitly retaining approved deferrals for hover events, right-click, wheel, and drag.
- `kb://android/develop/ui/compose/accessibility/semantics`: low-level custom-drawn composables require manual semantic meaning; tests inspect both merged and unmerged trees.
- `kb://android/training/testing/different-screens/tools`: use `DeviceConfigurationOverride` for deterministic window/font cases and a connected device for real resize/posture behavior.
- `kb://android/studio/preview/compose-screenshot-testing`: use the dedicated `screenshotTest` source set, reviewed references, `validateDebugScreenshotTest` in CI, and HTML diffs.
- `kb://android/develop/background-work/background-tasks/persistent/how-to/manage-work`: cancel by exact work identity, honor `onStopped()`/`isStopped()` cooperatively, and close owned resources.
- `kb://android/develop/background-work/background-tasks/testing/persistent/integration-testing`: use `work-testing`, `WorkManagerTestInitHelper`, synchronous executors, and `TestDriver` rather than real delays.

## Completion Gate

Do not declare the feature complete until all four milestones are green together. Review the durable transition table as a whole after Tasks 3–4, review every initial/changed golden after Task 16, inspect the NAR summary and representative real-package screenshots after Task 17, and complete the hands-on checklist after Task 18. The final review must inspect the current head, not an earlier commit, and must confirm that no hidden debug UI, partial durable tree, lost action data, or second coordinate implementation remains.
