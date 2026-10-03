# Gesture coordinate regression evidence — 2026-09-27

Base: `7249b817029f0549000421f25a06c7880ae8edeb` on `codex/walking-skeleton`.

The three new connected tests were run before editing `GhostStage.kt`. Each failed on an
observable tap coordinate while retaining collision surface 10:

| Test | Expected `(x, y, collision)` | Observed before fix |
| --- | --- | --- |
| `stationaryScreenPointerAcrossCanvasTranslationKeepsPressCoordinates` | `(100, 100, 10)` | `(60, 100, 10)` |
| `stationaryScreenPointerAcrossViewportResizeKeepsPressCoordinates` | `(100, 100, 10)` | `(100, 177, 10)` |
| `secondHeldTapAcrossTranslationStillDoubleClicksAtPressCoordinates` | `(100, 100, 10)` | `(60, 100, 10)` |

The red run ended `BUILD FAILED` with three test failures. Its XML was overwritten by
the subsequent green run; the red assertions above were observed in the task's
Gradle stdout.

The API 31 emulator was healthy before the connected run: `adb devices` reported
`emulator-5554 device`, `sys.boot_completed` was `1`, and `ro.build.version.sdk`
was `31`. The device was `Nanidroid_M4_Fresh_API31(AVD) - 12`.

From the repository root, with `GRADLE_USER_HOME` set to the existing cache:

```powershell
$env:GRADLE_USER_HOME='C:\Users\yenchi\.gradle'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.ui.CharacterGestureTest#stationaryScreenPointerAcrossCanvasTranslationKeepsPressCoordinates,com.cattailsw.nanidroid.ui.CharacterGestureTest#stationaryScreenPointerAcrossViewportResizeKeepsPressCoordinates,com.cattailsw.nanidroid.ui.CharacterGestureTest#secondHeldTapAcrossTranslationStillDoubleClicksAtPressCoordinates' --console=plain
```

After the fix, the same focused command passed three tests (`BUILD SUCCESSFUL in
19s`). The broader check also passed:

```powershell
$env:GRADLE_USER_HOME='C:\Users\yenchi\.gradle'
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.ui.CharacterGestureTest' --console=plain
```

That run ended `BUILD SUCCESSFUL in 31s`. The connected XML at
`app/build/outputs/androidTest-results/connected/debug/TEST-Nanidroid_M4_Fresh_API31(AVD) - 12-_app-.xml`
records 22 tests, zero failures, and zero errors. The 26 XML suites under
`app/build/test-results/testDebugUnitTest/` record 351 JVM tests, zero failures,
and zero errors. `git diff --check` passed.

Commit scope is `GhostStage.kt`, `CharacterGestureTest.kt`, and this evidence
file. The change maps later pointer positions into press-time local coordinates
for drag threshold checks and authored tap/move positions. It preserves the
existing press-time collision identity and leaves drag cancellation behavior
covered by the gesture test class. These results establish this focused gesture
behavior on the API 31 emulator; they do not establish broader native parity.
