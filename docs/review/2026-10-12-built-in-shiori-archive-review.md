# Built-in SHIORI archive ownership review

Reviewed 2026-10-12 against product HEAD `3078c4cd` and the revised, uncommitted design/plan. Workspace: `C:/Users/yenchi/.codex/worktrees/cc61/Nanidroid`. Loaded instructions: root `AGENTS.md` and the supplied task instructions. Skills: simplify and engineering-testing:reversible-design. No implementation or tests were run. This new report is the only file I edited.

## Governing constraint and recommendation

The author explicitly wants ghost dialogue, tap mapping, menu choices and debug-menu visibility in ZIP/NAR files, with installed dictionaries authoritative. I accept that preference. My earlier optional APK-owned-table proposal is rejected and is not a recommendation for this revision. Android host-dialog strings and host permission checks remain host responsibilities.

The revised boundary is sound and simpler than the wrapper/content-service design. Keep one generic table engine, the three fixed selector forms, ordinary SakuraScript host commands, and a narrow repository refresh. Do not add an assistant dispatcher, APK dialogue overlay, expression language, version-marker system or general updater.

The findings below record issues raised during this review. The lead revised the documents while the review was in progress; the final resolution section records my confirmation against the latest on-disk text. All actionable plan findings are now resolved at the document level.

## Actionable findings

### P2 — Verify the existing install identity before adding or replacing dictionaries

Task 2 currently specifies a fixed `nanidroid` root, path confinement, archive verification and per-file hashes. These establish where a file is and which bytes it contains; they do not establish that an existing same-named tree is the bundled built-in install. Current `BundledGhostRepository.ensureInstalled()` returns any existing `ghost/nanidroid` path before validating `install.txt`. The new refresh would otherwise add all missing managed files to a partial, unrelated or native-engine tree before `load` rejects it or applies different semantics.

Before any refresh mutation, parse the existing manifest and master descriptor through confined, non-linked regular files. Require `type=ghost`, `directory=nanidroid`, and the supported built-in SHIORI declaration. Do not demand byte-identical descriptors: customized display names are legitimate and must remain untouched. Nonmatching targets receive no managed additions/replacements; retain their existing load/error behavior. Check identity before creating missing locale directories, not only before the final rename.

Add a same-named native tree and a partial/unrelated directory test that snapshot every byte and directory entry before `ensureInstalled`. They must remain unchanged. Retain per-target byte rechecks immediately before replacement: install identity does not replace custom-content preservation.

### P2 — The bundled path must not retain Kotlin-authored onboarding or close text

The ownership rule conflicts with two current paths that the plan had not explicitly removed: `GhostRuntime.ONBOARDING` and its queue insertion near `GhostRuntime.kt:876`, and the synthesized English close reply in `BuiltInShiori.kt`. Renaming the assistant capability does not itself remove either. The plan initially continued to promise a default-close response.

Remove the runtime onboarding constant/insertion and let archived `OnFirstBoot` supply the sole welcome. Preserve activation/onboarding marker consumption so this does not reset onboarding state. For named bundled construction, absent `OnClose` should return 204; the shipped dictionaries contain the authored close reply, while a customized dictionary may intentionally omit it. Current `closeSession` already accepts an empty reply and calls `maybeFinishTransition`, so no new close mechanism is necessary.

The lead proposes retaining the ordinary constructor's existing generic close fallback solely for imported-table compatibility. That narrow exception is reasonable within this cleanup and does not require new assistant behavior. State it explicitly rather than claiming every legacy host fallback has been removed. Unrelated UnsupportedShiori diagnostics need not be refactored here. Tests should prove bundled missing-close returns 204, ordinary missing-close retains its prior behavior, and first activation consumes markers without injecting extra dialogue.

### P2 — Update the separate unit-test ZIP and preserve old dictionary fixtures deliberately

The file-responsibility list names `app/src/main/assets/nanidroid.zip` but omits the separate tracked `app/src/test/resources/nanidroid.zip`. Repository and runtime tests load the latter using `getResourceAsStream("/nanidroid.zip")`. Both currently have SHA256 `2ebf24a2be8255011c5e004459a58dd1317eb7b12a55adcbe6b8b2977c89dc2d`.

Updating only the production asset and pinned archive hash makes those tests fail archive verification or, if relaxed, test the wrong behavior. Add the unit resource to Task 2 and keep the two current-bundle copies byte-identical. Use separate narrowly named old en/ja dictionary fixtures for upgrade tests, verified against the old per-file hashes; do not leave the main test ZIP ambiguously representing the old release. No Gradle resource restructuring is needed.

### P3 — Specify exclusive temporary-file ownership without introducing orphan recovery

“Owned temporary names” and “clean up scoped temporary files” do not specify how existing authored files are protected. A fixed temporary filename opened for overwrite, or a prefix sweep after interruption, can truncate/delete an unrelated existing file inside the authoritative ghost tree.

Use exclusive unique temporary-file creation in the checked destination directory. Write/close the complete replacement before atomic publication; recheck the target's eligibility; clean up only the exact temporary file created by that invocation. Do not reuse the fresh-install whole-tree cleanup helper or scan arbitrary prefix matches inside an installed ghost. A process killed before publication may leave an unreferenced temporary file; leaving it alone is safer and smaller than adding a provenance journal merely to recover it. If persistent cleanup is desired, an explicitly reserved ownership namespace must first be defined and tested rather than inferred from a filename.

Test a pre-existing temp-like filename, failure before rename, failure between two locale replacements, and retry. None may alter custom files. This is a small clarification of the existing preservation guarantee, not a new update subsystem.

## Selector review and corrections already incorporated

The initial revised text allowed ordinary literal lookup of selector-row keys. A direct request for `OnMouseClick@r3=1@debug` could therefore return a debug menu in a release engine, even though developer execution would still be rejected by runtime policy. The latest spec/plan reserves recognized selector syntax, stores those rows separately, denies direct requests for selector IDs, and adds tests in both modes. That resolves the menu-visibility bypass at the plan level.

This reservation is a real compatibility boundary: an old table that intentionally used a recognized selector-looking literal event ID changes behavior. The latest spec explicitly acknowledges it. Unknown keys outside the exact grammar remain literal. Keep the grammar narrow and anchored; do not reserve every key containing `@`. Test ordinary `OnCustom@something`, recognized direct IDs, noncanonical numerals (`01`, signs, whitespace), integer overflow, missing references and selector precedence. No format-version marker is needed merely to support these three forms under the documented boundary.

The initial Reference3-only rule also could not preserve the wrapper's rejection of nonnumeric References0–2 for taps. The latest plan restricts generic mouse selector eligibility to valid integer References0–2 for `OnMouseClick`, `OnMouseDoubleClick` and `OnMouseMove`, while ordinary plain-event lookup remains unchanged. That is a small protocol rule with no assistant roles or speaker mapping in Kotlin. The default archive has no plain `OnMouseClick` fallback, so malformed clicks return 204. Do not broaden this to an unbounded `OnMouse*` prefix or create a general guard DSL.

Ordinary topic/choice rows now follow the ordinary table contract. The former assistant-specific rejection of every unexpected reference should not be recreated as a list of OnNanidroid event names. Replace those obsolete wrapper assertions with generic table semantics and runtime developer denial. The authored archive should have tests for every route into its tools menu, including the welcome/help choice, not just direct character taps; this catches a missing `Event@debug` variant without adding host menu logic.

## Refresh safety and minimum implementation

The three-path hash-recognized refresh is sufficient for this change after the identity check above. Verify the pinned ZIP before trusting replacement bytes; do not mutate custom dictionary bytes. New/current files are no-ops, recognized old files are replaced, and missing managed dictionaries are added only to an identified bundled install. Preserve descriptors, PNGs, shell files, saves, markers and all unrelated entries. Customized older tables may lack the assistance feature; installed authority means accepting that outcome rather than silently compensating in Kotlin.

Use one small fixed list of relative dictionary paths and previous hashes. Extract the three replacement dictionaries once from the verified archive. Check all destination components without following links, including special/reparse entries where the test platform exposes them, and require a regular file before hashing an existing target. The repository already contains examples of non-following attribute checks in `ImportStaging`; reuse the approach locally without importing its directory-deletion policy. Hash custom content in a bounded-memory stream rather than allocating the entire file just to discover it is custom.

Per-file atomic publication plus hash recognition handles ordinary process interruption: each published dictionary is old or new, and a later startup can finish the remaining recognized files. There is no need for a whole-directory transaction, backup tree, version marker, general updater or cross-locale rollback. Do not silently fall back to in-place writing if atomic replacement fails. The existing startup error path should report the failure while leaving published files intact. Claims about sudden power-loss durability would require separate synchronization guarantees; the proposed interruption tests should not be described as proving that broader property.

The default load path should read the installed selected dictionary only, with the planned size bound and Japanese fallback. BuiltInShiori needs neither Locale nor an asset reader. Required assistant keys belong in shipped-archive validation/tests, not validation of customized installed content. Keep imported loading behavior outside this refactor unless an actual change requires it.

For reproducible old-fixture construction, the current committed production ZIP contains these exact dictionary bytes:

| Entry | Bytes | SHA256 |
| --- | ---: | --- |
| `ghost/master/en/content.txt` | 314 | `b4621c0532d0027a539aecfadd85448b2167d9671f0f9c78239735b302b67f0c` |
| `ghost/master/ja/content.txt` | 540 | `3b1fbeee59cb47e1e015fe41535218ec9a778444be2dc98d145b91df85b36002` |

These hashes were read from the current ZIP during review. Preserve the exact fixture encoding and line endings. They recognize dictionary versions; they are not substitutes for root identity checks.

## Simplifications to keep

- One first-comma parser and a small recognized-selector parser; no selector AST, conditions framework or assistant routing class.
- Existing named bundled construction configures host capability and legacy-fallback policy only. Scripts and menu visibility remain authored dictionary entries.
- One transient command enum list, one runtime identity, one private FIFO and the existing delivery epoch. The previous review's publication/natural-progression/modal rules remain useful and need no new architecture.
- One narrow repository refresh routine, not a reusable installer framework. One test-fixture migration and the already planned final verification gate.
- Duplicating a short authored menu script between tap and choice entries is cheaper than adding an alias/templating language merely to remove a few archive lines. Archive generation tooling, if needed, should not become a runtime content service.

## Final resolution confirmation

Re-read the latest spec and Task 2/file-responsibility sections after the lead's revisions on 2026-10-12. The status below supersedes the initial finding descriptions; it does not claim that product code has changed or tests have passed.

| Review item | Confirmed resolution | Status |
| --- | --- | --- |
| Existing-install provenance | Refresh now requires matching install type/directory and descriptor id/built-in SHIORI identity before dictionary writes. Wrong-identity and same-named native targets are explicitly unchanged test cases. | Resolved in plan. |
| Kotlin-authored bundled dialogue | Task 2 removes ONBOARDING and its insertion while preserving markers. Named bundled construction returns 204 for missing OnClose; ordinary construction's legacy fallback is expressly retained as the compatibility exception. | Resolved in plan. |
| Separate test ZIP | The file list explicitly updates the unit-test ZIP byte-for-byte with production, saves exact old dictionaries under bundled-original/en and bundled-original/ja, and requires equality/hash checks. | Resolved in plan. |
| Temporary ownership | The refresh uses exclusive unique same-directory temporary files, deletes only its own exact created file and ignores interrupted orphans; prefix-matching user files must survive. | Resolved in plan. |
| Direct selector IDs | Recognized selector rows are reserved and inaccessible as literal event requests; tests cover both modes. Unrecognized syntax remains literal. | Resolved in plan. |
| Malformed mouse eligibility | Validation is limited to selector eligibility for the three named supported mouse events and preserves plain-event lookup. | Resolved in plan. |

The detail recommendations on regular-file checks, streaming hashes, failure without an in-place fallback, and testing every authored tools-entry route clarify implementation of the existing contracts; they do not require another subsystem or a change to archive ownership. No additional high-confidence actionable plan gap remains from this review.

**Final recommendation:** the revised archive-owned plan is ready for author review/execution authorization. Keep the bounded implementation and tests described above. Product implementation, device verification and behavior evidence remain pending. Nothing in this report recommends moving ghost behavior back into APK-owned content services.
