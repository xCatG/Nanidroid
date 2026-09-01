# GhostRuntime Native Session Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Nanidroid's transitional coordinator and Activity-owned ghost handoff with one application-owned, generation-fenced `GhostRuntime` OS thread, while deleting native authority from `Ghost` and preserving every essential user workflow.

**Architecture:** Filesystem-heavy ghost preparation runs in a failure-isolated application coroutine scope and publishes immutable prepared data. One named single-thread executor exclusively constructs, loads, queries, requests, and unloads SHIORI adapters; `SScriptRunner` receives only data handles and generation-tagged responses, and Activity recreation joins runtime-owned startup, attachment, and switch operations. Support work lands first, then every production native call moves in one cutover before the old coordinator, reservations, static factory, metadata subclass, and dead APIs are deleted.

**Tech Stack:** Kotlin, Android API 37/minSdk 31, Kotlin coroutines, Java single-thread executors, JNI/C++, JUnit 4, MockK, AndroidX Test/ActivityScenario, Gradle wrapper, Python `unittest`

**Spec:** `docs/superpowers/specs/2026-08-27-ghost-runtime-native-session-authority-design.md`

## Global Constraints

- Exactly one application-owned OS thread executes every production SHIORI load, charset lookup, request, and unload.
- `GhostRuntime` is the only production adapter/factory/native-session authority; native commands never call runner, Activity, Compose, renderer, observer, or callback code.
- Filesystem parsing and mutable surface construction never run on the native command thread.
- Every native command and returned completion carries the exact expected generation; stale work is rejected before JNI invocation or runner admission.
- Same-root startup joins one application-owned producer; Activity cancellation detaches a waiter and never cancels, unloads, or abandons that producer.
- A proven-empty failure permits retry; uncertain native teardown poisons the process and forbids replacement load.
- Do not retain `GhostSessionCoordinator`, either reservation type, `ShioriFactory`, `InfoOnlyGhost`, direct SHIORI ownership in `Ghost`, or renamed compatibility wrappers after cutover.
- Preserve Satori, YAYA, Kawari, NanidroidShiori, unsupported-engine, startup, switching, dialogue, timers, surfaces, collisions, balloons, foreground NAR import, and authored-link behavior.
- Do not add Hilt, WorkManager, a second authority layer, a second Gradle module, or a compatibility shim.
- Use the Gradle wrapper on Windows; compile/target API 37 and minSdk 31 remain unchanged.
- Physical arm64 runtime is explicitly deferrable when no qualifying device is attached; build/ELF/package evidence is still required and x86_64 results must not be described as arm64 coverage.
- PR #395's fixed corpus assertions are preserved through a semantic rebase; its test fixture must use an isolated runtime and must not retain an adapter.

## File Map

- Modify `src/main/kotlin/com/cattailsw/nanidroid/shiori/Shiori.kt`: typed explicit adapter lifecycle and deletion of `terminate()`.
- Modify `src/main/kotlin/com/cattailsw/nanidroid/shiori/{SatoriShiori,YayaShiori,Kawari,EchoShiori,NanidroidShiori,NotSupportedShiori}.kt`: unloaded construction, typed load/request/unload, and loaded-state rejection.
- Modify `jni/satori/satori_jni.cpp`, `jni/yaya/yaya_jni.cpp`, and `jni/kawari8/kawari_jni.cpp`: observable lifecycle status, no implicit owner replacement, idempotent unload, and cleared handles.
- Create `src/main/kotlin/com/cattailsw/nanidroid/GhostPreparation.kt`: `InstalledGhostMetadata`, `GhostEngine`, `PreparedGhost`, `GhostPreparer`, and installed catalog scan.
- Modify `src/main/kotlin/com/cattailsw/nanidroid/SurfaceManager.kt` and `SurfaceReader.kt`: builder-only mutation and frozen publication.
- Rewrite `src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt`: preparation scope, operation IDs, native executor, private session, generation fencing, attachment, switching, poison, and test close.
- Rewrite `src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt`: immutable display/prepared data only.
- Modify `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`: runtime command port, tagged admission, idempotent attachment admission, and switch playback completion.
- Modify `src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt`: immutable catalog/selection and runtime startup only.
- Modify `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`: join runtime startup/attachment/switch state and remove Activity-owned reservations/target continuation.
- Modify `src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt`: construct the production runtime with application dependencies.
- Delete `src/main/kotlin/com/cattailsw/nanidroid/GhostSessionCoordinator.kt`, `InfoOnlyGhost.kt`, `DirList.kt`, and `ShioriFactory.kt` after replacement tests pass.
- Rewrite `src/test/java/com/cattailsw/nanidroid/GhostRuntimeTest.kt`; create `GhostPreparationTest.kt`, `GhostRuntimeNativeThreadTest.kt`, `GhostRuntimeAttachmentTest.kt`, and `GhostRuntimeSwitchTest.kt`.
- Rewrite the runner/dialogue/switch tests named in Tasks 4–5 around closeable fake runtimes instead of `Ghost` subclasses that own fake SHIORI.
- Create `src/androidTest/java/com/cattailsw/nanidroid/ShioriLifecycleInstrumentationTest.kt` and `CrossEngineRuntimeInstrumentationTest.kt`; extend `NanidroidLifecycleInstrumentationTest.kt`.
- Create `scripts/run-cross-engine-runtime-audit.ps1`: deterministically select
  one archive for each native engine from supplied corpus roots, push run-owned
  copies, and invoke the exact Satori → YAYA → Kawari → Satori transition in
  one test process.
- Modify `src/androidTest/java/com/cattailsw/nanidroid/corpus/NarCorpusRuntimeTest.kt`: use one isolated runtime per corpus row or a serial closeable fixture.
- Replace legacy-presence assertions in `tools/test_native_shiori_contract.py`, `tools/test_kotlin_shiori_factory_contract.py`, `tools/test_kotlin_ghost_discovery_contract.py`, and `tools/test_ghost_runtime_composition_root.py` with final lifecycle/absence contracts.
- Modify `docs/testing.md` and `docs/testing/nar-corpus.md`: exact focused, corpus, connected, ABI, and deferral commands.

---

### Task 1: Make Native Adapter Lifecycle Observable

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/shiori/Shiori.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/shiori/SatoriShiori.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/shiori/YayaShiori.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/shiori/Kawari.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/shiori/EchoShiori.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/shiori/NanidroidShiori.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/shiori/NotSupportedShiori.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/ShioriFactory.kt`
- Modify: `jni/satori/satori_jni.cpp`
- Modify: `jni/yaya/yaya_jni.cpp`
- Modify: `jni/kawari8/kawari_jni.cpp`
- Modify: `tools/test_native_shiori_contract.py`
- Modify: every JVM/instrumentation fake that implements `Shiori` or overrides
  `unloadShiori`/`terminate`

**Interfaces:**
- Consumes: existing engine constructors and SHIORI request encoding.
- Produces: `Shiori.load(): ShioriLoadResult`, unchanged byte-preserving
  `request(String): String`, and `unloadShiori(): ShioriUnloadResult`; adapters
  are constructed unloaded and never silently replace an owner. Runtime request
  failures become typed at the command boundary in Task 3 rather than being
  fabricated by adapters.

- [ ] **Step 1: Add the typed lifecycle contract and make Kotlin compilation fail at every legacy implementation**

Replace `Shiori.kt` with:

```kotlin
package com.cattailsw.nanidroid.shiori

sealed interface ShioriLoadResult {
    data object Loaded : ShioriLoadResult
    data class Failed(
        val cause: Throwable,
        val state: LoadFailureState,
    ) : ShioriLoadResult
}

enum class LoadFailureState {
    ProvenEmpty,
    OwnerAlreadyPresent,
    CleanupRequired,
}

sealed interface ShioriUnloadResult {
    data object Unloaded : ShioriUnloadResult
    data class Failed(val cause: Throwable, val ownershipCertain: Boolean) : ShioriUnloadResult
}

class ShioriRequestException(
    message: String,
    cause: Throwable? = null,
    val ownershipCertain: Boolean,
) : IllegalStateException(message, cause)

interface Shiori {
    fun getModuleName(): String
    fun load(): ShioriLoadResult
    fun request(request: String): String
    fun unloadShiori(): ShioriUnloadResult
}
```

Run:

```powershell
.\gradlew.bat compileDebugKotlin
```

Expected: FAIL on the six implementations and typed lifecycle callers because
the old constructor-side load and `Unit` unload methods no longer satisfy the
interface.

- [ ] **Step 2: Make final native source contracts fail before changing JNI**

Extend `tools/test_native_shiori_contract.py` to require integer load-status JNI
signatures, a distinct owner-present status, no unload before a new load attempt,
unloaded request/charset rejection, Kawari Boolean-dispose checking, and `h = 0`
after successful unload. A zero/proven-empty result must be attainable for every
real engine: Satori load fails when no usable dictionary was loaded, and YAYA
load fails when the constructed VM reports suppression. Do not infer success
merely because either legacy POSIX entry point allocated an engine object. Run
the module and expect FAIL on each old bridge. Real adapter execution is
deliberately deferred until Task 5 can exercise adapters through a closeable
runtime without exposing them to instrumentation code.

- [ ] **Step 3: Implement explicit Kotlin adapter state without changing request bytes**

For each native adapter, store constructor arguments, `loaded = false`,
`loadCleanupRequired = false`, and constants `NATIVE_LOADED = 1`,
`NATIVE_FAILED_EMPTY = 0`, `NATIVE_OWNER_PRESENT = -1`, and
`NATIVE_CLEANUP_REQUIRED = -2`. Use this exact state pattern:

```kotlin
override fun load(): ShioriLoadResult {
    if (loaded) {
        return ShioriLoadResult.Failed(
            IllegalStateException("${getModuleName()} is already loaded"),
            LoadFailureState.OwnerAlreadyPresent,
        )
    }
    val status = try {
        nativeLoad(path, cacheDirectory)
    } catch (failure: Throwable) {
        loadCleanupRequired = true
        return ShioriLoadResult.Failed(failure, LoadFailureState.CleanupRequired)
    }
    return when (status) {
            NATIVE_LOADED -> {
                loaded = true
                ShioriLoadResult.Loaded
            }
            NATIVE_FAILED_EMPTY -> ShioriLoadResult.Failed(
                IllegalStateException("${getModuleName()} could not load this ghost"),
                LoadFailureState.ProvenEmpty,
            )
            NATIVE_OWNER_PRESENT -> ShioriLoadResult.Failed(
                IllegalStateException("${getModuleName()} already has a native owner"),
                LoadFailureState.OwnerAlreadyPresent,
            )
            NATIVE_CLEANUP_REQUIRED -> {
                loadCleanupRequired = true
                ShioriLoadResult.Failed(
                    IllegalStateException("${getModuleName()} load cleanup is required"),
                    LoadFailureState.CleanupRequired,
                )
            }
        else -> {
            loadCleanupRequired = true
            ShioriLoadResult.Failed(
                IllegalStateException("Unknown ${getModuleName()} native load status: $status"),
                LoadFailureState.CleanupRequired,
            )
        }
    }
}

override fun request(request: String): String {
    if (!loaded) {
        throw ShioriRequestException(
            "${getModuleName()} is not loaded",
            ownershipCertain = true,
        )
    }
    return try {
        decodeNativeResponse(request)
    } catch (failure: ShioriRequestException) {
        throw failure
    } catch (failure: LinkageError) {
        throw ShioriRequestException(
            "${getModuleName()} request linkage failed",
            failure,
            ownershipCertain = false,
        )
    } catch (failure: Exception) {
        throw ShioriRequestException(
            "${getModuleName()} request failed",
            failure,
            ownershipCertain = true,
        )
    }
}

override fun unloadShiori(): ShioriUnloadResult {
    return runCatching { check(nativeUnload()) }.fold(
        {
            loaded = false
            loadCleanupRequired = false
            ShioriUnloadResult.Unloaded
        },
        { ShioriUnloadResult.Failed(it, ownershipCertain = false) },
    )
}
```

Native adapters return idempotent success without entering JNI when neither
`loaded` nor `loadCleanupRequired` is set. This prevents an adapter that failed
with `OwnerAlreadyPresent`, or was never loaded, from unloading another
adapter's process-global native owner. Queue-confined lifecycle probes still
verify JNI duplicate-unload behavior only after that adapter acquired the
owner. A thrown native load is never
classified proven-empty: the runtime makes exactly one cleanup attempt and
poisons if ownership cannot be cleared.

Native request wrappers translate ordinary protocol/engine errors to
`ShioriRequestException(..., ownershipCertain = true)` and linkage or native
state-corruption evidence to `ownershipCertain = false`; they never return a
fabricated response. `EchoShiori`, `NanidroidShiori`, and
`NotSupportedShiori` use a Kotlin-only loaded
flag, return `Loaded`/`Unloaded`, and reject requests while unloaded. Update the
transitional `Ghost` construction path to call `load()` immediately after the
existing factory selects an adapter and throw the typed failure cause; update
its unload path to throw on a typed failure. This preserves current call-thread
behavior until Task 4. Mechanically update every fake `Shiori` implementation
to return `Loaded`/`Unloaded` while preserving its recorded request strings.
Delete `terminate()` and every override now; no production or retained test
caller may remain.

- [ ] **Step 4: Change all three JNI bridges atomically**

Make Satori and YAYA `nativeLoad` return `jint` and update its registered
signature from `V` to `I`: `1` means loaded, `0` means failed/proven empty, and
`-1` means an owner was already present, and `-2` means this invocation may own
state and requires cleanup. Each bridge acquires its global mutex and checks the
loaded flag immediately after non-null argument validation, before
`GetStringUTFChars`, allocation, logging, or other fallible work; it retains the
same lock through path conversion and the complete load transition. After that
check the bridge returns statuses rather than throwing: pre-load conversion or
allocation failure is `0`, underlying failure plus successful cleanup is `0`,
and underlying failure plus failed/uncertain cleanup is `-2`.
`nativeUnload` returns `jboolean` with signature `Z`. Return successful
status only when underlying load/unload reports success. YAYA's charset function throws when
`gYayaLoaded` is false instead of returning UTF-8.

Change Kawari load to `jint` and unload to `jboolean`. Its load starts with:

```cpp
if (h != 0) {
  return -1;
}
h = TKawariShioriFactory::GetFactory().CreateInstance(
    make_utf8_string_from_jstring(env, path));
return h != 0 ? 1 : 0;
```

Add one process-global Kawari mutex and hold it across the owner check plus
`CreateInstance`; use that same mutex for request and unload. This makes the
owner-present result atomic even when two isolated test runtimes have distinct
command threads. Replace Kawari's unchecked string helper with a null-safe
conversion performed after the `h != 0` owner check while that mutex remains
held: check `GetStringUTFChars` before dereference, release only a non-null
result, and return `0` without calling `CreateInstance` or mutating `h` when
conversion/allocation fails.

Its request throws `IllegalStateException` when `h == 0`. Its unload is:

```cpp
if (h == 0) return JNI_TRUE;
if (!TKawariShioriFactory::GetFactory().DisposeInstance((int)h)) return JNI_FALSE;
h = 0;
return JNI_TRUE;
```

- [ ] **Step 5: Flip the native source contract to the final rules**

Replace the old Kawari implicit-dispose assertion with checks for `if (h != 0)`,
`return -1`, `h = 0`, and request rejection. Add Satori/YAYA assertions that
owner-present returns without `unload()`, load signatures return `I`, and unload
signatures return `Z`. For Satori and YAYA, assert the loaded-flag check appears
before `GetStringUTFChars`/allocation and that one mutex guard spans both the
check and underlying `load` call.

Run:

```powershell
python -m unittest tools.test_native_shiori_contract
.\gradlew.bat assembleDebug compileDebugAndroidTestKotlin
```

Expected: PASS; JNI registration and Kotlin external signatures compile for both
packaged ABIs. Runtime lifecycle evidence remains a mandatory Task 5 gate.

- [ ] **Step 6: Commit the lifecycle contract**

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/shiori src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt src/main/kotlin/com/cattailsw/nanidroid/ShioriFactory.kt src/test src/androidTest jni/satori/satori_jni.cpp jni/yaya/yaya_jni.cpp jni/kawari8/kawari_jni.cpp tools/test_native_shiori_contract.py
git commit -m "Type SHIORI native lifecycle results"
```

---

### Task 2: Publish Immutable Ghost Preparation and Catalog Data

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/GhostPreparation.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SurfaceManager.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SurfaceReader.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt`
- Delete: `src/main/kotlin/com/cattailsw/nanidroid/InfoOnlyGhost.kt`
- Delete: `src/main/kotlin/com/cattailsw/nanidroid/DirList.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/GhostPreparationTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/NanidroidGhostStartupTest.kt`
- Modify: `tools/test_kotlin_ghost_discovery_contract.py`

**Interfaces:**
- Consumes: `DescReader`, `SurfaceReader`, `SurfaceTransparencyPolicy`, application context, and canonical installed roots.
- Produces: `InstalledGhostMetadata`, `GhostEngine`, `PreparedGhost`, `GhostPreparer.prepare(operationId, ghostId, canonicalRoot)`, and `InstalledGhostCatalog.scan(context)` with no adapter/JNI work.

- [ ] **Step 1: Write red preparation tests**

Create tests that build a temporary ghost tree and assert:

```kotlin
val prepared = GhostPreparer(null).prepare(41L, "fixture", root.canonicalFile)
assertEquals(41L, prepared.operationId)
assertEquals(root.canonicalFile, prepared.canonicalRoot)
assertEquals("fixture", prepared.id)
assertEquals("Fixture", prepared.name)
assertEquals(GhostEngine.Yaya, prepared.engine)
assertTrue(SurfaceCatalog::class.java.methods.none {
    it.name.startsWith("add") || it.name.startsWith("set")
})
assertTrue(prepared.surfaces.definitionsForTesting().values.all {
    it::class == SurfaceDefinition::class
})
assertSame(
    prepared.surfaces.definition("0"),
    prepared.surfaces.sakuraDefinition("missing"),
)
assertSame(
    prepared.surfaces.definition("10"),
    prepared.surfaces.keroDefinition("missing"),
)
```

Add a polygon fixture, attempt to cast/mutate its original source point list,
and prove the published polygon retains its copied points and rejects mutation.

Add `preparationDoesNotConstructAnAdapter` by injecting a factory counter only into `GhostRuntime` later; at this boundary assert `GhostPreparation.kt` has no import or text reference to `Shiori`, `ShioriFactory`, or `native`.

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.GhostPreparationTest"
```

Expected: Kotlin compilation FAIL because the preparation types do not exist.

- [ ] **Step 2: Implement the exact data boundary**

Create these declarations in `GhostPreparation.kt`:

```kotlin
internal data class InstalledGhostMetadata(
    val id: String,
    val canonicalRoot: File,
    val name: String?,
    val sakuraName: String?,
    val readme: File,
)

internal enum class GhostEngine { Satori, Yaya, Kawari, Nanidroid, Unsupported }

internal class SurfaceCatalog private constructor(
    private val definitions: Map<String, SurfaceDefinition>,
) {
    val keys: Set<String> get() = definitions.keys.toSet()
    fun definition(id: String): SurfaceDefinition? = definitions[id]
    fun sakuraDefinition(id: String): SurfaceDefinition? =
        definitions[id] ?: definitions["0"]
    fun keroDefinition(id: String): SurfaceDefinition? =
        definitions[id] ?: definitions["10"]
    internal fun definitionsForTesting(): Map<String, SurfaceDefinition> = definitions

    companion object {
        fun freeze(source: Map<String, SurfaceDefinition>): SurfaceCatalog =
            SurfaceCatalog(java.util.Collections.unmodifiableMap(
                source.mapValues { (_, value) -> value.deepFrozenCopy() }.toMap(),
            ))
    }
}

private fun SurfaceDefinition.deepFrozenCopy(): SurfaceDefinition = copy(
    collisions = java.util.Collections.unmodifiableList(collisions.map { collision ->
        val frozenShape = when (val shape = collision.shape) {
            is com.cattailsw.nanidroid.surface.CollisionShape.Polygon ->
                com.cattailsw.nanidroid.surface.CollisionShape.Polygon(
                    java.util.Collections.unmodifiableList(shape.points.toList()),
                )
            else -> shape
        }
        collision.copy(shape = frozenShape)
    }),
    animations = java.util.Collections.unmodifiableList(animations.map { animation ->
        animation.copy(
            frames = java.util.Collections.unmodifiableList(animation.frames.toList()),
            alternativeAnimationIds = java.util.Collections.unmodifiableList(
                animation.alternativeAnimationIds.toList(),
            ),
        )
    }),
    elements = java.util.Collections.unmodifiableList(elements.toList()),
)

internal data class PreparedGhost(
    val operationId: Long,
    val id: String,
    val canonicalRoot: File,
    val name: String?,
    val shellName: String?,
    val crafterName: String?,
    val sakuraName: String?,
    val keroName: String?,
    val surfaces: SurfaceCatalog,
    val ghostDescriptor: Map<String, String>,
    val shellDescriptor: Map<String, String>?,
    val engine: GhostEngine,
    val nanidroidContent: Map<String, String>,
)
```

`GhostPreparer.prepare` canonicalizes and checks both supplied ID and root name,
parses descriptors, builds surfaces, converts them to frozen definitions,
pre-reads Nanidroid content, and returns only copied maps/lists. Engine selection
is exact: `Nanidroid`, `satori.dll`, `yaya.dll`, `shiori.dll` plus
`kawarirc.kis`, otherwise unsupported.

- [ ] **Step 3: Publish definitions instead of mutable ShellSurface builders**

Change `SurfaceManager.addSurface` and `addParsedSurface` to `internal`; it
remains a preparation-local builder and legacy transitional renderer input only.
After parsing, convert every entry with `toSurfaceDefinition()`. Implement
`SurfaceDefinition.deepFrozenCopy()` to copy and wrap collisions, animations,
frames, elements, and alternative-ID lists with unmodifiable views, then publish
only `SurfaceCatalog.freeze(definitions)` in `PreparedGhost`. No `ShellSurface`,
manager map, animation table, Drawable cache, or mutable list escapes into
prepared/runtime state. Task 4 changes `ComposeGhostStageHost` from manager
lookup plus `toSurfaceDefinition()` to direct catalog definition lookup.

- [ ] **Step 4: Replace metadata inheritance with the immutable installed catalog**

Implement:

```kotlin
internal object InstalledGhostCatalog {
    fun scan(context: Context): List<InstalledGhostMetadata> =
        File(context.getExternalFilesDir(null), "ghost").listFiles().orEmpty()
            .filter(File::isDirectory)
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .mapNotNull(::readMetadata)
}
```

Change `GhostMgr.ghosts` to `List<InstalledGhostMetadata>`, use exact metadata
properties for names/paths/Readme, and retain its transitional `createGhost`
method by passing the selected metadata root to the old active `Ghost`
constructor. Delete `InfoOnlyGhost.kt` and `DirList.kt`. Flip
`tools/test_kotlin_ghost_discovery_contract.py` to assert both files are absent,
`GhostMgr` calls `InstalledGhostCatalog.scan`, and catalog scanning contains no
adapter/native reference.

- [ ] **Step 5: Run preparation, surface, and discovery suites**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.GhostPreparationTest" --tests "com.cattailsw.nanidroid.NanidroidGhostStartupTest" --tests "com.cattailsw.nanidroid.SurfaceDefinitionCharacterizationTest" --tests "com.cattailsw.nanidroid.surface.SurfaceParserRecoveryTest"
```

Expected: PASS with unchanged surface/collision characterization.

- [ ] **Step 6: Commit immutable preparation support**

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/GhostPreparation.kt src/main/kotlin/com/cattailsw/nanidroid/SurfaceManager.kt src/main/kotlin/com/cattailsw/nanidroid/SurfaceReader.kt src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt src/test/java/com/cattailsw/nanidroid/GhostPreparationTest.kt src/test/java/com/cattailsw/nanidroid/NanidroidGhostStartupTest.kt tools/test_kotlin_ghost_discovery_contract.py
git add -u src/main/kotlin/com/cattailsw/nanidroid/InfoOnlyGhost.kt src/main/kotlin/com/cattailsw/nanidroid/DirList.kt
git commit -m "Prepare immutable ghost runtime data"
```

---

### Task 3: Build the Closeable Single-Flight Runtime Core

**Files:**
- Rewrite: `src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt`
- Rewrite: `src/test/java/com/cattailsw/nanidroid/GhostRuntimeTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/GhostRuntimeNativeThreadTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/GhostRuntimeAttachmentTest.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/GhostRuntimeSwitchTest.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/GhostEventCapabilities.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/GhostEventCapabilitiesTest.kt`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/corpus/NarCorpusRuntimeTest.kt`
- Modify: `docs/testing/nar-corpus.md`

**Interfaces:**
- Consumes: Task 1's typed `Shiori`, Task 2's
  `PreparedGhost`/`GhostPreparer`, an injected data-only attachment-admission
  function for isolated tests, and application context. The existing
  coordinator-backed production `runner` property remains untouched and none of
  the new core commands has a production caller until Task 4.
- Produces: closeable test runtimes; `startOrJoin`, `request`, `attachHost`, `beginSwitch`, `completeSwitchPlayback`, `failSwitchBeforeUnload`, and typed runtime snapshots/results.

- [ ] **Step 1: Replace reservation tests with red single-flight/thread tests**

Use an injected blocking preparer and recording adapter. The core assertion is:

```kotlin
runtime.use {
    val first = async { runtime.startOrJoin("same", root) }
    preparationStarted.await()
    val second = async { runtime.startOrJoin("same", root) }
    releasePreparation.countDown()

    val firstHandle = assertIs<RuntimeResult.Success<GhostHandle>>(first.await()).value
    val secondHandle = assertIs<RuntimeResult.Success<GhostHandle>>(second.await()).value
    assertSame(firstHandle, secondHandle)
    assertEquals(1, prepareCount.get())
    assertEquals(1, loadCount.get())
    assertEquals(setOf(runtime.nativeThreadName), adapterThreads.toSet())
}
```

Add tests for cancelled first/sole waiters, active same-root reuse, shared failure
then retry, stale pre-native preparation drop, generation-stale request rejection,
proven-empty retry without cleanup, owner-already-present fatal rejection without
touching that owner, cleanup-required success/retry, cleanup-required failure
poison, and executor termination in `close()`.

- [ ] **Step 2: Define the exact runtime data/results**

Place these internal values at the top of `GhostRuntime.kt`:

```kotlin
internal data class GhostHandle(
    val prepared: PreparedGhost,
    val pointerCapabilities: PointerEventCapabilities,
    val generation: Long,
)

internal data class TaggedShioriResponse(
    val generation: Long,
    val response: ShioriResponse,
)

internal data class ShioriRequestIntent private constructor(
    val protocolText: String,
) {
    companion object {
        fun event(eventId: String, references: List<String?> = emptyList()) =
            ShioriRequestIntent(formatEventRequest(eventId, references))

        fun raw(
            method: ShioriMethod,
            eventId: String,
            references: List<String?> = emptyList(),
        ) = ShioriRequestIntent(formatRawRequest(method, eventId, references))

        private fun formatEventRequest(eventId: String, references: List<String?>): String =
            buildString {
                append("GET SHIORI/3.0\r\nSender: Nanidroid\r\nID: ")
                append(eventId)
                append("\r\nSecurityLevel: local\r\n")
                appendReferences(references)
                append("\r\n")
            }

        private fun formatRawRequest(
            method: ShioriMethod,
            eventId: String,
            references: List<String?>,
        ): String = buildString {
            append(method.name)
            append(" SHIORI/3.0\r\nSender: Nanidroid\r\nSecurityLevel: local\r\nID: ")
            append(eventId)
            append("\r\n")
            appendReferences(references)
            append("\r\n")
        }

        private fun StringBuilder.appendReferences(references: List<String?>) {
            references.forEachIndexed { index, value ->
                append("Reference").append(index).append(": ").append(value).append("\r\n")
            }
        }
    }
}

internal sealed interface RuntimeFailure {
    data object Busy : RuntimeFailure
    data object StaleGeneration : RuntimeFailure
    data class Replayable(val cause: Throwable) : RuntimeFailure
    data class Fatal(val cause: Throwable) : RuntimeFailure
}

internal sealed interface RuntimeResult<out T> {
    data class Success<T>(val value: T) : RuntimeResult<T>
    data class Failure(val failure: RuntimeFailure) : RuntimeResult<Nothing>
}

internal sealed interface BootOutcome {
    data class Response(val tagged: TaggedShioriResponse) : BootOutcome
    data class BootAttemptFailed(val cause: Throwable) : BootOutcome
}

internal sealed interface AttachmentReceipt {
    data class NewlyAttached(val operationId: Long) : AttachmentReceipt
    data object AlreadyAttached : AttachmentReceipt
}

internal fun interface AttachmentAdmission {
    fun admit(
        operationId: Long,
        handle: GhostHandle,
        outcome: BootOutcome,
    ): RuntimeResult<Unit>
}

internal interface GhostRuntimePersistence {
    fun readLastRunGhostId(): String?
    fun commitLastRunGhostId(ghostId: String)
    fun readActivationCount(ghostId: String): Long
    fun commitActivationCount(ghostId: String, count: Long)
}

internal sealed interface AttachmentReason {
    data object Initial : AttachmentReason
    data class Switched(val outgoingGhostName: String) : AttachmentReason
}

internal data class GhostRuntimeTestHooks(
    val onPreparationStarted: (Long, String, File) -> Unit = { _, _, _ -> },
    val onNativeLoadStarted: (Long, GhostEngine) -> Unit = { _, _ -> },
    val onGenerationPublished: (Long, String) -> Unit = { _, _ -> },
    val onActivationCommitted: (Long) -> Unit = {},
    val onBootAttempted: (Long, String) -> Unit = { _, _ -> },
    val onOutgoingUnloaded: (Long) -> Unit = {},
)

internal data class NativeLifecycleProbeTrace(
    val engine: GhostEngine,
    val commandThreadNames: List<String>,
    val steps: List<String>,
)
```

Expose one internal test constructor with this exact signature while keeping
`GhostRuntime(context)` as the sole production constructor:

```kotlin
internal companion object {
    fun testRuntime(
        context: Context?,
        preparer: GhostPreparer,
        adapterFactory: ((PreparedGhost) -> Shiori)? = null,
        persistence: GhostRuntimePersistence,
        admission: AttachmentAdmission = AttachmentAdmission {
                _, _, _ -> RuntimeResult.Success(Unit)
        },
    ): GhostRuntime
}
```

A null test `adapterFactory` uses the same private engine selection as production;
fake tests supply a factory that captures only shared trace/counters and an
in-memory persistence implementation. Production uses one application-context
`PrefUtil` implementation owned by `GhostRuntime`; Activity/GhostMgr never writes
last-run or activation state after Task 4.

Define this shared JVM-test fixture:

```kotlin
internal class InMemoryGhostRuntimePersistence : GhostRuntimePersistence {
    var lastRunGhostId: String? = null
    val activationCounts = mutableMapOf<String, Long>()
    val lastRunWrites = mutableListOf<String>()
    val activationWrites = mutableListOf<Pair<String, Long>>()

    override fun readLastRunGhostId(): String? = lastRunGhostId
    override fun commitLastRunGhostId(ghostId: String) {
        lastRunGhostId = ghostId
        lastRunWrites += ghostId
    }
    override fun readActivationCount(ghostId: String): Long =
        activationCounts[ghostId] ?: 0L
    override fun commitActivationCount(ghostId: String, count: Long) {
        activationCounts[ghostId] = count
        activationWrites += ghostId to count
    }
}
```

The application runtime also exposes package-internal instrumentation seams:

```kotlin
internal fun installTestHooksForTesting(hooks: GhostRuntimeTestHooks): AutoCloseable
internal fun resetSessionForTesting(): RuntimeResult<Unit>
internal fun probeAdapterLifecycleForTesting(
    prepared: PreparedGhost,
    invalidPrepared: PreparedGhost,
): RuntimeResult<NativeLifecycleProbeTrace>
```

`installTestHooksForTesting` uses an `AtomicReference`, rejects a second active
installation, and its returned close token clears only the identical hook value.
Hooks fire on their owning preparation/application/native worker and never on the
Activity main thread. `resetSessionForTesting` queue-confined-unloads any active
test session, clears pending test operations and runner test queues, and leaves
the process-lived executor/scope open; it exists solely so multiple methods in
`NanidroidLifecycleInstrumentationTest` can start idle without replacing the
`CatTailApplication` runtime. It returns `RuntimeResult.Failure(Fatal(...))`
without JNI work when poisoned.

Use one `CompletableDeferred<RuntimeResult<GhostHandle>>` per in-flight
operation and return the identical `GhostHandle` instance to joiners. Every
runtime command returns `RuntimeResult<T>`; no Kotlin `Result` or exception is
used to encode Busy, Stale, Replayable, or Fatal.
`formatEventRequest` preserves the current `Ghost.doShioriEvent` header order;
`formatRawRequest` preserves current `Ghost.requestRaw` order. Add literal tests
for both complete CRLF-terminated strings, including indexed empty/null reference
slots, before moving callers.

`probeAdapterLifecycleForTesting` is accepted only on a runtime created by the
test constructor. It submits one indivisible command to the native executor,
constructs adapters through the same private production selector, and returns
only immutable step names and thread names. No callback receives an adapter.
Native adapter classes expose package-internal, test-named probe methods only to
`GhostRuntime`: Satori and Kawari invoke their JNI request entry after unload;
YAYA invokes both charset and request after unload. The methods return data-only
rejection records and never expose native handles or adapter instances. A source
contract permits their call sites only inside `GhostRuntime.kt`.

- [ ] **Step 3: Implement application preparation and the native command executor**

Create a `SupervisorJob` plus bounded `Dispatchers.IO` scope for preparation and a named single-thread executor for native commands. Install the in-flight operation under a small state lock before launching preparation. Submit the prepared value only if operation ID/root still match. The command thread privately calls an injected adapter factory in tests and a `when (prepared.engine)` constructor function in production.

The private session is:

```kotlin
private data class Session(
    val prepared: PreparedGhost,
    val adapter: Shiori,
    val handle: GhostHandle,
    var attachment: AttachmentState,
)
```

No adapter is returned from any method or snapshot.

Map `LoadFailureState.ProvenEmpty` to replayable failure. Map
`OwnerAlreadyPresent` to fatal poison without invoking unload because the owner
belongs to another authority. For `CleanupRequired`, invoke exactly one unload
on the same adapter: known success becomes replayable/empty; failure poisons.

Discover pointer/event capabilities inline on this same native command after a
successful load; never enqueue a nested runtime request. Change
`GhostEventCapabilities` optional-probe handling so a
`ShioriRequestException(ownershipCertain = true)` from
`Get_Supported_Events` or `Has_Event` produces `UNKNOWN` for that optional
capability while retaining the loaded session. Rethrow an exception with
`ownershipCertain = false` to the runtime: it performs exactly one unload;
successful cleanup returns replayable with no published session, while cleanup
failure poisons. Add tests for all three terminals and assert no handle or
generation is published by either uncertain terminal.

- [ ] **Step 4: Implement exact-generation request, unload, poison, and close**

`request(expectedGeneration: Long, intent: ShioriRequestIntent):
RuntimeResult<TaggedShioriResponse>` validates the generation before passing
`intent.protocolText` to the adapter and returns a parsed tagged response.
`ShioriRequestException(ownershipCertain = true)` becomes replayable failure;
`ownershipCertain = false` poisons the runtime. `unload(expectedGeneration):
RuntimeResult<Unit>` clears the
session only after `Unloaded`; an uncertain result retains evidence and sets
poison. `close()` first marks the runtime closed under `stateLock`, so preparation
cannot pass native admission. A preparation already submitted must recheck
`closed` on the native thread before adapter creation; a load already in progress
must recheck it again at publication and unload its local adapter inline when
publication loses the race. Only after those queue-confined fences may `close()`
drain the final known session unload. If poisoned, it records the retained
ownership evidence and performs no later JNI request or teardown attempt. It
then cancels preparation, shuts down the executor, awaits termination for five
seconds, and throws when the test thread survives. Cover close before native
submission, after native submission, and during a blocked load; no case may
publish a generation or retain an adapter owner after close returns.

- [ ] **Step 5: Implement attachment retained bits and typed no-script failure**

Store one `Attaching` record with `activationCommitted`, `bootAttempted`, cached
`BootOutcome`, and `runnerAdmissionCommitted`. Activation and boot each execute
once in the application scope. `BootAttemptFailed` attaches without passing a
fabricated response to the admission dependency. Admission is keyed by
attachment operation ID; retry after an exception reuses the cached outcome and
never repeats activation/boot. Isolated runtimes inject `AttachmentAdmission`;
the application runtime cannot call `attachHost` until Task 4 replaces this
dependency with its exact `SScriptRunner.admitAttachment` method outside the
native command thread.

Read the pre-increment activation count once, make one best-effort commit of
`count + 1`, then select the exact boot intent:

| Attachment reason | Prior count | Event and references |
|---|---:|---|
| `Initial` | `0` | `OnFirstBoot`, `Reference0: 0` |
| `Initial` | `> 0` | `OnBoot`, `Reference0: <shell name>` |
| `Switched(outgoingName)` | `0` | `OnFirstBoot`, `Reference0: 0` |
| `Switched(outgoingName)` | `> 0` | `OnGhostChanged`, `Reference0: outgoingName`, `Reference1: null` exactly |

Add literal intent assertions for all four rows and persistence-failure cases;
a failed best-effort activation commit sets `activationCommitted` and does not
repeat on retry.

Add `attachRetryReusesCachedBootOutcomeWithoutRepeatingActivationOrBoot`: the
first admission hook throws after the runtime caches the boot outcome; retry
joins the same attachment operation, then asserts one activation, one boot
request, one successful admission, the identical handle/generation, and no
second adapter request. Add stale and duplicate attachment-operation-ID cases
and assert they change no state.

- [ ] **Step 6: Implement operation-tagged switching and every terminal**

`beginSwitch` records outgoing generation, target identity, and switch operation ID. `completeSwitchPlayback` validates both IDs, unloads the outgoing session, and only then launches target preparation. `failSwitchBeforeUnload` clears intent and keeps outgoing active. Proven-empty replacement failure clears switch state and returns replayable failure; failed/uncertain cleanup poisons and returns fatal; stale/duplicate completion changes no state.

Expose `preferredGhostId(): String?` from runtime persistence for idle startup
selection. Initial accepted load and successful replacement each commit their ID
once before publishing `Unattached`; active same-root reuse never writes again.
Pre-unload switch failure, outgoing unload poison, target preparation failure,
and every target-load failure preserve the previous last-run ID. Add tests for
each terminal and for recreation observing the runtime pending target instead of
the preference.

- [ ] **Step 7: Run the isolated runtime suites repeatedly**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.GhostRuntimeTest" --tests "com.cattailsw.nanidroid.GhostRuntimeNativeThreadTest" --tests "com.cattailsw.nanidroid.GhostRuntimeAttachmentTest" --tests "com.cattailsw.nanidroid.GhostRuntimeSwitchTest" --rerun-tasks
```

Expected: PASS; every test runtime terminates its named thread and no test shares session/generation state.

Also assert capability bootstrap ownership semantics in
`GhostEventCapabilitiesTest` and `GhostRuntimeNativeThreadTest`: a certain
optional-probe failure leaves the session active with `UNKNOWN`; an uncertain
failure followed by successful cleanup permits retry; an uncertain failure
followed by failed cleanup poisons and forbids reload.

- [ ] **Step 8: Port the corpus off direct factory ownership**

Replace `TestShioriGhost` and direct `ShioriFactory` construction in
`NarCorpusRuntimeTest` with one closeable internal `GhostRuntime` fixture. The
test obtains a handle through `startOrJoin`, submits raw/tagged requests through
the runtime, and calls runtime unload before close. It never receives or retains
`Shiori`. Preserve every existing corpus JSON field and expected outcome.

```kotlin
GhostRuntime.testRuntime(
    context,
    GhostPreparer(context),
    persistence = InMemoryGhostRuntimePersistence(),
).use { runtime ->
    val handle = assertIs<RuntimeResult.Success<GhostHandle>>(
        runBlocking { runtime.startOrJoin(ghostIdentity, installedRoot) },
    ).value
    val boot = assertIs<RuntimeResult.Success<TaggedShioriResponse>>(
        runtime.request(handle.generation, ShioriRequestIntent.event("OnBoot")),
    ).value
    auditTaggedResponse(handle, boot)
    assertIs<RuntimeResult.Success<Unit>>(runtime.unload(handle.generation))
}
```

Compile the port with:

```powershell
.\gradlew.bat compileDebugAndroidTestKotlin
```

Expected: PASS and `rg -n "ShioriFactory|TestShioriGhost" src/androidTest` returns
no match.

- [ ] **Step 9: Commit the runtime core and corpus boundary**

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt src/main/kotlin/com/cattailsw/nanidroid/runtime/dialogue/GhostEventCapabilities.kt src/test/java/com/cattailsw/nanidroid/GhostRuntimeTest.kt src/test/java/com/cattailsw/nanidroid/GhostRuntimeNativeThreadTest.kt src/test/java/com/cattailsw/nanidroid/GhostRuntimeAttachmentTest.kt src/test/java/com/cattailsw/nanidroid/GhostRuntimeSwitchTest.kt src/test/java/com/cattailsw/nanidroid/runtime/dialogue/GhostEventCapabilitiesTest.kt src/androidTest/java/com/cattailsw/nanidroid/corpus/NarCorpusRuntimeTest.kt docs/testing/nar-corpus.md
git commit -m "Add single-thread GhostRuntime session core"
```

---

### Task 4: Atomically Replace Every Production Session Authority

**Files:**
- Rewrite: `src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt`
- Delete: `src/main/kotlin/com/cattailsw/nanidroid/GhostSessionCoordinator.kt`
- Delete: `src/main/kotlin/com/cattailsw/nanidroid/ShioriFactory.kt`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/DialogueDialogBindingTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/GhostRuntimeTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/GhostShellNameCompatibilityTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/GhostShioriTrafficTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/GhostSwitchingCharacterizationTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/GhostSwitchRequestTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/LegacyPlatformSeamTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SakuraScriptCharacterizationTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerAuthorityTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerBootDispatchTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueObserverTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerDialogueTimingTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/GhostEventCapabilitiesTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/runtime/dialogue/SurfaceInteractionProtocolTest.kt`

**Interfaces:**
- Consumes: Task 3 runtime commands and handles.
- Produces: one runtime-backed runner request port; immutable `Ghost` and surface
  catalog; Activity waiters only; runtime-owned startup, attachment, and switch;
  all production bootstrap, timer, pointer, dialogue, boot, close, and ordinary
  requests traverse the command thread. This is one non-separable cutover: its
  commit is not created until the coordinator, reservations, factory, Activity
  continuation, and every old Ghost native method are absent and all Step 9
  tests pass.

- [ ] **Step 1: Add a red architecture test for native-call bypasses**

Extend `tools/test_ghost_runtime_composition_root.py` to scan production Kotlin and assert:

```python
self.assertEqual(
    {"src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt"},
    files_containing_any("SatoriShiori(", "YayaShiori(", "Kawari("),
)
self.assertNotIn("Shiori", read("src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt"))
self.assertNotIn("doShioriEvent(", read("src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt"))
self.assertNotIn("requestRaw(", read("src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt"))
```

Run it and expect FAIL on `Ghost`, `ShioriFactory`, and current direct callers.

- [ ] **Step 2: Replace fake-Shiori Ghost subclasses with closeable runtime fixtures**

Create one shared internal test helper whose adapter records requests and returns scripted responses:

```kotlin
class RuntimeFixture(response: (String) -> String) : AutoCloseable {
    val trace = RecordingShioriTrace()
    val runtime = GhostRuntime.testRuntime(
        context = null,
        preparer = preparer,
        adapterFactory = { RecordingShiori(trace, response) },
        persistence = InMemoryGhostRuntimePersistence(),
    )
    val handle = runBlocking {
        assertIs<RuntimeResult.Success<GhostHandle>>(
            runtime.startOrJoin("recording", root),
        ).value
    }
    val runner = runtime.runner
    override fun close() = runtime.close()
}
```

The fixture never retains or exposes its adapter. For ordinary request tests it
calls `attachHost`, asserts success, drains the known 204 boot queue, and clears
only the shared trace before the behavior under test. Boot-dispatch tests call
`attachHost` without clearing and assert the attachment traffic itself.

Port every test named in this task's file list. Preserve literal
request/event/order, shell-name, platform-seam, queue, dialogue, presentation,
timer, and surface-interaction assertions; delete only assertions about
reservation identity, coordinator lock shape, or a fake adapter stored inside
`Ghost`.

- [ ] **Step 3: Add blocked startup and switch recreation instrumentation**

Use runtime-owned hooks to block initial preparation, recreate the Activity,
release preparation, and assert one prepare, load, generation, activation, and
boot request. Add a second case that blocks replacement preparation after
outgoing unload, recreates, releases, and asserts one target, replacement
generation, and `OnGhostChanged`/`OnFirstBoot`, with no second outgoing unload.
The hooks block only the preparation/command worker; neither `onCreate`, Compose,
nor the main thread waits on a latch.

Each test first obtains `application.ghostRuntime`, requires
`resetSessionForTesting()` success, installs one `GhostRuntimeTestHooks`, and
closes the returned hook token in `finally`. Counters and latches are driven only
by the six declared runtime callbacks; no Activity-local callback is accepted as
load/activation/boot evidence.

- [ ] **Step 4: Make Ghost immutable data only**

Replace the old inheritance-oriented `Ghost` with an immutable value created
from `PreparedGhost`, then change `GhostHandle.prepared` to
`GhostHandle.ghost: Ghost` in the same atomic edit. Delete constructor-time
parsing, `shiori`, `mCtx`, `eventCapabilities`, `loadGhostInfo`, `unload`,
`requestRaw`, `doShioriEvent`, `setShioriForTesting`, `sendOnSecondChange`, and
`sendOnMinuteChange`. Move activation persistence to a runtime dependency.
Callers read `ghost.surfaces`, `ghost.id`, `ghost.name`, and other immutable
properties. Change `ComposeGhostStageHost` to accept `SurfaceCatalog` and look
up immutable `SurfaceDefinition` directly; no `SurfaceManager`/`ShellSurface`
escapes from the runtime handle.

- [ ] **Step 5: Route every runner request outside runner monitors**

For each current `target.doShioriEvent`/`target.requestRaw`, capture `GhostHandle`, operation identity, a runner-local admission epoch, and event inputs under the runner lock, then release the lock. A main-looper caller submits `runtime.requestAsync(handle.generation, formattedRequest)`, returns an acceptance receipt, observes the data-only future outside the native command, and schedules response admission on the main response scheduler. The actual state mutation—not a preliminary check—re-enters once to atomically verify generation, operation, and admission epoch while applying the parsed result. Off-main legacy callers may use the synchronous helper but must use that same atomic mutation boundary. Capability bootstrap runs directly inside the runtime load command and does not enqueue a nested runtime request.

Use one helper with this shape:

```kotlin
private fun requestCurrent(intent: ShioriRequestIntent): Boolean {
    val (handle, epoch) = synchronized(this) {
        (activeHandle ?: return false) to requestAdmissionEpoch
    }
    return requestPinned(handle, intent) { result ->
        val tagged = (result as? RuntimeResult.Success)?.value ?: return@requestPinned false
        val shouldRun = synchronized(this) {
            if (
                activeHandle?.generation != handle.generation ||
                tagged.generation != handle.generation ||
                requestAdmissionEpoch != epoch
            ) return@synchronized null
            msgQueue.add(tagged.response.requirePlayableValue())
            !playback.running
        } ?: return@requestPinned false
        if (shouldRun) run()
        true
    }
}
```

The helper itself is never called while already holding the runner monitor.

- [ ] **Step 6: Replace startup reservations with runtime joining/attachment**

`GhostMgr` returns immutable catalog roots. `Nanidroid` first reads runtime
active/pending identity, selects a catalog root only when idle, calls
`startOrJoin`, then `attachHost(handle.generation)`. Lifecycle cancellation stops
only result collection. Delete reservation locals, claimed flags, `finally`
abandonment, and `setGhostToRunner`. Bind Task 3's `AttachmentAdmission` to the
exact `SScriptRunner.admitAttachment` method; runtime invokes it only from the
application orchestration scope after leaving the native command.

Authored close completion retains the exact generation through `OnClose`
admission and any returned playback. A 204 response, a 200 response without a
playable `Value`, an unscheduled request, or an ownership-certain request
failure is already an authored terminal and must proceed directly to unload;
it must not wait for a playback callback that cannot occur. The
application-owned runner/runtime operation invokes
`runtime.unload(expectedGeneration)` and delivers the terminal to the current
host only after the typed unload result is observed. The Activity then calls
`finish()` without owning cancellable unload work. A stale terminal cannot
unload a replacement generation. Add lifecycle coverage proving request →
optional playback → unload → finish ordering and proving that relaunch creates
and attaches a fresh generation rather than reusing the closed session.

An ownership-uncertain request failure, including an already-poisoned runtime,
is also an authored close terminal. It must not attempt the JNI teardown that
the poison contract forbids; the application-owned exit operation instead
observes the typed fatal result, records it for diagnostics, and still notifies
the current host to finish. Lifecycle coverage must prove this terminal cannot
strand the Activity or authorize a replacement native load in the poisoned
process.

- [ ] **Step 7: Replace Activity switch continuation and delete the coordinator**

`switchGhost` resolves exact target metadata and calls `beginSwitch`. The runner
receives the operation ID with `OnGhostChanging` playback and invokes
`completeSwitchPlayback` once outside its lock; known pre-unload request failure
invokes `failSwitchBeforeUnload`. Delete `nextGhostId`, `ghostSwitchStep2`, every
Activity replacement coroutine, `GhostSessionCoordinator.kt`, both reservation
types, runner attach/abandon/gate helpers, coordinator tests, and coordinator
constructor plumbing. Retained tests assert behavior through runtime handles.

- [ ] **Step 8: Delete the static factory and inject the exact production runner**

Move the exact engine-selection branches into private
`GhostRuntime.createAdapter(prepared)`, delete `ShioriFactory.kt`, and flip its
Python contract to assert absence and sole runtime construction. `GhostRuntime`
constructs `SScriptRunner(context, runtimePort = this)`. `CatTailApplication`
remains the sole production runtime constructor. Remove coordinator arguments
from the runner constructor and retain no implicit/fallback runtime constructor.

- [ ] **Step 9: Run every focused JVM/source/lifecycle gate before committing**

```powershell
python -m unittest tools.test_ghost_runtime_composition_root tools.test_kotlin_shiori_factory_contract
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.GhostRuntimeTest" --tests "com.cattailsw.nanidroid.GhostRuntimeAttachmentTest" --tests "com.cattailsw.nanidroid.GhostRuntimeSwitchTest" --tests "com.cattailsw.nanidroid.GhostShioriTrafficTest" --tests "com.cattailsw.nanidroid.GhostSwitchRequestTest" --tests "com.cattailsw.nanidroid.GhostSwitchingCharacterizationTest" --tests "com.cattailsw.nanidroid.SScriptRunnerAuthorityTest" --tests "com.cattailsw.nanidroid.SScriptRunnerBootDispatchTest" --tests "com.cattailsw.nanidroid.SScriptRunnerPresentationTest" --tests "com.cattailsw.nanidroid.SScriptRunnerDialogueTimingTest" --tests "com.cattailsw.nanidroid.SScriptRunnerDialogueObserverTest" --tests "com.cattailsw.nanidroid.runtime.dialogue.GhostEventCapabilitiesTest" --tests "com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionProtocolTest"
.\gradlew.bat compileDebugAndroidTestKotlin
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.NanidroidLifecycleInstrumentationTest
```

Expected: PASS, including both blocked-recreation cases. Source contracts find
no production adapter construction outside `GhostRuntime.kt` and no coordinator,
reservation, static factory, Activity switch continuation, or Ghost native API.
Before the Gradle command, run:

```powershell
rg -n ": Ghost\(|object : Ghost\(|override fun (loadGhostInfo|unload|doShioriEvent|requestRaw)|GhostSessionCoordinator|ReservedGhost|GhostConstructionReservation" src/test src/androidTest
```

Expected: no stale test fixture or constructor remains.

- [ ] **Step 10: Obtain independent locking, lifecycle, and native-authority review**

Give reviewers the Task 4 working diff. Require them to enumerate every former
request/unload site, verify no runner lock is held while waiting, verify commands
never call runner/UI, check cancellation/recreation and attachment retained bits,
check switch terminals, and prove completions are generation-fenced. Fix every
verified blocker and rerun Step 9.

- [ ] **Step 11: Commit the indivisible authority replacement**

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt src/test/java src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt tools/test_ghost_runtime_composition_root.py tools/test_kotlin_shiori_factory_contract.py
git add -u src/main/kotlin/com/cattailsw/nanidroid/GhostSessionCoordinator.kt src/main/kotlin/com/cattailsw/nanidroid/ShioriFactory.kt
git commit -m "Replace ghost sessions with GhostRuntime"
```

---

### Task 5: Validate Real Engines and Delete Remaining Bypasses

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/ShioriLifecycleInstrumentationTest.kt`
- Create: `src/androidTest/java/com/cattailsw/nanidroid/CrossEngineRuntimeInstrumentationTest.kt`
- Create: `scripts/run-cross-engine-runtime-audit.ps1`
- Modify: `tools/test_kotlin_ghost_discovery_contract.py`
- Modify: `tools/test_kotlin_shiori_factory_contract.py`
- Modify: `tools/test_native_shiori_contract.py`
- Modify: `tools/test_ghost_runtime_composition_root.py`
- Modify: `docs/testing.md`
- Modify: `docs/testing/nar-corpus.md`

**Interfaces:**
- Consumes: closeable test runtime, typed runtime request/unload APIs, immutable
  installed catalog, private production engine selection, and the corpus port
  committed in Task 3.
- Produces: no direct factory/adapter escape in production or instrumentation; exact real-engine transition evidence; final source/artifact absence contracts.

- [ ] **Step 1: Add final dead-API and absence contracts**

Assert all four transitional files are absent, production `GhostRuntime.kt` is
the sole engine constructor, `Ghost.kt` contains no `Shiori`, and no source
contains reservation names. After exact `rg` confirms no caller, assert
`GhostMgr` has no `hasSameGhostId`, general two/three-argument `installGhost`,
`getLastInstallError`, or backing error field. Run the contracts before deletion
and expect the zero-caller manager assertions to fail.

- [ ] **Step 2: Delete the remaining zero-caller manager APIs**

Delete only the methods/state named in Step 1. Preserve `installFirstGhost` and
the cancellable foreground-import path. Run manager/install JVM tests and the
four absence contracts; expect PASS.

- [ ] **Step 3: Add real adapter lifecycle tests through closeable runtimes**

`ShioriLifecycleInstrumentationTest` accepts host-verified Satori, YAYA, and
Kawari private archive paths. For each engine, it creates runtime A and proves
load/request success; while A owns the native global it creates runtime B and
proves B fails fatally with owner-present state and never unloads A. It closes B,
unloads A, creates fresh runtime C, proves load/request success and duplicate
runtime unload success, and closes A/C. It retains only handles, typed results,
and a runtime trace—never a `Shiori`.

For each real engine it then invokes `probeAdapterLifecycleForTesting` and
asserts this exact queue-confined sequence on the one native command thread:
invalid/proven-empty load, valid load, duplicate-load rejection, request
success, unload success, a second adapter/JNI unload success, JNI request-after-
unload rejection, YAYA JNI charset-after-unload rejection, and valid reload
success followed by unload. The probe invokes the actual bridge paths and
returns only `NativeLifecycleProbeTrace`; instrumentation never receives an
adapter. This execution evidence complements the source checks for cleared
native handles and forbidden post-unload use.

The invalid fixtures must exercise engine-specific validity, not only invalid
JNI arguments: Satori receives a directory with no usable dictionary, while
YAYA receives content whose VM reports suppression. Both native entry points
return the proven-empty status only after deterministic cleanup succeeds.

- [ ] **Step 4: Add the exact real-engine transition test and host harness**

Create `CrossEngineRuntimeInstrumentationTest` that requires four app-private
archive arguments: `satoriNarPath`, `yayaNarPath`, `kawariNarPath`, and
`satoriReloadNarPath`. It verifies their SHA-256 arguments, installs each into a
distinct temporary root with `NarTransactionalInstaller`, verifies the selected
engine, then uses one closeable runtime to assert load success, one request,
known unload, and the next load. Record the runtime command trace
and assert:

```kotlin
assertEquals(
    listOf(
        "load:Satori", "request:Satori", "unload:Satori",
        "load:YAYA", "request:YAYA", "unload:YAYA",
        "load:Kawari 8", "request:Kawari 8", "unload:Kawari 8",
        "load:Satori", "request:Satori", "unload:Satori",
    ),
    trace,
)
```

Create `scripts/run-cross-engine-runtime-audit.ps1` with required
`-DeviceSerial` and optional `-CorpusRoots` defaulting to `.` and
`build/ui-audit`. It opens each discovered NAR as ZIP, follows the same accepted
package-root/install-descriptor rules as the corpus harness, reads
`ghost/master/descript.txt`, and classifies `satori.dll`, `yaya.dll`, and
`shiori.dll` plus `kawarirc.kis`. It sorts candidates by SHA-256, requires at
least one of each engine, and records the exact selected label/hash. It pushes
run-owned copies, passes Satori twice under two private names, invokes
`ShioriLifecycleInstrumentationTest` and
`CrossEngineRuntimeInstrumentationTest`, pulls both trace reports, and removes
the exact pushed/app-private paths in `finally`. The script rejects ambiguity,
an API outside 31–37, or an ABI outside `x86_64`/`arm64-v8a`.

- [ ] **Step 5: Run source contracts, corpus, and real-engine chain**

```powershell
python -m unittest tools.test_ghost_runtime_composition_root tools.test_kotlin_ghost_discovery_contract tools.test_kotlin_shiori_factory_contract tools.test_native_shiori_contract
.\gradlew.bat testDebugUnitTest
.\scripts\run-cross-engine-runtime-audit.ps1 -DeviceSerial emulator-5554 -CorpusRoots .,build/ui-audit
powershell -ExecutionPolicy Bypass -File scripts/run-nar-corpus-audit.ps1 -DeviceSerial emulator-5554
```

Expected: the script prints and persists the exact selected archive paths and
hashes, contracts and runtime chain PASS, and corpus reports the available fixed
rows without claiming absent inputs.

- [ ] **Step 6: Commit real-engine validation and dead-API deletion**

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt src/androidTest/java/com/cattailsw/nanidroid/ShioriLifecycleInstrumentationTest.kt src/androidTest/java/com/cattailsw/nanidroid/CrossEngineRuntimeInstrumentationTest.kt scripts/run-cross-engine-runtime-audit.ps1 tools docs/testing.md docs/testing/nar-corpus.md
git commit -m "Validate real GhostRuntime engine transitions"
```

---

### Task 6: Full Validation, Independent Reviews, and GitHub Review

**Files:**
- Modify only files required by concrete validation or review findings.
- Create during validation: `build/reports/phase3-native-runtime-pr-body.md`
  (ignored evidence file; do not commit it).

**Interfaces:**
- Consumes: the completed Tasks 1–5 diff.
- Produces: a clean reviewed PR advancing #385, exact validation evidence, and explicit physical arm64 deferral when necessary.

- [ ] **Step 1: Perform the primary exact-diff review**

```powershell
git status --short
git diff --check origin/master...HEAD
git diff --stat origin/master...HEAD
git diff origin/master...HEAD -- src/main src/test src/androidTest jni tools docs/superpowers
rg -n "GhostSessionCoordinator|GhostConstructionReservation|ReservedGhost|ShioriFactory|InfoOnlyGhost|object DirList|terminate\(|setShioriForTesting|sendOnSecondChange|sendOnMinuteChange|nextGhostId|ghostSwitchStep2" src/main src/test src/androidTest tools
rg -n "SatoriShiori\(|YayaShiori\(|Kawari\(" src/main/kotlin
rg -n "SatoriShiori\(|YayaShiori\(|Kawari\(" src/androidTest/java
```

Expected: no transitional/dead name remains; the first constructor scan reports
only `src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt`, and the
`src/androidTest` scan returns no match. Instrumentation exercises real adapters
only through runtime handles and the data-only lifecycle probe; it never becomes
a test-only construction/session authority.

- [ ] **Step 2: Run full host verification**

```powershell
.\gradlew.bat testDebugUnitTest jacocoTestReport --rerun-tasks
.\gradlew.bat assembleDebug lint validateDebugScreenshotTest
python -m unittest discover -s tools -p "test_*contract.py"
```

Expected: PASS with no screenshot baseline change.

- [ ] **Step 3: Run connected API 37 verification**

```powershell
adb devices -l
.\gradlew.bat connectedDebugAndroidTest
.\scripts\run-cross-engine-runtime-audit.ps1 -DeviceSerial emulator-5554 -CorpusRoots .,build/ui-audit
powershell -ExecutionPolicy Bypass -File scripts/run-nar-corpus-audit.ps1 -DeviceSerial emulator-5554
```

The two argument-driven instrumentation classes use `Assume` to skip under the
generic connected run when their private archive/hash arguments are absent;
the generic command is not accepted as their evidence. Expected: the generic
suite passes on the repository API 37 x86_64 emulator or an API 31–37 device,
then both harness commands pass and record native lifecycle, the exact cross-
engine chain, and available corpus rows.

- [ ] **Step 4: Verify both packaged ABIs and record arm64 honestly**

Inspect the debug APK and assert every retained native library exists for `x86_64` and `arm64-v8a`; run `llvm-readelf -h` on extracted arm64 libraries and require AArch64 machine type. If a physical arm64 API 31–37 device is attached, rerun `connectedDebugAndroidTest` for that exact serial. If none is attached, record physical arm64 runtime as deferred to #374, leave #385 open, and report only package/ELF evidence.

- [ ] **Step 5: Dispatch independent final reviews**

Send the exact `origin/master...HEAD` diff and approved spec to three independent reviewers:

```text
Android/lifecycle: verify application ownership, Activity cancellation/recreation, no main-thread blocking, exactly-once attachment/boot, and preserved foreground/background behavior. Report only actionable defects or APPROVE.

Native/concurrency: enumerate every adapter construction/load/charset/request/unload path, verify one named OS thread, JNI lifecycle truth, generation fencing, poison behavior, and absence of lock inversion. Report only actionable defects or APPROVE.

Adversarial/deletion: hunt for hidden renamed authority, direct adapter escapes, stale switch/attachment terminals, lost essential behavior, weakened corpus assertions, and unjustified test deletion. Report only actionable defects or APPROVE.
```

Fix each verified finding, rerun affected focused and full gates, commit focused fixes, and send the corrected diff back to the finding reviewer until approved.

- [ ] **Step 6: Push and open the focused PR**

After Steps 1–5 have actual results, use `apply_patch` to create
`build/reports/phase3-native-runtime-pr-body.md`. Populate it with the reviewed
commit, behavioral/deletion summary, explicit “advances but does not close
#385”, every exact host/connected/corpus/cross-engine command and result, the
three independent reviewer outcomes, JNI/CMake and API 37 coverage, packaged
ABI evidence, the #395 semantic corpus rebase, and the actual physical arm64
result or #374 deferral. Do not claim CI or GitHub automatic-review completion
before those results exist.

```powershell
git push -u origin codex/phase3-native-runtime-thread
gh pr create --base master --head codex/phase3-native-runtime-thread --title "Confine ghost sessions to GhostRuntime" --body-file build/reports/phase3-native-runtime-pr-body.md
```

Inspect the created PR body and verify every obtained result above is present
without a future-result claim.

- [ ] **Step 7: Obtain and resolve GitHub automatic review**

Request GitHub automatic Codex review on the exact pushed head. Wait for CI and review to settle, inspect every inline thread/check, fix verified findings, push, rerun affected gates, and re-request review after any head change. Do not report merge readiness with unresolved threads, stale automatic review, or failing required checks.

- [ ] **Step 8: Record final evidence**

After Step 7 settles, update the ignored body file with the exact CI conclusions,
GitHub automatic-review result, resolved-thread state, and final reviewed commit,
then run:

```powershell
gh pr edit --body-file build/reports/phase3-native-runtime-pr-body.md
git status --short
```

Require a clean tracked worktree (the ignored evidence file is allowed). Verify
the final PR body records the self-review, three independent approvals, GitHub
automatic-review result, host/connected/corpus commands, packaged ABI evidence,
and physical arm64 result/deferral before reporting the slice ready for squash
merge.
