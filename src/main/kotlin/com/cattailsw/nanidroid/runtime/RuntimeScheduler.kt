package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.PreparedGhost
import com.cattailsw.nanidroid.RuntimeResult
import com.cattailsw.nanidroid.ShioriRequestIntent
import com.cattailsw.nanidroid.TaggedShioriResponse
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal interface RuntimeScheduler : Closeable {
    fun schedule(key: RuntimeScheduleKey, delayMillis: Long, action: () -> Unit)
    fun cancel(key: RuntimeScheduleKey)
}

internal class ApplicationRuntimeScheduler : RuntimeScheduler {
    private val lock = Any()
    private val executor = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "GhostRuntime-Scheduler").apply { isDaemon = true }
    }
    private val scheduled = mutableMapOf<RuntimeScheduleKey, ScheduledFuture<*>>()
    private var closed = false

    override fun schedule(key: RuntimeScheduleKey, delayMillis: Long, action: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            scheduled.remove(key)?.cancel(false)
            scheduled[key] = executor.schedule(
                {
                    synchronized(lock) { scheduled.remove(key) }
                    action()
                },
                delayMillis.coerceAtLeast(0L),
                TimeUnit.MILLISECONDS,
            )
        }
    }

    override fun cancel(key: RuntimeScheduleKey) {
        synchronized(lock) { scheduled.remove(key)?.cancel(false) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            scheduled.values.forEach { it.cancel(false) }
            scheduled.clear()
            executor.shutdown()
        }
    }
}

internal data class RuntimeScheduleKey(
    val generation: Long,
    val kind: RuntimeScheduleKind,
    val token: Long,
)

internal enum class RuntimeScheduleKind { PLAYBACK, CLOCK, INPUT_TIMEOUT }

internal interface RuntimeCommandDispatcher : Closeable {
    fun dispatch(action: () -> Unit)
}

internal class SerializedRuntimeCommandDispatcher : RuntimeCommandDispatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in commands) command()
        }
    }

    override fun dispatch(action: () -> Unit) {
        commands.trySend(action)
    }

    override fun close() {
        commands.close()
        scope.cancel()
    }
}

internal interface RuntimeNativePort {
    fun load(
        operationId: Long,
        generation: Long,
        prepared: PreparedGhost,
        complete: (RuntimeNativeLoadOutcome) -> Unit,
    )

    fun request(
        token: RuntimeRequestToken,
        intent: ShioriRequestIntent,
        fallback: ShioriRequestIntent?,
        complete: (RuntimeResult<TaggedShioriResponse>) -> Unit,
    )

    fun unload(
        operationId: Long,
        generation: Long,
        complete: (RuntimeNativeLifecycleOutcome) -> Unit,
    )
}
