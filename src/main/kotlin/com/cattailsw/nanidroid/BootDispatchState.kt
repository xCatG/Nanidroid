package com.cattailsw.nanidroid

internal class BootDispatchState {
    data class Start(val started: Boolean, val dispatchBoot: Boolean)

    private var clockStarted = false
    private var bootDispatched = false

    fun startClock(): Start {
        if (clockStarted) return Start(started = false, dispatchBoot = false)
        clockStarted = true
        return Start(started = true, dispatchBoot = !bootDispatched)
    }

    fun stopClock() {
        clockStarted = false
    }

    fun markBootDispatched() {
        bootDispatched = true
    }

    fun resetForNoGhost() {
        bootDispatched = false
    }
}