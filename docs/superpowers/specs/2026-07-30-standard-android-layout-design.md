# Standard Android Layout Design

## Goal

Move the active Android application from custom root-level source trees to the
standard single-module Android layout without changing runtime behavior or Java
and Kotlin package names. The stale, untracked `legacy/` reference tree is
local cleanup and is not part of the tracked change.

## Target Layout

- Production code: `src/main/java/`
- Resources: `src/main/res/`
- Assets: `src/main/assets/`
- Application manifest: `src/main/AndroidManifest.xml`
- JVM tests: `src/test/java/`
- Instrumentation tests and manifest: `src/androidTest/java/` and
  `src/androidTest/AndroidManifest.xml`

The project remains a single root Gradle module. Native code stays in `jni/`;
no package declarations or functional source changes are planned.

## Build and Test Boundaries

Remove the custom `sourceSets` paths and the unused `modern/src` root, relying
on Android Gradle Plugin defaults. Retain the characterization-isolation
checks, but update their allowlists and file trees to the standard test paths.
Update active path-contract scripts and live documentation that name the old
locations. Historical modernization records remain unchanged.

## Validation

Run the relevant Python contract tests, Gradle JVM tests and `check`, then
compile or run instrumentation tests when the configured Android SDK and
emulator are available. Review the final diff to ensure changes are renames and
path-reference updates only.
