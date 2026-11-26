package com.cattailsw.nanidroid

sealed interface NanoDownloadState {
    data object Idle : NanoDownloadState
    data class Downloading(
        val percent: Int? = null,
        val modelLabel: String = DEFAULT_MODEL_LABEL
    ) : NanoDownloadState

    data class Ready(val modelLabel: String = DEFAULT_MODEL_LABEL) : NanoDownloadState
    data class Failed(
        val reason: String,
        val modelLabel: String = DEFAULT_MODEL_LABEL
    ) : NanoDownloadState

    data object Unavailable : NanoDownloadState

    companion object {
        const val DEFAULT_MODEL_LABEL = "Gemini Nano"
    }
}
