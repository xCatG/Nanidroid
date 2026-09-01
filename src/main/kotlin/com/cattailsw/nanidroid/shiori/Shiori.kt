package com.cattailsw.nanidroid.shiori

sealed interface ShioriLoadResult {
    data object Loaded : ShioriLoadResult
    data class Failed(
        val cause: Throwable,
        val state: LoadFailureState,
    ) : ShioriLoadResult
}

enum class LoadFailureState {
    ProvenEmpty,
    OwnerAlreadyPresent,
    CleanupRequired,
}

sealed interface ShioriUnloadResult {
    data object Unloaded : ShioriUnloadResult
    data class Failed(val cause: Throwable, val ownershipCertain: Boolean) : ShioriUnloadResult
}

class ShioriRequestException(
    message: String,
    cause: Throwable? = null,
    val ownershipCertain: Boolean,
) : IllegalStateException(message, cause)

interface Shiori {
    fun getModuleName(): String
    fun load(): ShioriLoadResult
    fun request(request: String): String
    fun unloadShiori(): ShioriUnloadResult
}
