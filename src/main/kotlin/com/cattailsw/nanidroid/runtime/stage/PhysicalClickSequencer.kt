package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.unit.IntOffset
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.Support
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import java.util.concurrent.atomic.AtomicBoolean

fun interface ClickDeadlineScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle
}

fun interface CancellationHandle {
    fun cancel()
}

class IdempotentCancellationHandle(private val cancelAction: () -> Unit) : CancellationHandle {
    private val cancelled = AtomicBoolean(false)
    override fun cancel() {
        if (cancelled.compareAndSet(false, true)) cancelAction()
    }
}

data class PhysicalClickKey(
    val source: PointerSource,
    val button: Int,
    val speaker: SurfaceSpeaker,
    val collisionIdentifier: String?,
)

class PhysicalClickSequencer(
    private val scheduler: ClickDeadlineScheduler,
    private val dispatch: (SurfaceInteractionEffect) -> Unit,
) {
    private data class PendingClick(
        val id: Long,
        val key: PhysicalClickKey,
        val effect: SurfaceInteractionEffect,
        val stagePoint: IntOffset,
        val deadlineMillis: Long,
        val slopPx: Float,
        val geometryToken: Any,
        var cancellation: CancellationHandle? = null,
    )

    private val pending = mutableListOf<PendingClick>()
    private var nextId = 1L
    private var retainedGeometryToken: Any? = null

    @Synchronized
    fun activate(
        effect: SurfaceInteractionEffect,
        stagePoint: IntOffset,
        eventTimeMillis: Long,
        doubleClickTimeoutMillis: Long,
        doubleClickSlopPx: Float,
        geometryToken: Any,
        doubleClickSupport: Support = Support.UNKNOWN,
    ) {
        require(doubleClickTimeoutMillis >= 0L)
        require(doubleClickSlopPx.isFinite() && doubleClickSlopPx >= 0f)
        if (retainedGeometryToken != null && retainedGeometryToken != geometryToken) cancelAllLocked()
        retainedGeometryToken = geometryToken

        val key = effect.key()
        val matching = pending.asReversed().firstOrNull { first ->
            first.key == key &&
                first.geometryToken == geometryToken &&
                eventTimeMillis < first.deadlineMillis &&
                withinSlop(first.stagePoint, stagePoint, minOf(first.slopPx, doubleClickSlopPx))
        }
        if (matching != null) {
            pending.remove(matching)
            matching.cancellation?.cancel()
            if (doubleClickSupport != Support.UNSUPPORTED) {
                dispatch(effect.copy(kind = PointerEventKind.DOUBLE_CLICK))
            }
            return
        }

        val id = nextId++
        val click = PendingClick(
            id = id,
            key = key,
            effect = effect.copy(kind = PointerEventKind.CLICK),
            stagePoint = stagePoint,
            deadlineMillis = saturatingAdd(eventTimeMillis, doubleClickTimeoutMillis),
            slopPx = doubleClickSlopPx,
            geometryToken = geometryToken,
        )
        pending += click
        click.cancellation = scheduler.schedule(doubleClickTimeoutMillis) { fire(id) }
    }

    @Synchronized
    fun retainGeometry(geometryToken: Any) {
        if (retainedGeometryToken != null && retainedGeometryToken != geometryToken) cancelAllLocked()
        retainedGeometryToken = geometryToken
    }

    @Synchronized
    fun cancelAll() {
        cancelAllLocked()
        retainedGeometryToken = null
    }

    @Synchronized
    private fun fire(id: Long) {
        val click = pending.firstOrNull { it.id == id } ?: return
        pending.remove(click)
        dispatch(click.effect)
    }

    private fun cancelAllLocked() {
        pending.forEach { it.cancellation?.cancel() }
        pending.clear()
    }

    private fun SurfaceInteractionEffect.key() = PhysicalClickKey(
        source = source,
        button = button,
        speaker = speaker,
        collisionIdentifier = collisionIdentifier,
    )
}

private fun withinSlop(first: IntOffset, second: IntOffset, slop: Float): Boolean {
    val dx = first.x.toDouble() - second.x.toDouble()
    val dy = first.y.toDouble() - second.y.toDouble()
    return dx * dx + dy * dy <= slop.toDouble() * slop.toDouble()
}

private fun saturatingAdd(first: Long, second: Long): Long =
    if (second > Long.MAX_VALUE - first) Long.MAX_VALUE else first + second
