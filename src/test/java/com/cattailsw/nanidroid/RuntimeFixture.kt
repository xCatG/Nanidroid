package com.cattailsw.nanidroid

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanner
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeCommandDispatcher
import com.cattailsw.nanidroid.runtime.RuntimeNativeLifecycleOutcome
import com.cattailsw.nanidroid.runtime.RuntimeNativeLoadOutcome
import com.cattailsw.nanidroid.runtime.RuntimeNativePort
import com.cattailsw.nanidroid.runtime.RuntimeRequestToken
import com.cattailsw.nanidroid.runtime.RuntimeScheduleKey
import com.cattailsw.nanidroid.runtime.RuntimeScheduler

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

internal open class ManualSnapshotRuntimeScheduler : RuntimeScheduler {
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

    fun runAll() {
        while (pending.isNotEmpty()) runNext()
    }

    fun runNext(kind: com.cattailsw.nanidroid.runtime.RuntimeScheduleKind) =
        requireNotNull(pending.entries.firstOrNull { it.key.kind == kind })
            .also { pending.remove(it.key) }
            .value
            .action()

    fun scheduled(): List<Scheduled> = pending.values.toList()

    override fun close() = pending.clear()
}

internal class BlockingThrowingCancelRuntimeScheduler : ManualSnapshotRuntimeScheduler() {
    val cancelEntered = java.util.concurrent.CountDownLatch(1)
    val cancelRelease = java.util.concurrent.CountDownLatch(1)
    val closeEntered = java.util.concurrent.CountDownLatch(1)
    val closeRelease = java.util.concurrent.CountDownLatch(1)
    val armed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun cancel(key: RuntimeScheduleKey) {
        if (!armed.get()) return super.cancel(key)
        cancelEntered.countDown()
        check(cancelRelease.await(5, java.util.concurrent.TimeUnit.SECONDS))
        throw IllegalStateException("cancel failed")
    }

    override fun close() {
        closeEntered.countDown()
        check(closeRelease.await(5, java.util.concurrent.TimeUnit.SECONDS))
        super.close()
    }
}

internal open class RecordingRuntimeNativePort : RuntimeNativePort {
    data class PendingLoad(
        val operationId: Long,
        val generation: Long,
        val prepared: PreparedGhost,
        val complete: (RuntimeNativeLoadOutcome) -> Unit,
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
        val invocationThreadName: String,
        val complete: (RuntimeNativeLifecycleOutcome) -> Unit,
    )

    val loads = ConcurrentLinkedQueue<PendingLoad>()
    val requests = ConcurrentLinkedQueue<PendingRequest>()
    val unloads = ConcurrentLinkedQueue<PendingUnload>()

    override fun load(
        operationId: Long,
        generation: Long,
        prepared: PreparedGhost,
        complete: (RuntimeNativeLoadOutcome) -> Unit,
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
        unloads += PendingUnload(operationId, generation, Thread.currentThread().name, complete)
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

internal class IndefinitelyBlockingRecordingRuntimeNativePort(
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
            release.await()
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

    fun startLoaded(
        id: String,
        root: File,
        pointerCapabilities: com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities =
            com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities(),
    ) {
        runtime.submit(RuntimeCommand.StartGhost(id, root))
        awaitNativeWork()
        nativePort.loads.remove().complete(RuntimeNativeLoadOutcome.Loaded(pointerCapabilities))
        dispatcher.drain()
    }

    fun startAttached(
        id: String,
        root: File,
        pointerCapabilities: com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities =
            com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities(),
    ) {
        startLoaded(id, root, pointerCapabilities)
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
            val scheduled = scheduler.scheduled().firstOrNull {
                it.key.kind == com.cattailsw.nanidroid.runtime.RuntimeScheduleKind.PLAYBACK
            }
                ?: throw AssertionError("playback stopped before predicate; snapshot=${runtime.snapshots.value}")
            scheduler.run(scheduled.key)
            dispatcher.drain()
        }
        throw AssertionError("playback did not reach predicate")
    }

    override fun close() = runtime.close()
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
