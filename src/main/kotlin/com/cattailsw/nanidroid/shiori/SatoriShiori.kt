package com.cattailsw.nanidroid.shiori

import android.content.Context
import java.nio.charset.Charset

/** Android JNI host for the bundled Satori SHIORI implementation. */
class SatoriShiori(
    private val path: String,
    context: Context?,
) : Shiori {
    private val cacheDirectory = context?.codeCacheDir?.absolutePath ?: path
    private var loaded = false
    private var loadCleanupRequired = false

    override fun getModuleName(): String = "Satori"

    override fun load(): ShioriLoadResult {
        if (loaded) {
            return ShioriLoadResult.Failed(
                IllegalStateException("${getModuleName()} is already loaded"),
                LoadFailureState.OwnerAlreadyPresent,
            )
        }
        val status = try {
            nativeLoad(path, cacheDirectory)
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
            decodeNativeResponse(request)
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

    private fun decodeNativeResponse(request: String): String {
        // Satori consumes the original Shift_JIS SHIORI protocol. Its response
        // announces the charset, so decode with the same transport encoding.
        val normalized = request.removeSuffix("\r\n\r\n") +
            "\r\nCharset: Shift_JIS\r\n\r\n"
        return nativeRequest(normalized.toByteArray(SHIFT_JIS)).toString(SHIFT_JIS)
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

    private external fun nativeLoad(path: String, cacheDirectory: String): Int
    private external fun nativeRequest(request: ByteArray): ByteArray
    private external fun nativeUnload(): Boolean

    private companion object {
        const val NATIVE_LOADED = 1
        const val NATIVE_FAILED_EMPTY = 0
        const val NATIVE_OWNER_PRESENT = -1
        const val NATIVE_CLEANUP_REQUIRED = -2
        val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

        init {
            System.loadLibrary("satoriya")
        }
    }
}
