package com.cattailsw.nanidroid.engine

import com.cattailsw.nanidroid.shiori.Kawari
import com.cattailsw.nanidroid.shiori.SatoriShiori
import com.cattailsw.nanidroid.shiori.YayaShiori
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

enum class NativeKind { SATORI, KAWARI, YAYA }
enum class NativeAvailability { Available, Occupied, Quarantined }
class NativeLease internal constructor(internal val generation: Long)
sealed interface NativeLoadResult {
    data class Loaded(val lease: NativeLease) : NativeLoadResult
    data class Failed(val status: Int) : NativeLoadResult
    data class Unresolved(val error: String) : NativeLoadResult
}
sealed interface NativeUnloadResult {
    data object Unloaded : NativeUnloadResult
    data object Empty : NativeUnloadResult
    data object Stale : NativeUnloadResult
    data class Unresolved(val error: String) : NativeUnloadResult
}
class NativeTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal data class NativeObservation(
    val loads: Int,
    val unloads: Int,
    val events: List<String>,
    val requestOrdinal: Long,
    val eventCounts: Map<String, Long>,
) {
    fun eventCount(id: String): Long = requireNotNull(eventCounts[id]) { "Event ID is not tracked: $id" }
}
internal data class NativeReplyObservation(
    val eventId: String,
    val ordinal: Long,
    val references: List<String>,
    val reply: ShioriReply,
)

interface NativeBinding {
    fun load(directory: File, libraryName: String): Int
    fun unload(): Boolean
    fun request(bytes: ByteArray): ByteArray
    fun transportCharset(): String
}

/** Production callers share [process]. No Activity owns or resets a native engine. */
class NativeShioriHost private constructor(
    private val lane: Lane,
    private val bindings: (NativeKind) -> NativeBinding,
    private val deadlinePause: suspend (Long) -> Unit = { delay(it) },
) {
    val availability: StateFlow<NativeAvailability> = lane.availability

    /** Read-only counters for device lifecycle assertions; no native operation is performed. */
    internal fun observationForTest(): NativeObservation = synchronized(lane.gate) {
        NativeObservation(lane.loadCount, lane.unloadCount, lane.eventIds.toList(), lane.requestOrdinal,
            lane.eventCounts?.toMap().orEmpty())
    }

    /** Counts only explicitly selected IDs until closed; snapshots are immutable. */
    internal fun trackEventsForTest(ids: Set<String>): AutoCloseable = synchronized(lane.gate) {
        check(lane.eventCounts == null) { "Event tracking is already active" }
        val counts = ids.associateWith { 0L }.toMutableMap()
        lane.eventCounts = counts
        AutoCloseable { synchronized(lane.gate) {
            if (lane.eventCounts === counts) lane.eventCounts = null
        } }
    }

    /** One removable observer; invoked after decode on the native lane. */
    internal fun setReplyObserverForTest(observer: ((NativeReplyObservation) -> Unit)?) = synchronized(lane.gate) {
        lane.replyObserver = observer
    }

    @OptIn(InternalCoroutinesApi::class)
    suspend fun load(kind: NativeKind, directory: File, libraryName: String): NativeLoadResult {
        val caller = currentCoroutineContext()[Job]
        val attempt = Any()
        val cancellation = caller?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) CoroutineScope(lane.dispatcher).launch { lane.cleanupCancelledOwner(attempt) }
        }
        return try {
            // JNI load is synchronous. Completion and ownership cleanup continue even
            // when the waiting coroutine is cancelled.
            withContext(NonCancellable + lane.dispatcher) { loadOnLane(kind, directory, libraryName, caller, attempt) }
        } finally {
            cancellation?.dispose()
        }
    }

    private fun loadOnLane(kind: NativeKind, directory: File, libraryName: String, caller: Job?, attempt: Any): NativeLoadResult {
        synchronized(lane.gate) {
            if (lane.quarantined) return NativeLoadResult.Unresolved("Native ownership is unresolved")
            if (lane.owner != null) return NativeLoadResult.Failed(-1)
        }
        return try {
            val binding = bindings(kind)
            if (!lane.admit()) return NativeLoadResult.Unresolved("Native ownership is unresolved")
            when (val status = binding.load(directory, libraryName)) {
                1 -> {
                    val lease = synchronized(lane.gate) {
                        if (lane.quarantined) return NativeLoadResult.Unresolved("Native ownership is unresolved")
                        NativeLease(++lane.generation).also {
                            lane.owner = Owner(it, kind, binding, attempt)
                            lane.loadCount++
                            lane.availability.value = NativeAvailability.Occupied
                        }
                    }
                    if (caller?.isCancelled == true) {
                        lane.cleanupCancelledOwner(attempt)
                        if (lane.isQuarantined()) NativeLoadResult.Unresolved("Cancelled load cleanup failed; ownership is unresolved")
                        else NativeLoadResult.Failed(0)
                    } else NativeLoadResult.Loaded(lease)
                }
                0 -> if (lane.isQuarantined()) NativeLoadResult.Unresolved("Native ownership is unresolved") else NativeLoadResult.Failed(0)
                else -> {
                    lane.quarantine()
                    NativeLoadResult.Unresolved("Native load returned $status; ownership is unresolved")
                }
            }
        } catch (error: Throwable) {
            lane.quarantine()
            NativeLoadResult.Unresolved("Native load threw ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    suspend fun request(lease: NativeLease, event: ShioriEvent): ShioriReply = onLane {
        val owner = synchronized(lane.gate) { lane.owner?.takeIf { it.lease === lease && !lane.quarantined } }
            ?: throw NativeTransportException("No active native owner for this lease")
        val references = event.references.toList()
        val ordinal = lane.noteEvent(event.id)
        try {
            val before = if (owner.kind == NativeKind.YAYA) resolveCharset(lane.jni { owner.binding.transportCharset() })
                else SHIFT_JIS
            val request = ShioriCodec.encode(event, before, owner.kind == NativeKind.SATORI)
            val response = lane.jni { owner.binding.request(request) }
            val after = if (owner.kind == NativeKind.YAYA) {
                val raw = lane.jni { owner.binding.transportCharset() }
                try { resolveCharset(raw) }
                catch (error: NativeTransportException) { ShioriCodec.declaredCharset(response) ?: throw error }
            } else SHIFT_JIS
            ShioriCodec.decode(response, after).also { lane.noteReply(event.id, ordinal, references, it) }
        } catch (error: NativeTransportException) {
            throw error
        } catch (error: Throwable) {
            lane.quarantine()
            throw NativeTransportException("Native request failed: ${error.message}", error)
        }
    }

    suspend fun unload(lease: NativeLease): NativeUnloadResult = withContext(NonCancellable + lane.dispatcher) {
        val owner = synchronized(lane.gate) { lane.owner } ?: return@withContext NativeUnloadResult.Empty
        if (owner.lease !== lease) return@withContext NativeUnloadResult.Stale
        if (lane.isQuarantined()) return@withContext NativeUnloadResult.Unresolved("Native ownership is unresolved")
        try {
            if (lane.jni { owner.binding.unload() }) {
                synchronized(lane.gate) {
                    if (lane.quarantined) NativeUnloadResult.Unresolved("Native ownership is unresolved")
                    else {
                        lane.owner = null
                        lane.unloadCount++
                        lane.availability.value = NativeAvailability.Available
                        NativeUnloadResult.Unloaded
                    }
                }
            } else {
                lane.quarantine()
                NativeUnloadResult.Unresolved("Native unload returned false")
            }
        } catch (error: Throwable) {
            lane.quarantine()
            NativeUnloadResult.Unresolved("Native unload threw ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    suspend fun shutdown(
        lease: NativeLease,
        notifyDestroy: Boolean = true,
        timeoutMillis: Long = 5_000L,
    ): NativeShutdownResult {
        val operation = synchronized(lane.gate) {
            lane.shutdowns[lease] ?: run {
                if (lane.quarantined) return NativeShutdownResult.Unresolved("Native ownership is unresolved")
                if (lane.owner?.lease !== lease) return NativeShutdownResult.Failed("No active native owner for this lease")
                ShutdownOperation(
                    lease,
                    notifyDestroy,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0)),
                ).also { lane.shutdowns[lease] = it }
            }
        }
        if (operation.started.compareAndSet(false, true)) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                while (!operation.result.isCompleted) {
                    val remaining = operation.deadline - System.nanoTime()
                    if (remaining <= 0) {
                        lane.finish(operation, NativeShutdownResult.Unresolved("Native shutdown deadline expired"), quarantine = true)
                        break
                    }
                    // Round upward; a scheduler or injected pause can still wake early.
                    // Recheck the monotonic clock before claiming the deadline.
                    val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
                    deadlinePause(millis + if (TimeUnit.MILLISECONDS.toNanos(millis) < remaining) 1 else 0)
                }
            }
            CoroutineScope(SupervisorJob() + lane.dispatcher).launch { shutdownOnLane(operation) }
        }
        return withContext(NonCancellable) { operation.result.await() }
    }

    private fun shutdownOnLane(operation: ShutdownOperation) {
        val owner = synchronized(lane.gate) {
            if (lane.quarantined || operation.result.isCompleted) return
            lane.owner?.takeIf { it.lease === operation.lease }
        }
        if (owner == null) {
            lane.finish(operation, NativeShutdownResult.Failed("No active native owner for this lease"))
            return
        }
        var failure: String? = null
        if (operation.notifyDestroy) {
            try {
                lane.noteEvent("OnDestroy")
                val before = if (owner.kind == NativeKind.YAYA) resolveCharset(lane.shutdownJni(operation) { owner.binding.transportCharset() }) else SHIFT_JIS
                val bytes = ShioriCodec.encode(ShioriEvent("OnDestroy", method = ShioriMethod.NOTIFY), before, owner.kind == NativeKind.SATORI)
                val response = lane.shutdownJni(operation) { owner.binding.request(bytes) }
                val after = if (owner.kind == NativeKind.YAYA) {
                    val raw = lane.shutdownJni(operation) { owner.binding.transportCharset() }
                    try { resolveCharset(raw) }
                    catch (error: NativeTransportException) { ShioriCodec.declaredCharset(response) ?: throw error }
                } else SHIFT_JIS
                val status = ShioriCodec.decode(response, after).status
                if (status != 200 && status != 204) failure = "OnDestroy returned $status"
            } catch (error: AbandonedShutdown) {
                return
            } catch (error: NativeTransportException) {
                failure = "OnDestroy transport error: ${error.message}"
            } catch (error: Throwable) {
                lane.finish(operation, NativeShutdownResult.Unresolved("OnDestroy transport failed: ${error.message}"), quarantine = true)
                return
            }
        }
        try {
            val unloaded = lane.shutdownJni(operation) { owner.binding.unload() }
            if (!unloaded) {
                lane.finish(operation, NativeShutdownResult.Unresolved("Native unload returned false"), quarantine = true)
            } else {
                lane.finish(operation, failure?.let { NativeShutdownResult.Failed(it) } ?: NativeShutdownResult.Completed, release = true)
            }
        } catch (_: AbandonedShutdown) {
            // The deadline owns the terminal result and quarantine.
        } catch (error: Throwable) {
            lane.finish(operation, NativeShutdownResult.Unresolved("Native unload threw ${error.javaClass.simpleName}: ${error.message}"), quarantine = true)
        }
    }

    private suspend fun <T> onLane(block: () -> T): T = withContext(lane.dispatcher) { block() }

    private class Owner(val lease: NativeLease, val kind: NativeKind, val binding: NativeBinding, val attempt: Any)
    private class ShutdownOperation(val lease: NativeLease, val notifyDestroy: Boolean, val deadline: Long) {
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val result = CompletableDeferred<NativeShutdownResult>()
    }
    private class AbandonedShutdown : RuntimeException()
    private class Lane {
        val gate = Any()
        val dispatcher: CoroutineDispatcher = nativeDispatcher
        val availability = MutableStateFlow(NativeAvailability.Available)
        var owner: Owner? = null
        var generation = 0L
        var loadCount = 0
        var unloadCount = 0
        var requestOrdinal = 0L
        var replyObserver: ((NativeReplyObservation) -> Unit)? = null
        val eventIds = mutableListOf<String>()
        var eventCounts: MutableMap<String, Long>? = null
        fun noteEvent(id: String) = synchronized(gate) {
            eventCounts?.let { counts -> counts[id]?.let { counts[id] = it + 1 } }
            if (eventIds.size == 128) eventIds.removeAt(0)
            eventIds.add(id)
            ++requestOrdinal
        }
        fun noteReply(id: String, ordinal: Long, references: List<String>, reply: ShioriReply) {
            val observer = synchronized(gate) { replyObserver } ?: return
            try { observer(NativeReplyObservation(id, ordinal, references, reply)) } catch (_: Throwable) { /* Observation cannot alter dispatch. */ }
        }
        var quarantined = false
        val shutdowns = mutableMapOf<NativeLease, ShutdownOperation>()
        fun isQuarantined() = synchronized(gate) { quarantined }
        fun admit() = synchronized(gate) { !quarantined }
        fun <T> jni(call: () -> T): T {
            if (!admit()) throw NativeTransportException("Native ownership is unresolved")
            return call()
        }
        fun <T> shutdownJni(operation: ShutdownOperation, call: () -> T): T {
            synchronized(gate) {
                if (quarantined || operation.result.isCompleted || System.nanoTime() >= operation.deadline) {
                    finish(operation, NativeShutdownResult.Unresolved("Native shutdown deadline expired"), quarantine = true)
                    throw AbandonedShutdown()
                }
            }
            return call()
        }
        fun finish(operation: ShutdownOperation, result: NativeShutdownResult, quarantine: Boolean = false, release: Boolean = false) {
            synchronized(gate) {
                if (operation.result.isCompleted) return
                if (quarantine || System.nanoTime() >= operation.deadline) {
                    this.quarantined = true
                    availability.value = NativeAvailability.Quarantined
                    operation.result.complete(NativeShutdownResult.Unresolved("Native shutdown deadline or ownership unresolved"))
                } else {
                    if (release) {
                        owner = null
                        unloadCount++
                        availability.value = NativeAvailability.Available
                    }
                    operation.result.complete(result)
                }
            }
        }
        fun quarantine() = synchronized(gate) { quarantined = true; availability.value = NativeAvailability.Quarantined }
        fun cleanupCancelledOwner(attempt: Any) {
            val active = synchronized(gate) { owner?.takeIf { it.attempt === attempt && !quarantined } } ?: return
            try {
                if (jni { active.binding.unload() }) {
                    synchronized(gate) {
                        if (!quarantined) {
                            owner = null
                            availability.value = NativeAvailability.Available
                        }
                    }
                } else quarantine()
            } catch (_: Throwable) { quarantine() }
        }
    }

    companion object {
        private val nativeDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Nanidroid-NativeShiori").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        private val SHIFT_JIS = Charset.forName("Shift_JIS")
        val process: NativeShioriHost by lazy { NativeShioriHost(Lane(), ::realBinding) }

        /** Isolated state is only for local fake-binding tests. */
        internal fun forTest(binding: (NativeKind) -> NativeBinding): NativeShioriHost = NativeShioriHost(Lane(), binding)
        internal fun forTest(
            binding: (NativeKind) -> NativeBinding,
            deadlinePause: suspend (Long) -> Unit,
        ): NativeShioriHost = NativeShioriHost(Lane(), binding, deadlinePause)

        private fun resolveCharset(raw: String): Charset {
            val name = when (raw.trim().lowercase()) {
                "default", "osnative" -> Charset.defaultCharset().name()
                "binary" -> "ISO-8859-1"
                else -> raw.trim()
            }
            return try { Charset.forName(name) }
            catch (error: Exception) { throw NativeTransportException("Invalid YAYA transport charset: $raw", error) }
        }

        private fun realBinding(kind: NativeKind): NativeBinding = when (kind) {
            NativeKind.KAWARI -> object : NativeBinding {
                private val native = Kawari()
                override fun load(directory: File, libraryName: String) = native.nativeLoad(directory.absolutePath + File.separator)
                override fun unload() = native.nativeUnload()
                override fun request(bytes: ByteArray) = native.requestFromJNI(bytes)
                override fun transportCharset() = "Shift_JIS"
            }
            NativeKind.SATORI -> object : NativeBinding {
                private val native = SatoriShiori()
                override fun load(directory: File, libraryName: String) = native.nativeLoad(directory.absolutePath + File.separator, directory.absolutePath)
                override fun unload() = native.nativeUnload()
                override fun request(bytes: ByteArray) = native.nativeRequest(bytes)
                override fun transportCharset() = "Shift_JIS"
            }
            NativeKind.YAYA -> object : NativeBinding {
                private val native = YayaShiori()
                override fun load(directory: File, libraryName: String) = native.nativeLoad(directory.absolutePath + File.separator, directory.absolutePath)
                override fun unload() = native.nativeUnload()
                override fun request(bytes: ByteArray) = native.nativeRequest(bytes)
                override fun transportCharset() = native.nativeTransportCharset()
            }
        }
    }
}
