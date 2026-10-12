# Built-in SHIORI Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Preserve the established sequential implementation/review workflow for PR #442: fresh GPT-6.1-Sol implementers and independent GPT-6-Sol reviewers with isolated context; no concurrent product edits.

**Goal:** Remove assistant-specific engine/content/UI coupling while preserving default help/tools behavior and routing host dialogs through SakuraScript playback.

**Architecture:** Consolidate default behavior and bounded locale loading into BuiltInShiori. ScriptPlayer emits generic host-command occurrences, and GhostRuntime admits and publishes session-owned, claimable requests. Compose consumes generic host requests and Android string resources; it never reads ghost content or SHIORI-specific labels.

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

1. A host command straddles the incremental parser budget: it must emit exactly once, never as visible text. Task1 parser tests.
2. A reply or queued script spans pause/resume: valid text may continue, but its older host-delivery epoch cannot open a dialog. Task2 runtime tests.
3. Imported scripts contain the developer command while common Ghosts/About commands are supported: only developer is denied; unrelated dialogue remains intact. Task2 trust tests.
4. Multiple host commands occur in one advance: preserve occurrence order and independently claim each once rather than overwriting a single pending action. Task2 queue tests.
5. Traditional Chinese is specified by script or region rather than language alone: select Chinese dialogue and localized debug resources consistently on startup. Tasks2/3 locale and device tests.

## Files and responsibility

- Task1 creates `runtime/HostUiCommand.kt` and modifies `runtime/{ScriptInteraction,PlaybackFrame,ScriptPlayer}.kt`. Own `runtime/ScriptPlayerTest.kt` additions under the existing test source root.
- Task2 modifies `engine/{BuiltInShiori,ShioriReply}.kt`, `NanidroidApplication.kt`, `MainActivity.kt`, `runtime/{GhostRuntime,StageState}.kt`, `ui/{StageViewModel,GhostStage,DebugToolsDialog}.kt`; creates `runtime/PresentedHostUiRequest.kt`; modifies locale resources and moves dialogue assets to `assets/builtin/{en,ja,zh-TW}/content.txt`.
- Task2 removes `engine/{BundledAssistantShiori,BuiltInAppAction}.kt`, `ghost/BundledAssistantContent.kt` and `runtime/PresentedAppAction.kt`; replaces their unit tests with built-in/default/host-request coverage. It also makes the minimum signature/fixture migration in the existing BundledAssistantUiTest so the intermediate test APK compiles, preserving its nine case identities for Task3. No assistant-specific loader or UI-content parameters remain.
- Task3 adapts `app/src/androidTest/java/com/cattailsw/nanidroid/ui/BundledAssistantUiTest.kt` into `BuiltInHostUiTest.kt`, updates `docs/testing/device-suites.json`, README and a new `docs/testing/2026-10-11-built-in-shiori-cleanup.md` acceptance record. Preserve earlier acceptance records as history.
- Production paths above are relative to `app/src/main/kotlin/com/cattailsw/nanidroid/`; unit test paths to `app/src/test/java/com/cattailsw/nanidroid/`. String resources are in `app/src/main/res/values{,-ja,-zh-rTW}/strings.xml`.

## Task 1: Parse host UI requests in normal SakuraScript playback

**Interfaces:**
- `enum class HostUiCommand { GhostExplorer, About, Developer }` in runtime, not engine/UI.
- `data class HostCommandOccurrence(val key: Long, val command: HostUiCommand)` in ScriptInteraction, using the player's existing increasing item-key source.
- Append `hostCommands: List<HostCommandOccurrence> = emptyList()` to PlaybackFrame, preserving existing positional constructors.
- `ScriptPlayer.advanceBy(milliseconds: Long): PlaybackFrame` emits only occurrences reached during this call. Recognize exact `open,ghostexplorer`, `open,configurationdialog,about`, and `open,developer` argument lists; ignore malformed/unknown variants. Preserve existing open/inputbox parsing and its field behavior.
- Thread the call-local occurrence list through applyTag, underscore/deferred parsing, and budget handling. It is transient like animation/surface edges, never retained/re-emitted by a later advance.

- [ ] Add failing `ScriptPlayerTest` cases: `hostTargetsParseExactly`, `unknownHostTargetsAreIgnored`, `hostCommandsRespectWaitsAndInput`, `hostCommandAcrossBudgetEmitsOnce`, `hostOccurrencesPreserveOrderAndUniqueKeys`, `hostCommandsDoNotChangeInputBehavior`. Assert enum values/order, absent commands before waits/input resolve, no duplicate on the next advance, unchanged surrounding text and input keys. Include truncated/extra-argument/oversized tags.

```kotlin
val player = ScriptPlayer("\\![open,ghostexplorer]\\![open,developer]\\e")
val commands = player.advanceBy(0).hostCommands
assertEquals(listOf(HostUiCommand.GhostExplorer, HostUiCommand.Developer), commands.map { it.command })
assertEquals(2, commands.map { it.key }.distinct().size)
assertTrue(player.advanceBy(0).hostCommands.isEmpty())
```

- [ ] Run `./gradlew.bat testDebugUnitTest --tests '*ScriptPlayerTest' --offline --console=plain`; record the intended missing-interface/assertion failure.
- [ ] Implement the interfaces and parser routing. Keep existing source-character budget and deferred-command behavior; do not add a general command execution framework.
- [ ] Run the same focused tests; require zero failures. Review the diff and commit `refactor: parse host dialogs through SakuraScript`.

## Task 2: Consolidate the built-in engine and replace the assistant bridge

**Engine/content interfaces:**
- Keep `BuiltInShiori(content: String)` source-compatible by adding defaulted `bundledDefault: Boolean = false` and `debugToolsEnabled: Boolean = false`. Ordinary table mode keeps authored event lookup, literal ghost-switch substitution and default-close response.
- Add `BuiltInShiori.bundled(originalContent: String, locale: Locale, openAsset: (String) -> InputStream, debugToolsEnabled: Boolean): BuiltInShiori`. Read at most64KiB plus one sentinel byte for the selected new asset, validate required default scripts, then overlay dialogue on original content through one shared private table parser. No specialized content object escapes the engine boundary.
- Locale priority: explicit Hans → Japanese fallback; English language → en; Chinese Hant or Chinese regionTW/HK/MO → zh-TW; all remaining locales → ja. Use `resources.configuration.locales[0]`, retaining script/country rather than `.language`.
- Default mode preserves current tap/reference validation, help topic events, debug menu visibility and unrelated original lifecycle replies. OnNanidroidOpenGhosts/About/Debug entries now return ordinary scripts containing the three Task1 commands; no typed field in ShioriReply. Direct default debug events are rejected when debug policy is false.
- Expose `internal val developerCommandsEnabled: Boolean` computed from bundledDefault/debugToolsEnabled, for host authorization. This is not a UI callback or text dependency.
- Move existing en/ja scripts to builtin assets; add Traditional Chinese scripts with identical event IDs/choice structure. Remove DebugToolsTitle/ShowTouchBounds/Close table keys. Restore `ShioriReply(status, value, charset)` and delete the wrapper/content/action classes.

**Runtime/UI interfaces:**
- `PresentedHostUiRequest(requestId: Long, sessionId: Long, dialogueId: Long, replacementGeneration: Long, deliveryEpoch: Long, command: HostUiCommand)` in runtime.
- Append `hostUiRequest: PresentedHostUiRequest? = null` to Ready; remove `appAction`. Publish the first pending request from a session-owned FIFO.
- `GhostRuntime.claimHostUiRequest(requestId: Long): HostUiCommand?`, delegated by StageViewModel. Validate and consume on the main-thread owner; stale IDs cannot consume another request. Drop invalid heads and publish the next valid one. IDs are monotonically unique across sessions.
- Task1 occurrences enter the FIFO from `acceptPlayback`, never by inspecting choice IDs or SHIORI reply fields. Clear `hostCommands` from the stored frame like other transient effects. Preserve authored ordering and each occurrence's dialogue/generation provenance.
- Rename bundledAssistant session capability to isBundledDefault and retain verified directory/canonical path selection. Common commands need an active/resumed current session; Developer additionally requires real BuiltInShiori.developerCommandsEnabled and default provenance. Keep generic/specialized factory distinction only to configure the actual default; no wrapper factory.
- Extend QueuedDialogue and current-player metadata with the request-time host delivery epoch. Capture the epoch before every awaited engine request that can enqueue a script (activation, pointer/choice/input, clock, surface and lifecycle paths). Carry it through queue/coalescing/playback. On pause, advance the epoch and clear pending requests without clearing ordinary dialogue. Old reply/queued-script commands are ignored after resume; new replies use the new epoch.
- Reuse existing replacement/dialogue/session checks and invalidate host FIFO on dialogue replacement, switch, close or session loss. Do not allow host dialogs in farewell/closing/switch phases.
- GhostStage takes `onClaimHostUiRequest: (Long) -> HostUiCommand? = { null }`, claims once while active, and dispatches generic commands to existing dialogs. Keep unsaved session-scoped modal state, Back and modal interaction blocking. Remove assistantLabels and the content injection in MainActivity.
- DebugToolsDialog removes its labels argument and uses `stringResource` keys `debug_tools_title`, `debug_show_touch_bounds`, `action_close` for visible and accessibility text. Supply en/ja/zh-rTW values; verify Android's Hant/HK/MO resource selection also finds these Traditional Chinese values, adding a shared Hant resource qualifier if the target framework needs it. Reuse the same Bounds state and actual UI FLAG_DEBUGGABLE guard. Other dialog translations are not part of this task. Unsupported dialogue locales retain Japanese fallback while other host UI retains normal Android resource fallback.

- [ ] Add/replace focused unit tests in BuiltInShioriTest and new BuiltInDefaultShioriTest/HostUiRequestTest. Test default taps/topics/lifecycle and ordinary-table delegation; en/ja plus zh-TW/zh-Hant/zh-HK/zh-MO and explicit Hans fallback; actual scripts parsed by Task1 player; required script/64KiB rejection; no UI-label keys; stable original installed-file hashes.

```kotlin
val engine = BuiltInShiori.bundled(original, Locale.forLanguageTag("zh-Hant"), openAsset, true)
val reply = engine.request(ShioriEvent("OnNanidroidOpenDebug"))
assertEquals(200, reply.status)
assertEquals(HostUiCommand.Developer, ScriptPlayer(requireNotNull(reply.value)).advanceBy(0).hostCommands.single().command)
```

- [ ] Add runtime cases for ordinary imported/native Ghosts/About commands, denied imported/release/fake-default developer commands, surrounding text surviving denial, command order/independent claims, double claim, stale choice/dialogue/session, replacement/switch/close/farewell, reply during pause/after pause-resume, queued script/wait spanning pause, and normal commands after resume. Preserve single localized welcome and activation markers using existing real-bundle fixtures.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests '*BuiltInShioriTest' --tests '*BuiltInDefaultShioriTest' --tests '*HostUiRequestTest' --tests '*BundledGhostRepositoryTest' --tests '*GhostInteractionTest' --tests '*GhostSwitchTest' --offline --console=plain`; record expected red evidence before switching interfaces.
- [ ] Implement the consolidated engine, migrated assets, runtime provenance/FIFO and UI/resource signatures as one compile-complete change. Update existing instrumentation fixture constructors/delegate injection and removed-field references enough to compile with the new contract, keeping the original nine method identities until Task3. Delete obsolete unit tests only after replacing their behavioral assertions; do not weaken unknown/native script or trust coverage.
- [ ] Run focused tests, then the full `testDebugUnitTest` once; compile the debug/test APKs to catch obsolete instrumentation signatures. Require zero failures, record existing skips, inspect all removed-class references and commit `refactor: decouple built-in dialogue from host UI`.

## Task 3: Full UI flow, multilingual evidence and PR update

**Interfaces:** Consume Task2 generic requests/core factory and Task1 script commands. Adapt the existing real-runtime/MainActivity fixture; no new production test-only dependency seam.

- [ ] Replace the old nine assistant UI cases with BuiltInHostUiTest cases preserving their tap/help/dialog/recomposition/rotation/Back/switch/inactive/debug assertions. Add an ordinary ghost script case for Ghosts/About with developer denied. Add localized default menu/debug checks for en-US, ja-JP and zh-TW, setting the fixture's startup locale before constructing the core and Activity. Restore any test flags/locale/delegates afterward; classify every renamed/new method in the strict device inventory.
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
