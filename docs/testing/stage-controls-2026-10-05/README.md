# Solid stage controls: screenshot evidence

Captured directly from an API 37 Android emulator on 2026-10-05. These are
emulator screenshots, not physical-device captures or mockups. No physical
device was connected. Images are unedited `adb shell screencap -p` output.

- App source: `8afe17b7` (solid text-only controls and toolbar translations).
- Variant: release, locally test-signed with the standard debug key solely for
  emulator installation. The production signing key was not used.
- Screenshot APK SHA-256:
  `8971cb691d57e183f1e02866740c5db4c7fea77a3ca12157faeb687829cbd787`.
- API 37, 720 x 1600 px, 320 dpi (360dp width), default font scale.
- Bundled ghost, stock emulator wallpaper. Release hides the debug Bounds button.
- Per-app locales were set to en-US, ja-JP and zh-TW, with a cold launch before
  capture. All three screenshots were visually inspected.
- The AVD ran read-only without saving a snapshot, then was closed.

| English | Japanese | Traditional Chinese |
| --- | --- | --- |
| ![English solid controls](en-US.png) | ![Japanese solid controls](ja-JP.png) | ![Traditional Chinese solid controls](zh-TW.png) |

## Validation

`./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
:app:assembleDebugAndroidTest` succeeded. JVM results: 380 cases, 376 passed,
four skipped, zero failures/errors. Lint: zero errors, 35 warnings, one hint.
`./gradlew.bat :app:assembleRelease` also succeeded.

Existing `StagePolishTest` passed all nine tests on API 31. API 37 debug visual
checks also covered Japanese at 320dp width with font scale 2.0; labels wrapped
without clipping. The initial instrumentation pass preceded a metadata-only
correction marking the brand `app_name` untranslatable; final builds include it.

An independent GPT-6-Sol source review found no actionable defects. Translation
scope is the stage controls/accessibility labels and Ghosts dialog title, not
the entire application. Native code, SDK configuration, runtime and the existing
blank-stage hide/show behavior are unchanged.

PR CI exposed a scheduling race in the existing cancellation test: its IO hook
could cancel before the Default-dispatcher job was assigned. The fixture now
holds source copying until admission returns, then cancels from the caller as
the UI does. Cancellation, no-install-event and no-publication assertions remain.
The focused `ImportCoordinatorTest` suite and full JVM suite passed after this
test-only correction (380 cases, four skipped, zero failures/errors). Screenshot
source remains `8afe17b7`; production runtime code is unchanged.

The Taiwan Chinese resource directory was subsequently renamed to
`values-zh-rTW` with identical string contents. Resource compilation, debug APK
assembly and lint passed (zero errors, 35 warnings, one hint); `aapt2` confirmed
all nine stage strings are packaged under `zh-rTW` with their original values.
