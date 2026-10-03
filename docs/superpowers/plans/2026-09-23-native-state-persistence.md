# Native State Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve native ghost state across supported lifecycle transitions, prove real saved values restore, and reproduce the reported 2elf rotation and LOBO rendering symptoms.

**Architecture:** Keep one application-owned runtime and one process-owned native lane. Add SHIORI notification framing, durable profile preparation, and a host-owned shutdown operation whose caller can finish independently of synchronous JNI. Native engines and ghost scripts retain ownership of save formats; the app does not serialize their state.

**Tech Stack:** Kotlin, coroutines/StateFlow, Compose/ViewModel, existing JNI engines, JUnit 4, coroutines-test, Android instrumentation, Gradle wrapper.

**Spec:** `docs/superpowers/specs/2026-09-23-native-state-persistence-design.md`; also read the approved baseline and blind-recreation design in the same directory.

**Status:** Approved for implementation by the user on 2026-09-23, with the requested live-process kill-test clarification incorporated below. This is the checkpoint between native milestone two and import milestone three. Execute in the existing implementation task with fresh GPT-6-Sol implementers and independent reviewers.

## Global Constraints

- Work only in `C:/work/src/nanidroid-recreation`. Preserve other edits; inspect current HEAD and status before starting.
- One app module; namespace/application ID `com.cattailsw.nanidroid`; minSdk 31; compile/target 37; manual constructor injection.
- Kotlin production: `app/src/main/kotlin/com/cattailsw/nanidroid`; JVM tests: `app/src/test/java`; device tests: `app/src/androidTest/java`.
- Keep Compose, ViewModel and lifecycle-aware StateFlow collection. No Activity-owned native state.
- No reference application source, tests, build files, architecture or history. Read only this recreation, approved documents, public specifications, permitted ghost data, and the already verified native tree. Do not modify the pinned JNI sources in this checkpoint.
- Sequential fresh GPT-6-Sol implementer and fresh independent reviewer per task, `fork_turns: none`; reuse each implementer for fixes. Lead coordinates and reviews, not product coding. Workers report workspace/instructions, own only their listed files, and preserve others' work.
- Keep local corpus payloads out of Git and APKs. Synthetic dictionaries written for tests are separate fixtures with their own provenance, never presented as unchanged corpus ghosts.
- No app-side save serializer, startup re-extraction, background unload/reload policy, importer, updater, generic compatibility expansion, or universal crash/power-loss durability claim.
- Rotation/pause/resume preserve the native session. Process death only promises recovery of completed writes subject to filesystem behavior, not unsaved memory.
- Implementation estimate: 6–10 active agent-hours including device evidence and reviews; reassess at 12 hours instead of silently expanding scope. Missing controllable fixture operations are a reported evidence gap, not permission to invent results.

## Review Focus

1. Existing profile is a file, unwritable directory, or escaping symlink: fail before JNI and preserve existing bytes (Task 2).
2. Native shutdown returns after the deadline: quarantine is permanent; no success, next JNI call, or new owner may appear (Task 3).
3. OnInitialize transport failure after load acquired ownership: cleanup once, no destination boot-history/last-ghost commit, visible fallback. A 400/500/other status is only logged and boot continues (Task 4).
4. Back overlaps a switch or rotation: only one outgoing shutdown; Activity finishes even after cleanup failure (Task 4).
5. Real save file exists but contains stale data: require changed value and fresh-engine observation, plus 2elf same-session rotation (Tasks 1 and 5).

## Baseline and validation

The native milestone ledger reports all five tasks and whole-branch fixes complete. Its latest recorded gate is 83 unit tests / 1 skipped and 26 connected / 3 skipped, with zero failures. Treat that as historical evidence, not this checkpoint's result. Preserve current native ownership, cancellation, charset and image-alpha regressions.

Use the wrapper from the recreation root:

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain
.\gradlew.bat :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest --offline --console=plain
```

Offline dependency failure is an environment result, not a failed product test; resolve it explicitly. Device evidence records API, ABI, serial, APK hashes, commands, raw results, actual execution and skips. Both ABIs must remain packaged; do not claim arm64 execution from x86_64 tests.

### Task 1: Establish fixture operations and reported reproductions

**Files:** Create `docs/testing/native-persistence-fixtures.md` and `docs/testing/lobo-rendering-reproduction.md`. No product changes.

**Interfaces:** Produces a per-fixture table consumed by Task 5: archive hash, pristine staging location, encoding, operation/event and references, changed variable, expected save path, independent readback operation, expected trigger, and original versus modified status.

- [ ] Verify these original local inputs by SHA-256 before staging disposable data copies:
  - Satori: `C:/tmp/Nanidroid-corpus-recovery/2elf/2elf-2.46.nar`, `a50830e18def75be051a3638c7375c7e2d96cb18f7b3f26d0037d84a0fc20be0`.
  - Kawari: `C:/tmp/Nanidroid-corpus-recovery/pcPets/Ukagakas/LOBO/LOBO_1.0.0.nar`, `f4e90615cf40801d4a7a7170762b6c0d6dddf18324f9ba146f4a700cbe2bebf7`.
  - YAYA: `C:/tmp/Nanidroid-corpus-recovery/pcPets/Ukagakas/Earthquake Rescue Duo/Earthquake_duo_1.0.1.nar`, `06db71e7e8293b4af0b5127dd73402d4ed90fecc5fdcebf4f0d34337ccb66538`.
- [ ] Inspect only ghost data to trace a deterministic persistent-value change and observation for each. LOBO candidates are `username` or `talkinterval` in `savedataParam`; its initial restore is in `kawarirc.kis`, its `OnInitialize` runs `SaveLoad`, and OnMinuteChange/OnDestroy invoke SaveData. Trace the actual setting handler, not a guessed SHIORI setter event. For 2elf trace a flag controlling a recognizable next response; for YAYA distinguish engine termination save from framework SAVEVAR.
- [ ] Record an executable event sequence and expected values in the table. If an unchanged ghost offers no accessible operation within supported input, document why; define a small separate test dictionary with explicit set/read events for engine coverage. Do not silently add a debug event to a corpus ghost or claim synthetic evidence proves 2elf.
- [ ] Reproduce 2elf rotation and LOBO's missing-rendered-part report on the current APK if possible. Record portrait/landscape screenshots, surface IDs, relevant ghost surface definitions and exact interactions. If the reported LOBO part cannot be identified, mark reproduction incomplete and request the missing visible detail; do not guess a rendering fix or block unrelated persistence work.
- [ ] Classify an observed LOBO defect: current supported image/alpha/placement regression goes into a separate focused fix; unfinished baseline composition/animation goes into milestone four; outside-baseline syntax is an explicit scope question. This task does not implement that fix.
- [ ] Independently review the fixture operations against their data and commit the documentation. Estimate: 45–90 minutes; cap exploratory fixture hunting at 90 minutes before reporting a precise evidence gap.

### Task 2: Notification framing and stable profile preparation

**Files:** Modify `engine/ShioriEvent.kt`, `engine/ShioriCodec.kt`; create `ghost/NativeProfileDirectory.kt`; tests `engine/ShioriCodecTest.kt`, `ghost/NativeProfileDirectoryTest.kt` under the corresponding source-set package roots.

**Interfaces:** Append a defaulted method to the existing event to preserve call sites:

```kotlin
enum class ShioriMethod { GET, NOTIFY }
data class ShioriEvent(
    val id: String,
    val references: List<String> = emptyList(),
    val method: ShioriMethod = ShioriMethod.GET,
)
object NativeProfileDirectory {
    fun prepare(master: File): File
}
```

`prepare` returns the existing or newly created profile directory. It throws an activation error for a non-directory, creation failure or escaping canonical path. Caller runs it on the IO dispatcher. It never deletes, clears or rewrites contents.

- [ ] Write failing codec cases for default GET and explicit NOTIFY, both notification IDs, absent references, CRLF termination, and existing per-engine charset behavior. Expected first line for initialization is `NOTIFY SHIORI/3.0\r\n`; there must be no Reference0 header. Keep method support independent of response-script handling, which Task 4 owns.
- [ ] Write failing filesystem cases: missing profile created, existing save bytes unchanged after repeated prepare, regular file at profile rejected, injected creation failure rejected, escaping symlink rejected. Do not equate `mkdirs() == false` with failure when the directory already exists. Run privilege-dependent symlink coverage on a device if Windows cannot create it.
- [ ] Run the focused tests and confirm failures exercise the new behavior; implement the minimal framing and path preparation.
- [ ] Run tests and existing codec/descriptor regressions, independently review and commit. No JNI load is added in this task.

### Task 3: Bounded host shutdown without cancelling JNI

**Files:** Modify `engine/NativeShioriHost.kt`, `runtime/NativePort.kt`; create `engine/NativeShutdownResult.kt`; extend `engine/NativeShioriHostTest.kt` and existing NativePort fake declarations only as needed to compile. Preserve existing `unload` for low-level/cancelled-load ownership cleanup.

**Interfaces:** Add to host and NativePort, forwarding in HostNativePort:

```kotlin
suspend fun shutdown(
    lease: NativeLease,
    notifyDestroy: Boolean = true,
    timeoutMillis: Long = 5_000L,
): NativeShutdownResult

sealed interface NativeShutdownResult {
    data object Completed : NativeShutdownResult
    data class Failed(val message: String) : NativeShutdownResult
    data class Unresolved(val message: String) : NativeShutdownResult
}
```

Completed means required calls returned successfully, not that saving succeeded. Failed means a protocol/transport error was retained but ownership was safely unloaded. Unresolved means deadline/ownership uncertainty; quarantine is permanent. `notifyDestroy=false` is for cleanup of a loaded candidate whose initialization did not succeed.

- [ ] Write deterministic fake-binding tests using latches and controllable completion, not a real hanging engine. Cover successful NOTIFY then unload, 200/204 with ignored Value, 400/500/other statuses followed by one unload while ownership remains known, transport exception with/without quarantine, stale lease, duplicate shutdown, and shutdown queued behind a request.
- [ ] Add deadline tests where NOTIFY or unload blocks. The awaiting caller must finish at the budget without releasing the blocked binding. Then release it and assert quarantine remains, no late success arrives, and no follow-on unload/request/load occurs after abandonment. Also test a queued operation expiring before it enters JNI and completion racing the deadline. Always release test latches in finally so test executor threads do not leak.
- [ ] Implement a host-owned operation launched independently of the caller Job; await its result with a separate deadline. Use an atomic terminal operation state and a short synchronized/atomic ownership gate usable from the deadline dispatcher. Never hold that gate across JNI. The deadline must not queue behind the blocked native lane. Every new JNI entry and every post-call state publication checks quarantine and the operation's terminal state. Existing unload/load/cancellation-completion paths must not reset availability after quarantine.
- [ ] Preserve one native OS thread; no timeout thread calls JNI. Define the JNI-entry check as the operation admission point: an already admitted synchronous call may finish after timeout, but no later call is admitted. A late unload may physically release engine resources; it must not release the logical quarantined lease or make the host reusable.
- [ ] Use one per-lease shutdown result for duplicate waiters; repeated calls cannot deliver OnDestroy twice. Caller cancellation cannot cancel in-flight JNI or bypass deadline/ownership handling. Budget starts when shutdown is requested, including queue delay, and ends after both notification and unload. This is not a new timeout for load, arbitrary requests, or outgoing dialogue.
- [ ] Run host, cancellation and charset regressions. Independently review race behavior and commit. Do not proceed if quarantine can be cleared by late existing paths.

### Task 4: Integrate startup/shutdown into the runtime

**Files:** Modify `runtime/GhostRuntime.kt` and, only if wiring requires it, `NanidroidApplication.kt`; tests `runtime/GhostSwitchTest.kt`, `runtime/GhostRuntimeTest.kt`. No new store or UI files: Back cleanup errors are logged only, with no persisted next-launch notice.

**Interfaces:** Consume Tasks 2/3.

- [ ] Write failing transition tests for `prepare -> load -> OnInitialize NOTIFY -> recordActivation/lastGhost -> OnFirstBoot/OnBoot/OnGhostChanged GET`. No boot/history/store commit occurs before initialization completes. Kotlin engines receive neither native notification nor native profile preparation.
- [ ] Test initialization status 400/500/other: log it and continue the same boot sequence with no fallback. Test transport failure, unresolved ownership and Back during initialization as failed activation. On known ownership, call bounded `shutdown(lease, notifyDestroy=false)` once; on quarantine do not call JNI. A failed or cancelled candidate does not receive OnDestroy as a successfully initialized session, does not commit destination history/last-ghost, and produces bundled fallback or Finished according to the current session transition. A subsequent successful activation still receives its proper first-boot event. Ignore late results from superseded session identities.
- [ ] Write failing orderly-switch and Back tests: finish outgoing script, then OnDestroy NOTIFY once and unload; no notification Value reaches ScriptPlayer. OnInitialize and OnDestroy carry no references. Cover repeated Back, Back during switch phases, failure with safely unloaded ownership, deadline expiry, and unresolved ownership. Preserve current timer suppression during outgoing dialogue.
- [ ] Implement the transition changes within the existing sealed session model. On a switch failure, retain the error on the bundled stage. On Back cleanup failure, log the error and always publish Finished; do not leave StageState.Error holding the Activity open.
- [ ] Test repeated start and pause/resume with an active fake native owner: same lease and no repeated load/unload/boot/notifications. Leave process/background save policy unchanged.
- [ ] Run the complete JVM suite and both APK builds. Independently review activation cleanup, exactly-once shutdown and final Activity exit paths; commit.

### Task 5: Real engine and UI evidence, then integration gate

**Files:** Create `app/src/androidTest/java/com/cattailsw/nanidroid/engine/NativePersistenceTest.kt`, `app/src/androidTest/java/com/cattailsw/nanidroid/ui/NativeRotationTest.kt`, `docs/native-persistence-evidence.md`; update `docs/testing.md`, Task 1 fixture/reproduction documents and `docs/implementation-handoff.md`. Add a host-side `tools/test-native-persistence.ps1` for multi-process orchestration. Test-only observation seams may be added narrowly to new host/runtime code; no public UI debug controls or fixture payloads in production.

- [ ] Stage disposable exact fixture copies using the Task 1 table. Preserve original NARs and all user data; never re-stage over the saved tree between write/read phases. Record on-device file paths before running any mutation.
- [ ] For each engine, drive the verified change, orderly switch away/back, and observe the restored value. Repeat with Back followed by a fresh app process. Inspect expected file contents as well as the reply/UI. Test LOBO OnMinuteChange separately from OnDestroy; prove nested profile creation and preservation. Assert the next GET sees initialized state and that notification responses never create dialogue.
- [ ] Split the abrupt-process test into independently orchestrated write and read phases. Start write-phase instrumentation asynchronously from the host script. Write/save A and record its file bytes/hash; optionally mutate unsaved B without another save. Publish a readiness marker containing a unique run ID, target PID, saved value and hash only after the save has completed, then keep that instrumentation test and target process alive behind a test-only wait gate without unloading. The host must observe the matching marker and confirm that exact target PID is still alive immediately before issuing `adb shell am force-stop com.cattailsw.nanidroid`. Do not finish instrumentation before force-stop and do not use elapsed sleep alone as readiness. Verify the original PID exits; an already-exited process, readiness timeout, PID mismatch or unexpected test completion invalidates the kill scenario. Expect write-phase instrumentation to be interrupted by force-stop; distinguish that expected termination from failures before readiness. Start fresh read-phase instrumentation, record a different target PID, and verify the saved bytes and engine-observed A without re-staging the fixture. Record that no orderly shutdown/unload ran before the kill. Never execute force-stop from the instrumentation process being killed. Give the host readiness/exit waits bounded deadlines and retain their logs; timeout cleanup must not be reported as a passing scenario. Distinguish this deliberate process kill from power loss and interrupted-write durability.
- [ ] Execute the concrete 2elf sequence from Task 1 through Activity recreation and physical orientation change. Observe the same lease/load count, no repeated first-boot/boot/initialize/destroy, and the same conversation flag's effect afterwards. Verify the app remains usable and does not enter fallback. A synthetic replacement test is additional coverage, not a claim that 2elf passed.
- [ ] With an injected cleanup failure, verify Back still finishes and the error is logged. Keep real permanently hung JNI out of device tests; Task 3's deterministic fakes own that scenario.
- [ ] Record LOBO rendering reproduction status and milestone assignment. An unresolved rendering symptom is listed separately from persistence results; do not silently fix or declare it resolved in this task.
- [ ] Run the full wrapper gate listed above. Inspect XML counts, skips, both-ABI APK entries, relevant UI screenshots and raw fixture logs. A green broad suite with fixture tests skipped cannot satisfy the persistence gate. Record any unavailable ABI/device or inaccessible fixture operation explicitly.
- [ ] Fresh whole-change review against this plan and design; send corrections to their owning workers. Commit evidence and handoff after checks. Carry forward the import requirement: duplicate installs must reject overwriting existing ghosts by default or receive a separately designed preservation policy. Milestones three through five remain outstanding; do not call the recreation complete.

## Acceptance and handoff

- [ ] Notifications, profile preparation, failed initialization cleanup, logged cleanup errors and nonblocking shutdown meet Tasks 2–4 tests.
- [ ] Quarantine remains monotonic under late load/request/unload/cleanup completion; no queued stale work touches a new owner.
- [ ] Real saved-value restore and 2elf rotation evidence are explicit, with any gaps preventing the corresponding completion claim.
- [ ] LOBO visual issue has a reproducible diagnosis or a precisely stated missing detail, and an assigned future milestone/focused fix.
- [ ] No native source or reference application code copied/modified; no ghost corpus payload committed.
- [x] User reviewed the design and plan and authorized implementation after tightening the live-process kill scenario. Preserve the existing fresh Sol worker/reviewer method and use the existing implementation task; do not create another implementation task.

