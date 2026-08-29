package com.cattailsw.nanidroid

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import com.cattailsw.nanidroid.shiori.Shiori
import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanner
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeCommandDispatcher
import com.cattailsw.nanidroid.runtime.RuntimeNativeLifecycleOutcome
import com.cattailsw.nanidroid.runtime.RuntimeNativePort
import com.cattailsw.nanidroid.runtime.RuntimeRequestToken
import com.cattailsw.nanidroid.runtime.RuntimeScheduleKey
import com.cattailsw.nanidroid.runtime.RuntimeScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Closeable runtime-owned replacement for the former fake-[Ghost] test subclasses. */
internal class RuntimeFixture(
    val id: String = "recording",
    val root: File = File("build/runtime-fixtures/${id}-${fixtureIds.incrementAndGet()}"),
    val trace: RecordingShioriTrace = RecordingShioriTrace(),
    val persistence: InMemoryGhostRuntimePersistence = InMemoryGhostRuntimePersistence(),
    response: (String) -> String = { NO_CONTENT_RESPONSE },
    bootstrapResponse: ((String) -> String)? = null,
    preparedFactory: (Long, String, File) -> PreparedGhost = ::preparedGhost,
    adapterDecorator: (Shiori) -> Shiori = { it },
    runnerConfiguration: SScriptRunnerConfiguration? = null,
    autoStart: Boolean = true,
    autoAttach: Boolean = autoStart,
) : AutoCloseable {
    val runtime = GhostRuntime.testRuntime(
        context = null,
        preparer = GhostPreparer(preparedFactory),
        adapterFactory = { prepared ->
            adapterDecorator(RecordingShiori(trace, prepared.id))
        },
        persistence = persistence,
        runnerConfiguration = runnerConfiguration,
    )
    val runner: SScriptRunner = runtime.runner
    var handle: GhostHandle? = null
        private set

    init {
        if (bootstrapResponse != null) {
            trace.requestHandler.set(bootstrapResponse)
        } else if (!autoAttach) {
            trace.requestHandler.set(response)
        }
        if (autoStart) {
            handle = runBlocking {
                val result = runtime.startOrJoin(id, root)
                assertTrue("runtime start failed: $result", result is RuntimeResult.Success)
                (result as RuntimeResult.Success).value
            }
        }
        if (autoAttach) {
            runBlocking {
                val result = runtime.attachHost(requireHandle().generation)
                assertTrue("runtime attachment failed: $result", result is RuntimeResult.Success)
            }
            runner.clearMsgQueue()
            trace.requests.clear()
            trace.ownedRequests.clear()
            trace.requestHandler.set(response)
        }
    }

    fun requireHandle(): GhostHandle = requireNotNull(handle) { "fixture was created without startup" }

    override fun close() = runtime.close()

    private companion object {
        val fixtureIds = AtomicLong()
        const val NO_CONTENT_RESPONSE = "SHIORI/3.0 204 No Content\r\n\r\n"
    }
}

internal class ManualRuntimeCommandDispatcher : RuntimeCommandDispatcher {
    private val pending = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile
    private var closed = false

    override fun dispatch(action: () -> Unit) {
        if (closed) return
        pending += action
    }

    fun drain(): Int {
        var count = 0
        while (true) {
            val action = pending.poll() ?: return count
            action()
            count += 1
        }
    }

    fun isEmpty(): Boolean = pending.isEmpty()

    override fun close() {
        closed = true
        pending.clear()
    }
}

internal class ManualSnapshotRuntimeScheduler : RuntimeScheduler {
    data class Scheduled(
        val key: RuntimeScheduleKey,
        val delayMillis: Long,
        val action: () -> Unit,
    )

    private val pending = linkedMapOf<RuntimeScheduleKey, Scheduled>()

    override fun schedule(key: RuntimeScheduleKey, delayMillis: Long, action: () -> Unit) {
        pending[key] = Scheduled(key, delayMillis, action)
    }

    override fun cancel(key: RuntimeScheduleKey) {
        pending.remove(key)
    }

    fun run(key: RuntimeScheduleKey) = requireNotNull(pending.remove(key)).action()

    fun runNext() = requireNotNull(pending.entries.firstOrNull()).also { pending.remove(it.key) }.value.action()

    fun runNext(kind: com.cattailsw.nanidroid.runtime.RuntimeScheduleKind) =
        requireNotNull(pending.entries.firstOrNull { it.key.kind == kind })
            .also { pending.remove(it.key) }
            .value
            .action()

    fun scheduled(): List<Scheduled> = pending.values.toList()

    override fun close() = pending.clear()
}

internal open class RecordingRuntimeNativePort : RuntimeNativePort {
    data class PendingLoad(
        val operationId: Long,
        val generation: Long,
        val prepared: PreparedGhost,
        val complete: (RuntimeNativeLifecycleOutcome) -> Unit,
    )

    data class PendingRequest(
        val token: RuntimeRequestToken,
        val intent: ShioriRequestIntent,
        val fallback: ShioriRequestIntent?,
        val complete: (RuntimeResult<TaggedShioriResponse>) -> Unit,
    )

    data class PendingUnload(
        val operationId: Long,
        val generation: Long,
        val complete: (RuntimeNativeLifecycleOutcome) -> Unit,
    )

    val loads = ConcurrentLinkedQueue<PendingLoad>()
    val requests = ConcurrentLinkedQueue<PendingRequest>()
    val unloads = ConcurrentLinkedQueue<PendingUnload>()

    override fun load(
        operationId: Long,
        generation: Long,
        prepared: PreparedGhost,
        complete: (RuntimeNativeLifecycleOutcome) -> Unit,
    ) {
        loads += PendingLoad(operationId, generation, prepared, complete)
    }

    override fun request(
        token: RuntimeRequestToken,
        intent: ShioriRequestIntent,
        fallback: ShioriRequestIntent?,
        complete: (RuntimeResult<TaggedShioriResponse>) -> Unit,
    ) {
        requests += PendingRequest(token, intent, fallback, complete)
    }

    override fun unload(
        operationId: Long,
        generation: Long,
        complete: (RuntimeNativeLifecycleOutcome) -> Unit,
    ) {
        unloads += PendingUnload(operationId, generation, complete)
    }
}

internal class BlockingRecordingRuntimeNativePort(
    private val blockedEventId: String,
) : RecordingRuntimeNativePort() {
    val entered = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)

    override fun request(
        token: RuntimeRequestToken,
        intent: ShioriRequestIntent,
        fallback: ShioriRequestIntent?,
        complete: (RuntimeResult<TaggedShioriResponse>) -> Unit,
    ) {
        if (intent.protocolText.contains("ID: $blockedEventId\r\n")) {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        super.request(token, intent, fallback, complete)
    }
}

internal class SnapshotRuntimeFixture(
    val dispatcher: ManualRuntimeCommandDispatcher = ManualRuntimeCommandDispatcher(),
    val scheduler: ManualSnapshotRuntimeScheduler = ManualSnapshotRuntimeScheduler(),
    val nativePort: RecordingRuntimeNativePort = RecordingRuntimeNativePort(),
    persistence: GhostRuntimePersistence = InMemoryGhostRuntimePersistence(),
    catalogScanner: RuntimeCatalogScanner = RuntimeCatalogScanner { emptyList() },
    preparer: GhostPreparer = scriptedPreparer(),
    elapsedRealtimeMillis: () -> Long = { 0L },
    canonicalizeRoot: (File) -> File = File::getCanonicalFile,
    awaitInitialCatalog: Boolean = true,
) : AutoCloseable {
    val runtime = GhostRuntime.testRuntime(
        context = null,
        preparer = preparer,
        persistence = persistence,
        ownershipMode = RuntimeOwnershipMode.SNAPSHOT_CORE_TEST,
        nativePort = nativePort,
        runtimeScheduler = scheduler,
        coordinationDispatcher = dispatcher,
        catalogScanner = catalogScanner,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        canonicalizeRoot = canonicalizeRoot,
    )

    init {
        if (awaitInitialCatalog) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (dispatcher.isEmpty()) {
                if (System.nanoTime() >= deadline) throw AssertionError("initial catalog scan did not return")
                Thread.yield()
            }
            dispatcher.drain()
        }
    }

    fun drain(): Int = dispatcher.drain()

    fun drainUntil(timeoutMillis: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (!predicate()) {
            dispatcher.drain()
            if (System.nanoTime() >= deadline) throw AssertionError("coordination condition did not settle")
            Thread.yield()
        }
    }

    fun awaitNativeWork(timeoutMillis: Long = 5_000L) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (nativePort.loads.isEmpty() && nativePort.requests.isEmpty() && nativePort.unloads.isEmpty()) {
            dispatcher.drain()
            if (System.nanoTime() >= deadline) throw AssertionError("native work was not submitted")
            Thread.yield()
        }
    }

    fun startLoaded(id: String, root: File) {
        runtime.submit(RuntimeCommand.StartGhost(id, root))
        awaitNativeWork()
        nativePort.loads.remove().complete(RuntimeNativeLifecycleOutcome.Success)
        dispatcher.drain()
    }

    fun startAttached(id: String, root: File) {
        startLoaded(id, root)
        awaitNativeWork()
        nativePort.requests.remove().complete(
            RuntimeResult.Success(
                TaggedShioriResponse(
                    requireNotNull(runtime.snapshots.value.generation),
                    ShioriResponse("SHIORI/3.0 204 No Content", java.util.Hashtable()),
                ),
            ),
        )
        drainUntil { runtime.snapshots.value.phase == GhostRuntimePhase.Attached }
    }

    fun makeTopHost(hostId: Long): com.cattailsw.nanidroid.runtime.RuntimeHostLease {
        val id = com.cattailsw.nanidroid.runtime.RuntimeHostId(hostId)
        runtime.submit(RuntimeCommand.RegisterHost(com.cattailsw.nanidroid.runtime.RuntimeHostLease(id, 1L)))
        runtime.submit(RuntimeCommand.SetResumed(com.cattailsw.nanidroid.runtime.RuntimeHostLease(id, 2L), true))
        val top = com.cattailsw.nanidroid.runtime.RuntimeHostLease(id, 3L)
        runtime.submit(RuntimeCommand.SetTopResumed(top, true))
        dispatcher.drain()
        return top
    }

    fun runPlaybackUntil(predicate: (com.cattailsw.nanidroid.runtime.RuntimeSnapshot) -> Boolean) {
        repeat(500) {
            if (predicate(runtime.snapshots.value)) return
            val scheduled = scheduler.scheduled().firstOrNull()
                ?: throw AssertionError("playback stopped before predicate; snapshot=${runtime.snapshots.value}")
            scheduler.run(scheduled.key)
            dispatcher.drain()
        }
        throw AssertionError("playback did not reach predicate")
    }

    override fun close() = runtime.close()
}

class RuntimeFixtureRegistry : TestRule {
    private val fixtures = mutableListOf<RuntimeFixture>()

    internal fun create(
        id: String = "recording",
        root: File = File("build/runtime-fixtures/$id-${registryFixtureIds.incrementAndGet()}"),
        trace: RecordingShioriTrace = RecordingShioriTrace(),
        persistence: InMemoryGhostRuntimePersistence = InMemoryGhostRuntimePersistence(),
        response: (String) -> String = { "SHIORI/3.0 204 No Content\r\n\r\n" },
        bootstrapResponse: ((String) -> String)? = null,
        preparedFactory: (Long, String, File) -> PreparedGhost = ::preparedGhost,
        adapterDecorator: (Shiori) -> Shiori = { it },
        runnerConfiguration: SScriptRunnerConfiguration? = null,
        autoStart: Boolean = true,
        autoAttach: Boolean = autoStart,
    ): RuntimeFixture = RuntimeFixture(
        id = id,
        root = root,
        trace = trace,
        persistence = persistence,
        response = response,
        bootstrapResponse = bootstrapResponse,
        preparedFactory = preparedFactory,
        adapterDecorator = adapterDecorator,
        runnerConfiguration = runnerConfiguration,
        autoStart = autoStart,
        autoAttach = autoAttach,
    ).also(fixtures::add)

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try {
                base.evaluate()
            } finally {
                fixtures.asReversed().forEach(RuntimeFixture::close)
                fixtures.clear()
            }
        }
    }

    private companion object {
        val registryFixtureIds = AtomicLong()
    }
}
