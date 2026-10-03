package com.cattailsw.nanidroid.runtime

data class PlaybackFrame(
    val sakura: SpeakerFrame,
    val kero: SpeakerFrame,
    val ended: Boolean,
    val textAdvanceSpeakers: Set<Int> = emptySet(),
    val input: InputRequest? = null,
    val animationRequests: List<AnimationRequest> = emptyList(),
    val surfaceChanges: List<SurfaceChange> = emptyList(),
)
