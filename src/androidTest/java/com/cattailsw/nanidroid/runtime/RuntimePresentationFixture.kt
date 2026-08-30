package com.cattailsw.nanidroid.runtime

internal fun runtimePresentation(
    sakuraText: String,
    sakuraSurfaceId: String,
    sakuraAnimationId: String?,
    sakuraBalloonId: String,
    keroText: String,
    keroSurfaceId: String,
    keroAnimationId: String?,
    keroBalloonId: String,
): RuntimePresentation = RuntimePresentation(
    sakura = RuntimeSpeakerPresentation(
        text = sakuraText,
        surfaceId = sakuraSurfaceId,
        surfaceEpoch = if (sakuraAnimationId == null) 0L else 1L,
        balloonVisible = sakuraText.isNotEmpty() && sakuraBalloonId != "-1",
    ),
    kero = RuntimeSpeakerPresentation(
        text = keroText,
        surfaceId = keroSurfaceId,
        surfaceEpoch = if (keroAnimationId == null) 0L else 1L,
        balloonVisible = keroText.isNotEmpty() && keroBalloonId != "-1",
    ),
    talkingAnimationEnabled = sakuraAnimationId == null && keroAnimationId == null,
)
