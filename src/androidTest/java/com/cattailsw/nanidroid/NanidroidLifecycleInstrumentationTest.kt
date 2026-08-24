package com.cattailsw.nanidroid

import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.State
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ActivityScenario.ActivityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.cattailsw.nanidroid.install.ArchiveInstallResult
import com.cattailsw.nanidroid.install.ForegroundNarImportBackend
import com.cattailsw.nanidroid.install.ForegroundNarImportCoordinator
import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarDocumentSelection
import com.cattailsw.nanidroid.install.NarImportAttemptToken
import com.cattailsw.nanidroid.install.NarImportRecoveryResult
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/** Real-device smoke coverage for main-activity launch and configuration recreation.  */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NanidroidLifecycleInstrumentationTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun launchAndRecreateKeepsMainActivityAvailable() {
        ActivityScenario.launch<Nanidroid?>(Nanidroid::class.java).use { scenario ->
            val initial = AtomicReference<Nanidroid?>()
            scenario.onActivity(ActivityAction { newValue: Nanidroid? -> initial.set(newValue) })
            Assert.assertNotNull(initial.get())

            scenario.recreate()

            val recreated = AtomicReference<Nanidroid?>()
            scenario.onActivity(ActivityAction { newValue: Nanidroid? -> recreated.set(newValue) })
            Assert.assertNotNull(recreated.get())
            Assert.assertFalse(recreated.get()!!.isFinishing())
        }
    }

    @Test
    fun sameProcessRecreationRestoresTheExactPickerOwnerWithoutRelaunching() {
        val dispatcher = QueuedDispatcher()
        val backend = RecordingForegroundNarBackend()
        val coordinator = ForegroundNarImportCoordinator(backend, dispatcher, "picker-process")
        dispatcher.runNext()
        ForegroundNarImportCoordinator.replaceForTesting(coordinator)

        try {
            ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
                val token = requireNotNull(coordinator.armPicker())
                scenario.onActivity { activity ->
                    privateFieldHandle(activity, "narPickerOwnerToken").set(activity, token)
                }

                scenario.recreate()

                scenario.onActivity { activity ->
                    Assert.assertEquals(token, nullablePrivateField(activity, "narPickerOwnerToken"))
                    Assert.assertEquals(
                        ForegroundNarImportState.AwaitingSelection(token),
                        coordinator.state.value,
                    )
                    Assert.assertTrue(coordinator.abandonPicker(token))
                    val next = requireNotNull(coordinator.armPicker())
                    Assert.assertEquals(
                        "A recreation must not arm a second hidden picker journey",
                        token.sequence + 1L,
                        next.sequence,
                    )
                    Assert.assertTrue(coordinator.abandonPicker(next))
                    privateFieldHandle(activity, "narPickerOwnerToken").set(activity, null)
                }
            }
        } finally {
            returnCoordinatorToIdle(coordinator, dispatcher, backend)
            ForegroundNarImportCoordinator.resetForTesting()
        }
    }

    @Test
    fun recreatingDuringCopyingAndInstallingKeepsOneImportAttempt() {
        val dispatcher = QueuedDispatcher()
        val selection = NarDocumentSelection(
            "content://archives/controlled.nar",
            "application/x-nar",
        )
        val backend = ControlledForegroundNarBackend(
            importResult = ArchiveInstallResult.Installed("/ghost/controlled", "controlled"),
        )
        val coordinator = ForegroundNarImportCoordinator(backend, dispatcher, "recreate-process")
        dispatcher.runNext()
        ForegroundNarImportCoordinator.replaceForTesting(coordinator)

        val importThreadFailure = AtomicReference<Throwable?>()
        var importThread: Thread? = null

        try {
            ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
                val initialActivity = AtomicReference<Nanidroid?>()
                scenario.onActivity { initialActivity.set(it) }

                val token = requireNotNull(coordinator.armPicker())
                Assert.assertTrue(coordinator.consumePickerResult(token, selection, importAllowed = true))
                Assert.assertEquals(ForegroundNarImportState.Copying(token), coordinator.state.value)

                scenario.recreate()
                val copyingActivity = AtomicReference<Nanidroid?>()
                scenario.onActivity { copyingActivity.set(it) }
                Assert.assertNotSame(initialActivity.get(), copyingActivity.get())
                Assert.assertEquals(ForegroundNarImportState.Copying(token), coordinator.state.value)

                importThread = Thread {
                    try {
                        dispatcher.runNext()
                    } catch (failure: Throwable) {
                        importThreadFailure.set(failure)
                    }
                }.apply { start() }
                Assert.assertTrue(
                    "Controlled backend never received the import",
                    backend.importStarted.await(ACTIVITY_INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                )

                backend.allowInstalling.countDown()
                Assert.assertTrue(
                    "Controlled backend never published Installing",
                    backend.installingPublished.await(ACTIVITY_INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                )
                Assert.assertEquals(
                    ForegroundNarImportState.Installing(token, CONTROLLED_PHASE, CONTROLLED_COMPLETED),
                    coordinator.state.value,
                )

                scenario.recreate()
                val installingActivity = AtomicReference<Nanidroid?>()
                scenario.onActivity { installingActivity.set(it) }
                Assert.assertNotSame(copyingActivity.get(), installingActivity.get())
                Assert.assertEquals(
                    ForegroundNarImportState.Installing(token, CONTROLLED_PHASE, CONTROLLED_COMPLETED),
                    coordinator.state.value,
                )

                backend.allowCompletion.countDown()
                importThread.join(ACTIVITY_INIT_TIMEOUT_MILLIS)
                Assert.assertFalse("Import worker did not terminate", importThread.isAlive)
                importThreadFailure.get()?.let { throw AssertionError("Import worker failed", it) }

                Assert.assertEquals(
                    ForegroundNarImportState.Installed(token, "/ghost/controlled", "controlled"),
                    coordinator.state.value,
                )
                Assert.assertEquals(1, backend.importCalls.get())
                Assert.assertEquals(listOf(selection), backend.selections.toList())

                Assert.assertTrue(coordinator.acknowledge(token))
                val next = requireNotNull(coordinator.armPicker())
                Assert.assertEquals(
                    "Activity recreation must not create another picker attempt",
                    token.sequence + 1L,
                    next.sequence,
                )
                Assert.assertTrue(coordinator.abandonPicker(next))
            }
        } finally {
            backend.allowInstalling.countDown()
            backend.allowCompletion.countDown()
            dispatcher.runNextIfPresent()
            importThread?.join(ACTIVITY_INIT_TIMEOUT_MILLIS)
            val state = coordinator.state.value
            when (state) {
                is ForegroundNarImportState.AwaitingSelection -> coordinator.abandonPicker(state.token)
                is ForegroundNarImportState.Installed -> coordinator.acknowledge(state.token)
                is ForegroundNarImportState.Failed -> coordinator.acknowledge(state.token)
                is ForegroundNarImportState.Interrupted -> coordinator.acknowledge(state.token)
                else -> Unit
            }
            ForegroundNarImportCoordinator.resetForTesting()
        }
    }

    @Test
    fun installedPrimaryWaitsForReplacementGhostMgrAndCleanupRetryRefreshesOnce() {
        val dispatcher = QueuedDispatcher()
        val backend = RecordingForegroundNarBackend(
            recoveryResults = listOf(
                NarImportRecoveryResult.Clean,
                NarImportRecoveryResult.Failed("cleanup blocked"),
                NarImportRecoveryResult.Clean,
            ),
            importResult = ArchiveInstallResult.Installed("/ghost/imported", "imported"),
        )
        val coordinator = ForegroundNarImportCoordinator(backend, dispatcher, "readiness-process")
        dispatcher.runNext()
        ForegroundNarImportCoordinator.replaceForTesting(coordinator)

        val constructionCount = AtomicInteger(0)
        val replacementConstructed = CountDownLatch(1)
        val allowReplacementReady = CountDownLatch(1)
        val replacementManager = AtomicReference<GhostMgr?>()
        val replacementRefreshed = CountDownLatch(1)
        val refreshedToken = AtomicReference<NarImportAttemptToken?>()
        val refreshCount = AtomicInteger(0)
        Nanidroid.replaceLifecycleTestHooksForTesting(
            NanidroidLifecycleTestHooks(
                afterGhostMgrCreatedBeforeReady = { manager ->
                    if (constructionCount.incrementAndGet() == 2) {
                        replacementManager.set(manager)
                        replacementConstructed.countDown()
                        check(allowReplacementReady.await(ACTIVITY_INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                    }
                },
                onForegroundNarRefresh = { manager, token ->
                    if (manager === replacementManager.get()) {
                        refreshedToken.set(token)
                        refreshCount.incrementAndGet()
                        replacementRefreshed.countDown()
                    }
                },
            ),
        )

        try {
            ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
                val currentGhost = awaitActiveRuntime(scenario).ghost
                scenario.recreate()
                Assert.assertTrue(
                    "Replacement GhostMgr was not held before readiness",
                    replacementConstructed.await(ACTIVITY_INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                )

                val token = requireNotNull(coordinator.armPicker())
                Assert.assertTrue(
                    coordinator.consumePickerResult(
                        expectedToken = token,
                        selection = NarDocumentSelection("content://archives/imported.nar", "content"),
                        importAllowed = true,
                    ),
                )
                dispatcher.runNext()
                val recovery = coordinator.state.value as ForegroundNarImportState.RecoveryRequired
                Assert.assertEquals(token, recovery.token)
                Assert.assertEquals(0, refreshCount.get())

                allowReplacementReady.countDown()
                Assert.assertTrue(
                    "Installed publication never crossed replacement GhostMgr readiness",
                    replacementRefreshed.await(ACTIVITY_INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                )
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                Assert.assertEquals(token, refreshedToken.get())
                Assert.assertEquals(1, refreshCount.get())
                Assert.assertSame(currentGhost, awaitActiveRuntime(scenario).ghost)

                Assert.assertTrue(coordinator.retryCleanup(token))
                Assert.assertTrue(coordinator.state.value is ForegroundNarImportState.Cleaning)
                dispatcher.runNext()
                Assert.assertEquals(
                    ForegroundNarImportState.Installed(token, "/ghost/imported", "imported"),
                    coordinator.state.value,
                )
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                Assert.assertTrue(
                    "Installed primary did not replay its success presentation after cleanup",
                    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wait(
                        Until.hasObject(
                            By.text(
                                InstrumentationRegistry.getInstrumentation().targetContext.getString(
                                    R.string.nar_import_installed_title,
                                ),
                            ),
                        ),
                        PRESENTATION_TIMEOUT_MILLIS,
                    ),
                )
                scenario.onActivity { activity ->
                    Assert.assertSame(currentGhost, privateField(activity, "currentGhost"))
                }
                Assert.assertEquals(
                    "RecoveryRequired -> Cleaning -> Installed must not refresh twice",
                    1,
                    refreshCount.get(),
                )
                Assert.assertTrue(coordinator.acknowledge(token))
            }
        } finally {
            allowReplacementReady.countDown()
            Nanidroid.resetLifecycleTestHooksForTesting()
            returnCoordinatorToIdle(coordinator, dispatcher, backend)
            ForegroundNarImportCoordinator.resetForTesting()
        }
    }

    @Test
    fun deadProcessPickerTokenCannotOpenItsReturnedUriOrCreateAnActivityDialog() {
        val dispatcher = QueuedDispatcher()
        val backend = RecordingForegroundNarBackend()
        val coordinator = ForegroundNarImportCoordinator(backend, dispatcher, "fresh-process")
        dispatcher.runNext()
        ForegroundNarImportCoordinator.replaceForTesting(coordinator)

        try {
            ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val dialogState = privateField(activity, "simpleDialogState") as State<*>
                    val dialogBefore = dialogState.value
                    val restoredOwner = reconcileNarPickerOwner(
                        restored = NarImportAttemptToken("dead-process", 1),
                        state = coordinator.state.value,
                        abandon = coordinator::abandonPicker,
                    )
                    Assert.assertNull(restoredOwner)
                    privateFieldHandle(activity, "narPickerOwnerToken").set(activity, restoredOwner)

                    val accepted = Nanidroid::class.java.getDeclaredMethod(
                        "dispatchNarDocumentPickerResult",
                        Uri::class.java,
                    ).apply { isAccessible = true }.invoke(
                        activity,
                        Uri.parse("content://archives/stale.nar"),
                    ) as Boolean

                    Assert.assertFalse(accepted)
                    Assert.assertNull(nullablePrivateField(activity, "narPickerOwnerToken"))
                    Assert.assertEquals(ForegroundNarImportState.Idle, coordinator.state.value)
                    Assert.assertEquals(0, backend.importCalls.get())
                    Assert.assertSame(dialogBefore, dialogState.value)
                }
            }
        } finally {
            returnCoordinatorToIdle(coordinator, dispatcher, backend)
            ForegroundNarImportCoordinator.resetForTesting()
        }
    }

    @Test
    fun pausingActivityStopsClockWithoutReplacingRuntimeOrNativeSession() {
        ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
            val before = awaitActiveRuntime(scenario)

            scenario.moveToState(Lifecycle.State.STARTED)

            scenario.onActivity { activity ->
                val runner = privateField(activity, "runner") as SScriptRunner
                val ghost = privateField(activity, "currentGhost") as Ghost
                val bootState = privateField(runner, "bootDispatchState")
                Assert.assertSame(before.runner, runner)
                Assert.assertSame(before.ghost, ghost)
                Assert.assertEquals(before.nativeGeneration, nativeSessionGeneration(runner))
                Assert.assertFalse(privateBoolean(bootState, "clockStarted"))
                Assert.assertTrue(privateBoolean(bootState, "bootDispatched"))
            }
        }
    }

    private fun awaitActiveRuntime(scenario: ActivityScenario<Nanidroid>): RuntimeIdentity {
        val deadline = SystemClock.uptimeMillis() + ACTIVITY_INIT_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            var identity: RuntimeIdentity? = null
            scenario.onActivity { activity ->
                val initialized = privateField(activity, "initComplete") as Boolean
                val runner = nullablePrivateField(activity, "runner") as? SScriptRunner
                val ghost = nullablePrivateField(activity, "currentGhost") as? Ghost
                if (initialized && runner != null && ghost != null) {
                    val bootState = privateField(runner, "bootDispatchState")
                    if (
                        privateBoolean(bootState, "clockStarted") &&
                        privateBoolean(bootState, "bootDispatched")
                    ) {
                        identity = RuntimeIdentity(runner, ghost, nativeSessionGeneration(runner))
                    }
                }
            }
            identity?.let { return it }
            SystemClock.sleep(RUNNER_STATE_POLL_MILLIS)
        }
        throw AssertionError("Nanidroid did not finish runtime initialization")
    }

    private fun returnCoordinatorToIdle(
        coordinator: ForegroundNarImportCoordinator,
        dispatcher: QueuedDispatcher,
        backend: RecordingForegroundNarBackend,
    ) {
        repeat(6) {
            when (val state = coordinator.state.value) {
                ForegroundNarImportState.Recovering -> dispatcher.runNextIfPresent()
                ForegroundNarImportState.Idle -> return
                is ForegroundNarImportState.AwaitingSelection -> coordinator.abandonPicker(state.token)
                is ForegroundNarImportState.Copying,
                is ForegroundNarImportState.Installing,
                is ForegroundNarImportState.Cleaning,
                -> dispatcher.runNextIfPresent()
                is ForegroundNarImportState.Installed -> coordinator.acknowledge(state.token)
                is ForegroundNarImportState.Failed -> coordinator.acknowledge(state.token)
                is ForegroundNarImportState.Interrupted -> coordinator.acknowledge(state.token)
                is ForegroundNarImportState.RecoveryRequired -> {
                    backend.recoveryResults.clear()
                    backend.recoveryResults.addLast(NarImportRecoveryResult.Clean)
                    coordinator.retryCleanup(state.token)
                    dispatcher.runNextIfPresent()
                }
            }
        }
        Assert.assertEquals(ForegroundNarImportState.Idle, coordinator.state.value)
    }

    private fun nativeSessionGeneration(runner: SScriptRunner): Long {
        val coordinator = privateField(runner, "sessionCoordinator")
        val owner = privateField(coordinator, "globalOwner")
        return privateField(owner, "generation") as Long
    }

    private fun privateBoolean(instance: Any, name: String): Boolean =
        privateField(instance, name) as Boolean

    private fun privateField(instance: Any, name: String): Any {
        return nullablePrivateField(instance, name)
            ?: throw AssertionError("Expected non-null $name on ${instance.javaClass.name}")
    }

    private fun nullablePrivateField(instance: Any, name: String): Any? {
        return privateFieldHandle(instance, name).get(instance)
    }

    private fun privateFieldHandle(instance: Any, name: String) =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        const val CONTROLLED_PHASE = "publishing"
        const val CONTROLLED_COMPLETED = 7L
        const val RUNNER_STATE_POLL_MILLIS = 20L
        const val PRESENTATION_TIMEOUT_MILLIS = 5_000L
        const val ACTIVITY_INIT_TIMEOUT_MILLIS = 30_000L
    }

    private data class RuntimeIdentity(
        val runner: SScriptRunner,
        val ghost: Ghost,
        val nativeGeneration: Long,
    )

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
        }

        fun runNext() = requireNotNull(tasks.poll()).run()

        fun runNextIfPresent() {
            tasks.poll()?.run()
        }
    }

    private class ControlledForegroundNarBackend(
        private val importResult: ArchiveInstallResult,
    ) : ForegroundNarImportBackend {
        val importStarted = CountDownLatch(1)
        val allowInstalling = CountDownLatch(1)
        val installingPublished = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        val importCalls = AtomicInteger(0)
        val selections = ConcurrentLinkedQueue<NarDocumentSelection>()

        override fun recoverOwnedStaging(): NarImportRecoveryResult = NarImportRecoveryResult.Clean

        override fun importDocument(
            selection: NarDocumentSelection,
            isCancelled: () -> Boolean,
            onInstallingProgress: (phase: String, completed: Long) -> Unit,
        ): ArchiveInstallResult {
            importCalls.incrementAndGet()
            selections.add(selection)
            importStarted.countDown()
            check(allowInstalling.await(ACTIVITY_INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            onInstallingProgress(CONTROLLED_PHASE, CONTROLLED_COMPLETED)
            installingPublished.countDown()
            check(allowCompletion.await(ACTIVITY_INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            return importResult
        }
    }

    private class RecordingForegroundNarBackend(
        recoveryResults: List<NarImportRecoveryResult> = listOf(NarImportRecoveryResult.Clean),
        private val importResult: ArchiveInstallResult = ArchiveInstallResult.Cancelled,
    ) : ForegroundNarImportBackend {
        val recoveryResults = ArrayDeque(recoveryResults)
        val importCalls = AtomicInteger(0)

        @Synchronized
        override fun recoverOwnedStaging(): NarImportRecoveryResult =
            recoveryResults.removeFirstOrNull() ?: NarImportRecoveryResult.Clean

        override fun importDocument(
            selection: NarDocumentSelection,
            isCancelled: () -> Boolean,
            onInstallingProgress: (phase: String, completed: Long) -> Unit,
        ): ArchiveInstallResult {
            importCalls.incrementAndGet()
            return importResult
        }
    }
}
