# Legacy Archive Runtime Deletion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete Nanidroid's unreachable archive queue and generic durable runtime, then prove that foreground document import remains the only archive-ingress path.

**Architecture:** Keep the foreground `OpenDocument` import coordinator and transactional installer introduced by PR #398. Remove the unreleased WorkManager/Hilt/KSP stack, durable-operation abstractions, queue persistence, background workers, recovery UI, and their manifest/resource residue as one atomic change. Move the one still-used monotonic clock interface into the neutral runtime package before deleting its Hilt owner.

**Tech Stack:** Kotlin, Android API 31–37, Jetpack Compose, Gradle version catalogs, JUnit 4, Python `unittest`, ADB, Android lint, APK/ELF inspection, GitHub CLI.

**Spec:** `docs/superpowers/specs/2026-08-24-delete-legacy-archive-runtime-design.md`

## Global Constraints

- Preserve the exact foreground import behavior landed in PR #398: launcher-owned `OpenDocument`, one foreground import at a time, rotation-safe observation, retry after terminal failure, no automatic ghost switch, and no notification or `ACTION_VIEW` archive ingress.
- Follow Path A from the approved spec. Nanidroid has not shipped, so do not add compatibility migration or cleanup code for developer-only WorkManager or archive-queue state.
- Keep `CatTailApplication` solely for process-start recovery of `ForegroundNarImportCoordinator`.
- Keep `NarTransactionalInstaller`, archive validators, SHIORI engines, and the existing x86_64/arm64-v8a native payloads.
- Treat PR #394 and PR #395 as separate corpus-harness work streams. Use PR #395's fixed harness commit for validation; do not merge its test-framework changes into this production PR.
- Keep `docs/modernization/durable-workflow-review-checklist.md`; it still governs foreground copies and transactional installation. Keep the phase-one ledger, verifier, tests, and historical plans/specs.
- Keep unrelated AIDL, screenshot, JaCoCo, Compose, lifecycle, and native build configuration.
- Use `apply_patch` for all source changes and exact file deletions.
- Work test-first. Every structural deletion contract must fail for the intended reason before the production deletion that makes it pass.
- Preserve unrelated user changes. Stop if a required file has overlapping edits that cannot be safely reconciled.
- Keep commits focused and imperative. Stage only the task's named paths, inspect `git diff --cached --name-only` before each commit, and do not create empty evidence-only commits.
- Physical arm64 execution is deferred by explicit approval. Both-ABI APK inventory and arm64 ELF-header validation remain mandatory.
- Before claiming completion, run the complete local, API 37, corpus, review, and GitHub gates in Tasks 6–8 against the final exact head.

---

## File Structure

### New retained-production file

- `src/main/kotlin/com/cattailsw/nanidroid/runtime/MonotonicClock.kt` — neutral interface used by `SScriptRunner` and retained unit tests.

### New structural contract

- `tools/test_kotlin_legacy_archive_runtime_absence.py` — source, resource, manifest, dependency, and runner absence contract for the deleted stack.

### Production files deleted

- Every file under `src/main/kotlin/com/cattailsw/nanidroid/durable/`.
- `src/main/kotlin/com/cattailsw/nanidroid/compose/durable/DurableStoreRecoveryPrompt.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/compose/durable/StalledOperationPrompt.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/install/DownloadManagerProgressObserver.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/install/InstallNarWorker.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/install/NarDownload.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadReceiver.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadRecoveryReceiver.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadRepository.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadStore.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/install/NarInstallProgressReporter.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/install/NarLocalArchiveStager.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/install/StageLocalNarWorker.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/di/PlatformClockModule.kt` after the clock extraction compiles independently.

### Dedicated tests deleted

- Every file under `src/test/java/com/cattailsw/nanidroid/durable/`.
- `src/test/java/com/cattailsw/nanidroid/install/NarDownloadRepositoryTest.kt`.
- `src/test/java/com/cattailsw/nanidroid/install/NarDownloadStoreTest.kt`.
- `src/test/java/com/cattailsw/nanidroid/install/NarInstallProgressReporterTest.kt`.
- `src/test/java/com/cattailsw/nanidroid/install/NarLocalArchiveStagerTest.kt`.
- `src/test/java/com/cattailsw/nanidroid/DurableBackupRulesTest.kt`.
- `src/androidTest/java/com/cattailsw/nanidroid/durable/DurableOperationAttentionInstrumentationTest.kt`.
- `src/androidTest/java/com/cattailsw/nanidroid/install/InstallNarWorkerCancellationTest.kt`.
- `src/androidTest/java/com/cattailsw/nanidroid/DependencyInjectionSmokeTest.kt`.
- `src/androidTest/java/com/cattailsw/nanidroid/NanidroidTestRunner.kt`.

### Resources and documents deleted

- `src/main/res/xml/backup_rules.xml`.
- `src/main/res/xml/data_extraction_rules.xml`.
- `docs/modernization/durable-operation-transition-table.md`.

### Files modified

- `src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`.
- `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`.
- Five retained `SScriptRunner` test files named in Task 1.
- `src/main/AndroidManifest.xml`.
- `src/main/res/values/strings.xml`.
- `src/main/res/values-ja/strings.xml`.
- `src/main/res/values-zh-rTW/strings.xml`.
- `src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt`.
- `build.gradle.kts`.
- `gradle/libs.versions.toml`.
- `docs/testing.md`.
- `tools/test_kotlin_foreground_nar_import_contract.py`.
- `tools/test_update_entrypoint_artifacts.py`.

---

## Task 1: Move the retained monotonic clock to neutral runtime ownership

**Files:**

- Create: `tools/test_kotlin_legacy_archive_runtime_absence.py`
- Create: `src/main/kotlin/com/cattailsw/nanidroid/runtime/MonotonicClock.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/di/PlatformClockModule.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/DialogueDialogBindingTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueTimingTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueObserverTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerBootDispatchTest.kt`

- [ ] **Step 1: Add the first failing ownership contract**

Create `tools/test_kotlin_legacy_archive_runtime_absence.py` with a repository-root helper and this first test:

```python
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class LegacyArchiveRuntimeAbsenceTest(unittest.TestCase):
    def read(self, relative_path: str) -> str:
        return (ROOT / relative_path).read_text(encoding="utf-8")

    def test_monotonic_clock_has_neutral_runtime_ownership(self) -> None:
        runtime_clock = self.read(
            "src/main/kotlin/com/cattailsw/nanidroid/runtime/MonotonicClock.kt"
        )
        platform_module = self.read(
            "src/main/kotlin/com/cattailsw/nanidroid/di/PlatformClockModule.kt"
        )
        runner = self.read(
            "src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt"
        )

        self.assertIn("fun interface MonotonicClock", runtime_clock)
        self.assertIn("fun nowMillis(): Long", runtime_clock)
        self.assertNotIn("fun interface MonotonicClock", platform_module)
        self.assertIn(
            "typealias MonotonicClock = com.cattailsw.nanidroid.runtime.MonotonicClock",
            platform_module,
        )
        self.assertIn("com.cattailsw.nanidroid.runtime.MonotonicClock", runner)
        self.assertNotIn("com.cattailsw.nanidroid.di.MonotonicClock", runner)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the contract and verify RED**

Run:

```powershell
python -m unittest tools.test_kotlin_legacy_archive_runtime_absence
```

Expected: the test errors because `runtime/MonotonicClock.kt` does not exist. No other failure is acceptable for this RED step.

- [ ] **Step 3: Add the neutral interface**

Create `src/main/kotlin/com/cattailsw/nanidroid/runtime/MonotonicClock.kt` exactly as:

```kotlin
package com.cattailsw.nanidroid.runtime

fun interface MonotonicClock {
    fun nowMillis(): Long
}
```

- [ ] **Step 4: Add the transitional Hilt alias**

Remove the interface declaration from `PlatformClockModule.kt` and add this package-level alias while keeping the existing provider implementation temporarily intact:

```kotlin
typealias MonotonicClock = com.cattailsw.nanidroid.runtime.MonotonicClock
```

The alias is intentionally temporary. It lets the still-present legacy runtime compile until Task 2 deletes it.

- [ ] **Step 5: Move retained imports to the runtime package**

In `SScriptRunner.kt` and all five retained tests listed above, replace:

```kotlin
import com.cattailsw.nanidroid.di.MonotonicClock
```

with:

```kotlin
import com.cattailsw.nanidroid.runtime.MonotonicClock
```

Do not change constructor signatures or clock behavior.

- [ ] **Step 6: Run focused GREEN validation**

Run:

```powershell
python -m unittest tools.test_kotlin_legacy_archive_runtime_absence
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.SScriptRunnerPresentationTest" --tests "com.cattailsw.nanidroid.SScriptRunnerDialogueTimingTest" --tests "com.cattailsw.nanidroid.SScriptRunnerDialogueObserverTest" --tests "com.cattailsw.nanidroid.SScriptRunnerBootDispatchTest" --tests "com.cattailsw.nanidroid.DialogueDialogBindingTest"
.\gradlew.bat testDebugUnitTest assembleDebug
```

Expected: the Python contract and all selected/full Gradle tasks pass. The app still includes Hilt and the legacy runtime at this checkpoint.

- [ ] **Step 7: Commit the ownership move**

```powershell
git add tools/test_kotlin_legacy_archive_runtime_absence.py src/main/kotlin/com/cattailsw/nanidroid/runtime/MonotonicClock.kt src/main/kotlin/com/cattailsw/nanidroid/di/PlatformClockModule.kt src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt src/test/java/com/cattailsw/nanidroid/DialogueDialogBindingTest.kt src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueTimingTest.kt src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueObserverTest.kt src/test/java/com/cattailsw/nanidroid/SScriptRunnerBootDispatchTest.kt
git commit -m "Move monotonic clock to runtime"
```

---

## Task 2: Delete the unreachable archive and durable runtime

**Files:**

- Modify: `tools/test_kotlin_legacy_archive_runtime_absence.py`
- Delete: the production and dedicated test files enumerated below

- [ ] **Step 1: Add the failing exact-path absence contract**

Add a tuple named `LEGACY_RUNTIME_PATHS` containing exactly these relative paths:

```python
LEGACY_RUNTIME_PATHS = (
    "src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperation.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperationAttentionCoordinator.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperationAttentionNotification.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperationStore.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperationSupervisor.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/durable/SharedDurableOperationSupervisor.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/durable/SharedPreferencesDurableOperationStore.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/compose/durable/DurableStoreRecoveryPrompt.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/compose/durable/StalledOperationPrompt.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/DownloadManagerProgressObserver.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/InstallNarWorker.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownload.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadReceiver.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadRecoveryReceiver.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadRepository.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadStore.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarInstallProgressReporter.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarLocalArchiveStager.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/StageLocalNarWorker.kt",
    "src/test/java/com/cattailsw/nanidroid/durable/DurableOperationAttentionCoordinatorTest.kt",
    "src/test/java/com/cattailsw/nanidroid/durable/DurableOperationSupervisorTest.kt",
    "src/test/java/com/cattailsw/nanidroid/durable/SharedDurableOperationSupervisorTest.kt",
    "src/test/java/com/cattailsw/nanidroid/durable/SharedPreferencesDurableOperationStoreTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarDownloadRepositoryTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarDownloadStoreTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarInstallProgressReporterTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarLocalArchiveStagerTest.kt",
    "src/androidTest/java/com/cattailsw/nanidroid/durable/DurableOperationAttentionInstrumentationTest.kt",
    "src/androidTest/java/com/cattailsw/nanidroid/install/InstallNarWorkerCancellationTest.kt",
)
```

Add:

```python
def test_legacy_runtime_paths_are_absent(self) -> None:
    present = [path for path in LEGACY_RUNTIME_PATHS if (ROOT / path).exists()]
    self.assertEqual([], present)
```

Before accepting the tuple, compare it with `rg --files` output. If a current dedicated file has a different name, use the real path and keep the tuple exhaustive.

- [ ] **Step 2: Run the contract and verify RED**

```powershell
python -m unittest tools.test_kotlin_legacy_archive_runtime_absence
```

Expected: `test_legacy_runtime_paths_are_absent` fails and reports the currently present legacy paths.

- [ ] **Step 3: Delete the exact legacy production files**

Use `apply_patch` with `*** Delete File` for all production entries in `LEGACY_RUNTIME_PATHS`. Delete every file under the production `durable` package and only the named archive-runtime files under `install` and `compose/durable`.

- [ ] **Step 4: Delete the exact dedicated tests**

Use `apply_patch` with `*** Delete File` for all test entries in `LEGACY_RUNTIME_PATHS`. Keep `DependencyInjectionSmokeTest.kt` until Task 4 so Hilt still has a smoke test while it exists.

- [ ] **Step 5: Prove no references remain**

Run:

```powershell
rg -n "DurableOperation|NarDownload|InstallNarWorker|StageLocalNarWorker|NarLocalArchiveStager|StalledOperationPrompt|DurableStoreRecoveryPrompt" src build.gradle.kts gradle docs tools
```

Expected: matches are limited to the newly added negative contract, historical design/plan records, or retained test names that explicitly prove absence. Investigate any production/build/resource match before continuing.

- [ ] **Step 6: Run GREEN validation**

```powershell
python -m unittest tools.test_kotlin_legacy_archive_runtime_absence
.\gradlew.bat testDebugUnitTest assembleDebug
```

Expected: both commands pass. WorkManager/Hilt may still be packaged until Task 4, but no application source uses the deleted archive runtime.

- [ ] **Step 7: Commit the runtime deletion**

```powershell
git add tools/test_kotlin_legacy_archive_runtime_absence.py
git add -u -- src/main/kotlin/com/cattailsw/nanidroid/durable src/main/kotlin/com/cattailsw/nanidroid/compose/durable src/test/java/com/cattailsw/nanidroid/durable src/androidTest/java/com/cattailsw/nanidroid/durable
git add -u -- src/main/kotlin/com/cattailsw/nanidroid/install/DownloadManagerProgressObserver.kt src/main/kotlin/com/cattailsw/nanidroid/install/InstallNarWorker.kt src/main/kotlin/com/cattailsw/nanidroid/install/NarDownload.kt src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadReceiver.kt src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadRecoveryReceiver.kt src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadRepository.kt src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadStore.kt src/main/kotlin/com/cattailsw/nanidroid/install/NarInstallProgressReporter.kt src/main/kotlin/com/cattailsw/nanidroid/install/NarLocalArchiveStager.kt src/main/kotlin/com/cattailsw/nanidroid/install/StageLocalNarWorker.kt
git add -u -- src/test/java/com/cattailsw/nanidroid/install/NarDownloadRepositoryTest.kt src/test/java/com/cattailsw/nanidroid/install/NarDownloadStoreTest.kt src/test/java/com/cattailsw/nanidroid/install/NarInstallProgressReporterTest.kt src/test/java/com/cattailsw/nanidroid/install/NarLocalArchiveStagerTest.kt src/androidTest/java/com/cattailsw/nanidroid/install/InstallNarWorkerCancellationTest.kt
git diff --cached --name-only
git commit -m "Delete legacy archive runtime"
```

---

## Task 3: Remove obsolete durable resources, backup rules, and documentation

**Files:**

- Modify: `tools/test_kotlin_legacy_archive_runtime_absence.py`
- Modify: `src/main/AndroidManifest.xml`
- Modify: `src/main/res/values/strings.xml`
- Modify: `src/main/res/values-ja/strings.xml`
- Modify: `src/main/res/values-zh-rTW/strings.xml`
- Delete: `src/main/res/xml/backup_rules.xml`
- Delete: `src/main/res/xml/data_extraction_rules.xml`
- Delete: `src/test/java/com/cattailsw/nanidroid/DurableBackupRulesTest.kt`
- Delete: `docs/modernization/durable-operation-transition-table.md`

- [ ] **Step 1: Add failing resource and file absence tests**

Import `xml.etree.ElementTree as ET`. Add the exact obsolete name tuple:

```python
OBSOLETE_DURABLE_STRINGS = (
    "durable_attention_channel_name",
    "durable_attention_channel_description",
    "durable_attention_title",
    "durable_operation_remote_nar",
    "durable_operation_local_nar",
    "durable_operation_nar_install",
    "durable_action_keep_waiting",
    "durable_action_stop",
    "durable_action_retry_stop",
    "durable_phase_downloading",
    "durable_phase_copying",
    "durable_phase_installing",
    "durable_phase_stopping",
    "durable_diagnostics_label",
    "durable_diagnostic_cancel_dispatch_failed",
    "durable_diagnostic_stopping_delayed",
    "durable_store_recovery_title",
    "durable_store_recovery_message",
    "durable_store_recovery_confirm",
    "durable_store_recovery_failed",
)
```

Add tests that parse each of the three `strings.xml` files, assert that its declared names are disjoint from the tuple, and assert that no declared name starts with `durable_`. Add a file-absence test for the two XML rule files, the durable transition table, and `DurableBackupRulesTest.kt`. Add a manifest test asserting the application element has neither Android `fullBackupContent` nor `dataExtractionRules`.

- [ ] **Step 2: Run the contract and verify RED**

```powershell
python -m unittest tools.test_kotlin_legacy_archive_runtime_absence
```

Expected: resource/file/manifest absence tests fail because the durable assets still exist.

- [ ] **Step 3: Remove obsolete manifest backup attributes**

Remove only `android:fullBackupContent` and `android:dataExtractionRules` from the `<application>` element. Keep the application class, icon, label, launcher activity, and the temporary WorkManager-related merge rules until Task 4.

- [ ] **Step 4: Remove obsolete localized strings**

Delete every `string` element named in `OBSOLETE_DURABLE_STRINGS` from all three locale files. Do not reorder unrelated resources and do not remove any foreground-import or installer validation string.

- [ ] **Step 5: Delete obsolete backup and transition artifacts**

Use `apply_patch` to delete:

```text
src/main/res/xml/backup_rules.xml
src/main/res/xml/data_extraction_rules.xml
src/test/java/com/cattailsw/nanidroid/DurableBackupRulesTest.kt
docs/modernization/durable-operation-transition-table.md
```

- [ ] **Step 6: Run resource GREEN validation**

```powershell
python -m unittest tools.test_kotlin_legacy_archive_runtime_absence
.\gradlew.bat testDebugUnitTest assembleDebug validateDebugScreenshotTest
```

Expected: contract, unit/resource compilation, APK assembly, and all 31 screenshot cases pass with no golden-image change.

- [ ] **Step 7: Commit the resource cleanup**

```powershell
git add src/main/AndroidManifest.xml src/main/res/values/strings.xml src/main/res/values-ja/strings.xml src/main/res/values-zh-rTW/strings.xml tools/test_kotlin_legacy_archive_runtime_absence.py
git add -u -- src/main/res/xml/backup_rules.xml src/main/res/xml/data_extraction_rules.xml src/test/java/com/cattailsw/nanidroid/DurableBackupRulesTest.kt docs/modernization/durable-operation-transition-table.md
git diff --cached --name-only
git commit -m "Remove obsolete durable resources"
```

---

## Task 4: Remove WorkManager, Hilt, AndroidX Hilt, and KSP

**Files:**

- Modify: `tools/test_kotlin_legacy_archive_runtime_absence.py`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Modify: `src/main/AndroidManifest.xml`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt`
- Modify: `build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Delete: `src/main/kotlin/com/cattailsw/nanidroid/di/PlatformClockModule.kt`
- Delete: `src/androidTest/java/com/cattailsw/nanidroid/NanidroidTestRunner.kt`
- Delete: `src/androidTest/java/com/cattailsw/nanidroid/DependencyInjectionSmokeTest.kt`

- [ ] **Step 1: Add failing platform-stack absence assertions**

Extend `test_kotlin_legacy_archive_runtime_absence.py` with tests that assert:

- `PlatformClockModule.kt`, `NanidroidTestRunner.kt`, and `DependencyInjectionSmokeTest.kt` do not exist.
- `build.gradle.kts` contains none of `libs.plugins.hilt`, `libs.plugins.ksp`, `libs.work.runtime`, `libs.androidx.hilt.work`, `libs.hilt.android`, `ksp(`, `kspAndroidTest(`, `libs.hilt.android.testing`, `libs.work.testing`, or `libs.androidx.hilt.compiler`.
- `gradle/libs.versions.toml` contains no version, library, or plugin aliases for WorkManager, Hilt, AndroidX Hilt, or KSP.
- `CatTailApplication.kt` contains no Hilt, WorkManager, worker-factory, configuration-provider, or injected-field symbol and does call `ForegroundNarImportCoordinator.get(this)` in `onCreate`.
- `Nanidroid.kt` contains neither `AndroidEntryPoint` nor a `dagger.hilt` import.
- `NanidroidLifecycleInstrumentationTest.kt` contains no Hilt runner/rule/annotation/import and retains all six existing `@Test` methods.
- `build.gradle.kts` sets `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`.
- The source manifest contains no `tools` namespace, `androidx.work` component, foreground-service tombstone, provider, or WorkManager initializer override.

- [ ] **Step 2: Run the expanded contract and verify RED**

```powershell
python -m unittest tools.test_kotlin_legacy_archive_runtime_absence
```

Expected: the new tests fail on the present Hilt/WorkManager/KSP configuration and files.

- [ ] **Step 3: Reduce `CatTailApplication` to startup recovery**

Replace its implementation with:

```kotlin
package com.cattailsw.nanidroid

import android.app.Application
import com.cattailsw.nanidroid.install.ForegroundNarImportCoordinator

class CatTailApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ForegroundNarImportCoordinator.get(this)
    }
}
```

- [ ] **Step 4: Remove activity and lifecycle-test Hilt wiring**

From `Nanidroid.kt`, remove the Dagger import and `@AndroidEntryPoint`; leave all launcher and Compose behavior unchanged.

From `NanidroidLifecycleInstrumentationTest.kt`, remove Hilt imports, the Hilt runner annotation, injected fields, Hilt rule, and `org.junit.Rule`. Preserve the six existing test methods and their assertions.

- [ ] **Step 5: Remove application build dependencies and plugins**

In `build.gradle.kts`:

- Remove Hilt and KSP plugins.
- Change the instrumentation runner to `androidx.test.runner.AndroidJUnitRunner`.
- Remove WorkManager runtime/testing, Hilt Android/testing/compiler, AndroidX Hilt Work/compiler, and all KSP configurations.
- Preserve `javax.inject.Inject` on the Gradle task constructor; it is Gradle build-script injection, not application Hilt.
- Keep unrelated Compose, lifecycle, test, screenshot, JaCoCo, and native build configuration unchanged.

- [ ] **Step 6: Remove catalog aliases**

In `gradle/libs.versions.toml`, remove only the now-unused WorkManager, Hilt, AndroidX Hilt, and KSP version keys, library aliases, and plugin aliases. Confirm every remaining catalog alias is referenced or intentionally shared.

- [ ] **Step 7: Reduce the source manifest**

Produce a manifest with:

- no `xmlns:tools` declaration;
- no WorkManager services, receivers, providers, initializer override, or foreground-service tombstone;
- no source-declared permission;
- the `CatTailApplication` application name, icon, label, launcher activity, and launcher intent filter retained.

Dependency manifests may still contribute unrelated profile-installer components in the merged manifest; do not add source tombstones for them.

- [ ] **Step 8: Delete Hilt-only files**

Use `apply_patch` to delete:

```text
src/main/kotlin/com/cattailsw/nanidroid/di/PlatformClockModule.kt
src/androidTest/java/com/cattailsw/nanidroid/NanidroidTestRunner.kt
src/androidTest/java/com/cattailsw/nanidroid/DependencyInjectionSmokeTest.kt
```

At the same time, revise `test_monotonic_clock_has_neutral_runtime_ownership` so it no longer opens the deleted `PlatformClockModule.kt`. Keep the exact runtime-interface assertions and assert that `SScriptRunner.kt` imports `com.cattailsw.nanidroid.runtime.MonotonicClock`. The separate platform-file absence test now owns proof that the transitional alias is gone.

- [ ] **Step 9: Clean generated state and run GREEN validation**

```powershell
.\gradlew.bat clean testDebugUnitTest assembleDebug
python -m unittest tools.test_kotlin_legacy_archive_runtime_absence
```

Expected: clean compilation, unit tests, APK assembly, and every new absence assertion pass. Cleaning is mandatory so stale Hilt/KSP generated classes cannot mask an incomplete teardown.

- [ ] **Step 10: Commit the platform teardown**

```powershell
git add build.gradle.kts gradle/libs.versions.toml src/main/AndroidManifest.xml src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt tools/test_kotlin_legacy_archive_runtime_absence.py
git add -u -- src/main/kotlin/com/cattailsw/nanidroid/di/PlatformClockModule.kt src/androidTest/java/com/cattailsw/nanidroid/NanidroidTestRunner.kt src/androidTest/java/com/cattailsw/nanidroid/DependencyInjectionSmokeTest.kt
git diff --cached --name-only
git commit -m "Remove WorkManager and Hilt stack"
```

---

## Task 5: Update generated-manifest, foreground-import, and documentation contracts

**Files:**

- Modify: `tools/test_update_entrypoint_artifacts.py`
- Modify: `tools/test_kotlin_foreground_nar_import_contract.py`
- Modify: `tools/test_kotlin_legacy_archive_runtime_absence.py`
- Modify: `docs/testing.md`

- [ ] **Step 1: Demonstrate that the old generated-manifest contract is obsolete**

After Task 4 has assembled the APK, run:

```powershell
python -m unittest tools.test_update_entrypoint_artifacts tools.test_kotlin_foreground_nar_import_contract
```

Expected: the two WorkManager-positive service/permission/receiver tests fail because those generated components are gone, and the foreground contract's old merge-controls method fails because the source tombstones/provider are gone. The old initializer-suppression test may remain green because absence satisfies its narrow assertion; it still must be generalized in Step 2. Record the method names and confirm there is no unrelated failure.

- [ ] **Step 2: Replace positive WorkManager assertions with exact negative contracts**

In `tools/test_update_entrypoint_artifacts.py`:

- Replace `test_removed_service_and_foreground_permissions_are_absent` with `test_removed_services_and_workmanager_components_are_absent`.
- Assert that the production merged-manifest permission set is disjoint from foreground-service, data-sync, internet, notification, network-state, boot-completed, wake-lock, and legacy storage permissions.
- Assert `NanidroidService` is absent and no merged service name starts with `androidx.work.`.
- Replace `test_only_dependency_archive_permissions_and_receivers_remain` with a receiver test asserting no receiver name starts with `androidx.work.` while allowing the dependency-provided profile-installer receiver.
- Replace `test_workmanager_initializer_remains_suppressed` with a scan of all provider metadata that asserts `androidx.work.WorkManagerInitializer` is absent. Do not require the AndroidX Startup provider itself.

- [ ] **Step 3: Update the foreground import source-manifest contract**

In `tools/test_kotlin_foreground_nar_import_contract.py`, remove requirements for the WorkManager initializer-removal marker and the foreground-service tombstone. Assert instead that the source manifest has:

- no provider element;
- no source permission element;
- no `xmlns:tools` text;
- the retained launcher activity and `CatTailApplication` declaration.

Keep every behavioral source assertion for the foreground coordinator, `OpenDocument` launcher, foreground presentation, retry, and transactional installer.

- [ ] **Step 4: Complete the legacy-stack negative contract**

Review `test_kotlin_legacy_archive_runtime_absence.py` as a single structural boundary. Ensure it covers:

- every exact deleted production/test/resource/document path;
- all obsolete localized string names;
- no Hilt/WorkManager/KSP build or catalog aliases;
- plain application/activity/test-runner wiring;
- retained `ForegroundNarImportCoordinator`, `NarTransactionalInstaller`, `SScriptRunner`, and runtime-owned `MonotonicClock`.
- a scan of every remaining production Kotlin file that rejects `androidx.work`, `androidx.hilt`, `dagger.hilt`, `NarDownloadRepository`, `SharedDurableOperationSupervisor`, `DurableOperation`, `InstallNarWorker`, `StageLocalNarWorker`, `NarLocalArchiveStager`, and `DownloadManagerProgressObserver`.

Do not duplicate generated-APK parsing already owned by `test_update_entrypoint_artifacts.py`.

- [ ] **Step 5: Update testing documentation**

In `docs/testing.md`:

- State that connected tests use the standard `androidx.test.runner.AndroidJUnitRunner` and real `CatTailApplication`.
- Remove instructions that depend on Hilt test application/runner setup.
- Document the production merged-manifest expectation: no WorkManager services, receivers, initializer metadata, or WorkManager-derived permissions.
- Keep the phase-1 shipped-state audit wording explicitly historical; do not rewrite historical verifier intent as current architecture.
- Add the new focused Python contract to the appropriate local verification command list.

- [ ] **Step 6: Run focused contract GREEN validation**

```powershell
.\gradlew.bat assembleDebug lint
python -m unittest tools.test_kotlin_legacy_archive_runtime_absence tools.test_kotlin_foreground_nar_import_contract tools.test_update_entrypoint_artifacts tools.test_verify_phase1_shipped_state_audit
.\gradlew.bat dependencyInsight --configuration debugRuntimeClasspath --dependency androidx.work
.\gradlew.bat dependencyInsight --configuration debugRuntimeClasspath --dependency androidx.hilt
.\gradlew.bat dependencyInsight --configuration debugRuntimeClasspath --dependency com.google.dagger
```

Expected: Gradle assembly/lint and all four Python modules pass. Each dependency query reports no matching dependency in `debugRuntimeClasspath`.

- [ ] **Step 7: Commit the contracts and documentation**

```powershell
git add tools/test_update_entrypoint_artifacts.py tools/test_kotlin_foreground_nar_import_contract.py tools/test_kotlin_legacy_archive_runtime_absence.py docs/testing.md
git commit -m "Prove legacy platform stack is absent"
```

---

## Task 6: Run complete local and static validation

**Files:**

- Verify: all changed files
- Compare: merge base `948ba5f947bd1bc4f37cdb0758e901f757f25af3`
- Inspect: `build/outputs/apk/debug/Nanidroid-debug.apk`

- [ ] **Step 1: Run retained behavior suites**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.install.ForegroundNarImportCoordinatorTest" --tests "com.cattailsw.nanidroid.install.NarTransactionalInstallerTest" --tests "com.cattailsw.nanidroid.SScriptRunnerPresentationTest" --tests "com.cattailsw.nanidroid.SScriptRunnerDialogueTimingTest" --tests "com.cattailsw.nanidroid.SScriptRunnerDialogueObserverTest" --tests "com.cattailsw.nanidroid.SScriptRunnerBootDispatchTest" --tests "com.cattailsw.nanidroid.DialogueDialogBindingTest"
```

Expected: every retained foreground-import, installer, and script-runtime suite passes.

- [ ] **Step 2: Run the full JVM/build matrix twice**

```powershell
.\gradlew.bat testDebugUnitTest jacocoTestReport assembleDebug
.\gradlew.bat testDebugUnitTest --rerun-tasks
```

Expected: all tasks pass; the rerun proves the result is not a Gradle cache artifact.

- [ ] **Step 3: Run lint and classify only reproduced baseline debt**

```powershell
.\gradlew.bat lint
git worktree list --porcelain
```

Compare lint issue identities with the exact PR #398 merge base. Any new issue identity is a regression and must be fixed. Existing debt may be reported only after reproducing the same identity at `948ba5f947bd1bc4f37cdb0758e901f757f25af3` in an isolated worktree.

- [ ] **Step 4: Run every repository Python contract**

```powershell
python -m unittest discover -s tools -p "test_*.py"
python -m unittest tools.test_kotlin_legacy_archive_runtime_absence tools.test_kotlin_foreground_nar_import_contract tools.test_update_entrypoint_artifacts tools.test_verify_phase1_shipped_state_audit
```

Expected: all change-owned modules pass. A repository-wide failure can be classified as baseline debt only after exact merge-base reproduction; otherwise fix it.

- [ ] **Step 5: Run screenshot validation**

```powershell
.\gradlew.bat validateDebugScreenshotTest
```

Expected: all 31 screenshot cases pass without changing a golden. If an image differs, inspect both images at original detail and treat any unexplained visual delta as a regression.

- [ ] **Step 6: Run repository hygiene and diff checks**

```powershell
python tools/check_repository_hygiene.py
git diff --check 948ba5f947bd1bc4f37cdb0758e901f757f25af3..HEAD
git status --short
```

Expected: no new hygiene violation, no whitespace error, and no unexplained worktree change. Separately report proven pre-existing hygiene debt rather than folding it into this PR.

- [ ] **Step 7: Prove dependency and merged-manifest absence**

```powershell
.\gradlew.bat dependencies --configuration debugRuntimeClasspath
python -m unittest tools.test_update_entrypoint_artifacts
```

Inspect the dependency output for `androidx.work`, `androidx.hilt`, `com.google.dagger:hilt`, and KSP runtime residue. Expected: none is present. The generated-manifest contract must prove no WorkManager service, receiver, initializer metadata, or derived permission remains.

- [ ] **Step 8: Record exact APK identity and size delta**

```powershell
$apkPath = Resolve-Path build\outputs\apk\debug\Nanidroid-debug.apk
$apkItem = Get-Item -LiteralPath $apkPath
$apkHash = Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath
$apkItem | Select-Object FullName,Length
$apkHash | Select-Object Algorithm,Hash,Path
```

Compare with the PR #398 baseline: 47,686,389 bytes and SHA-256 `b31b16ad2363783425441d61beeeaff1ca51d1b77245e7f019da6eff732000e1`. Record the new exact values in the PR; there is no arbitrary size threshold.

- [ ] **Step 9: Prove both ABI payloads and arm64 ELF headers**

Inventory the APK directly:

```powershell
$apkPath = (Resolve-Path build\outputs\apk\debug\Nanidroid-debug.apk).Path
$nativeEntries = @(& jar tf $apkPath | Where-Object { $_ -match '^lib/.+[.]so$' } | Sort-Object)
$nativeEntries
if ($nativeEntries -match 'narfs') { throw 'NARFS library remains in the APK' }
$requiredNativeNames = @('libandroidx.graphics.path.so', 'libsatoriya.so', 'libssu.so', 'libkawari8.so', 'libyaya.so')
foreach ($abi in @('arm64-v8a', 'x86_64')) {
    foreach ($nativeName in $requiredNativeNames) {
        if ("lib/$abi/$nativeName" -notin $nativeEntries) {
            throw "Missing $nativeName for $abi"
        }
    }
}
```

Require the AndroidX native entry plus Kawari, Satori, SSU, and YAYA for both `x86_64` and `arm64-v8a`, and require no NARFS library. Then extract only the arm64-v8a APK directory and inspect it:

```powershell
$arm64InspectionRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("nanidroid-arm64-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $arm64InspectionRoot | Out-Null
Push-Location $arm64InspectionRoot
try {
    & jar xf $apkPath 'lib/arm64-v8a'
    if ($LASTEXITCODE -ne 0) { throw 'APK arm64 extraction failed' }
    $sdkRoot = $env:ANDROID_SDK_ROOT
    if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_HOME }
    if (-not $sdkRoot) { throw 'ANDROID_SDK_ROOT or ANDROID_HOME is required' }
    $readElf = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'ndk') -Filter llvm-readelf.exe -File -Recurse |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($null -eq $readElf) { throw 'NDK llvm-readelf.exe was not found' }
    Get-ChildItem -LiteralPath (Join-Path $arm64InspectionRoot 'lib\arm64-v8a') -Filter *.so -File |
        ForEach-Object {
            $header = & $readElf.FullName -h $_.FullName
            if ($header -notmatch 'Class:\s+ELF64') { throw "$($_.Name) is not ELF64" }
            if ($header -notmatch 'Machine:\s+AArch64') { throw "$($_.Name) is not AArch64" }
            $header
        }
} finally {
    Pop-Location
    $resolvedInspectionRoot = (Resolve-Path -LiteralPath $arm64InspectionRoot).Path
    if (-not $resolvedInspectionRoot.StartsWith([System.IO.Path]::GetTempPath(), [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove non-temporary path: $resolvedInspectionRoot"
    }
    Remove-Item -LiteralPath $resolvedInspectionRoot -Recurse -Force
}
```

Require `Class: ELF64` and `Machine: AArch64` for every arm64 file.

- [ ] **Step 10: Correct any failure through its owning test**

For each change-owned failure, return to the responsible task, reproduce RED, apply the narrow fix, rerun that task's focused GREEN commands, and create a focused imperative commit. Then restart Task 6 from Step 1. Do not create a commit if no correction was required.

---

## Task 7: Validate API 37 device behavior and the existing 23-NAR corpus

**Files:**

- Test APK: `build/outputs/apk/androidTest/debug/Nanidroid-debug-androidTest.apk`
- Production APK: `build/outputs/apk/debug/Nanidroid-debug.apk`
- Fixed corpus harness: PR #395 commit `f623e032bf7446496a626b990cd30701a83e2298`
- Harness checkout: `C:\tmp\N395`
- Production checkout: `C:\Users\yenchi\.codex\worktrees\27f9\Nanidroid`

- [ ] **Step 1: Select and verify the API 37 emulator**

```powershell
$deviceSerial = $null
foreach ($candidateLine in @(adb devices | Select-String 'emulator-[0-9]+\s+device')) {
    $candidateSerial = $candidateLine.ToString().Split()[0]
    if ((adb -s $candidateSerial shell getprop ro.build.version.sdk).Trim() -eq '37') {
        $deviceSerial = $candidateSerial
        break
    }
}
if (-not $deviceSerial) { throw 'No online API 37 emulator is available' }
$apiLevel = (adb -s $deviceSerial shell getprop ro.build.version.sdk).Trim()
```

Expected: an online API 37 emulator serial is bound. Do not silently substitute an older API.

- [ ] **Step 2: Run the six-method lifecycle class with the standard runner**

```powershell
$env:ANDROID_SERIAL = $deviceSerial
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.NanidroidLifecycleInstrumentationTest
```

Expected: all six retained lifecycle methods pass under `androidx.test.runner.AndroidJUnitRunner` with the real `CatTailApplication`.

- [ ] **Step 3: Run focused foreground presentation and installer classes**

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.compose.ForegroundNarImportPresentationTest
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.install.NarTransactionalInstallerInstrumentationTest
```

Expected: both focused classes pass.

- [ ] **Step 4: Run the complete connected suite**

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Expected: all instrumentation tests pass. A red may be classified as baseline only after it reproduces at the exact PR #398 merge base on the same emulator state.

- [ ] **Step 5: Exercise the production document picker manually**

Install the production debug APK and verify these cases through the launcher UI:

1. Select a valid NAR and observe foreground progress through completion.
2. Rotate during an in-progress import and confirm one operation remains observed.
3. Select an invalid archive and confirm a terminal error with a usable retry path.
4. Retry with a valid archive and confirm completion.
5. Confirm completion does not automatically switch the active ghost.
6. Confirm no foreground notification appears.
7. Confirm an external `ACTION_VIEW` intent for a NAR has no Nanidroid resolver.

Uninstall both packages after the walkthrough so corpus execution starts clean:

```powershell
adb -s $deviceSerial uninstall com.cattailsw.nanidroid.test
adb -s $deviceSerial uninstall com.cattailsw.nanidroid
```

Accept `Unknown package` only when that package was not installed during the walkthrough; verify both packages are absent with `adb -s $deviceSerial shell pm list packages com.cattailsw.nanidroid`.

- [ ] **Step 6: Verify the fixed PR #395 harness checkout**

```powershell
git -C C:\tmp\N395 status --short
git -C C:\tmp\N395 rev-parse HEAD
```

Expected: the checkout is clean and HEAD is exactly `f623e032bf7446496a626b990cd30701a83e2298`. If the checkout is absent, create a clean detached worktree at that exact commit; do not run against a moving PR head.

- [ ] **Step 7: Verify the 23-NAR corpus roots**

```powershell
$corpusRoots = @(
    'C:\work\src\Nanidroid\2elf-2.46.nar',
    'C:\work\src\Nanidroid\build\ui-audit\ghosts',
    'C:\work\src\Nanidroid\build\ui-audit\pcPets'
)
$missingCorpusRoots = $corpusRoots | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missingCorpusRoots) { throw "Missing corpus roots: $($missingCorpusRoots -join ', ')" }
```

Expected: all three approved roots exist. The harness inventory must resolve exactly 23 NARs.

- [ ] **Step 8: Run the fixed harness against the exact deletion head**

```powershell
$productionCommit = git -C C:\Users\yenchi\.codex\worktrees\27f9\Nanidroid rev-parse HEAD
& C:\tmp\N395\scripts\run-nar-corpus-audit.ps1 `
    -DeviceSerial $deviceSerial `
    -CorpusRoots $corpusRoots `
    -ProductionCheckoutPath 'C:\Users\yenchi\.codex\worktrees\27f9\Nanidroid' `
    -ProductionCommit $productionCommit `
    -HarnessCommit 'f623e032bf7446496a626b990cd30701a83e2298'
```

Expected: the harness reports 23/23 completed rows, 143/143 sentinels, zero failures, zero timeouts, and successful cleanup. Inspect `C:\tmp\N395\build\reports\nar-corpus\summary.json` and record production commit, harness commit, production APK SHA-256, device serial/API, row count, sentinel count, and cleanup outcome.

- [ ] **Step 9: Record the arm64 validation boundary**

State in the PR evidence that physical arm64 runtime execution is deferred by explicit approval. Link it to the Task 6 proof that every required arm64-v8a library is packaged and has an ELF64/AArch64 header. Do not represent emulator execution as physical arm64 validation.

- [ ] **Step 10: Correct and repeat if necessary**

Any change-owned device or corpus failure returns to a focused failing test and narrow implementation fix, followed by Tasks 6 and 7 from the beginning. Do not change the fixed harness commit to obtain a passing result, and do not create a commit when no source correction is needed.

---

## Task 8: Complete coordinator, multi-agent, GitHub, and merge review gates

**Files:**

- Review range: `948ba5f947bd1bc4f37cdb0758e901f757f25af3..HEAD`
- Issue: GitHub issue #384

- [ ] **Step 1: Perform the coordinator's exact-range review**

Review every diff hunk and deletion against the approved spec. Confirm:

- no reachable foreground-import behavior was removed;
- no generic runtime/Hilt/WorkManager/KSP source or build residue remains;
- the standard runner still has all retained lifecycle coverage;
- manifests, resources, docs, and contracts agree;
- no PR #394/#395 production work was accidentally absorbed;
- validation evidence identifies exact commits and artifacts.

Run `git diff --check` and the focused Python contracts again while reviewing.

- [ ] **Step 2: Dispatch two fresh independent read-only reviews**

Dispatch concurrently:

1. An Android lifecycle/platform reviewer for application startup, activity recreation, instrumentation runner migration, Gradle/catalog cleanup, source/merged manifest behavior, and API 31–37 risks.
2. An adversarial reachability/security reviewer for hidden archive ingress, stale queue/work resurrection, content-URI handling, cancellation/retry semantics, deleted-resource references, and untested compatibility assumptions.

Give each reviewer the approved spec, exact review range, validation summary, and instruction to report only high-confidence actionable defects with file/line evidence. Reviewers must not edit the worktree.

- [ ] **Step 3: Resolve all review findings with evidence**

For every finding, independently verify the claim. Accepted findings require a reproducing test/contract, narrow fix, focused commit, and rerun of the owning task plus Tasks 6–7. Rejected findings require a concise evidence-backed disposition. Re-dispatch both fresh review roles against the new exact head after any accepted fix.

- [ ] **Step 4: Run the final exact-head local gates**

```powershell
.\gradlew.bat testDebugUnitTest jacocoTestReport assembleDebug lint
.\gradlew.bat testDebugUnitTest --rerun-tasks
python -m unittest discover -s tools -p "test_*.py"
python -m unittest tools.test_kotlin_legacy_archive_runtime_absence tools.test_kotlin_foreground_nar_import_contract tools.test_update_entrypoint_artifacts tools.test_verify_phase1_shipped_state_audit
.\gradlew.bat validateDebugScreenshotTest
python tools/check_repository_hygiene.py
git diff --check 948ba5f947bd1bc4f37cdb0758e901f757f25af3..HEAD
git status --short
```

Expected: all change-owned gates pass on the exact reviewed head. Re-run the exact API 37 and corpus commands if any accepted fix can affect runtime, packaging, resources, manifests, or tests.

- [ ] **Step 5: Push and open the focused PR**

```powershell
git push -u origin codex/phase2-delete-legacy-runtime
gh pr create --base master --head codex/phase2-delete-legacy-runtime --title "Delete legacy archive runtime" --body-file build\reports\delete-legacy-runtime-pr-body.md
```

Create `build/reports/delete-legacy-runtime-pr-body.md` with `apply_patch`, use it for PR creation, and delete it with `apply_patch` after the PR is open. The ignored report path must not be staged. The PR body must include:

- Path A and the unreleased-state rationale;
- exact deleted architecture and retained foreground path;
- local, API 37, manual, corpus, ABI/ELF, screenshot, lint, and hygiene evidence;
- old/new APK size and exact SHA-256;
- fixed PR #395 harness commit and 23/23 plus 143/143 results;
- physical arm64 runtime deferral;
- coordinator and both independent review outcomes;
- reproduced baseline debt separated from change-owned results;
- `Closes #384` only if this PR completes the issue's accepted scope; otherwise `Refs #384` with the remaining slice stated precisely.

- [ ] **Step 6: Request and wait for GitHub automatic review**

Request `@codex review` on the PR. Wait for every required GitHub Actions check and the automatic Codex review to reach a terminal state. Inspect:

```powershell
gh pr view --json url,headRefOid,mergeStateStatus,reviewDecision,statusCheckRollup,reviews,comments
gh pr checks
$prNumber = gh pr view --json number --jq .number
gh api repos/xCatG/Nanidroid/pulls/$prNumber/comments --paginate
```

Also inspect unresolved GraphQL review threads; top-level review summaries alone are insufficient.

- [ ] **Step 7: Resolve GitHub findings and revalidate exact head**

For each automatic or human finding, verify it using the receiving-review discipline. Accepted findings follow the same test-first fix, focused commit, full owning gates, fresh two-agent review, push, and GitHub re-review loop. Resolve threads only after the fixing commit is visible and verified. Continue until CI, automatic review, independent reviews, and coordinator review are all clean on the same head OID.

- [ ] **Step 8: Merge and verify the result**

After all gates are green and the PR is mergeable, merge using the repository's established method. Then:

```powershell
git fetch origin master
git rev-parse origin/master
gh pr view --json state,mergedAt,mergeCommit,url
```

Expected: the PR reports `MERGED`, and `origin/master` contains the merge commit.

- [ ] **Step 9: Update issue #384 and identify the next focused slice**

Comment on issue #384 with the merged PR, exact merge commit, deleted stack summary, retained foreground behavior, validation evidence, physical arm64 deferral, and whether any accepted issue scope remains. Inspect open issue/PR state and continue the focused-PR loop only with a separately approved next slice.

---

## Coverage Matrix

| Approved design concern | Owning task and proof |
| --- | --- |
| Preserve foreground document import | Tasks 4–7: plain startup hook, source contract, retained unit/device tests, manual picker walkthrough |
| Delete archive queue and generic durable runtime | Task 2 exact-path negative contract and clean build |
| Delete durable UI/resources/backup residue | Task 3 XML/file/manifest negative contracts |
| Remove WorkManager/Hilt/AndroidX Hilt/KSP | Tasks 4–6 build/catalog/source/dependency/merged-manifest proofs |
| Preserve `SScriptRunner` clock behavior | Task 1 neutral interface plus retained timing/presentation tests |
| No compatibility migration for unreleased state | Global constraint, Task 8 review/PR rationale |
| Keep PR #394/#395 separate | Global constraint, Task 7 fixed harness-only validation, Task 8 range review |
| Validate existing 23-NAR set | Task 7 fixed commit, 23/23 rows, 143/143 sentinels |
| Ease physical arm64 requirement without losing packaging proof | Tasks 6–7 both-ABI inventory, ELF64/AArch64 headers, explicit runtime deferral |
| User-required multi-agent and GitHub reviews | Task 8 coordinator, two fresh agents, GitHub Actions, automatic Codex review, exact-head loop |
