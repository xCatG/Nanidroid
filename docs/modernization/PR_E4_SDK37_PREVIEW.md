# PR E4: Android 37 preview lane

## Scope

This PR advances the Gradle Android API surface from 36 to 37:

* `compileSdk = 37`
* `targetSdk = 37`
* `minSdk = 9` remains unchanged

The APK parity gate allows only the reviewed legacy target-SDK 13 to 37
transition.  The legacy Ant reference output remains target SDK 13 and is not
modified.

## Validation

The local SDK contains `platforms/android-37.0`.  Against that platform:

* the SDK/parity and emulator-payload contract tests pass;
* `assembleDevice bundleDevice` passes after the pinned legacy and x86_64 JNI
  artifacts are rebuilt;
* the resulting `Nanidroid-device.apk` installs on `emulator-5554`;
* a cold launch leaves `com.cattailsw.nanidroid.Nanidroid` focused and its
  process alive with no crash-buffer entry;
* package inspection reports `compileSdkVersion=37` and `targetSdkVersion=37`.

The available AVD is Android 16 / API 36, so that launch validates packaging
and compatibility on a lower-platform device.  It is not evidence of Android
17/API-37 platform behavior; the hosted artifact lane and a future API-37 AVD
run remain the release gates for that behavior.

## Compatibility boundary

This is a preview target-SDK bump, not the Kotlin/Compose migration.  The
API-36 component, HTTPS, foreground-service, and installer boundaries remain
intact and continue to be locked by their focused contracts.
