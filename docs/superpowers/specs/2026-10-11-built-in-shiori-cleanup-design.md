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
2. BuiltInShiori handles default-ghost tap routing and returns dialogue/scripts.
   Ordinary table ghosts retain their authored event behavior. Default behavior
   is enabled explicitly for the verified bundled session, not by a display name.
3. ScriptPlayer interprets supported host commands during normal playback, as it
   already does for input boxes. SHIORI never holds a Compose callback or opens a
   dialog directly.
4. GhostRuntime publishes generic, session-owned host UI requests. The UI claims a
   request once and opens the corresponding host dialog. Requests contain a closed
   command type and identity, never a content object or engine-specific event ID.
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

- Keep help, introductions and menu copy in locale-specific dialogue assets owned
  by the app and loaded behind BuiltInShiori. APK-owned dialogue must still apply
  to existing installations without rewriting their extracted ghost files.
- Reuse one parser for built-in table data. Preserve the first-comma syntax,
  literal switch-name substitution, bounded reads and useful validation.
- Remove DebugToolsTitle, ShowTouchBounds and Close entries from dialogue files.
  Put debug dialog title, toggle label, Close and accessibility text into Android
  resources in English, Japanese and Traditional Chinese.
- Add the missing Traditional Chinese dialogue file and select it using the full
  locale: `zh-TW`, `zh-Hant`, and Traditional Chinese locales `zh-HK`/`zh-MO` use
  the Traditional Chinese asset; English uses English; Japanese and other
  unsupported locales retain the existing Japanese fallback. Explicit `zh-Hans`
  remains unsupported and falls back rather than displaying the wrong script.
- Keep assistant selection and UI resources consistent at startup. Changing locale
  within an already running session is outside this cleanup; restart applies it.
- Full translation of pre-existing import/input/About chrome is separate work;
  this cleanup covers the default dialogue and debug dialog affected by the coupling.

## Lifecycle and execution guarantees

- A host command executes when playback reaches it, respecting preceding text,
  waits and input-box pauses. A command is not inferred from a choice ID.
- Each parsed occurrence has a unique request identity; recompose/tick/claim cannot
  execute it twice. Repeating an explicitly authored command is a new occurrence.
- Requests retain session/dialogue/generation ownership. Replaced dialogue, ghost
  switch, close and pause invalidate pending delivery. Delayed SHIORI replies from
  before pause/resume cannot revive a host UI request.
- Suppressed requests must not cancel otherwise valid dialogue. Debug denial must
  not suppress ordinary text in the same reply. Parsing produces requests; UI
  execution remains outside the parser/SHIORI.
- Dialog state remains session-scoped and unsaved; recreation dismisses a consumed
  dialog without replay. Preserve modal interaction blocking and Back dismissal.
- Preserve the single localized first-boot welcome and existing activation markers.

## Verification

Use focused JUnit 4/coroutines-test and Compose tests to prove:

- Default tap/menu/topic/lifecycle behavior, ordinary table delegation, no UI-label
  keys in ghost content, and English/Japanese/Traditional Chinese selection.
- Ghosts/About/developer parsing, strict target matching, unknown/malformed tags,
  command ordering around waits/input, multiple command occurrences and bounded
  parsing across incremental playback calls.
- Common host commands from ordinary ghosts; debug allowed only from the actual
  built-in default and only in debug policy. Reject forged/imported debug scripts.
- Exactly-once claiming, stale/replaced/paused/switched/closed requests, valid text
  surviving a denied command, first-boot marker preservation and installed-file hashes.
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
preservation guarantees. Awaiting author review before an implementation plan is
written; product implementation has not started.
