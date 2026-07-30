# Adaptive Launcher Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Android 8.0+ adaptive launcher-icon support while preserving the existing Nanidroid illustration and legacy-device fallback.

**Architecture:** The manifest resolves `@mipmap/ic_launcher`. A base bitmap resource delegates to the current density-qualified drawable PNGs, while an API 26-qualified adaptive icon combines a green background with a padded foreground copy of the existing xhdpi artwork.

**Tech Stack:** Android resource XML, Android Gradle Plugin/Gradle wrapper, PowerShell.

## Global Constraints

- Preserve all existing `res/drawable-*/ic_launcher.png` files unchanged.
- Do not redesign the Nanidroid character-and-Android artwork.
- Keep all illustrated content inside the adaptive-icon safe zone.
- Do not alter application code or screen UI.
- Maintain compatibility with `minSdkVersion="9"`.

---

### Task 1: Add resource hierarchy and adaptive artwork

**Files:**
- Create: `res/mipmap/ic_launcher.xml`
- Create: `res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `res/drawable/ic_launcher_background.xml`
- Create: `res/drawable-nodpi/ic_launcher_foreground.png`
- Preserve: `res/drawable-ldpi/ic_launcher.png`, `res/drawable-mdpi/ic_launcher.png`, `res/drawable-hdpi/ic_launcher.png`, `res/drawable-xhdpi/ic_launcher.png`

**Interfaces:**
- Consumes: `res/drawable-xhdpi/ic_launcher.png`.
- Produces: `@mipmap/ic_launcher`, which resolves to an adaptive icon on API 26+.

- [ ] **Step 1: Write the resource-contract check**

```powershell
@('res/mipmap/ic_launcher.xml','res/mipmap-anydpi-v26/ic_launcher.xml','res/drawable/ic_launcher_background.xml','res/drawable-nodpi/ic_launcher_foreground.png') | ForEach-Object { Test-Path $_ }
```

- [ ] **Step 2: Run it before implementation**

Run the Step 1 command. Expected: `False` for all four paths.

- [ ] **Step 3: Create the foreground and XML layers**

Create `res/drawable-nodpi/ic_launcher_foreground.png` as a 432×432 transparent PNG. Android's adaptive safe zone is a 264px-diameter circle within that canvas, so center an upscaled copy of the 96×96 xhdpi artwork in a 186×186 square at `(123,123)`, which is inscribed in that circle; do not crop, repaint, or generate replacement art.

Create `res/drawable/ic_launcher_background.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#71B900" />
</shape>
```

Create `res/mipmap/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/ic_launcher" />
```

Create `res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 4: Run the resource-contract check**

Run the Step 1 command and inspect the XML. Expected: all paths exist; the adaptive icon has exactly one background and foreground; the PNG is 432×432 and all illustrated content is contained within the 264px-diameter circular safe zone.

- [ ] **Step 5: Commit the resource hierarchy**

```bash
git add res/mipmap/ic_launcher.xml res/mipmap-anydpi-v26/ic_launcher.xml res/drawable/ic_launcher_background.xml res/drawable-nodpi/ic_launcher_foreground.png
git commit -m "add adaptive launcher icon resources"
```

### Task 2: Wire the manifest and build the APK

**Files:**
- Modify: `AndroidManifest.xml:15-19`
- Verify: APK resources produced by the Gradle debug build

**Interfaces:**
- Consumes: `@mipmap/ic_launcher` from Task 1.
- Produces: an application icon that is adaptive on API 26+ and drawable-backed on older devices.

- [ ] **Step 1: Capture the current manifest icon reference**

```powershell
[xml]$manifest = Get-Content -Raw AndroidManifest.xml
$manifest.manifest.application.icon
```

Expected: `@drawable/ic_launcher`.

- [ ] **Step 2: Update manifest wiring**

Change the application declaration to use:

```xml
android:icon="@mipmap/ic_launcher"
```

- [ ] **Step 3: Verify the updated reference**

Run the Step 1 command. Expected: `@mipmap/ic_launcher`.

- [ ] **Step 4: Build and inspect packaged resources**

```powershell
.\gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Use the SDK's `aapt2 dump resources` (or equivalent) on the generated APK and verify it includes both `res/mipmap/ic_launcher.xml` and `res/mipmap-anydpi-v26/ic_launcher.xml`.

- [ ] **Step 5: Commit manifest wiring**

```bash
git add AndroidManifest.xml
git commit -m "use adaptive launcher icon"
```
