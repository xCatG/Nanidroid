# Remove Shipped Debug UI and Traffic Capture

## Goal

Delete Nanidroid's shipped debug panel, debug restoration, legacy surface-dump
callbacks, and in-memory raw SHIORI traffic capture. Preserve all ghost-authored
collision behavior, the existing collision visualization/corpus seam,
accessibility actions, surface rendering, and SHIORI dispatch semantics. This is
a focused #382 PR; #384, #385, and #386 remain blocked.

## Reviewed boundary

Three independent read-only reviews inspected the exact `80ef56c2` baseline:
code/caller ownership, runtime/render compatibility, and adversarial test/visual
audit risk.

Delete atomically:

- `compose/debug/GhostDebugSurface.kt` and `DebugPanelState.kt`;
- `runtime/BoundedShioriLog.kt` and `Ghost.shioriLog` recording;
- the debug toolbar icon/action, localized debug strings, debug Bundle keys,
  pointer diagnostic projection, SHIORI collector, sample-script action, and
  adaptive debug presentation;
- obsolete surface enumeration/dump/dialog callbacks that feed no product path;
- debug-only tests, three obsolete debug visual fixtures/goldens, and their audit/docs
  expectations.

Preserve exactly:

- collision parsing, authored ordering, shape containment, hit testing, named
  collision IDs, pointer routing, accessibility custom actions, and structured
  corpus evidence;
- `CollisionOverlay`, its stage-renderer plumbing, the
  `collision_shapes_combined` screenshot, and the current corpus visualization
  path. Their later removal is a separate #382 slice gated by connected tests
  and the exact 23-NAR comparison;
- ordinary image rendering, stage measurement, transforms, keyboard activation,
  and accessibility omission diagnostics;
- SHIORI request construction, queue/passive/pinned-session behavior, response
  parsing and insertion;
- toolbar visibility restoration, including pending restoration while startup
  is still loading;
- Android warning/error Logcat diagnostics unrelated to the removed raw traffic
  history;
- every installer, update, durable, service, JNI, and runtime-ownership boundary.

## Implementation

1. Convert `TransientUiSnapshot` and its Bundle helpers/tests to toolbar-only
   state. Keep the present sentinel so saved `false` remains distinct from no
   saved snapshot, and keep pending-state precedence across a second save.
2. In `Nanidroid`, replace the sole Activity surface-input callback with exactly
   one `runner?.dispatchSurfaceInteraction(effect)` call. Remove only Activity
   diagnostic projection/UI state. Do not refactor
   `dispatchSurfaceInteractionWithDiagnostics` or `SurfaceInteractionDispatchResult`;
   those stay behind the production wrapper until #385.
3. Remove the debug build probe, panel/log jobs, sample action, collision-overlay
   state, debug composition, and dead callbacks/fields (`onNextGhost`,
   `onNextSurface`, `onAnimate`, `pickNextAnimation`, `onShowCollision`,
   `runClick`, `narTest`, surface-key state/setup, animation placeholders).
4. Remove `NanidroidSimpleDialog.DebugMessage`, `SurfaceManager.dumpSurfaces`,
   the `ShellSurface` dump-format helpers, and unused `Setup.DLG_DBG_MSG`.
5. Delete `BoundedShioriLog`; remove only its field and append block from
   `Ghost.sendRequest`. Rewrite `GhostShioriTrafficTest` as a recording-SHIORI
   request/response behavior test so exact GET/ID/reference construction and the
   returned parsed response remain executable contracts.
6. Remove debug icon/callback/transient-overlay APIs from
   `NanidroidComposeShell`. Retain simple-dialog saveable state and durable
   prompt behavior.
7. Stop the Activity from supplying debug overlay state, but retain
   `collisionOverlaySpeaker`, `showCollisionOverlay`, `CollisionOverlay`, their
   geometry/projection machinery, `overlayTransform`, tests, screenshot fixture,
   and corpus callers unchanged for the separately gated overlay slice. Remove
   only the now-unreferenced `StageSurfaceSnapshot.debugTransform` alias and its
   direct debug assertion; retain renderer/pointer/semantics aliases.
8. Remove the 51 `debug_*` strings in each locale, the bug-report vector, and
   no other collision strings. `stage_collision_*` resources remain core.
9. Delete/move only debug-specific test assertions. Preserve and run named
    collision protocol, ordering, transform, semantics, accessibility,
    restoration, and corpus field assertions.
10. Recut the visual catalog atomically:
    - remove only `debug_bottom_sheet`, `debug_full_modal`, and
      `debug_side_panel` fixtures/previews/reference PNGs (349,444 bytes);
    - retain `collision_shapes_combined` unchanged;
    - replace `StageFixtureState.debug` with a screenshot-only
      `collisionOverlaySpeaker: SurfaceSpeaker? = null`, set to `SAKURA` only
      for `collision_shapes_combined`, and pass it directly through the retained
      renderer seam; remove `DEBUG_MODAL`;
    - change screenshot fixture count from 34 to 31 and product-state count
      from 19 to 16;
    - bump the visual case-set version;
    - set full UI audit count to 64 and update the audit checklist/docs;
    - leave the corpus overlay toggles, transform access, structured fields, and
      named-collision probes unchanged.

## Verification

Run on the exact committed head:

- focused toolbar restoration, Ghost request/response, collision parser/router,
  surface interaction protocol, stage transform, semantics, accessibility, and
  screenshot fixture contract tests;
- `testDebugUnitTest compileDebugAndroidTestKotlin
  compileDebugScreenshotTestKotlin assembleDebug` for both native ABIs;
- `lint`;
- `validateDebugScreenshotTest` with exactly 31 passing cases and no regenerated
  non-debug golden accepted without visual inspection;
- retain the `collision_shapes_combined` reference byte-for-byte with SHA-256
  `896f79fd2e0296b060e46c81db57ab8d6880cdf536eb87a45bd2bfff64eeb7e8`;
- `pwsh -NoProfile -ExecutionPolicy Bypass -File
  scripts/run-ui-visual-audit.ps1 -DryRun -HostSelfTest` with 64 cases;
- `python tools/verify_phase1_shipped_state_audit.py`;
- `git diff --check` and whole-tree hygiene proving no shipped debug panel,
  traffic log, Activity-owned debug overlay state, debug restoration key, debug
  toolbar resource/tag, or obsolete callback remains.

If a device is available, run connected tests. The corpus harness, collision
renderer, and exact 23-NAR fields are deliberately unchanged in this narrowed
slice; their evidence becomes a mandatory merge gate for the later overlay
removal PR, not an availability-based exception.

## Stop conditions

Stop the PR if a named collision becomes a generic surface click; collision
ordering/identifier/references change; surface dispatch is skipped or doubled;
Kero/passive/pinned-session behavior changes; stage measurement, image
visibility, pointer input, semantics, keyboard/accessibility activation, or
toolbar restoration regresses; structured corpus collision evidence weakens;
non-debug screenshot pixels change without explanation; or the diff reaches
installer, update/durable/service, JNI, runtime ownership, or ViewModel work.
