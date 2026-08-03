package com.cattailsw.nanidroid.runtime.dialogue

data class GhostRuntimeMode(
    val playingTalk: Boolean,
    val pendingUserAction: Boolean,
    val passive: Boolean,
) {
    val canTalk: Boolean
        get() = !playingTalk && !pendingUserAction && !passive
}
