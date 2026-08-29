package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.PreparedGhost
import com.cattailsw.nanidroid.RuntimeResult
import com.cattailsw.nanidroid.ShioriRequestIntent
import com.cattailsw.nanidroid.TaggedShioriResponse
import java.io.Closeable
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
        check(commands.trySend(action).isSuccess) { "Runtime coordination dispatcher is closed" }
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
        complete: (RuntimeNativeLifecycleOutcome) -> Unit,
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
