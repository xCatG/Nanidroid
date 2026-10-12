# Built-in SHIORI Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Preserve the established sequential implementation/review workflow for PR #442: fresh GPT-6.1-Sol implementers and independent GPT-6-Sol reviewers with isolated context; no concurrent product edits.

**Goal:** Remove assistant-specific engine/content/UI coupling while preserving default help/tools behavior and routing host dialogs through SakuraScript playback.

**Architecture:** Keep default behavior in the bundled ZIP dictionaries; use generic BuiltInShiori table selectors and bounded locale-aware repository loading. ScriptPlayer emits transient command enums; GhostRuntime assigns occurrence IDs and owns one private FIFO. Compose claims the narrow head request when no modal is visible and reads Android resources, never ghost content or SHIORI-specific labels.

**Tech Stack:** Existing Kotlin/Compose/ViewModel/StateFlow/manual injection; JUnit 4, coroutines-test, focused Compose instrumentation. No new dependency.

**Spec:** `docs/superpowers/specs/2026-10-11-built-in-shiori-cleanup-design.md`, approved by the author with “approve, please proceed”. This approval permits planning; this written plan still requires review before implementation.

## Global Constraints

- Work in the current isolated checkout `C:/Users/yenchi/.codex/worktrees/cc61/Nanidroid`, branch `codex/default-ghost-assistant`, PR #442. Report workspace and loaded AGENTS.md first.
- Current source and AGENTS.md govern; do not use superseded application code or search adjacent repositories.
- Preserve original PNG bytes, saved state, activation markers, customized dictionaries and unknown installed files. Update the bundled ZIP and its pinned hash deliberately. Refresh only recognized shipped dictionaries; no legacy-data migration or general NAR updater.
- One app module, existing Kotlin/Compose conventions and pinned toolchain; namespace/application ID `com.cattailsw.nanidroid`; minSdk31, compile/target37; arm64-v8a and x86_64. No native/build configuration changes are needed.
- Preserve taps/help/tools menus, first welcome/reminder, safe choice/input behavior, modal Back, session-scoped dialogs and exactly-once delivery.
- Ghosts/About commands are available to any active ghost. Developer commands require the verified built-in default session, a real BuiltInShiori configured for that default, debug policy and the actual UI debuggable flag.
- English, Japanese and Traditional Chinese dialogue/debug resources ship together. Locale changes apply on restart; no runtime locale-switch feature.
- Translation of unrelated pre-existing import/input/About chrome is outside this cleanup; original license notices retain their contents.
- Use only explicitly identified disposable emulators. Do not install another build on Pixel 7 unless requested.
- Keep outputs/APKs/logs/screenshots/secrets/private fixtures outside Git. Update PR #442 after verification and review, then post `@codex review`; no PR merge, release, signing-key work or publication.

## Review Focus

1. A command after a wait changes no text/visual frame: publish it immediately; a denied-only batch creates no request. Task2 runtime tests. Task1 also pins incremental parser boundaries.
2. A reply or queued script spans pause/resume: valid text may continue, but its older host-delivery epoch cannot open a dialog. Task2 runtime tests.
3. Imported scripts contain the developer command while common Ghosts/About commands are supported: only developer is denied; unrelated dialogue remains intact. Task2 trust tests.
4. Multiple commands and natural script advancement occur before claiming: preserve commands in the replacement generation and present each after the preceding modal closes. Tasks2/3 queue/ending-frame/modal tests.
5. Existing bundled install is old, customized or interrupted during refresh: update only recognized dictionary bytes, preserve all other files and retry safely. Task2 installer tests.
6. Traditional Chinese is specified by script or region rather than language alone: select Chinese dialogue and localized debug resources consistently on startup. Tasks2/3 locale and device tests.

## Files and responsibility

- Task1 creates `runtime/HostUiCommand.kt` and modifies `runtime/{PlaybackFrame,ScriptPlayer}.kt`. Own `runtime/ScriptPlayerTest.kt` additions under the existing test source root. No parser occurrence DTO is introduced.
- Task2 modifies `engine/{BuiltInShiori,ShioriReply}.kt`, `NanidroidApplication.kt`, `MainActivity.kt`, `runtime/{GhostRuntime,StageState}.kt`, `ui/{StageViewModel,GhostStage,DebugToolsDialog}.kt`; creates `runtime/HostUiRequest.kt`; modifies locale resources and packages complete dialogue tables in `assets/nanidroid.zip` under `ghost/master/{en,ja,zh-TW}/content.txt`; modifies `ghost/BundledGhostRepository.kt` and its tests for locale selection and managed-file refresh. Update `app/src/test/resources/nanidroid.zip` to match the production archive byte-for-byte; save only the old en/ja dictionary bytes under `app/src/test/resources/bundled-original/{en,ja}/content.txt` for refresh fixtures. Remove separate `assets/assistant/` tables.
- Task2 removes `engine/{BundledAssistantShiori,BuiltInAppAction}.kt`, `ghost/BundledAssistantContent.kt` and `runtime/PresentedAppAction.kt`; replaces their unit tests with built-in/default/host-request coverage. Migrate the existing BundledAssistantUiTest fixture/assertions once with the API change, preserving its nine behavioral cases and identities where valid. Update strict inventory in the same change if any identity changes. No assistant-specific loader or UI-content parameters remain.
- Task3 adds multiple-modal, ordinary-ghost and locale cases to `app/src/androidTest/java/com/cattailsw/nanidroid/ui/BundledAssistantUiTest.kt`; updates `docs/testing/device-suites.json`, README and a new `docs/testing/2026-10-11-built-in-shiori-cleanup.md` acceptance record. No class rename is needed to remove production coupling. Preserve earlier acceptance records as history.
- Production paths above are relative to `app/src/main/kotlin/com/cattailsw/nanidroid/`; unit test paths to `app/src/test/java/com/cattailsw/nanidroid/`. String resources are in `app/src/main/res/values{,-ja,-zh-rTW}/strings.xml`.

## Task 1: Parse host UI requests in normal SakuraScript playback

**Interfaces:**
- `enum class HostUiCommand { GhostExplorer, About, Developer }` in runtime, not engine/UI.
- Append `hostCommands: List<HostUiCommand> = emptyList()` to PlaybackFrame, preserving existing positional constructors. Equal enum values remain distinct ordered list elements; only runtime admission assigns IDs.
- `ScriptPlayer.advanceBy(milliseconds: Long): PlaybackFrame` emits only occurrences reached during this call. Recognize exact `open,ghostexplorer`, `open,configurationdialog,about`, and `open,developer` argument lists; ignore malformed/unknown variants. Preserve existing open/inputbox parsing and its field behavior.
- Thread the call-local occurrence list through applyTag, underscore/deferred parsing, and budget handling. It is transient like animation/surface edges, never retained/re-emitted by a later advance.

- [ ] Add failing `ScriptPlayerTest` cases: `hostTargetsParseExactly`, `unknownHostTargetsAreIgnored`, `hostCommandsRespectWaitsAndInput`, `hostCommandAcrossBudgetEmitsOnce`, `hostCommandsPreserveDuplicatesAndOrder`, `hostCommandsDoNotChangeInputBehavior`. Assert enum values/order, absent commands before waits/input resolve, no re-emission on the next advance, unchanged surrounding text and input keys. Include truncated/extra-argument/oversized tags.

```kotlin
val player = ScriptPlayer("\\![open,ghostexplorer]\\![open,developer]\\![open,ghostexplorer]\\e")
val commands = player.advanceBy(0).hostCommands
assertEquals(listOf(HostUiCommand.GhostExplorer, HostUiCommand.Developer, HostUiCommand.GhostExplorer), commands)
assertTrue(player.advanceBy(0).hostCommands.isEmpty())
```

- [ ] Run `./gradlew.bat testDebugUnitTest --tests '*ScriptPlayerTest' --offline --console=plain`; record the intended missing-interface/assertion failure.
- [ ] Implement the interfaces and parser routing. Keep existing source-character budget and deferred-command behavior; do not add a general command execution framework.
- [ ] Run the same focused tests; require zero failures. Review the diff and commit `refactor: parse host dialogs through SakuraScript`.

## Task 2: Consolidate the built-in engine and replace the assistant bridge

**Engine/content interfaces:**
- Keep `BuiltInShiori(content: String)` source-compatible. Add named `BuiltInShiori.bundled(content: String, debugToolsEnabled: Boolean): BuiltInShiori` with private mode state and `internal val developerCommandsEnabled: Boolean`. No Locale, asset reader, assistant IDs, menu construction or dialogue overlay enters the engine.
- Both construction paths use the same table parser and generic selector lookup: `Event@r3=N@debug`, `Event@r3=N`, `Event@debug`, `Event`, in that priority, where N is canonical nonnegative Int decimal Reference3. Missing/malformed Reference3 skips its variants. For OnMouseClick/OnMouseDoubleClick/OnMouseMove selector eligibility, require Int References0–2; leave plain-event lookup compatible. Default content contains no plain OnMouseClick fallback, giving204 for malformed payloads. Debug variants are eligible only in the trusted debug mode; ordinary/imported construction remains event-only unless it authors reference variants. Recognized selector rows are reserved and stored separately; direct event IDs equal to selector keys return204 rather than accessing variant scripts. Unknown keys outside that grammar remain literal event keys. Preserve first-comma syntax, literal switch-name substitution and the ordinary constructor's default-close response. Named bundled construction returns204 for missing OnClose so customized archive content remains authoritative. Do not implement a general condition language.
- Package complete en/ja/zh-TW dictionaries in the bundled ZIP. `OnMouseClick@r3=0` contains the help script; `OnMouseClick@r3=1` contains normal tools; its `@debug` variant additionally offers the developer choice. Topic/lifecycle/choice scripts are ordinary authored event entries. Choice responses contain Task1 host commands; release does not offer debug and runtime denies direct developer scripts. Preserve exact current tap eligibility through normal pointer dispatch, testing primary/secondary and malformed requests against current behavior.
- `BundledGhostRepository.load(root: File, locale: Locale): BundledGhost` selects installed content using the full startup locale; retain `load(root, language: String)` compatibility. Priority: explicit Hans -> ja; English -> en; Chinese Hant or Chinese TW/HK/MO -> zh-TW; otherwise ja. Missing selected dictionary falls back to ja as today. Bound every selected/fallback content read to64KiB plus one byte. Shipped required entries are validated in archive validation/tests; customized tables are not forced to contain assistant keys.
- Extend `ensureInstalled(): File` only for managed dictionary refresh. Before refresh, verify install.txt type/directory and descript.txt id/shiori against shipped `nanidroid`/`Nanidroid` identity and existing built-in engine classification, reading only confined non-linked regular manifest/descriptor files; a same-named native/replaced target receives no dictionary writes and follows existing load/error behavior. Pin the new archive SHA; record the previous shipped en/ja dictionary hashes from the current committed archive. Verify the archive before reading replacement bytes. Existing file matching previous shipped hash -> atomically replace; matching new hash -> no-op; other existing bytes -> preserve. Absent en/ja/zh-TW -> add; custom zh-TW -> preserve. Operate only on these three fixed relative paths, checking canonical containment and non-following file attributes for root/parents/file, rejecting links and requiring regular existing files. Hash existing dictionaries with bounded-memory streaming; extract replacement dictionaries once from the verified ZIP. Exclusively create a unique same-directory temporary file per target, recheck target before atomic replace, clean up only that exact created file. Do not sweep prefix-matching files; orphan temporaries from interruption are ignored. No version marker, directory replacement or host content overlay. Interrupted multi-file refresh retries idempotently; mixed old/new locales remain valid. Never fall back to in-place writing if atomic publication fails. This proves process-interruption retry, not power-loss durability. Propagate real I/O failures through the existing startup error path rather than claiming success.
- Update archive metadata/hash assertions and current README/provenance claims that say the archive is unchanged. Keep historical acceptance records untouched and verify both PNG SHA256 values remain identical. Preserve all nonmanaged installed bytes and existing activation markers. Remove runtime ONBOARDING constant/queue insertion; OnFirstBoot archive content owns welcome and markers retain their existing meaning. Customized old tables may lack new interactions; that is intentional preservation, not a fallback to APK behavior.
- Remove DebugToolsTitle/ShowTouchBounds/Close table keys. Restore `ShioriReply(status, value, charset)` and delete wrapper/content/action classes and external assistant assets. Only Android debug dialog resources remain APK-owned.

**Runtime/UI interfaces:**
- `data class HostUiRequest(val id: Long, val command: HostUiCommand)` in runtime. Append `hostUiRequest: HostUiRequest? = null` to Ready; remove `appAction`. Publish the first pending request from one session-owned FIFO. Keep generation/epoch/provenance private; session ownership is implicit in the queue.
- `GhostRuntime.claimHostUiRequest(requestId: Long): HostUiCommand?`, delegated by StageViewModel. Validate and consume on the main-thread owner; stale IDs cannot consume another request. Drop invalid heads and publish the next valid one. IDs are monotonically unique across sessions.
- Task1 command enums enter the FIFO from `acceptPlayback`, never by inspecting choice IDs or SHIORI reply fields. Assign IDs only there. Clear `hostCommands` from the stored frame like other transient effects. Preserve commands reached in the same replacement generation across natural `playNext` advancement; a current dialogue-ID equality check must not cancel an ending-frame command. Explicit replacement still clears the queue.
- Publish whenever admitted requests change the visible FIFO head, even if the retained playback frame is identical. Add `commandAfterWaitPublishesWithUnchangedFrame`, `deniedBatchCreatesNoRequest`, and `endingFrameCommandSurvivesNextQueuedScript` tests, including no unrelated frame update to hide a missing publication.
- Rename bundledAssistant session capability to isBundledDefault and retain verified directory/canonical path selection. Common commands need an active/resumed current session; Developer additionally requires real BuiltInShiori.developerCommandsEnabled and default provenance. Keep generic/specialized factory distinction only to configure the actual default; no wrapper factory.
- Extend QueuedDialogue and current-player metadata with the request-time host delivery epoch. Capture it before every awaited engine request that can enqueue a script, including `beginImportEvents`, activation, pointer/choice/input, clock, surface and lifecycle paths. Centralize capture in the current helper where possible without a request-framework rewrite. A request initiated while paused gets no host-delivery permission, even if its reply arrives after resume; its valid text remains eligible under existing reply rules. Carry metadata through queue/coalescing/playback. On pause, advance the epoch and clear pending requests without clearing ordinary dialogue. Old reply/queued-script commands are ignored after resume; new resumed requests use the new epoch.
- Reuse existing replacement/dialogue/session checks and invalidate host FIFO on dialogue replacement, switch, close or session loss. Do not allow host dialogs in farewell/closing/switch phases.
- GhostStage takes `onClaimHostUiRequest: (Long) -> HostUiCommand? = { null }`, claims once while active and no host/input/import/switch modal is visible, then opens the corresponding existing dialog. Key its effect on head ID, interaction activity and modal availability so closing the first dialog presents the next. Use no second UI queue or completion protocol. Keep unsaved session-scoped modal state, Back and interaction blocking. Remove assistantLabels and the content injection in MainActivity.
- DebugToolsDialog removes its labels argument and uses `stringResource` keys `debug_tools_title`, `debug_show_touch_bounds`, `action_close` for visible and accessibility text. Supply en/ja/zh-rTW values; verify Android's Hant/HK/MO resource selection also finds these Traditional Chinese values, adding a shared Hant resource qualifier if the target framework needs it. Reuse the same Bounds state and actual UI FLAG_DEBUGGABLE guard. Other dialog translations are not part of this task. Unsupported dialogue locales retain Japanese fallback while other host UI retains normal Android resource fallback.

- [ ] Add/replace focused unit tests in BuiltInShioriTest, new BuiltInDefaultShioriTest/HostUiRequestTest and BundledGhostRepositoryTest. Pin selector precedence, canonical numeral boundaries (01/sign/whitespace/overflow denied as selectors), unknown literal OnCustom@something, malformed/missing references, authored speaker roles swapped without Kotlin changes, direct selector IDs denied in both modes, malformed mouse payloads denied for selectors while ordinary-table lookup stays compatible, debug variant visibility from both tap and welcome/help choice routes, literal lifecycle substitution, bundled missing-OnClose returns204 and ordinary fallback remains compatible; assert no runtime-authored welcome. Test en/ja/zh-TW/Hant/HK/MO/Hans and bounded reads including fallback. Parse actual archived menu-choice scripts with Task1 player; assert no UI-label keys or external assistant loader remains.
- [ ] Add installer tests: fresh full archive; wrong install identity, partial/unrelated directory and same-named native target unchanged; old known dictionaries refreshed; repeat no-op; customized en/ja/zh preserved; missing files added; unknown/native/shell/PNG/activation bytes and prefix-matching user files untouched; wrong ZIP hash rejected; managed symlinks/escaped parents denied; simulated interrupted refresh retries without data loss. Derive previous hashes from the current committed archive before replacement and pin the extracted old dictionary fixtures; do not leave the main test-resource ZIP stale. Assert test-resource ZIP equals the updated production archive byte-for-byte. Assert failures do not turn into apparent success and customized entries remain authoritative after engine creation.

```kotlin
val engine = BuiltInShiori.bundled("OnMouseClick@r3=1,tools\\e\nOnMouseClick@r3=1@debug,debug-tools\\e", true)
// Request Reference3=1: debug-tools. With debugToolsEnabled=false: tools.
// Changing dictionary speaker keys changes tap behavior without host edits.
```

- [ ] Add runtime cases for ordinary imported/native Ghosts/About commands, denied imported/release/fake-default developer commands, surrounding text surviving denial, publication without frame changes, ending-frame/natural queue progression, command order/independent IDs/claims, double claim, stale choice/session, explicit replacement/switch/close/farewell, reply during pause/after pause-resume, requests initiated while paused, import-event epoch transport, queued script/wait spanning pause, and normal commands after resume. Replace old blanket boot/touch/import/clock and imported-tools denial assertions with positive common-command cases and developer-specific denial. Use existing native-port seams, not a new private native fixture. Preserve single welcome and activation markers.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests '*BuiltInShioriTest' --tests '*BuiltInDefaultShioriTest' --tests '*HostUiRequestTest' --tests '*BundledGhostRepositoryTest' --tests '*GhostInteractionTest' --tests '*GhostSwitchTest' --offline --console=plain`; record expected red evidence before switching interfaces.
- [ ] Implement the generic engine, archive dictionaries and managed refresh, runtime provenance/FIFO and UI/resource signatures as one compile-complete change. Migrate the existing instrumentation fixture/assertions once, retaining the original nine case identities where valid and updating inventory if identities change. Delete obsolete unit tests only after replacing their behavioral assertions; do not weaken unknown/native script or trust coverage.
- [ ] Run focused unit tests and compile debug/test APKs. Require zero failures, record skips, inspect removed-class references and commit `refactor: decouple built-in dialogue from host UI`. The final full unit gate belongs to Task3; do not repeat it on unchanged code solely for a task boundary.

## Task 3: Full UI flow, multilingual evidence and PR update

**Interfaces:** Consume Task2 generic requests/core factory and Task1 script commands. Adapt the existing real-runtime/MainActivity fixture; no new production test-only dependency seam.

- [ ] Preserve the migrated nine UI cases and add ordinary-ghost Ghosts/About with developer denied, `ghostsThenAboutPresentsAfterDismissal`, `repeatedHostCommandPresentsTwice`, and `hostRequestWaitsForExistingInputModal`. Add localized default menu/debug checks for en-US, ja-JP and zh-TW, setting startup locale before constructing the core and Activity. Keep repository/runtime language strings for imported ghosts unchanged. Restore flags/locale/delegates afterward; register only actually added/changed method identities in strict inventory.
- [ ] Build/run the focused cases on a verified disposable emulator with explicit serial, identity/API/ABI/viewport recorded. Record first failures without retrying them into passing evidence. Fix concrete regressions through the responsible task owner; rerun only changed/failing cases while iterating.
- [ ] Run the final local gate: `./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --offline --console=plain`. Record unit execution vs cache, counts/skips/lint warnings and APK/source hashes. Check strict inventory with `tools/test-device-suite.ps1 -ListOnly -Suite self-contained` and `-SelfCheck`.
- [ ] Run the final exact focused UI selectors once on the named disposable emulator. Require matching unique terminal identities, zero failures/skips; modeled release policy and actual release-device coverage remain separately stated.
- [ ] Launch the ordinary application factory, verify first/repeat welcome, both tap menus, host dialogs, recognized dictionary upgrade and customized/nonmanaged data preservation and returned default behavior. Capture and inspect default tools/help and debug dialog screenshots in en-US, ja-JP and zh-TW using a disposable emulator or locale-specific fixture; state the fixture boundary for each image. Keep originals and evidence local/ignored.
- [ ] Verify the new ZIP hash, unchanged PNG hashes and pinned paths; `rg` must find no production references to BundledAssistantContent, BundledAssistantShiori, BuiltInAppAction, PresentedAppAction, assistantLabels or ShioriReply.appAction. Update README's locale/host-command description and save current evidence in the new acceptance record without rewriting historical outcomes.
- [ ] Obtain independent whole-branch review of the cleanup against the approved spec, address actionable issues, rerun affected checks, and commit `docs: verify built-in SHIORI host-command cleanup`.
- [ ] Push PR #442, update its description to the final script-driven implementation/verification, and post `@codex review`. Stop the test-owned emulator; preserve raw evidence. No Pixel7 deployment or PR merge is included.

## Plan self-review and handoff

Self-review maps every spec section to Tasks1–3: engine/content/UI ownership in
Task2; script command syntax/ordering in Task1; epochs, provenance, FIFO and modal
behavior in Task2; locale/device/media/inventory/PR evidence in Task3. Signatures,
enum values and command targets match across tasks. The six review-focus inputs
have discriminating tests; no placeholder or unrelated feature is included.

**Status:** Written and self-reviewed. Awaiting author review before execution.
**Execution method:** Preserve the established sequential implementer/reviewer
workflow once this written plan is approved.

## Astra review resolution

The [Astra review](../../review/2026-10-11-built-in-shiori-cleanup-astra-review.md)
identified command-only publication and undefined modal/natural-advancement
semantics. This revision explicitly publishes requests without visual changes,
presents FIFO dialogs after dismissal and preserves reached commands across natural
queue advancement. It also removes parser identities/public ownership fields,
uses private named engine modes, migrates instrumentation once and limits full
unit execution to the final gate. The existing pause epoch and one FIFO remain
because they protect real asynchronous/occurrence behavior.

**Archive ownership revision (2026-10-12):** The author's preference supersedes
the optional APK-table proposal. Ghost dialogue, tap mapping, menu visibility and
choice behavior stay in ZIP/NAR files. Task2 now loads authoritative installed
dictionaries and refreshes only known shipped files. Generic selector lookup and
host-command playback remain small host capabilities; no assistant content layer
or default-role dispatcher is retained. The
[second Astra review](../../review/2026-10-12-built-in-shiori-archive-review.md)
accepts this boundary. Its selector, install identity, authored welcome/close,
exclusive temporary-file and test-archive findings are incorporated above.
