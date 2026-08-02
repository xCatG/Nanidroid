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

- `./gradlew assembleDebug` builds the debug APK and native targets.
- `./gradlew testDebugUnitTest` runs the JVM JUnit 4 suite.
- `./gradlew lint` runs Android lint.
- `./gradlew connectedDebugAndroidTest` runs instrumentation tests on a
  connected API 31+ emulator or device. On Windows, use `./gradlew.bat`.

Use the Gradle wrapper; dependency and plugin versions are centralized in
`gradle/libs.versions.toml`. The app compiles and targets API 37, with minSdk 31.

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
coverage threshold. Keep tests in Android's standard source-set layout:
`src/test/java/` for local JVM tests and `src/androidTest/java/` for device and
Compose instrumentation tests.

See [docs/testing.md](docs/testing.md) for local, connected, screenshot,
corpus, and full verification commands.

## Durable and Background Workflows

For workflows that survive an Activity or process (downloads, workers,
content-URI imports, installation, or long copies), follow the
[durable workflow review checklist](docs/modernization/durable-workflow-review-checklist.md)
before implementation and before requesting review.

## Android Skill Routing

Before editing Android code, inspect the available skill catalog and load every
task-specific Android skill that matches the work. Use `android-cli` only for
tasks involving the Android command-line tool; it is not the default Android
architecture guide. Prefer the relevant testing, intent-security, adaptive UI,
navigation, performance, build, or platform-integration skill when applicable.

## Commit & Pull Request Guidelines

Use concise imperative subjects, optionally scoped (for example,
`test: cover boot dispatch lifecycle` or `Fix ghost switch loading race`). Keep
each commit focused. PRs should explain the behavioral intent, identify linked
issues, list commands run, and include screenshots or recordings for visible
Compose changes. Call out native/CMake or SDK-level changes explicitly.
