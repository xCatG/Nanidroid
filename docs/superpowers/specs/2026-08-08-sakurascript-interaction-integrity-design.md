# SakuraScript Interaction Integrity Design

**Status:** Approved direction; revised after independent design review

**Date:** 2026-08-08

## Summary

Deliver one focused PR for GitHub issues #196, #218, #215, and #200. The PR
makes every SakuraScript interaction observable through one consistent runtime
authority: actions appear in the authored order, legacy selection callbacks
cannot expose actions hidden from Compose, and every playback-pausing input has
exactly one safe, recoverable host; unsupported-scope input commands do not
create a paused interaction. Passive-mode execution will use the same
balanced command syntax that the tokenizer accepts.

The implementation order is #196, #218, #215, then #200. Every behavior is
developed with a verified Red -> Green -> Refactor cycle. Refactoring is limited
to the changed interaction boundary and must preserve ordering, timing,
lifecycle, errors, and identity/security fencing.

## Goals

1. Present pending choices in their exact SakuraScript source order across
   arbitrary speaker changes.
2. Make legacy `UICallback.showUserSelection` observe the same visible choices
   as the Compose stage.
3. Ensure every pending input command has one actionable UI host for submit or
   explicit cancel, while unsupported-scope input commands remain non-pausing
   and unprojected.
4. Make valid quoted passive-mode command syntax execute exactly as
   the tokenizer reports it.
5. Preserve the current action capability model: object identity for choices,
   one-of claiming, input generations, current-runner checks, and ghost/session
   generation fencing.
6. Reduce local duplication between parsing, action projection, and legacy
   compatibility publication while changing no unrelated behavior.

## Non-goals

- Issues #195 and #213 are excluded.
- Timer/revelation work, including #199 and #202, is excluded.
- No timing, scheduler, playback cadence, passive-mode product-policy, or
  lifecycle redesign is included.
- Hidden scopes do not gain a new speaker lane or dialogue bubble.
- This does not replace the legacy playback parser wholesale, redesign the
  Compose stage, or add a new dialog state machine.

## Current Boundary

`SScriptRunner` keeps the active playback state and publishes an immutable
`DialogueRuntimeState`. `SakuraScriptTokenizer` supplies speaker-owned
`DialogueContent` segments. `DialogueSpeakerOwnership` derives each speaker's
visible bubble content and control ownership, while `ComposeGhostStageHost`
renders the resulting controls. `DialogueDialogBinding` binds input and legacy
dialog actions to the exact runner and input/choice generation they displayed.

The existing supported-scope input dialog is deliberately transient:
outside/back dismissal hides only its presentation, while explicit Cancel and
submit both verify the pending generation and runner identity before resuming
playback. This batch preserves that boundary unchanged.

The defects arise where the same authored controls acquire independent views:

- `DialogueRuntimeState.pendingChoices` is derived from speaker-grouped
  contents, losing cross-speaker source order (#196).
- The legacy selection callback scans later `\\q` commands with a regular
  expression rather than tracking scopes (#218).
- The #215 contract needs a durable regression: an unsupported-scope input
  command must remain unprojected and must not pause playback.
- Runtime passive-mode execution matches one raw spelling, while the tokenizer
  accepts balanced quoted and escaped command arguments (#200).

## Design

### Ordered action projection (#196)

Add a bounded, source-ordered interaction stream to tokenizer output. Each
entry carries its source position, active scope, speaker, kind, and the exact
action/spec instance. `AuthoredDialogueScript` retains this stream alongside
the existing speaker-grouped `DialogueContent`. Per-speaker content remains the
authority for rendered text and ownership. Pending choices derive from revealed,
visible stream entries, filtering only retired capabilities; they do not derive
by flattening grouped speaker content.

`DialogueSpeakerOwnership` continues to associate every choice capability with
its speaker for bubble placement. Its per-speaker lists are filtered views of
the same ordered pending list, so the global/legacy ordering and the Compose
action identities remain consistent.

### Scope-aware legacy selection publication (#218)

When the first visible choice causes the legacy callback to publish, its labels
and IDs come from a parser-backed, scope-aware remaining-script scan beginning
at that command. It preserves the callback's intentional forward-looking
behavior: later visible choices are included before they are revealed in the
runtime projection. The scan uses the tokenizer's quote, escape, and scope
rules, returns labels/IDs only (not new capability objects), and excludes
commands in scopes `>= 2`.

The callback remains a compatibility consumer. It does not own, claim, or
reconstruct actions, and it remains published once per playback state.

### Unsupported-scope input safety (#215)

Use #215's explicitly permitted resolution: an input command in a scope that
does not project dialogue (`>= 2`) is consumed for playback compatibility but
creates neither `InputBox` content nor `PendingInputState`, and it must not
pause playback or invoke the legacy input callback. Subsequent supported-scope
content continues normally.

Supported-scope input keeps the existing bubble-triggered `UserInput` dialog
route and its `DialogueDialogBinding` generation, runner-identity, explicit
Cancel, submit, restoration, and IME protections. This PR intentionally does
not introduce a fallback dialog: doing so would turn a suppressed control into
a user-visible interaction and require a new atomic pause/claim protocol to
avoid a stale-host resume race.

### Parsed passive-mode execution (#200)

Extract one internal balanced bracket-and-argument parser from the tokenizer's
current semantics and use it for both tokenization and passive execution. Its
contract includes quoted arguments, doubled quotes, and `\\]`, `\\,`, and
`\\\\` decoding. The executor recognizes only the parsed two-argument
`[enter|leave,passivemode]` form. Valid accepted forms update runtime passive
state; malformed and unsupported commands retain their existing non-effect
behavior.

This is deliberately an execution alignment, not a general playback-parser
migration.

## Test-first Implementation Sequence

Each row is an independent Red -> Green -> Refactor loop. Before production
code changes, add the named regression and run it alone to confirm it fails for
the intended missing behavior. Then make the smallest production change, rerun
the focused test until it passes, and only then simplify with the focused tests
remaining green.

| Order | Red behavior | Primary test boundary | Green outcome |
| --- | --- | --- | --- |
| #196a | `\\h\\q[A,a]\\u\\q[B,b]\\h\\q[C,c]` exposes `A, B, C`, not grouped order | Runner dialogue timing/observer test | Ordered pending actions retain source encounter order |
| #196b | Alternating direct-event and `script:` choices preserve order and still claim by identity | Runner dialogue test | Order metadata does not weaken action capability checks |
| #218 | First visible `\\q`, text/wait, later visible `\\q`, then `\\p2\\q` publishes the two visible label/ID pairs once | Runner callback regression | Parser-backed remaining-script scan replaces regex-only scan without losing forward-looking publication |
| #215 | `\\p2\\![open,inputbox,id]` creates no visible input or pending action, invokes no legacy callback, and does not pause trailing supported-scope text | Runner presentation/callback regression | Unsupported scope remains safely non-interactive while supported input behavior is unchanged |
| #200a | Valid quoted `\\!["enter",passivemode]` and `\\!["leave",passivemode]` arguments alter passive runtime mode | Table-driven tokenizer/runner test | Tokenizer and executor consume the same parsed arguments |
| #200b | Escaped/malformed forms that do not decode to the valid two arguments do not change state | Table-driven tokenizer/runner test | Shared parser agrees without broadening accepted syntax |

After every Green step, run its focused test class. After the final refactor,
run the affected JVM suite and the relevant Compose/instrumentation tests from
`docs/testing.md`, plus `./gradlew.bat lint` and `./gradlew.bat
testDebugUnitTest`. The exact connected-test command will be selected only if a
device/emulator is available; absence is reported rather than substituted with
a claim of device verification.

## Constrained Simplification

Simplification belongs inside each post-Green refactor, not in a preliminary or
unrelated cleanup commit.

- Give scope-aware command walking and ordered-action derivation explicit,
  single-purpose helpers.
- Remove duplicated choice scanning and unnecessary intermediate grouping only
  where the new common authority proves equivalent behavior.
- Keep `DialogueDialogBinding` as the sole dialog fencing point; do not add a
  parallel fallback callback/state machine.
- Prefer named local state and straightforward branches over compact regex or
  nested conditional logic when it clarifies playback effects.
- Do not change public APIs, data formats, error handling, order, timing,
  performance-sensitive scheduling, or security checks merely for style.

## Acceptance Criteria

1. Choices from any alternating Sakura/Kero sequence display and publish in
   authored order, while each choice remains in its original speaker bubble;
   mixed normal, direct-event, and script actions retain identity-based claims.
2. Compose and legacy callbacks expose the same visible choices for scripts
   containing hidden scopes.
3. Every projected pending input can be submitted or explicitly cancelled by a
   current, correctly bound host; unsupported-scope input commands create no
   host or paused action and allow trailing playback to continue.
4. Valid quoted tokenizer-supported passive-mode forms update runtime passive
   state, and invalid or malformed forms do not.
5. Existing supported input dialog behavior, IME layout, choice claiming,
   session fencing, and unrelated dialogue/timer behavior remain covered by
   their regression suites.

## Review Boundaries

The PR should be reviewed as one interaction-integrity change, preferably in
four commits aligned with the issue order. The #200 commit must stay
self-contained so it can be lifted into a follow-up only if review size
requires it. No production implementation begins until this written spec is
reviewed and approved by the user.
