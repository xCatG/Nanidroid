# Current NarFS build

The active Android application builds `libnarfs.so` from `jni/narfs` through
AGP external CMake build integration. It packages only `arm64-v8a` and
`x86_64`; Kawari/Satori JNI engines are not part of the modern product and
their descriptors retain the existing `NotSupportedShiori` behavior.

Validation: `assembleEmulator` built `narfs_full` for both ABIs and its APK
contains `lib/arm64-v8a/libnarfs.so` and `lib/x86_64/libnarfs.so`.
