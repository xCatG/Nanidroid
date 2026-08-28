package com.cattailsw.nanidroid

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GhostRuntimeAttachmentTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun initialFirstActivationCommitsOnceAndUsesLiteralOnFirstBootIntent() = runBlocking {
        val root = root("initial-first")
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence()
        val admissions = CopyOnWriteArrayList<AdmissionRecord>()
        val runtime = runtime(trace, persistence) { operationId, handle, outcome ->
            admissions += AdmissionRecord(operationId, handle, outcome)
            RuntimeResult.Success(Unit)
        }

        runtime.use {
            val handle = start(runtime, root)
            trace.requests.clear()
            val receipt = assertIs<RuntimeResult.Success<AttachmentReceipt>>(
                runtime.attachHost(handle.generation),
            ).value

            assertIs<AttachmentReceipt.NewlyAttached>(receipt)
            assertEquals(listOf(root.name to 1L), persistence.activationWrites)
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
            assertEquals(1, admissions.size)
            assertSame(handle, admissions.single().handle)
            assertIs<BootOutcome.Response>(admissions.single().outcome)
            assertEquals(GhostRuntimePhase.Attached, runtime.identity().phase)
        }
    }

    @Test
    fun initialReturningActivationUsesLiteralOnBootShellIntent() = runBlocking {
        val root = root("initial-returning")
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence().apply {
            activationCounts[root.name] = 4L
        }
        val runtime = GhostRuntime.testRuntime(
            context = null,
            preparer = GhostPreparer { operationId, ghostId, canonicalRoot ->
                preparedGhost(
                    operationId,
                    ghostId,
                    canonicalRoot,
                    shellName = "Custom Shell",
                )
            },
            adapterFactory = { prepared -> RecordingShiori(trace, prepared.id) },
            persistence = persistence,
        )

        runtime.use {
            val handle = start(runtime, root)
            trace.requests.clear()
            assertIs<RuntimeResult.Success<AttachmentReceipt>>(runtime.attachHost(handle.generation))

            assertEquals(listOf(root.name to 5L), persistence.activationWrites)
            assertEquals(
                listOf(
                    "GET SHIORI/3.0\r\n" +
                        "Sender: Nanidroid\r\n" +
                        "ID: OnBoot\r\n" +
                        "SecurityLevel: local\r\n" +
                        "Reference0: Custom Shell\r\n\r\n",
                ),
                trace.requests,
            )
        }
    }

    @Test
    fun attachRetryReusesCachedBootOutcomeWithoutRepeatingActivationOrBoot() = runBlocking {
        val root = root("retry")
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence()
        val admissionAttempts = AtomicInteger()
        val records = CopyOnWriteArrayList<AdmissionRecord>()
        val runtime = runtime(trace, persistence) { operationId, handle, outcome ->
            records += AdmissionRecord(operationId, handle, outcome)
            if (admissionAttempts.incrementAndGet() == 1) error("admission interrupted")
            RuntimeResult.Success(Unit)
        }

        runtime.use {
            val handle = start(runtime, root)
            trace.requests.clear()
            assertIs<RuntimeFailure.Replayable>(
                assertIs<RuntimeResult.Failure>(runtime.attachHost(handle.generation)).failure,
            )
            assertEquals(GhostRuntimePhase.Attaching, runtime.identity().phase)

            val retried = assertIs<RuntimeResult.Success<AttachmentReceipt>>(
                runtime.attachHost(handle.generation),
            ).value
            val operationId = assertIs<AttachmentReceipt.NewlyAttached>(retried).operationId

            assertEquals(1, persistence.activationWrites.size)
            assertEquals(1, trace.requests.size)
            assertEquals(2, records.size)
            assertEquals(operationId, records[0].operationId)
            assertEquals(operationId, records[1].operationId)
            assertSame(handle, records[0].handle)
            assertSame(handle, records[1].handle)
            assertSame(records[0].outcome, records[1].outcome)
            assertEquals(GhostRuntimePhase.Attached, runtime.identity().phase)
        }
    }

    @Test
    fun concurrentHostsJoinOneAttachmentOperation() = runBlocking {
        val root = root("join")
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence()
        val admissionStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseAdmission = java.util.concurrent.CountDownLatch(1)
        val admissionCount = AtomicInteger()
        val runtime = runtime(trace, persistence) { _, _, _ ->
            admissionCount.incrementAndGet()
            admissionStarted.complete(Unit)
            assertTrue(releaseAdmission.await(5, java.util.concurrent.TimeUnit.SECONDS))
            RuntimeResult.Success(Unit)
        }

        runtime.use {
            val handle = start(runtime, root)
            trace.requests.clear()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.attachHost(handle.generation)
            }
            admissionStarted.await()
            val joiner = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.attachHost(handle.generation)
            }
            releaseAdmission.countDown()
            val firstReceipt = assertIs<AttachmentReceipt.NewlyAttached>(
                assertIs<RuntimeResult.Success<AttachmentReceipt>>(first.await()).value,
            )
            val joinedReceipt = assertIs<AttachmentReceipt.NewlyAttached>(
                assertIs<RuntimeResult.Success<AttachmentReceipt>>(joiner.await()).value,
            )

            assertEquals(firstReceipt.operationId, joinedReceipt.operationId)
            assertEquals(1, admissionCount.get())
            assertEquals(1, persistence.activationWrites.size)
            assertEquals(1, trace.requests.size)
        }
    }

    @Test
    fun resetRetiresBlockedAttachmentAttemptBeforeAdmissionReturns() = runBlocking {
        val root = root("reset-blocked-admission")
        val trace = RecordingShioriTrace()
        val admissionStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseAdmission = java.util.concurrent.CountDownLatch(1)
        val runtime = runtime(trace, InMemoryGhostRuntimePersistence()) { _, _, _ ->
            admissionStarted.complete(Unit)
            assertTrue(releaseAdmission.await(5, java.util.concurrent.TimeUnit.SECONDS))
            RuntimeResult.Success(Unit)
        }
        val handle = start(runtime, root)
        trace.requests.clear()
        val attachment = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.attachHost(handle.generation)
        }

        try {
            admissionStarted.await()

            assertIs<RuntimeResult.Success<Unit>>(runtime.resetSessionForTesting())
            assertIs<RuntimeFailure.StaleGeneration>(
                assertIs<RuntimeResult.Failure>(
                    withTimeout(1_000) { attachment.await() },
                ).failure,
            )
            assertEquals(GhostRuntimePhase.Idle, runtime.identity().phase)
        } finally {
            releaseAdmission.countDown()
            attachment.join()
            runtime.close()
        }
    }

    @Test
    fun failedActivationCommitIsRetainedAndNotRepeatedByAdmissionRetry() = runBlocking {
        val root = root("persistence-failure")
        val trace = RecordingShioriTrace()
        val persistence = FailingActivationPersistence()
        val admissionAttempts = AtomicInteger()
        val runtime = GhostRuntime.testRuntime(
            context = null,
            preparer = GhostPreparer { operationId, ghostId, canonicalRoot ->
                preparedGhost(operationId, ghostId, canonicalRoot)
            },
            adapterFactory = { prepared -> RecordingShiori(trace, prepared.id) },
            persistence = persistence,
            admission = AttachmentAdmission { _, _, _ ->
                if (admissionAttempts.incrementAndGet() == 1) error("retry admission")
                RuntimeResult.Success(Unit)
            },
        )

        runtime.use {
            val handle = start(runtime, root)
            trace.requests.clear()
            assertIs<RuntimeResult.Failure>(runtime.attachHost(handle.generation))
            assertIs<RuntimeResult.Success<AttachmentReceipt>>(runtime.attachHost(handle.generation))

            assertEquals(1, persistence.readCount.get())
            assertEquals(1, persistence.commitCount.get())
            assertEquals(1, trace.requests.size)
            assertEquals(2, admissionAttempts.get())
        }
    }

    @Test
    fun certainBootFailureAttachesWithTypedNoScriptOutcome() = runBlocking {
        val root = root("boot-failed")
        val trace = RecordingShioriTrace()
        val outcome = AtomicReference<BootOutcome?>()
        val runtime = runtime(trace, InMemoryGhostRuntimePersistence()) { _, _, admitted ->
            outcome.set(admitted)
            RuntimeResult.Success(Unit)
        }

        runtime.use {
            val handle = start(runtime, root)
            trace.requests.clear()
            trace.requestFailure.set(
                com.cattailsw.nanidroid.shiori.ShioriRequestException(
                    "known boot failure",
                    ownershipCertain = true,
                ),
            )
            assertIs<RuntimeResult.Success<AttachmentReceipt>>(runtime.attachHost(handle.generation))

            assertIs<BootOutcome.BootAttemptFailed>(outcome.get())
            assertEquals(GhostRuntimePhase.Attached, runtime.identity().phase)
            assertEquals(1, trace.requests.size)
        }
    }

    @Test
    fun staleAndDuplicateAttachmentCallsChangeNoState() = runBlocking {
        val root = root("stale-duplicate")
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence()
        val admissionCount = AtomicInteger()
        val runtime = runtime(trace, persistence) { _, _, _ ->
            admissionCount.incrementAndGet()
            RuntimeResult.Success(Unit)
        }

        runtime.use {
            val handle = start(runtime, root)
            trace.requests.clear()
            val before = runtime.identity()
            assertIs<RuntimeFailure.StaleGeneration>(
                assertIs<RuntimeResult.Failure>(runtime.attachHost(handle.generation + 1)).failure,
            )
            assertEquals(before, runtime.identity())
            assertTrue(persistence.activationWrites.isEmpty())
            assertTrue(trace.requests.isEmpty())

            assertIs<AttachmentReceipt.NewlyAttached>(
                assertIs<RuntimeResult.Success<AttachmentReceipt>>(
                    runtime.attachHost(handle.generation),
                ).value,
            )
            val attachedIdentity = runtime.identity()
            assertIs<AttachmentReceipt.AlreadyAttached>(
                assertIs<RuntimeResult.Success<AttachmentReceipt>>(
                    runtime.attachHost(handle.generation),
                ).value,
            )
            assertEquals(attachedIdentity, runtime.identity())
            assertEquals(1, admissionCount.get())
            assertEquals(1, persistence.activationWrites.size)
            assertEquals(1, trace.requests.size)
        }
    }

    @Test
    fun defaultAttachmentAdmissionBindsTheRuntimeOwnedRunnerRequestPort() = runBlocking {
        val root = root("runner-admission")
        val trace = RecordingShioriTrace().apply {
            requestHandler.set { request ->
                if ("ID: OnProbe\r\n" in request) {
                    "SHIORI/3.0 200 OK\r\nValue: \\hattached\\e\r\n\r\n"
                } else {
                    "SHIORI/3.0 204 No Content\r\n\r\n"
                }
            }
        }
        val runtime = GhostRuntime.testRuntime(
            context = null,
            preparer = scriptedPreparer(),
            adapterFactory = { prepared -> RecordingShiori(trace, prepared.id) },
            persistence = InMemoryGhostRuntimePersistence(),
        )

        runtime.use {
            val handle = start(runtime, root)
            assertIs<RuntimeResult.Success<AttachmentReceipt>>(
                runtime.attachHost(handle.generation),
            )
            trace.requests.clear()
            runtime.runner.setNoWaitMode(true)

            assertTrue(runtime.runner.doShioriEvent("OnProbe", null))
            assertEquals(
                listOf(
                    "GET SHIORI/3.0\r\n" +
                        "Sender: Nanidroid\r\n" +
                        "ID: OnProbe\r\n" +
                        "SecurityLevel: local\r\n\r\n",
                ),
                trace.requests,
            )
        }
    }

    private fun runtime(
        trace: RecordingShioriTrace,
        persistence: InMemoryGhostRuntimePersistence,
        admission: AttachmentAdmission = AttachmentAdmission { _, _, _ -> RuntimeResult.Success(Unit) },
    ): GhostRuntime = testRuntime(scriptedPreparer(), trace, persistence, admission)

    private suspend fun start(runtime: GhostRuntime, root: File): GhostHandle =
        assertIs<RuntimeResult.Success<GhostHandle>>(
            runtime.startOrJoin(root.name, root),
        ).value

    private fun root(name: String): File = File("build/ghost-runtime-attachment-test/$name").canonicalFile

    private data class AdmissionRecord(
        val operationId: Long,
        val handle: GhostHandle,
        val outcome: BootOutcome,
    )

    private class FailingActivationPersistence : GhostRuntimePersistence {
        val readCount = AtomicInteger()
        val commitCount = AtomicInteger()

        override fun readLastRunGhostId(): String? = null
        override fun commitLastRunGhostId(ghostId: String) = Unit
        override fun readActivationCount(ghostId: String): Long {
            readCount.incrementAndGet()
            return 0L
        }
        override fun commitActivationCount(ghostId: String, count: Long) {
            commitCount.incrementAndGet()
            error("persistence unavailable")
        }
    }
}
