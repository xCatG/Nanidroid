# Built-in SHIORI and host-command cleanup

## Intent

The author requested removal of the assistant-specific coupling between SHIORI,
dialogue content and Compose UI. The default ghost should use the same event and
SakuraScript playback flow as other ghosts. A debug-menu request is the only
restricted extra operation needed by the default ghost. Keep the current tap/help/
tools behavior, original PNGs and installed data.

This refines the implementation in PR #442 on `codex/default-ghost-assistant`.
Current starting head is `78f80a6b`. Existing approval covers the assistant feature;
this document specifies the requested architecture cleanup. Repository AGENTS.md
and current source govern; historical replacement exclusions are context.

## Current issue

`BundledAssistantShiori` wraps the simple table SHIORI and duplicates event routing.
`BundledAssistantContent` parses another table and mixes dialogue with dialog labels.
MainActivity injects this content object into GhostStage and DebugToolsDialog.
`ShioriReply.appAction` bypasses normal script playback, with matching assistant
event IDs duplicated in GhostRuntime. These additions work but make the UI depend
on a particular ghost implementation.

## Intended structure

1. Taps and choices continue producing normal SHIORI events.
2. The ghost archive owns dialogue, tap rules and menu choices. BuiltInShiori
   performs generic table lookup and returns scripts; it contains no assistant
   event IDs, speaker roles, help topics or menu copy. Only developer-command
   permission is enabled for the verified bundled session.
3. ScriptPlayer interprets supported host commands during normal playback, as it
   already does for input boxes. SHIORI never holds a Compose callback or opens a
   dialog directly.
4. ScriptPlayer emits an ordered, transient list of command enums. GhostRuntime
   assigns one identity per admitted occurrence and publishes a generic head
   request containing only that ID and command. Ownership metadata stays private
   to its session-owned queue, never in a content object or engine-specific event ID.
5. GhostStage and DebugToolsDialog use Android string resources. They have no
   dependency on BundledAssistantContent, dialogue tables or built-in SHIORI classes.

The wrapper class and specialized content class are removed. Table parsing is
shared within the built-in engine/content-loading boundary rather than duplicated.
ShioriReply returns to status/value/charset; the assistant-only appAction field,
enum and event-ID admission map are removed/replaced by the script host-command path.

## Host commands

Use the existing `\![open,...]` command family:

| Script | Nanidroid host behavior | Availability |
| --- | --- | --- |
| `\![open,ghostexplorer]` | Open existing Ghosts/import dialog | Any active ghost |
| `\![open,configurationdialog,about]` | Open existing About/licenses dialog | Any active ghost |
| `\![open,developer]` | Open the current debug-tools dialog | Verified built-in default session and debuggable application only |

UKADOC documents the ghost-explorer, configuration-dialog and developer commands:
https://ssp.shillest.net/ukadoc/manual/list_sakura_script.html . `about` is a
Nanidroid-specific configuration-dialog target; it is not a claim of an SSP About
dialog standard. Nanidroid implements only the three targets above in this cleanup.
Existing input-box behavior stays intact. Unknown/malformed targets are ignored.

Using the existing developer command avoids inventing a new tag. Only the built-in
default SHIORI offers/emits that request in its tools menu. The host additionally
rejects it from imported/native sessions and in release builds, including scripts
that reproduce the same command bytes. Common Ghosts/About commands follow normal
playback for other ghosts as requested; this deliberately replaces the original
assistant-only restriction on those two harmless host dialogs.

## Dialogue, localization and installed data

- The author's preference is explicit: ghost behavior belongs in ghost ZIP/NAR
  files, including the bundled ghost. Remove separate APK-owned assistant tables;
  do not replace them with another content service or runtime overlay.
- Put complete en/ja/zh-TW dictionaries in `nanidroid.zip` under
  `ghost/master/{en,ja,zh-TW}/content.txt`. Load the installed selected dictionary
  as authoritative. Preserve lifecycle entries and literal switch substitution;
  translate lifecycle replies as well as help and menus for Traditional Chinese.
- Reuse the first-comma table parser. A small generic selector convention supplies
  reference-aware lookup: `Event@r3=0` and `Event@r3=1` select Reference3 exactly;
  `Event@debug` and `Event@r3=1@debug` provide debug-enabled variants. Lookup
  priority is matching reference+debug, matching reference, event+debug, event.
  Support only Reference3 selectors in this change, with canonical nonnegative
  Int decimal values. Absent/malformed Reference3 skips reference variants. For
  OnMouseClick/OnMouseDoubleClick/OnMouseMove variants also require numeric Int
  References0–2, matching the standard mouse payload. This validation affects
  selector eligibility only; existing plain-event tables retain their lookup.
  The shipped dictionary has no plain OnMouseClick fallback, so malformed mouse
  requests return204. Debug variants require the named trusted built-in debug
  configuration. Recognized selector syntax is reserved: parse those rows
  separately and never
  expose them through a direct event-ID lookup (including in release). Keys
  outside this grammar remain literal keys; no expressions, interpolation,
  aliases or routing DSL.
  Existing event-only tables keep their behavior. The archive maps speaker0 to
  help and speaker1 to tools; Kotlin does not know those roles. Choice event IDs
  are ordinary authored keys. Runtime separately denies unauthorized developer
  commands, even when a release dictionary directly contains one.
- `BundledGhostRepository.load(root, locale: Locale)` chooses and bounds the
  installed dictionary read to64KiB plus one sentinel byte. Preserve the existing
  language-string overload for callers that need it. BuiltInShiori receives the
  loaded content, never an asset reader or Locale. Validate shipped required keys
  in archive validation/tests, not at engine construction, so custom tables work.
- Existing-install upgrade is limited to the bundled dictionaries. Before any
  refresh, validate install type/directory and descriptor id/shiori (`nanidroid` /
  `Nanidroid`) against the shipped built-in identity, using existing engine
  classification. A same-named native/replaced target is not refreshed. Verify the new
  ZIP hash before refresh. Replace en/ja only when bytes match the recorded
  previous shipped dictionary hashes; add zh-TW only when absent. Current shipped
  hashes are no-ops; any other existing bytes are customized and preserved.
  Missing dictionaries may be restored. Preserve PNGs, descriptors, shell files,
  native saves, activation markers, unknown files and imported ghosts.
- Exclusively create a unique temporary file beside each changed dictionary,
  then atomically replace
  the file under the existing installation lock; recheck ownership before replace.
  Hash recognition makes refresh idempotent after interruption, without a version
  marker, whole-directory swap, migration framework or UI update workflow. Reject
  symlink/escaped managed paths and clean up only the exact temporary file created by this invocation. Never
  sweep prefix-matching files; interrupted leftover temporaries are ignored. A failed
  refresh leaves individually valid old/new dictionaries; retry on next startup.
  Customized old dictionaries intentionally retain their authored behavior and
  may lack the new assistant interactions; do not silently overlay those entries.
- Remove DebugToolsTitle, ShowTouchBounds and Close entries from dialogue files.
  Put debug dialog title, toggle label, Close and accessibility text into Android
  resources in English, Japanese and Traditional Chinese.
- Add the missing Traditional Chinese dialogue file and select it using the full
  locale: `zh-TW`, `zh-Hant`, and Traditional Chinese locales `zh-HK`/`zh-MO` use
  the installed Traditional Chinese dictionary; English uses English; Japanese and other
  unsupported locales retain the existing Japanese fallback. Explicit `zh-Hans`
  remains unsupported and falls back rather than displaying the wrong script.
- Keep assistant selection and debug UI resources consistent at startup for the
  three supported languages. Unsupported dialogue locales keep Japanese fallback;
  other host UI retains Android's normal resource fallback. Changing locale within
  an already running session is outside this cleanup; restart applies it.
- Full translation of pre-existing import/input/About chrome is separate work;
  this cleanup covers the default dialogue and debug dialog affected by the coupling.

## Lifecycle and execution guarantees

- A host command executes when playback reaches it, respecting preceding text,
  waits and input-box pauses. A command is not inferred from a choice ID.
- Each admitted occurrence has a unique request identity; recompose/tick/claim cannot
  execute it twice. Repeating an explicitly authored command is a new occurrence.
- Assign that identity only at runtime admission; the parser needs no item key.
- Explicit dialogue replacement, ghost switch, close and pause invalidate pending
  delivery. Natural advancement to the next queued script preserves commands
  already reached in the current replacement generation. Delayed SHIORI replies
  from before pause/resume cannot revive a host UI request.
- Publish admitted requests even when command-only playback changes no retained
  text/visual frame. Denied commands produce no request.
- Present queued host dialogs in order. Defer claiming the next request while any
  host/input/import/switch modal is visible, then claim when it closes. Repeated
  equal commands each get a presentation; add no second UI queue or modal scheduler.
- Suppressed requests must not cancel otherwise valid dialogue. Debug denial must
  not suppress ordinary text in the same reply. Parsing produces requests; UI
  execution remains outside the parser/SHIORI.
- Dialog state remains session-scoped and unsaved; recreation dismisses a consumed
  dialog without replay. Preserve modal interaction blocking and Back dismissal.
- Preserve the single localized first-boot welcome and existing activation markers.
  Remove the runtime ONBOARDING script/queue path; archive OnFirstBoot owns the
  welcome. Named bundled engine mode has no synthetic OnClose script: missing
  authored OnClose returns204. Ordinary table construction retains its existing
  close fallback for compatibility outside this default-ghost cleanup.

## Verification

Use focused JUnit 4/coroutines-test and Compose tests to prove:

- Default tap/menu/topic/lifecycle behavior, ordinary table delegation, no UI-label
  keys in ghost content, and English/Japanese/Traditional Chinese selection.
- Ghosts/About/developer parsing, strict target matching, unknown/malformed tags,
  command ordering around waits/input, multiple command occurrences and bounded
  parsing across incremental playback calls.
- Common host commands from ordinary ghosts; debug allowed only from the actual
  built-in default and only in debug policy. Reject forged/imported debug scripts.
- Command-only publication after a wait, a command reached in an ending frame
  before natural queue advancement, and two/repeated dialogs presented after dismissal.
- Exactly-once claiming, stale/replaced/paused/switched/closed requests, valid text
  surviving a denied command, first-boot marker preservation, managed dictionary refresh and customized-file preservation.
- Real rendered tap → choice → SHIORI script → parser → runtime → dialog flow;
  debug dialog resource localization, Back, rotation, switch and release-policy denial.
- Update the strict device-test inventory when method identities change.

Run relevant unit/lint/debug/test APK checks and focused tests on an explicit
disposable emulator. Capture and inspect screenshots for the default menu and debug
dialog in all three supported locales. Keep logs/images/APKs in ignored build
artifacts. Do not install on Pixel 7 again unless requested. After validation and
review, update PR #442 and request another Codex review under the existing PR workflow.

## Review status

Written and self-reviewed for intent, scope, interfaces, locale selection and
preservation guarantees. Author approved this written spec on 2026-10-11 with
“approve, please proceed”. The next stage is implementation-plan review;
product implementation has not started.

## Astra simplification review

The [independent review](../../review/2026-10-11-built-in-shiori-cleanup-astra-review.md)
recommended the narrow request/transient-enum design, named default construction,
one instrumentation migration and the explicit publication/modal/advancement rules
above. These clarifications preserve the intended independently executed commands.

The author rejected the optional APK-owned default-table proposal on 2026-10-12.
The archive-owned design above supersedes it. The
[second Astra review](../../review/2026-10-12-built-in-shiori-archive-review.md)
accepts that ownership preference and tightens selector visibility, install
identity, authored welcome/close behavior, temporary-file ownership and test
archive consistency. Those findings are incorporated in this spec and plan.
Product implementation has not started; this is a revision of the planning documents.
