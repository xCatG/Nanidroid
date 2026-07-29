# Modern mainline status

`codex/modernization-next` contains only the current Android application.
Historical Ant, Docker, Java, APK-comparison, and unsupported native-SHIORI
material is archived on the local `codex/legacy-reference` branch, not in this
branch.

Native runtime support is limited to the actively built NarFS JNI library.
AGP/CMake builds `narfs_full` from `jni/narfs` for `arm64-v8a` and `x86_64`.
Kawari and Satori descriptors continue to use `NotSupportedShiori`; simple
`NanidroidShiori` ghosts are unchanged.

Required local validation is `compileEmulatorKotlin`, `assembleEmulator`, the
JVM characterization suite, and release assembly/lint. The emulator APK must
contain `libnarfs.so` for both active ABIs and no unsupported SHIORI libraries.

The mainline archival deletion was validated with the complete 73-test host
contract suite, `testEmulatorUnitTest`, `assembleRelease`, and
`lintVitalRelease`. The archived project and its Docker/Ant lane are retained
only on the local `codex/legacy-reference` branch.
