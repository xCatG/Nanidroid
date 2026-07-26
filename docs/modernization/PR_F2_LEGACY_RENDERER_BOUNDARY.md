# PR F2: Legacy renderer boundary

## Purpose

This slice removes Android View mutation from `SScriptRunner`.  The runner now
emits an immutable `GhostPresentationFrame`; `GhostPresentationRenderer` is
the sole rendering boundary; and `LegacyGhostPresentationRenderer` preserves
the existing `SakuraView`, `KeroView`, `Balloon`, layout, and animation
behavior behind that boundary.

The frame owns the legacy rule that a balloon is visible when its balloon id
is not `-1` or its text is non-empty.  Animation ids remain one-shot commands:
they are dispatched to the renderer and are cleared only after dispatch.

## Why this is still Java

The frozen Ant compatibility build compiles the historical Java source tree
and cannot resolve Kotlin symbols.  Keeping this adapter Java lets the same
runtime continue to pass the Ant reference lane while the pure Kotlin
presentation contract introduced in F1 is characterized in the Gradle lane.
This is a temporary compatibility seam, not the target architecture:

1. characterize the script-runtime-to-frame trace;
2. make the Kotlin runtime emit the presentation state and commands;
3. replace `LegacyGhostPresentationRenderer` with a Compose renderer;
4. remove the legacy View adapter and frozen Ant lane when the modern path has
   equivalent emulator and release-artifact evidence.

## Validation

* `GhostPresentationFrameTest` locks the balloon-visibility policy without an
  Android UI dependency.
* `SScriptRunnerPresentationTest` uses a fake renderer to lock the ordered
  text/surface/one-shot-animation and end-of-script frame trace without
  Android views or native libraries.
* The focused Gradle JVM tests pass with only isolated-worktree native
  packaging preflights skipped; the pinned Docker legacy Ant/APK lane passes.
  Hosted CI runs the complete native, API-37 APK/AAB, and artifact pipeline.
