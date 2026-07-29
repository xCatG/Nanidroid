# PR E3: API 36 emulator validation

## Scope

This change adds an isolated `device` debug build type solely for the available
API 36 x86_64 AVD.  The existing ARM64 `emulator` CI lane and all production
variants retain their native-library inputs and behavior.

The profile builds the three JNI DSOs with the pinned r14b toolchain and
verifies their ELF identity, dependencies and exports before packaging.

## Real-device evidence

Validated on `emulator-5554` (Android 16, API 36, x86_64):

* `assembleDevice` produced an x86_64-capable API 36 APK and `adb install`
  succeeded.
* A clean cold launch left Nanidroid focused and its process alive, with no
  post-clear `AndroidRuntime` error.
* An `http` `.nar` deep link was rejected by Android intent resolution.
* `https://127.0.0.1:9/accepted.nar` reached the accepted HTTPS download path;
  API 36 allowed the foreground-service start and the `nanidroid_downloads`
  notification channel was created.  Port 9 is intentionally closed, so this
  validation performed no network transfer.

## Compatibility findings and containment

Real launch exposed two previously hidden legacy assumptions:

1. Bundled ACRA/Google Analytics jars link Apache HTTP.  The base manifest
   declares Android's optional `org.apache.http.legacy` compatibility shared
   library, required for those legacy jars to link on API 36.
2. Reading the system wallpaper can throw `SecurityException` under current
   storage permissions.  The app now preserves its theme background and logs
   the denial instead of crashing.

The `device` manifest sets a validation-only metadata flag that prevents ACRA
and legacy Analytics initialization.  Thus this test did not activate any
legacy telemetry sender; production variants do not merge the flag.

## Follow-up

The Kotlin migration must replace the bundled Analytics/ACRA stack and remove
the Apache compatibility dependency.  This PR is a narrowly scoped runtime
proof and characterization step, not that migration.
