package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.geometry.Offset
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

class PhysicalClickReservation internal constructor(internal val id: Long)

class PhysicalClickSequencer(
    private val scheduler: ClickDeadlineScheduler,
    private val nowMillis: () -> Long,
    private val dispatch: (SurfaceInteractionEffect) -> Unit,
) {
    private data class PendingClick(
        val id: Long,
        val key: PhysicalClickKey,
        val effect: SurfaceInteractionEffect,
        val stagePoint: Offset,
        val deadlineMillis: Long,
        val slopPx: Float,
        val geometryToken: Any,
        val liveGeometryToken: () -> Any,
        var cancellation: CancellationHandle? = null,
    )

    private val pending = mutableListOf<PendingClick>()
    private val reserved = mutableMapOf<Long, PendingClick>()
    private var nextId = 1L
    private var retainedGeometryToken: Any? = null

    @Synchronized
    fun activate(
        effect: SurfaceInteractionEffect,
        stagePoint: Offset,
        eventTimeMillis: Long,
        doubleClickTimeoutMillis: Long,
        doubleClickSlopPx: Float,
        geometryToken: Any,
        liveGeometryToken: () -> Any = { geometryToken },
    ) {
        require(doubleClickTimeoutMillis >= 0L)
        require(doubleClickSlopPx.isFinite() && doubleClickSlopPx >= 0f)
        if (retainedGeometryToken != null && retainedGeometryToken != geometryToken) cancelAllLocked()
        retainedGeometryToken = geometryToken

        val key = effect.key()
        val id = nextId++
        val deadlineMillis = saturatingAdd(eventTimeMillis, doubleClickTimeoutMillis)
        val click = PendingClick(
            id = id,
            key = key,
            effect = effect.copy(kind = PointerEventKind.CLICK),
            stagePoint = stagePoint,
            deadlineMillis = deadlineMillis,
            slopPx = doubleClickSlopPx,
            geometryToken = geometryToken,
            liveGeometryToken = liveGeometryToken,
        )
        pending += click
        val remaining = (deadlineMillis - nowMillis()).coerceAtLeast(0L)
        if (remaining == 0L) {
            fire(id)
        } else {
            click.cancellation = scheduler.schedule(remaining) { fire(id) }
        }
    }

    /** Reserves a completed first click at the second DOWN so its deadline cannot race the second UP. */
    @Synchronized
    fun reserveSecond(
        effect: SurfaceInteractionEffect,
        stagePoint: Offset,
        eventTimeMillis: Long,
        doubleClickSlopPx: Float,
        geometryToken: Any,
    ): PhysicalClickReservation? {
        require(doubleClickSlopPx.isFinite() && doubleClickSlopPx >= 0f)
        if (retainedGeometryToken != null && retainedGeometryToken != geometryToken) cancelAllLocked()
        retainedGeometryToken = geometryToken
        val key = effect.key()
        val matching = pending.asReversed().firstOrNull { first ->
            first.key == key &&
                first.geometryToken == geometryToken &&
                eventTimeMillis < first.deadlineMillis &&
                withinSlop(first.stagePoint, stagePoint, minOf(first.slopPx, doubleClickSlopPx))
        } ?: return null
        pending.remove(matching)
        matching.cancellation?.cancel()
        matching.cancellation = null
        reserved[matching.id] = matching
        return PhysicalClickReservation(matching.id)
    }

    @Synchronized
    fun completeSecond(
        reservation: PhysicalClickReservation,
        effect: SurfaceInteractionEffect,
        geometryToken: Any,
        doubleClickSupport: Support = Support.UNKNOWN,
    ) {
        val first = reserved.remove(reservation.id) ?: return
        if (
            first.geometryToken == geometryToken &&
            first.liveGeometryToken() == first.geometryToken &&
            first.key == effect.key() &&
            doubleClickSupport != Support.UNSUPPORTED
        ) {
            dispatch(effect.copy(kind = PointerEventKind.DOUBLE_CLICK))
        }
    }

    /** Restores the first single after an invalid second gesture, unless geometry invalidated it. */
    @Synchronized
    fun cancelSecond(
        reservation: PhysicalClickReservation,
        restorePending: Boolean,
    ) {
        val first = reserved.remove(reservation.id) ?: return
        if (!restorePending || first.liveGeometryToken() != first.geometryToken) return
        val remaining = (first.deadlineMillis - nowMillis()).coerceAtLeast(0L)
        if (remaining == 0L) {
            dispatch(first.effect)
        } else {
            pending += first
            first.cancellation = scheduler.schedule(remaining) { fire(first.id) }
        }
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
        if (click.liveGeometryToken() == click.geometryToken) dispatch(click.effect)
    }

    private fun cancelAllLocked() {
        pending.forEach { it.cancellation?.cancel() }
        pending.clear()
        reserved.clear()
    }

    private fun SurfaceInteractionEffect.key() = PhysicalClickKey(
        source = source,
        button = button,
        speaker = speaker,
        collisionIdentifier = collisionIdentifier,
    )
}

private fun withinSlop(first: Offset, second: Offset, slop: Float): Boolean {
    val dx = first.x.toDouble() - second.x.toDouble()
    val dy = first.y.toDouble() - second.y.toDouble()
    return dx * dx + dy * dy <= slop.toDouble() * slop.toDouble()
}

private fun saturatingAdd(first: Long, second: Long): Long =
    if (second > Long.MAX_VALUE - first) Long.MAX_VALUE else first + second
