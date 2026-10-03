# Nanidroid blind recreation design

Status: approved by the author in this task. First-milestone implementation
planning is authorized; project creation and implementation follow plan review.

## Authority and scope

[The 2012-derived baseline](2026-09-22-nanidroid-baseline-spec.md) defines the
product and supported protocol subset. This document defines how to recreate it,
the native copy boundary, and the clarifications below. The 2026 behavior
backlog and full-corpus parity goal are not in scope. The supplied baseline is the
approved product scope; approval is not a claim that its historical behavior has
been independently device-tested.

Use two separate references:

- Product history: `c22e1531cc1cd9424314d57e7bc34ccb91d84cc5`, dated 2014-11-25,
  containing the 2012 application. Read only through source-exposed researchers.
- Native source: `afc6a3a350fd10da35d085d9889c407ac5429a2b`, the fetched
  `origin/master` used for this design, including #414/#415 JNI hardening.
  The existing native copy manifest pins all 297 files. Do not copy native code
  from the product-history revision or silently follow future master changes.

Build a fresh Kotlin/Compose app, not a new UI over either old runtime. Retain the
single Android module, application ID `com.cattailsw.nanidroid`, minSdk 31,
compile/target API 37, and arm64-v8a/x86_64. Native licenses stay with copied code.

## Reconciliation decisions

| Topic | Reconciled target |
| --- | --- |
| Main UI | Wallpaper-backed two-character stage, Ghosts, About/licenses, readme/switch confirmation, inline choices, modal input, and HTTP/HTTPS/mailto links from baseline sections 1-2. |
| Library | `filesDir/ghost/<directory>`; directory identity, display names from descriptors, master shell only. No automatic migration or deletion of old external-file ghost trees. |
| Import | Picker-only, fresh ghost packages, foreground single-flight import, install events and post-install readme/switch prompt. Never switch automatically. |
| Protocol | Exactly the baseline's event, script, parser, and surface subset, including its explicit improvements such as CP932, alpha/PNA, move, always/runonce, corrected touch events, and inline choices. |
| First milestone | Built-in table SHIORI and bundled ghost; no native engine is required. Native engines come next. |
| 2026 additions | Native hardening and platform baseline only. No regression list is carried over; failures found with real ghosts become new tests. |
| Verification | Demonstrate the supported subset and report results for available ghosts. The old 23-entry corpus is a source of candidates, not a mandatory parity gate. |
| Layout | Phone portrait/landscape, insets, IME, large fonts, usable standard Compose accessibility. No inherited exhaustive tablet/fold/RTL audit requirement. |

Remove the previous requirements for minimize/restore events, passive-mode
commands, anchors/nested-script choices, password-input extensions, surface
aliases/append/range syntax, nonrectangular collision shapes, and the full modern
input/audit matrix unless the baseline calls for them. Do not silently restore
these through an appeal to general compatibility.
Basic lifecycle correctness, safe storage, and native ownership remain engineering
constraints on the selected features, not a separate modernization feature backlog.

### Clarifications needed to make the baseline implementable

These are proposed resolutions of the baseline's open or conflicting statements:

1. External `VIEW`/`SEND` import remains out of v1. Use only the system document
   picker. No URL acquisition, updater, background queue, analytics, or service.
2. Timer Reference0 is OS uptime in whole hours, including sleep:
   `SystemClock.elapsedRealtime() / 3_600_000L`. Pause stops emission, and resume
   does not replay missed ticks. Do not use uptimeMillis(), which excludes sleep.
3. Validate the destination's descriptors before switching. The last-run ghost
   only changes after activation succeeds. If the new ghost fails to load after
   the outgoing dialogue and unload, show an error and activate the bundled
   Nanidroid ghost. It uses the built-in engine, so this fallback cannot fail on
   native load. If native ownership remains unresolved (unload failure, load status
   -2, or an exception leaving ownership uncertain), disable native ghosts until
   process restart; Activity recreation does not clear native globals. The bundled
   engine still works without native calls. No automatic native recovery loop.
4. Inline choices remain visible after text/script queue completion until selected
   or invalidated by replacement dialogue. The queue-drain balloon hiding rule
   applies only when no choice or input is awaiting a response. Activating a choice
   or link does not also toggle controls or dispatch a character touch; inert balloon
   taps may toggle controls as the baseline describes.
5. Native transport chooses request encoding; response decoding honors a valid
   response Charset header, otherwise falling back to the engine's response-time
   transport charset. YAYA is queried again after the request. Parse protocol
   framing separately from script text. Malformed responses yield no script;
   native ownership/load failures remain visible errors rather than empty dialogue.
6. Import has one unambiguous package root: prefer root install.txt, otherwise a
   unique descriptor one wrapper directory deep. Reject ambiguous/deeper-only
   packages. Validate directory as one safe path component, not just a nonempty
   value; reject path escapes, symlinks, duplicate/conflicting entries, existing
   targets including case collisions, and exceeded size/entry limits.
7. 'Wipe staging at startup' means only abandoned attempts under this installation's
   dedicated private staging root, before accepting new imports, without following
   links or touching published ghosts. Stage on the target filesystem; publish
   without replacing an existing target. Cleanup failure cannot turn successful
   publication into a retry that installs twice. Recreation reattaches to ongoing
   work; process death does not resume an import automatically.

Specific size/entry limits and gesture timing belong in the relevant milestone's
small implementation plan. The author dropped the 2026 regression list: real
ghosts are exercised against the new code, and failures within the supported
subset become new fixtures and tests. Unsupported features are reported without
automatically expanding scope.

## Lead and subagent workflow

Implementers receive this design, the supplied baseline, public protocol documents,
allowlisted data/native sources, and new code. They receive neither 2012 nor 2026
application source, old tests, Git history, old architecture documents, or
source-derived implementation instructions. The baseline is intentionally permitted
behavioral material; references in it do not grant access to the named old files.

Codex is the lead and owns task boundaries, integration, verification, and review.
The lead does not write replacement product code. Integration means merging and
wiring subagent output; a fix is sent back to the task's implementer, or a fresh
subagent gets a new task for it.
Use GPT-6-Sol (`gpt-6-sol`) subagents for bounded research, implementation, and
review tasks. Start a fresh subagent for each distinct task with `fork_turns: none`,
passing the baseline, design, relevant new interfaces, and explicit file ownership.
Reuse that subagent for fixes within its task, then rotate to a fresh subagent for
the next task. Implementation and review are separate tasks. Parallelize only
independent work with disjoint ownership and settled interfaces.

The lead and researchers may inspect old source to resolve behavioral questions.
Implementers work in a source-filtered package with a fresh AGENTS.md; do not
deliberately supply old application source, old test bodies, or architecture to
copy. Source-exposed reviewers return behavioral counterexamples, not patches
copied from old code. Public-protocol questions need no researcher relay. If an
implementer opens old 2012 or 2026 application source (recalling memory does not
count), it reports this. The lead then decides whether to keep that task's
output or discard it and redo the task with a fresh subagent.

The author explicitly accepts shared/injected memory between coding agents.
There is no memory canary, memory-disabling prerequisite, or requirement to retire
an agent merely because it recalls earlier Nanidroid work. Do not alter global
memory settings. Fresh task contexts limit inherited assumptions; they are not
proof of memory isolation. This is practical blind reconstruction from the
baseline, not a claim of a strict memory-isolated clean room. Old Java/Kotlin
source still must not be copied; the native/JNI and ghost-data exceptions remain.

The author's current instructions govern. The baseline defines product scope,
and this design defines the architecture and its explicit clarifications. Lead
task instructions operate within those boundaries and cannot widen them. Public
documentation explains the selected subset. Memory is background only.

Memory describes the 2026 modernization in detail (runtime classes, durable
import workflows, feature backlogs). That is exactly what this rewrite leaves
behind, so anything recalled from memory is out of scope unless the baseline or
this design includes it. When memory conflicts with a task, follow the task. If
the conflict looks like a real gap, report it to the lead, who takes scope
questions to the author. The fresh AGENTS.md repeats this rule.

This is the selected execution method, not an open choice between agent workflows.
Prepare a small plan for the first milestone after design review; later milestone
plans are written when their dependencies and evidence are available.

Keep one small native/data manifest and a subset/deviation checklist. The bundled
archive is allowed as ghost content, not application source. The 2012
`assets/nanidroid.zip` and current `src/main/assets/nanidroid.zip` have the same Git
blob ID, `f01abd7497107a075e7e6955741b11165b8bf277`. Record its transfer hash and
preserve its notices. Rewrite onboarding text; do not copy Java/Kotlin engine or
parser implementations from either reference.

Public references: [UKADOC](https://ssp.shillest.net/ukadoc/manual/index.html),
[SHIORI/3.0](https://ssp.shillest.net/ukadoc/manual/spec_shiori3.html),
[SakuraScript](https://ssp.shillest.net/ukadoc/manual/list_sakura_script.html),
[events](https://ssp.shillest.net/ukadoc/manual/list_shiori_event.html),
[surfaces](https://ssp.shillest.net/ukadoc/manual/descript_shell_surfaces.html), and
[descriptors](https://ssp.shillest.net/ukadoc/manual/descript_ghost.html).
They explain selected features; they do not expand the baseline to all of SSP.

## Android CLI and project setup

The author permits use of the `android-cli` skill for project creation, supported
module creation, Android documentation, SDK/device work, and UI verification.
This permission does not bypass the requested design review before scaffolding.

The installed `android create --help` confirms project creation with template,
name, output, and minSdk options. It does not establish a dedicated module command.
After review, inspect `android create --list` and the selected template's current
help before creating a project. A candidate invocation, subject to that check, is:

```text
android create empty-activity --name=Nanidroid --minSdk=31 --output=<new-project-directory>
```

Use a new standalone repository at `C:\work\src\nanidroid-recreation`, outside every
existing Git repository and worktree, never the existing source root or a directory
nested inside it. Implementers run from a Codex task whose workspace is that
repository. Generate fresh scaffolding and audit it against this design: Kotlin/Compose,
the exact application ID, API levels, version catalog, and native ABI/build rules.
Template defaults do not override these requirements. Keep the original app intact
while the new one is built. Copy only the approved native tree and ghost data.

Start with one application module. Module-creation permission does not itself add
modules to the architecture. If a concrete need later justifies a module, check
the installed CLI's supported commands/templates; use normal Gradle configuration
if it has no suitable generator. Do not invent a CLI module command.

Use `android docs` for current Android guidance, the Gradle wrapper for builds and
tests, and the CLI's documented device/run/layout/screen commands for verification
on a disposable emulator. Check command help before use. Install/update tooling
only when required by the chosen build; do not run blanket SDK or CLI updates.
No project, module, emulator, or SDK installation is part of this document revision.

## Architecture

One Activity hosts Compose. Screen-level ViewModels expose immutable StateFlow
state and receive actions; Compose collects with lifecycle awareness. ViewModels
hold no Activity, View, Resources, or native handles. Reusable controls use plain
state holders. Use constructor injection and one application composition root.
No DI framework, duplicate database catalog, or generic workflow engine is needed.

One application-owned runtime owns the selected ghost, FIFO script playback,
clock, pending interactions, and presentation state. A session identity rejects
late results/actions after switches. Native calls run on one dedicated OS thread;
UI coroutine cancellation does not imply native cancellation. The built-in Kotlin
engine follows the same behavioral interface without loading native libraries.
Recreation attaches to the existing runtime; it does not boot another ghost.

Keep script parsing/playback and surface interpretation independent of Compose.
The renderer consumes their state and uses the same geometry for drawing and hit
testing. Repositories own the file-backed library and small persistent settings
(last ghost, boot counts, onboarding). An application-lived importer owns its
single foreground attempt. SavedStateHandle holds small UI identities, not native
sessions, documents, or ghost trees. No extra manager/coordinator layer without
an actual distinct lifetime/resource or current duplication it removes.

These choices follow [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations).
The baseline's deliberate unsupported behavior remains unsupported even when a
library makes it easy to add more features.

For DLL-based candidates, YAYA selection follows the retained loader's config
discovery: a safe `yaya.txt` default, or a safe DLL containing the bytes
`yaya.dll` with its same-stem `.txt`. Bound DLL inspection and run it on the IO
dispatcher; cache installed-choice kinds for synchronous stage publication and
recompute them when the installed list refreshes. Built-in, Satori, and Kawari
precedence remains first. AYA5-only DLL/config pairs without that discovery use
the existing unsupported SHIORI and keep their own descriptor and shell. A
native load failure for a selected native engine still takes the separate
bundled-recovery path.

## Native copy contract

Copy `jni/**` at the pinned native revision with its CMake configuration and notices;
use [the native manifest](2026-09-22-native-copy-manifest.json). Write new thin Kotlin
bindings. All methods below are instance methods in `com.cattailsw.nanidroid.shiori`:

| Class | Library | Native methods |
| --- | --- | --- |
| Kawari | kawari8 | nativeLoad(String): int; nativeUnload(): boolean; requestFromJNI(byte[]): byte[] |
| SatoriShiori | satoriya | nativeLoad(String, String): int; nativeRequest(byte[]): byte[]; nativeUnload(): boolean |
| YayaShiori | yaya | nativeLoad(String, String): int; nativeTransportCharset(): String; nativeRequest(byte[]): byte[]; nativeUnload(): boolean |

Preserve binding identities if shrinking. Load status is 1 success, 0 failed/empty,
-1 existing owner; Satori/YAYA also return -2 for failed cleanup. Pending exceptions
still require handling. Never unload an existing owner because a second load
returned -1. Unload returns true when empty/successful and false on cleanup failure.
All three support repeated unload when empty. Requests without a loaded engine
throw; YAYA's charset query also rejects an unloaded engine. Native state is
process-global, not per wrapper object. Unresolved ownership disables further
native loads until process restart, as in clarification 3. Do not assume synchronous
native calls can be cancelled.

Satori/Kawari use Shift_JIS transport; descriptor CP932 policy does not change their
wire encoding. YAYA transport is queried before encoding and after execution;
`default`/`osnative` mean platform default and `binary` means ISO-8859-1. Satori
rejects an unusable dictionary and YAYA rejects a suppressed VM. Build satoriya,
ssu, kawari8, and yaya with retained CMake 3.22.1 configuration; verify availability
of CI's NDK 28.0.13004108 before pinning it. Preserve source-selection/linker rules.

## Milestones and evidence

Preparation is limited to the first slice: check package contents, CLI/template
capabilities, toolchain/device access, native/data manifests, and available fixtures.
Do not inventory every public tag or reproduce the old test suite before
coding. Later native milestones need usable
Satori/Kawari/YAYA fixtures; missing fixtures are explicit blockers to those claims.

1. **Walking skeleton:** bundled ghost with the pure Kotlin table engine, boot,
   text, static surfaces/balloons, touch to OnMouseClick, and rotation on a device
   or emulator. Produce an installable APK. No native engine gate.
2. **Native engines:** Satori, Kawari, and YAYA boot and talk; ghost selection,
   switching/recovery, and OnClose exit work. Add the script constructs required
   by selected fixtures rather than pretending a ghost works with ignored output.
3. **Import:** picker, safe validation/publication, install events, readme/switch
   prompt, and preservation of installed targets across failures/recreation.
4. **Surfaces/interactions:** complete the baseline's animations including
   always/runonce/move, inline choices, input, and links. Earlier milestones may
   implement required subsets; this milestone completes the declared subset.
5. **Polish:** exercise available real ghosts against this baseline, finish
   landscape/large-font/IME behavior and About/licenses, and verify the build.

Each milestone gets an estimate before execution. Reassess at twice that estimate;
do not silently extend the scope or start another rewrite. A checkpoint reports
working evidence, the concrete blocker, and the smallest revised next step.

Use focused pure Kotlin tests for the supported parser/state behavior, device tests
for native ownership and UI lifecycle, and visual inspection of actual milestone
flows. Test the importer for the cases in clarification 7: publication
interrupted before or after the rename, a duplicate result after recreation, and
cleanup that touches only owned staging. Do not add background continuation.
Basic accessible actions belong to the controls being built; exhaustive modern UI
matrices are not inherited requirements.

Final checks are debug build, local tests, lint, both-ABI packaging, and connected
checks for implemented flows. Record actual corpus inputs/outcomes: no fixed 23/23
claim, no claim that partial/unsupported ghosts are fully compatible, and no claim
that retained native crashes were fixed without evidence. Missing device or archive
coverage is reported explicitly. Documentation and old tests are evidence sources,
not proof of current execution.

Keep reference application code intact until the replacement is usable and
reviewable; then integrate it and remove superseded application code. Milestones
are useful deliverables, not permission to call a partial baseline complete.
Release/merge remains separate.
