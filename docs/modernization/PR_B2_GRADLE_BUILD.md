# PR B2 — Modern Gradle build with legacy behavior

## Purpose

This slice replaces Ant as the application packaging path without combining
that change with a source-layout migration, dependency upgrade, native build
migration, target-SDK change, or UI rewrite.

The root Gradle project points at the existing `src/`, `res/`, `assets/`,
`libs/`, and `AndroidManifest.xml` paths. The three checked-in legacy JARs
remain the runtime dependencies. PR C will migrate the native build; until
then, Gradle packages the exact `armeabi` libraries produced by PR B1.

## Toolchain

| Component | Version |
| --- | --- |
| Android Gradle Plugin | 9.3.0 |
| Gradle wrapper | 9.5.0 |
| JDK | 17 |
| Android compile surface | API 15 revision 5 |
| Android SDK Build Tools | 36.0.0 |

API 15 is intentional. It is the compile platform recorded by the Ant project
and retains the source-visible Apache HTTP and notification APIs used by the
application. Raising the compile SDK requires explicit source migrations and
belongs in a later PR. The packaged minimum SDK 9 and target SDK 13 remain
unchanged.

The wrapper properties pin the Gradle distribution SHA-256. The committed
wrapper JAR also matches Gradle's published Gradle 9.5.0 wrapper checksum.

## Run it on Windows

Docker Desktop must use Linux containers. Build the reference first because it
produces the native libraries consumed by Gradle:

```powershell
docker compose -f docker/legacy/compose.yaml build
docker compose -f docker/legacy/compose.yaml run --rm build
docker compose -f .devcontainer/compose.yaml build
docker compose -f .devcontainer/compose.yaml run --rm dev ./docker/gradle/build.sh
```

The last command assembles the debug APK, checks its signature and ZIP
alignment, inspects it with `aapt`, and compares its stable contract with
`artifacts/legacy/Nanidroid-debug.json`.

## TDD and parity boundary

`tools/test_compare_apk_contracts.py` was written before its comparator and
covers both equivalence and package/native/required-entry drift. The comparison
includes:

- package, version, minimum SDK, and target SDK metadata;
- native ABI and exact native-library paths;
- the manifest, DEX, resources, and both JNI engines.

APK filename, byte size, whole-file SHA-256, signing material, ZIP timestamps,
and build-system metadata are recorded but intentionally excluded from parity.
Those values are expected to differ between independent debug packaging paths.

AGP 9 requires two small compatibility edits: the package namespace and SDK
declarations now live in Gradle instead of the source manifest, and the one
resource-ID `switch` is expressed as equivalent `if/else` checks because
generated resource IDs are non-final. The unused Ads SDK import is removed
because the missing SDK has no live call site. No other production Java, XML
resource, dependency, or native source is changed.

## CI bootstrap

GitHub only triggers a `pull_request` workflow when the workflow file exists on
the repository's default branch. Workflow-only PR #9 therefore placed the same
file on `master`, scoped to pull requests targeting `feature/modernization`.
That leaves the stable application untouched while allowing this PR and later
modernization slices to run both build lanes.
