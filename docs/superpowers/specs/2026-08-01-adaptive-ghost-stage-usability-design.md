# Adaptive Ghost Stage and Usability Design

**Status:** Approved for implementation planning

**Date:** 2026-08-01

**Revision:** 2026-08-02 Japanese protocol correction after plan review

## Summary

Nanidroid's ghost stage currently renders Sakura and Kero close to their raw PNG
pixel dimensions. That leaves them unusually small on high-density phones,
landscape windows, and tablets. The same stage couples a character tap to toolbar
visibility, rejects valid surface grammar used by real ghosts, discards authored
collision names before SHIORI dispatch, renders some SakuraScript controls
literally, and exposes a second row of legacy debug buttons whose collision
control is a no-op.

This design replaces raw-pixel placement with a bounded adaptive stage. Portrait
and tall windows use two speaker lanes. Compact-height landscape uses the agreed
three-column character/dialogue/character arrangement. One measured transform is
authoritative for rendering, input, collision overlays, and diagnostics.

The review pass retains those layout decisions and strengthens the compatibility
contract around stable window classification, shell parsing, collision geometry,
SHIORI references, SakuraScript actions, pointer sources, accessibility, recovery,
durable-operation escape hatches, and deterministic verification.

## Audit and Review Evidence

The design is based on an API 37 emulator audit of the current `master` build
across phone and tablet-sized portrait and landscape windows.

- `GhostStageLayoutPolicy` only scales surfaces down. It never scales them up,
  and the Compose host converts the resulting pixels directly to dp-equivalent
  placement. The bundled 250 x 400 Sakura therefore occupies only a small part
  of modern screens.
- The audited archives included the bundled ghost, 2elf, Yes Man, tewire-sen,
  Big Red Button, Bancho, Earthquake Rescue Duo, Nanika Atsume, Snake/Otacon,
  and other pcPets packages. Representative default surface pairs ranged from
  1 x 1 placeholders through 772 x 535 artwork.
- A face tap on 2elf produced dialogue, proving that the Compose pointer path
  can reach SHIORI when collision metadata loads.
- Snake/Otacon uses comments before braces, comma selectors, ranges,
  `surface.append`, anchors, passivemode, and structured input-box commands.
- Nanika Atsume uses ranges and exclusions. Bancho declares its primary hit
  regions through `surface.append` and polygon `collisionex` entries.
- The current parser hardcodes Shift-JIS, maps unparsed selector tokens to
  surface 0, stops after some brace errors, and cannot represent extended
  collision shapes.
- The current Compose interaction effect keeps only the numeric collision ID.
  Real scripts compare SHIORI `Reference4` with authored identifiers such as
  `Head` and `Face`.
- The current `draw CBox` callback is empty. Other debug controls retain legacy
  behavior disconnected from the Compose presentation pipeline.
- The existing JVM suite and all 18 connected tests pass, but they do not cover
  adaptive screenshots, the audited selector/collision corpus, accessibility,
  or end-to-end rendered-boundary interaction.

The specification was then reviewed independently by Claude, agy, a Codex
adversarial reviewer, and constructive Ghost/Nanika, Android adaptive UI, and
usability/QA specialists. Cross-validated findings were checked against the
code, downloaded corpus, and official UKADOC references before inclusion.

## Goals

1. Make Sakura and Kero visually prominent within their assigned halves without
   cropping or distorting authored surfaces.
2. Adapt to the current safe window rather than a device label, including
   rotation, split screen, flat foldables, and desktop-sized Android windows.
3. Give compact-height landscape a stable three-column layout with both bubble
   regions in the center.
4. Make drawing, hit-testing, pointer conversion, and collision diagnostics use
   the exact same measured transform.
5. Restore reliable touch, mouse, pen, and eraser click interaction for real
   ghost packages while retaining the input source in SHIORI `Reference6`.
6. Replace the second debug toolbar row with organized adaptive debug tools.
7. Preserve structured choices, anchors, and input boxes through the adaptive
   bubble presentation and its extracted action surfaces.
8. Add automated coverage proportional to the real surface, grammar, pointer,
   accessibility, and viewport range.
9. Keep passivemode-compatible durable work from trapping the user when an
   update, download, copy, or install stops making progress.

## Non-Goals

- Continuous ghost hover/petting (`OnMouseMove`), SHIORI `OnMouseWheel`, drag,
  right-click, long-press menu emulation, and enter/leave events are deferred.
  Standard Compose wheel/trackpad scrolling remains enabled in scrollable UI.
  The input effect is still general enough to add ghost events without changing
  layout or coordinate contracts.
- Rendering third-party balloon skin packages is separate from making the
  current dialogue surface adaptive and interactive. Balloon selection commands
  are consumed and retained diagnostically but do not change the Material bubble
  skin in this slice.
- Scopes beyond Sakura (`0`) and Kero (`1`) do not gain additional visible lanes
  in this slice. Their handling is an explicit product decision below.
- Image-mask `collisionex ... region` and animation-scoped collisions are not in
  the proposed compatibility baseline. They are diagnosed rather than silently
  treated as rectangles.
- Redesigning archive discovery, queue ordering, retry policy, or download
  management is outside this stage/UI change. The narrow passive-mode guard and
  stalled-operation cancellation/recovery contract are included because they
  determine whether the user can safely escape this UI state.
- A project-wide Navigation 3 migration is not required because this work does
  not introduce a new destination or list-detail relationship.
- Transparent margins are not cropped. Collision coordinates are authored
  against the complete intrinsic canvas.
- Characters do not bleed into the other speaker's half to satisfy a visual
  prominence target.

## Stable Window Environment and Classification

`StageEnvironment` contains:

- safe window width and height in dp after persistent system bars, display
  cutouts, and permanent occlusions;
- density and font scale;
- flat, book, or tabletop posture when available;
- separating or occluding display-feature rectangles;
- the canonical app-bar reservation; and
- available pointer and keyboard capabilities.

The classification size is stable: it reserves the canonical app-bar height
whether the bar is visible or hidden, and it excludes transient debug surfaces
and IME insets. Showing chrome, opening an input box, or opening debug tools must
not change the stage mode. IME insets reduce only the bubble/input viewport and
may make it scroll; they never trigger the tiny-window fallback.

Pointer capability is not pointer-event source. Capability may tune affordances,
but every dispatched event uses the source of that specific Compose pointer
event.

`isWide` is true when `width >= height * 1.2`.

Evaluation order:

1. **Tiny wide window:** `isWide` and either width is below 420 dp or height is
   below 240 dp.
2. **Tiny tall window:** not `isWide` and either width is below 240 dp or height
   is below 320 dp.
3. **Compact-height landscape:** `isWide`, width is at least 420 dp, and height
   is from 240 dp up to but not including 480 dp.
4. **Standard/tall:** every remaining supported window.

The 960 dp maximum content width applies to both standard/tall and very wide
compact-height stages, avoiding a discontinuity at 480 dp height.

Flat and non-occluding foldables follow the same rules. A separating hinge or
occlusion is never crossed by a character, bubble, or pointer target. Until a
dedicated two-pane foldable layout is designed, a separating book/tabletop
feature uses the largest safe connected region; if it cannot meet the applicable
lane minima, Nanidroid uses the tiny-window fallback. This intentionally narrows
the earlier broad foldable promise.

Tiny windows show this centered message:

> This window is too small for Nanidroid. Make it a little bigger 💦

The ordinary app shell remains visible and accessible. The hidden ghost stage
has no active semantics or pointer targets. Resizing above the boundary restores
the exact prior runtime frame and dialogue without rebooting the ghost.

## Stage Layout

### Standard and Tall Windows

The stage uses two equal physical speaker lanes: Kero remains on the left and
Sakura on the right in both LTR and RTL locales. Surrounding text and chrome may
mirror, but ghost positions and bubble pointers do not.

On expanded windows the two-lane content is centered and capped at 960 dp wide;
outer space is intentional. Each character is bottom-aligned, aspect-fit, and
confined to its lane. Its canvas may use at most 64% of stage height during
ordinary dialogue so its speaker's upper bubble region remains usable.

Each speaker owns a stable bubble cell inside its lane. The cell is above or
over the transparent part of its own canvas, never over the other speaker, an
authored collision, or visible artwork. The bubble consumes all pointer input
inside its frame, including plain text and padding. Interactive links and the
choice pop-out action have higher priority within that frame.

### Compact-Height Landscape

The stage uses three columns:

```text
| Kero | Kero bubble / Sakura bubble | Sakura |
```

The outer character lanes have a 120 dp minimum, and the center dialogue lane
has a 180 dp minimum. From 420 dp through 540 dp, the center remains 180 dp and
the remainder is divided equally between the outer lanes. At 540 dp and above,
all three lanes receive equal weight, subject to the 960 dp stage cap.

The center is split into fixed half-height cells:

- the upper Kero bubble points physically left;
- the lower Sakura bubble points physically right;
- an absent bubble leaves its half reserved but does not intercept input;
- the other bubble never expands into the reserved half; and
- long content scrolls inside its half while its frame and pointer stay fixed.

During incremental text output, a cell follows the newest text until the user
scrolls manually. Manual scrolling suspends auto-follow for that talk; the next
talk re-enables it.

Characters may use the full compact-height stage, remain bottom-aligned, never
crop, and remain inside their outer lanes.

## Hybrid Surface Sizing

Sizing preserves authored relative scale where usable while preventing visible
characters from remaining microscopic.

For each selected scope:

1. Composite the selected surface into its stable authored canvas, including
   elements and supported animation frames.
2. Classify the scope as hidden/placeholder only when the runtime explicitly
   selects a hidden surface, the speaker is absent, or the composed canvas has no
   visible pixels and no active collisions. Intrinsic dimensions alone never
   classify an opaque 8 x 8 sprite as a placeholder.
3. Exclude hidden/placeholders from the shared-scale calculation so an elongated
   or transparent sentinel cannot shrink the other character.
4. Calculate each visible surface's maximum uniform aspect-fit scale inside its
   lane and height cap, then start both at the lower maximum scale.
5. Use the composed visible-content bounds to raise a visible surface toward a
   96 dp shorter-side floor. The full canvas still determines fit and collision
   coordinates. The floor is best-effort and never causes crop, distortion,
   lane crossing, or more than a 2x independent boost over shared authored scale.
6. Preserve the full intrinsic canvas, aspect ratio, and bottom alignment.

Surface changes and animation frames do not make both characters pulse. Each
scope has a stable authored collision canvas for the selected surface. Animation
assets composite into it. A supported operation that genuinely changes that
canvas swaps rendering, hit-testing, overlay, and diagnostics atomically to a
single new transform.

## Rendering and Coordinate Contract

The pure policy returns logical dp lane and bubble placements. After Compose
measurement, the presentation layer materializes one immutable
`SurfaceTransformPx` per visible scope from the final rounded `IntRect`.

`SurfaceTransformPx` contains:

- intrinsic canvas width and height in authored integer pixels;
- one half-open rendered `IntRect` in stage-local physical pixels;
- the uniform dimensionless scale implied by those sizes; and
- stage/root translation needed for diagnostics.

Rules:

- dp-to-px rounding occurs exactly once during measure/layout;
- drawing, pointer routing, bubble anchoring, overlays, labels, and diagnostics
  consume the same transform instance and final `IntRect`;
- inverse mapping rejects points outside the half-open rendered rectangle;
- an accepted local point maps with `floor(local * intrinsic / rendered)` and is
  clamped only to the valid intrinsic canvas;
- no caller independently reconstructs scale or offset; and
- pointer handlers observe the latest transform atomically after resize,
  rotation, surface change, or recomposition.

Authored `collision` rectangles use start and end coordinates. They normalize
to an internal half-open rectangle that includes both authored endpoints:
`[min(start,end), max(start,end) + 1)`. Overlay paths and hit tests derive from
that normalized geometry. `collisionex` geometry retains its authored points.

## Surface Parsing and Collision Semantics

`SurfaceReader` becomes a line-oriented, recoverable parser rather than a set of
single-surface regular expressions.

### File and selector grammar

- Read `surfaces.txt` and `surfaces*.txt` in filename order.
- Honor a valid first-line `charset` declaration independently for each file.
  Without one, use validated UTF-8, then Windows-31J/Shift-JIS fallback. An
  undecodable file produces a per-file diagnostic without discarding other
  files.
- Permit indentation, blank lines, conventional `//` comment lines, and
  selector-line trailing comments used by audited ghosts.
- Accept audited inline opening-brace forms such as `surface30{` as a bounded
  compatibility extension and diagnose their noncanonical syntax.
- Support comma-separated IDs, inclusive ranges, `!` exclusions, and
  `surface.append` selectors.
- A normal `surface` block creates a missing target and adds its ordered entries
  to an existing target; repeated normal blocks do not discard unrelated prior
  entries. `surface.append` adds only to targets already established by a prior
  normal block or a `surface*.png`, never creates a target, and is not retroactive.
- An invalid token records its file, line, and text and is skipped. It never
  aliases data into surface 0.
- An exclusion remains excluded for that selector even if a later token names
  the same ID; filename ordering uses case-insensitive then ordinal tie-breaking.
- After a malformed selector or block, resynchronize at the next top-level
  selector. A missing brace, EOF, or invalid entry cannot discard later files or
  valid blocks.

### Collision model

The baseline supports:

- legacy rectangular `collisionN`;
- `collisionexN` rect, ellipse, circle, and polygon shapes; and
- per-file `descript` directives with `collision-sort` values `ascend`,
  `descend`, and `none`, retaining source-file boundaries and authored order for
  the default `none` behavior.

Named collision geometry wins over generic canvas input regardless of pixel
alpha. Overlap resolution follows the declared collision sort. Each region keeps
its numeric parser ID for diagnostics and its exact case-preserved authored
identifier for SHIORI.

An entry with invalid coordinates or no valid authored identifier is omitted
individually and diagnosed; valid sibling regions remain. Unsupported valid
shapes such as image-mask `region`, and animation-scoped collision entries, are
diagnosed explicitly rather than reclassified as malformed rectangles.

If a surface has no usable collision data, generic canvas input continues. If a
speaker has no usable visible surface, only that speaker is hidden/placeholder;
the other speaker and both dialogue states remain intact.

## Input and Dialogue Interaction

### Routing priority

One stage-level router resolves each pointer sequence in this order:

1. open modal or debug surface;
2. bubble choice, anchor, URL, input, or scrolling content;
3. noninteractive bubble frame, text, and padding;
4. authored surface collision;
5. generic rendered surface canvas; and
6. empty stage.

Nested Compose gesture consumption does not define behavior. A surface action
never toggles chrome. An empty-stage action toggles chrome without dispatching
SHIORI. A labeled semantics action also shows/hides controls so TalkBack,
Switch Access, keyboard, and D-pad users are not dependent on an unlabeled empty
space gesture.

The complete rendered canvas remains a generic surface target, including
transparent padding, except when its composed content is classified as a fully
hidden placeholder. A bubble may consume transparent canvas pixels behind its
own frame, but it cannot cover visible artwork or an authored collision.

This canvas-wide policy applies to Nanidroid's full-screen in-app stage. A
future Chromebook overlay or freeform desktop mode may need alpha-aware
click-through and must revisit this policy instead of inheriting it silently.

### Pointer effect and SHIORI references

The internal effect contains event kind, source scope, intrinsic point, button,
event-local pointer source, collision target, wheel delta, and diagnostic IDs.
It reserves event kinds for deferred move, enter/leave, wheel, drag, and hover.

`OnMouseClick` and `OnMouseDoubleClick` use:

| Reference | Value |
| --- | --- |
| `Reference0` | intrinsic x coordinate |
| `Reference1` | intrinsic y coordinate |
| `Reference2` | `0` for click events |
| `Reference3` | scope `0` for Sakura or `1` for Kero |
| `Reference4` | exact authored collision identifier, or empty for generic canvas |
| `Reference5` | primary `0`; other buttons only when their events are supported |
| `Reference6` | `touch`, `mouse`, `pen`, or `eraser` from the current event |

Numeric parser IDs and the `NO_COLLISION` sentinel never cross the SHIORI
boundary. A malformed collision without an authored identifier is not a valid
named target.

At ghost load and reload, Nanidroid builds a cached pointer-event capability
record. Prefer `Get_Supported_Events`; when that resource is unavailable, query
`Has_Event` for `OnMouseClick` and `OnMouseDoubleClick`. Only an explicit
declaration counts as supported or unsupported. A missing or malformed response
is `Unknown`. Capability discovery uses a dedicated raw-response path rather
than `getStringFromShiori`: a normal 204 can carry
`X-SSTP-PassThru-local`/`external` for `Get_Supported_Events` or
`X-SSTP-PassThru-Result: 0|1` for `Has_Event`. Header absence or malformed
values, not the 204 status itself, produce `Unknown`. `Has_Event` receives the
event ID in `Reference0` and queries local support. Never infer support from an
ordinary interaction response, never send both events for one gesture, and
never replay a gesture as a fallback: either can duplicate ghost-side effects.

Touch single-tap uses the complete capability table below. `S`, `U`, and `?`
mean supported, unsupported, and unknown:

| `OnMouseClick` | `OnMouseDoubleClick` | One touch tap |
| --- | --- | --- |
| `S` | any | `OnMouseClick` |
| `U` | `S` | `OnMouseDoubleClick` |
| `U` | `U` | no event |
| `U` | `?` | legacy `OnMouseDoubleClick` |
| `?` | `S` | `OnMouseDoubleClick` |
| `?` | `U` | `OnMouseClick` |
| `?` | `?` | legacy `OnMouseDoubleClick` |

Physical pointer policy for this slice:

- a mouse or pen/eraser primary single-click dispatches exactly one
  `OnMouseClick` after the platform double-click window expires unless click is
  explicitly unsupported, in which case it dispatches nothing;
- a recognized physical double-click dispatches exactly one
  `OnMouseDoubleClick` and suppresses the pending single-click unless double
  click is explicitly unsupported, in which case it dispatches nothing; and
- cancellation, slop outside the original surface/scope, or unsupported buttons
  dispatch nothing.

This exact-one-event policy avoids duplicate ghost responses. The touch rule
interprets the requested heuristic as "map a touch single-tap to double-click
when the ghost does not declare single-click but does declare double-click,"
as confirmed. The physical exact-one sequence is a deliberate Nanidroid product
policy approved for this slice, not a claim that it reproduces every SSP event
in the same order.

## SakuraScript and Bubble Actions

Each visible scope owns an ordered `DialogueContent` stream rather than a plain
string. Segments include text, newline, wait, clear, structured choice, anchor,
external URL, and input-box action. Speaker association and authored order are
never lost when choices are extracted.

- A parsed choice is a tagged action, not one generic `id`: `Normal(label, id,
  extraReferences)`, `DirectEvent(label, eventId, references)`, or
  `Script(label, sakuraScript)`. Its speaker is UI ownership metadata and is
  never appended to its SHIORI References.
- Each `\q[...]` action remains owned by the current speaker. Pending choices
  open in a large extracted action surface: a
  scrollable full-width dialog or sheet on compact/touch layouts and a capped
  centered dialog/popover on expanded layouts. Every row has a minimum 48 dp
  target. The speaker bubble exposes one 48 dp `Choose...` action while choices
  are pending so the action surface can be opened or reopened; the complete
  choice list is not squeezed into the bubble cell.
- Activating `Normal` first sends `OnChoiceSelectEx` with `Reference0 = label`,
  `Reference1 = id`, and authored extras in `Reference2+`; when that produces no
  talk script, it falls back to `OnChoiceSelect` with `Reference0 = id`.
  `DirectEvent` sends its authored `On...` event with arguments starting at
  `Reference0`. `Script` executes its SakuraScript locally and sends no choice
  event. Closing or recreating the host cannot silently discard a pending
  authored choice.
- `\_a[id,args...]label\_a` renders only `label` and retains quoted/empty
  arguments. A normal anchor sends `OnAnchorSelectEx(label,id,args...)` and
  falls back to `OnAnchorSelect(id)` only when no talk is returned; an `On...`
  ID sends only that direct event with authored arguments.
- External URLs remain distinct from ghost anchors and require an explicit user
  activation before leaving the app.
- `\![open,inputbox,...]` parses positional and named timeout/text/options,
  supplement, and repeated extra references. A normal submit sends
  `OnUserInput(id,value,supplement,extras...)`; an `On...` ID sends that event
  with `(value,supplement,extras...)`. Close/timeout sends
  `OnUserInputCancel(id,"close"|"timeout",supplement,extras...)`; only an
  unanswered timeout falls back to `OnUserInput(id,"timeout",supplement,extras...)`.
- Balanced `enter,passivemode` and `leave,passivemode` update explicit runtime
  state until leave or ghost termination. One runtime `canTalk` decision applies
  generally, not only to passive mode: idle sends `OnSecondChange` and
  `OnMinuteChange` with `GET` and `Reference3 = 1`; normal talk playback,
  pending-choice/input states, and passive mode send them with `NOTIFY` and
  `Reference3 = 0`, and ignore returned scripts. For both methods, `Reference0`
  is sleep-inclusive OS continuous uptime in whole hours (Android
  `SystemClock.elapsedRealtime()`), not process uptime or elapsed time since this
  runner or Activity started; References 1–3 retain the documented offscreen, overlap,
  and can-talk meanings. Passive choices do not time out, displayed dialogue
  does not disappear, and a surface response cannot break or replace the active
  passive sequence.
- One origin-aware passive user-action guard disables the Nanidroid-owned ghost
  switch, minimize, exit, network update, NAR import/install, and uninstall
  paths. Equivalent actions explicitly initiated by SakuraScript remain allowed.
  Entering passive mode never auto-cancels work already in progress; that work
  remains supervised and the user can invoke the specific safe-stop recovery
  flow below. Nanidroid does not claim to prevent Android system navigation or
  implement unrelated SSP desktop facilities. Authored choices, anchors, and
  input remain available so the sequence can leave passive mode.
- Recognized presentational commands that remain unsupported are consumed only
  after complete tokenization and are logged in debuggable builds.
- Unknown or truncated commands use a balanced-token recovery rule and cannot
  terminate later valid text.
- A scope selector above `1` never leaks control text or silently attributes
  tertiary dialogue to Sakura/Kero. The proposed behavior is to consume that
  scope's presentation with one bounded diagnostic until multi-scope UI is
  designed.

## Stalled Durable Operation Recovery

Passivemode prevents new user-originated ghost updates, archive imports/installs,
and uninstall work, but it never hides recovery for work that was already
active. Recovery controls are baseware safety controls, not ghost interactions,
and remain available while passive.

Each remote archive download, local archive copy/install, and ghost network
update publishes an operation ID, human-readable phase, monotonically increasing
progress value, and in-process monotonic `lastProgressAt`. A heartbeat is a real
phase transition or increase in bytes/items processed; recomposition, polling,
or repeating the same status is not progress.

When one specific active operation has no heartbeat for 30,000 ms, Nanidroid
shows a named stalled-operation prompt in the app and exposes the equivalent
action from its ongoing notification when backgrounded:

- `Keep waiting` dismisses the prompt and starts a new 30-second observation
  window without changing or restarting the operation;
- `Stop operation` records an idempotent durable cancellation request for only
  that operation and changes its visible state to `Stopping...`; and
- there is no countdown, implicit default, or automatic cancellation.

Cancellation is cooperative. It cancels the matching DownloadManager or
WorkManager job where applicable, closes owned network/file streams, and is
checked between bounded copy/extraction chunks and phase boundaries. A stalled
`Stopping...` state may expose diagnostics and `Keep waiting` again after 30
seconds, but Nanidroid does not force-kill its process or worker thread.

Fresh NAR install continues to use private staging and publishes only a verified
tree, so cancellation removes staging and leaves installed ghosts untouched.
Ghost network update must gain equivalent transactional safety before exposing
Stop: download and verify all candidate files outside the live ghost, record a
rollback/recovery journal, and then publish through a bounded commit phase.
Cancellation before commit deletes staging. Cancellation or process death during
commit is resolved from the journal by completing or rolling back before that
ghost can boot; it never leaves an unclassified partially updated state.

Durable operation phase and `CancelRequested` survive recreation. A recreated
active operation receives a fresh 30-second observation window to avoid a false
stall prompt; a persisted cancellation request is honored immediately. Terminal
states are `Completed`, `Failed`, and `Cancelled`, each with bounded diagnostics
and cleanup ownership.

## Accessibility

- Sakura and Kero each expose a localized semantic identity and generic activate
  action.
- Named collisions are exposed as logically ordered custom accessibility
  actions where practical. Accessibility activation dispatches the same typed
  effect as pointer input using the region's representative intrinsic point.
- Exact collision geometry is not inflated or falsified in the debug overlay.
- Choice rows and their bubble pop-out, anchors, URLs, input controls, overflow
  items, bug icon, and debug controls expose stable labels, roles, focus order,
  and keyboard/D-pad actions.
- Stalled-operation prompts name the affected operation and phase; `Keep
  waiting`, `Stop operation`, and diagnostic actions remain reachable through
  touch, keyboard/D-pad, and accessibility services while passive.
- Material chrome and bubble actions meet a 48 dp minimum target. Authored
  collision geometry remains exact; custom actions provide the accessible
  alternative for tiny authored regions.
- Dialogue uses a polite live region without announcing every typewriter
  character separately.
- Decorative collision overlays are excluded from the accessibility tree.
- The tiny fallback and hidden stage expose no invisible ghost actions.

## App Chrome and Debug Tools

The normal UI has one Material app-bar row:

- `Ghosts` remains the primary labeled action;
- a labeled bug icon appears only in debuggable builds; and
- Check updates, Readme, and Preferences move to the overflow menu.

Debug presentation uses deterministic predicates:

- compact-height landscape: full-stage modal debug overlay, preserving live
  ghost/bubble state behind it;
- standard/tall width below 840 dp: modal bottom sheet;
- standard/tall width at least 840 dp: capped side panel that does not alter the
  stage classification.

The compact overlay replaces the earlier proposed 180 dp center debug column;
that column is too narrow for readable logs and controls. This changes only the
debug-open state. The normal compact landscape mock remains `Kero | Kero/Sakura
bubbles | Sakura`, and closing debug returns to that unchanged arrangement.

Debug content is grouped by purpose:

1. **Surface:** selected scope, current surface ID, composed/intrinsic dimensions,
   visible-content bounds, and animation diagnostics. Surface ID is read-only;
   no previous/next override is exposed until override lifetime and SHIORI
   behavior are separately designed.
2. **Collision and input:** overlay switch, latest viewport/intrinsic coordinates,
   scope, authored identifier, diagnostic numeric ID, button, source, and event.
3. **Runtime tools:** NAR test plus a bounded recent SHIORI event/response log.

The log retains at most 100 events, truncates any single displayed request or
response to 64 KiB, and never stores it in saved-instance-state. Collision paths
and labels are cached by surface definition and transform. Image decoding and
compositing do not run on every dialogue-character recomposition.

Every exposed debug control has a table-driven test for its specific observable
state transition or runtime call. Release builds contain neither the bug action
nor a hidden focusable debug panel.

## Implementation Sequencing

The feature remains one user-facing design, but implementation is divided into
four dependency-ordered milestones with green tests and focused commits between
them:

1. **Durable-work safety:** operation identity/progress, stall observation,
   cooperative cancellation, transactional ghost updates, restart recovery, and
   deterministic cancellation tests.
2. **Compatibility foundation:** decoded surface files, selector expansion,
   ordered collision shapes, structured SakuraScript actions, exact SHIORI
   references, and pure parser/protocol fixtures.
3. **Adaptive stage:** stable environment classification, lane and bubble policy,
   optical sizing, measured pixel transforms, overlay/hit equality, pointer
   routing, and layout/property tests.
4. **Usability completion:** adaptive extracted bubble actions, accessibility
   semantics, adaptive debug surfaces, restoration/error behavior, screenshot
   goldens, and connected end-to-end coverage.

No milestone declares the feature complete independently. The foundation may be
merged behind existing presentation, but the adaptive stage is not released
without the usability-completion acceptance suite.

## Component Boundaries and State

### `GhostStageLayoutPolicy`

Pure Kotlin policy that classifies a stable `StageEnvironment`, calculates dp
lanes and bubble cells, and applies sizing. It does not render Compose UI,
materialize final pixel transforms, or dispatch runtime events.

### Presentation Stage

Measures policy placements, materializes `SurfaceTransformPx`, and renders
surfaces, structured dialogue, fallback, debug overlay, and semantics. It owns no
second coordinate implementation.

### `SurfaceInputDispatcher`

Accepts source-neutral pointer/semantic actions and the latest measured
transform, resolves collision geometry, and emits typed effects. A runtime
adapter maps effects to exact SHIORI references.

### `SurfaceReader`

Decodes and parses surface files into ordered typed definitions plus recoverable
diagnostics. It remains independent of window layout and Compose.

### App shell and restoration

The shell owns app-bar and debug visibility. Runtime/presentation state owns the
latest surfaces and dialogue. Saveable state retains app-bar visibility, debug
visibility, selected diagnostic scope, overlay switch, and each bubble's scroll
position. The runtime retains bounded diagnostics and republishes its latest
frame after recreation; bitmaps and logs are not serialized into a Bundle.

### `DurableOperationSupervisor`

One application-scoped owner observes operation identity, phase, real progress,
stall windows, and durable cancellation requests across DownloadManager,
WorkManager, and the transactional updater. Compose and notifications consume
its state but do not calculate stalls independently. The supervisor uses an
injectable monotonic clock for in-process decisions and restarts only the
observation window—not the underlying work—after process recreation.

## Error Handling

- Tiny windows render the fallback rather than a partially clipped stage.
- A missing, unreadable, undecodable, or zero-sized surface affects only its
  scope and records a bounded diagnostic.
- A malformed file/block/selector/entry does not discard later valid data.
- One invalid collision is omitted without losing valid siblings.
- Unsupported collision shapes and SakuraScript commands are named in
  diagnostics rather than misparsed as another supported construct.
- A failed SHIORI interaction retains the current frame and both bubble states,
  logs one bounded failure, and does not block the next successful event.
- A stalled operation never cancels automatically. A specific stop request is
  idempotent, does not affect other queued/running operations, and reaches a
  terminal state or remains visibly `Stopping...` with diagnostics.
- Cancellation and process death cannot expose partial NAR trees or an
  unclassified partially published ghost update; staging and recovery journals
  have explicit owners and bounded cleanup.
- Opening/closing IME, debug, or chrome never changes stage classification.

## Verification Strategy

### Pure JVM tests

Run every audited surface pair through every representative viewport for pure
classification/sizing invariants:

- 360 x 720 phone portrait;
- 720 x 360 compact landscape;
- 400 x 1000 tall phone;
- 610 x 500 short multi-window;
- 800 x 1280 tablet portrait;
- 1280 x 800 tablet landscape;
- 480 x 230 tiny wide; and
- 230 x 400 tiny tall.

Surface pairs:

- 250 x 400 and 235 x 200;
- 270 x 378 and 239 x 380;
- 427 x 640 and 1 x 1;
- 210 x 140 and 210 x 140;
- 772 x 535 and 422 x 377;
- 93 x 95 and 200 x 200, including Nanika's optical bounds;
- 450 x 750 and 450 x 750; and
- 300 x 501 and 210 x 420.

Add direct boundary cases immediately below, at, and above:

- 420 dp width; 240, 320, and 480 dp height;
- the `1.2` aspect ratio;
- 540 dp compact-lane transition; and
- 960 dp content cap.

Sizing cases include absent, hidden, zero-sized, fully transparent, transparent
with collisions, opaque 8 x 8, 8/9 px boundaries, elongated sentinels, extreme
aspect ratios, optical bounds, 96 dp floor conflicts, and surface changes that
must not move the other scope.

Transform/collision properties cover multiple densities, fractional logical
origins, final pixel rounding, every authored boundary, just-inside/outside
points, transparent canvas, overlapping regions, every supported shape,
rotation/resize freshness, and root-coordinate equality between the actual
drawn overlay and active hit target.

Parser fixtures cover:

- Snake/Otacon comment-before-brace, inline comments, comma lists, ranges,
  `surface.append`, anchors, passivemode, and input box;
- Nanika Atsume ranges and exclusions;
- Bancho polygon `collisionex` regions;
- UTF-8 and Shift-JIS non-ASCII identifiers;
- indented braces and entries;
- duplicate/accumulating/append-existing ordering and per-file `collision-sort`;
- a malformed selector that must not mutate surface 0; and
- malformed blocks/regions followed by multiple valid blocks.

### Durable-operation recovery tests

Use an injectable clock and fake DownloadManager/WorkManager/filesystem
boundaries to verify:

- no prompt at 29,999 ms without progress and one prompt at 30,000 ms;
- real byte/item/phase progress resets the window while repeated status does not;
- `Keep waiting` begins a new window and never restarts or cancels work;
- no timeout path cancels automatically;
- `Stop operation` is idempotent, names and cancels only its selected operation,
  persists across recreation, and remains available while passive;
- download, local copy, staged extraction, verification, pre-commit, commit, and
  cleanup cancellation paths reach the specified terminal/recovery state;
- process death before and during update publication deterministically rolls
  forward or back before ghost boot and preserves the previous usable ghost;
- a recreated active operation receives a fresh observation window while a
  recreated `CancelRequested` operation resumes stopping immediately; and
- a second 30-second stall while `Stopping...` exposes diagnostics without
  force-killing or silently changing terminal state.

### Screenshot tests

Use Compose Preview Screenshot Testing with deterministic in-memory shell and
bubble fixtures rather than installed user ghosts. The reviewed golden table
contains named cases rather than a Cartesian explosion:

- standard phone portrait, one and two bubbles;
- compact landscape, empty/one/two/long bubble states;
- tall phone;
- tablet portrait and landscape bounded stage;
- flat foldable and separating-feature fallback;
- tiny wide and tiny tall;
- debug bottom sheet, full-stage compact overlay, and side panel;
- named stalled-operation prompt in normal and passive states;
- collision overlay with rectangle, ellipse, and polygon; and
- LTR/RTL, light/dark, and font scales 1.0, 1.5, and 2.0 on selected cases.

CI runs `gradlew.bat validateDebugScreenshotTest`; it never runs the update task.
Baseline changes require reviewed image-diff artifacts. The repository's custom
JVM/device characterization allowlists are updated whenever tests are added,
removed, or moved. Because the screenshot plugin is experimental, its version is
pinned in the version catalog and upgrades require a deliberate golden review.

### Interaction, semantics, and instrumentation tests

Use a deterministic fixture ghost to verify:

1. exact SHIORI event and References 0–6 for touch, mouse, pen, and eraser,
   including click-only, double-only, both-declared, neither-declared, and
   unknown capabilities; capability fixtures cover 204 responses with each
   pass-through header, `0`, `1`, absent headers, and malformed values;
2. named hits use exact case-preserved `Reference4`, while generic canvas uses
   empty `Reference4` and never exposes `-1`;
3. rendered-edge taps agree with the actual overlay after scaling and rotation;
4. bubble padding/text/scrolling/actions never leak to surface or stage;
5. an absent reserved bubble half does not intercept input;
6. empty-stage input toggles chrome without dispatching SHIORI;
7. pointer cancellation, slop, single/double sequencing, and unsupported buttons;
8. choice pop-out and extracted rows retain UI speaker, label, ID, and all
   extended references with 48 dp targets; normal, extended, direct `On...`, and
   `script:` forms assert their exact dispatch and normal-choice fallback order;
9. anchors, external URLs, input submit/cancel, and passivemode behavior;
   separate idle, busy-talk, pending-choice/input, and passive timer tests assert
   request method, OS-uptime `Reference0`, References 1–3, response playback,
   persistence, non-breaking input, and origin-aware user-action guards;
10. semantics discovery and activation for surfaces, collisions, bubbles, chrome,
    debug controls, and fallback, including keyboard/D-pad activation;
11. every debug control's observable effect and release-build absence;
12. IME/chrome/debug visibility, rotation, and recreation preserve mode/state;
13. tiny fallback has no hidden interaction and restores the prior frame; and
14. parser/surface/SHIORI failures preserve the unaffected scope and next event.

Run JVM tests, `lint`, `assembleDebug`, `validateDebugScreenshotTest`, and the
connected instrumentation suite before implementation is considered complete.

## Acceptance Criteria

- Sakura and Kero prominently use their physical left/right halves on portrait
  phones without crop, distortion, lane crossing, or placeholder inflation.
- Compact-height landscape displays Kero, fixed upper/lower center bubbles, and
  Sakura without clipping or speaker-change jumps.
- Tablet and tall windows use a centered stage no wider than 960 dp.
- Chrome, IME, and debug visibility never change stage classification.
- Every supported collision overlay and active hit target share one measured
  transform and agree at rendered boundaries after scaling and rotation.
- Generic hits use an empty SHIORI `Reference4`; named hits use the exact authored
  identifier; numeric parser IDs remain diagnostic-only.
- Snake/Otacon, Nanika Atsume, and Bancho fixtures load the supported selector and
  collision baseline without mutating unintended surfaces.
- Surface actions never toggle chrome; bubble actions never leak; empty-stage
  actions never dispatch to the ghost.
- Extracted choices, inline anchors, and structured input boxes preserve all
  authored data, and unsupported control text is not visible.
- Accessibility services and keyboards can discover and activate the primary
  stage interactions without falsifying authored collision geometry.
- Debug tools occupy one adaptive surface, every exposed control works, and
  release builds expose none of them.
- A healthy long operation is never prompted merely for its total duration. One
  operation with no real progress for 30 seconds offers `Keep waiting` and a
  specific `Stop operation`, never cancels automatically, and remains recoverable
  while passive or after process recreation.
- Cancelling or losing the process during NAR install or ghost update never
  exposes a partial published tree; the prior ghost remains usable or the journal
  completes a classified commit before boot.
- Tiny windows show the agreed message, have no hidden ghost interaction, and
  restore the existing frame when resized.
- The named layout, grammar, screenshot, semantics, recovery, and end-to-end
  suites are present and green.

## Resolved Product Decisions

The user approved the following contracts. Implementation planning waits only
for final review of this written specification.

1. **Touch compatibility:** use declared ghost capabilities. A touch single-tap
   sends `OnMouseClick` when declared, maps to `OnMouseDoubleClick` when only
   double-click is declared, sends nothing when both are explicitly unsupported,
   and preserves legacy double-click behavior when capability metadata is
   unavailable. "Map single to double" means the ghost does not support
   single-click but does support double-click.
2. **Physical pointing-device sequencing:** delay a mouse/pen single until the
   double-click window closes, so a double-click produces only
   `OnMouseDoubleClick`, not preceding click responses.
3. **Transparent canvas:** keep the complete surface canvas tappable, including
   transparent padding, except fully hidden placeholders. Bubble bounds consume
   their overlap, and an explicit accessible control action restores chrome.
   This applies to the full-screen app; Chromebook overlay mode revisits it.
4. **Compact debug presentation:** replace the proposed 180 dp center debug
   column with a readable full-stage modal overlay; live stage state remains
   behind it. The normal center bubble column mock is unchanged.
5. **Additional scopes:** consume scopes `2+` with a bounded diagnostic rather
   than misattribute their dialogue. Rendering more than Sakura/Kero remains a
   later design.
6. **Collision compatibility boundary:** implement legacy rectangles plus
   `collisionex` rect/ellipse/circle/polygon now; defer image-mask `region` and
   animation-scoped collision definitions with explicit diagnostics.
7. **Bubble action architecture:** retain and improve extracted choices instead
   of forcing small inline targets. Compact/touch layouts use a large scrollable
   dialog or sheet; expanded layouts use a capped dialog/popover. A pending
   choice adds a 48 dp `Choose...` pop-out action to the correct speaker bubble.
   Structured parsing distinguishes normal/extended, direct `On...`, and
   `script:` actions, retains every argument, and follows the exact event and
   fallback order.
8. **Passivemode:** implement the Nanidroid-owned semantics needed to protect an
   authored passive sequence. The general runtime `canTalk` state, including
   ordinary busy talk and pending input as well as passive mode, selects
   `GET`/`Reference3 = 1` versus `NOTIFY`/`Reference3 = 0` and whether a timer
   response may play. Choices/dialogue persist, surface responses do not break
   the passive sequence, and an origin-aware guard disables owned ghost-switch,
   minimize, exit, update, import/install, and uninstall user actions while
   permitting SakuraScript-originated actions. Already-running durable work is
   supervised instead of cancelled automatically. After 30 seconds with no real
   progress, a prompt names the specific operation and offers `Keep waiting` or
   cooperative `Stop operation`; neither the stall threshold nor passivemode
   triggers cancellation by itself. Transactional staging/journaling prevents a
   partial published install or update. Do not suppress Android system navigation
   or claim unrelated SSP desktop features.

## Reference Contracts

- UKADOC SHIORI mouse, choice, anchor, and input events:
  <https://ssp.shillest.net/ukadoc/manual/list_shiori_event.html>
- UKADOC `surfaces.txt` selectors, charset, collision sort, and collision shapes:
  <https://ssp.shillest.net/ukadoc/manual/descript_shell_surfaces.html>
- UKADOC SakuraScript choices, anchors, input boxes, and controls:
  <https://ssp.shillest.net/ukadoc/manual/list_sakura_script.html>
- Android Compose Preview Screenshot Testing:
  <https://developer.android.com/studio/preview/compose-screenshot-testing>
