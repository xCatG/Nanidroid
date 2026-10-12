# Built-in SHIORI Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Preserve the established sequential implementation/review workflow for PR #442: fresh GPT-6.1-Sol implementers and independent GPT-6-Sol reviewers with isolated context; no concurrent product edits.

**Goal:** Remove assistant-specific engine/content/UI coupling while preserving default help/tools behavior and routing host dialogs through SakuraScript playback.

**Architecture:** Consolidate default behavior and bounded locale loading into named BuiltInShiori construction. ScriptPlayer emits transient command enums; GhostRuntime assigns occurrence IDs and owns one private FIFO. Compose claims the narrow head request when no modal is visible and reads Android resources, never ghost content or SHIORI-specific labels.

**Tech Stack:** Existing Kotlin/Compose/ViewModel/StateFlow/manual injection; JUnit 4, coroutines-test, focused Compose instrumentation. No new dependency.

**Spec:** `docs/superpowers/specs/2026-10-11-built-in-shiori-cleanup-design.md`, approved by the author with “approve, please proceed”. This approval permits planning; this written plan still requires review before implementation.

## Global Constraints

- Work in the current isolated checkout `C:/Users/yenchi/.codex/worktrees/cc61/Nanidroid`, branch `codex/default-ghost-assistant`, PR #442. Report workspace and loaded AGENTS.md first.
- Current source and AGENTS.md govern; do not use superseded application code or search adjacent repositories.
- Keep original PNGs, bundled ZIP, installed files, saved state, activation markers and existing lifecycle behavior. No installation refresh or migration.
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
5. Traditional Chinese is specified by script or region rather than language alone: select Chinese dialogue and localized debug resources consistently on startup. Tasks2/3 locale and device tests.

## Files and responsibility

- Task1 creates `runtime/HostUiCommand.kt` and modifies `runtime/{PlaybackFrame,ScriptPlayer}.kt`. Own `runtime/ScriptPlayerTest.kt` additions under the existing test source root. No parser occurrence DTO is introduced.
- Task2 modifies `engine/{BuiltInShiori,ShioriReply}.kt`, `NanidroidApplication.kt`, `MainActivity.kt`, `runtime/{GhostRuntime,StageState}.kt`, `ui/{StageViewModel,GhostStage,DebugToolsDialog}.kt`; creates `runtime/HostUiRequest.kt`; modifies locale resources and moves dialogue assets to `assets/builtin/{en,ja,zh-TW}/content.txt`.
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
- Keep `BuiltInShiori(content: String)` source-compatible for ordinary table ghosts. Use private mode state (ordinary/default/default-with-developer) set by named construction, not two public boolean flags. Ordinary mode keeps authored event lookup, literal ghost-switch substitution and default-close response.
- Add `BuiltInShiori.bundled(originalContent: String, locale: Locale, openAsset: (String) -> InputStream, debugToolsEnabled: Boolean): BuiltInShiori`. Read at most64KiB plus one sentinel byte for the selected new asset, validate required default scripts, then overlay dialogue on original content through one shared private table parser. No specialized content object escapes the engine boundary.
- Locale priority: explicit Hans → Japanese fallback; English language → en; Chinese Hant or Chinese regionTW/HK/MO → zh-TW; all remaining locales → ja. Use `resources.configuration.locales[0]`, retaining script/country rather than `.language`.
- Default mode preserves current tap/reference validation, help topic events, debug menu visibility and unrelated original lifecycle replies. OnNanidroidOpenGhosts/About/Debug entries now return ordinary scripts containing the three Task1 commands; no typed field in ShioriReply. Direct default debug events are rejected when debug policy is false.
- Expose `internal val developerCommandsEnabled: Boolean` from private mode for host authorization. Determine session developer permission once from verified default provenance and the actual configured BuiltInShiori. Admission uses that private session permission; the UI retains its real Android flag check. This is not a UI callback or text dependency.
- Move existing en/ja scripts to builtin assets; add Traditional Chinese scripts with identical event IDs/choice structure. Remove DebugToolsTitle/ShowTouchBounds/Close table keys. Restore `ShioriReply(status, value, charset)` and delete the wrapper/content/action classes.

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

- [ ] Add/replace focused unit tests in BuiltInShioriTest and new BuiltInDefaultShioriTest/HostUiRequestTest. Test default taps/topics/lifecycle and ordinary-table delegation; en/ja plus zh-TW/zh-Hant/zh-HK/zh-MO and explicit Hans fallback; actual scripts parsed by Task1 player; required script/64KiB rejection; no UI-label keys; stable original installed-file hashes.

```kotlin
val engine = BuiltInShiori.bundled(original, Locale.forLanguageTag("zh-Hant"), openAsset, true)
val reply = engine.request(ShioriEvent("OnNanidroidOpenDebug"))
assertEquals(200, reply.status)
assertEquals(HostUiCommand.Developer, ScriptPlayer(requireNotNull(reply.value)).advanceBy(0).hostCommands.single())
```

- [ ] Add runtime cases for ordinary imported/native Ghosts/About commands, denied imported/release/fake-default developer commands, surrounding text surviving denial, publication without frame changes, ending-frame/natural queue progression, command order/independent IDs/claims, double claim, stale choice/session, explicit replacement/switch/close/farewell, reply during pause/after pause-resume, requests initiated while paused, import-event epoch transport, queued script/wait spanning pause, and normal commands after resume. Replace old blanket boot/touch/import/clock and imported-tools denial assertions with positive common-command cases and developer-specific denial. Use existing native-port seams, not a new private native fixture. Preserve single welcome and activation markers.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests '*BuiltInShioriTest' --tests '*BuiltInDefaultShioriTest' --tests '*HostUiRequestTest' --tests '*BundledGhostRepositoryTest' --tests '*GhostInteractionTest' --tests '*GhostSwitchTest' --offline --console=plain`; record expected red evidence before switching interfaces.
- [ ] Implement the consolidated engine, migrated assets, runtime provenance/FIFO and UI/resource signatures as one compile-complete change. Migrate the existing instrumentation fixture/assertions once, retaining the original nine case identities where valid and updating inventory if identities change. Delete obsolete unit tests only after replacing their behavioral assertions; do not weaken unknown/native script or trust coverage.
- [ ] Run focused unit tests and compile debug/test APKs. Require zero failures, record skips, inspect removed-class references and commit `refactor: decouple built-in dialogue from host UI`. The final full unit gate belongs to Task3; do not repeat it on unchanged code solely for a task boundary.

## Task 3: Full UI flow, multilingual evidence and PR update

**Interfaces:** Consume Task2 generic requests/core factory and Task1 script commands. Adapt the existing real-runtime/MainActivity fixture; no new production test-only dependency seam.

- [ ] Preserve the migrated nine UI cases and add ordinary-ghost Ghosts/About with developer denied, `ghostsThenAboutPresentsAfterDismissal`, `repeatedHostCommandPresentsTwice`, and `hostRequestWaitsForExistingInputModal`. Add localized default menu/debug checks for en-US, ja-JP and zh-TW, setting startup locale before constructing the core and Activity. Keep repository/runtime language strings for imported ghosts unchanged. Restore flags/locale/delegates afterward; register only actually added/changed method identities in strict inventory.
- [ ] Build/run the focused cases on a verified disposable emulator with explicit serial, identity/API/ABI/viewport recorded. Record first failures without retrying them into passing evidence. Fix concrete regressions through the responsible task owner; rerun only changed/failing cases while iterating.
- [ ] Run the final local gate: `./gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --offline --console=plain`. Record unit execution vs cache, counts/skips/lint warnings and APK/source hashes. Check strict inventory with `tools/test-device-suite.ps1 -ListOnly -Suite self-contained` and `-SelfCheck`.
- [ ] Run the final exact focused UI selectors once on the named disposable emulator. Require matching unique terminal identities, zero failures/skips; modeled release policy and actual release-device coverage remain separately stated.
- [ ] Launch the ordinary application factory, verify first/repeat welcome, both tap menus, host dialogs, original installed-data preservation and returned default behavior. Capture and inspect default tools/help and debug dialog screenshots in en-US, ja-JP and zh-TW using a disposable emulator or locale-specific fixture; state the fixture boundary for each image. Keep originals and evidence local/ignored.
- [ ] Verify ZIP/PNG hashes and pinned paths; `rg` must find no production references to BundledAssistantContent, BundledAssistantShiori, BuiltInAppAction, PresentedAppAction, assistantLabels or ShioriReply.appAction. Update README's locale/host-command description and save current evidence in the new acceptance record without rewriting historical outcomes.
- [ ] Obtain independent whole-branch review of the cleanup against the approved spec, address actionable issues, rerun affected checks, and commit `docs: verify built-in SHIORI host-command cleanup`.
- [ ] Push PR #442, update its description to the final script-driven implementation/verification, and post `@codex review`. Stop the test-owned emulator; preserve raw evidence. No Pixel7 deployment or PR merge is included.

## Plan self-review and handoff

Self-review maps every spec section to Tasks1–3: engine/content/UI ownership in
Task2; script command syntax/ordering in Task1; epochs, provenance, FIFO and modal
behavior in Task2; locale/device/media/inventory/PR evidence in Task3. Signatures,
enum values and command targets match across tasks. The five review-focus inputs
have discriminating tests; no placeholder or unrelated feature is included.

**Status:** Written and self-reviewed. Awaiting author review before execution.
**Execution method:** Preserve the established sequential implementer/reviewer
workflow once this written plan is approved.

## Astra review resolution and optional simplification

The [Astra review](../../review/2026-10-11-built-in-shiori-cleanup-astra-review.md)
identified command-only publication and undefined modal/natural-advancement
semantics. This revision explicitly publishes requests without visual changes,
presents FIFO dialogs after dismissal and preserves reached commands across natural
queue advancement. It also removes parser identities/public ownership fields,
uses private named engine modes, migrates instrumentation once and limits full
unit execution to the final gate. The existing pause epoch and one FIFO remain
because they protect real asynchronous/occurrence behavior.

**Optional change awaiting author approval:** make all default dialogue authoritative
in complete APK-owned en/ja/zh-TW tables, including OnGhostChanging, OnGhostChanged,
OnClose, OnInstallFailure and homeurl. Then the named default factory becomes
`BuiltInShiori.bundled(locale: Locale, openAsset: (String) -> InputStream,
debugToolsEnabled: Boolean)` with no originalContent or overlay. Preserve shipped
en/ja lifecycle scripts, literal substitution, installed bytes and imported custom
tables; translate the four lifecycle replies for zh-TW. Manually edited/added
installed-default table entries would no longer affect dialogue. Until approved,
Task2 retains the original-content overlay and its preservation contract.
