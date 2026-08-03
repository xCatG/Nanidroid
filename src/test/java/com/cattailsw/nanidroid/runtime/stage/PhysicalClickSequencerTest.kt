package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.geometry.Offset
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
            fixture.activate(effect(source, collision = "c$index"), Offset(index * 20f, 0f), time = 0)
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
        fixture.activate(effect(PointerSource.MOUSE), Offset(20f, 20f), time = 0)
        val reservation = requireNotNull(
            fixture.sequencer.reserveSecond(
                effect(PointerSource.MOUSE),
                Offset(23f, 24f),
                eventTimeMillis = 200,
                doubleClickSlopPx = fixture.slop,
                geometryToken = "geometry",
            ),
        )
        fixture.sequencer.completeSecond(
            reservation,
            effect(PointerSource.MOUSE).copy(intrinsic = IntOffset(9, 10)),
            geometryToken = "geometry",
            doubleClickSupport = fixture.doubleSupport,
        )
        fixture.scheduler.advanceTo(1_000)

        assertEquals(listOf(PointerEventKind.DOUBLE_CLICK), fixture.effects.map { it.kind })
        assertEquals(IntOffset(9, 10), fixture.effects.single().intrinsic)
    }

    @Test
    fun `second down inside window reserves pair even when release is after deadline`() {
        val fixture = Fixture()
        fixture.activate(effect(PointerSource.MOUSE), Offset.Zero, time = 0)
        fixture.scheduler.advanceTo(499)
        val reservation = requireNotNull(
            fixture.sequencer.reserveSecond(
                effect(PointerSource.MOUSE),
                Offset.Zero,
                eventTimeMillis = 499,
                doubleClickSlopPx = fixture.slop,
                geometryToken = "geometry",
            ),
        )
        fixture.scheduler.advanceTo(700)
        assertTrue(fixture.effects.isEmpty())
        fixture.sequencer.completeSecond(
            reservation,
            effect(PointerSource.MOUSE),
            geometryToken = "geometry",
            doubleClickSupport = fixture.doubleSupport,
        )

        assertEquals(listOf(PointerEventKind.DOUBLE_CLICK), fixture.effects.map { it.kind })
    }

    @Test
    fun `cancelled second gesture restores the first pending single at its original deadline`() {
        val fixture = Fixture()
        fixture.click(effect(PointerSource.MOUSE), Offset.Zero, time = 0)
        fixture.scheduler.advanceTo(100)
        val reservation = requireNotNull(
            fixture.sequencer.reserveSecond(
                effect(PointerSource.MOUSE),
                Offset.Zero,
                eventTimeMillis = 100,
                doubleClickSlopPx = fixture.slop,
                geometryToken = "geometry",
            ),
        )
        fixture.scheduler.advanceTo(150)
        fixture.sequencer.cancelSecond(reservation, restorePending = true)
        fixture.scheduler.advanceTo(499)
        assertTrue(fixture.effects.isEmpty())
        fixture.scheduler.advanceTo(500)

        assertEquals(listOf(PointerEventKind.CLICK), fixture.effects.map { it.kind })
    }

    @Test
    fun `late cancellation dispatches the restored single at its already elapsed deadline`() {
        val fixture = Fixture()
        fixture.activate(effect(PointerSource.MOUSE), Offset.Zero, time = 0)
        val reservation = requireNotNull(
            fixture.sequencer.reserveSecond(
                effect(PointerSource.MOUSE),
                Offset.Zero,
                eventTimeMillis = 100,
                doubleClickSlopPx = fixture.slop,
                geometryToken = "geometry",
            ),
        )
        fixture.scheduler.advanceTo(700)

        fixture.sequencer.cancelSecond(reservation, restorePending = true)

        assertEquals(listOf(PointerEventKind.CLICK), fixture.effects.map { it.kind })
    }

    @Test
    fun `late initial activation dispatches at its already elapsed event deadline`() {
        val fixture = Fixture()
        fixture.scheduler.advanceTo(700)

        fixture.activate(effect(PointerSource.MOUSE), Offset.Zero, time = 100)

        assertEquals(listOf(PointerEventKind.CLICK), fixture.effects.map { it.kind })
    }

    @Test
    fun `deadline revalidates live geometry before dispatch`() {
        val fixture = Fixture()
        var liveGeometry = "stable"
        fixture.sequencer.activate(
            effect = effect(PointerSource.MOUSE),
            stagePoint = Offset.Zero,
            eventTimeMillis = 0,
            doubleClickTimeoutMillis = 500,
            doubleClickSlopPx = fixture.slop,
            geometryToken = "stable",
            liveGeometryToken = { liveGeometry },
        )
        liveGeometry = "changed"
        fixture.scheduler.advanceTo(500)

        assertTrue(fixture.effects.isEmpty())
    }

    @Test
    fun `explicitly unsupported double suppresses the matched pair entirely`() {
        val fixture = Fixture(doubleSupport = Support.UNSUPPORTED)
        fixture.click(effect(PointerSource.MOUSE), Offset(20f, 20f), time = 0)
        fixture.click(effect(PointerSource.MOUSE), Offset(21f, 21f), time = 100)
        fixture.scheduler.advanceTo(1_000)

        assertTrue(fixture.effects.isEmpty())
    }

    @Test
    fun `cross source button speaker and case-preserved collision remain independent`() {
        val fixture = Fixture()
        fixture.click(effect(PointerSource.MOUSE, collision = "Head"), Offset.Zero, time = 0)
        fixture.click(effect(PointerSource.PEN, collision = "Head"), Offset.Zero, time = 10)
        fixture.click(effect(PointerSource.MOUSE, button = 1, collision = "Head"), Offset.Zero, time = 20)
        fixture.click(effect(PointerSource.MOUSE, speaker = SurfaceSpeaker.KERO, collision = "Head"), Offset.Zero, time = 30)
        fixture.click(effect(PointerSource.MOUSE, collision = "head"), Offset.Zero, time = 40)
        fixture.scheduler.advanceTo(1_000)

        assertEquals(5, fixture.effects.size)
        assertTrue(fixture.effects.all { it.kind == PointerEventKind.CLICK })
    }

    @Test
    fun `distant same-key clicks and simultaneous keys retain multiple pending singles`() {
        val fixture = Fixture(slop = 5f)
        fixture.click(effect(PointerSource.MOUSE, collision = "Head"), Offset.Zero, time = 0)
        fixture.click(effect(PointerSource.MOUSE, collision = "Head"), Offset(100f, 100f), time = 100)
        fixture.click(effect(PointerSource.MOUSE, collision = "Face"), Offset.Zero, time = 150)
        fixture.scheduler.advanceTo(1_000)

        assertEquals(listOf("Head", "Head", "Face"), fixture.effects.map { it.collisionIdentifier })
    }

    @Test
    fun `triple click forms a double then a new pending single`() {
        val fixture = Fixture()
        repeat(3) { index -> fixture.click(effect(PointerSource.MOUSE), Offset.Zero, time = index * 100L) }

        assertEquals(listOf(PointerEventKind.DOUBLE_CLICK), fixture.effects.map { it.kind })
        fixture.scheduler.advanceTo(1_000)
        assertEquals(listOf(PointerEventKind.DOUBLE_CLICK, PointerEventKind.CLICK), fixture.effects.map { it.kind })
    }

    @Test
    fun `timeout cancel dispose and double races remain exact one event`() {
        val timeout = Fixture()
        timeout.click(effect(PointerSource.MOUSE), Offset.Zero, time = 0)
        timeout.scheduler.advanceTo(500)
        timeout.click(effect(PointerSource.MOUSE), Offset.Zero, time = 500)
        timeout.scheduler.advanceTo(1_000)
        assertEquals(2, timeout.effects.size)
        assertTrue(timeout.effects.all { it.kind == PointerEventKind.CLICK })

        val cancelled = Fixture()
        cancelled.activate(effect(PointerSource.PEN), Offset.Zero, time = 0)
        cancelled.sequencer.cancelAll()
        cancelled.scheduler.advanceTo(1_000)
        assertTrue(cancelled.effects.isEmpty())

        val racing = Fixture()
        racing.click(effect(PointerSource.ERASER), Offset.Zero, time = 0)
        racing.click(effect(PointerSource.ERASER), Offset.Zero, time = 499)
        racing.scheduler.advanceTo(1_000)
        assertEquals(listOf(PointerEventKind.DOUBLE_CLICK), racing.effects.map { it.kind })
    }

    @Test
    fun `geometry invalidation cancels pending while raster-stable token retains it`() {
        val fixture = Fixture()
        fixture.activate(effect(PointerSource.MOUSE), Offset.Zero, time = 0, geometry = "stable")
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
        val sequencer = PhysicalClickSequencer(scheduler, scheduler::nowMillis, effects::add)

        fun activate(
            effect: SurfaceInteractionEffect,
            point: Offset,
            time: Long,
            geometry: Any = "geometry",
        ) = sequencer.activate(
            effect = effect,
            stagePoint = point,
            eventTimeMillis = time,
            doubleClickTimeoutMillis = 500,
            doubleClickSlopPx = slop,
            geometryToken = geometry,
        )

        fun click(
            effect: SurfaceInteractionEffect,
            point: Offset,
            time: Long,
            geometry: Any = "geometry",
        ) {
            scheduler.advanceTo(time)
            val reservation = sequencer.reserveSecond(
                effect = effect,
                stagePoint = point,
                eventTimeMillis = time,
                doubleClickSlopPx = slop,
                geometryToken = geometry,
            )
            if (reservation == null) {
                activate(effect, point, time, geometry)
            } else {
                sequencer.completeSecond(reservation, effect, geometry, doubleSupport)
            }
        }
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

        fun nowMillis(): Long = now

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
