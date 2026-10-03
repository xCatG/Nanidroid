package com.cattailsw.nanidroid.engine

sealed interface NativeShutdownResult {
    data object Completed : NativeShutdownResult
    data class Failed(val message: String) : NativeShutdownResult
    data class Unresolved(val message: String) : NativeShutdownResult
}
