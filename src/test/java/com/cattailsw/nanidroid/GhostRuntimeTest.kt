package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities
import com.cattailsw.nanidroid.runtime.dialogue.ShioriMethod
import com.cattailsw.nanidroid.runtime.dialogue.Support
import com.cattailsw.nanidroid.shiori.Shiori
import com.cattailsw.nanidroid.shiori.ShioriLoadResult
import com.cattailsw.nanidroid.shiori.ShioriUnloadResult
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GhostRuntimeTest {
    @Rule
    @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun runtimeIdentityExposesOnlyCurrentPlaybackHandle() {
        val handle = GhostHandle(
            Ghost(preparedGhost(1L, "identity", File("build/ghost-runtime-test/identity"))),
            pointerCapabilities = PointerEventCapabilities(
                Support.UNKNOWN,
                Support.UNKNOWN,
            ),
            generation = 7L,
        )

        GhostRuntimePhase.entries.forEach { phase ->
            val identity = GhostRuntimeIdentity(handle, null, phase)
            if (phase == GhostRuntimePhase.Attached || phase == GhostRuntimePhase.SwitchPlayback) {
                assertSame(handle, identity.playbackHandle())
                assertSame(handle, identity.playbackHandle(expectedGeneration = 7L))
                assertNull(identity.playbackHandle(expectedGeneration = 8L))
            } else {
                assertNull(identity.playbackHandle())
                assertNull(identity.playbackHandle(expectedGeneration = 7L))
            }
        }
    }

    @Test
    fun eventIntentPreservesLegacyHeaderOrderAndNullReferenceSlots() {
        assertEquals(
            "GET SHIORI/3.0\r\n" +
                "Sender: Nanidroid\r\n" +
                "ID: OnExample\r\n" +
                "SecurityLevel: local\r\n" +
                "Reference0: first\r\n" +
                "Reference1: \r\n" +
                "Reference2: null\r\n\r\n",
            ShioriRequestIntent.event("OnExample", listOf("first", "", null)).protocolText,
        )
    }

    @Test
    fun rawIntentPreservesLegacyHeaderOrderAndNullReferenceSlots() {
        assertEquals(
            "NOTIFY SHIORI/3.0\r\n" +
                "Sender: Nanidroid\r\n" +
                "SecurityLevel: local\r\n" +
                "ID: OnExample\r\n" +
                "Reference0: first\r\n" +
                "Reference1: \r\n" +
                "Reference2: null\r\n\r\n",
            ShioriRequestIntent.raw(
                ShioriMethod.NOTIFY,
                "OnExample",
                listOf("first", "", null),
            ).protocolText,
        )
    }

    @Test
    fun sameRootWaitersSharePreparationLoadAndIdenticalHandle() = runBlocking {
        val root = File("build/ghost-runtime-test/same").canonicalFile
        val preparationStarted = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val prepareCount = AtomicInteger()
        val trace = RecordingShioriTrace()
        val preparer = GhostPreparer { operationId, ghostId, canonicalRoot ->
            prepareCount.incrementAndGet()
            preparationStarted.countDown()
            assertTrue(releasePreparation.await(5, TimeUnit.SECONDS))
            preparedGhost(operationId, ghostId, canonicalRoot)
        }
        val runtime = testRuntime(preparer, trace)

        runtime.use {
            val first = async(start = CoroutineStart.UNDISPATCHED) { runtime.startOrJoin("same", root) }
            assertTrue(preparationStarted.await(5, TimeUnit.SECONDS))
            val second = async(start = CoroutineStart.UNDISPATCHED) { runtime.startOrJoin("same", root) }
            releasePreparation.countDown()

            val firstHandle = assertIs<RuntimeResult.Success<GhostHandle>>(first.await()).value
            val secondHandle = assertIs<RuntimeResult.Success<GhostHandle>>(second.await()).value
            assertSame(firstHandle, secondHandle)
            assertEquals(1, prepareCount.get())
            assertEquals(1, trace.loadCount.get())
            assertEquals(setOf(runtime.nativeThreadName), trace.commandThreadNames.toSet())
            assertEquals(
                GhostRuntimeIdentity(firstHandle, null, GhostRuntimePhase.Unattached),
                runtime.identity(),
            )
        }
    }

    @Test
    fun cancelledSoleWaiterDoesNotCancelProducerAndLaterCallerReusesResult() = runBlocking {
        val root = File("build/ghost-runtime-test/cancelled").canonicalFile
        val preparationStarted = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val generationPublished = CountDownLatch(1)
        val trace = RecordingShioriTrace()
        val runtime = testRuntime(
            GhostPreparer { operationId, ghostId, canonicalRoot ->
                preparationStarted.countDown()
                assertTrue(releasePreparation.await(5, TimeUnit.SECONDS))
                preparedGhost(operationId, ghostId, canonicalRoot)
            },
            trace,
        )
        val hookToken = runtime.installTestHooksForTesting(
            GhostRuntimeTestHooks(onGenerationPublished = { _, _ -> generationPublished.countDown() }),
        )

        runtime.use {
            hookToken.use {
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                    runtime.startOrJoin("cancelled", root)
                }
                assertTrue(preparationStarted.await(5, TimeUnit.SECONDS))
                cancelled.cancelAndJoin()
                releasePreparation.countDown()
                assertTrue(generationPublished.await(5, TimeUnit.SECONDS))

                val handle = assertIs<RuntimeResult.Success<GhostHandle>>(
                    runtime.startOrJoin("cancelled", root),
                ).value
                assertEquals(1, trace.loadCount.get())
                assertSame(handle, runtime.identity().activeHandle)
            }
        }
    }

    @Test
    fun preparationFailureIsSharedThenRetryStartsOneNewOperation() = runBlocking {
        val root = File("build/ghost-runtime-test/retry").canonicalFile
        val preparationStarted = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val attempts = AtomicInteger()
        val trace = RecordingShioriTrace()
        val runtime = testRuntime(
            GhostPreparer { operationId, ghostId, canonicalRoot ->
                if (attempts.incrementAndGet() == 1) {
                    preparationStarted.countDown()
                    assertTrue(releaseFailure.await(5, TimeUnit.SECONDS))
                    error("scripted preparation failure")
                }
                preparedGhost(operationId, ghostId, canonicalRoot)
            },
            trace,
        )

        runtime.use {
            val first = async(start = CoroutineStart.UNDISPATCHED) { runtime.startOrJoin("retry", root) }
            assertTrue(preparationStarted.await(5, TimeUnit.SECONDS))
            val joiner = async(start = CoroutineStart.UNDISPATCHED) { runtime.startOrJoin("retry", root) }
            releaseFailure.countDown()
            assertIs<RuntimeFailure.Replayable>(
                assertIs<RuntimeResult.Failure>(first.await()).failure,
            )
            assertIs<RuntimeFailure.Replayable>(
                assertIs<RuntimeResult.Failure>(joiner.await()).failure,
            )

            val retried = assertIs<RuntimeResult.Success<GhostHandle>>(
                runtime.startOrJoin("retry", root),
            ).value
            assertEquals(2, attempts.get())
            assertEquals(1, trace.loadCount.get())
            assertSame(retried, runtime.identity().activeHandle)
        }
    }

    @Test
    fun resetDropsStalePreNativePreparationBeforeAdapterConstruction() = runBlocking {
        val root = File("build/ghost-runtime-test/stale-preparation").canonicalFile
        val preparationStarted = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val trace = RecordingShioriTrace()
        val runtime = testRuntime(
            GhostPreparer { operationId, ghostId, canonicalRoot ->
                preparationStarted.countDown()
                assertTrue(releasePreparation.await(5, TimeUnit.SECONDS))
                preparedGhost(operationId, ghostId, canonicalRoot)
            },
            trace,
        )

        runtime.use {
            val stale = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.startOrJoin("stale-preparation", root)
            }
            assertTrue(preparationStarted.await(5, TimeUnit.SECONDS))
            assertIs<RuntimeResult.Success<Unit>>(runtime.resetSessionForTesting())
            releasePreparation.countDown()
            assertIs<RuntimeFailure.StaleGeneration>(
                assertIs<RuntimeResult.Failure>(stale.await()).failure,
            )
            assertEquals(0, trace.factoryCount.get())
            assertEquals(GhostRuntimePhase.Idle, runtime.identity().phase)
        }
    }

    @Test
    fun activeSameRootReuseDoesNotPrepareLoadOrRewritePreference() = runBlocking {
        val root = File("build/ghost-runtime-test/reuse").canonicalFile
        val prepareCount = AtomicInteger()
        val trace = RecordingShioriTrace()
        val persistence = InMemoryGhostRuntimePersistence()
        val runtime = GhostRuntime.testRuntime(
            context = null,
            preparer = GhostPreparer { operationId, ghostId, canonicalRoot ->
                prepareCount.incrementAndGet()
                preparedGhost(operationId, ghostId, canonicalRoot)
            },
            adapterFactory = { prepared -> RecordingShiori(trace, prepared.id) },
            persistence = persistence,
        )

        runtime.use {
            val first = assertIs<RuntimeResult.Success<GhostHandle>>(
                runtime.startOrJoin("reuse", root),
            ).value
            val reused = assertIs<RuntimeResult.Success<GhostHandle>>(
                runtime.startOrJoin("reuse", root),
            ).value

            assertSame(first, reused)
            assertEquals(1, prepareCount.get())
            assertEquals(1, trace.loadCount.get())
            assertEquals(listOf("reuse"), persistence.lastRunWrites)
        }
    }

    @Test
    fun staleGenerationRequestIsRejectedBeforeAdapterInvocation() = runBlocking {
        val root = File("build/ghost-runtime-test/request-generation").canonicalFile
        val trace = RecordingShioriTrace()
        val runtime = testRuntime(scriptedPreparer(), trace)

        runtime.use {
            val handle = assertIs<RuntimeResult.Success<GhostHandle>>(
                runtime.startOrJoin("request-generation", root),
            ).value
            trace.requests.clear()
            trace.commandThreadNames.clear()

            assertIs<RuntimeFailure.StaleGeneration>(
                assertIs<RuntimeResult.Failure>(
                    runtime.request(handle.generation + 1, ShioriRequestIntent.event("OnBoot")),
                ).failure,
            )
            assertTrue(trace.requests.isEmpty())

            val tagged = assertIs<RuntimeResult.Success<TaggedShioriResponse>>(
                runtime.request(handle.generation, ShioriRequestIntent.event("OnBoot")),
            ).value
            assertEquals(handle.generation, tagged.generation)
            assertEquals(204, tagged.response.getStatusCode())
            assertEquals(listOf("GET SHIORI/3.0\r\nSender: Nanidroid\r\nID: OnBoot\r\nSecurityLevel: local\r\n\r\n"), trace.requests)
            assertEquals(listOf(runtime.nativeThreadName), trace.commandThreadNames)
        }
    }

    @Test
    fun closeUnloadsKnownSessionAndTerminatesNamedExecutor() = runBlocking {
        val root = File("build/ghost-runtime-test/close").canonicalFile
        val trace = RecordingShioriTrace()
        val runtime = testRuntime(scriptedPreparer(), trace)
        val threadName = runtime.nativeThreadName
        assertIs<RuntimeResult.Success<GhostHandle>>(runtime.startOrJoin("close", root))

        runtime.close()

        assertEquals(1, trace.unloadCount.get())
        assertTrue(
            withTimeout(5_000) {
                while (Thread.getAllStackTraces().keys.any { it.name == threadName && it.isAlive }) {
                    Thread.yield()
                }
                true
            },
        )
    }

    @Test
    fun testHooksHaveSingleIdentityScopedInstallationAndOwningWorkerThreads() = runBlocking {
        val root = File("build/ghost-runtime-test/hooks").canonicalFile
        val trace = RecordingShioriTrace()
        val callbackThreads = CopyOnWriteArrayList<Pair<String, String>>()
        val runtime = testRuntime(scriptedPreparer(), trace)
        val hooks = GhostRuntimeTestHooks(
            onPreparationStarted = { _, _, _ -> callbackThreads += "prepare" to Thread.currentThread().name },
            onNativeLoadStarted = { _, _ -> callbackThreads += "load" to Thread.currentThread().name },
            onGenerationPublished = { _, _ -> callbackThreads += "publish" to Thread.currentThread().name },
            onActivationCommitted = { callbackThreads += "activation" to Thread.currentThread().name },
            onBootAttempted = { _, _ -> callbackThreads += "boot" to Thread.currentThread().name },
        )
        val firstToken = runtime.installTestHooksForTesting(hooks)
        assertThrows(IllegalStateException::class.java) {
            runtime.installTestHooksForTesting(GhostRuntimeTestHooks())
        }

        runtime.use {
            firstToken.close()
            runtime.installTestHooksForTesting(hooks).use {
                val handle = assertIs<RuntimeResult.Success<GhostHandle>>(
                    runtime.startOrJoin(root.name, root),
                ).value
                assertIs<RuntimeResult.Success<AttachmentReceipt>>(runtime.attachHost(handle.generation))
            }

            val names = callbackThreads.toMap()
            assertTrue(names.keys.containsAll(listOf("prepare", "load", "publish", "activation", "boot")))
            assertEquals(runtime.nativeThreadName, names["load"])
            assertEquals(runtime.nativeThreadName, names["publish"])
            assertTrue(names["prepare"] != Thread.currentThread().name)
            assertTrue(names["activation"] != Thread.currentThread().name)
            assertTrue(names["boot"] != Thread.currentThread().name)
            assertTrue(names["activation"] != runtime.nativeThreadName)
            assertTrue(names["boot"] != runtime.nativeThreadName)
        }
    }
}

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

    override fun readActivationCount(ghostId: String): Long = activationCounts[ghostId] ?: 0L

    override fun commitActivationCount(ghostId: String, count: Long) {
        activationCounts[ghostId] = count
        activationWrites += ghostId to count
    }
}

internal class RecordingShioriTrace {
    val factoryCount = AtomicInteger()
    val loadCount = AtomicInteger()
    val unloadCount = AtomicInteger()
    val requests = CopyOnWriteArrayList<String>()
    val ownedRequests = CopyOnWriteArrayList<RecordedShioriRequest>()
    val commandThreadNames = CopyOnWriteArrayList<String>()
    val lifecycleEvents = CopyOnWriteArrayList<String>()
    val loadResults = ConcurrentLinkedQueue<ShioriLoadResult>()
    val unloadResults = ConcurrentLinkedQueue<ShioriUnloadResult>()
    val requestFailure = AtomicReference<Throwable?>(null)
    val requestHandler = AtomicReference<((String) -> String)?>(null)
    val requestObserver = AtomicReference<((RecordedShioriRequest) -> Unit)?>(null)
    val unloadObserver = AtomicReference<(() -> Unit)?>(null)
    val response = AtomicReference("SHIORI/3.0 204 No Content\r\n\r\n")
}

internal data class RecordedShioriRequest(
    val ownerGhostId: String,
    val protocolText: String,
)

internal class RecordingShiori(
    private val trace: RecordingShioriTrace,
    private val ownerGhostId: String,
) : Shiori {
    init {
        trace.factoryCount.incrementAndGet()
        trace.lifecycleEvents += "factory:$ownerGhostId"
    }

    override fun getModuleName(): String = "Recording"

    override fun load(): ShioriLoadResult {
        trace.loadCount.incrementAndGet()
        trace.commandThreadNames += Thread.currentThread().name
        return trace.loadResults.poll() ?: ShioriLoadResult.Loaded
    }

    override fun request(request: String): String {
        trace.requests += request
        trace.commandThreadNames += Thread.currentThread().name
        val recorded = RecordedShioriRequest(ownerGhostId, request)
        trace.ownedRequests += recorded
        trace.requestObserver.get()?.invoke(recorded)
        trace.requestFailure.get()?.let { throw it }
        trace.requestHandler.get()?.let { return it(request) }
        return trace.response.get()
    }

    override
    fun unloadShiori(): ShioriUnloadResult {
        trace.unloadObserver.get()?.invoke()
        trace.unloadCount.incrementAndGet()
        trace.lifecycleEvents += "unload:$ownerGhostId"
        trace.commandThreadNames += Thread.currentThread().name
        return trace.unloadResults.poll() ?: ShioriUnloadResult.Unloaded
    }
}

internal fun testRuntime(
    preparer: GhostPreparer,
    trace: RecordingShioriTrace,
    persistence: InMemoryGhostRuntimePersistence = InMemoryGhostRuntimePersistence(),
    admission: AttachmentAdmission? = null,
): GhostRuntime = GhostRuntime.testRuntime(
    context = null,
    preparer = preparer,
    adapterFactory = { prepared -> RecordingShiori(trace, prepared.id) },
    persistence = persistence,
    admission = admission,
)

internal fun scriptedPreparer(): GhostPreparer = GhostPreparer(::preparedGhost)

internal fun preparedGhost(
    operationId: Long,
    ghostId: String,
    canonicalRoot: File,
    engine: GhostEngine = GhostEngine.Unsupported,
    name: String? = ghostId,
    shellName: String? = "master",
    crafterName: String? = null,
    sakuraName: String? = null,
    keroName: String? = null,
    surfaces: SurfaceCatalog = SurfaceCatalog.freeze(emptyMap()),
    ghostDescriptor: Map<String, String> = emptyMap(),
    shellDescriptor: Map<String, String>? = null,
    nanidroidContent: Map<String, String> = emptyMap(),
): PreparedGhost = PreparedGhost(
    operationId = operationId,
    id = ghostId,
    canonicalRoot = canonicalRoot.canonicalFile,
    name = name,
    shellName = shellName,
    crafterName = crafterName,
    sakuraName = sakuraName,
    keroName = keroName,
    surfaces = surfaces,
    ghostDescriptor = ghostDescriptor,
    shellDescriptor = shellDescriptor,
    engine = engine,
    nanidroidContent = nanidroidContent,
)

internal inline fun <reified T> assertIs(actual: Any?): T {
    assertTrue("Expected ${T::class.java.name}, got ${actual?.javaClass?.name}", actual is T)
    return actual as T
}
