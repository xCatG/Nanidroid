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

## Operational Compose dialogs

URL entry, script text input/choice, and ghost switching now use Compose
state/dialogs rather than support fragments, XML layouts, or `ArrayAdapter`.
The URL path keeps the existing regex and approved HTTPS/NAR checks before it
starts a download; IME Done invokes the same validation. Script input and
choice retain their `SScriptRunner.UICallback` completion/cancellation
semantics. Ghost selection keeps analytics plus the existing readme/no-readme
switch actions. Each dialog's minimal state is restored from
`savedInstanceState` after recreation. The focused Compose-shell class passed
6/6 tests on `Nanidroid_API_37` (API 37).

Readme, no-readme-switch, about, and WebView/document dialogs remain in the
support-fragment layer by design; they are not part of this non-HTML dialog
slice.

## Operational fragment cleanup

The remaining non-document `DialogFragment` paths—debug/error/notice, help,
more-ghost, not-implemented, and the obsolete multi-NAR selector—have been
removed. Their user-visible actions and error categories are owned by
`NanidroidSimpleDialog`; the active install action still launches the system
document picker. The redundant context menu, menu XML, dialog XML, and string
arrays were removed with those paths. The focused Compose-shell device class
passed 7/7 tests on API 37; the emulator JVM suite and all 69 host contracts
also passed.

## API 37 system-picker import proof

On the API 37 x86_64 emulator, the app used its visible Compose `List Ghosts`
and `More Ghost` actions to launch the platform `ACTION_OPEN_DOCUMENT` picker.
A deterministic `picker-nanidroid.zip` fixture, derived from
`assets/nanidroid.zip` with the otherwise-identical ghost identity changed to
`picker-nanidroid`, was selected from the device's public Downloads directory.
The picker result was staged in cache, transactionally published under the
app's external `ghost/picker-nanidroid` directory, and the staging directory
was empty afterward. Selecting the installed ghost persisted
`lastrunghost=picker-nanidroid` and rendered its initial “Hi there! Welcome
back!” balloon in the Compose stage. API 37 edge-to-edge rendering initially
placed the primary toolbar behind the status bar; the Compose shell now applies
`statusBarsPadding()` so the picker entry action remains visible and tappable.

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
