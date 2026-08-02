package com.cattailsw.nanidroid.install

/** A durable archive acquisition request and its current lifecycle state. */
data class NarDownload(
    val id: String,
    val source: NarDownloadSource,
    val attemptId: Long = 1L,
    val retainedUri: String? = null,
    val downloadManagerId: Long? = null,
    val workManagerId: String? = null,
    val createdAtMillis: Long = 0,
    val state: NarDownloadState = NarDownloadState.Queued,
) {
    init {
        require(id.isNotBlank()) { "download id must not be blank" }
        require(attemptId > 0L) { "attempt id must be positive" }
    }
}

/** The original location from which an archive can be acquired again. */
sealed interface NarDownloadSource {
    data class Remote(val uri: String) : NarDownloadSource
    data class Local(val uri: String) : NarDownloadSource
}

/** Immutable state recorded for an archive request. */
sealed interface NarDownloadState {
    data object Copying : NarDownloadState
    data object Queued : NarDownloadState
    data object Downloading : NarDownloadState
    data object Installing : NarDownloadState
    data object Complete : NarDownloadState
    data object Cancelled : NarDownloadState
    data class NeedsAttention(val failure: Failure) : NarDownloadState

    data class Failure(val message: String)
}
