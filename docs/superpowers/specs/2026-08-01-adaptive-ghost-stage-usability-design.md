# Adaptive Ghost Stage and Usability Design

**Status:** Approved
**Date:** 2026-08-01

## Summary

Nanidroid's ghost stage currently renders Sakura and Kero close to their raw PNG
pixel dimensions. That leaves them unusually small on high-density phones,
landscape windows, and tablets. The same stage also couples a character tap to
toolbar visibility, rejects valid collision files in some real ghosts, renders
some SakuraScript control text literally, and exposes a second row of legacy
debug buttons whose collision control is a no-op.

This design replaces raw-pixel placement with a bounded adaptive stage, gives
portrait and tall windows two speaker lanes, gives compact-height landscape
windows a three-column character/dialogue/character arrangement, and makes one
render transform authoritative for drawing, input, and debug bounds. It also
reorganizes the app chrome, hardens surface parsing, and adds a representative
UI and interaction test matrix.

## Audit Evidence

The design is based on an API 37 emulator audit of the current `master` build
across phone and tablet-sized portrait and landscape windows.

- `GhostStageLayoutPolicy` only scales surfaces down. It never scales them up,
  and the Compose host converts the resulting pixels directly to dp-equivalent
  placement. The bundled 250 x 400 Sakura therefore occupies only a small part
  of modern screens.
- The audited archives included the bundled ghost, 2elf, Yes Man, tewire-sen,
  Big Red Button, Bancho, Earthquake Rescue Duo, Nanika Atsume, Snake/Otacon,
  and other pcPets packages. Representative default surface pairs ranged from
  1 x 1 placeholders through 772 x 535 artwork. A single fixed scale would not
  preserve usability across this range.
- A face tap on 2elf produced dialogue, proving that the Compose pointer path
  can reach SHIORI when collision metadata loads.
- Snake/Otacon's valid `surfaces.txt` places a comment between `surface0` and
  `{`. The current parser expects `{` immediately, aborts the parse, and loses
  every authored collision. The pointer still reaches YAYA, but it has no
  collision name and appears inert.
- The current Compose interaction effect keeps only the numeric collision ID.
  Real ghost scripts commonly compare SHIORI `Reference4` with authored names
  such as `Head` and `Face`, so a successfully parsed collision can still be
  unusable if its name is discarded before dispatch.
- The current `draw CBox` button calls an empty callback. Other debug controls
  retain legacy behavior that is disconnected from the Compose presentation
  pipeline.
- Some SakuraScript control content, including passivemode fragments, is
  displayed as dialogue instead of being consumed.
- The existing JVM suite and all 18 connected tests pass, but they do not cover
  an adaptive screenshot matrix, a real-world surface parser fixture, or an
  end-to-end rendered-collision interaction.

## Goals

1. Make Sakura and Kero visually prominent without cropping or distorting
   authored surfaces.
2. Adapt to the current window rather than a device label, including rotation,
   split screen, foldables, and desktop-sized Android windows.
3. Give compact-height landscape a stable three-column layout with both bubble
   regions in the center.
4. Make drawing, hit-testing, pointer conversion, and collision diagnostics use
   the exact same transform.
5. Restore reliable touch and mouse-click interaction for real ghost packages.
6. Replace the second debug toolbar row with organized, adaptive debug tools.
7. Add automated coverage proportional to the real surface and viewport range.

## Non-Goals

- Continuous mouse hover/petting (`OnMouseMove`), wheel, drag, right-click, and
  other extended pointing-device events are deferred. This design preserves an
  input-source-neutral boundary so they can be added without another layout
  rewrite.
- Rendering third-party balloon skin packages is separate from making the
  current dialogue surface adaptive and interactive.
- Archive download and installation queue behavior is outside this stage/UI
  change.
- A project-wide Navigation 3 migration is not required because this work does
  not introduce a new destination or a list-detail relationship.
- Transparent margins are not cropped from surface images. Collision
  coordinates are authored against the complete intrinsic canvas.

## Window Classification

Classification uses the stage's available dp size after system insets and the
single app bar have been removed.

`isWide` is true when `width >= height * 1.2`.

Evaluation order:

1. **Tiny wide window:** `isWide` and either width is below 420 dp or height is
   below 240 dp.
2. **Tiny tall window:** not `isWide` and either width is below 240 dp or height
   is below 320 dp.
3. **Compact-height landscape:** `isWide`, width is at least 420 dp, and height
   is from 240 dp up to but not including 480 dp.
4. **Standard/tall:** every remaining supported window, including phones in
   portrait and tablets or foldables with at least 480 dp of stage height.

Tiny windows show this centered message instead of overlapping or clipping
content:

> This window is too small for Nanidroid. Make it a little bigger 💦

The fallback remains inside the ordinary app shell so the user can resize,
rotate, or leave the app normally.

## Stage Layout

### Standard and Tall Windows

The stage uses two equal speaker lanes, Kero on the start side and Sakura on the
end side. On expanded windows the two-lane content is centered and capped at
960 dp wide; outer space is intentional rather than stretching characters to
tablet edges.

Each speaker's dialogue is anchored within that speaker's lane. Characters are
bottom-aligned and aspect-fit. Their normal visual region is capped at 64% of
the available stage height so dialogue retains usable space. A dialogue cell
may overlap unused transparent stage area but must not obscure the other
speaker's interactive surface.

### Compact-Height Landscape

The stage uses three columns:

```text
| Kero | Kero bubble / Sakura bubble | Sakura |
```

The outer character lanes have a 120 dp minimum, and the center dialogue lane
has a 180 dp minimum. From 420 dp through 540 dp, the center remains 180 dp and
the remaining width is divided equally between the outer lanes. At 540 dp and
above, all three lanes receive equal weight. This yields the user's one-third
layout on ordinary landscape phones while retaining minimum usability at the
420 dp fallback boundary.

The center column is split into two fixed half-height cells:

- The upper Kero bubble points toward the start-side Kero lane.
- The lower Sakura bubble points toward the end-side Sakura lane.
- An absent bubble leaves its half reserved. The other bubble never expands,
  so dialogue and characters do not jump when speakers alternate.
- Long content scrolls inside its half while the bubble frame and pointer stay
  fixed.

Characters may use the full compact-landscape stage height, remain
bottom-aligned, and never crop.

## Hybrid Surface Sizing

The layout preserves authored relative size where it remains usable, but does
not leave valid companions microscopic.

For each surface:

1. Calculate its maximum uniform aspect-fit scale inside its speaker lane and
   height cap.
2. Use the lower of Sakura's and Kero's maximum scales as the shared authored
   scale.
3. For a non-placeholder surface, independently increase that scale only when
   its rendered shorter side would be below 96 dp. Never exceed its lane fit.
4. Treat a surface with an intrinsic width or height of 8 px or less as an
   intentional placeholder; do not apply the 96 dp prominence floor.
5. Preserve the full intrinsic canvas, aspect ratio, and bottom alignment.

The result preserves normal Sakura/Kero size differences, raises genuinely
small visible companions to a usable minimum, and avoids turning 1 x 1 sentinel
surfaces into giant blocks.

## Rendering and Coordinate Transform

Layout produces a `SurfaceTransform` for each visible speaker. It contains the
intrinsic surface size, rendered rectangle, uniform scale, and stage-relative
origin.

The same instance is consumed by:

- surface compositing;
- collision-region drawing;
- viewport-to-surface pointer conversion;
- collision hit-testing;
- bubble-to-speaker anchoring; and
- debug coordinate reporting.

No caller independently reconstructs scale or offset. This is the central
invariant that prevents adaptive rendering and input from drifting apart.

## Input and Dialogue Interaction

### Stage Input

- A touch tap anywhere inside a rendered surface canvas dispatches one
  `OnMouseDoubleClick`, preserving Nanidroid's existing mobile convention.
  Named collision regions win over generic canvas hits, including over
  transparent pixels.
- A mouse primary click dispatches `OnMouseClick`; a physical double-click
  dispatches `OnMouseDoubleClick`.
- Events carry the source speaker, mapped intrinsic coordinates, button, input
  source, numeric collision ID for diagnostics, and authored collision name.
  The runtime sends the collision name as SHIORI `Reference4`; if the name is
  absent, it falls back to the numeric ID string.
- A surface tap does not alter app-bar visibility.
- A tap or click on empty stage space toggles the app chrome.
- A hit outside named collisions but inside the rendered canvas still
  dispatches the generic surface event, including on transparent padding.
- Bubble links, choices, and scrolling consume their own pointer events before
  the stage can handle them.

The internal input model distinguishes touch and mouse even though continuous
mouse movement and wheel dispatch are deferred.

### Surface Parsing

`SurfaceReader` accepts blank lines and comment lines between a surface selector
and its opening brace. Parsing is block-resilient: a malformed block records a
diagnostic and is skipped without discarding later surfaces or collisions.

If an affected surface has no usable collision data, generic surface input
continues to work. If a speaker has no usable surface at all, only that speaker
shows a placeholder.

### SakuraScript and Bubbles

- Supported speaker, surface, balloon, choice, link, wait, newline, and clear
  commands retain their current meaning.
- Recognized but unsupported control commands are consumed and logged in debug
  builds rather than rendered as visible text.
- Unknown malformed commands cannot terminate presentation of later valid text.
- Choices and links remain accessible and directly tappable inside the adaptive
  dialogue cell.

## App Chrome and Debug Tools

The normal UI has one Material app-bar row:

- `Ghosts` remains the primary labeled action.
- A bug icon appears only in debuggable builds.
- Check updates, Readme, and Preferences move to the overflow menu.

The bug icon opens an adaptive debug surface:

- portrait: modal bottom sheet;
- compact-height landscape: replaces the center dialogue column temporarily,
  leaving both characters visible;
- tablet/tall expanded window: side panel.

Debug controls are grouped by purpose:

1. **Surface:** selected speaker, current surface ID, previous/next surface, and
   animation diagnostics.
2. **Collision and input:** collision overlay switch, latest viewport and
   intrinsic coordinates, speaker, collision name, button, and input source.
3. **Runtime tools:** NAR test and a compact recent SHIORI event/response log.

The collision overlay outlines authored regions with labels and a subtle
translucent fill. It uses `SurfaceTransform`; it is not a second placement
implementation. Legacy callbacks that no longer affect the Compose renderer,
including the current no-op collision control, are removed instead of being
preserved behind new labels.

## Component Boundaries

### `GhostStageLayoutPolicy`

Pure Kotlin policy that classifies the window, calculates lanes and bubble
cells, applies hybrid sizing, and returns placements plus `SurfaceTransform`s.
It does not render Compose UI or dispatch runtime events.

### Presentation Stage

Consumes the policy result to render surfaces, dialogue, the tiny-window
fallback, and the debug overlay. It owns no independent coordinate math.

### `SurfaceInputDispatcher`

Accepts pointer-neutral actions and a `SurfaceTransform`, performs hit-testing,
and emits a typed interaction effect. A runtime adapter translates that effect
to the appropriate SHIORI event.

### `SurfaceReader`

Parses surface blocks and records recoverable diagnostics. It remains separate
from viewport layout and Compose rendering.

### App Shell and Debug State

Owns one-row chrome, overflow actions, debug-panel visibility, and diagnostic
selection. Debug state observes the presentation/input pipeline rather than
maintaining a parallel legacy surface state.

## Error Handling

- Tiny windows render the resize message rather than a partially clipped stage.
- A missing or unreadable surface affects only its speaker and reports a debug
  diagnostic.
- A malformed surface block is skipped; later blocks remain available.
- Invalid collision regions are omitted individually and reported.
- Unsupported SakuraScript controls are omitted from dialogue and reported in
  debug builds.
- Runtime event failures retain the current visible frame and appear in the
  debug event log; they do not clear both bubbles or crash the stage.

## Verification Strategy

### Pure JVM Tests

Parameterize layout and transform behavior over these representative stage
sizes in dp:

- 360 x 720 phone portrait;
- 720 x 360 compact landscape;
- 400 x 1000 tall phone;
- 610 x 500 short foldable or multi-window;
- 800 x 1280 tablet portrait;
- 1280 x 800 tablet landscape;
- 480 x 230 tiny wide fallback; and
- 230 x 400 tiny tall fallback.

Exercise representative surface pairs from the audit:

- 250 x 400 and 235 x 200;
- 270 x 378 and 239 x 380;
- 427 x 640 and 1 x 1;
- 210 x 140 and 210 x 140;
- 772 x 535 and 422 x 377;
- 93 x 95 and 200 x 200;
- 450 x 750 and 450 x 750; and
- 300 x 501 and 210 x 420.

Assertions cover classification, minimum lanes, max stage width, no crop,
aspect ratio, bottom alignment, placeholder handling, fixed bubble halves,
forward and inverse coordinate mapping, and debug-bound equality.

Parser fixtures include Snake/Otacon's comment-before-brace syntax, blank lines,
one malformed block followed by a valid block, and invalid individual collision
regions.

### Compose Screenshot Tests

Add Compose Preview Screenshot Testing for the major phone, foldable, tablet,
and desktop-like windows. Cover:

- standard two-lane layout;
- compact-landscape three-column layout;
- tablet bounded stage;
- one bubble, two bubbles, and long scrolling dialogue;
- debug panel in each adaptive presentation;
- collision overlay;
- tiny-window fallback;
- light and dark appearance; and
- font scale 1.0 and 1.5.

Reference images are reviewed rather than updated blindly.

### Interaction and Instrumentation Tests

Create a deterministic fixture ghost with known intrinsic sizes, collision
regions, dialogue, link, and choice responses. Verify:

1. a rendered collision tap produces the exact intrinsic coordinate, speaker,
   collision ID, authored collision name in `Reference4`, source, and SHIORI
   event;
2. a generic surface tap works outside named collisions;
3. an empty-stage tap toggles chrome without dispatching to the ghost;
4. bubble links, choices, and scrolling do not leak to the stage;
5. mouse single- and double-click actions remain distinct;
6. rotation and adaptive scaling keep the debug overlay and hit target aligned;
   and
7. a choice response updates the correct fixed bubble cell.

The existing JVM suite, lint, debug build, and connected instrumentation suite
must remain green.

## Acceptance Criteria

- Sakura and Kero use their allocated lanes prominently on portrait phones.
- Compact-height landscape displays Kero, two fixed center bubble cells, and
  Sakura without clipping at every supported size.
- Tablet and tall landscape windows return to a centered two-lane stage no wider
  than 960 dp.
- Every rendered collision outline matches its active hit target after scaling
  and rotation.
- The Snake/Otacon parser fixture loads authored collisions and responds to its
  expected interaction.
- Surface taps never toggle the app bar; empty-stage taps do.
- Bubble choices and links work and unsupported control text is not visible.
- Debug tools occupy one adaptive panel rather than a second toolbar row, and
  every exposed control affects the Compose presentation or diagnostics.
- Tiny windows show the agreed resize message.
- Automated layout, parser, screenshot, and end-to-end interaction coverage is
  present for the representative matrix above.
