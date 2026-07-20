# PR A — Baseline and modernization guardrails

## Purpose

This PR establishes the evidence and development-environment foundation for
the modernization program. It must not change application behavior, package
contents, source layout, dependencies, or native compilation.

The source baseline is commit
`c22e1531cc1cd9424314d57e7bc34ccb91d84cc5` on `master`.

## Repository baseline

At the baseline commit Git tracks 330 files:

| Category | Count |
| --- | ---: |
| Java production files | 54 |
| C/C++ headers and sources | 175 |
| Android resources | 37 |
| Assets | 6 |
| Files beneath `test/` | 14 |

The application manifest declares:

- package: `com.cattailsw.nanidroid`
- version code: `6`
- version name: `open_0.1`
- minimum SDK: API 9
- target SDK: API 13
- launcher activity: `Nanidroid`
- application class: `CatTailApplication`
- background service: `.NanidroidService`

`project.properties` contains `target=android-15`; this means Android API level
15, not Android 15.

## Known baseline limitations

- A clean Ant/NDK reference build is defined by
  [`PR_B1_LEGACY_BUILD.md`](PR_B1_LEGACY_BUILD.md). The exact historical
  release toolchain and release APK remain unavailable.
- The SDK, build-tools, JDK, and NDK versions used for the last known release
  are not recorded in the repository.
- `jni/Application.mk` requires the removed `gnustl_static` runtime.
- The existing tests use JUnit 3 and Android instrumentation APIs. `GhostTest`
  is empty and there is no reliable lifecycle test baseline.
- The baseline APK, signing identity, and release artifact hash are not
  currently available in the repository.

PR B must resolve or explicitly document these gaps before claiming build
parity. PR B1 records a repeatable reference build without claiming equality
to an unavailable historical APK.

## Tracked opaque artifacts

The machine-readable source of truth is
[`binary-inventory.json`](binary-inventory.json). No artifact in that ledger is
removed by PR A.

Two files named as native tools or libraries begin with the Windows `MZ`
signature:

- `jni/satori/lib.exe`
- `jni/satori/satori.so`

The `.so` filename therefore must not be treated as evidence that it is an
Android ELF shared object. Its historical role remains to be established.

The three JARs under `libs/` are referenced by production imports:

- ACRA 4.2.3
- Android support-v4
- legacy Google Analytics

They are migration candidates, not generated junk.

## Development environment

`.devcontainer/` defines an editor-neutral Linux build shell with:

- Debian Bookworm
- OpenJDK 17
- pinned Android command-line tools download
- CMake, Ninja, Make, Python, Git, and binary inspection tools
- a persistent Gradle cache

The devcontainer is a thin adapter over Docker Compose. It deliberately does
not install an Android platform, build-tools, NDK, emulator, or Gradle version
yet. PR B will pin those packages after the legacy toolchain investigation.

The Android emulator remains a Windows-host or CI/KVM responsibility.

`.gitattributes` intentionally scopes LF normalization to new modernization
infrastructure. Normalizing the legacy source tree would create a large,
behavior-free diff and belongs in a later isolated mechanical change if needed.

Building or using Android SDK tooling is subject to Google's Android SDK
License Agreement. The repository does not pre-accept SDK component licenses.

## Acceptance gates

Run:

```text
python tools/check_repository_hygiene.py
python tools/verify_environment.py
```

Inside the dev container both commands must pass. On a host without Android
command-line tools, environment verification reports the missing optional
tooling without failing.
