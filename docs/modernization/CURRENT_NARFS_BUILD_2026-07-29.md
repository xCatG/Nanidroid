# Historical NarFS build (2026-07-29)

> This snapshot is retained as modernization evidence, not current build
> guidance. The active app no longer builds or packages NARFS; use
> `build.gradle.kts` and `jni/CMakeLists.txt` as the authoritative native-build
> definition.

At the time of this snapshot, the Android application built `libnarfs.so` from
`jni/narfs` through AGP external CMake build integration. It packaged only
`arm64-v8a` and `x86_64`; Kawari/Satori JNI engines were not part of that
product and their descriptors retained the existing `NotSupportedShiori`
behavior.

Validation: `assembleEmulator` built `narfs_full` for both ABIs and its APK
contains `lib/arm64-v8a/libnarfs.so` and `lib/x86_64/libnarfs.so`.
