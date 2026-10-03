package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.ghost.SurfaceDefinitions
import org.junit.Assert.*
import org.junit.Test

class SurfaceAnimatorTest {
    private fun animator(body: String, random: (Long) -> Long = { 0L }) =
        SurfaceAnimator(SurfaceDefinitions.parse("surface0\n{\n$body\n}"), random)

    @Test fun waitPrecedesFirstFrameAndTimingAloneDoesNotPublish() {
        val animator = animator("animation0.interval,always\nanimation0.pattern0,overlay,1001,100,0,0")
        animator.setSurface(0, 0)
        assertEquals(0, animator.advanceBy(0, emptySet())?.get(0)?.baseSurfaceId)
        assertNull(animator.advanceBy(99, emptySet()))
        val first = animator.advanceBy(1, emptySet())
        assertEquals(1001, first?.get(0)?.layers?.single()?.surfaceId)
        assertNull(animator.advanceBy(500, emptySet()))
    }

    @Test fun runonceRetainsFinalFrameUntilDifferentLogicalSurface() {
        val animator = animator("animation0.interval,runonce\nanimation0.pattern0,overlay,1001,0,0,0")
        animator.setSurface(0, 0)
        assertEquals(1001, animator.advanceBy(50, emptySet())?.get(0)?.layers?.single()?.surfaceId)
        assertNull(animator.advanceBy(1000, emptySet()))
        animator.setSurface(0, 0)
        assertNull(animator.advanceBy(50, emptySet()))
        animator.setSurface(0, 1)
        assertTrue(animator.advanceBy(0, emptySet())?.get(0)?.layers?.isEmpty() == true)
    }

    @Test fun simultaneousTracksReplaceOnlyTheirOwnFramesAndMoveIsAbsolute() {
        val animator = animator("""
            animation0.interval,always
            animation0.pattern0,overlay,1001,0,0,0
            animation1.interval,runonce
            animation1.pattern0,move,0,0,4,-3
        """.trimIndent())
        animator.setSurface(0, 0)
        val visual = animator.advanceBy(50, emptySet())!![0]!!
        assertEquals(1, visual.layers.size)
        assertEquals(4, visual.offsetX)
        assertEquals(-3, visual.offsetY)
    }

    @Test fun negativeOneClearsTrackWithoutChangingLogicalBase() {
        val animator = animator("""
            animation0.interval,runonce
            animation0.pattern0,overlay,1001,0,0,0
            animation0.pattern1,overlay,-1,0,0,0
        """.trimIndent())
        animator.setSurface(0, 0)
        assertEquals(1001, animator.advanceBy(50, emptySet())!![0]!!.layers.single().surfaceId)
        val cleared = animator.advanceBy(50, emptySet())!![0]!!
        assertEquals(0, cleared.baseSurfaceId)
        assertTrue(cleared.layers.isEmpty())
    }

    @Test fun inclusiveWaitRangeUsesInjectedRandomAndExactBoundary() {
        val animator = animator("animation0.interval,runonce\nanimation0.pattern0,overlay,1001,70-72,0,0") { it - 1 }
        animator.setSurface(0, 0)
        animator.advanceBy(0, emptySet())
        assertNull(animator.advanceBy(71, emptySet()))
        assertEquals(1001, animator.advanceBy(1, emptySet())?.get(0)?.layers?.single()?.surfaceId)
    }

    @Test fun automaticRollsAreIndependentAndUnsupportedIntervalsStayIdle() {
        val animator = animator("""
            animation0.interval,sometimes
            animation0.pattern0,overlay,1001,0,0,0
            animation1.interval,rarely
            animation1.pattern0,overlay,1002,0,0,0
            animation2.interval,bind
            animation2.pattern0,overlay,1003,0,0,0
        """.trimIndent()) { 0 }
        animator.setSurface(0, 0)
        animator.advanceBy(0, emptySet())
        assertNull(animator.advanceBy(1_000, emptySet()))
        val visual = animator.advanceBy(50, emptySet())!![0]!!
        assertEquals(listOf(1001, 1002), visual.layers.map { it.surfaceId })
    }

    @Test fun sometimesUsesHalfAndRarelyUsesQuarterPerEligibleSecond() {
        val bounds = mutableListOf<Long>()
        val draws = ArrayDeque(listOf(1L, 1L, 0L, 0L))
        val animator = animator("""
            animation0.interval,sometimes
            animation0.pattern0,overlay,1001,0,0,0
            animation1.interval,rarely
            animation1.pattern0,overlay,1002,0,0,0
        """.trimIndent()) { bound -> bounds += bound; draws.removeFirst() }
        animator.setSurface(0, 0)
        animator.advanceBy(0, emptySet())
        assertNull(animator.advanceBy(1_000, emptySet()))
        assertEquals(listOf(2L, 4L), bounds)
        assertNull(animator.advanceBy(1_000, emptySet()))
        assertEquals(listOf(1001, 1002), animator.advanceBy(50, emptySet())!![0]!!.layers.map { it.surfaceId })
    }

    @Test fun talkRequiresAdvancingTextAndExplicitStartSuppressesThatAdvance() {
        val animator = animator("""
            animation0.interval,talk
            animation0.pattern0,overlay,1001,0,0,0
            animation1.interval,never
            animation1.pattern0,overlay,1002,0,0,0
        """.trimIndent())
        animator.setSurface(0, 0)
        animator.advanceBy(0, emptySet())
        assertNull(animator.advanceBy(500, emptySet()))
        animator.startExplicit(0, 1)
        val explicit = animator.advanceBy(500, setOf(0))!![0]!!
        assertEquals(listOf(1002), explicit.layers.map { it.surfaceId })
        assertNull(animator.advanceBy(500, setOf(0)))
        assertEquals(1001, animator.advanceBy(50, emptySet())!![0]!!.layers.first().surfaceId)
    }

    @Test fun missingExplicitIdDoesNothingAndAlternativeSelectsTarget() {
        val animator = animator("""
            animation0.interval,never
            animation0.pattern0,alternativestart,(1,2)
            animation1.interval,never
            animation1.pattern0,overlay,1001,0,0,0
            animation2.interval,never
            animation2.pattern0,overlay,1002,0,0,0
        """.trimIndent()) { it - 1 }
        animator.setSurface(0, 0)
        animator.advanceBy(0, emptySet())
        animator.startExplicit(0, 9)
        assertNull(animator.advanceBy(50, emptySet()))
        animator.startExplicit(0, 0)
        assertEquals(1002, animator.advanceBy(50, emptySet())?.get(0)?.layers?.single()?.surfaceId)
    }

    @Test fun baseFrameReplacesCollisionSource() {
        val animator = animator("animation0.interval,runonce\nanimation0.pattern0,base,1001,0,0,0")
        animator.setSurface(0, 0)
        val visual = animator.advanceBy(50, emptySet())!![0]!!
        assertEquals(1001, visual.baseSurfaceId)
        assertTrue(visual.layers.isEmpty())
    }

    @Test fun failedSometimesRollsDoNotStartUntilSuccessAndRunningTrackIsNotRestarted() {
        val rolls = ArrayDeque(listOf(1L, 1L, 1L, 0L, 0L))
        val animator = animator("""
            animation0.interval,sometimes
            animation0.pattern0,overlay,1001,5000,0,0
        """.trimIndent()) { rolls.removeFirst() }
        animator.setSurface(0, 0)
        animator.advanceBy(0, emptySet())
        repeat(3) { assertNull(animator.advanceBy(1_000, emptySet())) }
        assertNull(animator.advanceBy(1_000, emptySet()))
        assertNull(animator.advanceBy(1_000, emptySet()))
        assertNull(animator.advanceBy(3_000, emptySet()))
        assertEquals(1001, animator.advanceBy(1_000, emptySet())?.get(0)?.layers?.single()?.surfaceId)
    }

    @Test fun zeroWaitRequiresOneTickAndAlternativeCycleStaysBounded() {
        var draws = 0
        val animator = animator("""
            animation0.interval,never
            animation0.pattern0,alternativestart,(1)
            animation1.interval,never
            animation1.pattern0,alternativestart,(0)
            animation2.interval,never
            animation2.pattern0,overlay,1002,0,0,0
        """.trimIndent()) { draws++; 0 }
        animator.setSurface(0, 0)
        animator.advanceBy(0, emptySet())
        animator.startExplicit(0, 0)
        assertNull(animator.advanceBy(0, emptySet()))
        assertNull(animator.advanceBy(50, emptySet()))
        assertTrue(draws < 64)
        animator.startExplicit(0, 2)
        assertNull(animator.advanceBy(49, emptySet()))
        assertEquals(1002, animator.advanceBy(1, emptySet())?.get(0)?.layers?.single()?.surfaceId)
    }

    @Test fun automaticStartsWaitUntilTickAfterRollOrTalkThreshold() {
        val animator = animator("""
            animation0.interval,sometimes
            animation0.pattern0,overlay,1001,0,0,0
            animation1.interval,talk
            animation1.pattern0,overlay,1002,0,0,0
        """.trimIndent()) { 0 }
        animator.setSurface(0, 0)
        animator.advanceBy(0, emptySet())
        repeat(19) { assertNull(animator.advanceBy(50, emptySet())) }
        assertNull(animator.advanceBy(50, setOf(0)))
        assertEquals(listOf(1001, 1002),
            animator.advanceBy(50, emptySet())?.get(0)?.layers?.map { it.surfaceId })
    }

    @Test fun shortTalkBurstsAfterIdleUseFiveHundredMillisecondThrottle() {
        val animator = animator("""
            animation0.interval,talk
            animation0.pattern0,overlay,1001,0,0,0
            animation0.pattern1,overlay,-1,0,0,0
        """.trimIndent())
        animator.setSurface(0, 0)
        animator.advanceBy(0, emptySet())
        repeat(10) { assertNull(animator.advanceBy(50, emptySet())) }
        assertNull(animator.advanceBy(50, setOf(0)))
        assertEquals(1001, animator.advanceBy(50, emptySet())?.get(0)?.layers?.single()?.surfaceId)
        assertTrue(animator.advanceBy(50, emptySet())?.get(0)?.layers?.isEmpty() == true)
        repeat(7) { assertNull(animator.advanceBy(50, emptySet())) }
        assertNull(animator.advanceBy(50, setOf(0)))
        assertEquals(1001, animator.advanceBy(50, emptySet())?.get(0)?.layers?.single()?.surfaceId)
    }

    @Test fun alternativeCycleCannotRestartSourceBeforeItsNextImage() {
        val animator = animator("""
            animation0.interval,never
            animation0.pattern0,alternativestart,(1)
            animation0.pattern1,overlay,1001,0,0,0
            animation1.interval,never
            animation1.pattern0,alternativestart,(0)
            animation2.interval,never
            animation2.pattern0,alternativestart,(1)
        """.trimIndent())
        animator.setSurface(0, 0)
        animator.advanceBy(0, emptySet())
        animator.startExplicit(0, 0)
        assertEquals(1001, animator.advanceBy(50, emptySet())?.get(0)?.layers?.single()?.surfaceId)
        animator.startExplicit(0, 2)
        assertNull(animator.advanceBy(50, emptySet()))
    }

    @Test fun delayedAlternativeCanRestartFormerAncestorOnLaterTick() {
        for (explicit in listOf(false, true)) {
            val animator = animator("""
                animation0.interval,runonce
                animation0.pattern0,alternativestart,(1)
                animation0.pattern1,overlay,1001,0,0,0
                animation1.interval,never
                animation1.pattern0,overlay,-1,500,0,0
                animation1.pattern1,alternativestart,(0)
            """.trimIndent())
            animator.setSurface(0, 0)
            animator.advanceBy(0, emptySet())
            if (explicit) animator.startExplicit(0, 0)
            assertEquals(1001, animator.advanceBy(50, emptySet())?.get(0)?.layers?.single()?.surfaceId)
            repeat(9) { assertNull(animator.advanceBy(50, emptySet())) }
            val restarted = animator.advanceBy(50, emptySet())
            assertTrue(restarted?.get(0)?.layers?.isEmpty() == true)
            assertEquals(1001, animator.advanceBy(50, emptySet())?.get(0)?.layers?.single()?.surfaceId)
        }
    }
}
