package com.cattailsw.nanidroid.shiori

import java.nio.charset.Charset

/** Kawari 8's retained JNI bridge.  Its protocol remains Shift_JIS. */
class Kawari(private val path: String) : Shiori {
    private var loaded = false
    private var loadCleanupRequired = false

    override fun getModuleName(): String = "Kawari 8"

    override fun load(): ShioriLoadResult {
        if (loaded) {
            return ShioriLoadResult.Failed(
                IllegalStateException("${getModuleName()} is already loaded"),
                LoadFailureState.OwnerAlreadyPresent,
            )
        }
        val status = try {
            nativeLoad(path)
        } catch (failure: Throwable) {
            loadCleanupRequired = true
            return ShioriLoadResult.Failed(failure, LoadFailureState.CleanupRequired)
        }
        return when (status) {
            NATIVE_LOADED -> {
                loaded = true
                ShioriLoadResult.Loaded
            }
            NATIVE_FAILED_EMPTY -> ShioriLoadResult.Failed(
                IllegalStateException("${getModuleName()} could not load this ghost"),
                LoadFailureState.ProvenEmpty,
            )
            NATIVE_OWNER_PRESENT -> ShioriLoadResult.Failed(
                IllegalStateException("${getModuleName()} already has a native owner"),
                LoadFailureState.OwnerAlreadyPresent,
            )
            NATIVE_CLEANUP_REQUIRED -> {
                loadCleanupRequired = true
                ShioriLoadResult.Failed(
                    IllegalStateException("${getModuleName()} load cleanup is required"),
                    LoadFailureState.CleanupRequired,
                )
            }
            else -> {
                loadCleanupRequired = true
                ShioriLoadResult.Failed(
                    IllegalStateException("Unknown ${getModuleName()} native load status: $status"),
                    LoadFailureState.CleanupRequired,
                )
            }
        }
    }

    override fun request(request: String): String {
        if (!loaded) {
            throw ShioriRequestException(
                "${getModuleName()} is not loaded",
                ownershipCertain = true,
            )
        }
        return try {
            requestFromJNI(request.toByteArray(SHIFT_JIS)).toString(SHIFT_JIS)
        } catch (failure: ShioriRequestException) {
            throw failure
        } catch (failure: LinkageError) {
            throw ShioriRequestException(
                "${getModuleName()} request linkage failed",
                failure,
                ownershipCertain = false,
            )
        } catch (failure: Exception) {
            throw ShioriRequestException(
                "${getModuleName()} request failed",
                failure,
                ownershipCertain = true,
            )
        }
    }

    override fun unloadShiori(): ShioriUnloadResult =
        runCatching { check(nativeUnload()) }.fold(
            {
                loaded = false
                loadCleanupRequired = false
                ShioriUnloadResult.Unloaded
            },
            { ShioriUnloadResult.Failed(it, ownershipCertain = false) },
        )

    private external fun nativeLoad(path: String): Int
    private external fun nativeUnload(): Boolean
    private external fun requestFromJNI(req: ByteArray): ByteArray

    private companion object {
        const val NATIVE_LOADED = 1
        const val NATIVE_FAILED_EMPTY = 0
        const val NATIVE_OWNER_PRESENT = -1
        const val NATIVE_CLEANUP_REQUIRED = -2
        val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

        init {
            System.loadLibrary("kawari8")
        }
    }
}
