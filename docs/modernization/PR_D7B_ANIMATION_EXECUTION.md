# PR D7b: deterministic animation execution characterization

## Scope and decision

D7b closes a narrow deterministic animation slice left by D5 and D7a. It adds
two same-package, headless tests through the real Android graphics and view
framework:

1. direct `ShellSurface.Animation` assembly of a manager-backed overlay frame
   followed by a reset frame; and
2. singleton talking-animation dispatch through `SakuraView`, followed by a
   surface change.

There are no production, manifest, resource, asset, SDK, ABI, native,
dependency, plugin, or Kotlin changes. The tests construct the already parsed
package-private model directly, so parser grammar remains owned by D5. They do
not sleep, wait for frame advancement, select among multiple ids, or introduce
a production test seam.

## Immutable synthetic fixtures

The test embeds two original synthetic PNG byte arrays as Base64 and checks
their SHA-256 digests before writing them to the target application's cache:

| Fixture | Size | SHA-256 | Purpose |
| --- | --- | --- | --- |
| `surface0.png` | 4 x 3 | `57d054fb3911ecf5a663fcf7cb7181e61d05a492cc71d5b0597fa672f5f8aa53` | Magenta-keyed blue base and reset frame |
| `surface1.png` | 2 x 2 | `b2584b961c275a89922cb32f00e11b6c9f637d9761738e097d37ca9d26c4ac19` | Cyan-keyed red/green/yellow manager-backed overlay |

The overlay frame declares `sid = "1"`, offset `(1, 1)`, duration 37 ms, and
a deliberately wrong fallback `filePath` pointing at the 4 x 3 base. The full
4 x 3 expected pixel matrix therefore proves that manager lookup wins; silently
dropping the manager would not satisfy the oracle. The following reset frame
has duration 83 ms and must reproduce the complete keyed base matrix.

## Required migration invariants

- `AnimationDrawable` frames retain model insertion order.
- The tested frame durations remain exactly 37 ms and 83 ms.
- A manager-backed 2 x 2 overlay is composed at `(1, 1)` over the 4 x 3 base,
  including transparent-key reveal of the base.
- A reset frame returns the complete base surface.
- A surface with exactly one talk mapping resolves id `"3"`.
- `SakuraView.startTalkingAnimation()` loads, binds, and starts that exact
  `AnimationDrawable`; its current drawable is the first frame immediately
  after dispatch.
- Changing to another surface binds that surface drawable and clears both the
  animation object and current animation id.

All view operations run on Android's main thread. State is snapshotted there,
then asserted on the instrumentation test thread. This avoids turning an
ordinary assertion failure into an application-process crash.

## Exact device-test boundary

The exact Gradle and Python allowlist now contains only:

- `SurfaceRenderingCharacterizationTest.java`; and
- `SurfaceAnimationExecutionCharacterizationTest.java`.

The AndroidTest APK inspector requires both class descriptors and all four
required method names in `classes.dex`. Its runner, package, target package,
SDK, platform-library, no-native-payload, signing, alignment, and stale-output
contracts remain unchanged. D7a-specific inspector wording was generalized to
the combined D7 boundary.

## TDD and calibration evidence

RED commit `7e98183` changed only test contracts. The 21 focused Python tests
then produced eight expected failures: the new source and Gradle allowlist
entry were absent, the inspector still used D7a wording, and none of the three
new DEX markers was required.

The green implementation added the second device class, exact allowlist entry,
and DEX markers. A javac 17/API 15 compile failure showed that qualified
construction of a non-static inner class is lowered through
`java.util.Objects`, which is absent from the API 15 boot class path. A
test-only `ShellSurface` subclass now creates its inherited inner models
without that newer runtime dependency.

Calibration applied two temporary production mutations together:

1. adding one millisecond during `AnimationDrawable` assembly failed with
   `expected:<37> but was:<38>`; and
2. routing `startTalkingAnimation()` to `A_TYPE_RARELY` failed with
   `Talking animation was not loaded`.

The runner reported `Tests run: 4, Failures: 2, Errors: 0`; both D7a rendering
tests remained green. Both production mutations were restored before final
builds. The final production-source diff is empty.

## Final validation

Tooling passed 66/66: the 13 build-script and real-git-index contracts ran on
Windows, while the remaining 53 POSIX/native-path contracts ran in the pinned
Linux devcontainer. The five JVM suites passed 29/29 with no failures, errors,
or skips.

The standard Gradle pipeline retained equivalent package/native/required-entry
contracts. The emulator APK retained the exact additive `armeabi` plus
`arm64-v8a` payload with byte-identical native libraries. The final inspected
AndroidTest APK was 14,222 bytes with SHA-256:

```text
e37c2fdba7b786501db050cd0a0bf7fdf616dd33e865984e3822716c8209e277
```

The configured API 36.1 emulator reported:

```text
SurfaceAnimationExecutionCharacterizationTest:..
SurfaceRenderingCharacterizationTest:..
OK (4 tests)
```

Execution took 0.414 seconds. Acceptance checked ADB exit status, required
`OK (4 tests)`, and rejected `FAILURES!!!`.

The emulator app and test APK must be built in the same one-shot devcontainer
invocation because the debug keystore lives in that container's ephemeral
home. Building them in separate invocations creates valid APKs with different
signatures, which Android correctly rejects for instrumentation.

## Explicit deferrals

D7b does not characterize:

- animation parser grammar or Sakura Script event parsing;
- valid file-backed overlay fallback or `TYPE_BASE` frame replacement;
- `AltAnimation`, multiple-id randomness, or probability distributions;
- wall-clock frame advancement, scheduling cadence, sleeps, or Looper timing;
- one-shot, run-once, exclusive, interval, or repetition policy;
- missing files, invalid drawables, analytics, cache identity, or retries;
- MOVE frames, Kero-specific dispatch, visibility-only surface `-1`, or
  collision behavior;
- density scaling, screenshots, accessibility, or cross-Android-version
  drawable identity.

## Reproduction

```text
docker compose -f docker/legacy/compose.yaml run --rm build
docker compose -f docker/legacy/compose.yaml run --rm emulator-native
docker compose -f .devcontainer/compose.yaml run --rm dev bash -lc \
  "./docker/gradle/build.sh && ./docker/emulator/build.sh"
```

Then install and verify the same-invocation artifacts. The explicit result-text
checks prevent a stale installed test package or ADB's zero exit status for a
textual test failure from producing a false green:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 install --bypass-low-target-sdk-block -r `
  artifacts\emulator\apk\Nanidroid-emulator.apk
if ($LASTEXITCODE -ne 0) {
    throw "D7b emulator target install failed"
}
& $adb -s emulator-5554 install --bypass-low-target-sdk-block -r `
  artifacts\gradle\Nanidroid-debug-androidTest.apk
if ($LASTEXITCODE -ne 0) {
    throw "D7b AndroidTest install failed"
}
$result = & $adb -s emulator-5554 shell am instrument -w `
  com.cattailsw.nanidroid.test/android.test.InstrumentationTestRunner 2>&1
$instrumentExit = $LASTEXITCODE
$result
$text = $result -join "`n"
if ($instrumentExit -ne 0 -or
    $text -notmatch 'OK \(4 tests\)' -or
    $text -match 'FAILURES!!!') {
    throw "D7b instrumentation acceptance failed"
}
```
