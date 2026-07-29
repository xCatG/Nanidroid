# Modern mainline status

`codex/modernization-next` contains only the current Android application.
Historical Ant, Docker, Java, APK-comparison, and unsupported native-SHIORI
material is archived on the local `codex/legacy-reference` branch, not in this
branch.

## Compose stage retirement

The active renderer is now the Compose `ComposeGhostStageHost` plus its pure
surface compositor, pointer dispatcher, and animation scheduler. The dormant
retained `SakuraView`, `KeroView`, `Balloon`, `LayoutManager`, and both
View-backed renderer adapters have been removed, along with the
`SScriptRunner.setViews`/`setLayoutMgr` fallback path. The replacement device
contract exercises Compose stage visibility and Compose scheduler talk-frame
selection; the full JVM suite and host contracts pass. The focused class passed
2/2 tests on the `Nanidroid_API_37` x86_64 emulator (API 37), which was then
stopped.

The remaining active View/XML work is intentionally limited to the
support-fragment dialog/readme layer and its activity host; it is outside this
stage-only retirement slice and needs the separate document-rendering decision.

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
