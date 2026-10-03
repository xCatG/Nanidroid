package com.cattailsw.nanidroid.data

interface BootStateStore {
    suspend fun recordActivation(directoryId: String): Boolean
    suspend fun consumeOnboarding(): Boolean
}
