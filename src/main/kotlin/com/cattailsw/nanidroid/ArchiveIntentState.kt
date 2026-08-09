package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.dialogue.ActionOrigin
import com.cattailsw.nanidroid.runtime.dialogue.GhostActionGuard
import com.cattailsw.nanidroid.runtime.dialogue.GhostRuntimeMode
import com.cattailsw.nanidroid.runtime.dialogue.GuardedAction

/** The archive-import eligibility decision applied before the Activity receives archive state. */
internal fun allowsArchiveIngress(runtimeMode: GhostRuntimeMode?): Boolean =
    runtimeMode?.let {
        GhostActionGuard(it).allows(GuardedAction.IMPORT_INSTALL, ActionOrigin.USER)
    } ?: true

/** Durable Activity state for archive intents that arrive before startup completes. */
internal data class ArchiveIntentState(
    val consumedUri: String? = null,
    val pendingUri: String? = null,
    val pendingFlags: Int = 0,
) {
    sealed class Reception(open val state: ArchiveIntentState) {
        data class Ignored(override val state: ArchiveIntentState) : Reception(state)

        data class Pending(override val state: ArchiveIntentState) : Reception(state)

        data class Dispatch(
            override val state: ArchiveIntentState,
            val uri: String,
            val flags: Int,
        ) : Reception(state)
    }

    data class PendingArchive(
        val state: ArchiveIntentState,
        val uri: String,
        val flags: Int,
    )

    fun receive(uri: String, flags: Int): Reception {
        if (consumedUri == uri) return Reception.Ignored(this)
        if (pendingUri != null) {
            return Reception.Dispatch(copy(consumedUri = uri), uri, flags)
        }
        return Reception.Pending(copy(consumedUri = uri, pendingUri = uri, pendingFlags = flags))
    }

    /** A newly delivered intent is an explicit user action, even for the same URI. */
    fun receiveNewIntent(uri: String, flags: Int): Reception =
        copy(consumedUri = null).receive(uri, flags)

    fun takePending(): PendingArchive? {
        val uri = pendingUri ?: return null
        return PendingArchive(copy(pendingUri = null, pendingFlags = 0), uri, pendingFlags)
    }
}
