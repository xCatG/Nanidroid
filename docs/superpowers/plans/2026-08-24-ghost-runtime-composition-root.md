# GhostRuntime Composition Root Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `SScriptRunner`'s process-static production authority with one application-owned `GhostRuntime`, while preserving behavior and isolating each runner's SakuraScript queue.

**Architecture:** `CatTailApplication` creates one synchronized-lazy `GhostRuntime`; that runtime creates the exact `GhostSessionCoordinator` and `SScriptRunner` used by every `Nanidroid` and injected `GhostMgr`. This entry slice moves composition and queue ownership only; native SHIORI calls stay on their current threads until the next atomic slice.

**Tech Stack:** Kotlin, Android `Application`/`ActivityScenario`, JUnit 4, AndroidX Test, Gradle wrapper, Python `unittest`

**Spec:** `docs/superpowers/specs/2026-08-24-ghost-runtime-composition-root-design.md`

## Global Constraints

- One application-scoped `GhostRuntime`; no Activity-scoped or fallback production runtime.
- One runtime owns exactly one `GhostSessionCoordinator`, one `SScriptRunner`, and that runner's instance-local SakuraScript queue.
- Preserve startup, switching, playback, timer, rendering, dialogue, and SHIORI behavior.
- Do not move any subset of construction, load, charset, request, close, or unload calls to a new thread in this slice.
- Do not add a second host, actor, session manager, app coordinator, renderer callback, or presentation coordinator.
- Recreation guarantees in this slice apply only after an active ghost is attached; joining blocked startup remains the next slice.
- No backward-compatibility shim is required because no signed release has been distributed.
- Use the Gradle wrapper on Windows; compile/target API 37 and minSdk 31 remain unchanged.

## File Map

- Create `src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt`: application-lifetime composition root and the only transitional construction/reuse entry points.
- Modify `src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt`: synchronized-lazy runtime owner and eager `onCreate` initialization.
- Modify `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt`: instance-own `msgQueue`; remove singleton, implicit coordinator constructor, and companion session helpers.
- Modify `src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt`: receive `GhostRuntime` and route reuse/construction through it.
- Modify `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt`: read the application runtime once and pass it to `GhostMgr`.
- Create `src/test/java/com/cattailsw/nanidroid/SScriptRunnerAuthorityTest.kt`: queue-isolation and no-static-authority contracts.
- Create `src/test/java/com/cattailsw/nanidroid/GhostRuntimeTest.kt`: coordinator/runner identity and isolated-runtime contracts.
- Modify `src/test/java/com/cattailsw/nanidroid/GhostSwitchingCharacterizationTest.kt`: remove singleton-reset/concurrent-singleton coverage.
- Modify `src/test/java/com/cattailsw/nanidroid/SakuraScriptCharacterizationTest.kt`: use a fresh explicit coordinator per test.
- Modify `src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt`: replace one-argument runner construction with explicit coordinator construction.
- Modify `src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt`: prove one application runtime and active-session recreation identity.
- Create `tools/test_ghost_runtime_composition_root.py`: enforce `CatTailApplication` as the sole production runtime creator.
- Modify `docs/testing.md`: include the new composition-root contract in host verification.

---

### Task 1: Make the SakuraScript queue runner-instance-owned

**Files:**
- Create: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerAuthorityTest.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt:75-95`

**Interfaces:**
- Consumes: existing `SScriptRunner(Context?, GhostSessionCoordinator)` and `runtimeModeSnapshot(): GhostRuntimeMode` test seam.
- Produces: one `private val msgQueue = ConcurrentLinkedQueue<String>()` per runner; queue method signatures remain unchanged.

- [ ] **Step 1: Write the failing cross-runner queue test**

```kotlin
package com.cattailsw.nanidroid

import org.junit.Assert
import org.junit.Rule
import org.junit.Test

class SScriptRunnerAuthorityTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun runnersCannotConsumeOrClearEachOthersQueuedScripts() {
        val first = SScriptRunner(null, GhostSessionCoordinator()).apply {
            setNoWaitMode(true)
        }
        val second = SScriptRunner(null, GhostSessionCoordinator()).apply {
            setNoWaitMode(true)
        }

        first.addMsgToQueue(arrayOf("\\0first\\e"))
        Assert.assertTrue(first.runtimeModeSnapshot().playingTalk)

        second.run()
        Assert.assertTrue(first.runtimeModeSnapshot().playingTalk)
        Assert.assertFalse(second.runtimeModeSnapshot().playingTalk)

        first.clearMsgQueue()
        first.addMsgToQueue(arrayOf("\\0still-first\\e"))
        second.clearMsgQueue()
        Assert.assertTrue(first.runtimeModeSnapshot().playingTalk)
        Assert.assertFalse(second.runtimeModeSnapshot().playingTalk)

        first.clearMsgQueue()
    }
}
```

- [ ] **Step 2: Run the focused test and verify the shared queue is exposed**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.SScriptRunnerAuthorityTest"
```

Expected: FAIL because `second.run()` consumes the companion queue or `second.clearMsgQueue()` clears it, making `first.runtimeModeSnapshot().playingTalk` false.

- [ ] **Step 3: Move only the existing queue field to the runner instance**

Remove this line from `SScriptRunner.companion object`:

```kotlin
private val msgQueue = ConcurrentLinkedQueue<String>()
```

Add the same field beside the other runner-owned mutable state:

```kotlin
private val msgQueue = ConcurrentLinkedQueue<String>()
private var presentationRenderer: GhostPresentationRenderer? = null
```

Do not change enqueue, poll, clear, stop, or snapshot behavior.

- [ ] **Step 4: Run the focused test and the retained SakuraScript suites**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.SScriptRunnerAuthorityTest" --tests "com.cattailsw.nanidroid.SakuraScriptCharacterizationTest" --tests "com.cattailsw.nanidroid.SScriptRunnerDialogueTimingTest" --tests "com.cattailsw.nanidroid.SScriptRunnerPresentationTest"
```

Expected: PASS.

- [ ] **Step 5: Commit the queue ownership change**

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt src/test/java/com/cattailsw/nanidroid/SScriptRunnerAuthorityTest.kt
git commit -m "Move SakuraScript queue into runner"
```

---

### Task 2: Add the explicit GhostRuntime authority

**Files:**
- Create: `src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt`
- Create: `src/test/java/com/cattailsw/nanidroid/GhostRuntimeTest.kt`

**Interfaces:**
- Consumes: `GhostSessionCoordinator.beginConstruction`, `GhostSessionCoordinator.reuseActive`, and `SScriptRunner(Context?, GhostSessionCoordinator)`.
- Produces: `GhostRuntime.runner: SScriptRunner`, `beginGhostConstruction(String, File): GhostConstructionReservation`, and `reuseActiveGhost(String, File): ReservedGhost?`.

- [ ] **Step 1: Write failing authority and isolation tests**

```kotlin
package com.cattailsw.nanidroid

import java.io.File
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

class GhostRuntimeTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun reservationCanOnlyBeConsumedByItsRuntimeRunner() {
        val owner = GhostRuntime(null)
        val other = GhostRuntime(null)
        val root = File("build/ghost-runtime-test/owner").canonicalFile
        val reservation = owner.beginGhostConstruction(root.name, root)
            .bind(FakeGhost(root))

        Assert.assertFalse(other.runner.attachReservedGhost(reservation))
        Assert.assertTrue(owner.runner.abandonReservedGhost(reservation))
    }

    @Test
    fun explicitRuntimesHaveIndependentRunnerAndCoordinatorAuthority() {
        val first = GhostRuntime(null)
        val second = GhostRuntime(null)
        val firstRoot = File("build/ghost-runtime-test/first").canonicalFile
        val secondRoot = File("build/ghost-runtime-test/second").canonicalFile

        val firstConstruction = first.beginGhostConstruction(firstRoot.name, firstRoot)
        val secondConstruction = second.beginGhostConstruction(secondRoot.name, secondRoot)

        Assert.assertNotSame(first.runner, second.runner)
        firstConstruction.failConstruction()
        secondConstruction.failConstruction()
    }

    private class FakeGhost(root: File) : Ghost(root.path) {
        override fun loadGhostInfo() = Unit
        override fun incrementCreateCount() = Unit
        override fun getCreateCount(): Long = 0L
        override fun unload() = Unit
    }
}
```

- [ ] **Step 2: Run the focused test and verify the missing composition root**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.GhostRuntimeTest"
```

Expected: Kotlin compilation FAIL with unresolved reference `GhostRuntime`.

- [ ] **Step 3: Implement the minimal composition root**

Create `GhostRuntime.kt`:

```kotlin
package com.cattailsw.nanidroid

import android.content.Context
import java.io.File

internal class GhostRuntime(
    context: Context?,
    private val sessionCoordinator: GhostSessionCoordinator = GhostSessionCoordinator(),
) {
    val runner = SScriptRunner(context, sessionCoordinator)

    fun beginGhostConstruction(
        ghostId: String,
        ghostRoot: File,
    ): GhostConstructionReservation = sessionCoordinator.beginConstruction(ghostId, ghostRoot)

    fun reuseActiveGhost(
        ghostId: String,
        ghostRoot: File,
    ): ReservedGhost? = sessionCoordinator.reuseActive(ghostId, ghostRoot)
}
```

Do not add a thread, coroutine scope, command queue, lifecycle callback, or duplicated session state.

- [ ] **Step 4: Run the focused authority tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.GhostRuntimeTest" --tests "com.cattailsw.nanidroid.SScriptRunnerAuthorityTest"
```

Expected: PASS.

- [ ] **Step 5: Commit the composition root**

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt src/test/java/com/cattailsw/nanidroid/GhostRuntimeTest.kt
git commit -m "Add GhostRuntime authority"
```

---

### Task 3: Wire the application runtime and delete static runner authority

**Files:**
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt:64-99`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt:18-48`
- Modify: `src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt:300-425`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerAuthorityTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/GhostSwitchingCharacterizationTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SakuraScriptCharacterizationTest.kt`
- Modify: `src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt`
- Modify: `src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt`
- Create: `tools/test_ghost_runtime_composition_root.py`
- Modify: `docs/testing.md`

**Interfaces:**
- Consumes: the `GhostRuntime` API from Task 2.
- Produces: `CatTailApplication.ghostRuntime`, `GhostMgr(Context, GhostRuntime)`, and one production runner reachable only through the application runtime.

- [ ] **Step 1: Extend the authority test with failing static-architecture assertions**

Add imports and this test to `SScriptRunnerAuthorityTest.kt`:

```kotlin
import java.lang.reflect.Modifier

@Test
fun runnerHasNoStaticMutableSessionOrQueueAuthority() {
    val runnerFields = SScriptRunner::class.java.declaredFields.associateBy { it.name }
    Assert.assertFalse(runnerFields.containsKey("self"))
    Assert.assertFalse(runnerFields.containsKey("productionSessionCoordinator"))
    Assert.assertFalse(Modifier.isStatic(requireNotNull(runnerFields["msgQueue"]).modifiers))

    val forbiddenMethods = setOf(
        "getInstance",
        "beginGhostConstruction",
        "reserveGhostForAttachment",
        "reuseActiveGhost",
        "resetInstanceForTesting",
    )
    Assert.assertTrue(
        SScriptRunner::class.java.declaredMethods.none {
            it.name.substringBefore('$') in forbiddenMethods
        },
    )
    Assert.assertTrue(
        SScriptRunner.Companion::class.java.declaredMethods.none {
            it.name.substringBefore('$') in forbiddenMethods
        },
    )
    Assert.assertTrue(
        SScriptRunner::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .all { GhostSessionCoordinator::class.java in it.parameterTypes },
    )
}
```

Create `tools/test_ghost_runtime_composition_root.py` with the production
creator contract:

```python
import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
PRODUCTION_ROOT = ROOT / "src/main/kotlin"
APPLICATION = "src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt"
RUNTIME_CONSTRUCTION = re.compile(r"(?<!class )\bGhostRuntime\s*\(")


class GhostRuntimeCompositionRootTest(unittest.TestCase):
    def test_application_is_the_only_production_runtime_creator(self) -> None:
        creators = {}
        for path in sorted(PRODUCTION_ROOT.rglob("*.kt")):
            count = len(RUNTIME_CONSTRUCTION.findall(path.read_text(encoding="utf-8")))
            if count:
                creators[str(path.relative_to(ROOT)).replace("\\", "/")] = count

        self.assertEqual({APPLICATION: 1}, creators)


if __name__ == "__main__":
    unittest.main()
```

The negative lookbehind excludes the `class GhostRuntime(` declaration while
counting every constructor call in production Kotlin. Direct construction in
`src/test` remains the explicit test seam.

- [ ] **Step 2: Add failing application/runtime instrumentation contracts**

Replace `launchAndRecreateKeepsMainActivityAvailable` with:

```kotlin
@Test
fun recreatingAttachedSessionPreservesApplicationRuntimeGhostAndGeneration() {
    val application = ApplicationProvider.getApplicationContext<CatTailApplication>()
    ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
        val before = awaitActiveRuntime(scenario)
        Assert.assertSame(application.ghostRuntime.runner, before.runner)

        scenario.recreate()

        val after = awaitActiveRuntime(scenario)
        Assert.assertSame(application.ghostRuntime.runner, after.runner)
        Assert.assertSame(before.runner, after.runner)
        Assert.assertSame(before.ghost, after.ghost)
        Assert.assertEquals(before.nativeGeneration, after.nativeGeneration)
    }
}
```

Add this concurrent-read contract:

```kotlin
@Test
fun concurrentApplicationReadsReturnOneRuntimeAndRunner() {
    val application = ApplicationProvider.getApplicationContext<CatTailApplication>()
    val start = CountDownLatch(1)
    val runtimes = java.util.Collections.synchronizedList(mutableListOf<GhostRuntime>())
    val callers = List(12) {
        Thread {
            start.await(ACTIVITY_INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            runtimes += application.ghostRuntime
        }.apply { start() }
    }

    start.countDown()
    callers.forEach { it.join(ACTIVITY_INIT_TIMEOUT_MILLIS) }

    Assert.assertEquals(12, runtimes.size)
    Assert.assertEquals(1, runtimes.map(System::identityHashCode).toSet().size)
    Assert.assertEquals(1, runtimes.map { System.identityHashCode(it.runner) }.toSet().size)
}
```

Add `import androidx.test.core.app.ApplicationProvider` if it is not already present.

- [ ] **Step 3: Run the red production-authority checks**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.SScriptRunnerAuthorityTest"
.\gradlew.bat compileDebugAndroidTestKotlin
python -m unittest tools.test_ghost_runtime_composition_root
```

Expected: the JVM architecture test FAILS on `self`/`productionSessionCoordinator`; Android-test compilation FAILS because `CatTailApplication.ghostRuntime` does not exist yet; the Python contract FAILS because there is no sole application construction call yet.

- [ ] **Step 4: Make `CatTailApplication` the only production runtime creator**

Change `CatTailApplication` to:

```kotlin
class CatTailApplication : Application() {
    internal val ghostRuntime: GhostRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GhostRuntime(this)
    }

    override fun onCreate() {
        super.onCreate()
        ghostRuntime
        ForegroundNarImportCoordinator.get(this)
    }
}
```

There must be no companion registry or reset hook for this property.

- [ ] **Step 5: Inject the exact runtime into `GhostMgr`**

Change the declaration and construction path to:

```kotlin
internal class GhostMgr(
    ctx: Context,
    private val ghostRuntime: GhostRuntime,
) {
```

```kotlin
return ghostRuntime.reuseActiveGhost(root.name, root) ?: run {
    val construction = ghostRuntime.beginGhostConstruction(root.name, root)
    try {
        construction.bind(Ghost(root.path, context))
    } catch (error: Exception) {
        construction.failConstruction()
        throw error
    } catch (error: LinkageError) {
        construction.failConstruction()
        throw error
    }
}
```

Do not move catalog loading or installation state into `GhostRuntime`.

- [ ] **Step 6: Make each `Nanidroid` use its application runtime**

Add the Activity field:

```kotlin
private lateinit var ghostRuntime: GhostRuntime
private var runner: SScriptRunner? = null
```

Replace singleton lookup in `onCreate` with:

```kotlin
ghostRuntime = (application as CatTailApplication).ghostRuntime
runner = ghostRuntime.runner
```

Replace Activity-local manager construction with:

```kotlin
val manager = GhostMgr(this, ghostRuntime)
```

Leave lifecycle scope, startup ordering, callbacks, clock calls, and reservation abandonment unchanged.

- [ ] **Step 7: Remove every implicit/static coordinator path from `SScriptRunner`**

Delete:

```kotlin
constructor(ctx: Context?) : this(ctx, productionSessionCoordinator)
```

Delete the `self`, `productionSessionCoordinator`, `getInstance`, companion `beginGhostConstruction`, companion `reserveGhostForAttachment`, companion `reuseActiveGhost`, and `resetInstanceForTesting` declarations. Keep only constants in the companion. Keep the instance `reserveGhostForAttachmentForTesting` seam because it uses the runner's injected coordinator.

- [ ] **Step 8: Migrate tests away from process singleton state**

In `SakuraScriptCharacterizationTest.setUp`, use:

```kotlin
runner = SScriptRunner(null, GhostSessionCoordinator())
```

Remove its `Companion.getInstance` import and update the setup comment to say the fresh runner is driven to a deterministic surface baseline.

In `SScriptRunnerPresentationTest`, replace every `SScriptRunner(null)` or fully-qualified one-argument construction with:

```kotlin
SScriptRunner(null, GhostSessionCoordinator())
```

In `GhostSwitchingCharacterizationTest`, remove the `Companion.getInstance` import, `concurrentFirstCallersShareOneRunnerAuthority`, its `resetInstanceForTesting()` call, and now-unused `CountDownLatch`/`TimeUnit` imports. The instrumentation contract now owns application concurrency coverage.

In `docs/testing.md`, extend the host-side architecture command to include the
new source contract:

```powershell
python -m unittest tools.test_kotlin_legacy_archive_runtime_absence tools.test_kotlin_foreground_nar_import_contract tools.test_ghost_runtime_composition_root tools.test_update_entrypoint_artifacts
```

- [ ] **Step 9: Run focused JVM, Android-test compilation, and active-session device coverage**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cattailsw.nanidroid.GhostRuntimeTest" --tests "com.cattailsw.nanidroid.SScriptRunnerAuthorityTest" --tests "com.cattailsw.nanidroid.GhostSwitchingCharacterizationTest" --tests "com.cattailsw.nanidroid.SakuraScriptCharacterizationTest" --tests "com.cattailsw.nanidroid.SScriptRunnerBootDispatchTest" --tests "com.cattailsw.nanidroid.SScriptRunnerPresentationTest"
.\gradlew.bat compileDebugAndroidTestKotlin
python -m unittest tools.test_ghost_runtime_composition_root
```

Expected: PASS.

Run the lifecycle class on an available API 37 emulator or API 31–37 device:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cattailsw.nanidroid.NanidroidLifecycleInstrumentationTest
```

Expected: PASS, including the concurrent application-runtime and active-session recreation assertions. This lifecycle acceptance is mandatory and cannot be deferred with physical arm64 coverage. If no test target is attached, start the repository's API 37 emulator and run it there.

If a physical arm64 API 31–37 device is also attached, rerun the same targeted class on that device. If it is unavailable, record only the physical arm64 validation as deferred; emulator results must not be described as arm64 coverage.

- [ ] **Step 10: Commit the production handoff**

```powershell
git add src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt src/main/kotlin/com/cattailsw/nanidroid/GhostMgr.kt src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt src/test/java/com/cattailsw/nanidroid/SScriptRunnerAuthorityTest.kt src/test/java/com/cattailsw/nanidroid/GhostSwitchingCharacterizationTest.kt src/test/java/com/cattailsw/nanidroid/SakuraScriptCharacterizationTest.kt src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.kt src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.kt tools/test_ghost_runtime_composition_root.py docs/testing.md
git commit -m "Route production through GhostRuntime"
```

---

### Task 4: Run the full validation and review gates

**Files:**
- Modify only files required by concrete validation or review findings.

**Interfaces:**
- Consumes: the completed composition-root slice from Tasks 1–3.
- Produces: a clean reviewed branch, a GitHub PR for issue #385, and explicit evidence for every run or deferral.

- [ ] **Step 1: Perform the primary self-review**

Run:

```powershell
git status --short
git diff --check origin/master...HEAD
git diff --stat origin/master...HEAD
git diff origin/master...HEAD -- src/main src/test src/androidTest docs/superpowers
rg -n "SScriptRunner\.(getInstance|beginGhostConstruction|reserveGhostForAttachment|reuseActiveGhost|resetInstanceForTesting)|productionSessionCoordinator|@Volatile private var self|private val msgQueue" src/main src/test src/androidTest
```

Expected: the diff contains only the design, plan, runtime handoff, and tests; no forbidden static call or coordinator field remains; `msgQueue` appears only as an instance field and its ordinary uses.

- [ ] **Step 2: Run full host verification**

Run:

```powershell
.\gradlew.bat testDebugUnitTest jacocoTestReport
.\gradlew.bat assembleDebug lint validateDebugScreenshotTest
python -m unittest tools.test_kotlin_legacy_archive_runtime_absence tools.test_kotlin_foreground_nar_import_contract tools.test_ghost_runtime_composition_root tools.test_update_entrypoint_artifacts
```

Expected: all Gradle tasks and all Python tests PASS. No screenshot baseline should change because this slice has no UI changes.

- [ ] **Step 3: Run connected verification and the optional physical arm64 gate**

Inspect:

```powershell
adb devices -l
```

Run the complete suite on an API 37 emulator or API 31–37 device:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Expected: PASS; this is the mandatory connected acceptance gate.

If an attached physical arm64 API 31–37 device is available, select that exact serial and rerun the complete suite on it. If unavailable, document the explicit physical-device deferral allowed for this phase; do not report emulator or x86 coverage as arm64 validation.

- [ ] **Step 4: Dispatch independent Android and adversarial multi-agent reviews**

Give both reviewers the exact `origin/master...HEAD` diff and
`docs/superpowers/specs/2026-08-24-ghost-runtime-composition-root-design.md`.
Use these complete review prompts:

```text
Android review: Review origin/master...HEAD for issue #385's approved composition-root slice. Check application lifetime, Activity recreation after active attachment, Kotlin/Android visibility, lifecycle test realism, and regressions. Do not edit files. Report only high-confidence actionable defects with file/line evidence, or state clean.

Adversarial review: Review origin/master...HEAD against the approved #385 spec. Hunt for hidden static session/playback authority, cross-runtime queue or coordinator leakage, multiple production runtimes, partial native-thread movement, startup/switch ordering changes, and untested acceptance claims. Do not edit files. Report only high-confidence actionable defects with file/line evidence, or state clean.
```

Address only verified findings, rerun each affected focused test, and commit fixes with focused imperative subjects.

- [ ] **Step 5: Push and open the GitHub PR**

```powershell
git push -u origin codex/phase3-ghost-runtime-root
```

If physical arm64 validation passed, create the PR with:

```powershell
gh pr create --base master --head codex/phase3-ghost-runtime-root --title "Introduce the GhostRuntime composition root" --body "Advances #385 with its composition-root entry slice and does not close the phase issue. CatTailApplication now owns one GhostRuntime, which owns the exact production GhostSessionCoordinator and SScriptRunner; GhostMgr receives that runtime explicitly, and each runner owns its SakuraScript queue. Native thread confinement remains the next atomic slice. Validation passed: testDebugUnitTest, jacocoTestReport, assembleDebug, lint, validateDebugScreenshotTest, the four host architecture unittest modules, connectedDebugAndroidTest on a physical arm64 API 31-37 device, primary self-review, and independent Android/adversarial reviews. No signed release was distributed, so no backward-compatibility shim is retained."
```

If no qualifying physical arm64 device was available, create it with:

```powershell
gh pr create --base master --head codex/phase3-ghost-runtime-root --title "Introduce the GhostRuntime composition root" --body "Advances #385 with its composition-root entry slice and does not close the phase issue. CatTailApplication now owns one GhostRuntime, which owns the exact production GhostSessionCoordinator and SScriptRunner; GhostMgr receives that runtime explicitly, and each runner owns its SakuraScript queue. Native thread confinement remains the next atomic slice. Validation passed: testDebugUnitTest, jacocoTestReport, assembleDebug, lint, validateDebugScreenshotTest, the four host architecture unittest modules, connectedDebugAndroidTest on an API 37 emulator or API 31-37 device, primary self-review, and independent Android/adversarial reviews. Physical arm64 connectedDebugAndroidTest is explicitly deferred because no qualifying API 31-37 device was attached; this phase permits that deferral and does not claim arm64 coverage. No signed release was distributed, so no backward-compatibility shim is retained."
```

- [ ] **Step 6: Obtain and resolve GitHub automatic review**

Request the repository's automatic Codex review on the exact pushed head. Wait for CI and automatic review to settle, inspect every inline thread and check conclusion, address each verified actionable finding, push the fixes, rerun the affected gates, and re-request review if the head changed. Do not merge with unresolved threads, a stale review, or a failing required check.

- [ ] **Step 7: Record final evidence without overstating coverage**

Capture the exact reviewed commit, CI conclusions, multi-agent review outcomes, GitHub automatic-review outcome, host command results, and physical arm64 result/deferral in the PR. Confirm `git status --short` is clean before reporting the slice ready for merge.
