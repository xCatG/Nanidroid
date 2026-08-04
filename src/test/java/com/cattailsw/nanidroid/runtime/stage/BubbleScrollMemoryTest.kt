package com.cattailsw.nanidroid.runtime.stage

import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleScrollMemoryTest {
    @Test
    fun stateIsKeyedBySpeakerAndTalkAndOnlyManualUpdatesSuspendAutoFollow() {
        val memory = BubbleScrollMemory()

        val sakuraTalkOne = BubbleScrollKey(SurfaceSpeaker.SAKURA, talkId = 1L)
        val keroTalkOne = BubbleScrollKey(SurfaceSpeaker.KERO, talkId = 1L)
        val sakuraTalkTwo = BubbleScrollKey(SurfaceSpeaker.SAKURA, talkId = 2L)

        assertEquals(BubbleScrollSnapshot.FollowNewest, memory.snapshot(sakuraTalkOne))
        memory.update(sakuraTalkOne, position = 80, origin = BubbleScrollOrigin.PROGRAMMATIC)
        assertEquals(80, memory.snapshot(sakuraTalkOne).position)
        assertFalse(memory.snapshot(sakuraTalkOne).userScrolled)

        memory.update(sakuraTalkOne, position = 24, origin = BubbleScrollOrigin.MANUAL)
        assertEquals(24, memory.snapshot(sakuraTalkOne).position)
        assertTrue(memory.snapshot(sakuraTalkOne).userScrolled)

        assertEquals(BubbleScrollSnapshot.FollowNewest, memory.snapshot(keroTalkOne))
        memory.update(keroTalkOne, position = 12, origin = BubbleScrollOrigin.MANUAL)
        assertEquals(12, memory.snapshot(keroTalkOne).position)
        assertEquals(24, memory.snapshot(sakuraTalkOne).position)

        assertEquals(BubbleScrollSnapshot.FollowNewest, memory.snapshot(sakuraTalkTwo))
        assertFalse(memory.snapshot(sakuraTalkTwo).userScrolled)
    }

    @Test
    fun startingANewerTalkEvictsThePreviousTalkForOnlyThatSpeaker() {
        val memory = BubbleScrollMemory()
        val sakuraTalkOne = BubbleScrollKey(SurfaceSpeaker.SAKURA, talkId = 1L)
        val sakuraTalkTwo = BubbleScrollKey(SurfaceSpeaker.SAKURA, talkId = 2L)
        val keroTalkOne = BubbleScrollKey(SurfaceSpeaker.KERO, talkId = 1L)
        memory.update(sakuraTalkOne, 18, BubbleScrollOrigin.MANUAL)
        memory.update(keroTalkOne, 9, BubbleScrollOrigin.MANUAL)

        assertEquals(BubbleScrollSnapshot.FollowNewest, memory.snapshot(sakuraTalkTwo))

        assertEquals(BubbleScrollSnapshot.FollowNewest, memory.snapshot(sakuraTalkOne))
        assertEquals(9, memory.snapshot(keroTalkOne).position)
    }
}
