package com.cattailsw.nanidroid.durable

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local handoff from accepted user-origin durable work to the resumed Activity.
 *
 * This deliberately is not persisted: configuration changes must retain the opportunity to
 * request notification permission, while a cold process restore must not manufacture a prompt.
 */
internal object DurableNotificationPermissionAcceptance {
    private val lock = Any()
    private val accepted = MutableStateFlow(false)

    fun observe(): StateFlow<Boolean> = accepted.asStateFlow()

    fun markAccepted() {
        synchronized(lock) { accepted.value = true }
    }

    fun consumeAccepted(): Boolean = synchronized(lock) {
        if (!accepted.value) return@synchronized false
        accepted.value = false
        true
    }

    internal fun hasPendingAcceptanceForTesting(): Boolean = accepted.value

    internal fun resetForTesting() {
        synchronized(lock) { accepted.value = false }
    }
}
