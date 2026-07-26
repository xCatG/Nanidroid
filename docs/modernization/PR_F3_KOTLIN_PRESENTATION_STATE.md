# PR F3: Kotlin Sakura Script presentation state

## Purpose

`SakuraScriptPresentationReducer` turns the presentation fields still owned by
the Java runner into immutable, UI-free Kotlin transitions. It models:

* active Sakura/Kero speaker and synchronized text;
* text append and active-speaker clearing;
* surfaces, explicit balloon ids, and Kero's implicit text balloon;
* queued one-shot animations and their explicit post-render consumption;
* next-script reset, which clears transient presentation but preserves surfaces.

The reducer emits the existing `GhostPresentationState` contract. It has no
Android, view, native, or parser dependencies.

## Migration sequence

The Java runner and Java `LegacyGhostPresentationRenderer` remain the
compatibility path while Ant is frozen. The next runtime slice will have the
Kotlin script parser apply these exact transitions and emit this state to a
renderer. Once that parity path is proven, the Java mutable fields and legacy
frame can be removed from the Gradle/Compose path.

## Validation

* Focused Gradle JVM tests cover reset, surface retention, synchronized text,
  balloon visibility, and one-shot animation consumption.
* Repository characterization-contract tests pass.
* Hosted CI remains responsible for the complete legacy native, API-37
  APK/AAB, and artifact pipeline.
