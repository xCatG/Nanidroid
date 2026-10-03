package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.engine.NativeAvailability
import com.cattailsw.nanidroid.engine.NativeKind
import com.cattailsw.nanidroid.engine.NativeLease
import com.cattailsw.nanidroid.engine.NativeLoadResult
import com.cattailsw.nanidroid.engine.NativeShioriHost
import com.cattailsw.nanidroid.engine.NativeShutdownResult
import com.cattailsw.nanidroid.engine.NativeUnloadResult
import com.cattailsw.nanidroid.engine.ShioriEvent
import com.cattailsw.nanidroid.engine.ShioriReply
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/** Narrow runtime boundary; the process host retains all native ownership rules. */
interface NativePort {
    val availability: StateFlow<NativeAvailability>
    suspend fun load(kind: NativeKind, directory: File, libraryName: String): NativeLoadResult
    suspend fun request(lease: NativeLease, event: ShioriEvent): ShioriReply
    suspend fun unload(lease: NativeLease): NativeUnloadResult
    suspend fun shutdown(lease: NativeLease, notifyDestroy: Boolean = true, timeoutMillis: Long = 5_000L): NativeShutdownResult
}

class HostNativePort(private val host: NativeShioriHost) : NativePort {
    override val availability = host.availability
    override suspend fun load(kind: NativeKind, directory: File, libraryName: String) =
        host.load(kind, directory, libraryName)
    override suspend fun request(lease: NativeLease, event: ShioriEvent) = host.request(lease, event)
    override suspend fun unload(lease: NativeLease) = host.unload(lease)
    override suspend fun shutdown(lease: NativeLease, notifyDestroy: Boolean, timeoutMillis: Long) =
        host.shutdown(lease, notifyDestroy, timeoutMillis)
}
