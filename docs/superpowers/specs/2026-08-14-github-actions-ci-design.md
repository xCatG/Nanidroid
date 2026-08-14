# GitHub Actions CI Restoration Design

## Goal

Restore GitHub Actions feedback for pull requests by building and unit-testing
the current Android Gradle application.

## Scope

The workflow covers the current Gradle project only. It does not recreate the
retired legacy Docker build, whose source and supporting scripts were removed
from the default branch in commit `72ff30ed`.

## Design

Add one workflow under `.github/workflows/`. It runs for every pull request and
can also be started manually. It uses a Linux runner, a pinned Java 17 setup,
and Android SDK components compatible with the project: API 37.0, Build Tools
36.0.0, CMake 3.22.1, and an Android NDK. The Gradle wrapper then runs
`testDebugUnitTest` and `assembleDebug`.

The workflow grants read-only repository access and uses a pull-request/ref
concurrency key so that newer commits cancel superseded runs. Gradle caches are
enabled through the setup action. On failure, the workflow uploads Gradle
reports and outputs when they exist, allowing the failed PR check to contain
diagnostic evidence without retaining artifacts for successful runs.

## Validation

The repository-level regression check will assert that the workflow exists,
triggers on `pull_request`, installs the Android API 37 and CMake requirements,
and invokes the two Gradle tasks. The changed test must fail before the workflow
is added and pass after it. The focused test suite and the Gradle commands
referenced by the workflow provide local validation; GitHub Actions provides the
hosted-run confirmation once the pull request is opened.
