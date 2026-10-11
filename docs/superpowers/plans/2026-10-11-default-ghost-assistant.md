# Default Ghost Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Project AGENTS.md requires sequential implementation and independent review; use fresh GPT-6.1-Sol implementers and GPT-6-Sol reviewers with `fork_turns: none`.

**Goal:** Make the existing default characters useful Nanidroid assistants: tap 郭嘉榆 for app introduction/help and tap ドロイド君 for app tools, including a debug-build panel.

**Architecture:** Keep the existing built-in table SHIORI and compose it with a bundled-assistant handler. The handler supplies localized balloon menus and typed app-action replies; the runtime admits those actions only from the actual bundled session and publishes a claimable, session-scoped request to the Compose UI. Load assistant text from APK assets, leaving the original bundled archive, installed files, and PNGs intact.

**Tech Stack:** Existing Kotlin/Compose, ViewModel, lifecycle-aware StateFlow, manual constructor injection, JUnit 4, coroutines-test, focused Compose instrumentation. No new dependencies.

**Spec:** The approved in-chat design is captured in the next section; this is bounded enhancement work, and the author explicitly requested a written plan and Astra review. Existing constraints: `AGENTS.md`, `README.md`, `docs/superpowers/specs/2026-09-22-nanidroid-baseline-spec.md`, and `docs/superpowers/plans/2026-10-02-replacement-integration.md`. This request authorizes the assistant enhancement beyond the original integration scope.

## Approved intent and concrete scope

- Keep the author's hand-drawn current PNGs, names, and poses. Artwork changes await possible Painter RIF recovery.
- These characters serve Nanidroid. Portable operation in other Ukagaka hosts is not a goal.
- Any ordinary single tap on 郭嘉榆 opens an introduction/help menu in that character's balloon. Topics: What is Nanidroid?, Import and switch ghosts, Touch and dialogue controls. Topic pages have a Back choice and a Dismiss choice.
- Any ordinary single tap on ドロイド君 opens a tools menu in that character's balloon. Choices: Ghosts, About and licenses, Debug tools (debug builds only), Dismiss. Ghosts and About reuse their existing dialogs; import remains inside the existing Ghosts flow.
- Single taps route by SHIORI Reference3 (speaker 0 or 1); no named collision regions are necessary for this whole-character interaction. Do not add unused surface definitions. Existing double-tap/move behavior remains unchanged.
- First boot welcomes the user and explains the two roles with choices to open either menu. Later boots give a brief reminder. Preserve existing lifecycle replies through the delegated table where not explicitly replaced.
- English and Japanese assistance content ship together. Follow the current language selection/fallback policy (English when `en`, Japanese fallback for unprovided languages); keep all new help/menu/dialog copy consistent with that selected language.
- Initial Debug tools is a small debug-only dialog exposing the existing Show touch bounds toggle and a Close button. Event injection, arbitrary SakuraScript execution, request logs, native diagnostics, idle banter, relationship state, and extra features are excluded from this pass.
- Help must describe actual behavior: use Ghosts to import a NAR with the document picker; imports are fresh installations, refuse existing destinations, and do not migrate legacy data. Tap empty stage to show/hide controls; choice links and input prompts are ghost-dependent. Do not promise generic taps always produce dialogue in imported ghosts.

## Global constraints

- Work in `C:/Users/yenchi/.codex/worktrees/cc61/Nanidroid`; report workspace and loaded AGENTS.md first. Already an isolated linked worktree; no second worktree is required.
- Never use superseded code/history or search adjacent repositories. The Git parent is rollback metadata only.
- One app module; production Kotlin under `app/src/main/kotlin/com/cattailsw/nanidroid`; tests under `app/src/test/java` and `app/src/androidTest/java`.
- Namespace/application ID `com.cattailsw.nanidroid`; minSdk 31; compile/target 37; arm64-v8a and x86_64. Preserve pinned wrapper/catalog/NDK/CMake configuration.
- Do not modify native sources, native manifests, `nanidroid.zip`, its pinned SHA-256, PNG bytes, imported ghost files, or installed bundled files. Do not overwrite saved state or introduce migration.
- Imported/native ghosts cannot acquire app actions by naming their ghost Nanidroid, declaring `shiori,Nanidroid`, returning matching choice IDs, or embedding app-like SakuraScript commands. Existing safe link behavior remains separate.
- App actions are a closed typed enum; no URLs, commands, paths, arbitrary intents, or string-based reflection in this bridge.
- Debug entry visibility and execution are both controlled by `ApplicationInfo.FLAG_DEBUGGABLE`. Release builds reject a debug request even if directly invoked.
- No push, PR, merge, publication, signing-key work, or user-device installation. Use disposable emulators only. Keep outputs, secrets, local.properties, and private fixtures out of Git.

## Review focus

1. An imported built-in ghost impersonates the default: it must never open an app screen. Task 2 exercises this, including same IDs and forged typed replies.
2. A delayed tools reply arrives after switching, close, newer dialogue, or pause/resume: it must be discarded. Task 2 tests replacement/session ownership and the action-delivery epoch.
3. Rotation or recomposition repeats delivery: one selection must open one dialog, and consumed actions must not replay. Tasks 2 and 3 test claiming and recreation behavior.
4. An existing installed default still has the original six-message archive: it must gain assistance without any file replacement. Tasks 1 and 4 test APK-owned content on an unchanged install.
5. A release configuration receives a debug action or non-English locale: debug stays unavailable, and help/menu copy remains readable and internally consistent. Tasks 1 and 3 test policy and localization.

## File structure and responsibility

New production files (prefix `app/src/main/kotlin/com/cattailsw/nanidroid/`):
- `engine/BuiltInAppAction.kt`: closed `Ghosts`, `About`, `DebugTools` action enum.
- `engine/BundledAssistantShiori.kt`: default-only handler composed with `BuiltInShiori`; tap routing, named help events, lifecycle introduction, debug policy, typed action replies.
- `ghost/BundledAssistantContent.kt`: bounded asset text loading and lookup using the existing table syntax; localized labels shared with the debug UI.
- `runtime/PresentedAppAction.kt`: action request identity and session/generation ownership.
- `ui/DebugToolsDialog.kt`: the small localized debug dialog, using the same touch-bounds state as the existing Bounds control.
- `app/src/main/assets/assistant/en/content.txt` and `assistant/ja/content.txt`: help and menu scripts/labels. These are distinct from the pinned ZIP.

Modify:
- `engine/ShioriReply.kt`: append optional typed `appAction`, preserving existing constructor use/default behavior.
- `runtime/GhostRuntime.kt`: separate bundled engine factory, trusted bundled-session routing, action admission/claiming, pending request publication and invalidation; suppress the duplicate runtime welcome for the trusted assistant while preserving onboarding-marker bookkeeping.
- `runtime/StageState.kt`: append pending action to Ready with a default null value.
- `ui/StageViewModel.kt`: delegate action claiming to runtime.
- `NanidroidApplication.kt`: inject assistant content and bundled factory; keep ordinary built-in factory table-only.
- `ui/GhostStage.kt`, `MainActivity.kt`: claim actions when resumed, route to existing dialogs and new debug dialog.
- `README.md`: document the enhanced default and debug-only scope.

Tests:
- New `engine/BundledAssistantShioriTest.kt`, `runtime/BundledAssistantActionTest.kt`, `ghost/BundledAssistantContentTest.kt` under `app/src/test/java/com/cattailsw/nanidroid/`.
- New `ui/BundledAssistantUiTest.kt` under `app/src/androidTest/java/com/cattailsw/nanidroid/`.
- Extend existing `engine/BuiltInShioriTest.kt`, `ghost/BundledGhostRepositoryTest.kt`, runtime switch/interaction tests only where the new boundary requires it.

## Task 1: Default-only SHIORI assistance and localized content

**Ownership:** New enum, content loader, assistant handler/assets, `ShioriReply`, engine/content tests. Preserve other edits.

**Interfaces:**
- Append `appAction: BuiltInAppAction? = null` to `ShioriReply`.
- `BundledAssistantContent.load(openAsset: (String) -> InputStream, language: String): BundledAssistantContent` reads APK-owned content with an explicit 64 KiB limit per locale. Provide `script(eventId: String): String?` and `label(key: String): String`; require all authored help/menu/label keys in the chosen file. Use existing first-comma table semantics.
- `BundledAssistantShiori(delegate: ShioriEngine, content: BundledAssistantContent, debugToolsEnabled: Boolean) : ShioriEngine`.
- Menu/topic events: `OnNanidroidHelp`, `OnNanidroidIntro`, `OnNanidroidImportHelp`, `OnNanidroidInteractionHelp`, `OnNanidroidTools`, `OnNanidroidOpenGhosts`, `OnNanidroidOpenAbout`, `OnNanidroidOpenDebug`, `OnNanidroidDismiss`. Use the existing On-prefixed choice mechanism; no ScriptPlayer extension.
- Tools replies return status 200 with null script and the enum action; debug-disabled requests return 204 and no action. Dismiss returns a valid empty terminating script. Invalid speaker or malformed references return 204; unrelated events delegate.

- [ ] Write failing tests named `sakuraTapOpensHelp`, `droidTapOpensTools`, `invalidSpeakerHasNoAction`, `helpTopicsReturnBackChoices`, `toolsReturnTypedActions`, `releaseHidesAndRejectsDebug`, `firstBootExplainsBothRoles`, `unhandledEventsDelegate`, `localeFallbackAndRequiredKeys`, `oversizedContentRejected`. Assert speaker selection from Reference3, expected topic/action IDs, both locale choices, and unchanged ordinary table behavior.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests '*BundledAssistantShioriTest' --tests '*BundledAssistantContentTest' --tests '*BuiltInShioriTest'`; record expected initial failure.
- [ ] Implement the interfaces, authored English/Japanese content, and test fixtures. No arbitrary command interpreting and no random conversation machinery.
- [ ] Run the same tests; require zero failures, then commit the focused files as `feat: add bundled assistant dialogue and typed tools`.

## Task 2: Trusted runtime bridge and existing-install delivery

**Ownership:** Runtime session wiring, `PresentedAppAction`, `StageState`, application injection, ViewModel delegate, runtime/repository tests.

**Interfaces:**
- Add optional constructor parameter `bundledEngineFactory: (BundledGhost) -> ShioriEngine = engineFactory` to GhostRuntime. Ordinary imported built-in ghosts keep `engineFactory`; only `bundledSession(base)` uses the new factory.
- Route an explicitly selected default descriptor through `bundledSession(base)` only when both its directory ID and canonical master path match the app-owned bundled base. Use the base's verified shell location to derive its `ghost/master` path. A matching display name/declaration alone is insufficient. Tests cover startup from saved selection and switching back to default.
- Mark the resulting Session with an explicit immutable bundled-assistant capability; default false. Do not infer capability from an engine name, response text, or incoming event.
- `PresentedAppAction(requestId: Long, sessionId: Long, replacementGeneration: Long, deliveryEpoch: Long, action: BuiltInAppAction)`; monotonically unique IDs within the runtime. Append `appAction: PresentedAppAction? = null` to Ready.
- Keep a runtime-owned monotonically increasing action-delivery epoch. Capture it before starting the validated choice request; increment it on every resumed-to-paused transition and clear published pending requests. Admit the resulting app action only if the runtime is resumed and the captured epoch still matches; claim checks the same epoch. This rejects replies delivered during pause and replies delivered after pause/resume. Invalidation must not clear or cancel ordinary dialogue merely to prevent tool delivery.
- `GhostRuntime.claimAppAction(requestId: Long): BuiltInAppAction?`, exposed by `StageViewModel.claimAppAction(requestId: Long): BuiltInAppAction?`, atomically validates and clears the current pending request on the runtime's main-thread owner. Only active, resumed, trusted sessions with current generation may claim. Clear/reject requests on dialogue replacement, switch, close, and session loss; a paused action must not unexpectedly open on resume.
- Admit typed replies only in the validated choice `requestAction` path, for an active trusted session and one of the exact three tool events whose enum matches. Do not execute actions through `enqueue`, arbitrary script parsing, clock events, boot events, or farewell interactions. Newer selection supersedes an old pending request. Consumed choices cannot repeat an action.
- Application injects ordinary `BuiltInShiori` plus the composed assistant factory and debug policy. Load assistant content from APK assets using the same language choice as the existing default. This applies on every app start, including unchanged existing installs, without modifying BundledGhostRepository installation behavior.
- For the trusted assistant, its localized `OnFirstBoot` response is the sole welcome script: consume the existing onboarding marker where the original activation policy calls for it, but do not queue the runtime `ONBOARDING` constant. Do not reset per-ghost activation or onboarding markers. Preserve the original runtime onboarding behavior for sessions without assistant capability. Cover default startup through both fallback and saved-descriptor selection; an existing activated install receives `OnBoot` rather than a new first-boot welcome.

- [ ] Write failing tests: `defaultOnSavedSelectionGetsAssistance`, `switchBackToDefaultGetsAssistance`, `importedBuiltinCannotOpenTools`, `forgedActionAndMismatchedEventRejected`, `delayedReplyAfterSwitchOrReplacementDiscarded`, `closeAndFarewellCannotOpenTools`, `claimIsSingleUse`, `pauseInvalidatesPendingAction`, `replyDuringPauseIsRejected`, `replyAfterPauseResumeIsRejected`, `freshEnglishInstallHasOneWelcome`, `freshFallbackInstallHasOneJapaneseWelcome`, `existingActivatedInstallGetsReminder`, `existingInstalledDefaultFilesUntouched`. Assert state action is null on forbidden paths; ordinary dialogue survives action-only invalidation; valid requests are claimable exactly once; welcome role explanations appear once with correct locale/marker consumption; original installed files/PNG hashes are identical before/after.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests '*BundledAssistantActionTest' --tests '*BundledGhostRepositoryTest' --tests '*GhostInteractionTest' --tests '*GhostSwitchTest'`; record failing new assertions.
- [ ] Implement the wiring and admission rules without weakening existing interaction tokens or replacement generation handling. Introduce no extra thread or event bus.
- [ ] Run the same tests; require zero failures, then commit as `feat: bridge default ghost choices to app actions`.

## Task 3: UI action handling and debug panel

**Ownership:** `GhostStage`, `MainActivity`, `DebugToolsDialog`, focused UI tests. Reuse existing Ghosts, About, and import flows.

**Interfaces:**
- Append `onClaimAppAction: (Long) -> BuiltInAppAction? = { null }` and localized assistant labels to GhostStage's optional parameters. MainActivity passes the ViewModel delegate and the same localized labels used by the assistant.
- Handle pending request IDs with an effect keyed by request ID; claim before opening a dialog and only while `interactionActive`. Keep modal state scoped to the current session ID and unsaved across activity/process recreation. If consumption wins immediately before recreation, dismiss rather than replay; reopening by tapping the character is acceptable.
- `DebugToolsDialog(showTouchBounds: Boolean, labels: BundledAssistantContent, onShowTouchBoundsChange: (Boolean) -> Unit, onDismiss: () -> Unit)` uses a labeled Switch and Close button. Share the existing showTouchBounds state, so Bounds and the dialog stay in sync.
- Gate debug action execution in the UI as well as the handler using the actual debuggable flag; do not render the dialog or collision overlays in release. Existing top controls remain usable for imported ghosts.
- Dialog Back/dismiss closes the dialog before triggering app shutdown. Ensure MainActivity's existing BackHandler does not consume a dialog dismissal incorrectly. Block character interaction behind modal tools/help-related dialogs consistently with existing modal behavior.

- [ ] Add focused Compose tests named `sakuraTapShowsHelpAndTopicNavigation`, `droidChoicesOpenGhostsAndAbout`, `debugDialogSharesBoundsToggle`, `releasePolicyRejectsDebug`, `oneSelectionOpensOnceAcrossRecomposition`, `rotationDoesNotReplayConsumedAction`, `modalBackDismissesWithoutClosingStage`, `switchDismissesOldTools`. Use real runtime/assistant wiring for at least the tap-to-menu-to-dialog path; do not prove only a fake pre-populated action.
- [ ] Build/run the focused tests on an explicitly named disposable emulator (discover serial and API level first); record initial failing evidence. If unavailable, report instrumentation unrun and continue with compilable tests.
- [ ] Implement the UI interfaces and wiring. Add accessible descriptions/test tags for menus, help pages, and debug controls; keep font scaling and scrolling usable.
- [ ] Rerun focused instrumentation when available, then commit as `feat: add default character help and tools UI`.

## Task 4: Integration evidence and documentation

**Ownership:** README, focused acceptance record at `docs/testing/2026-10-11-default-ghost-assistant.md`, any fixes returned to their responsible implementer.

- [ ] Run `./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease`. Require successful exit codes and record report paths/counts. Do not label release assembly as release readiness.
- [ ] Run the focused connected instrumentation using `ANDROID_SERIAL` set to a discovered disposable emulator. Exercise first boot, subsequent boot, each character's tap/menu, all help topics/back/dismiss, Ghosts/import entry, About/licenses, debug toggle/close, hidden top controls, rotation, and return from another ghost. Test existing installed default files with the new APK; no reinstall that would erase the condition under test. Record emulator/API/APK provenance and any unavailable fixture separately.
- [ ] Confirm release debug exclusion through the debug-disabled policy tests and, if a disposable release-build runtime is available, manual UI evidence. Report exactly which evidence exists; a debug build alone cannot demonstrate release behavior.
- [ ] Record before/after hashes of `nanidroid.zip` and both embedded PNGs and show no tracked native/build config changes. Verify installed-file preservation from Task 2 tests.
- [ ] Update README with the two character roles, language fallback, and initial debug tools scope. Save actual acceptance evidence, failures, and limitations in the dated testing record.
- [ ] Request an independent whole-branch GPT-6-Sol review, resolve actionable findings through the responsible workers, and rerun only affected checks. Commit documentation as `docs: record default assistant behavior and verification`.

## Execution and branch handoff

1. Lead writes and self-reviews this plan.
2. Fresh Astra (`gpt-6-astra`, `fork_turns: none`) reviews this plan read-only, as explicitly requested by the author. Save its findings and resolution alongside the plan; revise before branching.
3. Create `codex/default-ghost-assistant` in the current isolated worktree. Commit the reviewed plan/review record there; if permissions block Git metadata updates, request only the narrowly scoped authorized Git operation.
4. The present request is planning, Astra review, and branch preparation. Product implementation starts after the plan handoff, with sequential GPT-6.1-Sol implementers and independent GPT-6-Sol reviewers per AGENTS.md. No implementation, push, or public PR is part of the planning deliverable.
