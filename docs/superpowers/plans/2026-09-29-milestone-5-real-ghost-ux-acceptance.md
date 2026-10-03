# Milestone 5 real-ghost UX acceptance follow-up

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task after author approval. Fresh GPT-6-Sol implementer and independent reviewer per bounded task; return corrections to the responsible worker.

**Status:** APPROVED for execution by the author, 2026-09-29. M5 acceptance remains OPEN. This is an M5 acceptance extension, not milestone 6 or release authorization.

**Goal:** Prove that the supported interactions can be completed through the actual app UI with unchanged representative ghosts, and close the input-recreation coverage gap without substituting direct runtime probes for user journeys.

**Architecture:** Reuse the current Compose stage, runtime, corpus manifests and test boundaries. Add focused UI tests and a compact scenario/evidence matrix, not another runner framework. Diagnose a demonstrated failure before changing product code.

**Tech Stack:** Kotlin, Compose instrumentation, ActivityScenario, existing native SHIORI engines, explicit-serial ADB and retained device captures.

**Spec:** `../specs/2026-09-22-nanidroid-baseline-spec.md`, `../specs/2026-09-22-blind-recreation-design.md`, and `2026-09-27-milestone-5-corpus-polish.md` Tasks 2, 5 and 7. Author clarification: real-ghost UX/UI coverage belongs in M5; acceptance declined pending this follow-up. Spoken TalkBack verification is author-deferred, not passed.

## Scope and constraints

- Workspace: C:/work/src/nanidroid-recreation. No legacy application/reference code or tests. Native tree remains pinned; original archives remain unchanged.
- Keep minSdk 31, compile/target 37, package com.cattailsw.nanidroid and existing architecture. No new dependency, general automation framework, modal-choice redesign or speculative refactor.
- Cover the original eight families. The private original39 scan remains separate and blocked by its existing isolation requirements; no new downloads or enlarged corpus.
- Use one owner per device, explicit serial, recorded API/ABI/APK hashes. API31 and API37 emulators may be used sequentially; Pixel 7 receives only data-preserving UI checks. Do not install the destructive corpus instrumentation runner or clear app data on Pixel. Preserve installed ghosts/saves and restore changed device settings.
- Supported behavior failures block acceptance. Existing exclusions (for example LOBO replace, unsupported AYA5 scripts, audio and OnUserInputCancel) remain exclusions, not new features to implement.
- No merge, release, integration or automatic acceptance. Previous proposed evidence substitutions are not author-approved by this draft.

## Approach and review focus

A smoke-only rerun would miss the reported gap. Exhaustive traversal of every dictionary branch is unbounded. Use an authored journey for every representative plus a shared edge-case matrix and deeper Satori/Kawari/YAYA journeys.

Five review priorities, owned by Tasks 1-4:
1. A visible menu exists but physical tapping never reaches its authored handler.
2. Timers, speaker changes, scrolling or rotation invalidate a still-visible choice.
3. Typed text is lost or submitted twice across IME and Activity lifecycle changes.
4. A nested dialog or farewell traps Back or accidentally closes/switches the ghost.
5. A setting changes on screen but is not restored after a genuine native save/reload.

Evidence levels must be explicit: authored physical UI; authored Compose semantics action; direct runtime/native probe; synthetic UI. Only the first two establish authored UI coverage; each family also needs physical pointer interaction. Never claim full ghost compatibility from one path.

## Review clarifications (2026-09-29; part of this draft)

These decisions refine the tasks below and were approved with this plan on 2026-09-29. They authorize execution and the specified acceptance methods, not retrospective M5 acceptance.

### Known menu-access gap: Task 1 prerequisite

Before freezing routes, diagnose the already failed 2elf MainActivity double-tap attempts. Existing bare-Compose RealSatoriDoubleTapUiTest proves the kusa collision reply, not the desired menu. Direct runtime.doubleClick is not a substitute. Record screen-to-authored coordinates and collision region, delivered event timestamps relative to the actual platform double-tap interval/slop, Activity/window focus, and dialogue-token changes between taps. Compare a known kusa target and a dictionary-supported menu target. Check whether dialogue-driven pointerInput restart discards the gesture; preserve stale-session safeguards rather than simply removing token keys. Two separately launched ADB tap commands are not a calibrated double-tap.

Distinguish injection timing, wrong collision target and a product recognizer defect before editing. Any demonstrated correction to this known menu-access gap is planned work, excluded from the two-new-defect cap; it still needs red/green tests and independent review. Budget up to two fix hours for it, then checkpoint if unresolved. Do not assume every ghost opens a menu with the same gesture.

### Pointer evidence and native-test isolation

For emulator authored physical-path evidence, inject timed MotionEvents into the actual resumed MainActivity window using UiAutomation/UiDevice, record delivered times/coordinates and resulting authored event/reply. This is window-level injected touch, not a human finger. Compose performTouchInput is Compose-level gesture evidence; performClick is semantics evidence; neither is labeled window-level physical input. Use existing instrumentation facilities, not a new runner framework.

Pixel uses actual human gestures or a verified existing window-level timed injection facility that does not require installing the destructive test runner. Plain ADB single taps can navigate controls, but sequential process launches cannot stand in for a verified double-tap. If no safe timed facility is available, prepare a short manual script for the author with screenshot/log capture, and leave that row pending until performed. Do not implicitly authorize installing a new helper on Pixel.

Every new native UI test must require its exact fixture/host arguments and explicitly close its owned app runtime, await Finished and verify native availability/lease release in teardown before the next test. ActivityScenario.close alone is insufficient. Preserve primary failure and cleanup failure separately. Verify one ordered pair with the existing LOBO native probe; quarantine a hung host/process rather than reuse it. Test setup must not overwrite another test's runtime or saved ghost tree.

### Earthquake input is a known protocol limitation

The M4 evidence already shows Earthquake OnNameTeach expects its named event with text in Reference0; the approved runtime sends OnUserInput(ID,text). Its UI draft/rotation can be tested, but a successful name mutation through that route is not currently a supported promise. Task 1 must identify another reachable input using the approved protocol before committing to native submit/readback and Pixel input completion. Do not run Earthquake name entry repeatedly hoping it passes.

Default proposed disposition: retain the existing protocol scope and explicitly mark Earthquake named-input submission excluded. If no supported authored input or YAYA UI setting route exists in the eight families, stop at the Task 1 checkpoint with the exact missing row and smallest proposed protocol amendment for author decision. Do not count this known limitation as a newly discovered defect, invent a setter, or silently replace a required authored journey with a synthetic one. Named-input dispatch is not approved by this draft.

### Draft-loss pass criteria

Keep the original immediate-recreate synthetic variant alongside the verified-precondition variant. Predetermine five fresh-process repetitions per API for each variant, two recreations per repetition; no post-hoc extension until green. This is a bounded diagnostic sample, not a statistical proof: historical runs do not establish an independent 25% failure probability. Record the actual field update, token and lifecycle ordering without adding an idle/frame wait to the immediate variant.

The precondition variant must retain exact text and submit exactly once with correct reference values. Real-keyboard rotation must also pass on the authored supported route; input draft/rotation may be observed separately on Earthquake despite its submit limitation. Failure only in the immediate variant is investigated for whether the edit was delivered before state saving. Do not assert that pre-next-frame recreation is inherently unreachable by a user. Classify it as a test artifact only with causal evidence; real rotation is important corroboration, not the sole possible proof of a lifecycle defect. Any unexplained failing fixed-set row remains an acceptance decision.

### Existing acceptance decisions and dialog policy

Approval of this revised plan would approve explicit-serial instrumentation as the required device-gate method; the literal Gradle command remains unperformed. All new authored UI journeys run on the final frozen product APK. Prior corpus/deep/persistence results may be retained only with an explicit unchanged-code dependency rationale; runtime, importer or save fixes trigger affected final-APK reruns. The draft-history uncertainty is addressed by Task 2, not waived. For provider PAUSE, the proposed acceptance contract is deterministic actual PAUSE/state recovery; the physical picker/background attempt is corroboration. A shade that remains RESUMED is not a failure or a PAUSE pass. These are explicit prospective plan choices for the author to review.

Ghosts/About currently use plain remember and dismiss on Activity recreation. Proposed M5 policy: permit dismissal on rotation, requiring the same live ghost, no extra boot/close, preserved pending runtime interaction and successful reopening. Task 4 must rotate with each dialog open and assert these conditions. Preserving dialog-open state would be a separate requested change, not an accidental requirement discovered in testing.

Explicitly rerun GhostInteractionUiTest.ordinaryInputBackCancelsButFarewellInputBackSkipsClose on the final API37 APK, alongside the other input paths. Contrary to the review's uncertainty, the retained second-device-post-layout/focused-ui.log already lists this method in the final focused run; retain that evidence and its earlier system-ANR failure rather than label it previously untested.

### Budget and reporting

Base tasks total 15-23 active hours. Allow up to two additional hours for a demonstrated known menu-access correction and up to four hours for newly discovered fixes; maximum planned envelope is 29 hours, with mandatory checkpoint at 20 active hours and after Task 1/Task 3. The new-defect cap excludes the known gesture-access diagnosis and the known named-input protocol limitation, but neither exemption permits unbounded fixing or a protocol change. Stop for revised scope/cost when a cap is reached. Record actual time from execution start. Keep raw runs outside Git and a concise scenario matrix in its dedicated document; evidence size is not measured by unverified token estimates.

Test growth is budgeted too. New real-ghost journeys live in one data-driven `RealGhostInteractionTest` driven by the Task 1 scenario table, not in new per-family method sets. When a UI journey replaces an existing `NativeTalkProbeTest` runtime-injection method, map its assertions before folding or deleting the probe. Preserve distinct native status, reference encoding, lease/cleanup and persistence assertions unless the replacement proves them too; matching visible text alone is not equivalent coverage. The data-driven test uses explicit per-scenario expectations, not a new scenario language or generic runner framework. Report net test/tool line change at each checkpoint; exceeding roughly +600 net test lines is a checkpoint trigger, not an automatic failure.

## Task 1: Diagnose menu access and freeze the scenario map (3-4 active hours)

**Files:** Create `docs/testing/milestone-5-ux-scenarios.md`; read the original manifest, `docs/testing/native-persistence-fixtures.md`, existing UI/native tests and bounded unchanged ghost dictionary data only.
**Output:** Scenario IDs with archive SHA-256, initial state, exact visible labels/gesture location, UI action sequence, expected observable result, supported-contract reference and device assignments. No guessed event invocation as a menu shortcut.

- [ ] Inspect the existing eight-family evidence and authored data to map reachable menus, choices, input and links. For each feature mark authored/reachable, absent with data evidence, unsupported by baseline, or unverified. An absent feature is not a passing test. Bound dictionary investigation to 20 minutes per family; unresolved routes stay gaps for the checkpoint, not silent synthetic substitutions.
- [ ] Use these required representatives and priorities:

| Family | Required visible journey and discovery target |
| --- | --- |
| 2elf 2.46 / Satori | Physical authored touch/menu, navigate a supported setting choice, return to dialogue; investigate the existing 見切れ利用 setting route for UI change/readback. |
| Earthquake Duo 1.0.1 / YAYA | Physical menu opening, offered OnAiTalk choice during timers, visible native response and both speakers. Name entry: draft/rotation only; submit excluded (known protocol limitation). Investigate Config Menu as the YAYA setting route. |
| LOBO 1.0.0 / Kawari | Physical menu, talk-rate selection/readback if reachable through supported constructs, return/switch; visually inspect composed authored wolf. |
| Big Red Button | Physical authored button interaction and observable surface change; do not require invented dialogue or menus. |
| Nanika Atsume 1.0.1 | Physical authored interaction and one menu/choice path if offered; otherwise document the actual surface/talk response. |
| Snake and Otacon 1.3.2 | Physical supported menu/choice path where offered, authored response and single-Back farewell. Preserve documented valid random branches. |
| Watchdog Bancho 1.0.0 | Physical supported interaction/menu route and response; retain known descriptor encoding limitation. |
| Yes Man 2.1.1 | Physical menu and offered Say Something choice, authored response; identify random valid responses without accepting arbitrary output. |

- [ ] Every family requires visible boot/stage, meaningful authored interaction, rotation with state preserved, switch away/back and ordinary close. Record intentionally hidden speakers separately from missing artwork. Nested menu, authored input and authored link coverage must be assigned wherever discovery finds supported reachable paths.
- [ ] Freeze exact routes before running their acceptance tests. If no representative offers a required real input/link path, explicitly report that gap; synthetic tests cover the shared control but cannot replace authored coverage without author disposition. No archive edits or injected event to manufacture a path.
- [ ] Independent review of the map before Task 2. Report scenario count and revised cost if discovery changes scope; do not invent exact labels in advance.

## Task 2: Establish input and lifecycle evidence (3-5 active hours)

**Files:** Existing `app/src/androidTest/java/com/cattailsw/nanidroid/ui/GhostInteractionUiTest.kt` (contains `GhostActivityRecreationTest`); new `ui/RealGhostInteractionTest.kt` (same source-set package) for all real-ghost journeys; synthetic cases stay in the existing classes. Production ownership, only after a demonstrated defect: `app/src/main/kotlin/com/cattailsw/nanidroid/ui/GhostInputDialog.kt`, `ui/StageViewModel.kt`, or `runtime/GhostRuntime.kt`, assigned individually.
**Output:** Reliable precondition/restore/submit assertions and an authored native input journey identified by Task 1.

- [ ] Reuse the same real Activity path as the historical failing test. Observe the exact editable text and active input token before recreation, then verify exact text/token afterward and exact submitted reference value, not merely OnUserInput event count. Record boot/load counts to detect a replacement session.
- [ ] Predeclare five fresh-process repetitions per API (31 and 37) for each of the two synthetic variants (immediate recreation and verified precondition): 20 repetitions total, each with two recreation cycles while the input remains pending. Include a Latin draft and a Japanese draft across the fixed set. Record every outcome; do not extend repetitions until green. A failure blocks that row until explained and corrected or explicitly dispositioned.
- [ ] Separate deterministic ActivityScenario.recreate from actual portrait/landscape rotation and IME interaction. On the authored native input route, type through the displayed field with real IME visible, verify draft, rotate, verify again, submit and observe the ghost's response. Exercise normal OK and IME Done in separate cases; cancellation/Back must dismiss according to current normal/farewell policy without spurious submission. A new input token must not inherit an old draft.
- [ ] Run the shared synthetic farewell-input second-Back and stale-token cases where no authored counterpart exists, labeled synthetic. Do not add OnUserInputCancel protocol behavior.
- [ ] If a failure reproduces, retain text/token/Activity lifecycle and save/restore observations sufficient to localize entry, save, restore or token invalidation. Temporary diagnostic hooks must remain test-local and be removed or justified before commit. Red/green a minimal defect fix and rerun its actual device journey; do not describe a synchronization-only test change as a product fix.
- [ ] Independently review and commit. Historical failures remain documented; report current bounded evidence and residual uncertainty without claiming random reruns prove the old cause.

## Task 3: Execute the eight authored UI journeys (6-9 active hours)

**Files:** `ui/RealGhostInteractionTest.kt`; reuse existing `ui/Milestone4CorpusDynamicUiTest.kt`, `ui/NativeTalkProbeTest.kt`, `ui/GhostInteractionUiTest.kt` where appropriate. Update the scenario map with raw-evidence paths. Real-ghost recreation tests belong in RealGhostInteractionTest.kt, not the synthetic GhostActivityRecreationTest class. Do not put a second general corpus orchestrator in tools.
**Input:** Task 1's exact routes and original archive hashes. **Output:** Per-family UI results, not native-only substitutes.

- [ ] Run all eight family journeys on the API37 emulator using real imports and the visible stage. Use pointer gestures for character targets and at least one actual choice tap wherever choices exist. Compose semantics may support assertions or complementary actions, but do not bypass runtime UI callbacks with choose/request/setter calls.
- [ ] For Earthquake and each other ghost offering choices, leave a menu open across at least five delivered second ticks and one rotation, then select the original offered item and verify its resulting visible reply. Shared synthetic tests retain speaker-switch coverage if no authored route exercises it. Verify nested menu Back/cancel behavior where authored.
- [ ] On available authored long menus, scroll to and select the last choice. Check separate choice targets do not fire character events or toggle controls. Run an authored reachable HTTP/HTTPS/mailto link if present using a test launcher recorder or external-app observation; never actually send email. Japanese-adjacent recognition may remain a separately labeled synthetic edge case if absent from the corpus.
- [ ] For 2elf, Earthquake and LOBO, change one supported authored setting through its discovered UI route, verify visible readback, rotate, orderly switch away/back, and verify it persists. Preserve the same installed tree between mutation and reload. Native file/readback probes may corroborate UI results but cannot perform the setter. If a route requires an excluded construct, report the limitation and request disposition; do not silently extend protocol scope.
- [ ] On Pixel, repeat the three engine-family interaction routes, including setting reload and, if Task 1 identified a supported authored input route, one native input recreation, with two cycles each. Existing six boot/switch cycles do not substitute for interaction checks. Record any existing user setting before altering it and restore through UI afterward; no app clear/reinstall/pristine overwrite or synthetic instrumentation on the phone.
- [ ] Review screenshots at the action and response, not merely loading/semantics nodes. Record authored random alternatives precisely; empty response, timeout or unrelated text is not an acceptable generic branch. Commit tests and report after independent review.

## Task 4: Combined polish and focused fixes (2-3 active hours, plus checkpointed fixes)

**Files:** Existing `ui/StagePolishTest.kt`, `ui/CharacterGestureTest.kt`, `ui/AboutDialogTest.kt`, `ui/GhostImportUiTest.kt`; only the demonstrated owning production component may change.
**Output:** Actual interaction remains usable under constrained layout/lifecycle; no blanket test rewrite.

- [ ] Reuse the established API37 four-view matrix (360x640 / 640x360 dp, font 1.0 / 2.0). On the real two-speaker/menu representative, perform the choice interaction at each view. On the authored input representative, exercise landscape 2.0 with real keyboard and reachable actions. Keep synthetic long-text/last-choice tests for stress conditions absent from authored paths; record actual viewport/insets and inspect captures.
- [ ] Open/dismiss Ghosts and About while a native ghost is active, return to the same session without duplicate boot or unintended close, then complete the pending supported interaction. Reuse instrumentation counters rather than infer ownership from screenshots.
- [ ] Retain the provider pause test as deterministic lifecycle coverage. Attempt one controlled real system-picker/background route on an emulator while a slow import is active; record actual Activity state. A notification shade that stays RESUMED does not satisfy PAUSE. Do not conflate missing physical PAUSE with failed deterministic behavior.
- [ ] For demonstrated in-scope defects: failing regression, smallest owning-component correction, independent review, affected authored device rerun. No cleanup-only edits. After two newly diagnosed defects or four active hours of fixes, stop and present remaining findings and cost. This checkpoint does not waive failures; pinned-native changes require separate scope approval.

## Task 5: One final acceptance packet (1-2 active hours)

**Files:** Keep the matrix and new run details in `docs/testing/milestone-5-ux-scenarios.md`. Add only a short dated pointer/status section to `docs/milestone-5-evidence.md`; update `docs/testing.md` and `docs/implementation-handoff.md` without another long synthesis.

- [ ] Freeze product source and APK hashes after fixes. Run the existing JVM/lint/build gate and explicit-serial full API31 instrumentation; record selected, passed, skipped and excluded methods with the existing targeted-evidence mapping. Avoid unfiltered Gradle fanout to Pixel. Treat serial execution as the proposed plan method, not a claim the literal Gradle task ran.
- [ ] Run all newly required authored journeys on the frozen product APK; if it changed after a journey, rerun affected cases and explain why retained cases are unaffected. Run targeted API37 matrix/input and final three-family Pixel journeys. Preserve pre-fix failures. Existing deep/native persistence evidence is retained only with an explicit dependency rationale; a runtime/save change requires affected persistence reruns.
- [ ] Publish one compact matrix: family, exact authored route, physical versus semantics action, viewport, recreation/choice/input/link/save coverage, outcome, archive/APK hashes and evidence. Separate absent features, unsupported features and missing coverage. No universal green family badge.
- [ ] Independent final review verifies supported user journeys and evidence substitutions, test isolation/native cleanup, and production/test/tool growth. Include actual measured active/wait time, not reconstructed estimates. No further broad review/refactor round by default.
- [ ] Present remaining gaps and a recommendation to the author. Acceptance stays OPEN until the author accepts the resulting scope/evidence. TalkBack remains the already approved deferral; no other waiver is inherited.

## Estimate and stopping rules

Provisional total: **15-23 active engineering hours**, excluding build/device waits and up to four hours of newly discovered fixes. Log active and wait time from the start. Checkpoint after Task 1 and after Task 3; mandatory reassessment at 20 active hours, or sooner at the defect/fix cap. Do not quietly enlarge the ghost corpus, implement excluded constructs or create a new test platform to finish the table. This estimate supersedes no historical budget claim; it is a separately reviewed follow-up budget.

## Definition of ready for acceptance review

All eight authored journeys have evidence or a named unresolved limitation; required reachable input/choice/link scenarios have been exercised through UI; input preconditions and lifecycle assertions pass the fixed test set; three-engine UI mutation/reload and Pixel interaction results are present where supported. Missing required routes, reproduced supported-path defects or unexplained failures in the new fixed run remain open decisions, never synthetic passes. The author gets a bounded compatibility statement and concise gap list, not a promise of exhaustive ghost-script coverage.



