package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.dialogue.GhostRuntimeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveIntentStateTest {
    @Test
    fun unresolvedRunnerCharacteristicallyAdmitsArchiveIngress() {
        val reception = receiveArchiveAtIngress(runtimeMode = null)

        assertTrue(reception is ArchiveIntentState.Reception.Pending)
    }

    @Test
    fun retainedPassiveRunnerRejectsArchiveIngress() {
        val reception = receiveArchiveAtIngress(
            GhostRuntimeMode(
                playingTalk = false,
                pendingUserAction = false,
                passive = true,
            ),
        )

        assertFalse(reception is ArchiveIntentState.Reception.Pending)
    }

    @Test
    fun recreatedIdleRunnerAcceptsArchiveIngress() {
        val reception = receiveArchiveAtIngress(
            GhostRuntimeMode(
                playingTalk = false,
                pendingUserAction = false,
                passive = false,
            ),
        )

        assertTrue(reception is ArchiveIntentState.Reception.Pending)
    }

    @Test
    fun receivingSecondArchiveWhileFirstIsPending_marksSecondAsConsumed() {
        val pendingFirst = ArchiveIntentState().receive("content://archives/first", 1)
            as ArchiveIntentState.Reception.Pending

        val second = pendingFirst.state.receive("content://archives/second", 2)
            as ArchiveIntentState.Reception.Dispatch

        assertEquals("content://archives/second", second.state.consumedUri)
        assertEquals("content://archives/first", second.state.pendingUri)
        assertEquals("content://archives/second", second.uri)
        assertEquals(2, second.flags)
    }

    @Test
    fun receivingFreshIntentForPreviouslyConsumedArchive_queuesItAgain() {
        val initial = ArchiveIntentState().receive("content://archives/reinstall", 1)
            as ArchiveIntentState.Reception.Pending
        val afterCompletion = initial.state.takePending()!!.state

        val repeated = afterCompletion.receiveNewIntent("content://archives/reinstall", 2)

        assertEquals(
            ArchiveIntentState.Reception.Pending(
                ArchiveIntentState(
                    consumedUri = "content://archives/reinstall",
                    pendingUri = "content://archives/reinstall",
                    pendingFlags = 2,
                ),
            ),
            repeated,
        )
    }

    private fun receiveArchiveAtIngress(
        runtimeMode: GhostRuntimeMode?,
    ): ArchiveIntentState.Reception {
        val state = ArchiveIntentState()
        return if (allowsArchiveIngress(runtimeMode)) {
            state.receive("content://archives/recreated-archive.nar", 1)
        } else {
            ArchiveIntentState.Reception.Ignored(state)
        }
    }
}
