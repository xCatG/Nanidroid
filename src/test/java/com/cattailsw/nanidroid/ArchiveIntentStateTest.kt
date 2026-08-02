package com.cattailsw.nanidroid

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveIntentStateTest {
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
}
