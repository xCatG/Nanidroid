package com.cattailsw.nanidroid.runtime

fun interface MonotonicClock {
    fun nowMillis(): Long
}
