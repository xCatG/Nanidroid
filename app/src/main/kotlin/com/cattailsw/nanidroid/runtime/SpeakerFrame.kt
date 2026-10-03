package com.cattailsw.nanidroid.runtime

data class SpeakerFrame(
    val surfaceId: Int,
    val visible: Boolean,
    val text: String,
    val balloonVisible: Boolean,
    val visual: SurfaceVisual? = null,
    val choices: List<ChoiceItem> = emptyList(),
)
