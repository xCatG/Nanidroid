package com.cattailsw.nanidroid

class TapClassifier(private val doubleTapTimeoutMs: Long = 300L) {
    enum class TapType {
        SINGLE,
        DOUBLE
    }

    fun classifyTap(now: Long, lastTapTime: Long): TapType {
        return if (now - lastTapTime <= doubleTapTimeoutMs) {
            TapType.DOUBLE
        } else {
            TapType.SINGLE
        }
    }
}
