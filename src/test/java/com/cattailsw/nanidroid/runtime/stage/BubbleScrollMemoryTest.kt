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

    @Test
    fun saveValuesContainAtMostTheCurrentTalkForEachPhysicalSpeaker() {
        val memory = BubbleScrollMemory()
        memory.update(BubbleScrollKey(SurfaceSpeaker.SAKURA, 1L), 18, BubbleScrollOrigin.MANUAL)
        memory.update(BubbleScrollKey(SurfaceSpeaker.SAKURA, 2L), 24, BubbleScrollOrigin.MANUAL)
        memory.update(BubbleScrollKey(SurfaceSpeaker.KERO, 3L), 9, BubbleScrollOrigin.PROGRAMMATIC)

        val saved = memory.saveValues()
        val restored = BubbleScrollMemory.restoreValues(saved)

        assertEquals(BubbleScrollSnapshot(24, true), restored.snapshot(BubbleScrollKey(SurfaceSpeaker.SAKURA, 2L)))
        assertEquals(BubbleScrollSnapshot(9, false), restored.snapshot(BubbleScrollKey(SurfaceSpeaker.KERO, 3L)))
        assertTrue(saved.size <= 8)
        assertTrue(1L !in saved)
    }

    @Test
    fun malformedOrOutOfRangeSavedValuesAreIgnoredWithoutGrowingState() {
        val restored = BubbleScrollMemory.restoreValues(
            listOf(
                "SAKURA", 4L, -1, true,
                "KERO", 8L, 9, true,
                "NOT_A_SPEAKER", 8L, 9, true,
                "SAKURA", 6L, Int.MAX_VALUE, true,
                "trailing",
            ),
        )

        assertEquals(BubbleScrollSnapshot.FollowNewest, restored.snapshot(BubbleScrollKey(SurfaceSpeaker.SAKURA, 4L)))
        assertEquals(BubbleScrollSnapshot(9, true), restored.snapshot(BubbleScrollKey(SurfaceSpeaker.KERO, 8L)))
        assertTrue(restored.saveValues().size <= 8)
    }

    @Test
    fun staleOldTalkUpdateCannotReplaceTheCurrentTalk() {
        val memory = BubbleScrollMemory()
        val old = BubbleScrollKey(SurfaceSpeaker.SAKURA, 10L)
        val current = BubbleScrollKey(SurfaceSpeaker.SAKURA, 11L)
        memory.update(old, 7, BubbleScrollOrigin.MANUAL)
        memory.update(current, 12, BubbleScrollOrigin.MANUAL)

        memory.update(old, 99, BubbleScrollOrigin.MANUAL)

        assertEquals(BubbleScrollSnapshot(12, true), memory.snapshot(current))
    }

    @Test
    fun restoredScrollSurvivesBlankHostBindingAndAnotherSaveBeforeStableGhostReturns() {
        val original = GhostBubbleScrollMemory.forContext("session-a", "ghost-a")
        val sakura = BubbleScrollKey(SurfaceSpeaker.SAKURA, 41L)
        val kero = BubbleScrollKey(SurfaceSpeaker.KERO, 41L)
        original.memoryFor("session-a", "ghost-a").update(sakura, 19, BubbleScrollOrigin.MANUAL)
        original.memoryFor("session-a", "ghost-a").update(kero, 27, BubbleScrollOrigin.MANUAL)

        val restoredWhileUnbound = GhostBubbleScrollMemory.restoreValues(original.saveValues())
        restoredWhileUnbound.memoryFor("session-a", "").snapshot(BubbleScrollKey(SurfaceSpeaker.SAKURA, 0L))
        val restoredAgain = GhostBubbleScrollMemory.restoreValues(restoredWhileUnbound.saveValues())

        assertEquals(
            BubbleScrollSnapshot(19, true),
            restoredAgain.memoryFor("session-a", "ghost-a").snapshot(sakura),
        )
        assertEquals(
            BubbleScrollSnapshot(27, true),
            restoredAgain.memoryFor("session-a", "ghost-a").snapshot(kero),
        )
    }

    @Test
    fun changingBetweenStableGhostsResetsBubbleScroll() {
        val retained = GhostBubbleScrollMemory.forContext("session-a", "ghost-a")
        val talk = BubbleScrollKey(SurfaceSpeaker.SAKURA, 41L)
        retained.memoryFor("session-a", "ghost-a").update(talk, 19, BubbleScrollOrigin.MANUAL)

        val replacement = retained.memoryFor("session-a", "ghost-b")

        assertEquals(BubbleScrollSnapshot.FollowNewest, replacement.snapshot(talk))
    }

    @Test
    fun oversizedGhostIdentityIsNotWrittenToTheSavePayload() {
        val retained = GhostBubbleScrollMemory.forContext("session-a", "g".repeat(513))
        retained.memoryFor("session-a", "g".repeat(513)).update(
            BubbleScrollKey(SurfaceSpeaker.SAKURA, 41L),
            19,
            BubbleScrollOrigin.MANUAL,
        )

        assertTrue(retained.saveValues().isEmpty())
    }

    @Test
    fun newProcessSessionDoesNotReuseRestoredScrollForTheSameGhostAndTalkId() {
        val talk = BubbleScrollKey(SurfaceSpeaker.SAKURA, 1L)
        val original = GhostBubbleScrollMemory.forContext("session-a", "ghost-a")
        original.memoryFor("session-a", "ghost-a").update(talk, 19, BubbleScrollOrigin.MANUAL)
        val restored = GhostBubbleScrollMemory.restoreValues(original.saveValues())

        restored.memoryFor("session-b", "")
        val firstNewProcessTalk = restored.memoryFor("session-b", "ghost-a").snapshot(talk)

        assertEquals(BubbleScrollSnapshot.FollowNewest, firstNewProcessTalk)
    }
}
