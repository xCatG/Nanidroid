# PR B1 — Frozen legacy build lane

## Purpose

This lane answers one question before the build-system migration begins:
can the source inherited from `master` still produce an installable APK, with
both JNI engines, in a clean Linux environment?

It is a reference build, not the future development toolchain. PR B2 will
introduce Gradle independently so its output can be compared with this lane.
No production Java, C++, resource, manifest, or dependency file is changed by
PR B1.

## Run it

Docker Desktop on Windows must be configured to use Linux containers.

```powershell
docker compose -f docker/legacy/compose.yaml build
docker compose -f docker/legacy/compose.yaml run --rm build
```

Generated files are written to the ignored `artifacts/legacy/` directory:

- `Nanidroid-debug.apk`
- `Nanidroid-debug.json`
- `native/armeabi/libkawari8.so`
- `native/armeabi/libsatoriya.so`

The source checkout is mounted read-only and copied into a disposable
container directory before the build. Ant, ndk-build, and debug signing
therefore cannot dirty the checkout.

The `Legacy build` GitHub Actions workflow runs the inspector tests,
repository hygiene check, full container build, signature/alignment checks,
and artifact contract for pull requests targeting `feature/modernization`.
It uploads the reference outputs for seven days.

## Frozen toolchain

The image uses JDK 8 because the Android Ant tooling and `dx` predate current
Java runtimes. Android archives are downloaded from `dl.google.com` and
verified before extraction.

| Component | Version | Pinned digest |
| --- | --- | --- |
| Eclipse Temurin base image | JDK 8 on Ubuntu Jammy | image index `sha256:4e6409efc7e022f46b4969fb489c12fe2869c49f176829b948874a7c8ebb4b84` |
| Android SDK Tools | 25.2.5 | `72df3aa1988c0a9003ccdfd7a13a7b8bd0f47fc1` |
| Android SDK Build Tools | 25.0.3 | `db95f3a0ae376534d4d69f4cdb6fad20649f3509` |
| Android SDK Platform | API 15 revision 5 | `69ab4c443b37184b2883af1fd38cc20cbeffd0f3` |
| Android SDK Platform Tools | 29.0.6 | `e95ed28330406705d47fe96bafb589be6c1f2f23` |
| Android NDK | r14b | `becd161da6ed9a823e25be5c02955d9cbca1dbeb` |

NDK r14b is selected because r14 is the final NDK generation documented to
support API 9, the manifest minimum, and it still contains the
`gnustl_static` runtime required by `jni/Application.mk`. The build is limited
to the historical `armeabi` ABI. The lane supplies GCC 4.9 and
`-fpermissive` as container-only compatibility inputs for pre-standard C++
lookup accepted by the original compiler generation; tracked native sources
remain untouched. The disposable build copy also receives case aliases for
`Sender.h`, `Utilities.h`, and `satori.h`, whose include spellings relied on
the case-insensitive historical host filesystem.

PR B2 removes the unused `com.google.ads.*` import from the tracked Java source:
the Ads SDK is absent, the imported types have no live references, and the only
`addAdView` call is already commented out. AGP 9 also requires the package
namespace and SDK levels in Gradle rather than the source manifest. To keep
this frozen Ant lane comparable, its disposable build copy restores the same
package, minimum SDK 9, and target SDK 13 metadata before packaging.

## Executable artifact contract

`tools/inspect_legacy_apk.py` fails the build unless the APK preserves:

- package `com.cattailsw.nanidroid`
- version code `6` and version name `open_0.1`
- minimum SDK 9 and target SDK 13
- `classes.dex`, compiled resources, and the binary manifest
- ELF `libkawari8.so` and `libsatoriya.so` under `lib/armeabi/`

The inspector has host-independent unit tests, including negative cases for a
missing engine and a Windows PE file masquerading as an Android `.so`.

## Reproducibility boundary

The container makes the inputs and procedure repeatable, but the debug APK is
not claimed to be byte-for-byte reproducible. Ant creates a debug signing key
when one is absent, and ZIP entry/signature timestamps can vary. The JSON
report records the size and SHA-256 of each observed APK so comparisons are
explicit.

The exact SDK, JDK, NDK, ABI set, signing identity, and APK used for the last
historical release remain unknown. This lane is the nearest verified buildable
reference from repository evidence; it is not proof that its binary equals a
previously released artifact.
