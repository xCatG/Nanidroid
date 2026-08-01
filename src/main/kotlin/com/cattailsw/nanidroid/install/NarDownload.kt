package com.cattailsw.nanidroid.install

/** A durable archive acquisition request and its current lifecycle state. */
data class NarDownload(
    val id: String,
    val source: NarDownloadSource,
    val retainedUri: String? = null,
    val downloadManagerId: Long? = null,
    val state: NarDownloadState = NarDownloadState.Queued,
) {
    init {
        require(id.isNotBlank()) { "download id must not be blank" }
    }
}

/** The original location from which an archive can be acquired again. */
sealed interface NarDownloadSource {
    data class Remote(val uri: String) : NarDownloadSource
    data class Local(val uri: String) : NarDownloadSource
}

/** Immutable state recorded for an archive request. */
sealed interface NarDownloadState {
    data object Queued : NarDownloadState
    data object Downloading : NarDownloadState
    data object Installing : NarDownloadState
    data object Complete : NarDownloadState
    data class NeedsAttention(val failure: Failure) : NarDownloadState

    data class Failure(val message: String)
}
