# PR C2 — Isolated ARM64 emulator artifact lane

## Purpose and boundary

C2 adds a repeatable, opt-in APK for local emulator smoke work without
changing the frozen debug artifact. The new `emulator` build type inherits the
debug Java/resources/signing configuration and adds exactly two ARM64 engines
from a separate staged root:

```text
artifacts/legacy/native/armeabi/{libkawari8.so,libsatoriya.so}
  + artifacts/emulator/native/arm64-v8a/{libkawari8.so,libsatoriya.so}
  -> Nanidroid-emulator.apk
```

The ordinary debug build still reads only `artifacts/legacy/native`, and its
unchanged inspectors still require exactly the two `armeabi` entries. C2 does
not weaken or parameterize those frozen defaults. No `.so` file is checked in.

There are no Java, C/C++, manifest, resource, JNI declaration, SDK, target-SDK,
or production behavior changes in this slice. CMake remains additive; the
existing Android.mk/Ant and armeabi CMake parity lane remains authoritative.

## Frozen ARM64 artifact contract

The ARM64 build runs in the same pinned legacy image used by C1, but in its own
disposable source copy, build directory, cache, staging root, and output root.

| Setting | Required value |
| --- | --- |
| NDK | r14b |
| ABI | `arm64-v8a` |
| API | 21 |
| Compiler | GCC 4.9 (`aarch64-linux-android-g++`) |
| STL | `gnustl_static` |
| Build engine | CMake only |
| CMake build directory | `/tmp/nanidroid-emulator-build/cmake-arm64-build` |
| Published native root | `artifacts/emulator/native` |

`inspect_emulator_native.py` reads the real CMake cache and the stripped ELF
files with the r14b AArch64 `readelf`. It reuses C1's strict CMake-declaration
parser to require the exact two modules, source lists, definitions, flags,
include roots, and link libraries. It then requires exact library paths,
ELF64/AArch64 identity, SONAME, `DT_NEEDED`, and exported JNI symbols. A stage
is published only after that inspection succeeds.

The verified ARM64 artifacts from the final local run were:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `libkawari8.so` | 1,105,008 | `197053c367a902a69198cdfe3554222a136f04ae64735fdd2dff4a84bea6aaf1` |
| `libsatoriya.so` | 1,591,680 | `40f6bcbde4f4df8de1894d5dee000ccdef8888e304df01fe35672df596c8becf` |

Both libraries require exactly `libc.so`, `libdl.so`, `liblog.so`, `libm.so`,
and `libstdc++.so`. Hashes and sizes are run provenance, not cross-environment
compatibility promises.

## Exact APK contract

`verify_emulator_apk.py` is deliberately separate from the legacy APK and
payload inspectors. It requires the unchanged package/version/SDK metadata,
the measured `aapt` native-code order `arm64-v8a, armeabi`, and exactly these
four entries—no missing or additional ABI or library is allowed:

- `lib/arm64-v8a/libkawari8.so`
- `lib/arm64-v8a/libsatoriya.so`
- `lib/armeabi/libkawari8.so`
- `lib/armeabi/libsatoriya.so`

Every entry must be byte-identical to its separately approved staged input.
The final verified payload hashes were:

| APK entry | SHA-256 |
| --- | --- |
| `lib/arm64-v8a/libkawari8.so` | `197053c367a902a69198cdfe3554222a136f04ae64735fdd2dff4a84bea6aaf1` |
| `lib/arm64-v8a/libsatoriya.so` | `40f6bcbde4f4df8de1894d5dee000ccdef8888e304df01fe35672df596c8becf` |
| `lib/armeabi/libkawari8.so` | `9b8d31d6e06d6b8b6a2d8ec208cf200872a78c3204f299e0c1e038720dadf067` |
| `lib/armeabi/libsatoriya.so` | `9f72243c7609ca82258e82b0e76bf05dd89488e477e6d67a2bc12b865044b8ec` |

The local debug APK was 2,045,672 bytes with SHA-256
`7b460cdfd9c09dfa14f60cbb708050413755f78bb4e3175a0e2ee988303dcc4f`.
The final local emulator APK was 2,963,956 bytes with SHA-256
`b913253b978c39b679d922221e08883c98d850f22c4f0cc6a854ed7897799956`.
Debug signing metadata makes whole-APK bytes nondeterministic, so these hashes
are provenance only. The structural APK comparator and byte-exact native
payload reports are the gates.

## TDD evidence

### RED

The first commit added the exact additive-ABI, payload-byte, native ELF, CMake
cache, extra-library, and legacy-rejection tests before implementation. The
focused run executed 14 tests: the pre-existing and new legacy rejection tests
passed, while exactly three errors identified the absent emulator verifier,
native inspector, and build scripts.

### GREEN

After wiring the lane, the focused suite passed 19/19 and the complete tooling
suite passed 48/48. The real ARM64 build passed its strict measured contract.
The first emulator APK verification exposed one test assumption: `aapt`
reports `arm64-v8a` before `armeabi`. Aligning the exact expected order with
that measured output restored the focused suite and accepted the already-built
APK with all four payloads byte-identical.

The unchanged standard pipeline also passed:

- 26/26 D1–D5 JVM characterization tests;
- exact Android.mk/CMake armeabi parity;
- frozen standard APK structural parity;
- byte-identical standard APK native payloads.

The first Gradle configuration run also caught eager lookup of the generated
`preEmulatorBuild` task. The final wiring uses lazy task matching; both
`assembleDebug` and `assembleEmulator` then completed successfully.

### REFACTOR

The emulator verifier shares only the stable badging parser. Its expected ABI
and payload constants remain independent from the legacy inspector so an
emulator-lane change cannot silently relax the frozen debug contract. The
ARM64 inspector reuses C1's strict CMake declaration reader without changing
C1's armeabi/ARMv5 ELF and build-evidence rules.

## Commands

Build the authoritative legacy/armeabi inputs and the isolated ARM64 pair:

```powershell
docker compose -f docker/legacy/compose.yaml run --rm build
docker compose -f docker/legacy/compose.yaml run --rm emulator-native
```

Run the frozen standard pipeline and then assemble the opt-in APK:

```powershell
docker compose -f .devcontainer/compose.yaml run --rm dev ./docker/gradle/build.sh
docker compose -f .devcontainer/compose.yaml run --rm dev ./docker/emulator/build.sh
```

On Android 15 and newer, this target-SDK-13 app requires the explicit local
development bypass:

```powershell
adb install --bypass-low-target-sdk-block -r artifacts/emulator/apk/Nanidroid-emulator.apk
```

The bypass is not a release or distribution mechanism.

## Runtime limit and D6 handoff

A disposable pre-C2 smoke artifact installed on the API 36.1 x86_64 AVD and
Android selected `primaryCpuAbi=arm64-v8a` through its ARM translation layer.
The unchanged app then crashed at `Nanidroid.java:161` because legacy
`ViewServer` networking runs on the main thread and triggers
`NetworkOnMainThreadException` on the modern runtime.

C2 intentionally does not include the proven-but-uncommitted SDK gate or any
other ViewServer fix. Therefore C2 proves a reproducible, installable ARM64
artifact and native selection path; it does **not** claim a successful app
launch or UI smoke. D6 must resolve and test that lifecycle/runtime blocker in
a separate behavior-changing PR.

Hosted CI should build and inspect these artifacts but must not launch a GUI
emulator. Device installation remains an explicit local smoke step.
