package com.cattailsw.nanidroid.compose

import com.cattailsw.nanidroid.ShellSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceAnimationSchedulerTest {
    @Test
    fun `injected monotonic clock advances cyclic frames at legacy durations`() {
        val clock = FakeClock()
        val scheduler = SurfaceAnimationScheduler(
            plan = plan(animation("script", frames = listOf(reset(37), reset(83)))),
            clock = clock,
            entropy = FixedEntropy(),
        )

        assertEquals(listOf(frame("script", 0, 37)), scheduler.presentationUpdated(false, "script", talkingAnimationEnabled = false))
        clock.nowMillis = 36
        assertTrue(scheduler.tick().isEmpty())
        clock.nowMillis = 37
        assertEquals(listOf(frame("script", 1, 83)), scheduler.tick())
        clock.nowMillis = 120
        assertEquals(listOf(frame("script", 0, 37)), scheduler.tick())
    }

    @Test
    fun `presentation replaces one shot when distinct ids render equal reset frames`() {
        val scheduler = SurfaceAnimationScheduler(
            plan = plan(animation("7"), animation("8")),
            clock = FakeClock(),
            entropy = FixedEntropy(),
        )

        assertEquals(
            listOf(frame("7", 0, 1)),
            scheduler.presentationUpdated(false, "7", talkingAnimationEnabled = false),
        )
        assertEquals(
            listOf(frame("8", 0, 1)),
            scheduler.presentationUpdated(false, "8", talkingAnimationEnabled = false),
        )
    }

    @Test
    fun `per second rolls preserve rarely then sometimes probability bands without catchup`() {
        val clock = FakeClock()
        val entropy = FixedEntropy(0.24, 0.25, 0.50)
        val scheduler = SurfaceAnimationScheduler(
            plan = plan(
                animation("rare", ShellSurface.A_TYPE_RARELY),
                animation("sometimes", ShellSurface.A_TYPE_SOMETIMES),
            ),
            clock = clock,
            entropy = entropy,
        )

        assertEquals(listOf(frame("rare", 0, 1)), scheduler.tick())
        clock.nowMillis = 999
        assertTrue(scheduler.tick().isEmpty())
        clock.nowMillis = 1_000
        assertEquals(listOf(frame("sometimes", 0, 1)), scheduler.tick())
        clock.nowMillis = 2_000
        assertTrue(scheduler.tick().isEmpty())
        clock.nowMillis = 10_000
        assertEquals(listOf(frame("rare", 0, 1)), scheduler.tick()) // one chance, not eight catch-up chances
    }

    @Test
    fun `frame-only ticks do not consume the first deferred periodic roll`() {
        val clock = FakeClock()
        val scheduler = SurfaceAnimationScheduler(
            plan = plan(animation("rare", ShellSurface.A_TYPE_RARELY)),
            clock = clock,
            entropy = FixedEntropy(0.0),
        )

        // The Compose host advances frames immediately but deliberately waits
        // one full second before allowing rarely/sometimes selection.
        assertTrue(scheduler.tick(allowPeriodicSelection = false).isEmpty())
        clock.nowMillis = 1_000
        assertEquals(listOf(frame("rare", 0, 1)), scheduler.tick(allowPeriodicSelection = true))
    }

    @Test
    fun `talking follows the runtime gate and explicit script animation wins that update`() {
        val clock = FakeClock()
        val scheduler = SurfaceAnimationScheduler(
            plan = plan(
                animation("talk", ShellSurface.A_TYPE_TALK),
                animation("script"),
            ),
            clock = clock,
            entropy = FixedEntropy(),
        )
        assertEquals(
            listOf(frame("script", 0, 1)),
            scheduler.presentationUpdated(true, "script", talkingAnimationEnabled = true),
        )
        repeat(9) {
            assertTrue(scheduler.presentationUpdated(true, talkingAnimationEnabled = false).isEmpty())
        }
        assertEquals(
            listOf(frame("talk", 0, 1)),
            scheduler.presentationUpdated(true, talkingAnimationEnabled = true),
        )
    }

    @Test
    fun `one shared runtime talk gate keeps both speakers and a replacement surface aligned`() {
        val clock = FakeClock()
        val sakura = SurfaceAnimationScheduler(plan(animation("talk", ShellSurface.A_TYPE_TALK)), clock, FixedEntropy())
        val kero = SurfaceAnimationScheduler(plan(animation("talk", ShellSurface.A_TYPE_TALK)), clock, FixedEntropy())

        assertEquals(
            listOf(frame("talk", 0, 1)),
            sakura.presentationUpdated(true, talkingAnimationEnabled = true),
        )
        assertEquals(
            listOf(frame("talk", 0, 1)),
            kero.presentationUpdated(true, talkingAnimationEnabled = true),
        )

        val replacementSakura = SurfaceAnimationScheduler(plan(animation("talk", ShellSurface.A_TYPE_TALK)), clock, FixedEntropy())
        repeat(9) {
            assertTrue(replacementSakura.presentationUpdated(true, talkingAnimationEnabled = false).isEmpty())
            assertTrue(kero.presentationUpdated(true, talkingAnimationEnabled = false).isEmpty())
        }

        assertEquals(
            listOf(frame("talk", 0, 1)),
            replacementSakura.presentationUpdated(true, talkingAnimationEnabled = true),
        )
        assertEquals(
            listOf(frame("talk", 0, 1)),
            kero.presentationUpdated(true, talkingAnimationEnabled = true),
        )
    }

    @Test
    fun `failed presentation start keeps the frame that advanced in the same update`() {
        val clock = FakeClock()
        val scheduler = SurfaceAnimationScheduler(
            plan(animation("active", frames = listOf(reset(10), reset(20)))),
            clock,
            FixedEntropy(),
        )
        assertEquals(listOf(frame("active", 0, 10)), scheduler.presentationUpdated(false, "active", talkingAnimationEnabled = false))
        clock.nowMillis = 10
        assertEquals(
            listOf(frame("active", 1, 20)),
            scheduler.presentationUpdated(false, "missing", talkingAnimationEnabled = false),
        )
        assertTrue(scheduler.tick().isEmpty())
    }

    @Test
    fun `failed periodic start keeps the frame that advanced in the same tick`() {
        val clock = FakeClock()
        val scheduler = SurfaceAnimationScheduler(
            plan = plan(
                animation("active", frames = listOf(reset(10), reset(20))),
                animation("broken", ShellSurface.A_TYPE_RARELY, alternatives = listOf("missing")),
            ),
            clock,
            FixedEntropy(0.0, 0.0),
        )

        assertEquals(listOf(frame("active", 0, 10)), scheduler.presentationUpdated(false, "active", talkingAnimationEnabled = false))
        assertTrue(scheduler.tick().isEmpty())
        clock.nowMillis = 1_000
        assertEquals(listOf(frame("active", 1, 20)), scheduler.tick())
    }

    @Test
    fun `alternative animation selection is deterministic scheduler work rather than compositor work`() {
        val clock = FakeClock()
        val scheduler = SurfaceAnimationScheduler(
            plan = plan(
                animation("alternate", alternatives = listOf("first", "second")),
                animation("first"),
                animation("second"),
            ),
            clock = clock,
            entropy = FixedEntropy(0.9),
        )

        assertEquals(listOf(frame("second", 0, 1)), scheduler.presentationUpdated(false, "alternate", talkingAnimationEnabled = false))
    }

    @Test
    fun `repeating an alternate container restarts its resolved child without another roll`() {
        val clock = FakeClock()
        val entropy = CountingEntropy(0.9, 0.0)
        val scheduler = SurfaceAnimationScheduler(
            plan = plan(
                animation("alternate", alternatives = listOf("first", "second")),
                animation("first"),
                animation("second"),
            ),
            clock,
            entropy,
        )

        assertEquals(listOf(frame("second", 0, 1)), scheduler.presentationUpdated(false, "alternate", talkingAnimationEnabled = false))
        assertEquals(listOf(frame("second", 0, 1)), scheduler.presentationUpdated(false, "alternate", talkingAnimationEnabled = false))
        assertEquals(1, entropy.calls)
    }

    @Test
    fun `interval choice and every alternate hop consume independent entropy`() {
        val clock = FakeClock()
        val entropy = FixedEntropy(
            0.0, // per-second probability: rarely
            0.0, // choose the first of two rarely candidates
            0.9, // choose the second alternate target, independently
        )
        val scheduler = SurfaceAnimationScheduler(
            plan = plan(
                animation("container", ShellSurface.A_TYPE_RARELY, alternatives = listOf("first", "second")),
                animation("other", ShellSurface.A_TYPE_RARELY),
                animation("first"),
                animation("second"),
            ),
            clock = clock,
            entropy = entropy,
        )

        assertEquals(listOf(frame("second", 0, 1)), scheduler.tick())
    }

    @Test
    fun `nan probability is normalized before adapter consumes the candidate selection roll`() {
        val clock = FakeClock()
        val scheduler = SurfaceAnimationScheduler(
            plan = plan(
                animation("first", ShellSurface.A_TYPE_RARELY),
                animation("second", ShellSurface.A_TYPE_RARELY),
            ),
            clock = clock,
            entropy = FixedEntropy(Double.NaN, 0.9),
        )

        assertEquals(listOf(frame("second", 0, 1)), scheduler.tick())
    }

    @Test
    fun `reducer stays pure when time and entropy are supplied in its event`() {
        val renderPlan = plan(animation("talk", ShellSurface.A_TYPE_TALK))
        val event = SurfaceAnimationScheduleEvent.PresentationUpdated(
            nowMillis = 41,
            hasVisibleSpeech = true,
            oneShotAnimationId = null,
            talkingAnimationEnabled = true,
            selectionRolls = emptyList(),
        )

        val first = SurfaceAnimationScheduleReducer.reduce(renderPlan, SurfaceAnimationScheduleState.Idle, event)
        val second = SurfaceAnimationScheduleReducer.reduce(renderPlan, SurfaceAnimationScheduleState.Idle, event)

        assertEquals(first, second)
        assertEquals(listOf(frame("talk", 0, 1)), first.effects)
    }

    private fun plan(vararg animations: SurfaceRenderAnimation) = SurfaceRenderPlan(
        surfaceId = 7,
        width = 10,
        height = 10,
        base = SurfaceRenderBase.Missing,
        animations = animations.toList(),
    )

    private fun animation(
        id: String,
        interval: Int = ShellSurface.A_TYPE_NEVER,
        frames: List<SurfaceRenderFrame> = listOf(reset(1)),
        alternatives: List<String> = emptyList(),
    ) = SurfaceRenderAnimation(id, interval, false, frames, alternatives)

    private fun reset(durationMillis: Int) = SurfaceRenderFrame.Reset(durationMillis)

    private fun frame(id: String, index: Int, durationMillis: Int) =
        SurfaceAnimationScheduleEffect.Frame(id, index, reset(durationMillis))

    private class FakeClock(var nowMillis: Long = 0) : SurfaceRenderClock {
        override fun nowMillis(): Long = nowMillis
    }

    private class FixedEntropy(vararg values: Double) : SurfaceRenderEntropy {
        private val values = values.toList()
        private var index = 0
        override fun nextUnitDouble(): Double = values.getOrElse(index++) { 0.0 }
    }

    private class CountingEntropy(vararg values: Double) : SurfaceRenderEntropy {
        private val values = values.toList()
        var calls = 0
            private set
        override fun nextUnitDouble(): Double = values.getOrElse(calls++) { 0.0 }
    }
}
