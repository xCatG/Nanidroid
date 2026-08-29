package com.cattailsw.nanidroid

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanner
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeExitLease
import com.cattailsw.nanidroid.runtime.RuntimeHostId
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import com.cattailsw.nanidroid.runtime.RuntimeNativeLifecycleOutcome
import com.cattailsw.nanidroid.runtime.RuntimeNativeLoadOutcome
import com.cattailsw.nanidroid.runtime.RuntimeNativePort
import com.cattailsw.nanidroid.runtime.RuntimeRequestToken
import com.cattailsw.nanidroid.runtime.RuntimeScheduleKey
import com.cattailsw.nanidroid.runtime.RuntimeScheduleKind
import com.cattailsw.nanidroid.runtime.RuntimeScheduler
import com.cattailsw.nanidroid.runtime.RuntimeSnapshot
import java.io.File
import java.util.Hashtable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class GhostRuntimeHostTestActivity : ComponentActivity() {
    private lateinit var harness: HostAdapterHarness
    private val runtime: RecordingHostRuntime
        get() = harness.hostRuntime
    private lateinit var record: ActivityRecord
    private var hostEpoch = 0L
    private var currentLease: RuntimeHostLease? = null
    private var lastPlayedCueId = 0L
    private var deliveredExitLeaseId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        harness = requireNotNull(GhostRuntimeHostTestEnvironment.harness)
        record = harness.registerActivity(this)
        runtime.submit(RuntimeCommand.RegisterHost(nextLease()))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                harness.awaitCollectorStart()
                runtime.snapshots.collect { snapshot ->
                    harness.beforeSnapshotDelivery(snapshot)
                    render(snapshot)
                    harness.afterSnapshotDelivery(snapshot)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runtime.submit(RuntimeCommand.SetResumed(nextLease(), true))
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (!isTopResumedActivity) harness.recordLifecycle("topResumedFalse")
        runtime.submit(RuntimeCommand.SetTopResumed(nextLease(), isTopResumedActivity))
    }

    override fun onPause() {
        harness.recordLifecycle("onPause")
        runtime.submit(RuntimeCommand.SetResumed(nextLease(), false))
        super.onPause()
    }

    override fun onStop() {
        harness.recordLifecycle("onStop")
        super.onStop()
    }

    override fun onDestroy() {
        runtime.submit(RuntimeCommand.UnregisterHost(nextLease()))
        harness.unregisterActivity(this)
        super.onDestroy()
    }

    override fun finish() {
        record.finishCount.incrementAndGet()
        harness.recordLifecycle("finish")
        super.finish()
    }

    internal fun requestBackForTesting() {
        val snapshot = runtime.snapshots.value
        runtime.submit(
            RuntimeCommand.Back(
                generation = snapshot.generation,
                host = requireNotNull(snapshot.foregroundHost),
                expected = snapshot.modeIdentity,
            ),
        )
    }

    internal fun launchOverlappingHostForTesting() {
        startActivity(Intent(this, GhostRuntimeHostTestActivity::class.java))
    }

    internal fun setTopResumedForTesting(topResumed: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        runtime.submit(RuntimeCommand.SetTopResumed(nextLease(), topResumed))
    }

    private suspend fun render(snapshot: RuntimeSnapshot) {
        harness.renderedRevisions.getOrPut(record.hostId) { CopyOnWriteArrayList() }.add(snapshot.revision)
        val lease = currentLease ?: return
        if (snapshot.foregroundHost != lease) return
        val hostCues = snapshot.cues.filter {
            it.hostLease == lease && it.cueId > lastPlayedCueId
        }
        if (hostCues.isNotEmpty()) {
            val played = harness.playedCues.getOrPut(record.hostId) { CopyOnWriteArrayList() }
            hostCues.mapTo(played) { it.cueId }
            lastPlayedCueId = hostCues.last().cueId
            if (harness.autoAcknowledgeCues) {
                val last = hostCues.last()
                harness.acknowledgedThrough[record.hostId] = last.cueId
                runtime.submit(RuntimeCommand.AcknowledgeCues(lease, last.cueId))
            }
        }
        snapshot.exit?.offeredLease?.takeIf {
            harness.autoDeliverExit && it.hostLease == lease && it.leaseId != deliveredExitLeaseId
        }?.let {
            deliveredExitLeaseId = it.leaseId
            deliverExit(it)
        }
    }

    private fun nextLease(): RuntimeHostLease {
        check(Looper.myLooper() == Looper.getMainLooper())
        hostEpoch += 1L
        return RuntimeHostLease(record.hostId, hostEpoch).also { currentLease = it }
    }

    private fun deliverExit(lease: RuntimeExitLease) {
        runtime.submit(RuntimeCommand.ClaimExit(lease))
        try {
            finish()
        } finally {
            runtime.submit(RuntimeCommand.AcknowledgeExit(lease))
        }
    }
}

internal object GhostRuntimeHostTestEnvironment {
    @Volatile
    var harness: HostAdapterHarness? = null
}

internal data class ActivityRecord(
    val hostId: RuntimeHostId,
    val finishCount: AtomicLong = AtomicLong(),
)

internal data class HostSubmission(
    val hostId: RuntimeHostId,
    val epoch: Long,
    val command: String,
    val mainThread: Boolean,
)

internal class RecordingHostRuntime(
    private val delegate: GhostRuntime,
    private val submissions: ConcurrentLinkedQueue<HostSubmission>,
    private val deliveryTrace: ConcurrentLinkedQueue<String>,
) {
    val snapshots get() = delegate.snapshots

    fun submit(command: RuntimeCommand) {
        when (command) {
            is RuntimeCommand.RegisterHost -> record(command.lease, "RegisterHost")
            is RuntimeCommand.SetResumed -> record(command.lease, "SetResumed")
            is RuntimeCommand.SetTopResumed -> record(command.lease, "SetTopResumed")
            is RuntimeCommand.UnregisterHost -> record(command.lease, "UnregisterHost")
            is RuntimeCommand.ClaimExit -> deliveryTrace += "claim"
            is RuntimeCommand.AcknowledgeExit -> deliveryTrace += "acknowledge"
            else -> Unit
        }
        delegate.submit(command)
    }

    private fun record(lease: RuntimeHostLease, command: String) {
        submissions += HostSubmission(
            lease.hostId,
            lease.hostEpoch,
            command,
            Looper.myLooper() == Looper.getMainLooper(),
        )
    }
}

internal class HostAdapterHarness(
    val autoAcknowledgeCues: Boolean,
    val autoDeliverExit: Boolean,
) : AutoCloseable {
    val scheduler = TestRuntimeScheduler()
    val submissions = ConcurrentLinkedQueue<HostSubmission>()
    val deliveryTrace = ConcurrentLinkedQueue<String>()
    val renderedRevisions = ConcurrentHashMap<RuntimeHostId, CopyOnWriteArrayList<Long>>()
    val playedCues = ConcurrentHashMap<RuntimeHostId, CopyOnWriteArrayList<Long>>()
    val acknowledgedThrough = ConcurrentHashMap<RuntimeHostId, Long>()
    val lifecycleTrace = ConcurrentLinkedQueue<String>()
    private val nextHostId = AtomicLong()
    private val activities = ConcurrentHashMap<GhostRuntimeHostTestActivity, ActivityRecord>()
    @Volatile
    private var collectorStart = CompletableDeferred(Unit)
    @Volatile
    private var deliveryGate: SnapshotDeliveryGate? = null

    val runtime = GhostRuntime.testRuntime(
        context = null,
        preparer = GhostPreparer { operationId, id, root ->
            PreparedGhost(
                operationId = operationId,
                id = id,
                canonicalRoot = root,
                name = id,
                shellName = "master",
                crafterName = null,
                sakuraName = null,
                keroName = null,
                surfaces = SurfaceCatalog.freeze(emptyMap()),
                ghostDescriptor = emptyMap(),
                shellDescriptor = null,
                engine = GhostEngine.Unsupported,
                nanidroidContent = emptyMap(),
            )
        },
        persistence = TestGhostRuntimePersistence,
        nativePort = ImmediateRuntimeNativePort,
        runtimeScheduler = scheduler,
        catalogScanner = RuntimeCatalogScanner {
            listOf(
                InstalledGhostMetadata(
                    id = GHOST_ID,
                    canonicalRoot = GHOST_ROOT,
                    name = GHOST_ID,
                    sakuraName = null,
                    readme = File(GHOST_ROOT, "readme.txt"),
                ),
            )
        },
        canonicalizeRoot = File::getAbsoluteFile,
    )
    val hostRuntime = RecordingHostRuntime(runtime, submissions, deliveryTrace)

    fun registerActivity(activity: GhostRuntimeHostTestActivity): ActivityRecord {
        val record = ActivityRecord(RuntimeHostId(nextHostId.incrementAndGet()))
        activities[activity] = record
        return record
    }

    fun unregisterActivity(activity: GhostRuntimeHostTestActivity) {
        activities.remove(activity)
    }

    fun recordFor(activity: GhostRuntimeHostTestActivity): ActivityRecord = requireNotNull(activities[activity])

    fun awaitReplacement(oldHostId: RuntimeHostId): ActivityRecord {
        var replacement: ActivityRecord? = null
        await {
            replacement = activities.values.firstOrNull { it.hostId != oldHostId }
            replacement != null
        }
        return requireNotNull(replacement)
    }

    fun recordLifecycle(value: String) {
        lifecycleTrace += value
        if (value in setOf("finish", "onPause", "topResumedFalse", "onStop")) deliveryTrace += value
    }

    fun submissionsFor(hostId: RuntimeHostId): List<HostSubmission> = submissions.filter { it.hostId == hostId }

    fun pauseFutureCollectors() {
        collectorStart = CompletableDeferred()
    }

    suspend fun awaitCollectorStart() = collectorStart.await()

    fun releaseCollectors() {
        collectorStart.complete(Unit)
    }

    fun blockNextDelivery(predicate: (RuntimeSnapshot) -> Boolean) {
        check(deliveryGate == null)
        deliveryGate = SnapshotDeliveryGate(predicate)
    }

    suspend fun beforeSnapshotDelivery(snapshot: RuntimeSnapshot) {
        val gate = deliveryGate ?: return
        if (gate.predicate(snapshot) && gate.claimed.compareAndSet(false, true)) {
            gate.snapshot.set(snapshot)
            gate.release.await()
        }
    }

    fun afterSnapshotDelivery(snapshot: RuntimeSnapshot) {
        val gate = deliveryGate ?: return
        if (gate.snapshot.get() === snapshot) gate.resumed.set(true)
    }

    fun awaitBlockedDelivery(): RuntimeSnapshot {
        await { deliveryGate?.snapshot?.get() != null }
        return requireNotNull(deliveryGate?.snapshot?.get())
    }

    fun releaseBlockedDelivery() {
        requireNotNull(deliveryGate).release.complete(Unit)
    }

    fun awaitDeliveryResumed() {
        val gate = requireNotNull(deliveryGate)
        await { gate.resumed.get() }
        deliveryGate = null
    }

    fun awaitForeground(hostId: RuntimeHostId) = await { runtime.snapshots.value.foregroundHost?.hostId == hostId }

    fun startAttached() {
        runtime.submit(RuntimeCommand.StartGhost(GHOST_ID, GHOST_ROOT))
        await { runtime.snapshots.value.phase == GhostRuntimePhase.Attached }
    }

    fun enqueueAndAdvance(script: String, cueCount: Int) {
        runtime.enqueueScriptForTesting(script)
        repeat(cueCount) { runPlaybackStep() }
        await {
            runtime.snapshots.value.cues.size == cueCount.coerceAtMost(64) || runtime.snapshots.value.foregroundHost == null
        }
    }

    fun advanceUntilTalkStops(maxSteps: Int = 10) {
        repeat(maxSteps) {
            if (!runtime.snapshots.value.mode.playingTalk && !scheduler.has(RuntimeScheduleKind.PLAYBACK)) return
            runPlaybackStep()
        }
        await { !runtime.snapshots.value.mode.playingTalk }
    }

    fun advanceUntilPresentationText(expected: String, maxSteps: Int = 20) {
        repeat(maxSteps) {
            if (runtime.snapshots.value.presentation.sakura.text == expected) return
            runPlaybackStep()
        }
        await { runtime.snapshots.value.presentation.sakura.text == expected }
    }

    private fun runPlaybackStep() {
        val beforeRevision = runtime.snapshots.value.revision
        val beforePlaybackDueCount = playbackDueCount()
        scheduler.awaitAndRun(RuntimeScheduleKind.PLAYBACK)
        await {
            playbackDueCount() > beforePlaybackDueCount && runtime.snapshots.value.revision > beforeRevision
        }
    }

    private fun playbackDueCount(): Int = runtime.snapshotCommandTraceForTesting().count { it == "PlaybackDue" }

    fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate()) {
            if (System.nanoTime() >= deadline) throw AssertionError("runtime condition did not settle: ${runtime.snapshots.value}")
            Thread.sleep(5L)
        }
    }

    override fun close() {
        val finished = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            activities.keys.toList().asReversed().forEach { it.finish() }
            finished.countDown()
        }
        finished.await(5L, TimeUnit.SECONDS)
        val destructionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (activities.isNotEmpty() && System.nanoTime() < destructionDeadline) {
            Thread.sleep(5L)
        }
        check(activities.isEmpty()) { "test host Activities did not finish" }
        runtime.close()
    }

    private companion object {
        const val GHOST_ID = "host-adapter"
        val GHOST_ROOT = File("build/android-host-adapter/host-adapter").absoluteFile
    }

    private class SnapshotDeliveryGate(
        val predicate: (RuntimeSnapshot) -> Boolean,
        val claimed: AtomicBoolean = AtomicBoolean(),
        val snapshot: AtomicReference<RuntimeSnapshot?> = AtomicReference(),
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
        val resumed: AtomicBoolean = AtomicBoolean(),
    )
}

internal class TestRuntimeScheduler : RuntimeScheduler {
    private data class Scheduled(val key: RuntimeScheduleKey, val action: () -> Unit)
    private val scheduled = LinkedHashMap<RuntimeScheduleKey, Scheduled>()
    private val closed = AtomicBoolean()

    override fun schedule(key: RuntimeScheduleKey, delayMillis: Long, action: () -> Unit) {
        synchronized(scheduled) {
            if (!closed.get()) scheduled[key] = Scheduled(key, action)
        }
    }

    override fun cancel(key: RuntimeScheduleKey) {
        synchronized(scheduled) { scheduled.remove(key) }
    }

    fun has(kind: RuntimeScheduleKind): Boolean = synchronized(scheduled) {
        scheduled.values.any { it.key.kind == kind }
    }

    fun scheduledKinds(): List<RuntimeScheduleKind> = synchronized(scheduled) {
        scheduled.values.map { it.key.kind }
    }

    fun awaitAndRun(kind: RuntimeScheduleKind) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var action: (() -> Unit)? = null
        while (action == null) {
            synchronized(scheduled) {
                val entry = scheduled.entries.firstOrNull { it.value.key.kind == kind }
                if (entry != null) {
                    scheduled.remove(entry.key)
                    action = entry.value.action
                }
            }
            if (action == null) {
                if (System.nanoTime() >= deadline) throw AssertionError("scheduled $kind action did not appear")
                Thread.sleep(5L)
            }
        }
        requireNotNull(action).invoke()
    }

    override fun close() {
        closed.set(true)
        synchronized(scheduled) { scheduled.clear() }
    }
}

private object ImmediateRuntimeNativePort : RuntimeNativePort {
    override fun load(
        operationId: Long,
        generation: Long,
        prepared: PreparedGhost,
        complete: (RuntimeNativeLoadOutcome) -> Unit,
    ) = complete(RuntimeNativeLoadOutcome.Loaded(com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities()))

    override fun request(
        token: RuntimeRequestToken,
        intent: ShioriRequestIntent,
        fallback: ShioriRequestIntent?,
        complete: (RuntimeResult<TaggedShioriResponse>) -> Unit,
    ) = complete(
        RuntimeResult.Success(
            TaggedShioriResponse(
                token.generation,
                ShioriResponse("SHIORI/3.0 204 No Content", Hashtable()),
            ),
        ),
    )

    override fun unload(
        operationId: Long,
        generation: Long,
        complete: (RuntimeNativeLifecycleOutcome) -> Unit,
    ) = complete(RuntimeNativeLifecycleOutcome.Success)
}

private object TestGhostRuntimePersistence : GhostRuntimePersistence {
    override fun readLastRunGhostId(): String? = null
    override fun commitLastRunGhostId(ghostId: String) = Unit
    override fun readActivationCount(ghostId: String): Long = 0L
    override fun commitActivationCount(ghostId: String, count: Long) = Unit
}
