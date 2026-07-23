# PR D9b2b1c — narfs static Android build

This build-only slice compiles the existing portable `narfs_core` as a static
archive in both native engines. It does not add JNI, Java, a shared library,
Gradle native input, APK payload, copying, staging, or runtime integration.

The legacy lane produces an API 9 ARMv5TE Thumb `libnarfs_core.a` with
ndk-build and CMake. The emulator lane independently produces the API 21
AArch64 archive. Both use C99 with warnings as errors and link a disposable
public-header probe with `--no-undefined`.

`inspect_narfs_static.py` verifies matching declarations, archive/probe ELF
identity, the two public symbols, bounded libc imports, forbidden modern
filesystem/private-crypto symbols, and exact `libc.so` linkage. The legacy
reports are compared for ndk-build/CMake parity. Existing APK inspectors still
require exactly `libkawari8.so` and `libsatoriya.so`; archives and probes never
enter published native directories.

The next slice may add the third shared JNI DSO and runtime packaging.
