package com.cattailsw.nanidroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.State
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ActivityScenario.ActivityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.cattailsw.nanidroid.install.NarDownload
import com.cattailsw.nanidroid.install.NarDownloadSource
import com.cattailsw.nanidroid.install.NarUserEnqueueResult
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.concurrent.thread

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
    fun recreatedActivityWithNullRunnerRejectsArchiveIntentWhenRetainedRunnerIsPassive() {
        val retainedRunner = SScriptRunner.getInstance(null)
        val archiveIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://archives/recreated.nar"), "application/x-nar")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            // Keep cleanup active while preparing the process-global runner: either
            // wait below can time out after no-wait or passive mode has changed.
            retainedRunner.run {
                setNoWaitMode(true)
                // The process-global runner may still be mid-playback -- or have messages
                // still queued -- from a preceding instrumentation test in this same app
                // process. run() silently no-ops while state.running is already true
                // (SScriptRunner.run()), so the passive-mode command below would never
                // execute and this test would become order-dependent. Force the runner
                // fully idle first (stopping playback AND draining any queued messages),
                // then wait for the passive-mode command to actually take effect.
                forceRunnerIdle(this)
                addMsgToQueue(arrayOf("\\![enter,passivemode]\\e"))
                run()
                awaitRunnerState(this) { it.passive }
            }
            ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val runnerField = privateFieldHandle(activity, "runner")
                    // initOnSeparateThread()'s lifecycle coroutine may still be preparing a
                    // ghost. Null the field only for this reflective call and restore it
                    // immediately, so the initialization completion never observes null.
                    val originalRunner = runnerField.get(activity)
                    try {
                        runnerField.set(activity, null)
                        Nanidroid::class.java.getDeclaredMethod(
                            "handleIncomingIntent",
                            Intent::class.java,
                            Boolean::class.javaPrimitiveType,
                        ).apply {
                            isAccessible = true
                            invoke(activity, archiveIntent, false)
                        }
                    } finally {
                        runnerField.set(activity, originalRunner)
                    }

                    val state = privateField(activity, "archiveIntentState") as ArchiveIntentState

                    Assert.assertSame(retainedRunner, SScriptRunner.getInstance(null))
                    Assert.assertNull(state.pendingUri)
                }
            }
        } finally {
            // Activity initialization above may have left the retained runner running
            // (or paused mid dialogue action) rather than sitting idle in passive mode:
            // closing ActivityScenario stops the clock but does not stop playback, and
            // run() silently no-ops while playback is already active. Force idle again
            // before queuing the cleanup command so it is guaranteed to execute, then
            // wait for passive mode to actually clear -- otherwise the singleton stays
            // passive and contaminates later instrumentation tests in this process.
            forceRunnerIdle(retainedRunner)
            retainedRunner.addMsgToQueue(arrayOf("\\![leave,passivemode]\\e"))
            retainedRunner.run()
            awaitRunnerState(retainedRunner) { !it.passive }
            retainedRunner.setNoWaitMode(false)
        }
    }

    @Test
    fun acceptedArchiveWorkDefersNotificationPermissionUntilStartedActivityResumes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        assumeTrue(
            "Run this gate on a clean emulator where notification permission is denied",
            instrumentation.targetContext.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED,
        )

        ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.onActivity { activity ->
                invokePrivate(
                    activity,
                    "handleAcceptedNarUserEnqueueResult",
                    NarUserEnqueueResult::class.java,
                    NarUserEnqueueResult(
                        download = NarDownload(
                            id = "accepted-local-import",
                            source = NarDownloadSource.Local("content://archives/accepted.nar"),
                        ),
                        acceptedActive = true,
                    ),
                )
                Assert.assertTrue(pendingNotificationPermission(activity))
            }

            val sawPermissionDialog = AtomicBoolean(false)
            val permissionHandler = thread(name = "nanidroid-permission-dialog-dismissal") {
                val device = UiDevice.getInstance(instrumentation)
                val deadline = SystemClock.uptimeMillis() + PERMISSION_DIALOG_TIMEOUT_MILLIS
                while (SystemClock.uptimeMillis() < deadline) {
                    if (PERMISSION_CONTROLLER_PACKAGES.any { device.hasObject(By.pkg(it)) }) {
                        sawPermissionDialog.set(true)
                        device.pressBack()
                        return@thread
                    }
                    SystemClock.sleep(PERMISSION_DIALOG_POLL_MILLIS)
                }
            }

            scenario.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            permissionHandler.join(PERMISSION_DIALOG_TIMEOUT_MILLIS)
            Assert.assertTrue("Notification permission dialog was not launched", sawPermissionDialog.get())
            scenario.onActivity { activity ->
                Assert.assertFalse(pendingNotificationPermission(activity))
            }
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
                onForegroundNarRefresh = { manager, _ ->
                    if (manager === replacementManager.get()) refreshCount.incrementAndGet()
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
                awaitInstalledReady(scenario, token)
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
                scenario.onActivity { activity ->
                    Assert.assertEquals(token, nullablePrivateField(activity, "installedReadyToken"))
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

    @Test
    fun invalidInitialFileAndHttpArchiveIntentsAreIgnored() {
        INVALID_ARCHIVE_URIS.forEach { uri ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClass(InstrumentationRegistry.getInstrumentation().targetContext, Nanidroid::class.java)
                setDataAndType(uri, "application/x-nar")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ActivityScenario.launch<Nanidroid>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertNoPendingArchive(activity)
                }
            }
        }
    }

    @Test
    fun invalidWarmFileAndHttpArchiveIntentsAreIgnoredAndBecomeCurrentIntent() {
        ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
            INVALID_ARCHIVE_URIS.forEach { uri ->
                scenario.onActivity { activity ->
                    val incoming = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/x-nar")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    invokePrivate(activity, "onNewIntent", Intent::class.java, incoming)
                    Assert.assertSame(incoming, activity.intent)
                    assertNoPendingArchive(activity)
                }
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

    private fun awaitInstalledReady(
        scenario: ActivityScenario<Nanidroid>,
        token: NarImportAttemptToken,
    ) {
        val deadline = SystemClock.uptimeMillis() + ACTIVITY_INIT_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                ready = nullablePrivateField(activity, "installedReadyToken") == token
            }
            if (ready) return
            SystemClock.sleep(RUNNER_STATE_POLL_MILLIS)
        }
        throw AssertionError("Installed publication never crossed GhostMgr readiness")
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

    private fun pendingNotificationPermission(activity: Nanidroid): Boolean =
        privateField(activity, "pendingDurableNotificationPermission") as Boolean

    private fun assertNoPendingArchive(activity: Nanidroid) {
        val state = privateField(activity, "archiveIntentState") as ArchiveIntentState
        Assert.assertNull(state.pendingUri)
        Assert.assertNull(state.consumedUri)
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

    private fun invokePrivate(instance: Any, name: String, parameter: Class<*>, argument: Any) {
        instance.javaClass.getDeclaredMethod(name, parameter).apply { isAccessible = true }
            .invoke(instance, argument)
    }

    /**
     * Forces the process-global runner into a fully idle state: stops any in-flight playback
     * AND drains any messages left queued by a preceding instrumentation test. stop() alone
     * leaves a non-empty msgQueue behind, which keeps runtimeModeSnapshot().playingTalk true
     * and makes any subsequent wait for idle time out. clearMsgQueue() clears the queue and
     * stops playback in one call, so it is used here instead of stop() alone.
     */
    private fun forceRunnerIdle(runner: SScriptRunner) {
        runner.clearMsgQueue()
        awaitRunnerState(runner) { !it.playingTalk }
    }

    /**
     * Polls the process-global runner's mode snapshot until [predicate] holds, or fails the
     * test if it never converges. Mirrors the deadline/poll idiom already used above for the
     * notification-permission dialog dismissal wait.
     */
    private fun awaitRunnerState(
        runner: SScriptRunner,
        predicate: (com.cattailsw.nanidroid.runtime.dialogue.GhostRuntimeMode) -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + RUNNER_STATE_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate(runner.runtimeModeSnapshot())) return
            SystemClock.sleep(RUNNER_STATE_POLL_MILLIS)
        }
        Assert.assertTrue(
            "Retained runner did not converge to the expected state within " +
                "${RUNNER_STATE_TIMEOUT_MILLIS}ms",
            predicate(runner.runtimeModeSnapshot()),
        )
    }

    private companion object {
        const val PERMISSION_DIALOG_TIMEOUT_MILLIS = 15_000L
        const val PERMISSION_DIALOG_POLL_MILLIS = 50L
        const val RUNNER_STATE_TIMEOUT_MILLIS = 5_000L
        const val RUNNER_STATE_POLL_MILLIS = 20L
        const val ACTIVITY_INIT_TIMEOUT_MILLIS = 30_000L
        val INVALID_ARCHIVE_URIS = listOf(
            Uri.parse("file:///sdcard/Download/invalid.nar"),
            Uri.parse("http://example.test/invalid.nar"),
        )
        val PERMISSION_CONTROLLER_PACKAGES = listOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
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
