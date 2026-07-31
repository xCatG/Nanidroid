# Repository Guidelines

## Project Structure & Module Organization

Nanidroid is a single Android application module. Production Kotlin lives in
`src/main/kotlin/com/cattailsw/nanidroid/`; keep packages aligned with that
namespace (for example, Compose UI in `compose/`, install/archive logic in
`install/`, and script runtime code in `runtime/`). Android resources and the
manifest are in `src/main/res/` and `src/main/AndroidManifest.xml`. Native
SHIORI components and their CMake configuration are under `jni/`.

Local JVM tests live in `src/test/java/`; device and Compose instrumentation
tests live in `src/androidTest/java/`. Both use Android's standard source-set
layout and Kotlin sources.

## Build, Test, and Development Commands

- `./gradlew.bat assembleDebug` builds the debug APK and native targets.
- `./gradlew.bat testDebugUnitTest` runs the JVM JUnit 4 suite.
- `./gradlew.bat lint` runs Android lint.
- `./gradlew.bat connectedDebugAndroidTest` runs instrumentation tests on a
  connected API 31+ emulator or device.

Use the Gradle wrapper; dependency and plugin versions are centralized in
`gradle/libs.versions.toml`. The app compiles and targets API 37, with minSdk
31.

## Coding Style & Naming Conventions

Write application code and tests in Kotlin. Follow existing conventions:
four-space indentation, trailing commas in multiline declarations, `PascalCase`
types, `camelCase` members, and one primary type per file named after that type.
Keep Compose functions and state models small and package-local where
practical. No formatter is enforced; match the surrounding file and keep
imports tidy.

## Testing Guidelines

Name tests `*Test`; use JUnit 4 and MockK for mocks. Prefer JVM tests for pure
behavior and `androidTest` only for framework or UI behavior. There is no
coverage threshold. `build.gradle.kts` deliberately allowlists JVM and device
characterization tests because of Android default-return stubs; update the
corresponding list whenever adding, removing, or relocating a test.

## Commit & Pull Request Guidelines

Use concise imperative subjects, optionally scoped (for example,
`test: cover boot dispatch lifecycle` or `Fix ghost switch loading race`). Keep
each commit focused. PRs should explain the behavioral intent, identify linked
issues, list commands run, and include screenshots or recordings for visible
Compose changes. Call out native/CMake or SDK-level changes explicitly.
