# SakuraScript Interaction Integrity Design

**Status:** Approved design; awaiting user review of this written specification

**Date:** 2026-08-08

## Summary

Deliver one focused PR for GitHub issues #196, #218, #215, and #200. The PR
makes every SakuraScript interaction observable through one consistent runtime
authority: actions appear in the authored order, legacy selection callbacks
cannot expose actions hidden from Compose, and every playback-pausing input has
exactly one safe, recoverable host. Passive-mode execution will use the same
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
3. Ensure a pending input command always has one actionable UI host for submit
   or explicit cancel, including an input in an unprojected scope.
4. Make valid quoted and escaped passive-mode command syntax execute exactly as
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

The existing input dialog is deliberately transient: outside/back dismissal
hides only its presentation, while explicit Cancel and submit both verify the
pending generation and runner identity before resuming playback. The fallback
in this design reuses that boundary; it must not bypass it.

The defects arise where the same authored controls acquire independent views:

- `DialogueRuntimeState.pendingChoices` is derived from speaker-grouped
  contents, losing cross-speaker source order (#196).
- The legacy selection callback scans later `\\q` commands with a regular
  expression rather than tracking scopes (#218).
- An input command can pause legacy playback without emitting a visible
  `InputBox` segment for a Compose bubble (#215).
- Runtime passive-mode execution matches one raw spelling, while the tokenizer
  accepts balanced quoted and escaped command arguments (#200).

## Design

### Ordered action projection (#196)

Retain a bounded authored encounter order for interactive actions as the
tokenizer/runner processes a talk. Per-speaker `DialogueContent` remains the
authority for rendered text and ownership. Pending choices derive from the
ordered action projection, filtering only unrevealed or retired capabilities;
they do not derive by flattening grouped speaker content.

`DialogueSpeakerOwnership` continues to associate every choice capability with
its speaker for bubble placement. Its per-speaker lists are filtered views of
the same ordered pending list, so the global/legacy ordering and the Compose
action identities remain consistent.

### Scope-aware legacy selection publication (#218)

When the first visible choice causes the legacy callback to publish, its labels
and IDs are collected through the same scope-aware command/projection semantics
used for visible dialogue actions. Commands in scopes `>= 2` are consumed for
playback compatibility but cannot be included in the callback array.

The callback remains a compatibility consumer. It does not own, claim, or
reconstruct actions, and it remains published once per playback state.

### Unprojected input fallback (#215)

When playback publishes a pending input with no visible projected input control,
the activity opens one fallback `UserInput` dialog through
`DialogueDialogBinding`. The fallback is conditional: a supported-scope input
with an owning bubble keeps its existing bubble-triggered route and does not
open a duplicate dialog.

The fallback stores the existing `DialogueDialogRestoration` identity. Submit
and explicit Cancel use the binding's current-runner and pending-generation
checks before `resumeEvt()` and `submitInput`/`dismissInput`. Back/outside
dismissal remains presentation-only. A stale host after a new input, a ghost
change, or recreation may not resume or dispatch into a different session.

### Parsed passive-mode execution (#200)

Replace the raw `PASSIVE_MODE` regular-expression decision with a small named
helper that consumes the already-balanced command arguments at the current
playback position. It recognizes only the tokenizer's valid two-argument
`[enter|leave,passivemode]` form after quote/escape decoding. Valid accepted
forms update runtime passive state; malformed and unsupported commands retain
their existing non-effect behavior.

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
| #218 | A visible `\\q` followed by `\\p2\\q` publishes only the visible label/ID through `UICallback` | Runner callback regression | Scope-aware remaining-choice collection replaces regex-only scan |
| #215a | `\\p2\\![open,inputbox,id]` pauses playback and opens one recoverable fallback dialog | Activity/dialog-binding test plus runner test where needed | Fallback is offered only when pending input has no projected owner |
| #215b | Fallback submit, explicit cancel, stale generation/session, recreation, and supported-scope non-duplication remain fenced | Existing dialogue timing/binding tests | Existing generation/runner/restoration binding is reused |
| #200a | Valid quoted passive-mode arguments alter passive runtime mode | Tokenizer/runner test | Executor consumes parsed balanced arguments |
| #200b | Escaped argument spellings accepted by the tokenizer behave identically; malformed forms do not change state | Tokenizer/runner test | Parser and executor agree without broadening accepted syntax |

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
   authored order, while each choice remains in its original speaker bubble.
2. Compose and legacy callbacks expose the same visible choices for scripts
   containing hidden scopes.
3. Any paused input can be submitted or explicitly cancelled by a current,
   correctly bound host; no stale, duplicate, or unsupported-scope host can
   dispatch an action.
4. All valid tokenizer-supported passive-mode forms update runtime passive
   state, and invalid forms do not.
5. Existing supported input dialog behavior, IME layout, choice claiming,
   session fencing, and unrelated dialogue/timer behavior remain covered by
   their regression suites.

## Review Boundaries

The PR should be reviewed as one interaction-integrity change, preferably in
four commits aligned with the issue order. The #200 commit must stay
self-contained so it can be lifted into a follow-up only if review size
requires it. No production implementation begins until this written spec is
reviewed and approved by the user.
