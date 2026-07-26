# PR F1: Kotlin presentation contract

## Purpose

`SScriptRunner` currently owns Sakura Script interpretation, mutable render
state, Android view mutation, animation triggering, balloon visibility, and
event dispatch.  This PR starts its decomposition by introducing a pure Kotlin
presentation contract:

* `GhostSpeakerPresentation` describes one speaker's text, surface, optional
  animation and balloon visibility.
* `GhostPresentationState` describes both Sakura and Kero.
* `GhostPresentationReducer.snapshot` centralizes the legacy visibility rule:
  show a balloon when it is explicitly selected or has text.

This immutable model is intentionally Android-view-free.  A later PR will
adapt `SScriptRunner` to emit this state and will replace the legacy adapter
with Compose; this PR avoids changing the frozen Ant reference runtime before
that adapter has equivalent characterization coverage.

## Build migration

AGP 9 built-in Kotlin is already enabled by the Android plugin.  Because this
project uses a shared historical `src/` directory, the main Android source set
now explicitly registers that directory for Kotlin as well as Java.  No
external Kotlin Gradle plugin is applied.

## Validation

* The new `GhostPresentationReducerTest` verifies speaker text, surfaces,
  optional animations and both balloon visibility cases.
* `testEmulatorUnitTest --tests GhostPresentationReducerTest` passes.
* The pinned legacy Ant/APK Docker lane passes unchanged.

The existing Android-stub Sakura Script JVM test cannot run locally because
the API-15 mock classes are compile-only; hosted CI remains its full gate.
