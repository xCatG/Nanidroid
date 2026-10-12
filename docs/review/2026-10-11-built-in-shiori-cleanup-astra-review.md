# Built-in SHIORI cleanup: independent simplification review

Reviewed 2026-10-11 at `237f35c1`, branch `codex/default-ghost-assistant`.
Workspace: `C:/Users/yenchi/.codex/worktrees/cc61/Nanidroid`.
Loaded project instructions: the workspace root `AGENTS.md`, plus the instructions supplied with the task. No nested AGENTS.md was found in the file inventory.

This is a review of the approved design and proposed implementation plan against current source. No product, plan, Git, or installed-data changes were made. This report is the only written artifact. Skills applied: simplify and engineering-testing:reversible-design. No tests were run: the proposed implementation does not exist yet.

## Recommendation

Revise the plan before execution. Its direction is sound: merge the default behavior into BuiltInShiori, return ordinary scripts, recognize three host commands in ScriptPlayer, and give Compose Android resources. Keep the existing session/pause protections. The plan can be materially smaller by removing the parser occurrence DTO, limiting the public request to ID/command, using named engine construction instead of public mode flags, and migrating existing tests once.

The principal correctness gap is publication of a command-only playback change. The principal unresolved behavior is what multiple modal commands mean to the user. Neither is solved by adding more identity fields.

## Findings, ranked by severity

### P1 — Explicitly publish host requests even when the retained frame is unchanged

Plan Task 2 says to consume host commands in `acceptPlayback` and clear them from the stored frame. However, `GhostRuntime.kt:1042` publishes a ticker result only when `session.frame != before` or ghost-selection eligibility changes. A script such as `\_w[500]\![open,ghostexplorer]\_w[5000]\e` can reach its host command without changing any retained frame field. The new FIFO head would then remain invisible to StateFlow until an unrelated frame change, possibly for seconds. An initial-command test that goes through `enqueue`/`publish` would miss this.

Make host-request change part of the ticker's publication decision, or publish explicitly when accepting an admitted host command. Keep the change local; no new event bus is needed. Add one runtime test where a command after a wait becomes claimable immediately while the retained visual frame is identical. Also check that an all-denied command batch does not manufacture a request.

### P2 — FIFO claiming does not define presentation order or modal arbitration

Plan Task 2 publishes the next FIFO head immediately on claim. The current `GhostStage.kt:223-235` effect claims whenever active and sets one dialog true while clearing the others. Translating that effect literally means two commands from one advance can claim in order but immediately replace the first dialog with the second. Two identical commands can be claimed while producing only one visible opening. `modalVisible` currently blocks character interaction, not the claiming effect; an input box or import result can also already be present.

This is an underspecified new behavior, rather than proof that every FIFO design is wrong. Define it before implementing queue/UI tests. If the intended contract is that each requested dialog is actually presented, retain a small runtime FIFO and let the existing UI defer its next claim while a modal is visible; key the effect on the modal becoming available as well as the request ID. Do not add a second UI queue, completion protocol, or general modal scheduler. Include an end-to-end `Ghosts → About` case and a repeated-command case.

Alternatively, documenting immediate replacement is smaller and consistent with the existing setters, but it weakens the likely meaning of independently executing each occurrence. Treat that choice as a substantive clarification of the approved multiple-command contract, not a silent simplification. Dropping all but the last command is a substantive scope change and is not recommended under the current spec.

### P2 — Define natural dialogue progression separately from interruption before adding another ownership check

`playNext` increments `dialogueVersion` for every queued script (`GhostRuntime.kt:1120-1127`), whereas `clearDialogue` increments `replacementGeneration` and removes queued work on interruption (`:1091-1104`). A ticker can accept an ending frame, start the next script, and only then publish. If the proposed request's `dialogueId` must equal the currently displayed dialogue, a command legitimately reached in that ending frame can become invalid before the UI has any chance to claim it.

The plan says to preserve every occurrence and also to reuse dialogue ownership checks, without specifying this distinction. State explicitly whether natural queue advancement cancels an unclaimed host command. Recommended: preserve commands reached in the current replacement generation across natural queue advancement; clear them on explicit replacement, pause, switch, and close. Retain the origin dialogue token privately for provenance if useful, but do not automatically apply the choice-click validity predicate to a host effect already reached by playback. Add a test where a command is reached in an ending frame with another script queued. This is a behavior clarification to record in the design before implementation if “replaced dialogue” was intended to include natural advancement.

### P3 — Two identities and six public ownership fields are unnecessary

Task 1 introduces `HostCommandOccurrence(key, command)` using the player's item-key counter. Task 2 then allocates a distinct globally increasing request ID and exposes session, dialogue, generation and epoch through `PresentedHostUiRequest`. The current UI reads only `requestId` and obtains the command by claiming it; it does not validate those ownership fields. Unlike a choice or input prompt, a transient parsed host command is not displayed and later sent back to the player.

Use `PlaybackFrame.hostCommands: List<HostUiCommand>`. Repeated equal enum values remain distinct ordered list elements. Allocate each occurrence's unique identity once, when the runtime admits it. The public presentation can be `HostUiRequest(id, command)`; keep any necessary origin token and lifetime metadata in the private runtime queue. This preserves the spec's identity guarantee without assigning an unused parser identity.

A queue owned by a Session already has session ownership. Eager clearing at the existing invalidation points can also eliminate redundant per-entry lifetime fields; retain private fields only where a concrete delayed path requires validation. Do not delete the pre-request epoch check: pending replies and queued scripts still need it. Prefer the existing `DialogueToken` if explicit origin provenance is retained, instead of another pair of session/dialogue longs.

### P3 — Named construction can eliminate public mode combinations and repeated policy checks

Task 2 adds two public constructor booleans, a bundled factory, an exposed computed engine capability, a session flag and a concrete-type check. The public boolean combination `bundledDefault = false, debugToolsEnabled = true` has no meaningful distinct behavior. Preserving `BuiltInShiori(content)` does not require exposing those combinations.

Keep that existing constructor for ordinary table ghosts and use one named factory for the default ghost. Let it select a private three-way mode (ordinary table, default, default with developer tools), or an equally small private representation. Keep one private table parser and overlay the localized map in memory. Determine the session's developer authorization once from verified bundled provenance and the actual configured BuiltInShiori; expose a boolean to the playback admission code. Keep the real Android debuggable guard at the UI boundary.

Do not replace the current canonical-path/default-session distinction with a display-name check, and do not infer trust from command bytes. Retain the two runtime factories for now: they already distinguish default construction from imported table engines and support existing tests. Unifying every engine factory is a separate refactor with little benefit here.

### P3 — Reduce test migration churn and duplicated locale plumbing

Task 2 partially migrates the nine instrumentation cases; Task 3 then renames/replaces them and changes the inventory. Migrate the fixture and assertions once alongside the API change. Keep the existing methods where their observable behavior is still valid. A class rename is optional and should occur once, with its inventory update. “BundledAssistantUiTest” is not a production architectural dependency.

Some existing assertions must deliberately change: `bootTouchImportAndClockRepliesCannotOpenTools` and `importedBuiltinCannotOpenTools` express the old blanket restriction. Under the approved spec, ordinary active-ghost scripts can issue Ghosts/About regardless of the SHIORI event that produced them. Replace those assertions with positive common-command coverage and developer-specific denial. Preserving their old meaning would reintroduce the unwanted event admission map.

Keep full Locale selection confined to loading the APK-owned default dialogue. The current repository/runtime language strings also select imported content and lifecycle fallback; converting them all to Locale is unnecessary. Capture the startup Locale at default construction. Keep dialogue assets and Android strings separate because they own different content; use one Traditional Chinese resource definition set that actually covers the required locales, rather than copying every new debug label into TW/HK/MO directories. Verify the resource selection on Android rather than assuming it. The plan already permits the needed qualifier adjustment.

## Concrete minimal design

1. **BuiltInShiori owns all built-in dialogue behavior.** Ordinary construction preserves the current table contract. A named default factory reads one selected locale asset with the existing byte bound, validates it, overlays the original table and enables default routing. No wrapper, content service, UI labels, or new loader interface. Nonlocalized open-command replies can be defined once inside the engine; locale assets need only contain the localized dialogue/choices. Keep validation for direct debug-menu events as well as the direct developer event, so an authored internal menu key does not bypass release menu policy.
2. **ScriptPlayer emits transient enum values.** Extend the existing `!` handling for exact recognized targets while retaining input behavior, incremental parsing and its source budget. A list preserves duplicates and order. No parser callback, engine access, or command executor object.
3. **GhostRuntime owns delivery.** Reuse its monotonic request counter, current Session, replacement generation and existing pause epoch. Carry a captured request epoch through QueuedDialogue and the active player because a reply or script may cross pause/resume. Admit commands in `acceptPlayback`, assign IDs there, append to one private FIFO, and publish when it changes. Keep denial independent of ordinary text playback.
4. **Compose receives a narrow request.** StageState presents only the head ID/command. `claimHostUiRequest(id)` atomically checks/consumes the matching head and exposes the next one. Existing unsaved session-scoped dialog state handles presentation, with the agreed modal arbitration from finding 2. DebugToolsDialog reads `stringResource`; MainActivity no longer loads or passes ghost content.

The pause epoch is justified complexity, not an artifact to remove. Incrementing the replacement generation on pause would also discard otherwise valid text replies and change the existing contract. Likewise, a single overwriteable pending slot cannot satisfy the approved multiple-occurrence behavior. Keep those two mechanisms small and private.

The epoch audit must include `beginImportEvents` (`GhostRuntime.kt:206`) as well as activation, interaction, timers, surface replies and lifecycle calls. It directly requests/enqueues replies outside the existing private request helper. Prefer centralizing capture in that helper where possible, but do not launch a general request-framework refactor. Capture before suspension; reading the epoch only when enqueueing allows a pre-pause reply to acquire the new lifetime. Specify the treatment of requests initiated while paused rather than accidentally inheriting it from a default parameter.

## Further simplification requiring an explicit scope adjustment

Prefer a complete APK-owned default locale table if the author accepts that the app owns all default-ghost dialogue. I inspected the en/ja `content.txt` entries in the current committed `app/src/main/assets/nanidroid.zip`, not historical application source. Beyond `OnFirstBoot` and `OnBoot`, which are already overridden, each contains only four lifecycle entries: `OnGhostChanging`, `OnGhostChanged`, `OnClose`, and `OnInstallFailure`, plus `homeurl`.

Move those four existing en/ja scripts and the small metadata entry into the corresponding app-owned tables; provide matching Traditional Chinese lifecycle scripts. The default factory can then be `BuiltInShiori.bundled(locale, openAsset, debugToolsEnabled)` with no `originalContent` input and no overlay. Ordinary imported table ghosts continue using `BuiltInShiori(content)`. Preserve literal switch-name substitution. Keep the ZIP, PNGs, extracted files, descriptor loading and activation markers unchanged. The initial implementation need not refactor BundledGhostRepository merely because default construction no longer uses its content string.

This is preferable on architectural merits: one source of truth per default locale, fewer factory inputs, no two-table precedence, and no Japanese close/switch/install-failure replies mixed into the new Traditional Chinese dialogue. It adds only four localized dialogue entries and does not require translation of host dialogs. The current overlay plan would select the old Japanese table for language `zh`, because the committed ZIP has no Chinese table, then inherit those Japanese lifecycle replies.

It is **not behavior-preserving for every existing installation**. The current wrapper delegates otherwise-unhandled events to the installed table, so user-modified or added default-table entries would stop affecting runtime behavior even though the files remain intact. The approved spec explicitly preserves unrelated original lifecycle replies, and the plan explicitly preserves ordinary delegation to the original content. Amend that contract to say that the shipped en/ja lifecycle behavior is preserved in APK assets and the default's installed dialogue table is no longer authoritative. Test imported custom tables separately and retain the installed-file hash assertion. No migration is needed.

If that ownership change is not approved, retain the small private overlay. Avoid compensating with an elaborate merge policy or rewriting installed files. Translating the four inherited entries by adding overrides to zh-TW alone is an intermediate localization fix, but it does not remove the two-source construction complexity.

## Changes to the plan

- **Task 1:** replace HostCommandOccurrence with an ordered enum list. Keep the existing parser boundary, ordering and input tests; test identity at the runtime boundary where it is actually used.
- **Task 2:** use named default construction, a narrow presented request and private FIFO metadata. Add command-only publication and ending-frame/next-dialogue tests. Explicitly include import-event epoch transport. Migrate existing UI fixtures once and update inventory in that same change if names change.
- **Optional approved ownership change:** replace the default overlay with complete APK-owned en/ja/zh-TW tables, removing `originalContent` from the default factory. Update the design's delegation promise and tests; preserve imported-table behavior and all installed bytes.
- **Task 3:** focus new UI assertions on real script-to-dialog wiring, the agreed multiple-modal behavior and locale resources. Preserve existing nine behavioral cases rather than rewriting them for terminology. Keep the final build/lint/unit gate and the approved disposable-emulator screenshots; these are requested evidence, not disposable architecture overhead.
- Avoid a new real native fixture solely for these commands. The native response path already produces ordinary ShioriReply values; exercise admission using the existing runtime native-port seam, and clearly distinguish that from real JNI/device evidence.
- Do not require an extra full unit run merely because a task boundary was crossed after a green unchanged build. Use focused checks during implementation and the final full gate; cached results should be reported accurately. Compilation of the instrumentation APK remains useful after signature migration.

## Scope and risk assessment

The DTO/constructor/test-migration simplifications preserve the approved behavior and need no product-scope expansion. Removing Traditional Chinese, common commands from imported ghosts, bounded loading, installed-file preservation, stale-pause protection or single-use claims would change the approved scope and is not recommended.

Multiple-dialog presentation and natural dialogue progression need explicit semantics, as described above. They are the two decisions to resolve in the plan rather than leaving them to implementation guesswork. A new general modal scheduler, broad runtime request framework, full host-chrome translation, or migration of installed content would expand scope and cost unnecessarily.

Expected benefit is less exposed state, fewer identity domains, fewer test rewrites and a smaller engine configuration API. Most remaining implementation risk is concentrated in GhostRuntime's asynchronous reply/playback boundary, not in the three tag strings or resource labels. Prioritize discriminating tests there; line-count reduction alone is not a reason to remove its safeguards.

## Scoped resolution confirmation — 2026-10-11

Re-read the lead's revised design/plan sections addressing this report. This was a document confirmation only; it did not reopen the broader review or inspect a new product implementation. The original findings above record the previous plan state.

| Finding | Resolution in revised documents | Status |
| --- | --- | --- |
| P1: command-only publication | Task 2 explicitly publishes changes to the FIFO head even when the retained frame is identical, and names both the unchanged-frame and denied-batch tests. | Resolved in plan; implementation verification pending. |
| P2: multiple-modal behavior | The design specifies sequential presentation after dismissal. Task 2 defers claims while a host/input/import/switch modal is visible and observes modal availability. Task 3 adds Ghosts→About, repeated-command and existing-input cases. | Resolved in plan; implementation verification pending. |
| P2: natural dialogue advancement | Both documents distinguish explicit replacement from natural progression. Task 2 explicitly forbids current-dialogue equality from cancelling an ending-frame command and names its regression test. | Resolved in plan; implementation verification pending. |
| P3: duplicated identities/public metadata | Task 1 uses an ordered enum list; runtime admission assigns the sole request ID. The public HostUiRequest contains only ID/command, with queue ownership and lifetime metadata private. | Resolved in plan. |
| P3: engine configuration complexity | Ordinary construction stays source-compatible; named default construction chooses private modes and computes session developer permission once from actual engine/provenance. | Resolved in plan. |
| P3: migration/locale cost | Existing instrumentation migrates once, keeps valid identities and avoids a class rename. Imported language handling remains unchanged; full Locale selection and Android resource verification stay scoped to the affected default/debug content. Full unit execution belongs to the final gate. | Resolved in plan. |

The epoch requirement now explicitly includes beginImportEvents and requests initiated while paused; old or unpermitted delivery origins do not gain permission after resume. The existing epoch and one FIFO remain, without a new request framework or UI queue. This addresses the supplementary lifetime audit recommendation.

Complete APK-owned default tables remain clearly labelled optional and awaiting author approval in both documents. The original-content overlay remains the binding implementation contract, including preservation of installed-default custom dialogue behavior. That is the correct treatment of the proposed scope change.

**Updated recommendation:** the revised sections address this review's actionable plan findings. No further revision is required from this scoped confirmation before author plan review. This is not an implementation approval or evidence that runtime/UI behavior passes tests; the specified checks remain to be executed during implementation. The optional default-table ownership decision remains with the author.
