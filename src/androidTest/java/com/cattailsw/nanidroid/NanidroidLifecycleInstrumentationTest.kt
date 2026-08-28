package com.cattailsw.nanidroid

import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.State
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ActivityScenario.ActivityAction
import androidx.test.core.app.ApplicationProvider
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.CoroutineContext

/** Real-device smoke coverage for main-activity launch and configuration recreation.  */
@RunWith(AndroidJUnit4::class)
class NanidroidLifecycleInstrumentationTest {
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

    @Test
    fun recreatingWhileInitialPreparationIsBlockedJoinsOneRuntimeOperation() {
        val application = ApplicationProvider.getApplicationContext<CatTailApplication>()
        val runtime = application.ghostRuntime
        requireResetSuccess(runtime)
        val preparationStarted = CountDownLatch(1)
        val allowPreparation = CountDownLatch(1)
        val operationId = AtomicLong(NO_OPERATION)
        val ghostId = AtomicReference<String?>()
        val prepareCount = AtomicInteger()
        val loadCount = AtomicInteger()
        val publishedGenerations = ConcurrentLinkedQueue<Long>()
        val activationCount = AtomicInteger()
        val bootEvents = ConcurrentLinkedQueue<String>()
        val outgoingUnloadCount = AtomicInteger()
        val hookToken = runtime.installTestHooksForTesting(
            GhostRuntimeTestHooks(
                onPreparationStarted = { operation, id, _ ->
                    operationId.compareAndSet(NO_OPERATION, operation)
                    ghostId.compareAndSet(null, id)
                    prepareCount.incrementAndGet()
                    preparationStarted.countDown()
                    check(
                        allowPreparation.await(
                            ACTIVITY_INIT_TIMEOUT_MILLIS,
                            TimeUnit.MILLISECONDS,
                        ),
                    )
                },
                onNativeLoadStarted = { operation, _ ->
                    if (operation == operationId.get()) loadCount.incrementAndGet()
                },
                onGenerationPublished = { generation, id ->
                    if (id == ghostId.get()) publishedGenerations.add(generation)
                },
                onActivationCommitted = { operation ->
                    if (operation == operationId.get()) activationCount.incrementAndGet()
                },
                onBootAttempted = { operation, event ->
                    if (operation == operationId.get()) bootEvents.add(event)
                },
                onOutgoingUnloaded = { outgoingUnloadCount.incrementAndGet() },
            ),
        )

        try {
            ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
                Assert.assertTrue(
                    "Runtime preparation did not reach the controlled boundary",
                    preparationStarted.await(
                        ACTIVITY_INIT_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS,
                    ),
                )
                val firstActivity = AtomicReference<Nanidroid?>()
                scenario.onActivity(firstActivity::set)

                scenario.recreate()

                scenario.onActivity { recreated ->
                    Assert.assertNotSame(firstActivity.get(), recreated)
                }
                allowPreparation.countDown()
                val attached = awaitActiveRuntime(scenario)

                Assert.assertEquals(1, prepareCount.get())
                Assert.assertEquals(1, loadCount.get())
                Assert.assertEquals(listOf(attached.nativeGeneration), publishedGenerations.toList())
                Assert.assertEquals(1, activationCount.get())
                Assert.assertEquals(1, bootEvents.size)
                Assert.assertTrue(bootEvents.single() in setOf("OnFirstBoot", "OnBoot"))
                Assert.assertEquals(0, outgoingUnloadCount.get())
                Assert.assertEquals(ghostId.get(), attached.ghost.id)
            }
        } finally {
            allowPreparation.countDown()
            hookToken.close()
            requireResetSuccess(runtime)
        }
    }

    @Test
    fun recreatingAfterOutgoingUnloadJoinsOneReplacementOperation() {
        val application = ApplicationProvider.getApplicationContext<CatTailApplication>()
        val runtime = application.ghostRuntime
        requireResetSuccess(runtime)
        val targetId = "runtime-recreation-${System.nanoTime()}"
        val targetRoot = java.io.File(application.cacheDir, targetId)
        val replacementOperation = AtomicLong(NO_OPERATION)
        val targetPreparationStarted = CountDownLatch(1)
        val allowTargetPreparation = CountDownLatch(1)
        val outgoingUnloaded = CountDownLatch(1)
        val targetPrepareCount = AtomicInteger()
        val targetLoadCount = AtomicInteger()
        val targetGenerations = ConcurrentLinkedQueue<Long>()
        val targetActivationCount = AtomicInteger()
        val targetBootEvents = ConcurrentLinkedQueue<String>()
        val outgoingUnloadCount = AtomicInteger()
        val switchResult = AtomicReference<RuntimeResult<GhostHandle>?>()
        val switchFailure = AtomicReference<Throwable?>()
        var switchThread: Thread? = null
        val hookToken = runtime.installTestHooksForTesting(
            GhostRuntimeTestHooks(
                onPreparationStarted = { operation, id, _ ->
                    if (operation == replacementOperation.get() && id == targetId) {
                        targetPrepareCount.incrementAndGet()
                        targetPreparationStarted.countDown()
                        check(
                            allowTargetPreparation.await(
                                ACTIVITY_INIT_TIMEOUT_MILLIS,
                                TimeUnit.MILLISECONDS,
                            ),
                        )
                    }
                },
                onNativeLoadStarted = { operation, _ ->
                    if (operation == replacementOperation.get()) targetLoadCount.incrementAndGet()
                },
                onGenerationPublished = { generation, id ->
                    if (id == targetId) targetGenerations.add(generation)
                },
                onActivationCommitted = { operation ->
                    if (operation == replacementOperation.get()) targetActivationCount.incrementAndGet()
                },
                onBootAttempted = { operation, event ->
                    if (operation == replacementOperation.get()) targetBootEvents.add(event)
                },
                onOutgoingUnloaded = { operation ->
                    if (operation == replacementOperation.get()) {
                        outgoingUnloadCount.incrementAndGet()
                        outgoingUnloaded.countDown()
                    }
                },
            ),
        )

        try {
            ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
                val outgoing = awaitActiveRuntime(scenario)
                Assert.assertTrue(
                    outgoing.ghost.canonicalRoot.copyRecursively(targetRoot, overwrite = true),
                )
                val operationId = requireSuccess(
                    runtime.beginSwitch(
                        outgoing.nativeGeneration,
                        targetId,
                        targetRoot,
                    ),
                )
                replacementOperation.set(operationId)
                val completionThread = Thread {
                    try {
                        switchResult.set(
                            runBlocking {
                                runtime.completeSwitchPlayback(
                                    outgoing.nativeGeneration,
                                    operationId,
                                )
                            },
                        )
                    } catch (failure: Throwable) {
                        switchFailure.set(failure)
                    }
                }
                switchThread = completionThread
                completionThread.start()

                Assert.assertTrue(
                    "Outgoing generation was not unloaded",
                    outgoingUnloaded.await(ACTIVITY_INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                )
                Assert.assertTrue(
                    "Replacement preparation did not reach the controlled boundary",
                    targetPreparationStarted.await(
                        ACTIVITY_INIT_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS,
                    ),
                )
                val firstActivity = AtomicReference<Nanidroid?>()
                scenario.onActivity(firstActivity::set)

                scenario.recreate()

                scenario.onActivity { recreated ->
                    Assert.assertNotSame(firstActivity.get(), recreated)
                }
                allowTargetPreparation.countDown()
                completionThread.join(ACTIVITY_INIT_TIMEOUT_MILLIS)
                Assert.assertFalse("Switch waiter did not terminate", completionThread.isAlive)
                switchFailure.get()?.let { throw AssertionError("Switch waiter failed", it) }
                val replacement = requireSuccess(requireNotNull(switchResult.get()))
                val attached = awaitActiveRuntime(scenario)

                Assert.assertEquals(targetId, replacement.ghost.id)
                Assert.assertEquals(replacement.generation, attached.nativeGeneration)
                Assert.assertNotEquals(outgoing.nativeGeneration, attached.nativeGeneration)
                Assert.assertEquals(1, targetPrepareCount.get())
                Assert.assertEquals(1, targetLoadCount.get())
                Assert.assertEquals(listOf(attached.nativeGeneration), targetGenerations.toList())
                Assert.assertEquals(1, targetActivationCount.get())
                Assert.assertEquals(listOf("OnFirstBoot"), targetBootEvents.toList())
                Assert.assertEquals(1, outgoingUnloadCount.get())
            }
        } finally {
            allowTargetPreparation.countDown()
            switchThread?.join(ACTIVITY_INIT_TIMEOUT_MILLIS)
            hookToken.close()
            requireResetSuccess(runtime)
            targetRoot.deleteRecursively()
        }
    }

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

    @Test
    fun startupRecoverySettlesBeforeTestCoordinatorReplacement() {
        val bootstrapDispatcher = QueuedDispatcher()
        val bootstrapBackend = RecordingForegroundNarBackend()
        val bootstrap = ForegroundNarImportCoordinator(
            bootstrapBackend,
            bootstrapDispatcher,
            "bootstrap-process",
        )
        bootstrapDispatcher.runNext()
        replaceStartupCoordinatorForTesting(bootstrap)
        ForegroundNarImportCoordinator.resetForTesting()

        val recoveryDispatcher = QueuedDispatcher()
        val recoveringBackend = RecordingForegroundNarBackend(
            recoveryResults = listOf(NarImportRecoveryResult.Cleaned),
        )
        val recovering = ForegroundNarImportCoordinator(
            recoveringBackend,
            recoveryDispatcher,
            "recovering-process",
        )
        ForegroundNarImportCoordinator.replaceForTesting(recovering)

        val replacementDispatcher = QueuedDispatcher()
        val replacement = ForegroundNarImportCoordinator(
            RecordingForegroundNarBackend(),
            replacementDispatcher,
            "replacement-process",
        )
        replacementDispatcher.runNext()
        val replacementFailure = AtomicReference<Throwable?>()
        val recoveringObserved = CountDownLatch(1)
        val replacementThread = Thread {
            try {
                replaceStartupCoordinatorForTesting(
                    replacement,
                    onRecovering = recoveringObserved::countDown,
                )
            } catch (failure: Throwable) {
                replacementFailure.set(failure)
            }
        }

        try {
            replacementThread.start()
            Assert.assertTrue(
                "Replacement helper did not observe startup recovery",
                recoveringObserved.await(ACTIVITY_INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
            )
            Assert.assertTrue(
                "Test coordinator replacement did not remain pending during startup recovery",
                replacementThread.isAlive,
            )

            recoveryDispatcher.runNext()
            replacementThread.join(ACTIVITY_INIT_TIMEOUT_MILLIS)

            Assert.assertFalse("Replacement thread did not terminate", replacementThread.isAlive)
            replacementFailure.get()?.let { throw AssertionError("Replacement failed", it) }
            Assert.assertSame(
                replacement,
                ForegroundNarImportCoordinator.get(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                ),
            )
        } finally {
            recoveryDispatcher.runNextIfPresent()
            returnCoordinatorToIdle(recovering, recoveryDispatcher, recoveringBackend)
            ForegroundNarImportCoordinator.resetForTesting()
        }
    }

    @Test
    fun sameProcessRecreationRestoresTheExactPickerOwnerWithoutRelaunching() {
        val dispatcher = QueuedDispatcher()
        val backend = RecordingForegroundNarBackend()
        val coordinator = ForegroundNarImportCoordinator(backend, dispatcher, "picker-process")
        dispatcher.runNext()
        replaceStartupCoordinatorForTesting(coordinator)

        try {
            ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
                lateinit var token: NarImportAttemptToken
                scenario.onActivity { activity ->
                    token = requireNotNull(coordinator.armPicker(activity.taskId))
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
    fun concurrentActivityReconciliationCannotCancelTheLiveOwnerResult() {
        val dispatcher = QueuedDispatcher()
        val backend = RecordingForegroundNarBackend()
        val coordinator = ForegroundNarImportCoordinator(backend, dispatcher, "picker-process")
        dispatcher.runNext()
        replaceStartupCoordinatorForTesting(coordinator)

        try {
            ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { firstScenario ->
                val token = requireNotNull(coordinator.armPicker())
                firstScenario.onActivity { activity ->
                    privateFieldHandle(activity, "narPickerOwnerToken").set(activity, token)
                    val concurrentOwner = reconcileNarPickerOwner(
                        restored = null,
                        state = coordinator.state.value,
                    )
                    Assert.assertNull(concurrentOwner)
                    Assert.assertEquals(
                        ForegroundNarImportState.AwaitingSelection(token),
                        coordinator.state.value,
                    )
                    val accepted = Nanidroid::class.java.getDeclaredMethod(
                        "dispatchNarDocumentPickerResult",
                        Uri::class.java,
                    ).apply { isAccessible = true }.invoke(
                        activity,
                        Uri.parse("content://archives/live-owner.nar"),
                    ) as Boolean

                    Assert.assertTrue(accepted)
                    Assert.assertNull(nullablePrivateField(activity, "narPickerOwnerToken"))
                }
                Assert.assertEquals(ForegroundNarImportState.Copying(token), coordinator.state.value)
                dispatcher.runNext()
                Assert.assertEquals(1, backend.importCalls.get())
                Assert.assertTrue(coordinator.state.value is ForegroundNarImportState.Interrupted)
                Assert.assertTrue(coordinator.acknowledge(token))
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
        replaceStartupCoordinatorForTesting(coordinator)

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
        replaceStartupCoordinatorForTesting(coordinator)

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
        replaceStartupCoordinatorForTesting(coordinator)

        try {
            ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val dialogState = privateField(activity, "simpleDialogState") as State<*>
                    val dialogBefore = dialogState.value
                    val restoredOwner = reconcileNarPickerOwner(
                        restored = NarImportAttemptToken("dead-process", 1),
                        state = coordinator.state.value,
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
        val application = ApplicationProvider.getApplicationContext<CatTailApplication>()
        ActivityScenario.launch<Nanidroid>(Nanidroid::class.java).use { scenario ->
            val before = awaitActiveRuntime(scenario)

            scenario.moveToState(Lifecycle.State.STARTED)

            scenario.onActivity { activity ->
                val runner = privateField(activity, "runner") as SScriptRunner
                val ghost = privateField(activity, "currentGhost") as Ghost
                val bootState = privateField(runner, "bootDispatchState")
                Assert.assertSame(before.runner, runner)
                Assert.assertSame(before.ghost, ghost)
                Assert.assertEquals(
                    before.nativeGeneration,
                    application.ghostRuntime.identity().activeHandle?.generation,
                )
                Assert.assertFalse(privateBoolean(bootState, "clockStarted"))
                Assert.assertTrue(privateBoolean(bootState, "bootDispatched"))
            }
        }
    }

    private fun replaceStartupCoordinatorForTesting(
        replacement: ForegroundNarImportCoordinator,
        onRecovering: () -> Unit = {},
    ) {
        val startup = ForegroundNarImportCoordinator.get(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        if (startup === replacement) return
        val deadline = SystemClock.uptimeMillis() + ACTIVITY_INIT_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            when (val state = startup.state.value) {
                ForegroundNarImportState.Recovering -> onRecovering()
                is ForegroundNarImportState.Cleaning -> Unit
                ForegroundNarImportState.Idle -> {
                    ForegroundNarImportCoordinator.replaceForTesting(replacement)
                    return
                }
                is ForegroundNarImportState.Interrupted -> startup.acknowledge(state.token)
                is ForegroundNarImportState.RecoveryRequired -> startup.retryCleanup(state.token)
                else -> throw AssertionError(
                    "Unexpected active production coordinator state before test replacement: $state",
                )
            }
            SystemClock.sleep(RUNNER_STATE_POLL_MILLIS)
        }
        throw AssertionError(
            "Production coordinator startup recovery did not settle before test replacement: " +
                startup.state.value,
        )
    }

    private fun awaitActiveRuntime(scenario: ActivityScenario<Nanidroid>): RuntimeIdentity {
        val runtime = ApplicationProvider
            .getApplicationContext<CatTailApplication>()
            .ghostRuntime
        val deadline = SystemClock.uptimeMillis() + ACTIVITY_INIT_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            var identity: RuntimeIdentity? = null
            scenario.onActivity { activity ->
                val initialized = privateField(activity, "initComplete") as Boolean
                val runner = nullablePrivateField(activity, "runner") as? SScriptRunner
                val ghost = nullablePrivateField(activity, "currentGhost") as? Ghost
                val runtimeIdentity = runtime.identity()
                val handle = runtimeIdentity.activeHandle
                if (
                    initialized &&
                    runner === runtime.runner &&
                    ghost != null &&
                    handle?.ghost === ghost &&
                    runtimeIdentity.phase == GhostRuntimePhase.Attached
                ) {
                    val bootState = privateField(runner, "bootDispatchState")
                    if (
                        privateBoolean(bootState, "clockStarted") &&
                        privateBoolean(bootState, "bootDispatched")
                    ) {
                        identity = RuntimeIdentity(runner, ghost, handle.generation)
                    }
                }
            }
            identity?.let { return it }
            SystemClock.sleep(RUNNER_STATE_POLL_MILLIS)
        }
        throw AssertionError("Nanidroid did not finish runtime initialization")
    }

    private fun requireResetSuccess(runtime: GhostRuntime) {
        val result = runtime.resetSessionForTesting()
        Assert.assertTrue("GhostRuntime reset failed: $result", result is RuntimeResult.Success)
    }

    private fun <T> requireSuccess(result: RuntimeResult<T>): T {
        Assert.assertTrue("GhostRuntime command failed: $result", result is RuntimeResult.Success)
        return (result as RuntimeResult.Success).value
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
        const val NO_OPERATION = -1L
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
