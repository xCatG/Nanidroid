# PR F4: Kotlin presentation interpreter

## Purpose

`SakuraScriptPresentationInterpreter` is a pure Kotlin interpreter for the
Sakura Script commands whose only effect is visible ghost presentation:

* speaker selection, text, synchronized text, and clear;
* surfaces, balloons, and one-shot animations;
* newline and end-of-script reset frames.

It emits `GhostPresentationState` frames in the same order observed from the
legacy runner. The tests include the exact text/surface/animation/end trace
used by the Java renderer-boundary characterization.

## Deliberate boundary

This is not yet a production replacement for `SScriptRunner`. Choices, user
input, waits, Shiori callbacks, clock/lifecycle effects, and queue scheduling
have observable behavior outside presentation. They remain on the Java path
until each has a Kotlin boundary and characterization. Routing a partial
interpreter into the app before then would silently drop user-visible effects.

## Next step

The next runtime slice will characterize and move non-presentation parser
effects into explicit Kotlin outputs, then compose those outputs with the
presentation interpreter. Only after the full output trace matches can the
Gradle runtime select Kotlin rather than the Java runner.

## Validation

* Focused Gradle JVM tests pass for the interpreter and presentation reducer.
* Repository characterization-contract tests pass.
* Hosted CI validates the complete legacy native and API-37 APK/AAB pipeline.
