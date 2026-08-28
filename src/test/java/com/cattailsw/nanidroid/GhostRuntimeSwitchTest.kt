package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.shiori.LoadFailureState
import com.cattailsw.nanidroid.shiori.ShioriLoadResult
import com.cattailsw.nanidroid.shiori.ShioriUnloadResult
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GhostRuntimeSwitchTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun beginAndPreUnloadFailureAreOperationTaggedAndKeepOutgoingActive() = runBlocking {
        val outgoingRoot = root("pre-unload-outgoing")
        val targetRoot = root("pre-unload-target")
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence()
        val runtime = testRuntime(scriptedPreparer(), trace, persistence)

        runtime.use {
            val outgoing = start(runtime, outgoingRoot)
            val operationId = assertIs<RuntimeResult.Success<Long>>(
                runtime.beginSwitch(outgoing.generation, targetRoot.name, targetRoot),
            ).value
            assertEquals(
                GhostRuntimeIdentity(
                    activeHandle = outgoing,
                    pending = PendingGhostIdentity(operationId, targetRoot.name, targetRoot),
                    phase = GhostRuntimePhase.SwitchPlayback,
                ),
                runtime.identity(),
            )

            val authoredFailure = IllegalStateException("OnGhostChanging failed")
            assertSame(
                authoredFailure,
                assertIs<RuntimeFailure.Replayable>(
                    assertIs<RuntimeResult.Failure>(
                        runtime.failSwitchBeforeUnload(
                            outgoing.generation,
                            operationId,
                            authoredFailure,
                        ),
                    ).failure,
                ).cause,
            )
            assertEquals(
                GhostRuntimeIdentity(outgoing, null, GhostRuntimePhase.Unattached),
                runtime.identity(),
            )
            assertEquals(0, trace.unloadCount.get())
            assertEquals(listOf(outgoingRoot.name), persistence.lastRunWrites)
        }
    }

    @Test
    fun replacementPreparationIsRuntimeOwnedAndRecreationJoinsExactPendingTarget() = runBlocking {
        val outgoingRoot = root("recreation-outgoing")
        val targetRoot = root("recreation-target")
        val targetPreparationStarted = CountDownLatch(1)
        val releaseTargetPreparation = CountDownLatch(1)
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence()
        val runtime = testRuntime(
            GhostPreparer { operationId, ghostId, canonicalRoot ->
                if (ghostId == targetRoot.name) {
                    targetPreparationStarted.countDown()
                    assertTrue(releaseTargetPreparation.await(5, TimeUnit.SECONDS))
                }
                preparedGhost(operationId, ghostId, canonicalRoot)
            },
            trace,
            persistence,
        )

        runtime.use {
            val outgoing = start(runtime, outgoingRoot)
            val switchId = begin(runtime, outgoing, targetRoot)
            val completion = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.completeSwitchPlayback(outgoing.generation, switchId)
            }
            assertTrue(targetPreparationStarted.await(5, TimeUnit.SECONDS))
            assertEquals(
                GhostRuntimeIdentity(
                    activeHandle = null,
                    pending = PendingGhostIdentity(switchId, targetRoot.name, targetRoot),
                    phase = GhostRuntimePhase.Replacing,
                ),
                runtime.identity(),
            )
            assertEquals(targetRoot.name, runtime.identity().pending?.ghostId)
            assertEquals(outgoingRoot.name, runtime.preferredGhostId())

            val recreation = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.startOrJoin(targetRoot.name, targetRoot)
            }
            releaseTargetPreparation.countDown()
            val completedHandle = assertIs<RuntimeResult.Success<GhostHandle>>(completion.await()).value
            val recreatedHandle = assertIs<RuntimeResult.Success<GhostHandle>>(recreation.await()).value

            assertSame(completedHandle, recreatedHandle)
            assertEquals(1, trace.unloadCount.get())
            assertEquals(listOf(outgoingRoot.name, targetRoot.name), persistence.lastRunWrites)
        }
    }

    @Test
    fun successfulReturningReplacementAttachesWithExactOnGhostChangedIntent() = runBlocking {
        val outgoingRoot = root("returning-outgoing")
        val targetRoot = root("returning-target")
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence().apply {
            activationCounts[targetRoot.name] = 2L
        }
        val runtime = testRuntime(
            GhostPreparer { operationId, ghostId, canonicalRoot ->
                preparedGhost(
                    operationId,
                    ghostId,
                    canonicalRoot,
                    name = if (ghostId == outgoingRoot.name) "Outgoing Display" else "Target Display",
                )
            },
            trace,
            persistence,
        )

        runtime.use {
            val outgoing = start(runtime, outgoingRoot)
            val target = assertIs<RuntimeResult.Success<GhostHandle>>(
                runtime.completeSwitchPlayback(
                    outgoing.generation,
                    begin(runtime, outgoing, targetRoot),
                ),
            ).value
            trace.requests.clear()

            assertIs<RuntimeResult.Success<AttachmentReceipt>>(runtime.attachHost(target.generation))
            assertEquals(
                listOf(
                    "GET SHIORI/3.0\r\n" +
                        "Sender: Nanidroid\r\n" +
                        "ID: OnGhostChanged\r\n" +
                        "SecurityLevel: local\r\n" +
                        "Reference0: Outgoing Display\r\n" +
                        "Reference1: null\r\n\r\n",
                ),
                trace.requests,
            )
            assertEquals(listOf(targetRoot.name to 3L), persistence.activationWrites)
        }
    }

    @Test
    fun successfulFirstReplacementAttachesWithExactOnFirstBootIntent() = runBlocking {
        val outgoingRoot = root("first-outgoing")
        val targetRoot = root("first-target")
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence()
        val runtime = testRuntime(scriptedPreparer(), trace, persistence)

        runtime.use {
            val outgoing = start(runtime, outgoingRoot)
            val target = assertIs<RuntimeResult.Success<GhostHandle>>(
                runtime.completeSwitchPlayback(
                    outgoing.generation,
                    begin(runtime, outgoing, targetRoot),
                ),
            ).value
            trace.requests.clear()
            assertIs<RuntimeResult.Success<AttachmentReceipt>>(runtime.attachHost(target.generation))

            assertEquals(
                listOf(
                    "GET SHIORI/3.0\r\n" +
                        "Sender: Nanidroid\r\n" +
                        "ID: OnFirstBoot\r\n" +
                        "SecurityLevel: local\r\n" +
                        "Reference0: 0\r\n\r\n",
                ),
                trace.requests,
            )
        }
    }

    @Test
    fun staleAndDuplicateCompletionNeverUnloadOrMutateTheAcceptedReplacement() = runBlocking {
        val outgoingRoot = root("duplicate-outgoing")
        val targetRoot = root("duplicate-target")
        val trace = RecordingShioriTrace()
        val runtime = testRuntime(scriptedPreparer(), trace)

        runtime.use {
            val outgoing = start(runtime, outgoingRoot)
            val switchId = begin(runtime, outgoing, targetRoot)
            val before = runtime.identity()
            assertIs<RuntimeFailure.StaleGeneration>(
                assertIs<RuntimeResult.Failure>(
                    runtime.completeSwitchPlayback(outgoing.generation + 1, switchId),
                ).failure,
            )
            assertEquals(before, runtime.identity())
            assertEquals(0, trace.unloadCount.get())

            val target = assertIs<RuntimeResult.Success<GhostHandle>>(
                runtime.completeSwitchPlayback(outgoing.generation, switchId),
            ).value
            val accepted = runtime.identity()
            assertIs<RuntimeFailure.StaleGeneration>(
                assertIs<RuntimeResult.Failure>(
                    runtime.completeSwitchPlayback(outgoing.generation, switchId),
                ).failure,
            )
            assertEquals(accepted, runtime.identity())
            assertSame(target, runtime.identity().activeHandle)
            assertEquals(1, trace.unloadCount.get())
        }
    }

    @Test
    fun duplicateOldGenerationUnloadCannotTouchActiveReplacement() = runBlocking {
        val outgoingRoot = root("retired-outgoing")
        val targetRoot = root("retired-target")
        val trace = RecordingShioriTrace()
        val runtime = testRuntime(scriptedPreparer(), trace)

        runtime.use {
            val outgoing = start(runtime, outgoingRoot)
            val target = assertIs<RuntimeResult.Success<GhostHandle>>(
                runtime.completeSwitchPlayback(
                    outgoing.generation,
                    begin(runtime, outgoing, targetRoot),
                ),
            ).value
            assertEquals(1, trace.unloadCount.get())

            assertIs<RuntimeResult.Success<Unit>>(runtime.unload(outgoing.generation))
            assertEquals(1, trace.unloadCount.get())
            assertSame(target, runtime.identity().activeHandle)
            assertIs<RuntimeResult.Success<TaggedShioriResponse>>(
                runtime.request(target.generation, ShioriRequestIntent.event("OnBoot")),
            )
            assertIs<RuntimeFailure.StaleGeneration>(
                assertIs<RuntimeResult.Failure>(runtime.unload(target.generation + 1L)).failure,
            )
            assertEquals(1, trace.unloadCount.get())
            assertSame(target, runtime.identity().activeHandle)

            trace.unloadResults += ShioriUnloadResult.Failed(
                IllegalStateException("replacement teardown failed"),
                ownershipCertain = false,
            )
            assertIs<RuntimeFailure.Fatal>(
                assertIs<RuntimeResult.Failure>(runtime.unload(target.generation)).failure,
            )
            assertIs<RuntimeFailure.Fatal>(
                assertIs<RuntimeResult.Failure>(runtime.unload(outgoing.generation)).failure,
            )
            assertEquals(2, trace.unloadCount.get())
        }

        assertEquals(2, trace.unloadCount.get())
    }

    @Test
    fun outgoingUnloadFailurePoisonsWithoutStartingOrClosingTarget() = runBlocking {
        val outgoingRoot = root("unload-poison-outgoing")
        val targetRoot = root("unload-poison-target")
        val prepareCount = AtomicInteger()
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence()
        val runtime = testRuntime(
            GhostPreparer { operationId, ghostId, canonicalRoot ->
                prepareCount.incrementAndGet()
                preparedGhost(operationId, ghostId, canonicalRoot)
            },
            trace,
            persistence,
        )
        val outgoing = start(runtime, outgoingRoot)
        trace.unloadResults += ShioriUnloadResult.Failed(
            IllegalStateException("outgoing teardown failed"),
            ownershipCertain = false,
        )

        assertIs<RuntimeFailure.Fatal>(
            assertIs<RuntimeResult.Failure>(
                runtime.completeSwitchPlayback(
                    outgoing.generation,
                    begin(runtime, outgoing, targetRoot),
                ),
            ).failure,
        )
        assertEquals(GhostRuntimePhase.Poisoned, runtime.identity().phase)
        assertSame(outgoing, runtime.identity().activeHandle)
        assertEquals(1, prepareCount.get())
        assertEquals(listOf(outgoingRoot.name), persistence.lastRunWrites)
        runtime.close()
        assertEquals(1, trace.unloadCount.get())
    }

    @Test
    fun acceptedSwitchReportsFatalWhenPriorNativeCommandPoisonsBeforeUnload() = runBlocking {
        val outgoingRoot = root("concurrent-poison-outgoing")
        val targetRoot = root("concurrent-poison-target")
        val requestStarted = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val trace = RecordingShioriTrace()
        val runtime = testRuntime(scriptedPreparer(), trace)

        runtime.use {
            val outgoing = start(runtime, outgoingRoot)
            trace.requestHandler.set {
                requestStarted.countDown()
                assertTrue(releaseRequest.await(5, TimeUnit.SECONDS))
                throw com.cattailsw.nanidroid.shiori.ShioriRequestException(
                    "ownership became uncertain",
                    ownershipCertain = false,
                )
            }
            val request = async(Dispatchers.Default) {
                runtime.request(outgoing.generation, ShioriRequestIntent.event("OnConcurrentRequest"))
            }
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
            val operationId = begin(runtime, outgoing, targetRoot)
            val completion = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.completeSwitchPlayback(outgoing.generation, operationId)
            }

            releaseRequest.countDown()

            assertIs<RuntimeFailure.Fatal>(assertIs<RuntimeResult.Failure>(request.await()).failure)
            assertIs<RuntimeFailure.Fatal>(assertIs<RuntimeResult.Failure>(completion.await()).failure)
            assertEquals(GhostRuntimePhase.Poisoned, runtime.identity().phase)
            assertEquals(0, trace.unloadCount.get())
        }
        assertEquals(0, trace.unloadCount.get())
    }

    @Test
    fun targetPreparationFailureClearsSwitchAndPreservesPriorPreference() = runBlocking {
        val outgoingRoot = root("prep-failure-outgoing")
        val targetRoot = root("prep-failure-target")
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence()
        val runtime = testRuntime(
            GhostPreparer { operationId, ghostId, canonicalRoot ->
                if (ghostId == targetRoot.name) error("target preparation failed")
                preparedGhost(operationId, ghostId, canonicalRoot)
            },
            trace,
            persistence,
        )

        runtime.use {
            val outgoing = start(runtime, outgoingRoot)
            assertIs<RuntimeFailure.Replayable>(
                assertIs<RuntimeResult.Failure>(
                    runtime.completeSwitchPlayback(
                        outgoing.generation,
                        begin(runtime, outgoing, targetRoot),
                    ),
                ).failure,
            )
            assertEquals(GhostRuntimeIdentity(null, null, GhostRuntimePhase.Idle), runtime.identity())
            assertEquals(listOf(outgoingRoot.name), persistence.lastRunWrites)
            assertEquals(1, trace.loadCount.get())
        }
    }

    @Test
    fun targetLoadTerminalsPreservePriorPreferenceAndCleanupExactlyOnce() = runBlocking {
        val states = listOf(
            LoadFailureState.ProvenEmpty to false,
            LoadFailureState.CleanupRequired to true,
        )
        states.forEachIndexed { index, (state, expectsCleanup) ->
            val outgoingRoot = root("load-terminal-$index-outgoing")
            val targetRoot = root("load-terminal-$index-target")
            val trace = RecordingShioriTrace()
            val persistence = InMemoryGhostRuntimePersistence()
            val runtime = testRuntime(scriptedPreparer(), trace, persistence)
            try {
                val outgoing = start(runtime, outgoingRoot)
                trace.loadResults += ShioriLoadResult.Failed(
                    IllegalStateException("target load failed"),
                    state,
                )
                assertIs<RuntimeFailure.Replayable>(
                    assertIs<RuntimeResult.Failure>(
                        runtime.completeSwitchPlayback(
                            outgoing.generation,
                            begin(runtime, outgoing, targetRoot),
                        ),
                    ).failure,
                )
                assertEquals(GhostRuntimePhase.Idle, runtime.identity().phase)
                assertEquals(listOf(outgoingRoot.name), persistence.lastRunWrites)
                assertEquals(if (expectsCleanup) 2 else 1, trace.unloadCount.get())
            } finally {
                runtime.close()
            }
        }
    }

    @Test
    fun targetOwnerAlreadyPresentPoisonsWithoutTouchingForeignOwnerOrPreference() = runBlocking {
        val outgoingRoot = root("target-owner-outgoing")
        val targetRoot = root("target-owner-target")
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence()
        val runtime = testRuntime(scriptedPreparer(), trace, persistence)

        val outgoing = start(runtime, outgoingRoot)
        trace.loadResults += ShioriLoadResult.Failed(
            IllegalStateException("target owner already present"),
            LoadFailureState.OwnerAlreadyPresent,
        )

        assertIs<RuntimeFailure.Fatal>(
            assertIs<RuntimeResult.Failure>(
                runtime.completeSwitchPlayback(
                    outgoing.generation,
                    begin(runtime, outgoing, targetRoot),
                ),
            ).failure,
        )
        assertEquals(GhostRuntimePhase.Poisoned, runtime.identity().phase)
        assertEquals(listOf(outgoingRoot.name), persistence.lastRunWrites)
        runtime.close()
        assertEquals(1, trace.unloadCount.get())
    }

    @Test
    fun failedTargetCleanupPoisonsAndPreservesPriorPreference() = runBlocking {
        val outgoingRoot = root("target-cleanup-poison-outgoing")
        val targetRoot = root("target-cleanup-poison-target")
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence()
        val runtime = testRuntime(scriptedPreparer(), trace, persistence)
        val outgoing = start(runtime, outgoingRoot)
        trace.loadResults += ShioriLoadResult.Failed(
            IllegalStateException("target partial load"),
            LoadFailureState.CleanupRequired,
        )
        trace.unloadResults += ShioriUnloadResult.Unloaded
        trace.unloadResults += ShioriUnloadResult.Failed(
            IllegalStateException("target cleanup failed"),
            ownershipCertain = false,
        )

        assertIs<RuntimeFailure.Fatal>(
            assertIs<RuntimeResult.Failure>(
                runtime.completeSwitchPlayback(
                    outgoing.generation,
                    begin(runtime, outgoing, targetRoot),
                ),
            ).failure,
        )
        assertEquals(GhostRuntimePhase.Poisoned, runtime.identity().phase)
        assertEquals(listOf(outgoingRoot.name), persistence.lastRunWrites)
        runtime.close()
        assertEquals(2, trace.unloadCount.get())
    }

    private suspend fun start(runtime: GhostRuntime, root: File): GhostHandle =
        assertIs<RuntimeResult.Success<GhostHandle>>(
            runtime.startOrJoin(root.name, root),
        ).value

    private fun begin(runtime: GhostRuntime, outgoing: GhostHandle, targetRoot: File): Long =
        assertIs<RuntimeResult.Success<Long>>(
            runtime.beginSwitch(outgoing.generation, targetRoot.name, targetRoot),
        ).value

    private fun root(name: String): File = File("build/ghost-runtime-switch-test/$name").canonicalFile
}
