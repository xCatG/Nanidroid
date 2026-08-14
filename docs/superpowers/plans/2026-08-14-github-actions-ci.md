# GitHub Actions CI Restoration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore pull-request CI that unit-tests and builds the current Android Gradle application.

**Architecture:** A single GitHub Actions workflow owns hosted validation. Configuration-file validation consists of the real Gradle commands locally and the hosted workflow run after the branch is pushed; a source-text test would not establish CI behavior.

**Tech Stack:** GitHub Actions YAML, Android SDK API 37, Android NDK, CMake 3.22.1, Java 17, Gradle 9.5, Python `unittest`.

## Global Constraints

- Trigger on every `pull_request` and support manual `workflow_dispatch` runs.
- Build and test the current Gradle project only; do not recreate the removed legacy Docker lane.
- Install API 37, Build Tools 36.0.0, an Android NDK, and CMake 3.22.1.
- Run exactly the project-supported verification tasks: `testDebugUnitTest` and `assembleDebug`.
- Use read-only repository permissions, cancellation-safe PR/ref concurrency, and pinned third-party Actions.
- Upload Gradle diagnostics only when a job fails and report files exist.

---

## File structure

- Create: `.github/workflows/android-build.yml` — pull-request and manual hosted Gradle validation.

### Task 1: Restore the modern Android CI workflow

**Files:**
- Create: `.github/workflows/android-build.yml`

**Interfaces:**
- Consumes: `build.gradle.kts` (`compileSdk = 37`, CMake `3.22.1`) and `gradle/wrapper/gradle-wrapper.properties`.
- Produces: the GitHub Actions `Android build` status check for each pull request.

- [ ] **Step 1: Add the minimal workflow**

```yaml
name: Android build

on:
  workflow_dispatch:
  pull_request:

permissions:
  contents: read

concurrency:
  group: android-build-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803 # v6
      - uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5
        with:
          distribution: temurin
          java-version: "17"
          cache: gradle
      - uses: android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407 # v3
      - run: sdkmanager "platforms;android-37" "build-tools;36.0.0" "cmake;3.22.1" "ndk;28.0.13004108"
      - run: ./gradlew testDebugUnitTest assembleDebug --no-daemon --stacktrace
```

Add a final failure-only `actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7` step that uploads `build/reports/` with `if-no-files-found: ignore`.

- [ ] **Step 2: Run the same Gradle tasks locally**

Run: `./gradlew.bat testDebugUnitTest assembleDebug --no-daemon --stacktrace`

Expected: `BUILD SUCCESSFUL` with both the JVM test task and debug APK task completed.

- [ ] **Step 3: Commit the focused change**

```bash
git add .github/workflows/android-build.yml docs/superpowers/plans/2026-08-14-github-actions-ci.md
git commit -m "ci: restore Android pull request builds"
```

## Self-review

- Spec coverage: Task 1 supplies the PR/manual trigger, toolchain, Gradle tasks, concurrency, read-only permissions, cache, and failed-run diagnostics. It explicitly omits the retired legacy lane.
- Placeholder scan: no placeholders remain.
- Type consistency: no application interfaces change.
