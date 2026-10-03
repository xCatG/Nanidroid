# Nanidroid Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. The lead coordinates; GPT-6-Sol subagents implement and independently review. Do not switch to lead-written product code.

**Goal:** Produce a fresh installable Android app that boots the bundled ghost through a new Kotlin table SHIORI, renders its two surfaces and timed dialogue, dispatches touch, and survives rotation without restarting playback.

**Architecture:** One application runtime owns startup, script playback, and event dispatch. A screen ViewModel exposes its state to a Compose stage; file access lives in a small repository. The built-in engine has no JNI dependency in this milestone.

**Tech Stack:** Kotlin, Compose/Material 3, AndroidX ViewModel/StateFlow and lifecycle-aware collection, coroutines, JUnit 4, Compose instrumentation tests, Android CLI, Gradle wrapper. Manual constructor injection; no DI framework.

**Spec:** [Approved design](../specs/2026-09-22-blind-recreation-design.md) and [approved product baseline](../specs/2026-09-22-nanidroid-baseline-spec.md).

**Status:** Approved 2026-09-23. CLI scaffold and input transfer complete; product implementation pending workspace handoff.

## Global Constraints

- One Android application module; application ID and namespace `com.cattailsw.nanidroid`.
- minSdk 31; compile/target API 37; eventual native ABIs arm64-v8a/x86_64.
- New standalone project root `C:\work\src\nanidroid-recreation` (called `<root>` below),
  outside every existing Git repository and worktree, with its own `git init`. Never
  generate inside or over the existing application: a nested directory would expose
  old source and history one `../` away, and Codex would also load the old repo's
  root AGENTS.md into every worker.
- Reference source is not an implementation dependency. Workers read only the approved documents, public specs, transferred ghost content, CLI-generated scaffolding, and new files.
- Copy the bundled ghost data unchanged. No old Java/Kotlin application or test code, old build scripts, or old architecture documents.
- Native copying remains pinned to `afc6a3a350fd10da35d085d9889c407ac5429a2b` but is deferred to milestone two; no native library needs packaging for this milestone.
- Shared memory is allowed as background. User instructions and approved scope govern; recalled modernization requirements do not expand this plan.
- GPT-6-Sol workers use fresh context (`fork_turns: none`) per task; fixes return to that task's worker. Fresh GPT-6-Sol reviewers inspect each deliverable. The lead does not author replacement product fixes.
- Workers are not alone in the workspace: edit only assigned files, preserve others' edits, and report interface changes before proceeding. Tasks below execute sequentially because their interfaces depend on previous tasks.
- Apply Android skills only within approved scope: edge-to-edge and lifecycle tests are relevant; Hilt, Navigation 3, adaptive navigation scaffolds, coverage infrastructure, and a broad screenshot matrix are not required.

## Review Focus

1. UTF-8 BOM before a comment or charset declaration must not corrupt content lookup or display names (Task 1).
2. Interrupted bootstrap or an existing install must not expose partial data or overwrite a live ghost (Task 1).
3. Rotation while loading or speaking must not install twice, increment boot count twice, or restart the script (Tasks 2 and 4).
4. A real bundled-ghost tap must dispatch exactly once with unscaled coordinates even though the engine returns no script (Tasks 3 and 4).
5. Pause/resume must freeze playback without elapsed-time catch-up, duplicate boot, or accumulating timer jobs (Task 2).

## Scope and evidence already collected

The allowed archive `src/main/assets/nanidroid.zip` is 74,537 bytes, 14 entries
(6 directories, 8 files), SHA-256
`2ebf24a2be8255011c5e004459a58dd1317eb7b12a55adcbe6b8b2977c89dc2d`.
It has a root install.txt, no enclosing directory, and selects `shiori,Nanidroid`.
Every text file in it (install.txt, both descript.txt, both content.txt) uses CRLF
line endings; readers remove CRLF/LF line terminators without trimming meaningful
content.
English and Japanese content are UTF-8 with BOM; an unavailable language falls
back to Japanese. The first BOM-prefixed line is a semicolon comment.

English OnFirstBoot is `\0\s0Hello World\e`; OnBoot is
`\0\s0Hi there! Welcome back!\e`. Japanese OnFirstBoot is also Hello World;
Japanese OnBoot is `\0\s0Nanidroid起動！\e`. The archive contains no touch,
second/minute, or surface-change responses. Those events return 204/no script.
Do not add canned touch dialogue to the shipped ghost just to make a test pass.

Surface 0 is `surface0000.png`, 250x144; surface 10 is `surface0010.png`,
235x200. Both carry alpha. There is no surfaces.txt or collision geometry.
No animation parser is necessary for this milestone. The shell descriptor has
UTF-8 BOM but no charset declaration; follow the approved descriptor default
rather than inventing a new decoding policy for unused metadata.

The installed CLI listed `empty-activity` with Compose/activity/AGP-9 tags.
Its successful-looking list/help output returned exit code 1; verify the actual
result of creation rather than assuming either success or failure from that
observation. JDK 17 is on PATH; adb was not found on PATH. SDK, emulator, and build
readiness have not been verified. No build or device run has occurred.

## File ownership and interfaces

All paths below are relative to `<root>`. The project has one `app` module;
production packages are under `app/src/main/kotlin/com/cattailsw/nanidroid/`.
Tests use `app/src/test/java/` and `app/src/androidTest/java/` with the same namespace.
The generated template may use a java source directory; normalize new Kotlin
sources once during Task 1 and keep one package layout.

| Task | Production responsibility and owned files |
| --- | --- |
| 1 | Project/configuration, AGENTS.md, docs/testing.md, assets/nanidroid.zip; ghost/DescriptorReader.kt, ghost/BundledGhost.kt, ghost/BundledGhostRepository.kt; engine/BuiltInShiori.kt, engine/ShioriEvent.kt, engine/ShioriEngine.kt, engine/ShioriReply.kt |
| 2 | runtime/ScriptPlayer.kt, runtime/PlaybackFrame.kt, runtime/SpeakerFrame.kt, runtime/StageState.kt, runtime/GhostRuntime.kt; data/BootStateStore.kt, data/PreferencesBootStateStore.kt; NanidroidApplication.kt; ui/StageViewModel.kt |
| 3 | MainActivity.kt, ui/GhostStage.kt, ui/StageGeometry.kt, ui/Theme.kt, AndroidManifest.xml and new UI resources; image decoding in ghost/SurfaceImageLoader.kt |
| 4 | Instrumentation tests and docs/testing.md/evidence; product defects go back to their owning implementer |

A worker may add a focused helper file inside its owned package if it removes
actual duplication; it must report it. No general host/session framework.

These are new interface contracts, not code copied from the existing app:

```kotlin
data class ShioriEvent(val id: String, val references: List<String> = emptyList())
data class ShioriReply(val status: Int, val value: String? = null, val charset: String = "UTF-8")
interface ShioriEngine { fun request(event: ShioriEvent): ShioriReply }
// BuiltInShiori(content: String) : ShioriEngine
// DescriptorReader.read(bytes: ByteArray): Map<String, String>
// BundledGhostRepository.ensureInstalled(): File
// BundledGhostRepository.load(root: File, language: String): BundledGhost
// BundledGhost includes directoryId, name, sakuraName, keroName,
// Map<Int, File> surfaces, and the chosen UTF-8 content String.
```

The built-in engine can return typed replies directly; string/byte SHIORI transport
belongs to native-engine integration. It still follows the baseline's 200/204,
Value, and UTF-8 response semantics. Do not build a fake JNI-shaped interface.

## Task 1: Fresh project and bundled-ghost loader

**Estimate:** 45 minutes of active execution, excluding external provisioning.
**Produces:** installable Compose template plus independently tested bootstrap and
built-in engine, with the interfaces above.
**Tests:** `ghost/DescriptorReaderTest.kt`, `ghost/BundledGhostRepositoryTest.kt`,
`engine/BuiltInShioriTest.kt` in the new local test source set.

- [ ] Before creation, confirm `<root>` is absent or empty; preserve and report
  unexpected contents. Confirm its nearest existing parent is not inside a Git
  repository (`git -C <parent> rev-parse --show-toplevel` must fail). After
  `git init`, confirm `git -C <root> rev-parse --show-toplevel` prints `<root>`. Read Android CLI create help/list, then run:
  `android create empty-activity --name=Nanidroid --minSdk=31 --output=C:\work\src\nanidroid-recreation`.
  Run `git init` in `<root>`. Inspect output files. Do not install into or overwrite
  the reference project.
- [ ] Give workers a fresh `<root>/AGENTS.md` repeating scope, file ownership,
  model, memory, source-copy, and test rules from this plan. Copy the design,
  baseline, and plan to the same relative paths (`<root>/docs/superpowers/specs/`
  and `<root>/docs/superpowers/plans/`) so their links stay valid, plus the asset;
  nothing else. Record the asset hash in `<root>/inputs.json`.
- [ ] Launch step: after the setup commit, the lead starts a new Codex task whose
  workspace is `<root>` and continues from there. Subagents inherit the spawning
  task's workspace and instructions, so none is spawned from the old worktree.
  Each worker's first report states its workspace root and the AGENTS.md files it
  loaded. Stop if the workspace differs from `<root>` or instructions from the
  reference repository are loaded. Global user instructions and approved skills
  remain allowed.
- [ ] Set the exact namespace/application ID and API levels. Use the generated
  compatible AGP/Kotlin/Gradle versions and verify their SDK-37 support through
  official docs; if unsupported, select a compatible stable toolchain and record
  exact versions in the version catalog before writing product code. Add Compose,
  lifecycle-runtime-compose, lifecycle-viewmodel-compose, coroutines-android,
  JUnit 4, coroutines-test, AndroidX test runner/core/ext-JUnit, and Compose test
  dependencies. Resolve versions through current official docs/CLI lookup rather
  than copying the old Gradle scripts. Record versions and JDK in docs/testing.md.
- [ ] Build the untouched new template with `./gradlew.bat :app:assembleDebug` from
  `<root>`. Verify SDK location with `android info` and list emulator options
  with `android emulator list`; document missing provisioning before expanding work.
- [ ] Write these behavioral tests before implementing the engine/decoder:

```kotlin
@Test fun bomBeforeCommentIsNotAnEvent() {
    val engine = BuiltInShiori("\uFEFF; event,string\r\nOnFirstBoot,\\0\\s0Hello World\\e\r\n")
    assertEquals(ShioriReply(200, "\\0\\s0Hello World\\e"),
        engine.request(ShioriEvent("OnFirstBoot")))
    assertEquals(204, engine.request(ShioriEvent("OnMouseClick")).status)
}
@Test fun descriptorKeepsCommasInValues() {
    val bytes = "\uFEFFcharset,UTF-8\r\nname,Hello, friend\r\n".toByteArray(Charsets.UTF_8)
    assertEquals("Hello, friend", DescriptorReader.read(bytes)["name"])
}
```

- [ ] Run `./gradlew.bat :app:testDebugUnitTest`; confirm the new tests fail for
  missing behavior, not a broken toolchain. Implement BOM removal, CRLF/LF line
  splitting, first-comma splitting, declared charset/CP932 descriptor default, UTF-8 content rows, comments,
  204 for missing events, default OnClose, and literal Reference0 substitution in
  the two ghost-switch rows. Test a reference containing `$` and backslash.
- [ ] Implement trusted bundled-asset bootstrap using filesDir/ghost/nanidroid and
  sibling private staging. Verify the fixed archive hash and path containment;
  publish only a complete tree, never replace an existing live target. On restart,
  remove only its own incomplete staging. No document picker or general NAR importer.
  Keep ZipInputStream/file operations testable on the JVM; inject asset opening and
  the target root instead of passing an Activity.
- [ ] Add filesystem tests using JUnit TemporaryFolder: existing target bytes stay
  unchanged; failure halfway through copying leaves no live target; retry from
  abandoned owned staging succeeds; an unrelated sibling remains unchanged.
  Exercise the actual archive via test resources transferred from the allowed
  asset, asserting en/ja selection, fallback, surface IDs, and missing touch response.
- [ ] Rerun the local suite and build, obtain fresh review, resolve findings through
  the same worker, and commit only the task's files in the `<root>` repository.

## Task 2: Runtime, playback, and ViewModel state

**Estimate:** 60 minutes.
**Consumes:** Task 1's BundledGhost and ShioriEngine.
**Produces:** application-owned runtime ready to connect to Compose.
**Tests:** `runtime/ScriptPlayerTest.kt`, `runtime/GhostRuntimeTest.kt`,
`ui/StageViewModelTest.kt`.

Use immutable `SpeakerFrame(surfaceId, visible, text, balloonVisible)` and
`PlaybackFrame(sakura, kero, ended)` models. `StageState` holds loading/ready/error,
that frame, display names, and surface image identities. The ViewModel forwards
runtime state and user methods; it does not duplicate playback or file ownership.

```kotlin
// ScriptPlayer(script: String) owns only pure playback state.
// advanceBy(milliseconds: Long): PlaybackFrame
// GhostRuntime(loadGhost: suspend () -> BundledGhost,
//              engineFactory: (BundledGhost) -> ShioriEngine,
//              bootState: BootStateStore, scope: CoroutineScope)
// val state: StateFlow<StageState>
// suspend fun start(language: String)
// fun setResumed(resumed: Boolean)
// fun click(speaker: Int, x: Int, y: Int)
// fun close() // dispatch OnClose; state signals finished after reply playback
// interface BootStateStore:
//   suspend fun recordActivation(directoryId: String): Boolean // true on first activation
//   suspend fun consumeOnboarding(): Boolean // true once per installation
```

- [ ] Write the player test, then run the focused local test before implementation:

```kotlin
@Test fun bootScriptRevealsTextThenEnds() {
    val player = ScriptPlayer("\\0\\s0Hi\\e")
    val start = player.advanceBy(0)
    assertFalse(start.ended)
    assertEquals("", start.sakura.text)
    val halfway = player.advanceBy(50)
    assertEquals("H", halfway.sakura.text)
    val complete = player.advanceBy(1_500)
    assertTrue(complete.ended)
    assertEquals("Hi", complete.sakura.text)
    assertEquals(0, complete.sakura.surfaceId)
    assertEquals(10, complete.kero.surfaceId)
}
```

- [ ] Implement only the bundled/onboarding needs: speaker commands, surface changes,
  text at 50ms steps (speaker and surface commands take no
  time; no text at t=0; one character per elapsed 50ms), and end with a one-second pause. Support newline/clear if used
  by the new onboarding text; consume unsupported tags and their bracket arguments
  without showing command payloads. Broader script semantics remain milestone four.
  Keep completed text in the returned frame, with balloons hidden when the queue
  drains; this makes completion and visibility separate observable properties.
- [ ] Construct one runtime from NanidroidApplication. File loading runs on IO;
  runtime mutations are serialized on its injected scope's dispatcher. Join an
  in-flight start and reuse a ready start; do not create a new engine on recreation.
  Write a short new onboarding script using supported tags, ahead of first boot.
  Persist first activation/onboarding using a small repository (SharedPreferences
  is adequate here); never persist a native handle or the script player.
- [ ] Use a fake BootStateStore and recording ShioriEngine with coroutines-test.
  `runTest` advances a supplied StandardTestDispatcher: call start twice while a
  CompletableDeferred blocks loading, release it, and assert one load, one engine,
  one activation write, and one OnFirstBoot. A new runtime sharing that store gets
  OnBoot. Unknown touch responses clear queued dialogue but produce no fake reply.
- [ ] Pause at a partial text frame; advance virtual time by 60 seconds and assert
  the frame unchanged. Resume and advance 50ms: at most one normal playback step
  occurs, with no new boot. Add a ViewModel recreation test over the same runtime
  to prove the same partial frame is observed rather than reconstructed.
- [ ] Emit second/minute events only while resumed, using elapsedRealtime hours;
  do not replay missed ticks. OnClose waits for its real built-in script to finish.
  Expose failure state for IO/bootstrap errors; do not show an endless loading label.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest`, review, and commit task-owned files.

## Task 3: Render the two-character Compose stage and connect input

**Estimate:** 60 minutes.
**Consumes:** runtime/StageViewModel state and Task 1's surface files.
**Produces:** the milestone's first end-to-end usable APK.
**Tests:** `ui/StageGeometryTest.kt` locally; `ui/GhostStageTest.kt` on device.

`StageGeometry` is pure Kotlin and exposes
`scaleFor(sakuraWidth: Int, sakuraHeight: Int, keroWidth: Int, keroHeight: Int,
width: Int, height: Int): Float` and a surface-rectangle inverse mapping to
unscaled integer local coordinates. Its return is shared by drawing and hit tests.

- [ ] Pin the no-upscaling layout rule in a local test:

```kotlin
@Test fun scaleUsesBothWidthsWithoutUpscaling() {
    assertEquals(0.5f, StageGeometry.scaleFor(250, 144, 235, 200, 243, 100), 0.001f)
    // Width-bound: 388 / (250 + 235) = 0.8; the height limit (1000 / 200) is looser.
    assertEquals(0.8f, StageGeometry.scaleFor(250, 144, 235, 200, 388, 1000), 0.001f)
    assertEquals(1f, StageGeometry.scaleFor(250, 144, 235, 200, 1000, 1000), 0f)
}
```

- [ ] Implement MainActivity as a thin host, calling enableEdgeToEdge and observing
  StageViewModel through collectAsStateWithLifecycle. Forward resumed state and
  Back; keep bootstrap/runtime ownership out of composition. Configure a wallpaper
  window background with transparent Compose stage rather than reading wallpaper
  pixels or requesting broad storage access. Put the content inside safe drawing
  bounds exactly once. Use ordinary opaque balloon/control surfaces for legibility.
- [ ] Decode the two approved PNGs off the UI thread; preserve alpha and cache for
  this session. Do not create a general compositor or infer collisions from alpha.
  Draw sakura bottom-right and kero bottom-left, never scale up. Place scrollable
  balloons above the characters using the baseline height rule; show loading/error
  states with readable labels. Account for balloon space in constrained windows.
- [ ] Add one tap gesture per character. Convert the hit location through the exact
  draw transform; send OnMouseClick references x, y, 0, speaker, empty collision,
  0, touch. Consume balloon taps so they do not hit a character. Empty/inert balloon
  taps may toggle a minimal control surface; ghost selection/About are later work,
  so do not ship dead buttons pretending to implement them. Provide named semantics
  and an accessibility click using the character's center in surface coordinates.
- [ ] Add a Compose test with a recording runtime engine. Tap the rendered kero
  center and assert one OnMouseClick, speaker 1, expected surface-pixel center and
  empty collision. Repeat with the real built-in engine: dispatch occurs once and
  returns 204, without asserting new dialogue. No production event-log UI is needed.
- [ ] Add a blocked-loader test showing Loading; then release it and assert both
  image semantics exist. Check an explicit failure state using a failing injected
  loader. A balloon activation must not dispatch a character event.
- [ ] Run local tests, `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest`,
  and focused connected tests on the disposable emulator. Review and commit.

## Task 4: Prove startup, touch, and recreation on a device

**Estimate:** 30 minutes, excluding emulator provisioning/build downloads.
**Consumes:** completed runnable project; no new product subsystem.
**Tests:** `WalkingSkeletonInstrumentationTest.kt` in androidTest.

- [ ] Use a disposable API 31+ emulator compatible with the chosen API-37 build;
  record API, ABI, density, and orientation. Confirm it has no existing Nanidroid
  user data before installing this same-ID replacement. Do not uninstall a user's
  app on an existing personal device. Use Android CLI help for exact run/layout/
  screen commands, and SDK platform-tools if adb is not on PATH.
- [ ] Add an ActivityScenario recreation test with a test application/composition
  root using the production runtime and a recording engine decorator. Hold loading,
  recreate, release loading, and assert one OnFirstBoot; then recreate midway
  through dialogue and assert playback continues rather than repeats. Keep test
  probes in androidTest, not public product UI or process-global reset hooks.
- [ ] Run the normal app with the unchanged asset in English: confirm first boot
  Hello World, sakura/kero images, timed text, alpha, and a dispatched touch with no
  new response. Relaunch the process and confirm OnBoot's Welcome back dialogue.
  Repeat locale fallback using an unsupported language and confirm Japanese content.
- [ ] Inspect portrait and landscape screenshots while balloons are visible; verify
  character positions, readable dialogue, safe system-bar bounds, and rotation
  continuity. Confirm Back waits for OnClose then finishes. Capture screenshot
  and layout evidence from the current APK, not template previews.
- [ ] Run final milestone commands from `<root>`:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew.bat :app:connectedDebugAndroidTest
```

- [ ] Record `<root>/docs/milestone-1-evidence.md`: command results, actual APK
  path and SHA-256, toolchain/device versions, tested locale/rotation flows, screenshot
  paths, and any unavailable checks. APK output is normally
  `<root>/app/build/outputs/apk/debug/app-debug.apk`; verify rather than assume.
  Do not call this native-engine or full-ghost compatibility evidence.
- [ ] Fresh GPT-6-Sol reviewer checks the complete new project against this plan.
  Send any fixes to the responsible worker and rerun affected checks. Commit source
  and the short evidence report; keep APKs, caches, and generated captures out of Git.
  Present the usable APK and results before planning milestone two.

## Milestone limit and deferred work

Initial estimate: 3 hours 15 minutes of active execution. Reassess at 6 hours
30 minutes, or earlier if toolchain/device provisioning prevents end-to-end proof.
Record elapsed work and external waits separately; do not silently extend scope.

Not in this plan: native engines/ABI checks, arbitrary ghost selection/switching,
external import, complete script grammar, animation/PNA, choices/input/links,
About/license UI, and broader layout polish. These belong to the approved later
milestones, not discarded requirements. No 23-archive parity claim or inherited
modernization audit. Basic file safety and lifecycle correctness apply now.

## Plan self-review

- First-milestone scope maps to Tasks 1-4; native and broader product requirements
  are explicitly deferred to their approved milestone.
- Task boundaries and interfaces are new; no source-exposed product implementation
  is supplied by the lead. Example tests specify observable behavior only.
- All five review-focus cases have owning tasks and explicit checks.
- Actual bundled content justifies the limited script subset and no-response tap test.
- Dependency versions are determined from the fresh template and official lookup,
  recorded before product implementation; no guessed library version is required.
- The original application is unchanged. Approval of this plan precedes creation
  of `<root>` and the first implementation dispatch.
