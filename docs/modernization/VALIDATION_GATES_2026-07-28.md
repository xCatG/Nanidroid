# Remaining validation-gate execution record

Date: 2026-07-28. These commands were run without publishing or configuring
release credentials. The documented pinned Docker/Ant workflow regenerated the
required native artifacts from locally available images; no image or toolchain
download was needed on this host.

## Authoritative gate commands

| Gate | Command |
| --- | --- |
| Full JVM characterization | `$env:ANDROID_HOME=$sdk; $env:ANDROID_SDK_ROOT=$sdk; .\\gradlew.bat --no-daemon --no-configuration-cache testEmulatorUnitTest` |
| Local release assembly | `$env:ANDROID_HOME=$sdk; $env:ANDROID_SDK_ROOT=$sdk; .\\gradlew.bat --no-daemon --no-configuration-cache assembleRelease` |
| Emulator/device artifact assembly (API 36/37 documentation lane) | `assembleEmulator bundleEmulator` and `assembleDevice bundleDevice` |
| Native artifact preflights | `verifyLegacyNativeLibraries`, `verifyEmulatorNativeLibraries`, and `verifyDeviceNativeLibraries` |
| Frozen Ant regeneration | `docker compose -f docker/legacy/compose.yaml run --rm build` |
| ARM64 and x86_64 native regeneration | `docker compose -f docker/legacy/compose.yaml run --rm emulator-native`; add `--env EMULATOR_ABI=x86_64 --env OUTPUT_ROOT=/out/x86_64` for the device profile. |

## Outcomes

| Check | Result | Classification |
| --- | --- | --- |
| `verifyLegacyNativeLibraries` | Passed after `docker compose -f docker/legacy/compose.yaml run --rm build` regenerated `artifacts/legacy/native/armeabi/{libkawari8.so,libnarfs.so,libsatoriya.so}`. | Pinned r14b Docker/Ant output; `artifacts/` is intentionally ignored and no binary was staged. |
| `verifyEmulatorNativeLibraries` | Passed. The ARM64 artifacts are present at `artifacts/emulator/native/arm64-v8a/`. | Passed preflight. |
| `verifyDeviceNativeLibraries` | Passed after `docker compose -f docker/legacy/compose.yaml run --rm --env EMULATOR_ABI=x86_64 --env OUTPUT_ROOT=/out/x86_64 emulator-native` regenerated the three x86_64 libraries. | Pinned r14b Docker output; `artifacts/` is intentionally ignored and no binary was staged. |
| `testEmulatorUnitTest -x verifyLegacyNativeLibraries` | Compiled production and test code; 229 tests ran and 32 failed. | Host-framework blocker, not a new Kotlin compiler failure. Eight failures call the nonfunctional host `android.os.SystemClock.uptimeMillis`; 24 fail because the API-15 `MockContext`/`Context` facades throw `RuntimeException: Stub!`, followed by teardown nulls. Device/instrumentation is required for those Android behavior tests. |
| `assembleRelease` | Passed, including `verifyLegacyNativeLibraries` and `lintVitalRelease`, after native regeneration. | Non-publishing unsigned local release assembly; no lint baseline or suppression was added. |
| `python -m unittest discover -s tools -p 'test_*.py'` | 196 tests passed. | Kotlin-aware source contracts inspect the active sources, including ViewServer retirement and native-SHIORI fallback. Synthetic native evidence uses platform-neutral paths, including drive-qualified Windows paths. |

The exact local release assembly is now unblocked by native artifacts. The full
JVM host task still has the separately recorded Android-stub limitations; it is
not a native-preflight failure.

## Release disposition

`assembleRelease` now produces a local unsigned release APK, but no signed
release APK/AAB is validated by this record. Before release, repeat the
device/API-36 and API-37 validation gates on the regenerated payloads and make
the required signing, Firebase, privacy, and release-policy decisions. The
Kotlin-aware host artifact/security contract suite is complete (196 tests
passed) and is not a remaining blocker. The earlier API-36 native
instrumentation failure predates the regenerated x86_64 `libnarfs.so` and must
be rerun rather than treated as current evidence.
