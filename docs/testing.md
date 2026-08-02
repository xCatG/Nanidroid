# Testing Nanidroid

Run all commands from the repository root with the Gradle wrapper on Windows.

## Local JVM and coverage

```powershell
.\gradlew.bat testDebugUnitTest jacocoTestReport
```

The JaCoCo HTML report is written to `build/reports/jacoco/testDebugUnitTestCoverage/`.

## Connected Android tests

Use an API 31–37 emulator or device, then run:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

The suite uses `NanidroidTestRunner`, which installs Hilt's test application.

## Compose screenshot tests

Screenshot previews live in `src/screenshotTest/`; committed references live in
`src/screenshotTestDebug/reference/`.

```powershell
.\gradlew.bat updateDebugScreenshotTest
.\gradlew.bat validateDebugScreenshotTest
```

Inspect every changed/generated reference PNG before committing it. CI validates
existing references only; updating a baseline requires human image-diff review.
After a screenshot-plugin upgrade, regenerate and review every golden. The
HTML comparison report is `build/reports/screenshotTest/preview/debug/index.html`.

## NAR corpus (introduced in Task 17)

With the dedicated disposable emulator running, use:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-nar-corpus-audit.ps1 -DeviceSerial emulator-5554
```

The corpus runner and its manifest are added in Task 17; do not place local
archives or generated corpus reports under version control.

## Full verification

```powershell
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest
.\gradlew.bat validateDebugScreenshotTest jacocoTestReport
```
