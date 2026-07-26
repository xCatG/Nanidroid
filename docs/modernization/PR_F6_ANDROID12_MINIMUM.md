# PR F6: Android 12 minimum SDK

## Decision

The supported product minimum is Android 12 / API 31. The Gradle application
module now declares `minSdk = 31`.

Android 2.3 through Android 11 are intentionally no longer supported by the
modern product. The frozen Ant reference lane remains an artifact/regression
tool; it is not a supported-device promise.

## Why

Jetpack Compose requires API 21 or newer. Selecting API 31 aligns the product
minimum with Android 12 and removes the remaining ambiguity around legacy
ViewServer and pre-modern-device compatibility. It unblocks the Compose ghost
renderer while keeping `compileSdk` and `targetSdk` at API 37.

## Validation

* A repository build contract asserts the approved minimum and rejects the old
  API-9 declaration.
* The API-36 emulator remains a supported validation device because it is
  above the new minimum.
* Hosted CI continues to validate the legacy artifact, ARM64 native engines,
  and API-37 APK/AAB outputs.
