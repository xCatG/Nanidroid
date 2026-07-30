package com.cattailsw.nanidroid

import org.junit.Assert.assertEquals
import org.junit.Test

class BootDispatchStateTest {
    @Test
    fun firstStartDispatchesBoot_butRepeatedResumeAndGhostHandoffDoNot() {
        val state = BootDispatchState()
        assertEquals(BootDispatchState.Start(started = true, dispatchBoot = true), state.startClock())
        assertEquals(BootDispatchState.Start(started = false, dispatchBoot = false), state.startClock())
        state.markBootDispatched()
        state.stopClock()
        assertEquals(BootDispatchState.Start(started = true, dispatchBoot = false), state.startClock())
        state.stopClock()
        state.markBootDispatched()
        assertEquals(BootDispatchState.Start(started = true, dispatchBoot = false), state.startClock())
    }

    @Test
    fun freshStateAfterAppRecreationMayDispatchBootOnce() {
        assertEquals(BootDispatchState.Start(started = true, dispatchBoot = true), BootDispatchState().startClock())
    }
}