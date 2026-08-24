# Foreground NAR Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every user-reachable archive queue, URL, receiver, and external-intent path with one application-lived `OpenDocument` import that safely survives Activity recreation but intentionally does not continue across process death.

**Architecture:** A token-safe `ForegroundNarImportCoordinator` owns one process-local picker/import state machine and delegates IO to a backend composed from `NarContentUriImport`, exact owned-staging recovery, and `NarTransactionalInstaller`. The Activity owns only the platform picker, registry-owner token, `GhostMgr` readiness barrier, and presentation; Compose renders coordinator state without copying it into Activity-restored dialog state. The cutover removes every production root of the legacy archive/durable workflow while leaving its now-unreachable classes and dependencies for the next deletion PR.

**Tech Stack:** Kotlin 2.3.21, Android API 31–37, `ActivityResultContracts.OpenDocument`, Kotlin coroutines/`StateFlow`, Jetpack Compose Material 3, JUnit 4, MockK, AndroidX instrumentation, Gradle 9.5/AGP 9.3.

**Spec:** `docs/superpowers/specs/2026-08-23-foreground-nar-import-design.md`

## Global Constraints

- Start from phase baseline `fc394aac49cdd2466c408f523c246a3084411d92`; the design/plan commits may precede implementation on the same focused branch.
- The only user archive ingress is `ActivityResultContracts.OpenDocument` with `*/*`; retained archive validation, not provider MIME metadata, decides validity.
- The picker/import token is `(processNonce: String, sequence: Long)`, with a random nonce per process and a monotonically increasing in-process sequence.
- No Activity, launcher, Activity `ContentResolver`, dialog callback, or Activity lifecycle scope may be captured by the coordinator.
- One selection creates at most one copy and one publication; token-aware compare-and-set transitions are the sole authority.
- Rotation reattaches to the in-process attempt; a new process rejects an old registry result, and a new task abandons an orphaned in-process picker owner.
- Copy staging is exactly `noBackupFilesDir/nar-import-v1/nar-import-<24 lowercase hex>.zip` and is capped at `544 * 1024 * 1024` bytes.
- Installer staging is exactly `externalFilesDir/ghost/.nanidroid-install-staging/candidate-<32 lowercase hex>`.
- Cleanup never follows symlinks, recurses outside an exact verified candidate, deletes an unmatched top-level entry, or deletes a published target.
- Logical target conflicts use `String.equals(other, ignoreCase = true)` under the installer lock.
- A candidate must be discoverable through the same `ghost/master/descript.txt` parsing contract as `InfoOnlyGhost` before rename.
- Once atomic rename succeeds, the primary outcome remains installed; cleanup/catalog problems may not invite republication.
- No user cancellation, background continuation, persisted URI grant, URL entry, incoming NAR `ACTION_VIEW`, queue, history, notification, replacement, or automatic retry is added.
- Path A is authoritative: no released/distributed state-capable APK exists, so unreleased developer-device queue/WorkManager state receives no migration bridge.
- This cutover removes Nanidroid's own four archive-workflow permission declarations and three receiver components. Because WorkManager remains compiled until the later dependency-deletion PR, the merged APK still legitimately contains dependency-contributed `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, and WorkManager/profile-installer receivers; none is a Nanidroid archive ingress.
- Keep Satori, SSU, YAYA, Kawari, `NarStagedSource`, `NarInstallPlanValidator`, `NarVerifiedInstallSession`, and `NarTransactionalInstaller` behavior and packaging intact.
- Physical ARM64 runtime testing remains deferred; both-ABI APK inventory and ARM64 ELF verification remain mandatory.
- Every task uses red-green TDD, ends in a focused commit, and receives coordinator review before the next task.

## File Structure

### New production files

- `src/main/kotlin/com/cattailsw/nanidroid/install/NarGhostDiscoverabilityValidator.kt` — validates the private candidate against production ghost-discovery requirements before publication.
- `src/main/kotlin/com/cattailsw/nanidroid/install/OwnedStagingRecovery.kt` — closed, no-follow, exact-name staging cleanup primitive shared by both staging domains.
- `src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportState.kt` — tokens, immutable states, primary outcomes, document selection, and recovery results.
- `src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportCoordinator.kt` — process-local CAS state machine and singleton ownership.
- `src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportBackend.kt` — application-context adapter from a selected URI to bounded copy, transactional install, and dual-root recovery.
- `src/main/kotlin/com/cattailsw/nanidroid/compose/ForegroundNarImportPresentation.kt` — progress and terminal Compose presentation derived directly from coordinator state.

### New tests

- `src/test/java/com/cattailsw/nanidroid/install/NarGhostDiscoverabilityValidatorTest.kt`
- `src/test/java/com/cattailsw/nanidroid/install/OwnedStagingRecoveryTest.kt`
- `src/test/java/com/cattailsw/nanidroid/install/ForegroundNarImportCoordinatorTest.kt`
- `src/test/java/com/cattailsw/nanidroid/install/ForegroundNarImportBackendTest.kt`
- `src/test/java/com/cattailsw/nanidroid/ForegroundNarPickerOwnershipTest.kt`
- `src/androidTest/java/com/cattailsw/nanidroid/install/NarTransactionalInstallerInstrumentationTest.kt`
- `src/androidTest/java/com/cattailsw/nanidroid/compose/ForegroundNarImportPresentationTest.kt`
- `tools/test_kotlin_foreground_nar_import_contract.py`

### Existing files modified in the cutover

- Installer: `NarTransactionalInstaller.kt` and `NarContentUriImport.kt`. `GhostMgr.kt` remains production-unchanged and is exercised through the new Activity call graph.
- Ownership/lifecycle: `CatTailApplication.kt`, `Nanidroid.kt`, `AndroidManifest.xml`.
- UI: `NanidroidComposeShell.kt`, `NanidroidSimpleDialogs.kt`, `src/main/res/values/strings.xml`, `src/main/res/values-ja/strings.xml`, and `src/main/res/values-zh-rTW/strings.xml`.
- Tests: `NarTransactionalInstallerTest.kt`, `NanidroidLifecycleInstrumentationTest.kt`, `NanidroidComposeShellTest.kt`, `NanidroidSimpleDialogsTest.kt`, `AdaptiveGhostStageFixtures.kt`, `AdaptiveGhostStageScreenshotRenderer.kt`, and `AdaptiveGhostStageScreenshotTest.kt`. The retained `NarContentUriImportTest.kt` is run unchanged.
- Static contracts/docs: `test_update_entrypoint_artifacts.py`, `docs/testing.md`.

### Files deleted in the cutover

- `src/main/kotlin/com/cattailsw/nanidroid/ArchiveIntentAdapter.kt`
- `src/main/kotlin/com/cattailsw/nanidroid/ArchiveIntentState.kt`
- `src/test/java/com/cattailsw/nanidroid/ArchiveIntentAdapterTest.kt`
- `src/test/java/com/cattailsw/nanidroid/ArchiveIntentStateTest.kt`
- `src/test/java/com/cattailsw/nanidroid/ArchiveIntentIngressGuardTest.kt`
- `tools/test_kotlin_incoming_nar_intent_contract.py`
- `tools/test_kotlin_nar_download_queue_contract.py`

---

### Task 1: Reject undiscoverable and logically conflicting targets before publication

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/install/NarGhostDiscoverabilityValidator.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/install/NarGhostDiscoverabilityValidatorTest.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/install/NarTransactionalInstallerInstrumentationTest.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/NarTransactionalInstaller.kt:141-278`
- Modify: `src/test/java/com/cattailsw/nanidroid/install/NarTransactionalInstallerTest.kt`

**Interfaces:**
- Consumes: `DescReader`, `ArchiveInstallResult`, `ArchiveInstallFailure`, and the existing synchronized `NarTransactionalInstaller.install` overloads.
- Produces: `NarGhostDiscoverabilityValidator.validate(candidateRoot: File): Boolean` and `NarTransactionalInstaller.hasLogicalTargetName(entries: Array<out File>, targetId: String): Boolean`.

- [ ] **Step 1: Write failing discoverability tests**

Add tests that create a candidate without `ghost/master/descript.txt`, a candidate with an empty descriptor, a fake-filesystem descriptor canonical escape, and valid Shift_JIS/UTF-8 descriptors. Add `@Rule @JvmField val androidStubs = HostAndroidStubRule()` because `DescReader` reaches Android logging/clock stubs. Use the production rule: only the exact in-candidate regular descriptor whose `DescReader.parse()` succeeds is accepted; an empty parsed map is valid when parsing itself succeeds, matching `InfoOnlyGhost`.

```kotlin
@Test fun missingMasterDescriptorIsNotDiscoverable() {
    val candidate = temporaryDirectory("missing-descriptor")
    File(candidate, "ghost/master").mkdirs()

    assertFalse(NarGhostDiscoverabilityValidator.validate(candidate))
}

@Test fun validMasterDescriptorIsDiscoverable() {
    val candidate = temporaryDirectory("valid-descriptor")
    val descriptor = File(candidate, "ghost/master/descript.txt")
    descriptor.parentFile!!.mkdirs()
    descriptor.writeText("charset,UTF-8\nname,Test Ghost\nsakura.name,Sakura\n")

    assertTrue(NarGhostDiscoverabilityValidator.validate(candidate))
}
```

- [ ] **Step 2: Write failing transaction tests**

Add tests proving that an otherwise valid NAR lacking discovery metadata returns `InvalidArchive` before rename, that pure name comparison treats `Foo` and `foo` as one logical ID, that a case-variant conflict preserves the first tree byte-for-byte, and that exceptions after a successful rename cannot escape or change the result from `Installed`.

```kotlin
@Test fun logicalTargetNamesUseGhostMgrCaseFolding() {
    val root = temporaryDirectory("logical-target")
    val first = File(root, "Foo").apply { mkdir() }

    assertTrue(NarTransactionalInstaller.hasLogicalTargetName(arrayOf(first), "foo"))
}

@Test fun missingDiscoveryDescriptorNeverPublishes() {
    val root = temporaryDirectory("undiscoverable")
    val archive = zip(
        "install.txt", descriptor("ghost-id"),
        "ghost/master/file.txt", bytes("payload"),
    )

    val result = NarTransactionalInstaller.install(archive, root, null, { false })

    assertTrue(result is ArchiveInstallResult.Failed)
    assertEquals(ArchiveInstallFailure.InvalidArchive, (result as ArchiveInstallResult.Failed).failure)
    assertFalse(File(root, "ghost-id").exists())
}
```

- [ ] **Step 3: Run the focused tests and confirm red**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.install.NarGhostDiscoverabilityValidatorTest" --tests "com.cattailsw.nanidroid.install.NarTransactionalInstallerTest"
```

Expected: FAIL because the validator/helper do not exist and the current installer publishes an archive without `descript.txt`.

- [ ] **Step 4: Implement candidate validation and logical identity checks**

Implement the validator with this exact injectable filesystem boundary, canonical containment, and the same parser used by discovery:

```kotlin
internal interface NarDiscoverabilityFileSystem {
    fun canonical(file: File): File
    fun isRegularFile(file: File): Boolean
    fun parseDescriptor(file: File)
}

internal object RealNarDiscoverabilityFileSystem : NarDiscoverabilityFileSystem {
    override fun canonical(file: File) = file.canonicalFile
    override fun isRegularFile(file: File) = file.isFile
    override fun parseDescriptor(file: File) {
        DescReader(file.path).parse()
    }
}

internal object NarGhostDiscoverabilityValidator {
    fun validate(
        candidateRoot: File,
        files: NarDiscoverabilityFileSystem = RealNarDiscoverabilityFileSystem,
    ): Boolean = try {
        val root = files.canonical(candidateRoot)
        val ghostPath = File(root, "ghost")
        val masterPath = File(ghostPath, "master")
        val descriptorPath = File(masterPath, "descript.txt")
        if (!files.isRegularFile(descriptorPath)) return false
        val ghost = files.canonical(ghostPath)
        val master = files.canonical(masterPath)
        val descriptor = files.canonical(descriptorPath)
        if (ghost != ghostPath || master != masterPath || descriptor != descriptorPath ||
            ghost.parentFile != root || master.parentFile != ghost || descriptor.parentFile != master
        ) {
            return false
        }
        files.parseDescriptor(descriptor)
        true
    } catch (_: Exception) {
        false
    }
}
```

The real filesystem implementation must use `Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isRegularFile` rather than `File.isFile`, so the descriptor itself cannot be a followed symlink. In `NarTransactionalInstaller`, enumerate `root.listFiles()` under `INSTALL_LOCK`, fail closed when enumeration returns `null`, reject `equals(ignoreCase = true)` both after plan creation and immediately before rename, and call the validator after extraction/session close but before publication. Map discovery failure to `Error.ARCHIVE_REJECTED` / `ArchiveInstallFailure.InvalidArchive`.

Harden the publication boundary in the same task. If `rename(candidate, target)` throws, treat publication as successful only when the previously absent target is now a directory and the candidate path no longer exists; otherwise return `PublishFailed`. Once rename is known successful, construct/store the `Installed` result before any progress or cleanup callback. Wrap post-rename progress and every `finally` cleanup probe/delete so exceptions are swallowed into owned residue for `recoverOwnedStaging`; no exception or `Failed` result may escape after known publication.

```kotlin
val publishedTargetId = plan.descriptor.getTargetId()
val renamed = try {
    fileOperations.rename(candidate, target)
} catch (_: Exception) {
    try {
        target.isDirectory && !candidate.exists()
    } catch (_: Exception) {
        false
    }
}
if (!renamed) return failure(
    Error.PUBLISH_FAILED,
    "The ghost files were prepared but could not be published. Please try again.",
)
val installed = success(target, publishedTargetId)
try {
    onProgress("Cleaning up", total[0])
} catch (_: Exception) {
    // Publication is authoritative; exact staging recovery owns residue.
}
return installed
```

Replace raw `exists`/`deleteTree` calls in `finally` with an `Exception`-bounded helper that never follows symlinks outside the already owned transaction and never throws. Do not catch `Error`.
`RealFileOperations.rename` itself converts `SecurityException` to `false`
before returning to this boundary; the thrown-after-move branch is retained for
the injected transaction seam and is accepted only by the verified
target-present/candidate-absent probe.

- [ ] **Step 5: Migrate valid transaction fixtures**

Create a test helper that always adds a parseable `ghost/master/descript.txt` to valid ghost archives, then route every success/publication fixture through it. Keep the new missing/malformed-descriptor tests on raw `zip(...)` so they prove the rejection.

```kotlin
private fun validGhostZip(targetId: String, vararg values: Any): File =
    zip(
        "install.txt", descriptor(targetId),
        "ghost/master/descript.txt",
        bytes("charset,UTF-8\nname,Test Ghost\nsakura.name,Sakura\n"),
        *values,
    )
```

- [ ] **Step 6: Add the Android case-sensitive filesystem proof**

In `NarTransactionalInstallerInstrumentationTest`, install `Foo`, record every byte under its target, then attempt `foo` on Android app storage. Assert `TargetExists`, no `foo` sibling, and an identical first-tree inventory. This connected test is authoritative for Android's case-sensitive filesystem; the JVM helper test proves the name rule on Windows. In the JVM transaction suite, add a `FileOperations.rename` fake that performs the move and then throws plus a progress callback that throws on `"Cleaning up"`; both calls must return `Installed`, proving the exception boundary.

- [ ] **Step 7: Run focused and retained installer suites**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.install.NarGhostDiscoverabilityValidatorTest" --tests "com.cattailsw.nanidroid.install.NarTransactionalInstallerTest" --tests "com.cattailsw.nanidroid.install.NarInstallPlanValidatorTest" --tests "com.cattailsw.nanidroid.install.NarZipCentralPreflightTest"
```

Expected: BUILD SUCCESSFUL with all focused and retained validation tests passing.

- [ ] **Step 8: Review and commit**

Inspect `git diff` for any post-rename `Failed` path and confirm none remains. Commit:

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/install/NarGhostDiscoverabilityValidator.kt src/main/kotlin/com/cattailsw/nanidroid/install/NarTransactionalInstaller.kt src/test/java/com/cattailsw/nanidroid/install/NarGhostDiscoverabilityValidatorTest.kt src/test/java/com/cattailsw/nanidroid/install/NarTransactionalInstallerTest.kt src/androidTest/java/com/cattailsw/nanidroid/install/NarTransactionalInstallerInstrumentationTest.kt
git commit -m "Harden fresh NAR publication"
```

### Task 2: Reconcile only exact app-owned staging in both domains

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/install/OwnedStagingRecovery.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/install/OwnedStagingRecoveryTest.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/NarTransactionalInstaller.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/install/NarTransactionalInstallerTest.kt`

**Interfaces:**
- Consumes: canonical `File` roots and the installer `INSTALL_LOCK`.
- Produces: `OwnedStagingRecovery.reconcile(...)`, `OwnedStagingRecoveryResult`, and `NarTransactionalInstaller.recoverOwnedStaging(installRoot: File?): OwnedStagingRecoveryResult`.

- [ ] **Step 1: Define failing cleanup ownership tests**

Use a fake filesystem boundary to prove matching regular files, matching candidate directories, no-follow symlink behavior, unmatched sibling preservation, canonical-parent rejection, enumeration failure, delete failure, and the `Clean` versus `Cleaned` distinction.

```kotlin
@Test fun importRecoveryDeletesOnlyMatchingRegularFiles() {
    val parent = temporaryDirectory("import-parent")
    val root = File(parent, "nar-import-v1").apply { mkdir() }
    val owned = File(root, "nar-import-0123456789abcdef01234567.zip").apply { writeText("partial") }
    val unmatched = File(root, "keep.txt").apply { writeText("keep") }

    val result = OwnedStagingRecovery.reconcile(
        root = root,
        expectedParent = parent,
        entryPattern = Regex("^nar-import-[0-9a-f]{24}\\.zip$"),
        entryKind = OwnedStagingEntryKind.REGULAR_FILE,
    )

    assertEquals(OwnedStagingRecoveryResult.Cleaned, result)
    assertFalse(owned.exists())
    assertTrue(unmatched.exists())
}
```

- [ ] **Step 2: Run the cleanup tests and confirm red**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.install.OwnedStagingRecoveryTest"
```

Expected: FAIL because the recovery types do not exist.

- [ ] **Step 3: Implement the closed cleanup primitive**

Use these exact public-to-module types:

```kotlin
internal enum class OwnedStagingEntryKind { REGULAR_FILE, DIRECTORY_TREE }

internal sealed interface OwnedStagingRecoveryResult {
    data object Clean : OwnedStagingRecoveryResult
    data object Cleaned : OwnedStagingRecoveryResult
    data class Failed(val message: String) : OwnedStagingRecoveryResult
}

internal interface OwnedStagingFileSystem {
    fun canonical(file: File): File
    fun existsNoFollow(file: File): Boolean
    fun isRegularFileNoFollow(file: File): Boolean
    fun isDirectoryNoFollow(file: File): Boolean
    fun isSymbolicLink(file: File): Boolean
    fun list(file: File): List<File>?
    fun delete(file: File): Boolean
}

internal object OwnedStagingRecovery {
    fun reconcile(
        root: File?,
        expectedParent: File?,
        entryPattern: Regex,
        entryKind: OwnedStagingEntryKind,
        files: OwnedStagingFileSystem = RealOwnedStagingFileSystem,
    ): OwnedStagingRecoveryResult
}
```

`RealOwnedStagingFileSystem` implements type checks with `Files.readAttributes(..., NOFOLLOW_LINKS)` and deletion with `Files.deleteIfExists`. A null root/expected parent or canonical-parent mismatch is `Failed`; an absent correctly located root is `Clean` and is not created. Reject a symlink root. Inspect only top-level matching entries. A matching top-level entry of the wrong no-follow kind, including a symlink, is `Failed` and is not deleted. For a verified directory tree, treat inner symlinks as leaf entries and delete the link itself without traversal. Enclose the reconciliation body in `try/catch (exception: Exception)` and return `Failed("Nanidroid could not reconcile its private import staging.")` on any unexpected filesystem exception; do not catch `Error`. Return `Failed` on verification, enumeration, or deletion failure for a verified owned entry. Leave an empty root and all unmatched entries alone so an ordinary prior successful import does not create an interruption notice.

- [ ] **Step 4: Add synchronized installer recovery**

Add:

```kotlin
@JvmStatic
internal fun recoverOwnedStaging(installRoot: File?): OwnedStagingRecoveryResult =
    synchronized(INSTALL_LOCK) {
        val root = try {
            installRoot?.canonicalFile
        } catch (_: Exception) {
            null
        }
            ?: return@synchronized OwnedStagingRecoveryResult.Failed(
                "Nanidroid cannot access its ghost storage.",
            )
        OwnedStagingRecovery.reconcile(
            root = File(root, STAGING_DIRECTORY),
            expectedParent = root,
            entryPattern = Regex("^candidate-[0-9a-f]{32}$"),
            entryKind = OwnedStagingEntryKind.DIRECTORY_TREE,
        )
    }
```

Keep it under the same `INSTALL_LOCK` as install/publication. Do not expose the lock or implement cleanup in the coordinator.

- [ ] **Step 5: Prove install/recovery serialization and target preservation**

In `NarTransactionalInstallerTest`, block an install inside its progress callback while `INSTALL_LOCK` is held, start `recoverOwnedStaging` on a second executor, assert recovery has not returned, release the callback latch, and assert recovery then completes. Also prove recovery removes an abandoned matching candidate, preserves an unmatched directory and published target, and reports a failed delete. Assert a cleanup retry never invokes `rename`.

- [ ] **Step 6: Run focused cleanup and installer tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.install.OwnedStagingRecoveryTest" --tests "com.cattailsw.nanidroid.install.NarTransactionalInstallerTest"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Review and commit**

Confirm every recursive call begins beneath an already verified matching candidate and no method accepts a computed broad delete root. Commit:

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/install/OwnedStagingRecovery.kt src/main/kotlin/com/cattailsw/nanidroid/install/NarTransactionalInstaller.kt src/test/java/com/cattailsw/nanidroid/install/OwnedStagingRecoveryTest.kt src/test/java/com/cattailsw/nanidroid/install/NarTransactionalInstallerTest.kt
git commit -m "Recover exact NAR staging"
```

### Task 3: Build the token-safe application-lived coordinator

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportState.kt`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportCoordinator.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/install/ForegroundNarImportCoordinatorTest.kt`

**Interfaces:**
- Consumes: `ArchiveInstallResult` and a fakeable `ForegroundNarImportBackend` contract.
- Produces: immutable coordinator state, `armPicker`, token-aware picker consumption/abandonment/launch failure, terminal acknowledgement, and cleanup-only retry.

- [ ] **Step 1: Write the state and backend contracts in the test**

Drive the implementation toward these exact types:

```kotlin
internal data class NarImportAttemptToken(
    val processNonce: String,
    val sequence: Long,
)

internal data class NarDocumentSelection(
    val uri: String,
    val scheme: String?,
)

internal sealed interface NarImportPrimaryOutcome {
    data object Interrupted : NarImportPrimaryOutcome
    data class Installed(val installedPath: String, val targetId: String) : NarImportPrimaryOutcome
    data class Failed(val message: String, val failure: ArchiveInstallFailure) : NarImportPrimaryOutcome
}

internal sealed interface ForegroundNarImportState {
    data object Recovering : ForegroundNarImportState
    data object Idle : ForegroundNarImportState
    data class AwaitingSelection(val token: NarImportAttemptToken) : ForegroundNarImportState
    data class Copying(val token: NarImportAttemptToken) : ForegroundNarImportState
    data class Installing(val token: NarImportAttemptToken, val phase: String, val completed: Long) : ForegroundNarImportState
    data class Installed(val token: NarImportAttemptToken, val installedPath: String, val targetId: String) : ForegroundNarImportState
    data class Failed(val token: NarImportAttemptToken, val message: String, val failure: ArchiveInstallFailure) : ForegroundNarImportState
    data class Interrupted(val token: NarImportAttemptToken) : ForegroundNarImportState
    data class RecoveryRequired(val token: NarImportAttemptToken, val primary: NarImportPrimaryOutcome, val message: String) : ForegroundNarImportState
    data class Cleaning(val token: NarImportAttemptToken, val primary: NarImportPrimaryOutcome) : ForegroundNarImportState
}

internal sealed interface NarImportRecoveryResult {
    data object Clean : NarImportRecoveryResult
    data object Cleaned : NarImportRecoveryResult
    data class Failed(val message: String) : NarImportRecoveryResult
}
```

- [ ] **Step 2: Write failing transition tests**

Cover startup `Recovering`, clean/cleaned/failed/thrown recovery, one arm, duplicate arm, picker cancellation, launch failure, return-time guard rejection, valid URI, Copying/Installing/Installed, every typed failure, a thrown import backend, unexpected in-flight cancellation to `Interrupted`, stale token A against current B, observer replay, acknowledgement, recovery retry, thrown/failed recovery retry, and cleanup retry never calling import.

Use an injected queued dispatcher so each IO action advances only when the test calls `runNext()`; do not use sleeps.

```kotlin
@Test fun stalePickerACannotConsumeAwaitingPickerB() {
    val coordinator = fixture(processNonce = "process-current")
    dispatcher.runNext() // startup recovery -> Idle
    val tokenA = requireNotNull(coordinator.armPicker())
    assertTrue(coordinator.abandonPicker(tokenA))
    val tokenB = requireNotNull(coordinator.armPicker())

    assertFalse(
        coordinator.consumePickerResult(
            expectedToken = tokenA,
            selection = NarDocumentSelection("content://provider/a.nar", "content"),
            importAllowed = true,
        ),
    )
    assertEquals(ForegroundNarImportState.AwaitingSelection(tokenB), coordinator.state.value)
    assertEquals(0, backend.importCalls)
}
```

- [ ] **Step 3: Run coordinator tests and confirm red**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.install.ForegroundNarImportCoordinatorTest"
```

Expected: FAIL because coordinator production types do not exist.

- [ ] **Step 4: Implement one CAS state machine**

Define the backend contract:

```kotlin
internal interface ForegroundNarImportBackend {
    fun recoverOwnedStaging(): NarImportRecoveryResult
    fun importDocument(
        selection: NarDocumentSelection,
        isCancelled: () -> Boolean,
        onInstallingProgress: (phase: String, completed: Long) -> Unit,
    ): ArchiveInstallResult
}
```

Construct the coordinator with `backend`, `CoroutineDispatcher`, and `processNonce`; create `CoroutineScope(SupervisorJob() + dispatcher)`, `MutableStateFlow(Recovering)`, and `AtomicLong(0)`. Invoke backend operations inside an `Exception` boundary (do not catch `Error`). Startup recovery maps `Clean → Idle`, `Cleaned → Interrupted(newToken)`, and typed or thrown recovery failure → `RecoveryRequired(newToken, Interrupted, "Nanidroid could not reconcile its private import staging.")` so `Recovering` can never be stranded.

`armPicker()` must CAS only `Idle → AwaitingSelection(newToken)`. `consumePickerResult(expectedToken, selection, importAllowed)` must CAS only the exact `AwaitingSelection(expectedToken)`; null/forbidden goes to `Idle`, and an allowed selection goes to `Copying` before launching IO. `failPickerLaunch(expectedToken, message)` must CAS only the exact `AwaitingSelection(expectedToken)` to `Failed(expectedToken, message, SourceUnavailable)`, making launch failure replayable without an Activity-owned dialog. Never open the URI before the successful CAS.

The backend contract guarantees an exception cannot escape after known publication; Task 1 proves this at the transactional boundary and Task 4 closes the outer content-stage `finally` boundary. Therefore a thrown `importDocument` maps to primary `Failed("Nanidroid could not complete the selected document import.", StagingFailed)`, while an `Exception`-wrapped `CancellationException` maps to primary `Interrupted`. After every returned/thrown import primary, call `recoverOwnedStaging` inside its own exception boundary. Map both `Clean` and `Cleaned` to the terminal state for the primary result; map typed or thrown cleanup failure to `RecoveryRequired(primary, "Nanidroid could not reconcile its private import staging.")`. Convert a returned unexpected in-flight `ArchiveInstallResult.Cancelled` to primary `Interrupted`. `retryCleanup` CASes `RecoveryRequired → Cleaning`, calls only exception-bounded `recoverOwnedStaging`, then returns to the recorded terminal on `Clean`/`Cleaned` or the same `RecoveryRequired` with that exact message on typed/thrown failure. `acknowledge` accepts only `Installed`, `Failed`, and `Interrupted` with the matching token.

- [ ] **Step 5: Prove cross-process nonce rejection**

Add a test restoring `NarImportAttemptToken("dead-process", 1)` while the new coordinator creates `NarImportAttemptToken("new-process", 1)`. Assert the old result cannot consume the new state even though sequences match.

- [ ] **Step 6: Run the complete coordinator test class**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.install.ForegroundNarImportCoordinatorTest"
```

Expected: BUILD SUCCESSFUL with no sleep-based synchronization.

- [ ] **Step 7: Review and commit**

Review every state mutation and verify it is either the initial startup transition or a `compareAndSet` against exact state/token. Commit:

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportState.kt src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportCoordinator.kt src/test/java/com/cattailsw/nanidroid/install/ForegroundNarImportCoordinatorTest.kt
git commit -m "Add foreground NAR import coordinator"
```

### Task 4: Compose the Android bounded-copy/install/recovery backend

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportBackend.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/install/ForegroundNarImportBackendTest.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportCoordinator.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/install/NarContentUriImport.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/install/NarContentUriImportTest.kt`

**Interfaces:**
- Consumes: coordinator backend interface, `NarContentUriImport.importContent`, `NarTransactionalInstaller.install`, both recovery functions, and application context.
- Produces: `AndroidForegroundNarImportBackend` and `ForegroundNarImportCoordinator.get(context)`.

- [ ] **Step 1: Write backend composition tests**

Use temporary import/install roots and injected `openContent`/`install` functions. Prove content scheme rejection, bounded copy, exact bytes delivered to the installer, first installing callback before installer progress, exact result propagation, dual-root recovery combination, cleanup failure preservation, null external ghost storage for both import and startup recovery, import-root cleanup still occurring while ghost storage is null, and a later cleanup retry that succeeds after the injected ghost-root supplier becomes available. In `NarContentUriImportTest`, inject source-stage deletion that throws `SecurityException` after the install lambda returns `Installed`; assert `importContent` still returns that exact `Installed` primary and leaves the matching stage file for backend recovery.

```kotlin
@Test fun selectedContentCopiesPrivatelyThenCallsInstallerOnce() {
    val backend = backend(
        bytes = "nar bytes".toByteArray(),
        installResult = ArchiveInstallResult.Installed("/ghost/test", "test"),
    )

    val result = backend.importDocument(
        NarDocumentSelection("content://provider/test.nar", "content"),
        isCancelled = { false },
        onInstallingProgress = { phase, _ -> phases += phase },
    )

    assertEquals(ArchiveInstallResult.Installed("/ghost/test", "test"), result)
    assertEquals(1, installerCalls)
    assertArrayEquals("nar bytes".toByteArray(), installedArchiveBytes)
    assertTrue(importRoot.listFiles().orEmpty().none { it.name.startsWith("nar-import-") })
}
```

- [ ] **Step 2: Run backend tests and confirm red**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.install.ForegroundNarImportBackendTest"
```

Expected: FAIL because the production backend does not exist.

- [ ] **Step 3: Implement the testable backend**

Give `AndroidForegroundNarImportBackend` an internal constructor accepting exact import root `File`, `ghostRoot: () -> File?`, `openContent: (String) -> InputStream?`, and installer/recovery functions. The production factory uses only `context.applicationContext`, `contentResolver.openInputStream(Uri.parse(uri))`, `File(noBackupFilesDir, "nar-import-v1")`, and a supplier that reevaluates `getExternalFilesDir(null)?.let { File(it, "ghost") }` for each import or recovery call. Never call a `File` constructor with the nullable external-files result. A null ghost root makes `importDocument` return `ArchiveInstallResult.Failed("Nanidroid cannot access its ghost storage.", StorageUnavailable)` before opening the URI and makes `recoverOwnedStaging` return `NarImportRecoveryResult.Failed` with the same message; cleanup retry can succeed if storage later becomes available.

At the start of each `importDocument` call, evaluate `val root = ghostRoot() ?: return ArchiveInstallResult.Failed("Nanidroid cannot access its ghost storage.", ArchiveInstallFailure.StorageUnavailable)` before calling the URI opener. Then call `NarContentUriImport.importContent` with `selection.scheme`. Inside its install lambda, emit `"Preparing installer"` before calling `NarTransactionalInstaller.install(staged, root, null, isCancelled, onInstallingProgress)`. The direct transactional installer always supplies `targetId`; normalize the nullable legacy result type with `result.targetId ?: File(result.installedPath).name` so a published success can never become a false failure. Do not create `GhostMgr` or perform a post-publication discoverability failure check.

Add `deleteStaged: (File) -> Unit = { it.delete() }` to the typed `NarContentUriImport.importContent` overload that returns `ArchiveInstallResult`. In `finally`, call it inside `try/catch (_: Exception)` and do not catch `Error`. A false delete or thrown delete leaves the exact matching source stage for the backend's immediate `recoverOwnedStaging`; it never changes the returned primary result.

Add this total boundary mapping in the backend file:

```kotlin
private fun OwnedStagingRecoveryResult.toNarImportRecoveryResult(): NarImportRecoveryResult =
    when (this) {
        OwnedStagingRecoveryResult.Clean -> NarImportRecoveryResult.Clean
        OwnedStagingRecoveryResult.Cleaned -> NarImportRecoveryResult.Cleaned
        is OwnedStagingRecoveryResult.Failed -> NarImportRecoveryResult.Failed(message)
    }
```

At the start of each `recoverOwnedStaging` call, evaluate `val root = ghostRoot()`. Always call the import-root cleaner with `^nar-import-[0-9a-f]{24}\\.zip$` and map its result. Independently compute the installer result as `root?.let(NarTransactionalInstaller::recoverOwnedStaging)?.toNarImportRecoveryResult() ?: NarImportRecoveryResult.Failed("Nanidroid cannot access its ghost storage.")`. Combine the two mapped `NarImportRecoveryResult` values as: any `Failed` wins, otherwise any `Cleaned` yields `Cleaned`, otherwise `Clean`. The null-root/import-residue test must assert that the import artifact is deleted but the mapped ghost-storage failure wins. Thus one unavailable staging domain never prevents safe reconciliation of the other.

- [ ] **Step 4: Add process singleton ownership**

Implement `ForegroundNarImportCoordinator.get(context)` with double-checked locking, a random `UUID` process nonce, `Dispatchers.IO`, and one backend instance. Add package-internal `replaceForTesting`/`resetForTesting` seams guarded by `check(instance == null || instance.state.value == ForegroundNarImportState.Idle)`. Tests may replace or reset only an absent/idle singleton; awaiting, running, cleaning, and terminal attempts cannot be discarded.

In `CatTailApplication.onCreate`, replace `SharedDurableOperationSupervisor.get(this)` with:

```kotlin
override fun onCreate() {
    super.onCreate()
    ForegroundNarImportCoordinator.get(this)
}
```

Keep `@HiltAndroidApp`, `Configuration.Provider`, and `HiltWorkerFactory` until the later dependency-deletion PR.

- [ ] **Step 5: Run focused and application compilation tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.install.ForegroundNarImportBackendTest" --tests "com.cattailsw.nanidroid.install.NarContentUriImportTest" compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Review and commit**

Confirm `ForegroundNarImportBackend.kt` is the only Android-context adapter and no coordinator field has an Activity type. Commit:

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportBackend.kt src/main/kotlin/com/cattailsw/nanidroid/install/ForegroundNarImportCoordinator.kt src/main/kotlin/com/cattailsw/nanidroid/install/NarContentUriImport.kt src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt src/test/java/com/cattailsw/nanidroid/install/ForegroundNarImportBackendTest.kt src/test/java/com/cattailsw/nanidroid/install/NarContentUriImportTest.kt
git commit -m "Compose foreground NAR import backend"
```

### Task 5: Add foreground import presentation independent of Bundle dialogs

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/compose/ForegroundNarImportPresentation.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/compose/ForegroundNarImportPresentationTest.kt`
- Modify: `src/main/res/values/strings.xml`
- Modify: `src/main/res/values-ja/strings.xml`
- Modify: `src/main/res/values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: `ForegroundNarImportState` and token-aware callbacks.
- Produces: `ForegroundNarImportPresentation(state, installedReadyToken, onAcknowledge, onSelectAnother, onRetryCleanup)`.

- [ ] **Step 1: Write failing Compose presentation tests**

Test Copying, Installing, Cleaning, Installed before/after readiness, Failed, Interrupted, RecoveryRequired with installed primary, and stale callback token capture. Assert progress consumes pointer input, terminal text is replayable, Installed never offers auto-switch, and RecoveryRequired exposes only cleanup retry.

```kotlin
@Test fun failedImportOffersMatchingTokenDismissAndReselect() {
    val token = NarImportAttemptToken("process", 7)
    var action: Pair<String, NarImportAttemptToken>? = null
    rule.setContent {
        ForegroundNarImportPresentation(
            state = ForegroundNarImportState.Failed(
                token,
                "This ghost archive is invalid.",
                ArchiveInstallFailure.InvalidArchive,
            ),
            installedReadyToken = null,
            onAcknowledge = { action = "dismiss" to it },
            onSelectAnother = { action = "select" to it },
            onRetryCleanup = { action = "cleanup" to it },
        )
    }

    rule.onNodeWithTag("nar-import-select-another").performClick()
    assertEquals("select" to token, action)
}
```

- [ ] **Step 2: Run presentation tests and confirm red**

Run on the connected API 31–37 test device/emulator:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.ForegroundNarImportPresentationTest
```

Expected: FAIL because the composable does not exist.

- [ ] **Step 3: Implement exact presentation behavior**

Use a blocking `Surface` overlay for `Copying`, `Installing`, and `Cleaning`. Use `AlertDialog` for terminal states. For Installed with a mismatched/null `installedReadyToken`, keep a noninteractive “Refreshing installed ghosts…” progress surface; only render success after readiness matches the state token.

Add these exact English resources:

```xml
<string name="nar_import_from_document">Install from document</string>
<string name="nar_import_copying">Copying selected archive…</string>
<string name="nar_import_installing">Installing selected ghost…</string>
<string name="nar_import_cleaning">Cleaning private staging…</string>
<string name="nar_import_refreshing">Refreshing installed ghosts…</string>
<string name="nar_import_installed_title">Ghost installed</string>
<string name="nar_import_installed_message">The ghost was installed and is now available in the ghost list.</string>
<string name="nar_import_failed_title">Couldn’t install ghost</string>
<string name="nar_import_interrupted_title">Import interrupted</string>
<string name="nar_import_interrupted_message">Previous import was interrupted. Installed ghosts are preserved; if the new ghost isn’t listed, select the archive again.</string>
<string name="nar_import_recovery_title">Import cleanup needs attention</string>
<string name="nar_import_retry_cleanup">Retry cleanup</string>
<string name="nar_import_select_another">Select another</string>
```

Add the same keys to `values-ja/strings.xml` with these exact values, in the same key order:

```text
ドキュメントからインストール
選択したアーカイブをコピーしています…
選択したゴーストをインストールしています…
プライベートステージングをクリーンアップしています…
インストール済みゴーストの一覧を更新しています…
ゴーストをインストールしました
ゴーストをインストールしました。ゴースト一覧から選択できます。
ゴーストをインストールできませんでした
インポートが中断されました
以前のインポートは中断されました。インストール済みのゴーストは保持されています。新しいゴーストが一覧にない場合は、アーカイブをもう一度選択してください。
インポートのクリーンアップに対応が必要です
クリーンアップを再試行
別のファイルを選択
```

Add the same keys to `values-zh-rTW/strings.xml` with these exact values, in the same key order:

```text
從文件安裝
正在複製選取的封存檔…
正在安裝選取的偽人格…
正在清理私人暫存區…
正在更新已安裝的偽人格列表…
已安裝偽人格
偽人格已安裝，現在可從偽人格列表中選取。
無法安裝偽人格
匯入已中斷
上一次匯入已中斷。已安裝的偽人格會保留；如果列表中沒有新的偽人格，請重新選擇封存檔。
匯入清理需要處理
重試清理
選擇另一個文件
```

Render the runtime failure message from state, not as formatted markup. RecoveryRequired with an installed primary must state that the installed ghost is preserved and only cleanup will be retried.

- [ ] **Step 4: Run presentation tests green**

Run:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.ForegroundNarImportPresentationTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Review and commit**

Confirm no import terminal state is converted to `NanidroidSimpleDialog` and every callback carries the rendered token. Commit:

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/compose/ForegroundNarImportPresentation.kt src/androidTest/java/com/cattailsw/nanidroid/compose/ForegroundNarImportPresentationTest.kt src/main/res/values/strings.xml src/main/res/values-ja/strings.xml src/main/res/values-zh-rTW/strings.xml
git commit -m "Present foreground NAR import state"
```

### Task 6: Wire the picker, registry ownership, and GhostMgr readiness into the Activity

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt:216-1010`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidComposeShell.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/ForegroundNarPickerOwnershipTest.kt`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt`

**Interfaces:**
- Consumes: coordinator singleton/state, `ActivityResultContracts.OpenDocument`, foreground presentation, and Activity-local `GhostMgr`.
- Produces: one picker journey with exact registry-owner token restoration and a readiness-gated success refresh.

- [ ] **Step 1: Add failing lifecycle helper tests**

Define package-internal top-level helpers in `Nanidroid.kt` for Bundle token encode/decode and new-task ownership reconciliation. `reconcileNarPickerOwner(restored, state, abandon)` returns `restored` only when `state == AwaitingSelection(restored)`; for any other state it returns null, and for `AwaitingSelection(current)` with a null/different restored token it first calls `abandon(current)`. Test same-process restoration, dead-process nonce mismatch, no-owner abandonment, malformed Bundle values, stale A/current B rejection, and launch exception rollback in the new JVM test file. Keep the existing `TransientUiSnapshot` schema and both of its test classes unchanged; picker owner keys are Activity registry-routing state, not part of that snapshot.

```kotlin
@Test fun newTaskWithoutRegistryOwnerAbandonsAwaitingSelection() {
    val token = NarImportAttemptToken("live-process", 4)
    val coordinator = coordinatorAt(ForegroundNarImportState.AwaitingSelection(token))

    val owner = reconcileNarPickerOwner(
        restored = null,
        state = coordinator.state.value,
        abandon = coordinator::abandonPicker,
    )

    assertNull(owner)
    assertEquals(ForegroundNarImportState.Idle, coordinator.state.value)
}
```

- [ ] **Step 2: Run lifecycle tests and confirm red**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.ForegroundNarPickerOwnershipTest"
```

Expected: FAIL until the helper test/file and Activity helpers exist.

- [ ] **Step 3: Replace the custom picker bookkeeping**

Register exactly:

```kotlin
private val narDocumentPicker = registerForActivityResult(
    ActivityResultContracts.OpenDocument(),
) { uri ->
    val expectedToken = narPickerOwnerToken
    narPickerOwnerToken = null
    if (expectedToken == null) return@registerForActivityResult
    val accepted = foregroundNarImport.consumePickerResult(
        expectedToken = expectedToken,
        selection = uri?.let { NarDocumentSelection(it.toString(), it.scheme) },
        importAllowed = allows(GuardedAction.IMPORT_INSTALL, ActionOrigin.USER),
    )
    if (!accepted) return@registerForActivityResult
}
```

A stale or new-process registry result whose exact saved owner token is rejected is a silent no-op: it cannot open the URI, mutate coordinator state, or create an Activity-owned import dialog. The user remains free to launch a fresh picker from `Idle`. A live coordinator `Interrupted(token)` is reserved for verified startup residue that was cleaned and renders through `ForegroundNarImportPresentation` with token-aware acknowledgement.

Before launch, call `armPicker`, save its token in `narPickerOwnerToken`, and launch `arrayOf("*/*")`. Catch runtime launch failure, call `failPickerLaunch` for that exact token, and clear ownership; the resulting coordinator `Failed` state is the replayable presentation. Save nonce and sequence Bundle keys only while an owner exists. On Activity creation, pass the decoded token and current coordinator state through `reconcileNarPickerOwner`: a dead-process/malformed/non-awaiting token is cleared, and an orphaned or mismatched live `AwaitingSelection` is atomically abandoned before enabling selection.

Keep the launch-time guard and the callback-time guard. Remove `awaitingNarDocument`, replacement IDs, persisted-grant flags, and the custom `NarDocumentPickerContract/Result` types.

- [ ] **Step 4: Add the GhostMgr readiness barrier**

Create `CompletableDeferred<GhostMgr>` beside `gm`. Complete it exactly once when `createSvcs2ndThread` constructs the manager. In a `LaunchedEffect(importState)` keyed by the full state/token, identify `Installed` or installed-primary `RecoveryRequired`, return immediately when `installedReadyToken == token`, await readiness, recheck both `installedReadyToken != token` and `foregroundNarImport.state.value` still carries the same installed primary token, call `refreshGhost()`, and set `installedReadyToken` only after refresh. Preserve `installedReadyToken` through `RecoveryRequired → Cleaning → Installed`; cleanup retry must not refresh the same publication twice. Never switch `currentGhost`.

- [ ] **Step 5: Host foreground presentation in the shell**

Add shell parameters for `narImportState`, `installedReadyToken`, and the three token callbacks. Activity callback behavior:

- Acknowledge: `coordinator.acknowledge(token)`.
- Select another: acknowledge the exact Failed token, then call the picker launch only if acknowledgement succeeded.
- Retry cleanup: `coordinator.retryCleanup(token)`.

Import presentation must be above ordinary `simpleDialog` and must not be written by `saveSimpleDialog`/`restoreSimpleDialog`.

- [ ] **Step 6: Add same-process recreation and stale-result tests**

Use the coordinator test replacement seam and `ActivityScenario.recreate()` to prove the owner token survives, no second launch occurs, and Installed completion during replacement `GhostMgr` construction refreshes once after readiness. Add a fresh-coordinator/dead-process token case that dispatches the saved callback data into the pure callback adapter and asserts the URI opener is never called and no Activity dialog is created. Drive an installed-primary `RecoveryRequired → Cleaning → Installed` cleanup retry and assert the replacement manager refresh count remains one.

- [ ] **Step 7: Run Activity/coordinator tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.ForegroundNarPickerOwnershipTest" --tests "com.cattailsw.nanidroid.install.ForegroundNarImportCoordinatorTest"
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.NanidroidLifecycleInstrumentationTest
```

Expected: both commands succeed; the connected report shows no duplicate import or readiness loss.

- [ ] **Step 8: Review and commit**

Search the Activity for `takePersistableUriPermission`, `NarLiveGrant`, replacement IDs, and custom picker contracts; none may remain in the new path. Commit the integration without deleting the old queue/UI roots yet:

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidComposeShell.kt src/test/java/com/cattailsw/nanidroid/ForegroundNarPickerOwnershipTest.kt src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt
git commit -m "Route document imports through foreground coordinator"
```

### Task 7: Remove legacy Activity, archive intent, queue, durable prompt, and URL UI roots

**Files:**
- Delete: `src/main/kotlin/com/cattailsw/nanidroid/ArchiveIntentAdapter.kt`
- Delete: `src/main/kotlin/com/cattailsw/nanidroid/ArchiveIntentState.kt`
- Delete: `src/test/java/com/cattailsw/nanidroid/ArchiveIntentAdapterTest.kt`
- Delete: `src/test/java/com/cattailsw/nanidroid/ArchiveIntentStateTest.kt`
- Delete: `src/test/java/com/cattailsw/nanidroid/ArchiveIntentIngressGuardTest.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidComposeShell.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidSimpleDialogs.kt`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/compose/NanidroidComposeShellTest.kt`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/compose/NanidroidSimpleDialogsTest.kt`
- Modify: `src/main/res/values/strings.xml`
- Modify: `src/main/res/values-ja/strings.xml`
- Modify: `src/main/res/values-zh-rTW/strings.xml`
- Modify: `src/screenshotTest/kotlin/com/cattailsw/nanidroid/compose/AdaptiveGhostStageFixtures.kt`
- Modify: `src/screenshotTest/kotlin/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotRenderer.kt`
- Modify: `src/screenshotTest/kotlin/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTest.kt`
- Delete: `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/StalledNormalPreview_stalled_normal_d7978cda_0.png`
- Delete: `src/screenshotTestDebug/reference/com/cattailsw/nanidroid/compose/AdaptiveGhostStageScreenshotTestKt/StalledPassivePreview_stalled_passive_b4fcfca2_0.png`
- Create: the two `updateDebugScreenshotTest` outputs under the same reference directory whose filenames begin `ImportInstallingPreview_import_installing_` and `ImportFailedPreview_import_failed_`
- Modify: `docs/testing.md`

**Interfaces:**
- Consumes: the fully wired foreground import path from Task 6.
- Produces: production UI with one **Install from document** action and no legacy backend observer/callback.

- [ ] **Step 1: Change tests to require the reduced UI**

Delete shell/dialog tests whose sole contract is archive queue, URL entry, durable attention prompt, or durable recovery prompt. Tag the retained More Ghost action `install-from-document`. Add assertions that this tag exists exactly once, the overflow contains Readme but no archive queue, and the shell has no queue badge. Keep unrelated stage, modal, accessibility, and readme tests intact.

```kotlin
@Test fun moreGhostOffersOnlyInstallFromDocument() {
    rule.setContent {
        NanidroidSimpleDialogHost(
            NanidroidSimpleDialog.MoreGhost(onInstallFromDocument = {}),
            onDismiss = {},
        )
    }

    rule.onNodeWithTag("install-from-document").assertExists()
    rule.onNodeWithText(rule.activity.getString(R.string.nar_import_from_document)).assertExists()
    rule.onNodeWithText("Enter URL").assertDoesNotExist()
}
```

- [ ] **Step 2: Run focused UI tests and confirm red**

Run:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.NanidroidSimpleDialogsTest,com.cattailsw.nanidroid.compose.NanidroidComposeShellTest
```

Expected: FAIL while the old URL/queue/durable UI remains.

- [ ] **Step 3: Remove all Activity legacy roots**

Delete from `Nanidroid`: `NarDownloadRepository`, live-grant executor/handoff, download collection, durable supervisor collection/actions/recovery, notification permission launcher/request, URL validation/enqueue, incoming intent state/handlers, `onNewIntent` archive dispatch, replacement flow, and their Bundle keys. Preserve outgoing dialogue/readme browser intents and the trusted packaged first-ghost bootstrap.

Delete `ArchiveIntentAdapter.kt`, `ArchiveIntentState.kt`, and their three JVM tests.

- [ ] **Step 4: Reduce Compose shell and simple dialogs**

Remove `onArchiveQueue`, `archiveDownloads`, queue status/badge/label helpers, `stalledOperations`, durable prompt parameters/hosts, and `archiveDownloads` from `NanidroidSimpleDialogHost`. Keep durable component source files compiled but unreachable for the next deletion PR.

Change the dialog model to:

```kotlin
data class MoreGhost(
    val onInstallFromDocument: () -> Unit,
) : NanidroidSimpleDialog
```

Delete `UrlEntry`, `ArchiveQueue`, their composables, previews, callbacks, and restoration constants. Render one `nar_import_from_document` action.

- [ ] **Step 5: Remove obsolete strings in every locale**

Remove URL, HTTPS, queue count/status/plural, SD-card picker, and manual download strings only after `rg` proves no production/test reference. Keep `dl_complete`, durable notification/action/phase strings, and durable recovery strings while their compiled backend/component files remain.

- [ ] **Step 6: Replace two obsolete screenshot fixtures**

Replace `stalled_normal`/`StalledNormalPreview` and `stalled_passive`/`StalledPassivePreview` with `import_installing`/`ImportInstallingPreview` and `import_failed`/`ImportFailedPreview`; replace `StageFixtureState.stalledOperation` with `narImportState`. Keep the suite at exactly 31 cases: nine grid, sixteen product, six pairwise. Update the renderer to pass foreground state and remove durable/queue parameters. Update `docs/testing.md` to say foreground-import previews instead of durable-prompt previews.

Regenerate:

```powershell
.\gradlew.bat updateDebugScreenshotTest
```

Require the generator diff to delete exactly the two named stalled references and add exactly the two named import references, with no other PNG changes. Open both new reference PNGs at original resolution and reject unexpected stage, toolbar, typography, RTL, or modal changes before staging them.

- [ ] **Step 7: Run focused UI and screenshot tests green**

Run:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.NanidroidSimpleDialogsTest,com.cattailsw.nanidroid.compose.NanidroidComposeShellTest
.\gradlew.bat validateDebugScreenshotTest
```

Expected: both commands succeed and exactly 31 screenshot cases validate.

- [ ] **Step 8: Review and commit**

Run `rg -n "NarDownloadRepository|NarLiveGrant|ArchiveQueue|UrlEntry|SharedDurableOperationSupervisor|POST_NOTIFICATIONS" src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/main/kotlin/com/cattailsw/nanidroid/compose`; expect no production-shell/Activity hit. Commit:

```powershell
git add src/main src/test src/androidTest src/screenshotTest docs/testing.md
git commit -m "Remove legacy archive UI roots"
```

### Task 8: Remove manifest entry points and replace static contracts

**Files:**
- Modify: `src/main/AndroidManifest.xml`
- Delete: `tools/test_kotlin_incoming_nar_intent_contract.py`
- Delete: `tools/test_kotlin_nar_download_queue_contract.py`
- Create: `tools/test_kotlin_foreground_nar_import_contract.py`
- Modify: `tools/test_update_entrypoint_artifacts.py`

**Interfaces:**
- Consumes: the single foreground coordinator call graph and Path A compatibility decision.
- Produces: a merged manifest and source contract with no external/archive/durable legacy root.

- [ ] **Step 1: Write the failing static cutover contract**

The new Python contract must assert:

```python
self.assertIn("ActivityResultContracts.OpenDocument", activity)
self.assertIn('arrayOf("*/*")', activity)
self.assertIn("ForegroundNarImportCoordinator.get", activity)
self.assertNotIn("NarDownloadRepository", activity)
self.assertNotIn("NarLiveGrantHandoff", activity)
self.assertNotIn("handleIncomingIntent", activity)
self.assertNotIn("enqueuePendingArchiveIntent", activity)
self.assertNotIn("override fun onNewIntent", activity)
self.assertNotIn("ArchiveIntent", activity)
self.assertNotIn("takePersistableUriPermission", activity)
self.assertIn("NarContentUriImport.importContent", backend)
self.assertIn("NarTransactionalInstaller.install", backend)
self.assertIn('assets.open("nanidroid.zip")', activity)
```

Do not ban `Intent.ACTION_VIEW` globally in the Activity: the retained dialogue/readme browser launchers still use it for outgoing links. The manifest assertions, deleted incoming handlers, and closed archive-ingress allowlist distinguish those outgoing intents from archive ingress.

Parse the source manifest with `ElementTree` and assert zero VIEW filters, zero receiver entries, and absence of Nanidroid declarations for `ACCESS_NETWORK_STATE`, `INTERNET`, `POST_NOTIFICATIONS`, and `RECEIVE_BOOT_COMPLETED`. Separately allowlist the launcher activity and trusted bundled asset install.

- [ ] **Step 2: Run static contracts and confirm red**

Run:

```powershell
python -m unittest tools.test_kotlin_foreground_nar_import_contract tools.test_update_entrypoint_artifacts
```

Expected: FAIL while source/merged manifest still contains legacy roots or before generated artifacts are refreshed.

- [ ] **Step 3: Remove manifest roots**

Delete the NAR/ZIP VIEW intent filter, `NarDownloadReceiver`, `NarDownloadRecoveryReceiver`, `DurableOperationAttentionReceiver`, and the four obsolete permissions. Retain the launcher filter, `singleTop`, exported activity, WorkManager initializer suppression, and foreground-service permission removal tombstone until dependency deletion.

- [ ] **Step 4: Replace and update contract tests**

Delete the two positive legacy source-contract scripts. Update `test_update_entrypoint_artifacts.py` to assert zero NAR VIEW filters and absence of the three Nanidroid receiver class names in the packaged manifest. Assert packaged `INTERNET` and `POST_NOTIFICATIONS` are absent; assert `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, and `WAKE_LOCK` plus WorkManager/profile-installer receiver classes remain dependency-owned until the later dependency-deletion PR. Keep the SDK floor/target, launcher identity, removed legacy service, WorkManager-generated service, and foreground-permission-removal assertions.

- [ ] **Step 5: Regenerate artifacts and run contracts green**

Run:

```powershell
.\gradlew.bat assembleDebug lint
python -m unittest tools.test_kotlin_foreground_nar_import_contract tools.test_update_entrypoint_artifacts
python -m unittest tools.test_verify_phase1_shipped_state_audit
python tools/verify_phase1_shipped_state_audit.py
```

Expected: foreground/static contracts and the historical Path A verifier pass. Compare lint with the exact baseline from `fc394aac`; introduce no new error or warning.

- [ ] **Step 6: Prove manifest non-resolution on device**

Install the debug APK and run package-manager resolution for cold/warm `ACTION_VIEW content://…nar` intents. Assert Nanidroid is not returned. Launch `MAIN` and assert the Activity still starts.

- [ ] **Step 7: Review and commit**

Inspect the packaged manifest rather than only source XML. Commit:

```powershell
git add src/main/AndroidManifest.xml tools/test_kotlin_foreground_nar_import_contract.py tools/test_update_entrypoint_artifacts.py
git rm tools/test_kotlin_incoming_nar_intent_contract.py tools/test_kotlin_nar_download_queue_contract.py
git commit -m "Retire legacy archive entry points"
```

### Task 9: Prove the complete cutover locally and on device

**Files:**
- Modify only test/docs files needed to repair evidence gaps found by the commands below; any production change returns to the responsible earlier task and repeats its review.

**Interfaces:**
- Consumes: the complete focused branch.
- Produces: reproducible local, connected, screenshot, corpus, ABI, hygiene, and review evidence suitable for the PR body.

- [ ] **Step 1: Run focused red-green suites once more**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.install.ForegroundNarImportCoordinatorTest" --tests "com.cattailsw.nanidroid.install.ForegroundNarImportBackendTest" --tests "com.cattailsw.nanidroid.install.OwnedStagingRecoveryTest" --tests "com.cattailsw.nanidroid.install.NarGhostDiscoverabilityValidatorTest" --tests "com.cattailsw.nanidroid.install.NarTransactionalInstallerTest"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the repository-wide JVM/build/coverage gates**

Run:

```powershell
.\gradlew.bat testDebugUnitTest jacocoTestReport assembleDebug
python tools/check_repository_hygiene.py
```

Expected: zero JVM failures, successful report/APK generation, and clean hygiene.

- [ ] **Step 3: Run lint and all Python source/generated contracts**

Run the repository's complete `tools/test_*.py` unittest discovery plus `lint`. Record the exact known lint baseline separately from any new diagnostic. Any new lint diagnostic is a blocker.

```powershell
.\gradlew.bat lint
python -m unittest discover -s tools -p "test_*.py"
```

- [ ] **Step 4: Validate and inspect screenshots**

Run:

```powershell
.\gradlew.bat validateDebugScreenshotTest
```

Inspect every changed reference PNG at original resolution and record its path/hash/result in the review notes.

- [ ] **Step 5: Run connected tests including one real picker journey**

On a clean API 31–37 emulator/device, run:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Require `NanidroidLifecycleInstrumentationTest` to use its queued fake backend to recreate the Activity deterministically once in `Copying` and once in `Installing`, proving no duplicate work. Then use the `docs/testing.md` pinned Snake and Otacon V1.3.2 staging recipe to place that exact corpus archive in device-visible Documents. Through the production UI, choose More Ghost → Install from document → that staged archive and verify one target, success replay, discoverability, no automatic switch, and no notification. Repeat with a text file renamed `invalid.nar` and verify replayable `InvalidArchive` failure plus Select another.

- [ ] **Step 6: Run the fixed 23-NAR corpus**

Use the fixed PR #395 harness/worktree against the exact implementation commit. Require 23/23 rows, 143/143 sentinels, zero archive failure, and cleanup verification. Record the summary JSON path and production commit identity.

- [ ] **Step 7: Verify both ABI inventories**

Inspect the debug APK and require `arm64-v8a` and `x86_64` copies of Satori, SSU, YAYA, and Kawari plus expected AndroidX native content, with no NARFS library. Verify every ARM64 retained `.so` is an AArch64 ELF. Do not claim a physical ARM64 runtime test.

- [ ] **Step 8: Run coordinator full-diff review**

Review `git diff fc394aac...HEAD` requirement by requirement: one ingress, no live legacy root, lifecycle replay, token authority, both staging roots, prepublication discovery, logical target identity, post-publication outcome, UI state source, translations, tests, docs, and deletions. Run `git diff --check` and ensure the worktree is clean after the final evidence commit.

- [ ] **Step 9: Commit evidence-only corrections**

If validation required only test/doc corrections, commit them with:

```powershell
git add src/test src/androidTest src/screenshotTest docs tools
git commit -m "Complete foreground import validation"
```

If no correction was required, do not create an empty commit.

### Task 10: Independent review, GitHub review, merge, and next deletion slice

**Files:**
- Modify: canonical issue `#384` and PR body through GitHub; no production file unless an accepted review finding requires returning to an earlier task.

**Interfaces:**
- Consumes: exact validated implementation HEAD and all evidence from Task 9.
- Produces: merged focused PR, verified default branch, updated issue ledger, and the next backend-deletion entry slice.

- [ ] **Step 1: Dispatch independent local reviews**

Send the exact full diff to a fresh Android lifecycle/Compose reviewer and a fresh adversarial installer/security/reachability reviewer. Neither reviewer may be an implementation worker. Require prioritized high-confidence findings or explicit clear results.

- [ ] **Step 2: Perform coordinator reconciliation**

Inspect every finding against the spec and source. Fix accepted findings in the owning task, rerun affected focused/full gates, then request fresh re-review of the changed exact head. Record rejected findings with technical evidence.

- [ ] **Step 3: Push and open the focused PR**

Push the branch and open a PR linked to `#384`. The body must include product intent, Path A compatibility decision, files/surface removed, coordinator/installer invariants, commands and exact results, screenshot inspection, fixed-corpus evidence, ABI evidence, deferred physical ARM64 runtime test, and both local review outcomes.

- [ ] **Step 4: Wait for GitHub CI and automatic review**

Wait for every required check and GitHub's exact-head automatic review. Inspect aggregate reviews, comments, and every unresolved inline thread; a green check summary alone is insufficient.

- [ ] **Step 5: Resolve GitHub findings and revalidate**

For each actionable finding, apply the smallest owning-task correction, rerun affected tests plus `testDebugUnitTest`, request local independent re-review when material, push, and wait for new exact-head CI/review.

- [ ] **Step 6: Merge and verify default branch**

Merge only with all gates green and no unresolved actionable thread. Fetch/pull default branch, verify the merged tree contains the reviewed implementation, rerun at least `testDebugUnitTest`, static ingress/manifest contracts, and `git diff --check`, then record the squash/merge commit.

- [ ] **Step 7: Update issue #384 and start the next focused deletion design**

Update `#384` with the merged commit, exact validation/review evidence, remaining unreachable backend/dependency surface, and next slice. The next slice deletes queue/repository/workers/receivers/stores/durable machinery; the following slice deletes WorkManager/Hilt/KSP/initializer/build surface unless its design proves those deletions remain one reviewable atomic change.
