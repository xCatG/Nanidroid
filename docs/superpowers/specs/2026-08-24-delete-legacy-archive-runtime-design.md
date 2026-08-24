# Legacy Archive Runtime Deletion Design

**Status:** Approved in chat on 2026-08-24; written for review before implementation.

**Issue:** #384

**Prerequisite:** PR #398, merged as `948ba5f947bd1bc4f37cdb0758e901f757f25af3`.

## Decision summary

Delete the unreachable archive queue, generic durable-operation runtime, and
their WorkManager/Hilt/KSP platform stack in one focused atomic change. Keep the
foreground `OpenDocument` import introduced by #398, its process-owned recovery,
the transactional installer, and all retained SHIORI engines.

This is one conceptual deletion boundary even though the diff is large. The
legacy production files reference one another, their dedicated tests exercise
only that retired system, and Gradle dependency inspection shows WorkManager,
Hilt, and AndroidX Hilt enter the app through direct dependencies owned by the
same cluster. Splitting the work would deliberately ship an intermediate build
that still packages inert services, receivers, permissions, code generation,
and test infrastructure.

## Context

Issue #382 selected compatibility Path A: no state-capable APK was signed,
released, or distributed. There is therefore no user or release state whose
queue records, durable-operation records, WorkManager requests, DownloadManager
rows, notifications, or URI grants must remain decodable or recoverable.

PR #397 removed the unreachable NARFS/retained-overlay cluster. PR #398 then
replaced every user-reachable archive ingress with one foreground document
picker and removed archive queue/durable UI, URL entry, incoming NAR `VIEW`,
archive receivers from the source manifest, and application startup access to
the old supervisor. What remains is mutually supporting but unreachable legacy
backend code plus the build and test infrastructure that packages it.

Current discovery found approximately 5,836 production lines in the legacy
backend, durable, and dead Compose-prompt files, plus more than 10,000 dedicated
test lines. `MonotonicClock` is the only type in the Hilt module used by retained
runtime code. `CatTailApplication` is still required, but only as the plain
application-startup hook that creates `ForegroundNarImportCoordinator` and
starts owned-staging recovery.

## Product contract after deletion

The archive product contract remains exactly the one approved for #398:

- The only user archive ingress is `ActivityResultContracts.OpenDocument`.
- The selected `content://` source is copied immediately into bounded,
  app-owned staging while the temporary grant is live.
- The process-owned coordinator survives Activity recreation but makes no
  promise to continue after process death or app termination.
- Startup recovery sweeps only canonical, no-follow, verified app-owned staging
  entries and exposes interruption or cleanup failure as replayable state.
- Installation remains fresh-install-only. An existing target ID returns
  `TargetExists` and neither tree is modified.
- Validation, absent-target verification, private candidate construction,
  discoverability validation, atomic publication, and post-publication outcome
  precedence stay authoritative.
- Success refreshes the catalog without switching the active ghost.
- There is no remote acquisition, URL entry, incoming NAR `VIEW`, queue,
  history, notification, background continuation, persisted URI grant,
  replacement, or automatic retry.

## Goals

1. Remove every production class whose only purpose is the retired archive
   queue, DownloadManager handoff, WorkManager execution, durable state,
   durable attention, or dead durable Compose presentation.
2. Remove WorkManager, Hilt, AndroidX Hilt, and KSP from source, generated code,
   runtime dependencies, instrumentation, and the merged APK.
3. Preserve application-startup foreground staging recovery without dependency
   injection or a custom WorkManager configuration.
4. Preserve `MonotonicClock` as a small dependency-free runtime seam for
   `SScriptRunner` and its tests.
5. Remove obsolete localized strings, backup exclusions, transition documents,
   and tests that describe state which no longer exists.
6. Strengthen source and packaged-manifest contracts so a later change cannot
   silently reintroduce the retired runtime.
7. Revalidate the foreground importer, transactional installer, corpus, native
   packaging, Activity lifecycle, and screenshots at the exact final commit.

## Non-goals

- Do not migrate, cancel, decode, quarantine, or delete queue/WorkManager state
  from unreleased developer installations. Path A intentionally adds no
  compatibility code.
- Do not merge or redesign the corpus frameworks in PRs #394 or #395. They may
  be used as external validation harnesses.
- Do not change foreground import states, messages, picker MIME policy, size
  limits, install validation, publication semantics, or ghost selection.
- Do not consolidate retained installer boundaries merely because the legacy
  duplicate is deleted.
- Do not remove the general durable-workflow review checklist; foreground copy
  and installation still need its ownership and interruption discipline.
- Do not change the API 31 minimum, API 37 compile/target SDK, native engine
  set, or physical ARM64 deferral approved for this phase.

## Alternatives considered

### 1. Atomic runtime and dependency deletion — selected

Delete the mutually unreachable runtime, its dedicated tests, build plugins,
dependencies, manifest merge controls, test runner integration, resources, and
active documentation together. Every commit within the branch must compile or
be an explicitly recorded TDD red/green step, and the PR lands only after the
complete boundary is absent.

This produces the desired architecture directly and lets source, dependency,
and APK assertions prove the same fact.

### 2. Delete Kotlin first, dependencies later — rejected

This would make the first PR smaller, but `CatTailApplication`, the Hilt test
runner, KSP tasks, WorkManager initializer suppression, dependency-contributed
permissions, services, and receivers would remain. The intermediate APK would
be behaviorally inert yet structurally contrary to #384, and both PRs would
need nearly identical build, manifest, device, corpus, and review gates.

### 3. Remove dependencies first and retain adapters or stubs — rejected

The workers, scheduler, repository, shared supervisor, Hilt application, and
instrumentation runner directly depend on the removed libraries. Keeping them
would require replacement abstractions or stubs for a system whose required
end state is deletion, increasing code and migration risk without product
value.

## Retained architecture

### Application startup

`CatTailApplication` remains the manifest application class and extends only
`android.app.Application`. Its `onCreate` calls
`ForegroundNarImportCoordinator.get(this)`. It does not implement
`Configuration.Provider`, inject a worker factory, or initialize any durable
supervisor.

Creating the foreground coordinator starts its existing process-owned recovery
on bounded IO. The Activity obtains the same singleton. No new startup service,
receiver, provider, scheduler, or persisted record is introduced.

### Foreground archive flow

The #398 flow is retained without architectural changes:

```text
Nanidroid Activity
  -> OpenDocument picker ownership token
  -> ForegroundNarImportCoordinator single-flight state
  -> AndroidForegroundNarImportBackend
  -> NarContentUriImport bounded owned copy
  -> NarTransactionalInstaller validation and atomic publication
  -> OwnedStagingRecovery exact cleanup
  -> GhostMgr.refreshGhost after readiness
```

No deleted class sits on this path. The retained archive types include
`NarStagedSource`, `NarArchiveInventoryValidator`, `NarZipCentralPreflight`,
`NarDescriptorParser`, `NarInstallPlanValidator`,
`NarGhostDiscoverabilityValidator`, `NarVerifiedInstallSession`, and
`NarTransactionalInstaller`.

### Runtime clock seam

Move the `MonotonicClock` functional interface out of
`di/PlatformClockModule.kt` into
`runtime/MonotonicClock.kt` with package
`com.cattailsw.nanidroid.runtime`. Update `SScriptRunner` and retained tests to
import that type. Keep the existing default based on
`SystemClock.elapsedRealtime()` and all injection-by-constructor test seams.

Delete `PlatformClockModule`; no replacement module or service locator is
created.

## Deletion boundary

### Production Kotlin deleted

Delete the complete generic durable package:

- `durable/DurableOperation.kt`
- `durable/DurableOperationAttentionCoordinator.kt`
- `durable/DurableOperationAttentionNotification.kt`
- `durable/DurableOperationStore.kt`
- `durable/DurableOperationSupervisor.kt`
- `durable/SharedDurableOperationSupervisor.kt`
- `durable/SharedPreferencesDurableOperationStore.kt`

Delete the dead durable Compose hosts:

- `compose/durable/DurableStoreRecoveryPrompt.kt`
- `compose/durable/StalledOperationPrompt.kt`

Delete the archive queue, remote acquisition, worker, and duplicate staging
cluster:

- `install/DownloadManagerProgressObserver.kt`
- `install/InstallNarWorker.kt`
- `install/NarDownload.kt`
- `install/NarDownloadReceiver.kt`
- `install/NarDownloadRecoveryReceiver.kt`
- `install/NarDownloadRepository.kt`
- `install/NarDownloadStore.kt`
- `install/NarInstallProgressReporter.kt`
- `install/NarLocalArchiveStager.kt`
- `install/StageLocalNarWorker.kt`

`NarLiveGrantHandoff` and `AndroidNarInstallWorkScheduler` are defined inside
the worker files and disappear with them.

### Dedicated tests deleted

Delete tests whose entire subject is the removed system:

- `src/test/java/com/cattailsw/nanidroid/DurableBackupRulesTest.kt`
- all four tests under `src/test/java/com/cattailsw/nanidroid/durable/`
- `NarDownloadRepositoryTest.kt`
- `NarDownloadStoreTest.kt`
- `NarInstallProgressReporterTest.kt`
- `NarLocalArchiveStagerTest.kt`
- `DependencyInjectionSmokeTest.kt`
- `DurableOperationAttentionInstrumentationTest.kt`
- `InstallNarWorkerCancellationTest.kt`

Do not delete or weaken retained foreground coordinator/backend/recovery,
transactional installer, archive validation, lifecycle, Compose presentation,
screenshot, or corpus tests.

### Resource and active-document deletion

- Remove every `durable_*` string from English, Japanese, and Traditional
  Chinese resources.
- Delete `backup_rules.xml` and `data_extraction_rules.xml`, whose only entries
  exclude the retired queue and durable preference files, and remove their
  application manifest attributes.
- Delete `docs/modernization/durable-operation-transition-table.md`, which is
  the active transition table for the removed system.
- Update `docs/testing.md` to describe the standard AndroidJUnitRunner and the
  dependency-free manifest contract.

Keep the phase-one shipped-state audit ledger, verifier, tests, and historical
plans/specs. They are evidence for why Path A was allowed and inspect historical
Git state; they are not active runtime instructions. Keep
`docs/modernization/durable-workflow-review-checklist.md` because it still
applies to foreground copies and transactional installation.

## Build and instrumentation changes

### Gradle and catalog

Remove from `build.gradle.kts`:

- the Hilt and KSP plugins;
- WorkManager runtime and test dependencies;
- AndroidX Hilt Work and compiler dependencies;
- Hilt Android, compiler, and instrumentation dependencies;
- all `ksp(...)` and `kspAndroidTest(...)` configurations.

Remove the corresponding versions, libraries, and plugin aliases from
`gradle/libs.versions.toml`. KSP has no remaining processor and must disappear
entirely rather than remain as unused build configuration.

The `javax.inject.Inject` used by the Gradle task constructor in
`build.gradle.kts` is Gradle build-script injection and is unrelated to
application Hilt. Preserve it unless the Android Gradle Plugin requires a
different constructor form.

### Application and Activity

- Remove `@HiltAndroidApp`, `Configuration.Provider`, `HiltWorkerFactory`, and
  injected fields from `CatTailApplication` while retaining foreground recovery
  initialization.
- Remove `@AndroidEntryPoint` and its import from `Nanidroid`.
- Keep the Activity lifecycle, picker registration, action guard, import state,
  GhostMgr readiness barrier, and presentation wiring unchanged.

### Instrumentation runner

Set `testInstrumentationRunner` directly to
`androidx.test.runner.AndroidJUnitRunner` and delete
`NanidroidTestRunner.kt`. Remove `@HiltAndroidTest`, `HiltAndroidRule`, and their
imports from `NanidroidLifecycleInstrumentationTest`; do not otherwise change
its six foreground lifecycle proofs.

Instrumentation now launches the real plain `CatTailApplication`. Tests that
replace the process singleton must retain their existing `try/finally` reset
discipline so application initialization cannot leak state between methods.

## Manifest contract

### Source manifest

After deletion, the source manifest contains:

- the plain `CatTailApplication` application class;
- label, icon, and any unrelated application settings;
- exactly one exported `singleTop` launcher Activity and its MAIN/LAUNCHER
  filter.

It contains no permission declaration or removal tombstone, WorkManager
initializer suppression, AndroidX Startup provider override, archive/durable
receiver, service, provider, or NAR/ZIP `VIEW` filter. Remove the `tools`
namespace when no source attribute uses it.

### Merged manifest and APK

The packaged manifest must contain no component whose class name begins with
`androidx.work.`, no `androidx.work.WorkManagerInitializer` metadata, and no
Nanidroid legacy archive/durable component.

It must not request:

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `RECEIVE_BOOT_COMPLETED`
- `WAKE_LOCK`
- `FOREGROUND_SERVICE`
- `POST_NOTIFICATIONS`
- broad external-storage permissions

Dependency-owned Profile Installer components may remain. Their presence does
not authorize archive work and must not be confused with WorkManager residue.

## Compatibility and data disposition

Path A is authoritative. The change does not read or mutate:

- `shared_prefs/nar-download-queue.xml`
- `shared_prefs/durable_operations_v1.xml`
- WorkManager databases, preferences, jobs, or request UUIDs
- DownloadManager rows or retained URI grants
- old durable notification channels or active-notification identities

No signed or distributed installation can contain those records. Adding
cleanup code would be a compatibility feature for unreleased developer state,
would require preserving identities that the design removes, and would expand
the app's authority over external resources. Stale files on a developer device
are inert and may be cleared with ordinary app-data reset.

Removing the old backup exclusions is safe under the same decision. No new
runtime writes the excluded files, and no release backup requires migration.

## Security and failure behavior

The deletion must not relax any retained archive validation or cleanup scope.
In particular:

- no deleted queue/stager helper replaces `NarContentUriImport`;
- no broad directory delete replaces exact candidate/transaction cleanup;
- no old file URI, URL, receiver, worker, or package-manager ingress survives;
- no dependency removal changes the foreground coordinator's single-flight
  token checks or ActivityResult ownership;
- no post-publication cleanup error changes an installed primary outcome into
  a new install attempt;
- no manifest merge artifact reintroduces network, boot, notification, or
  foreground-service authority.

Build or device failure caused by removing generated Hilt/WorkManager artifacts
is a blocker. The fix must remove the remaining dependency rather than restore
the retired architecture, unless retained product code is proven to need it.

## Static contracts

Extend the foreground/source and generated-artifact tests to assert all of the
following:

- every production and dedicated-test path listed for deletion is absent;
- source contains no imports, annotations, calls, or strings for WorkManager,
  Hilt, KSP, archive queue, DownloadManager, durable operation, dead durable
  Compose prompts, or the custom test runner;
- `CatTailApplication` is plain and still calls
  `ForegroundNarImportCoordinator.get(this)`;
- `Nanidroid` still uses `OpenDocument`, the foreground coordinator, and no
  legacy ingress;
- the catalog and build script contain none of the removed aliases,
  configurations, or plugins;
- the source manifest matches the reduced launcher-only contract;
- the merged manifest and APK satisfy the component and permission contract
  above;
- the foreground importer and transactional installer classes remain present.

Historical Path A audit tests continue to run against Git history. Do not
rewrite their historical resource inventory into a claim about current source.

## Validation plan

Validation is against the exact final implementation commit, not an earlier
production-equivalent commit except where a later commit changes evidence only
and that distinction is documented.

### Local and generated gates

- Focused retained foreground/import/installer JVM suites.
- Full `testDebugUnitTest`, `jacocoTestReport`, and `assembleDebug`.
- Forced uncached JVM run once to prove deleted generated output is not masking
  a source dependency.
- `lint`, with diagnostics compared to merge base `948ba5f9`; no new error or
  warning identity.
- Complete Python contract discovery, with inherited baseline failures
  separated from branch regressions.
- Foreground/dependency/manifest/Path A focused contract subset fully green.
- `validateDebugScreenshotTest` with exactly 31 cases; no golden changes are
  expected. Inspect any unexpected delta at original resolution.
- `git diff --check`, clean tracked status, and repository hygiene check with
  inherited baseline debt reported separately.

### Dependency and packaging proof

- `dependencyInsight` or the resolved runtime classpath shows no WorkManager,
  Hilt, AndroidX Hilt, Dagger, or KSP runtime/build processor owned by the app.
- APK/source inventory contains no removed class name or `androidx.work`
  component.
- Record APK size before and after deletion as evidence, without making a size
  threshold a correctness gate.
- Both `arm64-v8a` and `x86_64` still package Kawari, Satori, SSU, and YAYA;
  ARM64 retained libraries remain ELF64 AArch64 and NARFS remains absent.

### Device proof

On an API 37 emulator or device:

- launch the app using the standard AndroidJUnitRunner;
- run the full retained `NanidroidLifecycleInstrumentationTest` class and prove
  all six methods pass without Hilt;
- run retained foreground presentation and transactional installer device
  tests;
- run the broader connected suite and classify only reproducible merge-base
  failures as baseline debt;
- repeat the production document-picker valid/invalid journey when the APK or
  manifest dependency change could affect launch, provider routing, or process
  initialization;
- confirm no archive notification, incoming NAR/ZIP `VIEW` resolution,
  automatic ghost switch, or duplicate import appears.

Physical ARM64 execution remains deferred by the approved phase requirement.
Both-ABI APK inventory and ARM64 ELF verification remain mandatory.

### Corpus proof

Run the fixed 23-NAR harness from PR #395 or its merged successor against the
exact deletion-branch APK. Require 23/23 rows, all 143 sentinels, zero archive
failures, verified cleanup, and no timeout or abort. The corpus harness remains
external to this PR; do not copy or merge its framework into the deletion
branch merely to run it.

## Review and landing

Before publishing:

1. Coordinator full-diff review of source reachability, Gradle/catalog removal,
   manifest output, retained clock semantics, foreground recovery, tests, and
   documentation.
2. Fresh independent Android reviewer focused on application startup,
   instrumentation runner behavior, manifest merge output, API 31 compatibility,
   and lifecycle/device evidence.
3. Fresh independent adversarial reviewer focused on complete legacy
   reachability removal, security authority, transactional-installer
   preservation, staging ownership, dependency residue, and regression risk.
4. Reconcile every accepted finding, rerun the owning gates, and re-review the
   corrected exact head.
5. Push a focused PR linked to #384, wait for every GitHub check, request GitHub
   Codex review, and inspect top-level comments plus every unresolved inline
   thread.
6. Merge only after exact-head local, multi-agent, GitHub CI, and GitHub Codex
   gates are clean. Fetch and verify the merged master before selecting another
   simplification slice.

## Interaction with corpus PRs

PR #394 touches only corpus metadata resolver plans, documentation, scripts,
and fixtures. PR #395 touches corpus plans/docs/scripts, one corpus
instrumentation test, and one tokenizer test. Neither overlaps the production
deletion boundary. `docs/testing.md` may need a small textual reconciliation if
#395 lands first; that is not a product or architecture conflict.

## Stop conditions

Stop and redesign rather than restoring legacy pieces if any of these occurs:

- retained production code outside the identified cluster still requires a
  deleted durable/queue/worker type;
- removing WorkManager/Hilt changes foreground coordinator startup or causes
  Activity recreation to duplicate an import;
- the standard test runner cannot launch the real application without altering
  production lifecycle behavior;
- generated or packaged output still contains WorkManager code, components, or
  permissions after the declared dependencies are gone;
- the foreground importer, transactional validator, fixed corpus, or retained
  native engines regress;
- deletion makes cleanup broader, weakens canonical/no-follow ownership, or
  introduces any compatibility action against unreleased external state;
- PR #394 or #395 introduces a genuine production dependency on the cluster
  before this work lands.
