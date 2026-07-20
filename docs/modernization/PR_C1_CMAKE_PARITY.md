# PR C1 — CMake native-build parity

## Purpose

This slice introduces CMake as a second native build engine without changing
the native product contract. The existing `Android.mk` build remains the
reference. Both engines compile the untouched Kawari and Satori sources, and a
strict comparator must accept the CMake candidate before Gradle can consume it.

This PR does not change C/C++ or Java sources, ABI or API support, compiler,
STL, JNI class names, dependencies, source layout, or the Gradle build files.

## Frozen toolchain

The CMake lane uses the same Android NDK r14b toolchain as the reference:

| Setting | Frozen value |
| --- | --- |
| Android NDK | r14b |
| Android API | 9 |
| ABI | `armeabi` |
| Architecture mode | Thumb |
| Compiler | GCC 4.9 |
| STL | `gnustl_static` |
| CMake | 3.22.1 |

The official CMake 3.22.1 Linux x86-64 archive is downloaded in the legacy
image and verified with SHA-256
`73565c72355c6652e9db149249af36bcab44d9d478c5546fd926e69ad6b43640`.
The gate verifies `source.properties` identifies NDK revision `14.1.3816874`
and invokes the frozen compiler to verify its exact path, `-dumpversion`
result, and banner. It does not trust command-line labels. The disposable
build copy retains the case-compatibility aliases needed by the historical
Windows-authored sources. The candidate also states ndk-build's effective
release defaults explicitly: `NDEBUG` for both modules and `-Os` for Kawari,
while Satori's tracked `-O0` remains the final effective optimization flag.

The Kawari `Android.mk` declares seven paths through
`LOCAL_CPP_INCLUDES`, but NDK r14b does not place that variable's entries on
the real GCC command line. Its effective project include search path is only
`jni/kawari8`. CMake intentionally mirrors that measured root. Satori's
`LOCAL_C_INCLUDES` entries are applied by r14b and remain mirrored in order.
The command-evidence gate, rather than build-file declarations alone, enforces
these effective include roots.

After linking, the candidate libraries are processed by the frozen r14b
`arm-linux-androideabi-strip --strip-unneeded`, matching ndk-build's installed
release-library behavior before inspection and Gradle packaging.

## Run and inspect

```powershell
docker compose -f docker/legacy/compose.yaml build
docker compose -f docker/legacy/compose.yaml run --rm build
docker compose -f .devcontainer/compose.yaml run --rm dev ./docker/gradle/build.sh
```

The ignored `artifacts/legacy/` directory contains:

- `native-ndk-build.json` and `native-ndk-build/armeabi/*.so`
- `native-cmake.json` and `native-cmake/armeabi/*.so`
- `native-parity.json`
- `native/armeabi/*.so`, the parity-checked CMake payload consumed by Gradle

Every legacy run first removes all previously published native directories,
reports, and APKs. It builds into a fresh stage under the output filesystem and
promotes each result with a same-filesystem rename only after the comparator is
green. A failed or interrupted run removes its stage and leaves no stale
published native input for Gradle to consume.

## TDD and parity boundary

The focused contract tests first produced a candidate-absent red result:
12 tests ran, 11 passed, and the only error reported the missing
`jni/CMakeLists.txt`. After wiring the candidate, the suite is green and covers
both build declarations and negative drift cases. The subsequent adversarial
hardening began red because actual build-evidence and APK-payload verifiers did
not exist; the final focused suite runs 23 tests and the complete tooling suite
runs 33.

The reference build records `ndk-build V=1`; CMake exports
`compile_commands.json` and its generated `link.txt` files. The inspector
normalizes those real commands and requires exact parity for:

- missing candidate artifacts;
- ordered compile and link source files;
- effective definitions, material flags, and project include-root order;
- ordered link libraries and the exact `gnustl_static` archive;
- ABI/API sysroot, ARM target flags, compiler path/banner, NDK identity, and
  STL ABI paths;
- `Application.mk`'s `APP_STL` and `APP_CPPFLAGS`;
- ELF dependencies, exported JNI symbols, and `readelf -A` CPU, ARM, and Thumb
  attributes.

The parser rejects source or include paths escaping both the repository and
frozen NDK roots, duplicate or unknown CMake operations, and any undeclared
target mutation. Tests exercise these rejection paths as well as ordinary
contract drift.

The executable comparator requires exact parity for the frozen NDK and toolchain,
normalized build declarations and measured commands, ELF32 little-endian
ARM/EABI and soft-float properties, ARMv5TE/ARM/Thumb-1 attributes, SONAME,
`DT_NEEDED`, and JNI exports. It records but intentionally does not compare the
build-system name, CMake version, timestamps, debug sections, build IDs, or
whole-file hashes. Hashes are provenance because independent build systems
need not produce byte-identical ELF files.

The final Gradle build keeps its existing native input path. That path is
populated only from the parity-checked, stripped CMake candidate. The Gradle
build automatically runs `verify_apk_native_payload.py` after the APK contract
comparison and before publishing the APK. It rejects missing or extra native
entries and requires the APK's two native payloads to be byte-for-byte
identical to `native-cmake/armeabi/*.so`; the result and hashes are written to
`artifacts/gradle/native-payload.json`.

The legacy reference, stripped candidate, and final APK sizes from the verified
run were:

| Artifact | ndk-build reference | CMake candidate |
| --- | ---: | ---: |
| `libkawari8.so` | 662,160 bytes | 658,064 bytes |
| `libsatoriya.so` | 1,140,504 bytes | 1,136,408 bytes |

The Ant reference APK was 1,117,407 bytes and the final Gradle APK was
2,045,674 bytes. APK sizes and hashes are provenance, not parity assertions:
the application packaging engines and signing inputs differ. The verified
Gradle APK's two native entries were byte-identical to the stripped CMake
candidate, with SHA-256 values
`9b8d31d6e06d6b8b6a2d8ec208cf200872a78c3204f299e0c1e038720dadf067`
and
`9f72243c7609ca82258e82b0e76bf05dd89488e477e6d67a2bc12b865044b8ec`.
