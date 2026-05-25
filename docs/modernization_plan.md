# Implementation Plan: Address Code Review Comments & Fix UI Issues

This plan details the changes required to address the code review feedback received on GitHub, fix the Shift-JIS stream-parsing bug in `DescReader.kt`, and resolve layout conflicts in `MainScreen.kt`.

## User Review Required

> [!IMPORTANT]
> **Native STL / Permissive C++ compilation**:
> The reviewer recommended removing the `-fpermissive` compilation flag. Standardizing all legacy code in Kawari/Satori to compile without `-fpermissive` under modern clang/ndk represents a very high risk of introducing compile failures or unintended runtime bugs in mascot dialogue generation. We will attempt to remove the flag for `satoriya` target first, check the errors, and resolve simple ones. If the errors are too extensive, we will retain the flag for compatibility while explaining the risk.

> [!TIP]
> **MainScreen Layout Optimization**:
> To fix the layout warning regarding nesting a `LazyColumn` inside a scrollable parent `Column`, we will refactor `CatalogCard` to render its list of installed mascots as a standard Compose `Column` instead of a `LazyColumn`. Since users typically have only a few mascots installed, this avoids nesting scrollable components, eliminates layout measurement conflicts, and provides a much smoother scrolling experience.

---

## Proposed Changes

### Core System (Java/Kotlin)

#### [MODIFY] [DescReader.kt](../app/src/main/java/com/cattailsw/nanidroid/DescReader.kt)
- Read all bytes from the `InputStream` or `File` up front into a `ByteArray` to prevent closing and losing the underlying stream when detecting the charset.
- Use `ByteArrayInputStream` to read first line for charset detection, then parse using the detected charset.

#### [MODIFY] [LayoutManager.kt](../app/src/main/java/com/cattailsw/nanidroid/LayoutManager.kt)
- Wrap view properties (`sv`, `kv`, `bSakura`, `bKero`, `fl`) in `WeakReference` to prevent memory leaks in the singleton.
- Add `clearViews()` method to release references immediately.
- Scale original mascot dimensions (`origW`, `origH`) by screen density (`resources.displayMetrics.density`) to ensure correct physical size on higher DPI screens.

#### [MODIFY] [SakuraView.kt](../app/src/main/java/com/cattailsw/nanidroid/SakuraView.kt)
- Map touch/hit event coordinates back to original image space in `testColDect()` by dividing coordinates by the view's current horizontal/vertical scale factor, ensuring correct collision/hit registration when scaled on high DPI displays.

#### [MODIFY] [SScriptRunner.kt](../app/src/main/java/com/cattailsw/nanidroid/SScriptRunner.kt)
- Wrap view properties in `WeakReference`.
- Add `clearViews()` method to release references.

#### [MODIFY] [Ghost.kt](../app/src/main/java/com/cattailsw/nanidroid/Ghost.kt)
- Store `ctx?.applicationContext` as `mCtx` instead of a strong reference to the Activity/Service context.

#### [MODIFY] [BottleLogSensor.kt](../app/src/main/java/com/cattailsw/nanidroid/BottleLogSensor.kt)
- Rename `getUrlContent` to `getAssetContent` to accurately reflect that it reads from local assets.

#### [MODIFY] [MainActivity.kt](../app/src/main/java/com/cattailsw/nanidroid/MainActivity.kt)
- Use `lifecycleScope.launch` instead of `CoroutineScope(Dispatchers.Main).launch` to manage coroutine cancellation automatically.

#### [MODIFY] [NanidroidService.kt](../app/src/main/java/com/cattailsw/nanidroid/NanidroidService.kt)
- Use `use {}` block for resource safety when reading the update MD5 checksum file.

#### [MODIFY] [OverlayMascotService.kt](../app/src/main/java/com/cattailsw/nanidroid/OverlayMascotService.kt)
- Call `clearViews()` on `LayoutManager` and `SScriptRunner` in `onDestroy()` to prevent leaking overlay views.
- Pass `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` in `startForeground` when running on Android 10 (Q) or higher (API 29+) to align with Android 14+ runtime requirements for declared foreground services.

### Utilities

#### [MODIFY] [NarUtil.kt](../app/src/main/java/com/cattailsw/nanidroid/util/NarUtil.kt)
- Replace deprecated `Environment.getExternalStorageDirectory()` and hardcoded `nar` path with `context.getExternalFilesDir("nar")` to ensure scoped storage compatibility.

#### [MODIFY] [NarUtilTest.kt](../app/src/test/java/com/cattailsw/nanidroid/test/NarUtilTest.kt)
- Refactor the unit test to dynamically create a temporary ZIP/NAR file containing a valid `install.txt` file at runtime, instead of relying on a hardcoded, non-existent `C:\tmp\2elf-2.41.nar` file.

### User Interface (Compose)

#### [MODIFY] [MainScreen.kt](../app/src/main/java/com/cattailsw/nanidroid/ui/main/MainScreen.kt)
- Clear views in `onDispose {}` inside `InAppMascotView` to release view references immediately when Compose view leaves the composition.
- Refactor `CatalogCard` to list installed mascots using a standard `Column` instead of a `LazyColumn` to avoid nested scrolling conflicts and fit smoothly inside scrollable lists/sheets.
- Redesign the dashboard layout to show only the `HologramChamber` (mascot and balloon) by default in the main body.
- Add a transparent `TopAppBar` with a settings gear icon button.
- Implement a modal bottom sheet overlay (`ModalBottomSheet`) triggered by the gear icon to display the control cards (`ControlCenterCard`, `CatalogCard`, `ConsoleCard`).

#### [MODIFY] [MainScreenViewModel.kt](../app/src/main/java/com/cattailsw/nanidroid/ui/main/MainScreenViewModel.kt)
- Extend `AndroidViewModel(application)` to obtain the application context cleanly, removing the need to pass `Context` parameters to ViewModel methods.
- Introduce an `isInitialized` flag to track initialization state, ensuring that screen rotation (which recreates the Compose view hierarchy) does not trigger re-initialization of the `Ghost` instance, keeping the mascot's session memory intact.

### Native Build & C++ Sources

#### [NO CHANGE] [stltool.h](../app/src/main/cpp/_/stltool.h)
- Retain `using namespace std;` namespace imports and vector subclassing (`strvec`). Over 50 legacy source files in Satori and Kawari rely on standard namespace pollution to compile; resolving it globally would be overly intrusive and high-risk.

#### [NO CHANGE] [satori_jni.cpp](../app/src/main/cpp/satori/satori_jni.cpp)
- Retain allocation logic for `pPath` inside JNI `load()` without calling `free(pPath)` in JNI. Ownership of the memory is transferred to the native `load()` function inside `SakuraDLLHost.cpp` which calls `free(i_data)`. Adding a manual `free()` call inside JNI would cause a fatal double-free crash.

#### [MODIFY] [CMakeLists.txt](../app/src/main/cpp/CMakeLists.txt)
- Attempt to remove `-fpermissive` from `satoriya` target and resolve standard C++ compilation issues.

---

## Verification Plan

### Automated Tests
- Run `./gradlew test` to ensure all unit tests (including the updated hermetic `NarUtilTest`) compile and pass.
- Run `./gradlew assembleDebug` to verify C++ native libraries and Kotlin classes build successfully.

### Manual Verification
- Install and launch the app on the emulator.
- Grant the system overlay window permission (SYSTEM_ALERT_WINDOW) to Nanidroid on the emulator via ADB using:
  ```bash
  adb shell appops set com.cattailsw.nanidroid SYSTEM_ALERT_WINDOW allow
  ```
- Switch between different ghosts in the Compose catalog to verify the layout and character images load without memory leaks or crashes.
- Turn on "Overlay Desktop Mode" to verify the Floating mascot renders correctly on the screen, and turn it off to verify it is destroyed and resources are cleared.
