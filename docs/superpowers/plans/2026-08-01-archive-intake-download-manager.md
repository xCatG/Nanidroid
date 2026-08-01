# Archive Intake and DownloadManager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve #160 and #161 by removing generic external HTTPS archive intents and migrating manual remote archive downloads to a verified DownloadManager handoff.

**Architecture:** `RemoteNarUrl` is the pure HTTPS/archive validation boundary used by the URL-entry UI and DownloadManager coordinator. A non-exported `NarDownloadReceiver` re-validates a recorded successful DownloadManager completion and passes its descriptor stream to the existing private staged import facility, which invokes `GhostMgr`.

**Tech Stack:** Kotlin, Android `DownloadManager`, `BroadcastReceiver`, JUnit 4, MockK.

## Global Constraints

- minSdk 31, targetSdk 37.
- Preserve the local SAF picker flow and existing transactional install policy.
- Do not accept external archive intents or arbitrary local file paths.
- Do not change polling or ghost-update service behavior.

---

### Task 1: Retire generic external archive intent routing

**Files:**
- Modify: `src/main/AndroidManifest.xml`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Delete: `src/main/kotlin/com/cattailsw/nanidroid/IncomingNarIntent.kt`

**Produces:** no exported `ACTION_VIEW` HTTPS archive filter and no lifecycle archive-intent intake.

- [ ] **Step 1: Write a manifest/source regression test**

Create a JVM characterization asserting the manifest has no `<data android:scheme="https"/>` under an `ACTION_VIEW` filter and that the activity no longer calls `handleIncomingIntent`.

- [ ] **Step 2: Run the characterization test and confirm it fails**

Run: `./gradlew.bat testDebugUnitTest --tests '*ArchiveIntentRetirementTest'`

- [ ] **Step 3: Remove the filter, lifecycle handling, and obsolete type**

Keep the launcher filter. Remove `handleIncomingIntent`, `onNewIntent`, and the obsolete file. Do not alter `ACTION_VIEW` links used to open help pages.

- [ ] **Step 4: Re-run the test and commit**

Run: `./gradlew.bat testDebugUnitTest --tests '*ArchiveIntentRetirementTest'`

### Task 2: Add URL policy and reusable private stream staging

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/RemoteNarUrl.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/NarContentUriImport.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/RemoteNarUrlTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/install/NarContentUriImportTest.kt`
- Modify: `build.gradle.kts`

**Produces:** `RemoteNarUrl.isApproved(Uri?): Boolean` and `NarContentUriImport.importStream(File?, () -> InputStream?, (File) -> String?): Result`.

- [ ] **Step 1: Write failing tests**

Test accepted HTTPS `.nar`/`.zip` URLs and rejection of HTTP, missing host, query-only extension, and non-archive paths. Test that `importStream` invokes installation after a private copy and always deletes its staging file.

- [ ] **Step 2: Run only those tests and confirm failure**

Run: `./gradlew.bat testDebugUnitTest --tests '*RemoteNarUrlTest' --tests '*NarContentUriImportTest'`

- [ ] **Step 3: Implement the smallest pure policy and stream delegate**

`RemoteNarUrl` examines only the URI scheme, host, and path suffix. `importContent` keeps its `content`-scheme check, then delegates to `importStream`.

- [ ] **Step 4: Re-run tests and commit**

Run: `./gradlew.bat testDebugUnitTest --tests '*RemoteNarUrlTest' --tests '*NarContentUriImportTest'`

### Task 3: Use DownloadManager for remote archive installation

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/NarDownloadManager.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/NarDownloadReceiver.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Modify: `src/main/AndroidManifest.xml`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/NanidroidService.kt`

**Produces:** `NarDownloadManager.enqueue(Context, Uri): Long?`, and a non-exported receiver for `DownloadManager.ACTION_DOWNLOAD_COMPLETE`.

- [ ] **Step 1: Write a failing coordinator unit test**

Use an injected `DownloadManager` seam to prove valid URLs enqueue with a private external-files destination and invalid URLs do not enqueue.

- [ ] **Step 2: Run the coordinator test and confirm failure**

Run: `./gradlew.bat testDebugUnitTest --tests '*NarDownloadManagerTest'`

- [ ] **Step 3: Implement coordinator, receiver, and UI wiring**

Store download IDs in private shared preferences. The receiver checks recorded IDs, queries for `STATUS_SUCCESSFUL`, opens the descriptor, calls `NarContentUriImport.importStream(File(cacheDir, "nar-import"), ...) { GhostMgr(context).installGhost("download", it.path) }`, removes the record and manager entry, and finishes its async broadcast. The activity calls `enqueue` from the URL dialog. Remove `ACTION_RUN`, `NarDownloadTask`, and archive notification code from the service without disturbing its update behavior.

- [ ] **Step 4: Run focused tests and commit**

Run: `./gradlew.bat testDebugUnitTest --tests '*NarDownloadManagerTest' --tests '*RemoteNarUrlTest' --tests '*NarContentUriImportTest'`

### Task 4: Full verification and GitHub resolution

**Files:** Review all changed files.

- [ ] **Step 1: Run complete verification**

Run: `./gradlew.bat testDebugUnitTest`, `./gradlew.bat lint`, and `./gradlew.bat assembleDebug`.

- [ ] **Step 2: Inspect final requirements**

Confirm the manifest has no generic HTTPS archive filter, local picker remains, DownloadManager is used for entered remote URLs, receiver is non-exported, and no archive notification/service task remains.

- [ ] **Step 3: Commit, push, open a PR, and close #160 and #161**

Use a PR body that links `Fixes #160` and `Fixes #161`; close the issues after the PR is created.
