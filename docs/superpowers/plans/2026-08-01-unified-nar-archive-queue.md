# Unified NAR Archive Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a durable queue for HTTPS downloads and local content URI NAR installation.

**Architecture:** Existing Activity/UI delegates archive actions to `NarDownloadRepository`. DownloadManager transfers remote data; a unique WorkManager worker performs one typed, cancellable install attempt; durable queue state drives the UI.

**Tech Stack:** Kotlin, Compose, DownloadManager, WorkManager, serialized local persistence, JUnit 4, MockK, Python contract tests.

## Global Constraints

- minSdk 31; target and compile SDK 37.
- HTTPS manual URLs only; no generic HTTPS `ACTION_VIEW` route.
- SAF and Open-with `content://` imports use one queue path; a non-persistable grant is copied before the Activity returns.
- Archive failures become user-controlled Retry/Delete, never automatic loops.
- Enforce archive byte limits during all streaming copies.

---

### Task 1: Persist queue records

**Files:** Create `src/main/kotlin/com/cattailsw/nanidroid/install/NarDownload.kt`, `NarDownloadStore.kt`, and `src/test/java/com/cattailsw/nanidroid/install/NarDownloadStoreTest.kt`.

**Interfaces:** Produce immutable `NarDownload`, `NarDownloadSource`, `NarDownloadState`, and serialized `create`, `update`, `get`, `getAll`, `delete` operations.

- [ ] **Step 1: Write failing record tests**

```kotlin
@Test fun updatingOneRecordDoesNotDiscardAnother() {
    store.create(remote(id = "a", state = Downloading))
    store.create(remote(id = "b", state = Downloading))
    store.update("a") { it.copy(state = NeedsAttention(Failure("offline"))) }
    assertEquals(Downloading, store.get("b")!!.state)
}
@Test fun needsAttentionRetainsItsSourceForRetry() {
    val item = remote(id = "a", retainedUri = retainedFile)
    store.create(item); store.update("a") { it.copy(state = NeedsAttention(Failure("invalid archive"))) }
    assertEquals(retainedFile, store.get("a")!!.retainedUri)
}
```

- [ ] **Step 2: Run the focused test**

Run: `./gradlew.bat testDebugUnitTest --tests '*NarDownloadStoreTest'`
Expected: FAIL because records/store do not exist.

- [ ] **Step 3: Implement immutable records and serialized mutations**

```kotlin
data class NarDownload(val id: String, val source: NarDownloadSource, val state: NarDownloadState)
```

- [ ] **Step 4: Re-run focused tests**

Run: `./gradlew.bat testDebugUnitTest --tests '*NarDownloadStoreTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

Run: `git add src/main/kotlin/com/cattailsw/nanidroid/install/NarDownload.kt src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadStore.kt src/test/java/com/cattailsw/nanidroid/install/NarDownloadStoreTest.kt; git commit -m "feat: persist NAR download queue records"`

### Task 2: Make import/install typed, bounded, and cancellable

**Files:** Modify `install/NarContentUriImport.kt`, `install/NarTransactionalInstaller.kt`, and `GhostMgr.kt`; extend their existing tests.

**Interfaces:** Produce `ArchiveInstallResult`; all long-running copy/extract APIs accept `isCancelled: () -> Boolean`.

- [ ] **Step 1: Write failing safety tests**

```kotlin
@Test fun copyRejectsMaximumBytesPlusOneWithoutCommittedStaging() {
    val result = importer.copy(ByteArray(MAX_ARCHIVE_BYTES + 1).inputStream(), neverCancelled)
    assertTrue(result is ArchiveInstallResult.Failed); assertFalse(stageFile.exists())
}
@Test fun cancellationDeletesPartialStaging() {
    val result = importer.copy(repeatingInput, isCancelled = { bytesRead >= 1024 })
    assertEquals(ArchiveInstallResult.Cancelled, result); assertFalse(stageFile.exists())
}
```

- [ ] **Step 2: Run focused tests**

Run: `./gradlew.bat testDebugUnitTest --tests '*NarContentUriImportTest' --tests '*NarTransactionalInstallerTest'`
Expected: FAIL because the APIs have no typed outcome/cancellation.

- [ ] **Step 3: Implement minimal typed/cancellable pipeline**

```kotlin
fun importContent(uri: Uri, isCancelled: () -> Boolean): ArchiveInstallResult
```

- [ ] **Step 4: Re-run focused tests**

Run: `./gradlew.bat testDebugUnitTest --tests '*NarContentUriImportTest' --tests '*NarTransactionalInstallerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

Run: `git add src/main/kotlin/com/cattailsw/nanidroid/install src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt src/test/java/com/cattailsw/nanidroid/install; git commit -m "feat: make NAR installation cancellable"`

### Task 3: Add repository and background hand-off

**Files:** Modify `gradle/libs.versions.toml`, `build.gradle.kts`, and `AndroidManifest.xml`; create `install/NarDownloadRepository.kt`, `install/NarDownloadReceiver.kt`, `install/NarDownloadRecoveryReceiver.kt`, `install/InstallNarWorker.kt`, and `NarDownloadRepositoryTest.kt`.

**Interfaces:** Consume Tasks 1–2; produce `enqueueRemote`, `enqueueLocal`, `retry`, `delete`, and `observeDownloads`.

- [ ] **Step 1: Write failing repository tests**

```kotlin
@Test fun unknownCompletionDoesNotScheduleWork() {
    repository.onDownloadComplete(999L); assertTrue(workScheduler.enqueuedNames.isEmpty())
}
@Test fun failedInstallPersistsNeedsAttentionAndWorkerSucceeds() {
    installer.result = ArchiveInstallResult.Failed("invalid archive", InvalidArchive)
    assertEquals(Result.success(), worker.doWork()); assertIs<NeedsAttention>(store.get(id)!!.state)
}
@Test fun deleteCancelsUniqueWorkAndDeletesOwnedData() {
    repository.delete(id); assertEquals("install-nar-$id", workScheduler.cancelledName); assertEquals(downloadId, gateway.removedId)
}
@Test fun revokedPersistedUriBecomesNeedsAttention() {
    resolver.openFailure = SecurityException(); worker.doWork(); assertIs<NeedsAttention>(store.get(id)!!.state)
}
@Test fun reconciliationSchedulesCompletedRegisteredDownload() {
    gateway.status = Successful; repository.reconcile(); assertEquals("install-nar-$id", workScheduler.enqueuedNames.single())
}
@Test fun missingProviderBecomesNeedsAttention() {
    resolver.openFailure = FileNotFoundException(); worker.doWork(); assertIs<NeedsAttention>(store.get(id)!!.state)
}
@Test fun deleteThenReenqueueUsesSeparateStagingDirectories() {
    repository.delete(oldId); val newId = repository.enqueueRemote(url).id; assertNotEquals(stageDirectory(oldId), stageDirectory(newId))
}
```

- [ ] **Step 2: Run focused tests**

Run: `./gradlew.bat testDebugUnitTest --tests '*NarDownloadRepositoryTest'`
Expected: FAIL because repository/worker do not exist.

- [ ] **Step 3: Add WorkManager and implementation**

```kotlin
workManager.enqueueUniqueWork("install-nar-$id", ExistingWorkPolicy.KEEP, request)
```

- [ ] **Step 4: Add boot/package-replaced and startup reconciliation, plus worker cancellation forwarding**

```kotlin
override suspend fun doWork(): Result = repository.install(inputData.id, ::isStopped)
```

- [ ] **Step 5: Re-run focused tests and commit**

Run: `./gradlew.bat testDebugUnitTest --tests '*NarDownloadRepositoryTest'`
Expected: PASS.

Run: `git add gradle/libs.versions.toml build.gradle.kts src/main/kotlin/com/cattailsw/nanidroid/install src/main/AndroidManifest.xml src/test/java/com/cattailsw/nanidroid/install/NarDownloadRepositoryTest.kt; git commit -m "feat: queue DownloadManager NAR installs"`

### Task 4: Connect Activity/UI and retire HTTPS route

**Files:** Modify `Nanidroid.kt`, `AndroidManifest.xml`, and `NarArchiveCharacterizationTest.kt`; delete `IncomingNarIntent.kt` and `LegacyNotificationBridge.kt`.

**Interfaces:** Consume Task 3 repository API; Activity adapts manual URLs, picker results, and validated content-view intents.

- [ ] **Step 1: Write failing intent/UI tests**

```kotlin
@Test fun httpsViewIntentIsRetired() {
    assertFalse(ArchiveIntentAdapter.accepts(Intent(ACTION_VIEW, Uri.parse("https://host/a.nar"))))
}
@Test fun grantedContentViewIntentEnqueuesLocalImport() {
    val intent = Intent(ACTION_VIEW, uri).addFlags(FLAG_GRANT_READ_URI_PERMISSION)
    assertEquals(Local(uri), ArchiveIntentAdapter.toSource(intent))
}
@Test fun temporaryContentGrantIsCopiedBeforeWorkerScheduling() {
    adapter.handleTemporaryGrant(uri); assertTrue(privateCopy.exists()); assertEquals(privateCopy, repository.lastEnqueuedSource)
}
```

- [ ] **Step 2: Run focused test**

Run: `./gradlew.bat testDebugUnitTest --tests '*NarArchiveCharacterizationTest'`
Expected: FAIL against master’s HTTPS intent route.

- [ ] **Step 3: Route all inputs and queue state through repository**

```kotlin
repository.observeDownloads().collect { downloads ->
    renderDownloadRows(downloads)
    if (downloads.any { it.state == Installed }) ghostManager.reload()
}
```

- [ ] **Step 4: Re-run focused test and commit**

Run: `./gradlew.bat testDebugUnitTest --tests '*NarArchiveCharacterizationTest'`
Expected: PASS.

Run: `git add src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/main/AndroidManifest.xml src/test/java/com/cattailsw/nanidroid/NarArchiveCharacterizationTest.kt; git rm src/main/kotlin/com/cattailsw/nanidroid/IncomingNarIntent.kt src/main/kotlin/com/cattailsw/nanidroid/LegacyNotificationBridge.kt; git commit -m "feat: unify NAR archive entry points"`

### Task 5: Update contracts and validate

**Files:** Modify `tools/test_kotlin_incoming_nar_intent_contract.py`, `tools/test_kotlin_nanidroid_service_contract.py`, and `tools/test_kotlin_nanidroid_activity_contract.py`; create `tools/test_kotlin_nar_download_queue_contract.py`.

- [ ] **Step 1: Replace retired HTTPS/service assertions with queue invariants**

```python
def test_manifest_has_no_generic_https_archive_view_filter():
    assert 'android:scheme="https"' not in manifest_text
def test_local_view_filter_has_no_wildcard_mime_type():
    assert 'android:mimeType="*/*"' not in manifest_text
```

- [ ] **Step 2: Run contracts**

Run: `python -m unittest discover -s tools -p 'test_*contract.py'`
Expected: PASS.

- [ ] **Step 3: Run Android verification and commit**

Run: `./gradlew.bat testDebugUnitTest assembleDebug lint`
Expected: BUILD SUCCESSFUL.

Run: `git add tools; git commit -m "test: cover NAR archive queue contracts"`
