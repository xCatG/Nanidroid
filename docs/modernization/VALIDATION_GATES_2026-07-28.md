# Remaining validation-gate execution record

Date: 2026-07-28.  These commands were run without publishing, configuring
release credentials, invoking Docker/Ant builds, or downloading components.

## Authoritative gate commands

| Gate | Command |
| --- | --- |
| Full JVM characterization | `$env:ANDROID_HOME=$sdk; $env:ANDROID_SDK_ROOT=$sdk; .\\gradlew.bat --no-daemon --no-configuration-cache testEmulatorUnitTest` |
| Local release assembly | `$env:ANDROID_HOME=$sdk; $env:ANDROID_SDK_ROOT=$sdk; .\\gradlew.bat --no-daemon --no-configuration-cache assembleRelease` |
| Emulator/device artifact assembly (API 36/37 documentation lane) | `assembleEmulator bundleEmulator` and `assembleDevice bundleDevice` |
| Native artifact preflights | `verifyLegacyNativeLibraries`, `verifyEmulatorNativeLibraries`, and `verifyDeviceNativeLibraries` |
| Frozen Ant regeneration (not run) | `docker compose -f docker/legacy/compose.yaml run --rm build` |
| ARM64 and x86_64 native regeneration (not run) | `docker compose -f docker/legacy/compose.yaml run --rm emulator-native`; add `--env EMULATOR_ABI=x86_64 --env OUTPUT_ROOT=/out/x86_64` for the device profile. |

## Outcomes

| Check | Result | Classification |
| --- | --- | --- |
| `verifyLegacyNativeLibraries` | Failed: all three expected `artifacts/legacy/native/armeabi` libraries (`libkawari8.so`, `libnarfs.so`, `libsatoriya.so`) are absent. | Frozen artifact prerequisite; no source or native artifact was changed. Docker 29.6.2 is available, but the documented container build may download pinned toolchains and was intentionally not run. |
| `verifyEmulatorNativeLibraries` | Passed. The ARM64 artifacts are present at `artifacts/emulator/native/arm64-v8a/`. | Passed preflight. |
| `verifyDeviceNativeLibraries` | Failed: all three expected x86_64 libraries under `artifacts/emulator/x86_64/native/x86_64/` are absent. | Frozen artifact prerequisite; blocks the API-36 x86_64 device artifact lane. |
| `testEmulatorUnitTest -x verifyLegacyNativeLibraries` | Compiled production and test code; 229 tests ran and 32 failed. | Host-framework blocker, not a new Kotlin compiler failure. Eight failures call the nonfunctional host `android.os.SystemClock.uptimeMillis`; 24 fail because the API-15 `MockContext`/`Context` facades throw `RuntimeException: Stub!`, followed by teardown nulls. Device/instrumentation is required for those Android behavior tests. |
| `assembleRelease -x verifyLegacyNativeLibraries` | Initial assembly compiled release Kotlin and Java but failed in `lintVitalRelease`: `res/layout/main.xml:19`, `@id/adview is not a sibling in the same RelativeLayout` (`NotSibling`). After removing that stale constraint, `lintVitalRelease` and the complete partial `assembleRelease -x verifyLegacyNativeLibraries` both passed. | Resolved retained-XML defect; no lint baseline or suppression was added. The unmodified full assembly still requires the frozen legacy artifact gate. |
| `python -m unittest discover -s tools -p 'test_*.py'` | 197 tests passed. | Kotlin-aware source contracts now inspect the active `SScriptRunner.kt` and `NarFilesystemInspector.kt` while retaining native/JNI visibility and exact-source checks. Synthetic native evidence uses platform-neutral paths, including drive-qualified Windows paths. |

The unmodified exact full JVM and release commands remain blocked by the absent
frozen legacy ARM libraries. The `-x verifyLegacyNativeLibraries` executions
above are explicitly partial diagnostics, not substitutes for the required
native/reference gate.

## Release disposition

No release APK/AAB is validated by this record. Before release, regenerate and
verify the frozen/required ABI artifacts and repeat the device/API-36 and
API-37 validation gates. The Kotlin-aware host artifact/security contract suite
is complete (197 tests passed) and is no longer a remaining blocker. The earlier
API-36 native instrumentation failure is separately recorded as a missing
`libnarfs.so` x86_64 packaging prerequisite.
