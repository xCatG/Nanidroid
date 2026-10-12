# Built-in SHIORI cleanup: independent final branch review

Reviewed on 2026-10-12 in
`C:/Users/yenchi/.codex/worktrees/cc61/Nanidroid`, with root `AGENTS.md` loaded.
Scope: PR #442, base `35ff0c7d` through
`cb6c51c598231101f059e6ef22a17f89d7c5d891`, using the supplied whole-branch
review package, the approved cleanup specification/plan, current source and
tests, README, and current cleanup acceptance record. The controller's pending
plan edits were preserved. This review made no production/test changes.

The user's archive ownership preference is a requirement: dialogue, tap mapping,
menu visibility and choices remain in ZIP/NAR dictionaries. No APK dialogue
service, content overlay, or assistant-specific dispatcher is recommended.
Historical replacement exclusions and earlier planning status are not approval
gates for this authorized work.

## Finding

### [P2] Keep imported switch names from becoming trusted host commands

**Location:** `app/src/main/kotlin/com/cattailsw/nanidroid/runtime/GhostRuntime.kt:577-579`
(the new command admission predicate), with the substitution at
`app/src/main/kotlin/com/cattailsw/nanidroid/engine/BuiltInShiori.kt:38-40`.
**Confidence: 97/100.**

An imported ghost can declare `name,Probe\![open,developer]` in its descriptor.
After the bundled default has been activated once, switch to that ghost and
then return to the bundled default in a resumed debug build. The imported
descriptor's `name` is retained as `displayName`, copied to `BundledGhost.name`,
and passed by `finishSwitch` as `previousName` at line 996. Activation sets the
new default session to `Active` at line 868 and requests `OnGhostChanged` with
that name at lines 891-904. The shipped English dictionary contains
`OnGhostChanged,\0\s0Changed From %1$s\e`; `BuiltInShiori` inserts the reference
without SakuraScript escaping. `enqueue`/`substituteNames` do not neutralize the
inserted tag. Playback therefore emits `Developer`, and the new admission
predicate accepts it because the *receiving* session is the trusted default.
The UI's real debuggable check also passes in this scenario, opening Debug tools
without an authored default tools selection. This violates the explicit
requirement that imported ghosts cannot issue developer requests.

This is newly observable because this branch makes the developer tag executable
and grants the default session permission. The existing raw substitution alone
could not open this newly added dialog at the base revision. Outgoing
`OnGhostChanging` is correctly denied during `Switching`; that phase check does
not cover the incoming `OnGhostChanged` path, which runs after `Active`.
The relevant imported field for the return path is `name`, **not** `sakura.name`.

Preserve the authored greeting and display the substituted foreign name as
literal text through a parser-supported escaping/data boundary, so text from
another ghost cannot acquire the receiving session's command capability. Keep
valid developer commands authored in the default dictionary working. A focused
regression should switch back to an already activated real bundled engine from
an installed descriptor carrying the above name and assert no Developer request
is admitted while the greeting still displays its name. Current direct
imported/native denial tests do not exercise this cross-session substitution.

## Other review results

No other high-confidence actionable findings were established. Reviewed the
generic selector precedence/reserved-key handling, authored archive menus and
lifecycle entries, locale selection and bounded reads, managed dictionary
identity/hash checks and atomic per-file replacement, command parsing around
waits/input and incremental boundaries, admission IDs/FIFO/epochs, explicit
replacement versus natural progression, modal claim gating and session-scoped
dialog state, and the instrumentation setup/cleanup corrections. The narrow
transient-command and single-runtime-FIFO design fits the archive ownership
requirement; no additional architectural layer or simplification is needed to
resolve the finding above.

## Verification boundary

This was a read-only source review apart from this report. No tests, builds,
device commands, installations, remote review publications, or broad reruns were
performed. The finding is supported by the concrete current call chain and
archive bytes; this review does not claim a newly executed device reproduction.

The current acceptance record reports 440 unit cases (435 passes, five Windows
privilege skips), lint with zero errors/26 warnings/one hint, successful
debug/test/unsigned-release assemblies, and 17 exact API 31 UI passes in
46.16 seconds. Its latest instrumentation source hash is `c5a0dd35559a368d7f435208ba1ac0c2ddbf182b05fe794b1e3a073ec992dc60`.
Those are existing implementation-owner evidence, not tests rerun by this
reviewer. The recorded original PNG preservation and matching production/test
ZIP, nine inspected locale screenshots, and custom/nonmanaged file preservation
retain their stated scope. The review does not relabel earlier failed fixtures,
UiAutomation conflicts, or rejected blank captures as passes.

Live dialogue locale reloading remains outside the approved startup/process
restart scope. Release denial was modeled on a debug APK; actual release-device
behavior, the full device suite, private native fixtures and physical Pixel 7
deployment are not established by the focused acceptance evidence.

**Verdict:** Address the single P2 provenance finding before final approval.

## Scoped correction review — 8f0e779d

Reviewed only `cb6c51c598231101f059e6ef22a17f89d7c5d891` through
`8f0e779df2e353ad2e4d1b6acb8eaf0c078a3d6c`, using the supplied correction diff,
`final-fix-report.md`, the appended acceptance evidence, and the directly affected
parser/name-macro consumers. The original finding and verdict above are retained
as history. This scoped pass made no source/test changes and reran no tests,
builds, or device checks.

**Original finding resolved.** Lifecycle reference serialization escapes both
backslash and percent characters; the macro pass preserves the escaped pairs,
and ordinary playback consumes them as text without rescanning the emitted
characters. The real-runtime regression follows installed foreign metadata back
to an already activated bundled engine, checks the literal greeting and absence
of a host request, then claims an authentic archived developer choice. The added
device identity checks the corresponding runtime/MainActivity path. The preserved
red log shows the new runtime regression failing before the fix. This correction
keeps dialogue/behavior in the archive and does not alter the host permission or
FIFO policy.

### [P2] Decode serialized name data in bracket-argument consumers

**Location:** `app/src/main/kotlin/com/cattailsw/nanidroid/runtime/ScriptPlayer.kt:324-327`.
**Confidence: 96/100.**

The changed `substituteNames` method escapes every macro replacement, including
replacements inside choice labels and event references, but only the ordinary
text loop in `advanceBy` decodes those escapes. For example, an authored script
`\q[Talk to %selfname,OnTalk,%selfname]` with descriptor name `100%` previously
created label `Talk to 100%` and reference `100%`. It now produces
`\q[Talk to 100\%,OnTalk,100\%]`. `bracketArgument` returns that raw substring;
`parseChoice` splits it and stores `fields[0]` and `fields.drop(2)` unchanged.
Consequently `BalloonContent` displays the extra backslash and `ChoiceItem.event`
sends `100\%` as Reference0 to `OnTalk`. Names with backslashes likewise acquire
doubled backslashes. The same raw-argument path changes macro-derived inputbox
IDs. This is a compatibility regression introduced by escaping replacements at
line 326, and can break authored event handling even though normal balloon text
is now correct.

Keep the new protection when substitutions enter executable script, but ensure
literal argument values retain their original data when consumed. Decode through
an appropriate literal-data boundary in the argument consumers (or serialize
according to context), without globally unescaping before command recognition.
Add a focused parser regression with a name containing percent/backslash inside
a choice label and event reference; require both rendered label and resulting
SHIORI reference to equal the original name, alongside the existing injection
denial checks. Current new tests put substitutions in ordinary text only and
therefore do not expose this argument-consumer mismatch.

No other high-confidence actionable issue was established in this scoped pass.
Read the saved unit/UI result summaries: 446 cases, zero failures/errors, five
Windows skips (441 passes); 18 observed UI identities, all passed, including the
new installed-name regression. The implementation-owner report and acceptance
record document the executed full gate, lint zero errors/26 warnings/one hint,
three assembled APKs, the 45.949-second exact UI run and nine regenerated,
inspected screenshots. These remain existing evidence, not reviewer reruns.
Earlier normal-factory journeys correctly retain their older APK provenance.

**Scoped verdict:** The original provenance bypass is fixed; address the new
P2 argument-value compatibility regression before final approval. Archive-owned
behavior, startup locale scope and all previously stated device/release limits
remain unchanged.

## Scoped correction review — 230902e2

Reviewed only `8f0e779df2e353ad2e4d1b6acb8eaf0c078a3d6c` through
`230902e2dd4c9f3bb119f4610c2d0c234e933274`, using the supplied round-two diff,
the appended implementation report/acceptance record, and the directly relevant
parser/helper consumers. Earlier findings and verdicts remain historical above.
No source/test changes, test/build reruns, device commands, or external actions
were performed for this review.

**Argument-value finding resolved.** Choice labels, choice IDs, event references
and inputbox IDs are now decoded after their structural field boundaries have
been identified. Commas and brackets in substituted data are quoted, so decoding
restores the original value without splitting it into new fields or ending an
argument. The normal and deferred bracket scans share the same escape-aware
end search; a quoted character crossing the source budget is carried into the
next scan. Numeric surface/animation/balloon/wait consumers decode before their
existing validation. Host-command admission still matches the encoded argument
against the exact allowlist, so decoded comma/bracket metadata is not reparsed
as host-command structure. Emitted ordinary text is likewise not rescanned.

The added regressions directly cover the prior `100%` label/reference example,
backslashes and hostile delimiters, input IDs, event/legacy/Satori choice data,
numeric/newline/wait consumers, quoted delimiter budget boundaries, and denial
of substituted structural fields. Existing installed-name return regressions
continue to cover the original trusted-default bypass and successful execution
of an authentic archived developer choice. This remains a generic parser data
boundary; archive-owned dialogue, menus and behavior are preserved.

**No further high-confidence actionable findings** were established in this
narrow correction review. Saved summaries inspected during this pass report
451 unit cases, zero failures/errors and five existing Windows privilege skips
(446 passes), plus 18 observed device identities with 18 passes and no
failures/skips/incomplete results. The implementation record reports the
actually executed full gate, lint zero errors/26 warnings/one hint, all three
APK assemblies, and the fresh 46.871-second API31 run. These are retained
implementation-owner evidence rather than reviewer reruns.

The nine previously inspected images retain their `8f0e779d` APK provenance;
this pass does not claim a newly inspected image set for `230902e2`. The fresh
device assertions and prior visual evidence are recorded separately. Historical
normal-factory journeys, Windows skips, modeled release policy, live-locale
exclusion, native-fixture limits and untouched Pixel 7 retain their stated scope.

**Current verdict:** Both reported P2 findings are resolved at `230902e2`.
No actionable review findings remain from the whole-branch review and its two
scoped correction passes. Final documentation integration and authorized PR
actions remain with the controller.
