# GhostRuntime Playback and Snapshot Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make application-scoped `GhostRuntime` the sole mutable owner of SakuraScript playback, dialogue actions, timers, foreground hosts, switch/exit terminals, and the installed catalog, exposing one immutable `RuntimeSnapshot` while deleting `SScriptRunner` and parallel presentation authorities.

**Architecture:** Land characterization tests and framework-free reducers first without wiring a second production path. Build a stateless `SakuraScriptPlayer` and pure host/catalog protocols, assemble them behind `GhostRuntime`'s non-blocking coordination lane under fakes, prove the Android lifecycle adapter, then perform one atomic production cutover that removes runner callbacks, Activity catalog authority, and obsolete presentation prototypes. Native work remains on the FIFO native lane created by PR #402; every native and filesystem result re-enters the coordination lane as a separately fenced command.

**Tech Stack:** Kotlin, Android API 37/minSdk 31, Kotlin coroutines and `StateFlow`, AndroidX Activity/Compose/Lifecycle, Java executor/future primitives, JUnit 4, MockK, AndroidX Test/ActivityScenario, Gradle wrapper, Python `unittest`

**Spec:** `docs/superpowers/specs/2026-08-28-ghost-runtime-playback-snapshot-design.md`

## Global Constraints

- `GhostRuntime` is the only production owner of native session, active/pending identity, catalog, queue/playback, clock/timers, dialogue actions, switch/exit terminals, and foreground host state.
- The coordination lane is serialized and non-blocking: it never waits for JNI, filesystem IO, another executor, or a UI callback.
- The existing FIFO native lane remains the only lane that constructs or calls a SHIORI adapter; each native result returns as a generation/operation-tagged coordination command.
- `SakuraScriptPlayer` is a stateless reducer over immutable `PlayerState`; it cannot schedule, call SHIORI, publish, or retain Android/UI/native objects.
- One read-only `StateFlow<RuntimeSnapshot>` is the only authoritative runtime/presentation state observed by Activity and Compose.
- Host commands are ordered by `(RuntimeHostId, hostEpoch)`; only the newest accepted top-resumed host may claim terminals or acknowledge cues.
- Exit delivery is one non-suspending main-thread enqueue block: `ClaimExit`, exact-Activity `finish()`, then `AcknowledgeExit` in `finally`; a claimed lease is never reassigned across Activities.
- One-shot cues are lossless and ordered only during one continuous active-host lease. Host loss expires that lease's cues while retaining the normalized final frame; hostless playback never stalls or replays expired animation.
- Catalog publication is epoch-linearized as `Loading`, `Ready`, or `Failed`. Startup and bundled installation require proven `Ready`; an install is not ready until a newest-epoch post-commit scan contains its target.
- Preserve the exact `OnFirstBoot`, `OnGhostChanged`, and `OnBoot` attachment selection and exactly one attachment event.
- Preserve switch/exit ordering, authored playback, timers, dialogue/input/choice/anchor, pointer, surface, animation, Readme, foreground NAR import, Satori, YAYA, Kawari, NanidroidShiori, and unsupported-engine behavior.
- Do not add a coordinator, service locator, second authority layer, compatibility facade, Gradle module, Hilt, or WorkManager.
- Delete `SScriptRunner`, its callback interfaces and handler schedulers, mutable `GhostMgr` catalog ownership, renderer callbacks, parallel presentation interpreters, and obsolete layout/interaction prototypes in the production cutover.
- Use the Windows Gradle wrapper; compile/target API 37 and minSdk 31 remain unchanged.
- Keep the canonical 23-NAR and three-engine real-adapter requirements unchanged. If artifacts remain unavailable, record zero available rows and leave the stacked PR draft.
- Physical arm64 runtime remains deferred until a qualifying API 31–37 device exists; build/package/ELF evidence is required and x86_64 evidence must never be called arm64 runtime coverage.
- This branch remains stacked on `codex/phase3-native-runtime-thread` / PR #402 and must not absorb PR #394/#395 corpus-tooling work.

## File Map

- Create `src/main/kotlin/com/cattailsw/nanidroid/runtime/RuntimeSnapshot.kt`: immutable host/action/cue/catalog/terminal/snapshot value types and internal `RuntimeCommand` protocol.
- Create `src/main/kotlin/com/cattailsw/nanidroid/runtime/SakuraScriptPlayer.kt`: immutable `PlayerState`, data-only `PlayerEffect`, and stateless SakuraScript reducer.
- Create `src/main/kotlin/com/cattailsw/nanidroid/runtime/RuntimeHostState.kt`: pure host epoch, exit lease, and cue lease reducer.
- Create `src/main/kotlin/com/cattailsw/nanidroid/runtime/RuntimeCatalog.kt`: pure catalog epoch state plus scan port.
- Create `src/main/kotlin/com/cattailsw/nanidroid/runtime/RuntimeScheduler.kt`: application scheduler seam whose callbacks only submit fenced runtime commands.
- Rewrite `src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt`: coordination lane, command admission, player/catalog/host ownership, snapshot publication, native-result re-entry, and runtime-facing command methods.
- Rewrite `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`: lifecycle host commands, lifecycle-aware snapshot collection, view-local draft restoration, exit delivery block, and snapshot-driven startup/switch/readme/import UI.
- Rewrite `src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt`: render a supplied snapshot for an explicit Activity-owned host lease and acknowledge only that lease's cues; retain only surface pixel/cache, animation scheduler, measurement, and scroll state.
- Rewrite `src/main/kotlin/com/cattailsw/nanidroid/DialogueDialogBinding.kt`: key drafts and submissions by exact runtime generation/dialogue/action identity; never read a runner.
- Modify `src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt`: construct the runtime and forward application-owned foreground-import completion to catalog refresh.
- Delete `src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt`; use runtime catalog state, `InstalledGhostCatalog.scan`, and existing transactional installers directly.
- Modify `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/DialogueContent.kt`: stable action IDs used by snapshot commands instead of object identity.
- Delete `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`, `BootDispatchState.kt`, `GhostPresentationFrame.kt`, and `GhostPresentationRenderer.kt`.
- Delete `src/main/kotlin/com/cattailsw/nanidroid/runtime/GhostStageLayout.kt`, `SakuraScriptInteractionEffects.kt`, `SakuraScriptPresentationState.kt`, `SakuraScriptPresentationInterpreter.kt`, `GhostPresentationState.kt`, and `KotlinGhostPresentationRuntime.kt` after their retained behavior is represented by the new player/snapshot tests.
- Modify `build.gradle.kts`: replace representative JaCoCo class targets for the deleted runner/presentation adapter with `GhostRuntime` and `SakuraScriptPlayer`.
- Replace runner fixtures/tests with `RuntimeFixture`, `SakuraScriptPlayerTest`, `RuntimeHostStateTest`, `RuntimeCatalogTest`, and migrated runtime behavior suites.
- Rewrite `src/androidTest/java/com/cattailsw/nanidroid/SScriptRunnerMainThreadRequestInstrumentationTest.kt` as `GhostRuntimeMainThreadRequestInstrumentationTest.kt`; extend `NanidroidLifecycleInstrumentationTest.kt` for snapshot/host/exit/cue ownership.
- Update the eight affected source contracts under `tools/` to require final runtime ownership and deleted runner/renderer/catalog authority.
- Modify `docs/testing.md` only for renamed focused tests and commands; do not duplicate PR #394/#395 corpus framework documentation.

---

### Task 1: Freeze Playback, Host, and Catalog Behavior

**Files:**
- Create: `src/test/java/com/cattailsw/nanidroid/RuntimeCoordinationFixture.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/RuntimeFixture.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueTimingTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerBootDispatchTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerHostBindingTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueObserverTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/GhostSwitchingCharacterizationTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/NanidroidGhostStartupTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SakuraScriptCharacterizationTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerAuthorityTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/runtime/GhostStageLayoutPolicyTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/stage/GhostStageLayoutPolicyTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/runtime/stage/SurfaceSizingPropertyTest.kt`

**Interfaces:**
- Consumes: current runner/runtime behavior at `b7308745`.
- Produces: `ManualRuntimeScheduler`, `BlockingRuntimeAdapter`, and named characterization cases that later tasks migrate without weakening assertions.

- [ ] **Step 1: Add deterministic test infrastructure without changing production**

Create `RuntimeCoordinationFixture.kt` with these exact test seams:

```kotlin
internal class ManualRuntimeScheduler : SScriptPlaybackScheduler {
    data class Scheduled(val delayMillis: Long, val action: () -> Unit)
    private val pending = ArrayDeque<Scheduled>()

    override fun schedule(delayMillis: Long, action: () -> Unit) {
        pending += Scheduled(delayMillis, action)
    }

    override fun cancelPending() = pending.clear()
    fun runNext() = requireNotNull(pending.removeFirstOrNull()).action()
    fun runAll() { while (pending.isNotEmpty()) runNext() }
    fun delays(): List<Long> = pending.map(Scheduled::delayMillis)
}

internal class BlockingRuntimeAdapter(
    private val delegate: Shiori,
) : Shiori by delegate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun request(request: String): String {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        return delegate.request(request)
    }
}
```

Construct the decorator around the fixture's existing `RecordingShiori(trace, ownerGhostId)` instance. Do not subclass or open that final fake, expose a production adapter, or change production construction.

- [ ] **Step 2: Add current-behavior characterization cases**

Add named tests with exact event/order assertions:

```kotlin
@Test fun attachmentSelectsExactlyOneFirstBootGhostChangedOrBootEvent()
@Test fun authoredPlaybackContinuesWhileClockOwnerIsAbsent()
@Test fun blockedTimerResponseCannotEnterAfterClockEpochChanges()
@Test fun repeatedBackJoinsOneExitOperation()
@Test fun switchPlaybackOwnsOutgoingResponseBeforeUnload()
@Test fun equalAnimationIdsFromSeparateCommandsAreSeparateRenderCalls()
@Test fun pendingInputRestoresOnlyAgainstSameDialogueIncarnationAndGeneration()
@Test fun foregroundImportRefreshCannotPublishPreCommitCatalogScan()
```

For attachment, require one request ID, never a pair:

```kotlin
assertEquals(listOf("OnFirstBoot"), requestIds(firstActivation))
assertEquals(listOf("OnGhostChanged"), requestIds(switchedReturn))
assertEquals(listOf("OnBoot"), requestIds(ordinaryReturn))
```

For hostless playback, stop the clock owner, run at least 65 immediate animation-bearing script commands through the manual scheduler, and assert the final visible surface/text plus playback completion. The current runner need not expose cue retention; this case freezes the required non-stalling behavior for migration.

Split the seven existing adaptive-policy tests out of `runtime/GhostStageLayoutPolicyTest.kt` into `runtime/stage/GhostStageLayoutPolicyTest.kt` without changing assertions. Add current-production replacements for the four obsolete pixel-facade invariants:

```kotlin
@Test fun migrationInvariant_zeroSafeBoundsPublishesTinyFallbackWithoutInteractiveRects()
@Test fun migrationInvariant_wideStageUsesPhysicalSpeakerLanesAndAdaptiveBubbleBands()
@Test fun migrationInvariant_shortKeroRetainsAdaptiveBubbleAndSurfaceRegions()
@Test fun migrationInvariant_oversizedSurfacesFitAssignedRegionsBeforePlacement()
```

The first three exercise `runtime.stage.GhostStageLayoutPolicy`; the last exercises `SurfaceSizingPolicy`. Keep the old four tests until Task 7, but explicitly record that their unused View-era pixel formulas are superseded by these production Compose contracts rather than silently discarding them.

- [ ] **Step 3: Run the characterization slice**

```powershell
.\gradlew.bat testDebugUnitTest `
  --tests "com.cattailsw.nanidroid.SScriptRunnerDialogueTimingTest" `
  --tests "com.cattailsw.nanidroid.SScriptRunnerBootDispatchTest" `
  --tests "com.cattailsw.nanidroid.SScriptRunnerPresentationTest" `
  --tests "com.cattailsw.nanidroid.SScriptRunnerHostBindingTest" `
  --tests "com.cattailsw.nanidroid.SScriptRunnerDialogueObserverTest" `
  --tests "com.cattailsw.nanidroid.GhostSwitchingCharacterizationTest" `
  --tests "com.cattailsw.nanidroid.NanidroidGhostStartupTest" `
  --tests "com.cattailsw.nanidroid.SakuraScriptCharacterizationTest" `
  --tests "com.cattailsw.nanidroid.SScriptRunnerAuthorityTest" `
  --tests "com.cattailsw.nanidroid.runtime.GhostStageLayoutPolicyTest" `
  --tests "com.cattailsw.nanidroid.runtime.stage.GhostStageLayoutPolicyTest" `
  --tests "com.cattailsw.nanidroid.runtime.stage.SurfaceSizingPropertyTest" `
  --rerun-tasks
```

Expected: PASS on the unchanged production path. Do not proceed with a misunderstood or failing characterization.

- [ ] **Step 4: Obtain independent characterization review and commit**

Ask one Android reviewer to verify the expected lifecycle semantics and one adversarial reviewer to look for assertions that merely restate implementation details. Fix verified weaknesses, rerun Step 3, then commit:

```powershell
git add src/test/java/com/cattailsw/nanidroid
git commit -m "test: freeze runtime playback ownership behavior"
```

---

### Task 2: Define the Immutable Runtime Protocol

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/RuntimeSnapshot.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/DialogueContent.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/RuntimeSnapshotTest.kt`

**Interfaces:**
- Consumes: immutable `InstalledGhostMetadata`, `SurfaceCatalog`, `DialogueContent`, `GhostRuntimeMode`, `SurfaceInteractionEffect`, and `GhostRuntimePhase`.
- Produces: exact data-only `RuntimeSnapshot`, host/action/cue/exit/catalog identities, and `RuntimeCommand`. No production consumer is wired in this task.

- [ ] **Step 1: Write red immutability and identity tests**

Create tests that assert equal-valued actions and cues remain distinct by identity, copied inputs cannot mutate snapshots, stale host/action identities differ, and forbidden object types do not appear in snapshot fields:

```kotlin
@Test fun equalCuePayloadsRemainDistinct() {
    val lease = RuntimeHostLease(RuntimeHostId(7), 3)
    val a = RuntimePresentationCue(1, 9, lease, GhostSpeaker.SAKURA, RuntimeCueKind.ONE_SHOT, "2")
    val b = a.copy(cueId = 2)
    assertNotEquals(a, b)
}

@Test fun snapshotTypesContainNoAndroidNativeOrCallbackObjects() {
    val forbidden = setOf(
        android.content.Context::class.java,
        android.app.Activity::class.java,
        java.io.FileDescriptor::class.java,
        java.util.concurrent.locks.Lock::class.java,
    )
    RuntimeSnapshot::class.java.declaredFields.forEach { field ->
        assertTrue("forbidden ${field.type}", forbidden.none { it.isAssignableFrom(field.type) })
    }
}
```

Run and expect compilation failure because the protocol types do not exist:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.runtime.RuntimeSnapshotTest"
```

- [ ] **Step 2: Add stable action identities to dialogue values**

In `DialogueContent.kt`, add:

```kotlin
internal data class DialogueActionKey(
    val generation: Long,
    val incarnation: Long,
    val actionId: Long,
)

internal data class RuntimeChoiceAction(val key: DialogueActionKey, val action: DialogueAction)
internal data class RuntimeAnchorAction(val key: DialogueActionKey, val action: AnchorAction)
internal data class RuntimeInputAction(val key: DialogueActionKey, val pending: PendingInputState)
```

Keep parsed `DialogueAction`, `AnchorAction`, and `InputBoxSpec` as payload values. The player in Task 3 assigns monotonically increasing `actionId` values when it adopts an authored script; Activity commands carry `DialogueActionKey` and never depend on Kotlin object identity.

- [ ] **Step 3: Implement the exact snapshot and command contract**

Create `RuntimeSnapshot.kt` with these module-private shapes (helper enums/data classes remain in the same file). Every declaration is explicitly `internal`; do not promote currently internal constituent types to make this compile:

```kotlin
@JvmInline internal value class RuntimeHostId(val value: Long)

internal data class RuntimeHostLease(val hostId: RuntimeHostId, val hostEpoch: Long)

internal data class RuntimeSpeakerPresentation(
    val text: String,
    val surfaceId: String,
    val surfaceEpoch: Long,
    val balloonVisible: Boolean,
)

internal data class RuntimeSurfaceIdentity(
    val generation: Long,
    val speaker: GhostSpeaker,
    val surfaceId: String,
    val surfaceEpoch: Long,
)

internal data class RuntimePresentation(
    val sakura: RuntimeSpeakerPresentation,
    val kero: RuntimeSpeakerPresentation,
    val talkingAnimationEnabled: Boolean,
)

internal enum class RuntimeCueKind { TALKING, ONE_SHOT }

internal data class RuntimePresentationCue(
    val cueId: Long,
    val generation: Long,
    val hostLease: RuntimeHostLease,
    val speaker: GhostSpeaker,
    val kind: RuntimeCueKind,
    val animationId: String?,
)

internal sealed interface RuntimeCatalogState {
    val epoch: Long
    val lastProvenEntries: List<InstalledGhostMetadata>
    val publications: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus>
    data class Loading(
        override val epoch: Long,
        override val lastProvenEntries: List<InstalledGhostMetadata>,
        override val publications: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus>,
    ) : RuntimeCatalogState
    data class Ready(
        override val epoch: Long,
        val entries: List<InstalledGhostMetadata>,
        override val publications: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus>,
    ) : RuntimeCatalogState {
        override val lastProvenEntries: List<InstalledGhostMetadata> = entries
    }
    data class Failed(
        override val epoch: Long,
        override val lastProvenEntries: List<InstalledGhostMetadata>,
        override val publications: Map<CatalogPublicationToken, RuntimeCatalogPublicationStatus>,
        val reason: RuntimeNoticeCode,
    ) : RuntimeCatalogState
}

internal sealed interface RuntimeCatalogPublicationStatus {
    val targetId: String
    data class Pending(override val targetId: String, val requestedEpoch: Long) : RuntimeCatalogPublicationStatus
    data class Ready(override val targetId: String, val provenEpoch: Long) : RuntimeCatalogPublicationStatus
    data class RecoveryRequired(
        override val targetId: String,
        val failedEpoch: Long,
        val reason: RuntimeNoticeCode,
    ) : RuntimeCatalogPublicationStatus
}

internal data class RuntimeExitLease(
    val operationId: Long,
    val leaseId: Long,
    val generation: Long?,
    val hostLease: RuntimeHostLease,
)

internal data class RuntimeExitSnapshot(
    val operationId: Long,
    val generation: Long?,
    /** Present only while this unclaimed lease is offered to its exact host. */
    val offeredLease: RuntimeExitLease?,
)

internal data class RuntimeDialogueSnapshot(
    val state: DialogueRuntimeState,
    val choices: List<RuntimeChoiceAction>,
    val anchors: List<RuntimeAnchorAction>,
    val input: RuntimeInputAction?,
)

internal data class RuntimeSnapshot(
    val revision: Long,
    val generation: Long?,
    val phase: GhostRuntimePhase,
    val activeGhostId: String?,
    val activeSurfaces: SurfaceCatalog?,
    val pending: PendingGhostIdentity?,
    val catalog: RuntimeCatalogState,
    val presentation: RuntimePresentation,
    val cues: List<RuntimePresentationCue>,
    val dialogue: RuntimeDialogueSnapshot,
    val mode: GhostRuntimeMode,
    val modeIdentity: RuntimeModeIdentity,
    val clockRunning: Boolean,
    val foregroundHost: RuntimeHostLease?,
    val exit: RuntimeExitSnapshot?,
    val notice: RuntimeNotice?,
) {
    companion object {
        fun initial(): RuntimeSnapshot
    }
}
```

Define internal `RuntimeNoticeCode` as a closed enum and internal `RuntimeNotice(operationId, code)` as data only. Use this exact internal command boundary:

```kotlin
internal data class RuntimeModeIdentity(
    val generation: Long?,
    val modeRevision: Long,
    val parentOperationId: Long?,
    val parentPhaseRevision: Long?,
)

internal sealed interface RuntimeRequestOrigin {
    data class Playback(val playbackToken: Long) : RuntimeRequestOrigin
    data class Timer(
        val clockEpoch: Long,
        val kind: RuntimeTimerKind,
        val bucket: Long,
        val mode: RuntimeModeIdentity,
    ) : RuntimeRequestOrigin
    data class Dialogue(val action: DialogueActionKey) : RuntimeRequestOrigin
    data class Pointer(val surface: RuntimeSurfaceIdentity) : RuntimeRequestOrigin
    data class Parent(val operationId: Long, val phaseRevision: Long) : RuntimeRequestOrigin
    data class Attachment(val operationId: Long) : RuntimeRequestOrigin
}

internal data class RuntimeRequestToken(
    val generation: Long,
    val requestId: Long,
    val parentOperationId: Long?,
    val origin: RuntimeRequestOrigin,
)

internal data class CatalogPublicationToken(
    val source: String,
    val value: String,
)

internal enum class RuntimeTimerKind { SECOND, MINUTE }

internal sealed interface RuntimeCatalogScanOutcome {
    data class Scanned(val entries: List<InstalledGhostMetadata>) : RuntimeCatalogScanOutcome
    data class Failed(val reason: RuntimeNoticeCode) : RuntimeCatalogScanOutcome
}

internal sealed interface RuntimePreparationOutcome {
    data class Prepared(val value: PreparedGhost) : RuntimePreparationOutcome
    data class Failed(val reason: RuntimeNoticeCode) : RuntimePreparationOutcome
}

internal sealed interface RuntimeNativeLifecycleOutcome {
    data object Success : RuntimeNativeLifecycleOutcome
    data class Failed(val reason: RuntimeNoticeCode, val ownershipCertain: Boolean) : RuntimeNativeLifecycleOutcome
}

internal sealed interface RuntimeCommand {
    data class RegisterHost(val lease: RuntimeHostLease) : RuntimeCommand
    data class SetResumed(val lease: RuntimeHostLease, val resumed: Boolean) : RuntimeCommand
    data class SetTopResumed(val lease: RuntimeHostLease, val topResumed: Boolean) : RuntimeCommand
    data class UnregisterHost(val lease: RuntimeHostLease) : RuntimeCommand
    data class StartGhost(val ghostId: String, val canonicalRoot: File) : RuntimeCommand
    data class PreparationCompleted(val operationId: Long, val outcome: RuntimePreparationOutcome) : RuntimeCommand
    data class NativeLoadCompleted(val operationId: Long, val generation: Long, val outcome: RuntimeNativeLifecycleOutcome) : RuntimeCommand
    data class NativeUnloadCompleted(val operationId: Long, val generation: Long, val outcome: RuntimeNativeLifecycleOutcome) : RuntimeCommand
    data class PlaybackDue(val generation: Long, val token: Long) : RuntimeCommand
    data class TimerDue(val generation: Long, val clockEpoch: Long, val kind: RuntimeTimerKind, val bucket: Long) : RuntimeCommand
    data class InputExpired(val key: DialogueActionKey, val elapsedMillis: Long) : RuntimeCommand
    data class NativeResponse(val token: RuntimeRequestToken, val result: RuntimeResult<TaggedShioriResponse>) : RuntimeCommand
    data class CatalogChanged(val token: CatalogPublicationToken, val targetId: String) : RuntimeCommand
    data class CatalogScanned(val epoch: Long, val outcome: RuntimeCatalogScanOutcome) : RuntimeCommand
    data class RetryCatalog(val publication: CatalogPublicationToken?, val expectedFailureEpoch: Long) : RuntimeCommand
    data class Back(val generation: Long?, val host: RuntimeHostLease, val expected: RuntimeModeIdentity) : RuntimeCommand
    data class SwitchGhost(val generation: Long, val host: RuntimeHostLease, val expected: RuntimeModeIdentity, val targetGhostId: String) : RuntimeCommand
    data class Pointer(val generation: Long, val host: RuntimeHostLease, val surface: RuntimeSurfaceIdentity, val effect: SurfaceInteractionEffect) : RuntimeCommand
    data class ActivateChoice(val key: DialogueActionKey) : RuntimeCommand
    data class ActivateAnchor(val key: DialogueActionKey) : RuntimeCommand
    data class SubmitInput(val key: DialogueActionKey, val value: String) : RuntimeCommand
    data class DismissInput(val key: DialogueActionKey) : RuntimeCommand
    data class ClaimExit(val lease: RuntimeExitLease) : RuntimeCommand
    data class AcknowledgeExit(val lease: RuntimeExitLease) : RuntimeCommand
    data class AcknowledgeCues(val host: RuntimeHostLease, val throughCueId: Long) : RuntimeCommand
}
```

Every user action carries generation plus its narrow action/host identity; Back and switch also carry their expected mode/parent identity. `RuntimeSnapshot.initial()` exposes the exact idle identity `RuntimeModeIdentity(null, 0L, null, null)`, allowing no-runtime Back without a sentinel; switch still requires a non-null generation and an expected identity whose generation matches it. Every request token records a typed origin so response admission can validate playback token, clock epoch/bucket/mode, claimed dialogue action incarnation, pointer surface epoch, parent phase, or attachment operation without using global snapshot revision. `File`, `PreparedGhost`, and `Throwable`-bearing native results are permitted only on internal commands and are never copied into `RuntimeSnapshot`.

- [ ] **Step 4: Prove value safety and commit**

Add frozen collection helpers that make a defensive copy and wrap it with `Collections.unmodifiableList`, `Collections.unmodifiableSet`, or `Collections.unmodifiableMap`. The sole snapshot publication factory recursively freezes catalog entries and the token-keyed publication-status map, dialogue contents/actions, cue inventory, and every nested collection before assigning `MutableStateFlow.value`; `toList()` alone is not accepted. Tests cast each reachable collection back to its mutable JVM interface, attempt `clear`/`put`, require `UnsupportedOperationException`, and confirm the previously published snapshot remains equal. Walk the complete snapshot type graph rather than checking direct fields only. Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.runtime.RuntimeSnapshotTest" --tests "com.cattailsw.nanidroid.runtime.dialogue.*"
```

Expected: PASS. Then commit:

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/runtime/RuntimeSnapshot.kt src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/DialogueContent.kt src/test/java/com/cattailsw/nanidroid/runtime/RuntimeSnapshotTest.kt src/test/java/com/cattailsw/nanidroid/runtime/dialogue
git commit -m "Define immutable runtime snapshot protocol"
```

---

### Task 3: Extract the Stateless SakuraScript Player

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/SakuraScriptPlayer.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/SakuraScriptPlayerTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueTimingTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueObserverTest.kt`

**Interfaces:**
- Consumes: `RuntimePresentation`, `RuntimeDialogueSnapshot`, tokenizer/parser payloads, monotonic elapsed time supplied by commands, and generation-tagged native response data.
- Produces: `PlayerState + PlayerCommand -> PlayerTransition`; data-only effects consumed by `GhostRuntime` in Task 5.

- [ ] **Step 1: Write red reducer tests from the characterization matrix**

Create tests for enqueue/start, per-character reveal, waits, scope/surface/balloon changes, talking and explicit animation cues, `\e`, clear, passive mode, authored SHIORI suspension/resume, stale response, parser failure, dialogue action IDs, input timeout, choice/anchor dispatch, and switch/exit playback terminals. Also create every `SakuraScriptPlayerTest` replacement method named in Task 7 Step 6 during this task; Task 7 may delete an old source only after its exact mapped replacement already exists and passes.

The central test shape is:

```kotlin
val initial = PlayerState.initial(generation = 4)
val enqueued = SakuraScriptPlayer.reduce(initial, PlayerCommand.Enqueue("\\0hello\\e", parent = null))
val started = SakuraScriptPlayer.reduce(enqueued.state, PlayerCommand.Advance(enqueued.state.playbackToken))
assertEquals("h", started.state.presentation.sakura.text)
assertEquals(listOf(PlayerEffect.SchedulePlayback(started.state.playbackToken, 50L)), started.effects)
assertTrue(PlayerEffect::class.sealedSubclasses.none {
    it.simpleName in setOf("PublishSnapshot", "CallUi")
})
```

Run and expect compilation failure:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.runtime.SakuraScriptPlayerTest"
```

- [ ] **Step 2: Implement immutable player inputs, state, and effects**

Use these exact boundaries:

```kotlin
internal data class PlayerPayload(
    val script: String,
    val parent: PlayerParent?,
)

internal data class PlayerCursor(
    val payload: PlayerPayload,
    val charIndex: Int,
    val speaker: GhostSpeaker,
    val waitMillis: Long,
    val wholeLine: Boolean,
    val quickSession: Boolean,
    val synchronizedSession: Boolean,
)

internal data class PlayerState(
    val generation: Long,
    val queue: List<PlayerPayload>,
    val current: PlayerCursor?,
    val presentation: RuntimePresentation,
    val dialogue: RuntimeDialogueSnapshot,
    val passive: Boolean,
    val authoredRequest: RuntimeRequestOrigin.Playback?,
    val playbackToken: Long,
    val nextActionId: Long,
) {
    companion object {
        fun initial(generation: Long): PlayerState
    }
}

internal sealed interface PlayerParent {
    data class Switch(val operationId: Long) : PlayerParent
    data class Exit(val operationId: Long) : PlayerParent
}

internal sealed interface PlayerCommand {
    data class Enqueue(val script: String, val parent: PlayerParent?) : PlayerCommand
    data class Advance(val token: Long) : PlayerCommand
    data class NativeResponse(val token: RuntimeRequestToken, val response: PlayerResponse) : PlayerCommand
    data class ActivateChoice(val key: DialogueActionKey) : PlayerCommand
    data class ActivateAnchor(val key: DialogueActionKey) : PlayerCommand
    data class SubmitInput(val key: DialogueActionKey, val value: String) : PlayerCommand
    data class DismissInput(val key: DialogueActionKey) : PlayerCommand
    data class InputExpired(val key: DialogueActionKey, val elapsedMillis: Long) : PlayerCommand
    data class Clear(val owner: PlayerParent?) : PlayerCommand
}

internal sealed interface PlayerResponse {
    data class Returned(val response: ShioriResponse) : PlayerResponse
    data object ReplayableFailure : PlayerResponse
    data object FatalFailure : PlayerResponse
    data object StaleGeneration : PlayerResponse
}

internal sealed interface PlayerEffect {
    data class SchedulePlayback(val token: Long, val delayMillis: Long) : PlayerEffect
    data class RequestShiori(val origin: RuntimeRequestOrigin, val intent: ShioriRequestIntent, val fallback: ShioriRequestIntent?) : PlayerEffect
    data class PresentationCue(val speaker: GhostSpeaker, val kind: RuntimeCueKind, val animationId: String?) : PlayerEffect
    data class ParentCompleted(val parent: PlayerParent) : PlayerEffect
}

internal data class PlayerTransition(val state: PlayerState, val effects: List<PlayerEffect>)

internal object SakuraScriptPlayer {
    fun reduce(state: PlayerState, command: PlayerCommand): PlayerTransition
}
```

Do not put a scheduler, executor, `Context`, `Handler`, observer, renderer, `GhostRuntime`, `GhostHandle`, or callback in `PlayerState` or `SakuraScriptPlayer`.

- [ ] **Step 3: Port runner behavior into the reducer without delegating production**

Move pure parsing/projection logic from `SScriptRunner` by copy-then-delete-later, not by calling runner methods. Preserve `WAIT_UNIT = 50L`, `WAIT_YEN_E = 1000L`, no-wait tests, authored-response suspension, fallback request construction, `OnUserInputCancel` fallback, clear semantics, and exact dialogue projection. `PlayerEffect.RequestShiori` carries a typed origin and request data, never an allocated request ID; the runtime allocates and registers the exact `RuntimeRequestToken` when consuming that effect. The player stores only the playback origin needed to suspend authored playback, and runtime admission validates the exact response token before reducing it. Assign action IDs once when an authored payload is adopted and retain them across incremental reveal. Increment a speaker's `surfaceEpoch` only when that speaker's authoritative surface ID changes; pointer commands must match generation, speaker, surface ID, and surface epoch, so unrelated dialogue/clock/catalog revisions cannot invalidate current geometry.

Convert the three migrated test classes to assert both old runner behavior and new player transitions from the same fixtures. They stop testing the runner only in Task 7, when production cuts over atomically.

- [ ] **Step 4: Run player and characterization tests**

```powershell
.\gradlew.bat testDebugUnitTest `
  --tests "com.cattailsw.nanidroid.runtime.SakuraScriptPlayerTest" `
  --tests "com.cattailsw.nanidroid.SScriptRunnerDialogueTimingTest" `
  --tests "com.cattailsw.nanidroid.SScriptRunnerPresentationTest" `
  --tests "com.cattailsw.nanidroid.SScriptRunnerDialogueObserverTest" `
  --rerun-tasks
```

Expected: PASS, with production still using only `SScriptRunner`.

- [ ] **Step 5: Obtain independent pure-reducer review and commit**

Require one reviewer to compare every `SScriptRunner` parser/playback branch with a reducer test, and a second reviewer to reject hidden scheduling, mutable collections, callbacks, or Android/native references. Fix findings, rerun Step 4, then commit:

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/runtime/SakuraScriptPlayer.kt src/test/java/com/cattailsw/nanidroid/runtime/SakuraScriptPlayerTest.kt src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueTimingTest.kt src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueObserverTest.kt
git commit -m "Extract stateless SakuraScript player"
```

---

### Task 4: Prove Host, Exit, Cue, and Catalog Reducers

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/RuntimeHostState.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/RuntimeCatalog.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/RuntimeHostStateTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/runtime/RuntimeCatalogTest.kt`

**Interfaces:**
- Consumes: Task 2 identities and immutable installed metadata.
- Produces: pure `RuntimeHostReducer.reduce`, `RuntimeCatalog.reduce`, and `RuntimeCatalogScanner`; `GhostRuntime` owns the returned state in Task 5.

- [ ] **Step 1: Write the red host/terminal/cue matrix**

Cover register/resume/top-resume/unregister epoch ordering, supersession, stale commands, exit offer/claim/ack, host loss before delivery block, claim followed by queued acknowledgement before lifecycle loss, cue prefix acknowledgement, stale acknowledgement, active-host capacity, host loss expiration, and hostless 65-cue completion.

```kotlin
@Test fun claimThenAckPrecedeFinishTriggeredLifecycleLoss() {
    val lease = activeExitLease()
    val afterClaim = RuntimeHostReducer.reduce(stateWith(lease), RuntimeHostInput.Command(RuntimeCommand.ClaimExit(lease)))
    val afterAck = RuntimeHostReducer.reduce(afterClaim.state, RuntimeHostInput.Command(RuntimeCommand.AcknowledgeExit(lease)))
    val afterLoss = RuntimeHostReducer.reduce(afterAck.state, RuntimeHostInput.Command(topResumedFalse(lease.hostLease)))
    assertNull(afterLoss.state.exit)
    assertTrue(afterLoss.effects.none { it is RuntimeHostEffect.OfferExit })
}

@Test fun hostlessCuesExpireWithoutBackpressure() {
    val state = RuntimeHostState.empty()
    val final = (1L..65L).fold(state) { current, id ->
        RuntimeHostReducer.reduce(current, RuntimeHostInput.Cue(id, cuePayload())).state
    }
    assertTrue(final.cues.isEmpty())
    assertFalse(final.playerBackpressured)
}
```

- [ ] **Step 2: Implement the pure host reducer**

Use these exact reducer boundaries:

```kotlin
internal data class RuntimeHostState(
    val registeredEpochs: Map<RuntimeHostId, Long>,
    val resumed: Set<RuntimeHostLease>,
    val topResumed: RuntimeHostLease?,
    val nextExitLeaseId: Long,
    val exit: RuntimeExitSnapshot?,
    val claimedExitLease: RuntimeExitLease?,
    val cues: List<RuntimePresentationCue>,
    val playerBackpressured: Boolean,
) {
    companion object {
        fun empty(): RuntimeHostState
    }
}

internal data class RuntimeCuePayload(
    val generation: Long,
    val speaker: GhostSpeaker,
    val kind: RuntimeCueKind,
    val animationId: String?,
)

internal sealed interface RuntimeHostInput {
    data class Command(val command: RuntimeCommand) : RuntimeHostInput
    data class OfferExit(val operationId: Long, val generation: Long?) : RuntimeHostInput
    data class Cue(val cueId: Long, val payload: RuntimeCuePayload) : RuntimeHostInput
}

internal data class RuntimeHostTransition(
    val state: RuntimeHostState,
    val effects: List<RuntimeHostEffect>,
)

internal sealed interface RuntimeHostEffect {
    data class OfferExit(val lease: RuntimeExitLease) : RuntimeHostEffect
    data class BackpressureChanged(val paused: Boolean) : RuntimeHostEffect
}

internal object RuntimeHostReducer {
    fun reduce(state: RuntimeHostState, input: RuntimeHostInput): RuntimeHostTransition
}
```

`RuntimeHostState` contains registered host epochs, resumed set, current top lease, next exit lease ID, optional exit terminal, ordered cue suffix, and `playerBackpressured`. `reduce` copies every published collection. Capacity is exactly 64. Capacity pauses only while the same valid top-resumed host lease exists; losing or superseding that lease clears cues and backpressure.

An accepted `ClaimExit` moves the exact offered token into internal `claimedExitLease` and clears `RuntimeExitSnapshot.offeredLease`; it does not mutate the external token observed by Activity. `SetTopResumed(false)`, `UnregisterHost`, and supersession may reassign only a still-offered lease. `AcknowledgeExit` matches operation ID, lease ID, generation, host ID, and host epoch against `claimedExitLease`, requires that internal claimed record, then consumes the terminal. No reducer effect calls `finish()`.

- [ ] **Step 3: Write and implement the catalog epoch matrix**

Define:

```kotlin
internal fun interface RuntimeCatalogScanner {
    fun scan(): List<InstalledGhostMetadata>
}

internal data class RuntimeCatalogOwner(
    val state: RuntimeCatalogState,
    val requestedEpoch: Long,
    val scanInFlight: Boolean,
    val dirty: Boolean,
)

internal sealed interface RuntimeCatalogEffect {
    data class StartScan(val epoch: Long) : RuntimeCatalogEffect
    data class PublicationReady(val token: CatalogPublicationToken, val targetId: String) : RuntimeCatalogEffect
    data class PublicationRecoveryRequired(
        val token: CatalogPublicationToken,
        val targetId: String,
        val reason: RuntimeNoticeCode,
    ) : RuntimeCatalogEffect
}

internal data class RuntimeCatalogTransition(
    val owner: RuntimeCatalogOwner,
    val effects: List<RuntimeCatalogEffect>,
)

internal object RuntimeCatalog {
    fun reduce(owner: RuntimeCatalogOwner, command: RuntimeCommand): RuntimeCatalogTransition
}
```

`RuntimeCatalog.reduce` accepts only `RuntimeCommand.CatalogChanged`, `RuntimeCommand.CatalogScanned`, and `RuntimeCommand.RetryCatalog`; passing another variant is an effect-free no-op. Test initial `Loading -> Ready`, initial failure, bundled install only after `Ready(empty)`, blocked scan followed by install commit, coalesced dirty refresh, stale epoch rejection, post-commit required-target scan, epoch-fenced retry, and active generation unaffected by catalog publication. Duplicate `CatalogChanged` delivery with the same publication token is an effect-free no-op. A new token advances `requestedEpoch` and enters `Pending(targetId, requestedEpoch)`; changes arriving during a scan coalesce into one next newest-epoch scan.

Settle every pending publication independently from that same scan. A case-insensitive target match produces `Ready(targetId, provenEpoch)` plus `PublicationReady`; an omitted target or failed scan produces token-keyed `RecoveryRequired(targetId, failedEpoch, reason)` plus `PublicationRecoveryRequired` without blocking a different token whose target was proven. Preserve already-ready token statuses. `RetryCatalog(null, expectedFailureEpoch)` is accepted only for a matching global `Failed` epoch; it advances `requestedEpoch` and atomically returns every `RecoveryRequired` publication from that exact failed epoch to `Pending(targetId, newEpoch)` before scheduling/coalescing the scan. `RetryCatalog(token, expectedFailureEpoch)` is accepted only when that exact token remains `RecoveryRequired` at that failure epoch; it advances `requestedEpoch`, returns only that token to `Pending`, and schedules/coalesces a new scan. Stale, duplicate, already-ready, or mismatched retries are effect-free. Test failed scan with multiple pending tokens, global retry, then independent `PublicationReady` settlement for every target present in the successful retry scan.

- [ ] **Step 4: Run and commit the pure state machines**

```powershell
.\gradlew.bat testDebugUnitTest `
  --tests "com.cattailsw.nanidroid.runtime.RuntimeHostStateTest" `
  --tests "com.cattailsw.nanidroid.runtime.RuntimeCatalogTest" `
  --rerun-tasks
```

Expected: PASS. Commit:

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/runtime/RuntimeHostState.kt src/main/kotlin/com/cattailsw/nanidroid/runtime/RuntimeCatalog.kt src/test/java/com/cattailsw/nanidroid/runtime/RuntimeHostStateTest.kt src/test/java/com/cattailsw/nanidroid/runtime/RuntimeCatalogTest.kt
git commit -m "Model runtime host and catalog ownership"
```

---

### Task 5: Assemble GhostRuntime's Non-Blocking Coordination Lane

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/RuntimeScheduler.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt:183-1348`
- Modify: `src/test/java/com/cattailsw/nanidroid/RuntimeFixture.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/GhostRuntimeTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/GhostRuntimeNativeThreadTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/GhostRuntimeAttachmentTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/GhostRuntimeSwitchTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/GhostRuntimeSnapshotTest.kt`

**Interfaces:**
- Consumes: Tasks 2–4 reducers, a test-only fake native port, `InstalledGhostCatalog.scan`, and `GhostPreparer`.
- Produces: a test-constructor-only snapshot coordination mode with `GhostRuntime.snapshots: StateFlow<RuntimeSnapshot>`, non-blocking `submit(RuntimeCommand)`, virtual scheduler injection, and high-level host/user/catalog command helpers. Normal `GhostRuntime(context)`, `CatTailApplication`, `Nanidroid`, and the real native/session path remain exclusively runner-owned until Task 7.

- [ ] **Step 1: Write red coordination-order tests**

Use a blocked fake adapter and controllable command dispatcher to prove:

```kotlin
runtime.submit(requestCommand)
assertTrue(adapter.entered.await(5, TimeUnit.SECONDS))
runtime.submit(hostLoss)
runtime.submit(RuntimeCommand.Back(generation, hostLease, expectedMode))
adapter.release.countDown()
fixture.drainCoordination()
assertEquals(listOf("request", "hostLoss", "back", "nativeResponseRejected"), fixture.trace)
```

Add analogous cases for clear, switch, catalog dirtying, timer epoch invalidation, response tail re-entry, atomic snapshot consistency, and no collector invocation while runtime/native locks are held. For dialogue, prove a blocked normal choice clears its sibling set and creates one exact claim, a duplicate sibling command emits no second request, and only its matching response is admitted. Separately prove a no-content anchor response removes its request claim but leaves the anchor reusable for a second activation; a local-script choice clears siblings and enqueues without a native request/claim; timeout claims the exact input and admits its fallback response; and Back, switch, dialogue replacement, and generation retirement reject each late claimed response.

- [ ] **Step 2: Add the scheduler and coordination seams**

Create:

```kotlin
internal interface RuntimeScheduler : Closeable {
    fun schedule(key: RuntimeScheduleKey, delayMillis: Long, action: () -> Unit)
    fun cancel(key: RuntimeScheduleKey)
}

internal data class RuntimeScheduleKey(val generation: Long, val kind: RuntimeScheduleKind, val token: Long)
internal enum class RuntimeScheduleKind { PLAYBACK, CLOCK, INPUT_TIMEOUT }

internal interface RuntimeCommandDispatcher : Closeable {
    fun dispatch(action: () -> Unit)
}

internal interface RuntimeNativePort {
    fun load(operationId: Long, generation: Long, prepared: PreparedGhost, complete: (RuntimeNativeLifecycleOutcome) -> Unit)
    fun request(token: RuntimeRequestToken, intent: ShioriRequestIntent, fallback: ShioriRequestIntent?, complete: (RuntimeResult<TaggedShioriResponse>) -> Unit)
    fun unload(operationId: Long, generation: Long, complete: (RuntimeNativeLifecycleOutcome) -> Unit)
}
```

The final production path connected in Task 7 may use an application-owned main `Handler`; snapshot-test mode uses a virtual scheduler. A delayed action is allowed only to call `runtime.submit(RuntimeCommand.PlaybackDue/TimerDue/InputExpired)` and never mutates player/runtime state directly.

Inject a serialized `RuntimeCommandDispatcher` into the private runtime constructor. Production implements it with an unlimited `Channel<() -> Unit>` and exactly one consumer coroutine in `CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))`; `dispatch` uses `trySend`, so callers never wait and FIFO enqueue order is explicit. Tests use a manual FIFO dispatcher. Closing the runtime closes the channel, cancels coordination/IO work, closes the scheduler, then drains/closes the existing native executor without calling collectors under a lock.

Add an internal `RuntimeOwnershipMode { LEGACY_RUNNER, SNAPSHOT_CORE_TEST }`. The public production constructor hardcodes `LEGACY_RUNNER`; only `GhostRuntime.testRuntime` may select `SNAPSHOT_CORE_TEST` and inject `RuntimeNativePort`. Exactly one owner is constructed per runtime instance: legacy mode constructs/admits through `SScriptRunner` and never starts the snapshot command consumer, while snapshot-test mode constructs the new state and fake port and never constructs a runner. Task 7 removes the mode and legacy branch while connecting the real native lane.

- [ ] **Step 3: Publish one snapshot from authoritative state**

In `SNAPSHOT_CORE_TEST` mode, add:

```kotlin
private val mutableSnapshots = MutableStateFlow(RuntimeSnapshot.initial())
internal val snapshots: StateFlow<RuntimeSnapshot> = mutableSnapshots.asStateFlow()

internal fun submit(command: RuntimeCommand) {
    coordinationDispatcher.dispatch { admit(command) }
}
```

`admit` is the only method allowed to replace `PlayerState`, `RuntimeHostState`, `RuntimeCatalogOwner`, switch/exit parent state, clock/request state, or `mutableSnapshots.value`. Build the complete next snapshot after state/native locks are released, increment revision exactly once per authoritative mutation, recursively freeze it, and suppress an equal no-op admission.

Use explicit internal registries:

```kotlin
private data class RuntimeClockState(
    val running: Boolean,
    val epoch: Long,
    val lastSecondBucket: Long?,
    val lastMinuteBucket: Long?,
)

private data class RuntimeRequestRegistry(
    val nextRequestId: Long,
    val pending: Set<RuntimeRequestToken>,
    val claimedDialogue: Map<Long, RuntimeDialogueRequestClaim>,
)

private enum class RuntimeDialogueClaimKind {
    CHOICE,
    ANCHOR,
    INPUT_SUBMIT,
    INPUT_DISMISS,
    INPUT_TIMEOUT,
}

private data class RuntimeDialogueRequestClaim(
    val action: DialogueActionKey,
    val kind: RuntimeDialogueClaimKind,
)
```

Consuming any `PlayerEffect.RequestShiori` allocates `nextRequestId`, builds the exact `RuntimeRequestToken`, records it in `pending`, and only then submits native work. Dialogue commands use variant-specific admission:

- Normal/direct-event choice: require the exact current key, clear every sibling choice from that dialogue incarnation immediately, allocate one `CHOICE` claim, and issue SHIORI. A duplicate sibling activation is effect-free.
- Local-script choice: require the exact current key, clear the same sibling set, and enqueue its local SakuraScript directly; allocate neither a native token nor a claim.
- Normal/direct-event anchor: require the exact current key but leave the anchor published and reusable. Each accepted activation allocates its own `ANCHOR` claim/request; a no-content response removes only that claim, so a later activation remains valid.
- Submit or dismiss input: require and remove the exact current input, then allocate one `INPUT_SUBMIT` or `INPUT_DISMISS` claim/request.
- `InputExpired`: require that the exact input key is still current and its deadline is reached, remove it, then allocate one `INPUT_TIMEOUT` claim/request with the existing `OnUserInputCancel`/`OnUserInput` fallback contract.

Before admitting `NativeResponse`, remove only its exact pending token and validate its typed origin: playback token still current; timer clock epoch/kind/bucket and mode identity still current; dialogue origin matches the exact `RuntimeDialogueRequestClaim.action` for that request ID; pointer surface identity still current; parent operation/phase revision still current; or attachment operation still current. Consuming or rejecting a dialogue response also removes only its exact claimed record. Back clear, accepted switch/exit clear, dialogue-incarnation replacement, generation retirement, and close remove all pending tokens and dialogue claims in their exact retired scope; a late response is then effect-free. Foreground loss increments `RuntimeClockState.epoch` before any blocked timer result can re-enter. Back admission accepts the exact nullable idle identity for no-generation exit; Back/switch admission otherwise validates `RuntimeModeIdentity` without rejecting for unrelated presentation, clock-display, or catalog revisions.

- [ ] **Step 4: Route native and filesystem completions back to the tail**

Define a test-injectable `RuntimeNativePort` whose asynchronous completion returns immutable `RuntimeResult<TaggedShioriResponse>`. In snapshot-test mode, native submission captures generation, parent operation, request token, and intent; its completion handler only calls:

```kotlin
submit(RuntimeCommand.NativeResponse(requestToken, result))
```

Do not alter the default production `Session`, attachment admission, or real request methods in this task. The fake port models the final split: native evidence is separate from `GhostHandle`, attachment, player, host, catalog, parent operations, and snapshot identity, and returns the exact `NativeLoadCompleted`/`NativeUnloadCompleted`/`NativeResponse` command names defined in Task 2. Task 7 applies that split to the real native lane atomically with runner deletion. No fake-port completion invokes a player/runtime reducer or publishes a snapshot inline.

Catalog scans run on bounded IO and return only:

```kotlin
val outcome = runCatching { scanner.scan().toList() }.fold(
    onSuccess = { RuntimeCatalogScanOutcome.Scanned(it) },
    onFailure = { RuntimeCatalogScanOutcome.Failed(RuntimeNoticeCode.CATALOG_SCAN_FAILED) },
)
submit(RuntimeCommand.CatalogScanned(epoch, outcome))
```

Keep direct synchronous request/probe APIs only for existing test/real-engine instrumentation that owns no player state. In this task, playback, timer, dialogue, switch, and exit command routing exists only inside `SNAPSHOT_CORE_TEST`; adding any partial production bridge is forbidden. Task 7 atomically moves all production paths to coordination commands while deleting runner authority.

- [ ] **Step 5: Drive player effects and parent terminals**

For each admitted player transition, runtime replaces `PlayerState`, converts cue effects through `RuntimeHostState`, schedules fenced delays, submits native requests, and consumes `ParentCompleted` exactly once for ordinary talk, switch, or exit. Implement every switch/exit table row from the spec and the cross-operation admission matrix. Rejected commands produce zero state/effect changes.

- [ ] **Step 6: Run focused runtime proofs**

```powershell
.\gradlew.bat testDebugUnitTest `
  --tests "com.cattailsw.nanidroid.GhostRuntimeTest" `
  --tests "com.cattailsw.nanidroid.GhostRuntimeNativeThreadTest" `
  --tests "com.cattailsw.nanidroid.GhostRuntimeAttachmentTest" `
  --tests "com.cattailsw.nanidroid.GhostRuntimeSwitchTest" `
  --tests "com.cattailsw.nanidroid.GhostRuntimeSnapshotTest" `
  --tests "com.cattailsw.nanidroid.runtime.SakuraScriptPlayerTest" `
  --tests "com.cattailsw.nanidroid.runtime.RuntimeHostStateTest" `
  --tests "com.cattailsw.nanidroid.runtime.RuntimeCatalogTest" `
  --rerun-tasks
```

Expected: PASS; old production runner callbacks remain the only wired UI path until Task 7.

Add a constructor-composition behavior test using counting test factories: `GhostRuntime(context)` constructs/adopts only the legacy runner authority, while a `SNAPSHOT_CORE_TEST` fixture constructs/adopts only the snapshot state/port and exposes no runner. Assert commands sent to the inactive authority have no observable effect. Separately, the Task 5 diff review must show no changes to `CatTailApplication.kt` or `Nanidroid.kt`; do not encode that review as a source-grep unit test. Delete or invert the transitional composition test in Task 7 when legacy mode is removed.

- [ ] **Step 7: Obtain concurrency review and commit**

Require one reviewer to trace coordination/native/IO enqueue order and lock release, and one adversarial reviewer to find dual mutable ownership or unfenced completions. Fix findings and rerun Step 6. Commit:

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt src/main/kotlin/com/cattailsw/nanidroid/runtime/RuntimeScheduler.kt src/test/java/com/cattailsw/nanidroid/RuntimeFixture.kt src/test/java/com/cattailsw/nanidroid/GhostRuntime*Test.kt
git commit -m "Add GhostRuntime coordination snapshot core"
```

---

### Task 6: Prove the Android Host Adapter Before Cutover

**Files:**
- Create: `src/androidTest/java/com/cattailsw/nanidroid/GhostRuntimeHostAdapterInstrumentationTest.kt`
- Modify: `src/androidTest/AndroidManifest.xml`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt`

**Interfaces:**
- Consumes: Task 5 test runtime, `RuntimeSnapshot`, and command methods.
- Produces: lifecycle/device evidence for epoch ordering, lifecycle-aware collection, cue acknowledgement, and exit block ordering. It does not wire the production `Nanidroid` path.

- [ ] **Step 1: Add a test-only host Activity and red lifecycle tests**

Declare a test APK `ComponentActivity` that owns one `RuntimeHostId`, increments `hostEpoch` before each lifecycle submission, and collects snapshots only inside `repeatOnLifecycle(Lifecycle.State.STARTED)`. Record main-loop command submission and rendered revisions.

Test overlapping Activities, recreation, top-resumed handoff, stopped collection, two cues before collector dispatch, cue prefix ack, old-host cue expiry, and 65 hostless cues. Keep the old Activity STARTED across a top-resumed handoff and assert that its collector neither plays nor acknowledges cues leased to the new Activity. Add an exit test whose delivery method is exactly:

```kotlin
private fun deliverExit(lease: RuntimeExitLease) {
    runtime.submit(RuntimeCommand.ClaimExit(lease))
    try {
        finish()
    } finally {
        runtime.submit(RuntimeCommand.AcknowledgeExit(lease))
    }
}
```

Assert the trace is `claim, finish, acknowledge, onPause, topResumedFalse, onStop`, and a later Activity is not finished.

- [ ] **Step 2: Run API 37 focused instrumentation**

```powershell
.\gradlew.bat compileDebugAndroidTestKotlin
.\gradlew.bat connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.GhostRuntimeHostAdapterInstrumentationTest
```

Expected: PASS on the configured API 37 x86_64 emulator/device. If no compatible device is connected, keep this task open rather than replacing it with a JVM claim.

- [ ] **Step 3: Obtain Android lifecycle review and commit**

Require an independent Android reviewer to verify lifecycle callback order, main-thread non-suspension, collector cancellation, new-Activity safety, and no Activity reference retained in the runtime. Fix findings, rerun Step 2, then commit:

```powershell
git add src/androidTest/AndroidManifest.xml src/androidTest/java/com/cattailsw/nanidroid/GhostRuntimeHostAdapterInstrumentationTest.kt src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt
git commit -m "test: prove runtime host delivery lifecycle"
```

---

### Task 7: Cut Production Over and Delete Parallel Authority

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt:258-1152`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt:37-392`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/DialogueDialogBinding.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt`
- Modify: `build.gradle.kts`
- Delete: `src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt`
- Delete: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`
- Delete: `src/main/kotlin/com/cattailsw/nanidroid/BootDispatchState.kt`
- Delete: `src/main/kotlin/com/cattailsw/nanidroid/GhostPresentationFrame.kt`
- Delete: `src/main/kotlin/com/cattailsw/nanidroid/GhostPresentationRenderer.kt`
- Delete: obsolete runtime files listed in the File Map
- Rewrite/migrate: runner JVM and instrumentation tests listed in the File Map
- Modify: affected source contracts under `tools/`

**Interfaces:**
- Consumes: the independently reviewed inactive runtime core and Android host contract.
- Produces: one production owner, one snapshot stream, no callback/runner/catalog/presentation alternative, and a net-negative production authority diff.

- [ ] **Step 1: Make final source-absence contracts red**

Update the eight affected Python contracts to require:

```text
SScriptRunner.kt absent
GhostMgr.kt absent
GhostPresentationRenderer.kt absent
GhostPresentationFrame.kt absent
BootDispatchState.kt absent
obsolete runtime prototype files absent
GhostRuntime.kt is the only RuntimeSnapshot MutableStateFlow publisher
Nanidroid.kt uses runtime host/action commands and lifecycle-aware snapshot collection
ComposeGhostStageHost.kt accepts RuntimeSnapshot plus the Activity-owned RuntimeHostLease and contains no authoritative runtime/dialogue projection
no production callback interface for presentation/dialogue/exit/switch
```

Run and expect failure before deletion:

```powershell
python -m unittest tools.test_compose_stage_retirement_contract tools.test_ghost_runtime_composition_root tools.test_kotlin_compose_renderer_contract tools.test_kotlin_ghost_discovery_contract tools.test_kotlin_legacy_archive_runtime_absence tools.test_kotlin_presentation_frame_contract tools.test_kotlin_renderer_contract tools.test_kotlin_shiori_response_contract
```

- [ ] **Step 2: Wire Activity lifecycle and snapshot collection**

Give each Activity instance one `RuntimeHostId` from an application monotonic ID source and its own incrementing epoch. Submit register/resumed/top-resumed/unregister commands from matching lifecycle callbacks. Collect `runtime.snapshots` with `repeatOnLifecycle(STARTED)` or a Compose `produceState` whose body uses `repeatOnLifecycle(STARTED)`; never save runtime state in a Bundle.

Replace `runner`, `runnerHostToken`, callback implementations, `hostResumed`, `hostTopResumed`, and direct switch/exit continuation with generation/host-fenced commands. Keep the Activity's exact `RuntimeHostLease` as view-local binding state. Filter an offered exit by equality with that lease before executing the exact non-suspending block proven in Task 6. Retain only toolbar visibility, open Readme document, NAR picker owner token, and input draft keyed by `DialogueActionKey`; restore a draft only while the newest snapshot contains that exact input key.

- [ ] **Step 3: Make Compose a snapshot adapter**

Change `ComposeGhostStageHost.Stage` to accept both `RuntimeSnapshot` and the Activity-owned `RuntimeHostLease`; never infer delivery eligibility from `snapshot.foregroundHost`. Derive surface/text/balloon values directly from `snapshot.presentation`, filter cues by exact lease equality, and process only that lease's cues in cue-ID order. After applying a contiguous prefix, submit one `AcknowledgeCues(hostLease, throughCueId)` command. A STARTED collector whose lease is no longer top-resumed renders the normalized frame but performs no leased cue or exit side effect.

Retain view-local surface catalog caches, decoded/composed pixels, active surface frames, animation scheduler state, measurement, and scroll memory. Delete `runtimeState`, `dialogueState`, `renderer`, `updateDialogueState`, and `setSurfaceCatalog` as mutable authoritative projections. A new host starts from the normalized frame and never replays an expired old-host cue.

- [ ] **Step 4: Move catalog/startup/install/readme selection to runtime state**

Initialize `GhostRuntime` catalog scanning at application startup. Startup waits for `RuntimeCatalogState.Ready`, chooses the persisted ID then remaining catalog entries, and starts/attaches through the runtime. `Loading` and `Failed` never trigger bundled installation.

Delete `GhostMgr`. Use `snapshot.catalog.lastProvenEntries` for lists, display names, Sakura names, Readme paths, and switch target roots. Route bundled installation and foreground import through existing `NarTransactionalInstaller`/`ForegroundNarImportCoordinator`; after commit, submit `CatalogChanged(publicationToken, targetId)`. Convert a foreground `NarImportAttemptToken` to `CatalogPublicationToken("foreground-import", "$processNonce:$sequence:$ownerTaskId")`; use `CatalogPublicationToken("bundled-install", operationId.toString())` for bundled installation. `CatTailApplication` observes application-owned import completion so Activity destruction cannot lose the refresh, and duplicate observation of one retained terminal produces no new epoch/scan. Runtime publishes each installation independently ready only after a newest-epoch scan contains that token's target; one missing target cannot withhold another proven target. A visible global scan failure action submits `RetryCatalog(null, snapshot.catalog.epoch)`, which reactivates every token stranded by that exact failed scan. A token recovery action for a target omitted by an otherwise successful scan submits `RetryCatalog(token, status.failedEpoch)`. A retry whose global or token-keyed failure epoch no longer matches is an effect-free no-op.

- [ ] **Step 5: Delete runner authority and connect the real player**

Remove `RuntimeOwnershipMode`, the legacy branch, runtime construction of `SScriptRunner`, `AttachmentAdmission(runner::admitAttachment)`, `runnerConfiguration`, `runtime.runner`, and every runner callback. Make the tested coordination path unconditional. Split the real native session so the native lane retains only `NativeSession(adapter, generation)` plus ownership/poison evidence and returns `NativeLoadCompleted`, `NativeUnloadCompleted`, and `NativeResponse` commands; coordination owns all other state and publishes/retire generations only after those commands. On attachment, runtime directly initializes `PlayerState` and admits exactly one boot outcome. On generation retirement it clears player/actions/cues/timers itself. Timer, pointer, dialogue, switch, exit, and ordinary SHIORI responses all enter through `RuntimeCommand.NativeResponse` and player/parent fencing.

Delete `SScriptRunner.kt`, handler scheduler types, `BootDispatchState.kt`, old frame/renderer types, and their callback-oriented tests. Rename/migrate retained suites to `SakuraScriptPlayerTest`, `GhostRuntimePlaybackTest`, `GhostRuntimeDialogueTest`, `GhostRuntimeHostTest`, and `GhostRuntimeMainThreadRequestInstrumentationTest`; keep every assertion from Task 1. Extend `tools.test_compose_stage_retirement_contract` with the old-to-new test-ID table below and make it fail unless every named replacement method exists before its old source file is deleted.

- [ ] **Step 6: Delete parallel prototypes only after mapped tests are green**

Delete `runtime/GhostStageLayout.kt`, `SakuraScriptInteractionEffects.kt`, `SakuraScriptPresentationState.kt`, `SakuraScriptPresentationInterpreter.kt`, `GhostPresentationState.kt`, and `KotlinGhostPresentationRuntime.kt`. Delete their direct prototype tests only after this assertion-by-assertion map is green:

```text
SakuraScriptCharacterizationTest.requiredMigrationInvariant_speakerTextSurfaceAndAnimationHaveOrderedTrace -> SakuraScriptPlayerTest.speakerTextSurfaceAndAnimationHaveOrderedTransition
SakuraScriptCharacterizationTest.requiredMigrationInvariant_newlineModifierAndClearHaveOrderedTextStates -> SakuraScriptPlayerTest.newlineModifierAndClearHaveOrderedTextStates
SakuraScriptCharacterizationTest.requiredMigrationInvariant_quickSessionEmitsOneWholeLineTextState -> SakuraScriptPlayerTest.quickSessionEmitsOneWholeLineTransition
SakuraScriptCharacterizationTest.requiredMigrationInvariant_distinctSurfaceTransitionsAndAnimationStartsAreOrdered -> SakuraScriptPlayerTest.distinctSurfaceTransitionsAndAnimationCuesAreOrdered
SakuraScriptCharacterizationTest.legacyObserved_choicesAreReportedThenTheirLabelsContinueAsText -> SakuraScriptPlayerTest.choicesPublishThenLabelsContinueAsText
SakuraScriptCharacterizationTest.legacyObserved_unsupportedTagsAreConsumedRatherThanRendered -> SakuraScriptPlayerTest.unsupportedTagsAreConsumedNotRendered
SakuraScriptCharacterizationTest.rendererReceivesAnimationOnlyWhenTheRunnerSchedulesIt -> SakuraScriptPlayerTest.animationCueAppearsOnlyWhenPlayerSchedulesIt
SScriptRunnerAuthorityTest.runnersCannotConsumeOrClearEachOthersQueuedScripts -> GhostRuntimeSnapshotTest.runtimeInstancesCannotConsumeEachOthersPlayerQueues
SScriptRunnerAuthorityTest.runnerHasNoStaticMutableSessionOrQueueAuthority -> GhostRuntimeSnapshotTest.runtimeHasNoStaticMutableQueuePlayerHostOrCatalogState
runtime.GhostStageLayoutPolicyTest.requiredMigrationInvariant_unmeasuredStageDoesNotProduceLayout -> runtime.stage.GhostStageLayoutPolicyTest.migrationInvariant_zeroSafeBoundsPublishesTinyFallbackWithoutInteractiveRects
runtime.GhostStageLayoutPolicyTest.requiredMigrationInvariant_wideStageKeepsOriginalSurfaceSizesAndSplitBalloons -> runtime.stage.GhostStageLayoutPolicyTest.migrationInvariant_wideStageUsesPhysicalSpeakerLanesAndAdaptiveBubbleBands
runtime.GhostStageLayoutPolicyTest.requiredMigrationInvariant_shortKeroUsesTallBalloonRule -> runtime.stage.GhostStageLayoutPolicyTest.migrationInvariant_shortKeroRetainsAdaptiveBubbleAndSurfaceRegions
runtime.GhostStageLayoutPolicyTest.requiredMigrationInvariant_surfacesScaleToFitWidthBeforePlacement -> runtime.stage.SurfaceSizingPropertyTest.migrationInvariant_oversizedSurfacesFitAssignedRegionsBeforePlacement
The remaining seven adaptive tests in runtime.GhostStageLayoutPolicyTest -> same method IDs in runtime.stage.GhostStageLayoutPolicyTest
SakuraScriptInteractionInterpreterTest.requiredMigrationInvariant_choicesBecomeLabelsAndOneOrderedSelectionEffect -> SakuraScriptPlayerTest.choicesBecomeLabelsAndOneOrderedAction
SakuraScriptInteractionInterpreterTest.requiredMigrationInvariant_inputBoxIsConsumedAndRetainsItsExactId -> SakuraScriptPlayerTest.inputBoxIsConsumedAndRetainsStableActionId
SakuraScriptInteractionInterpreterTest.requiredMigrationInvariant_scriptsWithoutInteractionsRemainUntouched -> SakuraScriptPlayerTest.scriptWithoutInteractionsRemainsUntouched
SakuraScriptInteractionInterpreterTest.inputBoxesAreParsedIndividuallyRatherThanUsingTheLegacyGreedyCapture -> SakuraScriptPlayerTest.inputBoxesParseIndividually
SakuraScriptInteractionInterpreterTest.requiredMigrationInvariant_effectsKeepInputAndChoiceSourceOrder -> SakuraScriptPlayerTest.inputAndChoiceActionsKeepSourceOrder
SakuraScriptInteractionInterpreterTest.requiredMigrationInvariant_effectCollectionsCannotBeMutatedFromJava -> RuntimeSnapshotTest.dialogueActionCollectionsRejectMutation
SakuraScriptPresentationReducerTest.requiredMigrationInvariant_scriptResetKeepsSurfacesButClearsTransientPresentation -> SakuraScriptPlayerTest.scriptResetKeepsSurfacesAndClearsTransientPresentation
SakuraScriptPresentationReducerTest.requiredMigrationInvariant_synchronizationAndKeroTextPreserveLegacyBalloonPolicy -> SakuraScriptPlayerTest.synchronizationAndKeroTextPreserveBalloonPolicy
SakuraScriptPresentationReducerTest.requiredMigrationInvariant_reselectingCurrentSpeakerRetainsItsText -> SakuraScriptPlayerTest.reselectingCurrentSpeakerRetainsText
SakuraScriptPresentationReducerTest.requiredMigrationInvariant_animationIsVisibleOnceThenExplicitlyConsumed -> SakuraScriptPlayerTest.animationBecomesOneLeaseScopedCue
SakuraScriptPresentationInterpreterTest.requiredMigrationInvariant_textSurfaceAnimationAndStopFramesMatchLegacyTrace -> SakuraScriptPlayerTest.textSurfaceAnimationAndStopMatchOrderedTransitions
SakuraScriptPresentationInterpreterTest.requiredMigrationInvariant_repeatedSpeakerAndNewlineCommandsKeepVisibleText -> SakuraScriptPlayerTest.repeatedSpeakerAndNewlineKeepVisibleText
GhostPresentationReducerTest.requiredMigrationInvariant_snapshotPreservesSpeakerTextSurfaceAnimationAndBalloonPolicy -> RuntimeSnapshotTest.presentationPreservesSpeakerTextSurfaceCueAndBalloonPolicy
GhostPresentationReducerTest.requiredMigrationInvariant_emptyTextAndDisabledBalloonRemainHidden -> RuntimeSnapshotTest.emptyTextAndDisabledBalloonRemainHidden
KotlinGhostPresentationRuntimeTest.render_emitsTheLegacyRendererEffectTraceInOrder -> GhostRuntimeSnapshotTest.snapshotAndCuesPreserveLegacyEffectOrder
KotlinGhostPresentationRuntimeTest.explicitAnimationSuppressesTalkingButKeepsVisibleBalloonText -> SakuraScriptPlayerTest.explicitAnimationSuppressesTalkingCueAndKeepsBalloonText
KotlinGhostPresentationRuntimeTest.runtimeImplementsTheExistingRendererAbiWithoutRetainingViews -> RuntimeSnapshotTest.snapshotGraphContainsNoViewOrCallback
KotlinGhostPresentationRuntimeTest.renderRejectsMissingSurfaceIdLikeTheLegacyRenderer -> SakuraScriptPlayerTest.missingSurfaceIdIsRejectedAtPlayerBoundary
```

Update `build.gradle.kts` JaCoCo representative targets to `GhostRuntime` and `runtime/SakuraScriptPlayer`.

- [ ] **Step 7: Run the atomic cutover gate**

```powershell
python -m unittest discover -s tools -p "test_*contract.py"
.\gradlew.bat testDebugUnitTest --rerun-tasks
.\gradlew.bat compileDebugAndroidTestKotlin
.\gradlew.bat connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.NanidroidLifecycleInstrumentationTest
.\gradlew.bat connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.GhostRuntimeMainThreadRequestInstrumentationTest
```

Expected: PASS. The connected tests must include blocked request + host loss/Back/switch ordering, top-resumed overlap, recreation, exit delivery, cue acknowledgement/expiry, input draft restoration, catalog post-commit scan, and exactly one attachment event.

- [ ] **Step 8: Prove deletion accounting and absence**

```powershell
rg -n "SScriptRunner|SScriptPlaybackScheduler|SScriptResponseScheduler|GhostPresentationRenderer|GhostPresentationFrame|BootDispatchState|class GhostMgr|KotlinGhostPresentationRuntime|SakuraScriptPresentationInterpreter|SakuraScriptInteractionEffects" src/main src/test src/androidTest tools
git diff --numstat codex/phase3-native-runtime-thread...HEAD -- src/main/kotlin
git diff --check codex/phase3-native-runtime-thread...HEAD
```

Expected: no retained authority name except historical migration text in tests/tools where explicitly necessary; no production whitespace errors; summed deleted production Kotlin lines exceed summed added production Kotlin lines. If the new core plus remaining owner path is not smaller than the replaced production authority, stop and simplify before commit.

- [ ] **Step 9: Obtain independent cutover reviews and commit**

Dispatch at least two independent exact-diff reviews:

```text
Android/lifecycle: verify application ownership, lifecycle epoch ordering, no Activity retention, lifecycle-aware collection, exact exit delivery ordering, cue lease semantics, and uninterrupted hostless playback.

Adversarial/authority: enumerate every queue/player/timer/catalog/switch/exit/native response owner; find any callback, second mutable projection, stale completion, hidden compatibility facade, lost essential behavior, or deletion without migrated proof.
```

Fix every verified finding, rerun Steps 7–8, and return the corrected diff to the finding reviewer until approved. Commit the indivisible cutover:

```powershell
git add src/main src/test src/androidTest tools build.gradle.kts docs/testing.md
git commit -m "Move playback ownership into GhostRuntime"
```

---

### Task 8: Full Validation, Reviews, and Stacked Pull Request

**Files:**
- Modify only files required by concrete validation/review findings.
- Create during validation: `build/reports/phase3-playback-snapshot-pr-body.md` (ignored; do not commit).

**Interfaces:**
- Consumes: completed Tasks 1–7 on the branch stacked above PR #402.
- Produces: a clean, independently reviewed draft PR that advances #385, exact evidence, unchanged corpus requirements, and honest arm64 status.

- [ ] **Step 1: Perform the primary exact-diff review**

```powershell
git status --short
git diff --check codex/phase3-native-runtime-thread...HEAD
git diff --stat codex/phase3-native-runtime-thread...HEAD
git diff codex/phase3-native-runtime-thread...HEAD -- src/main src/test src/androidTest tools build.gradle.kts docs/testing.md
rg -n "SScriptRunner|GhostMgr|GhostPresentationRenderer|GhostPresentationFrame|BootDispatchState|KotlinGhostPresentationRuntime|SakuraScriptPresentationInterpreter|SakuraScriptInteractionEffects" src/main src/test src/androidTest tools
rg -n "MutableStateFlow<RuntimeSnapshot>|MutableStateFlow\(RuntimeSnapshot" src/main/kotlin
```

Expected: one snapshot publisher in `GhostRuntime`; no parallel authority or deleted production type; no unrelated PR #394/#395 tooling diff.

- [ ] **Step 2: Run full host verification**

```powershell
.\gradlew.bat testDebugUnitTest jacocoTestReport --rerun-tasks
.\gradlew.bat assembleDebug lint validateDebugScreenshotTest
python -m unittest discover -s tools -p "test_*contract.py"
```

Expected: PASS with no unexplained screenshot baseline change.

- [ ] **Step 3: Run connected API 37 verification**

```powershell
adb devices -l
.\gradlew.bat connectedDebugAndroidTest
```

Expected: PASS on a compatible API 31–37 device, including the full lifecycle and main-thread request suites. Record the actual serial, API, and ABI.

- [ ] **Step 4: Run the canonical corpus and three-engine gates when artifacts exist**

Use the existing PR #394/#395-compatible commands from `docs/testing.md` without modifying their assertions. Require exactly 23 manifest rows and real Satori/YAYA/Kawari coverage. If the canonical archives remain unavailable, record exactly `0 archives`, `0/23 rows`, and `0/3 engines`; do not fabricate a pass, do not weaken the manifest, and keep the PR draft.

- [ ] **Step 5: Verify both packaged ABIs and record arm64 honestly**

Build both configured ABIs, inspect the APK entries, and run `llvm-readelf -h` on extracted arm64 libraries, requiring AArch64 machine type. If a physical arm64 API 31–37 device is attached, run the connected suite for its exact serial. Otherwise record physical arm64 runtime as deferred and report only build/package/ELF proof.

- [ ] **Step 6: Dispatch final multi-agent review**

Send the exact `codex/phase3-native-runtime-thread...HEAD` diff plus approved spec and plan to independent Android and adversarial reviewers. Require only actionable findings or `APPROVE`. Fix verified findings in focused commits, rerun affected focused/full gates, and return the corrected head to each finding reviewer until approved.

- [ ] **Step 7: Push and create the stacked draft PR**

Create the ignored PR body with `apply_patch`. Include behavioral ownership, deletion/LOC accounting, exact commands/results, independent reviews, API/ABI evidence, explicit “advances but does not close #385,” canonical corpus status, and physical arm64 result/deferral. Then use non-interactive stack commands:

```powershell
gh stack submit --auto --remote origin
gh stack view --json
```

Edit the new PR title/body with `gh pr edit` if auto-generation is insufficient. Verify its base is `codex/phase3-native-runtime-thread`, PR #402's head remains unchanged, and the new PR is draft while any canonical corpus gate is unavailable.

- [ ] **Step 8: Obtain GitHub automatic review and settle the exact head**

Request GitHub automatic review on the exact pushed head. Wait for CI and review to settle, inspect every inline thread/check, fix verified findings, push through `gh stack push --remote origin`, rerun affected gates, and re-request automatic review after any head change. Do not claim completion with unresolved threads, stale review, failing checks, or absent corpus evidence.

- [ ] **Step 9: Record final evidence without closing the overarching goal**

Update the ignored PR body with the exact final commit, CI conclusions, automatic-review result, resolved-thread count, independent approvals, validation totals, deletion accounting, corpus availability, and arm64 status; apply it with `gh pr edit --body-file`. Require a clean tracked worktree. Report this slice as review-ready or draft-blocked by exact named gates, but keep #385 and the broader simplification goal active until all required ownership work and canonical corpus evidence are genuinely complete.
