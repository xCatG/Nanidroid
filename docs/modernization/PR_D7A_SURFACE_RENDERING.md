# PR D7a: deterministic surface rendering characterization

## Scope and decision

D7a closes only the deterministic pixel gap left by D5. It runs two headless
tests through the real Android graphics framework:

1. a base surface whose direct filename is absent and whose zero-padded
   fallback is decoded; and
2. an element surface composed from a base layer and an offset overlay.

There are no production, main-manifest, resource, asset, SDK, ABI, native,
plugin, dependency, or Kotlin changes. The existing five-file JVM
characterization boundary and Android default-return behavior are unchanged.
The device test uses the platform `InstrumentationTestCase` runner because the
API 15 compile surface already provides it. No Activity, screenshot framework,
AndroidX dependency, or broad `test/src` tree is involved.

## Immutable synthetic fixtures

The Java test embeds three original, repository-authored PNG byte arrays as
Base64. The exact bytes and their SHA-256 digests are checked before each file
is written to the target application's cache directory:

| Fixture | Size | SHA-256 | Pixel purpose |
| --- | --- | --- | --- |
| `surface0007.png` | 3 x 2 | `bc7cc462b23cb8bc91f9f9154a95c72dbf3e52673fa864427daafc71c0ebbe50` | Repeated magenta key plus retained red, blue, green, and yellow pixels |
| `base.png` | 4 x 3 | `57d054fb3911ecf5a663fcf7cb7181e61d05a492cc71d5b0597fa672f5f8aa53` | Magenta key and opaque blue element base |
| `overlay.png` | 2 x 2 | `b2584b961c275a89922cb32f00e11b6c9f637d9761738e097d37ca9d26c4ac19` | Cyan key plus retained red, green, and yellow overlay pixels |

These are synthetic color matrices, not copied artwork. No binary fixture is
committed. Tests draw the production `BitmapDrawable` or `LayerDrawable` into
an `ARGB_8888` `Bitmap` through a real `Canvas` at native dimensions and
compare every row-major ARGB pixel.

## Required migration invariants

- If `surface7.png` is absent and `surface0007.png` exists, the padded path is
  selected and the decoded dimensions are 3 x 2.
- The decoded upper-left pixel is the color key. Every exact occurrence becomes
  `Color.TRANSPARENT`; all tested non-key opaque ARGB values remain exact.
- Element draw order follows the declared order.
- A 2 x 2 overlay declared at `(1, 1)` occupies exactly that inset within its
  4 x 3 base. A transparent overlay pixel reveals the base pixel below it.

## Exact device-test boundary

`test/device` replaces the default AndroidTest Java and manifest roots. The
only allowlisted Java source is:

`test/device/com/cattailsw/nanidroid/SurfaceRenderingCharacterizationTest.java`

Gradle verifies that exact set before every AndroidTest compilation and from
`check`. A separate Python oracle pins the source, minimal manifest, runner,
source-root, guard, and hosted packaging wiring. The generated debug test APK
is independently inspected for:

- package `com.cattailsw.nanidroid.test`;
- target package `com.cattailsw.nanidroid`;
- runner `android.test.InstrumentationTestRunner`;
- minimum SDK 9 and target SDK 13;
- the `android.test.runner` platform library; and
- no native libraries.

The AndroidTest build type remains the default `debug`. Hosted CI can compile,
sign, align, inspect, and upload that small test APK without coupling its
compile gate to the opt-in ARM64 emulator artifact. For device execution, the
debug test APK targets the separately verified `emulator` application APK;
both share the application identity and debug signing lineage.

## TDD and validation

The first commit added contracts that failed because the device source,
manifest, Gradle wiring, hosted APK lane, and inspector did not exist. The
implementation then made those contracts pass.

Final local evidence:

```text
tooling (Linux): 64 tests, 0 failures
JVM characterization: 29 tests, 0 failures
legacy build and CMake parity: equivalent
standard Gradle APK parity/native payload: equivalent/identical
emulator APK native payload: exact armeabi + arm64-v8a set, identical bytes
repository hygiene: 389 tracked files, 7 inventoried opaque artifacts
debug test APK: 11,070 bytes
debug test APK SHA-256:
  0025fe59a84b69a8e8412b0f8899987e97bc68e4c44af44a80152890abdce9a6
```

API 36 execution installs the verified emulator target and debug test APK with
the explicit low-target-SDK bypass, then runs:

```text
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.cattailsw.nanidroid.SurfaceRenderingCharacterizationTest \
  com.cattailsw.nanidroid.test/android.test.InstrumentationTestRunner
```

The restored API 36 run reported `OK (2 tests)` and the subsequent log scan
found no fatal exception, fatal signal, `UnsatisfiedLinkError`, or matching
`AndroidRuntime` process failure. The platform runner reports
`INSTRUMENTATION_CODE: -1` for its completed run, and `adb shell` can return
zero even when the textual test result contains failures. Acceptance therefore
requires the explicit `OK (2 tests)` result and absence of `FAILURES!!!`; shell
status or the instrumentation code alone is not an oracle.

Calibration produced four independent failures before all temporary changes
were restored:

1. An intentionally wrong expected keyed pixel failed the base test at
   row-major index 0 while the element test passed.
2. A temporary production color-key mutation from pixel `(0, 0)` to `(1, 0)`
   failed both fixtures at their first keyed pixel.
3. A temporary production element inset-X mutation of `+1` left the base test
   green and failed only the element test at row-major index 6.
4. An unexpected Java source under `test/device` failed
   `verifyDeviceCharacterizationTestIsolation` with the exact unexpected path.

`ShellSurface.java` and the test expectations were then restored
byte-for-byte before the final builds and API 36 run.

## Explicit deferrals

D7a does not characterize target-dimension resize quirks, the first element's
non-zero offset, malformed or sparse element declarations, drawable caching,
missing/invalid image and analytics paths, density scaling, cross-Android-
version pixel identity, or screenshot appearance. Animation frame composition,
durations, dispatch, scheduling, and random selection remain D7b or later work.
