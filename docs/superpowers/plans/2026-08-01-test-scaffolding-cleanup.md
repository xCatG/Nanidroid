# Test Scaffolding Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retain behavior-preserving tests while removing obsolete characterization-test Gradle scaffolding and Android default-return stubs.

**Architecture:** The app retains standard `src/test/java` JVM tests and `src/androidTest/java` instrumentation tests. Gradle no longer maintains exact source allowlists; JVM tests must either run without Android framework calls or explicitly mock their seams, while genuine framework/UI tests remain in `androidTest`.

**Tech Stack:** Gradle Kotlin DSL, AGP, Kotlin, JUnit 4, MockK, AndroidX test.

## Global Constraints

- Preserve test assertions and production behavior; do not delete compatibility coverage.
- Do not add dependencies; use the existing MockK test dependency if mocking is needed.
- Keep pure behavior in `src/test/java`; move only genuine Android framework/UI coverage to `src/androidTest/java`.
- Remove `unitTests.isReturnDefaultValues = true` only after the JVM suite passes without it.
- Remove both source allowlists and their custom verification task classes only when they no longer guard an exceptional boundary.
- Verify `testDebugUnitTest` and `compileDebugAndroidTestKotlin`; report unrelated lint failures without fixing them.

---

### Task 1: Diagnose JVM reliance on Android default stubs

**Files:**
- Modify temporarily: `build.gradle.kts`
- Inspect: `src/test/java/**/*.kt`, `src/androidTest/java/**/*.kt`

**Interfaces:**
- Consumes: existing `testDebugUnitTest` task and `unitTests.isReturnDefaultValues` setting.
- Produces: a file-by-file classification of every test that fails without default Android stubs, plus the smallest compliant remediation for each.

- [ ] **Step 1: Establish the existing JVM-suite baseline**

Run: `./gradlew testDebugUnitTest`
Expected: PASS before any temporary configuration change.

- [ ] **Step 2: Perform the red diagnostic**

Temporarily remove only `unitTests.isReturnDefaultValues = true`, then run:

`./gradlew testDebugUnitTest`

Expected: Either PASS, proving the setting is obsolete, or failures that name tests relying on an Android framework seam.

- [ ] **Step 3: Classify each failure**

For every failure, inspect the test and production call path. Use MockK for a collaborator seam that can remain JVM-local; move only real framework/UI integration behavior to the matching `src/androidTest/java` package. Do not change production behavior.

- [ ] **Step 4: Restore a clean diagnostic state**

Keep the setting removed only if all failures have a compliant remediation ready for Task 2. Record the exact test commands, results, and classifications in the task report.

### Task 2: Simplify the test build boundary and make the JVM suite independent

**Files:**
- Modify: `build.gradle.kts`
- Modify only as required by Task 1: affected files under `src/test/java/` or `src/androidTest/java/`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: Task 1’s failure classification.
- Produces: ordinary standard Android test source sets, a JVM suite independent of Android default-return stubs, and no custom source allowlists.

- [ ] **Step 1: Write or retain a focused failing test for each required remediation**

If Task 1 exposes a mockable seam, write a focused JVM test that exercises the real behavior through the mock. If it exposes framework/UI behavior, place the test in `androidTest` and verify it compiles before changing production code. Do not add production code unless a pre-existing seam is demonstrably insufficient.

- [ ] **Step 2: Apply the minimal remediation**

Use MockK at the existing test seam or relocate the framework/UI test. Preserve test names and assertions where practical.

- [ ] **Step 3: Remove obsolete Gradle scaffolding**

Delete `VerifyCharacterizationTestIsolation`, `VerifyDeviceCharacterizationTestIsolation`, both exact test-source lists/file trees, their registered tasks, and their task dependencies. Delete `unitTests.isReturnDefaultValues = true`. Keep normal `testOptions` only if another setting remains necessary.

- [ ] **Step 4: Update contributor guidance**

Remove the `AGENTS.md` instruction that says `build.gradle.kts` allowlists characterization tests. Replace it with the standard JVM-versus-instrumentation testing guidance established by the final code.

- [ ] **Step 5: Verify**

Run:

`./gradlew testDebugUnitTest`
`./gradlew compileDebugAndroidTestKotlin`
`./gradlew lint`
`git diff --check`

Expected: the first two commands pass. If lint reports existing source findings outside the diff, report them without expanding scope.