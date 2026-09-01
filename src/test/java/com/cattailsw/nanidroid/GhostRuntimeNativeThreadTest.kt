package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities
import com.cattailsw.nanidroid.runtime.dialogue.Support
import com.cattailsw.nanidroid.shiori.LoadFailureState
import com.cattailsw.nanidroid.shiori.ShioriLoadResult
import com.cattailsw.nanidroid.shiori.ShioriRequestException
import com.cattailsw.nanidroid.shiori.ShioriUnloadResult
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GhostRuntimeNativeThreadTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun provenEmptyLoadIsReplayableWithoutCleanupAndCanRetry() = runBlocking {
        val root = root("proven-empty")
        val trace = RecordingShioriTrace().apply {
            loadResults += ShioriLoadResult.Failed(
                IllegalStateException("empty"),
                LoadFailureState.ProvenEmpty,
            )
        }
        val runtime = testRuntime(scriptedPreparer(), trace)
        try {
            assertIs<RuntimeFailure.Replayable>(
                assertIs<RuntimeResult.Failure>(runtime.startOrJoin(root.name, root)).failure,
            )
            assertEquals(0, trace.unloadCount.get())
            assertEquals(GhostRuntimePhase.Idle, runtime.identity().phase)

            assertIs<RuntimeResult.Success<GhostHandle>>(runtime.startOrJoin(root.name, root))
            assertEquals(2, trace.loadCount.get())
            assertEquals(0, trace.unloadCount.get())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun ownerAlreadyPresentPoisonsWithoutTouchingForeignOwner() = runBlocking {
        val root = root("owner-present")
        val ownerFailure = IllegalStateException("foreign owner")
        val trace = RecordingShioriTrace().apply {
            loadResults += ShioriLoadResult.Failed(
                ownerFailure,
                LoadFailureState.OwnerAlreadyPresent,
            )
        }
        val runtime = testRuntime(scriptedPreparer(), trace)

        assertSame(
            ownerFailure,
            assertIs<RuntimeFailure.Fatal>(
                assertIs<RuntimeResult.Failure>(runtime.startOrJoin(root.name, root)).failure,
            ).cause,
        )
        assertEquals(GhostRuntimePhase.Poisoned, runtime.identity().phase)
        assertIs<RuntimeFailure.Fatal>(
            assertIs<RuntimeResult.Failure>(runtime.startOrJoin(root.name, root)).failure,
        )
        assertEquals(1, trace.loadCount.get())
        assertEquals(0, trace.unloadCount.get())

        runtime.close()
        assertEquals(0, trace.unloadCount.get())
    }

    @Test
    fun cleanupRequiredKnownSuccessIsReplayableAndRetryable() = runBlocking {
        val root = root("cleanup-success")
        val trace = RecordingShioriTrace().apply {
            loadResults += ShioriLoadResult.Failed(
                IllegalStateException("partial load"),
                LoadFailureState.CleanupRequired,
            )
        }
        val runtime = testRuntime(scriptedPreparer(), trace)
        try {
            assertIs<RuntimeFailure.Replayable>(
                assertIs<RuntimeResult.Failure>(runtime.startOrJoin(root.name, root)).failure,
            )
            assertEquals(1, trace.unloadCount.get())

            assertIs<RuntimeResult.Success<GhostHandle>>(runtime.startOrJoin(root.name, root))
            assertEquals(2, trace.loadCount.get())
            assertEquals(1, trace.unloadCount.get())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun cleanupRequiredFailedCleanupPoisonsAndForbidsReloadOrCloseCleanup() = runBlocking {
        val root = root("cleanup-failed")
        val trace = RecordingShioriTrace().apply {
            loadResults += ShioriLoadResult.Failed(
                IllegalStateException("partial load"),
                LoadFailureState.CleanupRequired,
            )
            unloadResults += ShioriUnloadResult.Failed(
                IllegalStateException("cleanup failed"),
                ownershipCertain = false,
            )
        }
        val runtime = testRuntime(scriptedPreparer(), trace)

        assertIs<RuntimeFailure.Fatal>(
            assertIs<RuntimeResult.Failure>(runtime.startOrJoin(root.name, root)).failure,
        )
        assertIs<RuntimeFailure.Fatal>(
            assertIs<RuntimeResult.Failure>(runtime.startOrJoin(root.name, root)).failure,
        )
        assertEquals(1, trace.loadCount.get())
        assertEquals(1, trace.unloadCount.get())
        runtime.close()
        assertEquals(1, trace.unloadCount.get())
    }

    @Test
    fun resetRejectsNativeStartedPreparationBeforeAdapterConstruction() = runBlocking {
        val root = root("reset-native-started")
        val trace = RecordingShioriTrace()
        val nativeLoadStarted = CountDownLatch(1)
        val releaseNativeLoad = CountDownLatch(1)
        val runtime = testRuntime(scriptedPreparer(), trace)
        val hookToken = runtime.installTestHooksForTesting(
            GhostRuntimeTestHooks(
                onNativeLoadStarted = { _, _ ->
                    nativeLoadStarted.countDown()
                    assertTrue(releaseNativeLoad.await(5, TimeUnit.SECONDS))
                },
            ),
        )
        val startup = async(Dispatchers.Default) {
            runtime.startOrJoin(root.name, root)
        }

        try {
            assertTrue(nativeLoadStarted.await(5, TimeUnit.SECONDS))

            assertIs<RuntimeFailure.Busy>(
                assertIs<RuntimeResult.Failure>(runtime.resetSessionForTesting()).failure,
            )
            assertEquals(0, trace.factoryCount.get())
            assertEquals(GhostRuntimePhase.Starting, runtime.identity().phase)
        } finally {
            releaseNativeLoad.countDown()
        }

        try {
            assertIs<RuntimeResult.Success<GhostHandle>>(startup.await())
        } finally {
            hookToken.close()
            runtime.close()
        }
        Unit
    }

    @Test
    fun nanidroidRuntimeUsesPreparedContentInsteadOfReadingMasterPath() = runBlocking {
        val root = root("nanidroid-prepared-content")
        val localeDirectory = File(root, "ghost/master/ja").apply { mkdirs() }
        File(localeDirectory, "content.txt").writeText("OnBoot,disk boot\n")
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val runtime = GhostRuntime.testRuntime(
            context = context,
            preparer = GhostPreparer { operationId, ghostId, canonicalRoot ->
                preparedGhost(
                    operationId,
                    ghostId,
                    canonicalRoot,
                    engine = GhostEngine.Nanidroid,
                    nanidroidContent = mapOf("OnBoot" to "prepared boot"),
                )
            },
            persistence = InMemoryGhostRuntimePersistence(),
        )

        runtime.use {
            val handle = assertIs<RuntimeResult.Success<GhostHandle>>(
                runtime.startOrJoin(root.name, root),
            ).value
            val response = assertIs<RuntimeResult.Success<TaggedShioriResponse>>(
                runtime.request(handle.generation, ShioriRequestIntent.event("OnBoot")),
            ).value.response

            assertEquals("prepared boot", response.getKey("Value"))
        }
    }

    @Test
    fun certainOptionalProbeFailureLeavesActiveSessionWithUnknownCapability() = runBlocking {
        val root = root("certain-probe")
        val trace = RecordingShioriTrace().apply {
            requestHandler.set { request ->
                when {
                    "ID: Get_Supported_Events" in request -> "SHIORI/3.0 204 No Content\r\n\r\n"
                    "Reference0: OnMouseClick" in request -> throw ShioriRequestException(
                        "click unavailable",
                        ownershipCertain = true,
                    )
                    "Reference0: OnMouseDoubleClick" in request ->
                        "SHIORI/3.0 204 No Content\r\nX-SSTP-PassThru-Result: 1\r\n\r\n"
                    else -> error("Unexpected request: $request")
                }
            }
        }
        val runtime = testRuntime(scriptedPreparer(), trace)

        runtime.use {
            val handle = assertIs<RuntimeResult.Success<GhostHandle>>(
                runtime.startOrJoin(root.name, root),
            ).value
            assertEquals(
                PointerEventCapabilities(Support.UNKNOWN, Support.SUPPORTED),
                handle.pointerCapabilities,
            )
            assertSame(handle, runtime.identity().activeHandle)
            assertEquals(0, trace.unloadCount.get())
        }
    }

    @Test
    fun uncertainProbeWithKnownCleanupPublishesNothingAndPermitsRetry() = runBlocking {
        val root = root("uncertain-probe-retry")
        val persistence = InMemoryGhostRuntimePersistence()
        val trace = RecordingShioriTrace().apply {
            requestFailure.set(ShioriRequestException("ownership uncertain", ownershipCertain = false))
        }
        val runtime = testRuntime(scriptedPreparer(), trace, persistence)
        try {
            assertIs<RuntimeFailure.Replayable>(
                assertIs<RuntimeResult.Failure>(runtime.startOrJoin(root.name, root)).failure,
            )
            assertEquals(GhostRuntimeIdentity(null, null, GhostRuntimePhase.Idle), runtime.identity())
            assertEquals(1, trace.unloadCount.get())
            assertTrue(persistence.lastRunWrites.isEmpty())

            trace.requestFailure.set(null)
            val retried = assertIs<RuntimeResult.Success<GhostHandle>>(
                runtime.startOrJoin(root.name, root),
            ).value
            assertEquals(1L, retried.generation)
            assertEquals(listOf(root.name), persistence.lastRunWrites)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun uncertainProbeWithFailedCleanupPoisonsAndNeverReloads() = runBlocking {
        val root = root("uncertain-probe-poison")
        val trace = RecordingShioriTrace().apply {
            requestFailure.set(ShioriRequestException("ownership uncertain", ownershipCertain = false))
            unloadResults += ShioriUnloadResult.Failed(
                IllegalStateException("cleanup failed"),
                ownershipCertain = false,
            )
        }
        val runtime = testRuntime(scriptedPreparer(), trace)

        assertIs<RuntimeFailure.Fatal>(
            assertIs<RuntimeResult.Failure>(runtime.startOrJoin(root.name, root)).failure,
        )
        trace.requestFailure.set(null)
        assertIs<RuntimeFailure.Fatal>(
            assertIs<RuntimeResult.Failure>(runtime.startOrJoin(root.name, root)).failure,
        )
        assertEquals(1, trace.loadCount.get())
        assertEquals(1, trace.unloadCount.get())
        runtime.close()
        assertEquals(1, trace.unloadCount.get())
    }

    @Test
    fun uncertainOrdinaryRequestPoisonsRetainsEvidenceAndCloseDoesNoMoreJni() = runBlocking {
        val root = root("uncertain-request")
        val trace = RecordingShioriTrace()
        val runtime = testRuntime(scriptedPreparer(), trace)
        val handle = assertIs<RuntimeResult.Success<GhostHandle>>(
            runtime.startOrJoin(root.name, root),
        ).value
        trace.requests.clear()
        trace.requestFailure.set(ShioriRequestException("request ownership uncertain", ownershipCertain = false))

        assertIs<RuntimeFailure.Fatal>(
            assertIs<RuntimeResult.Failure>(
                runtime.request(handle.generation, ShioriRequestIntent.event("OnBoot")),
            ).failure,
        )
        assertEquals(GhostRuntimePhase.Poisoned, runtime.identity().phase)
        assertSame(handle, runtime.identity().activeHandle)
        val commandCountBeforeClose = trace.commandThreadNames.size
        assertIs<RuntimeFailure.Fatal>(
            assertIs<RuntimeResult.Failure>(runtime.resetSessionForTesting()).failure,
        )
        assertEquals(commandCountBeforeClose, trace.commandThreadNames.size)

        runtime.close()
        assertEquals(0, trace.unloadCount.get())
        assertEquals(commandCountBeforeClose, trace.commandThreadNames.size)
    }

    @Test
    fun certainOrdinaryRequestFailureIsReplayableAndSessionRemainsUsable() = runBlocking {
        val root = root("certain-request")
        val trace = RecordingShioriTrace()
        val runtime = testRuntime(scriptedPreparer(), trace)

        runtime.use {
            val handle = assertIs<RuntimeResult.Success<GhostHandle>>(
                runtime.startOrJoin(root.name, root),
            ).value
            trace.requestFailure.set(ShioriRequestException("known failure", ownershipCertain = true))
            assertIs<RuntimeFailure.Replayable>(
                assertIs<RuntimeResult.Failure>(
                    runtime.request(handle.generation, ShioriRequestIntent.event("OnBoot")),
                ).failure,
            )
            assertSame(handle, runtime.identity().activeHandle)
            assertEquals(GhostRuntimePhase.Unattached, runtime.identity().phase)

            trace.requestFailure.set(null)
            assertIs<RuntimeResult.Success<TaggedShioriResponse>>(
                runtime.request(handle.generation, ShioriRequestIntent.event("OnBoot")),
            )
        }
        Unit
    }

    @Test
    fun unloadFailurePoisonsRetainsHandleAndCloseDoesNotRetryTeardown() = runBlocking {
        val root = root("unload-failure")
        val trace = RecordingShioriTrace()
        val runtime = testRuntime(scriptedPreparer(), trace)
        val handle = assertIs<RuntimeResult.Success<GhostHandle>>(
            runtime.startOrJoin(root.name, root),
        ).value
        trace.unloadResults += ShioriUnloadResult.Failed(
            IllegalStateException("unload failed"),
            ownershipCertain = false,
        )

        assertIs<RuntimeFailure.Fatal>(
            assertIs<RuntimeResult.Failure>(runtime.unload(handle.generation)).failure,
        )
        assertSame(handle, runtime.identity().activeHandle)
        assertEquals(GhostRuntimePhase.Poisoned, runtime.identity().phase)
        runtime.close()
        assertEquals(1, trace.unloadCount.get())
    }

    @Test
    fun allLoadProbeRequestAndUnloadCommandsUseOneNamedThread() = runBlocking {
        val root = root("thread-affinity")
        val trace = RecordingShioriTrace()
        val runtime = testRuntime(scriptedPreparer(), trace)

        runtime.use {
            val handle = assertIs<RuntimeResult.Success<GhostHandle>>(
                runtime.startOrJoin(root.name, root),
            ).value
            assertIs<RuntimeResult.Success<TaggedShioriResponse>>(
                runtime.request(handle.generation, ShioriRequestIntent.event("OnBoot")),
            )
            assertIs<RuntimeResult.Success<Unit>>(runtime.unload(handle.generation))
        }

        assertTrue(trace.commandThreadNames.isNotEmpty())
        assertEquals(setOf(runtime.nativeThreadName), trace.commandThreadNames.toSet())
    }

    @Test
    fun productionRuntimeRejectsLifecycleProbeWithoutJniWork() {
        val root = root("production-probe-rejected")
        val runtime = GhostRuntime(null)
        val prepared = preparedGhost(1L, root.name, root)

        runtime.use {
            assertIs<RuntimeFailure.Fatal>(
                assertIs<RuntimeResult.Failure>(
                    runtime.probeAdapterLifecycleForTesting(prepared, prepared),
                ).failure,
            )
            assertEquals(GhostRuntimePhase.Idle, runtime.identity().phase)
        }
    }

    @Test
    fun postUnloadNativeProbeCallSitesExistOnlyInsideGhostRuntime() {
        val productionRoot = File("src/main/kotlin/com/cattailsw/nanidroid")
        val callPattern = Regex("\\.probeNative(?:Request|CharsetAndRequest)AfterUnloadForTesting\\(")
        val callSites = productionRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { callPattern.containsMatchIn(it.readText()) }
            .map { it.name }
            .toSet()

        assertEquals(setOf("GhostRuntime.kt"), callSites)
    }

    private fun root(name: String): File = File("build/ghost-runtime-native-thread-test/$name").canonicalFile
}
