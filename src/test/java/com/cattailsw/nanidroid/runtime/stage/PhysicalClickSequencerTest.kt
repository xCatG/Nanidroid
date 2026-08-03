package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.unit.IntOffset
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.Support
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import java.util.PriorityQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalClickSequencerTest {
    @Test
    fun `mouse pen and eraser singles wait for their deadlines`() {
        val fixture = Fixture()
        listOf(PointerSource.MOUSE, PointerSource.PEN, PointerSource.ERASER).forEachIndexed { index, source ->
            fixture.activate(effect(source, collision = "c$index"), IntOffset(index * 20, 0), time = 0)
        }

        assertTrue(fixture.effects.isEmpty())
        fixture.scheduler.advanceTo(499)
        assertTrue(fixture.effects.isEmpty())
        fixture.scheduler.advanceTo(500)
        assertEquals(listOf(PointerSource.MOUSE, PointerSource.PEN, PointerSource.ERASER), fixture.effects.map { it.source })
        assertTrue(fixture.effects.all { it.kind == PointerEventKind.CLICK })
    }

    @Test
    fun `matching second click cancels single and emits exactly one double`() {
        val fixture = Fixture()
        fixture.activate(effect(PointerSource.MOUSE), IntOffset(20, 20), time = 0)
        fixture.activate(effect(PointerSource.MOUSE), IntOffset(23, 24), time = 200)
        fixture.scheduler.advanceTo(1_000)

        assertEquals(listOf(PointerEventKind.DOUBLE_CLICK), fixture.effects.map { it.kind })
    }

    @Test
    fun `explicitly unsupported double suppresses the matched pair entirely`() {
        val fixture = Fixture(doubleSupport = Support.UNSUPPORTED)
        fixture.activate(effect(PointerSource.MOUSE), IntOffset(20, 20), time = 0)
        fixture.activate(effect(PointerSource.MOUSE), IntOffset(21, 21), time = 100)
        fixture.scheduler.advanceTo(1_000)

        assertTrue(fixture.effects.isEmpty())
    }

    @Test
    fun `cross source button speaker and case-preserved collision remain independent`() {
        val fixture = Fixture()
        fixture.activate(effect(PointerSource.MOUSE, collision = "Head"), IntOffset(0, 0), time = 0)
        fixture.activate(effect(PointerSource.PEN, collision = "Head"), IntOffset(0, 0), time = 10)
        fixture.activate(effect(PointerSource.MOUSE, button = 1, collision = "Head"), IntOffset(0, 0), time = 20)
        fixture.activate(effect(PointerSource.MOUSE, speaker = SurfaceSpeaker.KERO, collision = "Head"), IntOffset(0, 0), time = 30)
        fixture.activate(effect(PointerSource.MOUSE, collision = "head"), IntOffset(0, 0), time = 40)
        fixture.scheduler.advanceTo(1_000)

        assertEquals(5, fixture.effects.size)
        assertTrue(fixture.effects.all { it.kind == PointerEventKind.CLICK })
    }

    @Test
    fun `distant same-key clicks and simultaneous keys retain multiple pending singles`() {
        val fixture = Fixture(slop = 5f)
        fixture.activate(effect(PointerSource.MOUSE, collision = "Head"), IntOffset(0, 0), time = 0)
        fixture.activate(effect(PointerSource.MOUSE, collision = "Head"), IntOffset(100, 100), time = 100)
        fixture.activate(effect(PointerSource.MOUSE, collision = "Face"), IntOffset(0, 0), time = 150)
        fixture.scheduler.advanceTo(1_000)

        assertEquals(listOf("Head", "Head", "Face"), fixture.effects.map { it.collisionIdentifier })
    }

    @Test
    fun `triple click forms a double then a new pending single`() {
        val fixture = Fixture()
        repeat(3) { index -> fixture.activate(effect(PointerSource.MOUSE), IntOffset(0, 0), time = index * 100L) }

        assertEquals(listOf(PointerEventKind.DOUBLE_CLICK), fixture.effects.map { it.kind })
        fixture.scheduler.advanceTo(1_000)
        assertEquals(listOf(PointerEventKind.DOUBLE_CLICK, PointerEventKind.CLICK), fixture.effects.map { it.kind })
    }

    @Test
    fun `timeout cancel dispose and double races remain exact one event`() {
        val timeout = Fixture()
        timeout.activate(effect(PointerSource.MOUSE), IntOffset.Zero, time = 0)
        timeout.scheduler.advanceTo(500)
        timeout.activate(effect(PointerSource.MOUSE), IntOffset.Zero, time = 500)
        timeout.scheduler.advanceTo(1_000)
        assertEquals(2, timeout.effects.size)
        assertTrue(timeout.effects.all { it.kind == PointerEventKind.CLICK })

        val cancelled = Fixture()
        cancelled.activate(effect(PointerSource.PEN), IntOffset.Zero, time = 0)
        cancelled.sequencer.cancelAll()
        cancelled.scheduler.advanceTo(1_000)
        assertTrue(cancelled.effects.isEmpty())

        val racing = Fixture()
        racing.activate(effect(PointerSource.ERASER), IntOffset.Zero, time = 0)
        racing.activate(effect(PointerSource.ERASER), IntOffset.Zero, time = 499)
        racing.scheduler.advanceTo(1_000)
        assertEquals(listOf(PointerEventKind.DOUBLE_CLICK), racing.effects.map { it.kind })
    }

    @Test
    fun `geometry invalidation cancels pending while raster-stable token retains it`() {
        val fixture = Fixture()
        fixture.activate(effect(PointerSource.MOUSE), IntOffset.Zero, time = 0, geometry = "stable")
        fixture.sequencer.retainGeometry("stable")
        fixture.scheduler.advanceTo(499)
        assertTrue(fixture.effects.isEmpty())
        fixture.sequencer.retainGeometry("changed")
        fixture.scheduler.advanceTo(1_000)
        assertTrue(fixture.effects.isEmpty())
    }

    private class Fixture(
        val scheduler: FakeScheduler = FakeScheduler(),
        val effects: MutableList<SurfaceInteractionEffect> = mutableListOf(),
        val slop: Float = 8f,
        val doubleSupport: Support = Support.SUPPORTED,
    ) {
        val sequencer = PhysicalClickSequencer(scheduler, effects::add)

        fun activate(
            effect: SurfaceInteractionEffect,
            point: IntOffset,
            time: Long,
            geometry: Any = "geometry",
        ) = sequencer.activate(
            effect = effect,
            stagePoint = point,
            eventTimeMillis = time,
            doubleClickTimeoutMillis = 500,
            doubleClickSlopPx = slop,
            geometryToken = geometry,
            doubleClickSupport = doubleSupport,
        )
    }

    private class FakeScheduler : ClickDeadlineScheduler {
        private data class Scheduled(val deadline: Long, val order: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val queue = PriorityQueue(compareBy<Scheduled> { it.deadline }.thenBy { it.order })
        private var now = 0L
        private var order = 0L

        override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle {
            val scheduled = Scheduled(now + delayMillis, order++, action)
            queue += scheduled
            return CancellationHandle { scheduled.cancelled = true }
        }

        fun advanceTo(target: Long) {
            while (queue.peek()?.deadline?.let { it <= target } == true) {
                val next = queue.remove()
                now = next.deadline
                if (!next.cancelled) next.action()
            }
            now = target
        }
    }

    private fun effect(
        source: PointerSource,
        button: Int = 0,
        speaker: SurfaceSpeaker = SurfaceSpeaker.SAKURA,
        collision: String? = null,
    ) = SurfaceInteractionEffect(
        kind = PointerEventKind.CLICK,
        speaker = speaker,
        intrinsic = IntOffset(2, 3),
        button = button,
        source = source,
        collisionIdentifier = collision,
        diagnosticCollisionId = collision?.hashCode(),
    )
}
