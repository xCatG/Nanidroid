package com.cattailsw.nanidroid.shiori

import android.content.Context
import java.nio.charset.Charset

/** JNI host for the maintained POSIX build of YAYA. */
class YayaShiori(
    private val path: String,
    context: Context?,
) : Shiori {
    private val cacheDirectory = context?.codeCacheDir?.absolutePath ?: path
    private var loaded = false
    private var loadCleanupRequired = false

    override fun getModuleName(): String = "YAYA"

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
        val requestCharset = transportCharset()
        val response = nativeRequest(request.toByteArray(requestCharset))
        return response.toString(transportCharset())
    }

    override fun unloadShiori(): ShioriUnloadResult {
        if (!loaded && !loadCleanupRequired) return ShioriUnloadResult.Unloaded
        return runCatching { check(nativeUnload()) }.fold(
            {
                loaded = false
                loadCleanupRequired = false
                ShioriUnloadResult.Unloaded
            },
            { ShioriUnloadResult.Failed(it, ownershipCertain = false) },
        )
    }

    internal fun probeNativeCharsetAndRequestAfterUnloadForTesting(): List<NativePostUnloadProbeResult> =
        listOf(
            capturePostUnloadProbeForTesting("charset") { nativeTransportCharset() },
            capturePostUnloadProbeForTesting("request") {
                nativeRequest("GET SHIORI/3.0\r\n\r\n".toByteArray())
            },
        )

    private external fun nativeLoad(path: String, cacheDirectory: String): Int
    private external fun nativeTransportCharset(): String
    private external fun nativeRequest(request: ByteArray): ByteArray
    private external fun nativeUnload(): Boolean

    private fun transportCharset(): Charset = when (nativeTransportCharset().lowercase()) {
        "default", "osnative" -> Charset.defaultCharset()
        "binary" -> Charsets.ISO_8859_1
        else -> Charset.forName(nativeTransportCharset())
    }

    private companion object {
        const val NATIVE_LOADED = 1
        const val NATIVE_FAILED_EMPTY = 0
        const val NATIVE_OWNER_PRESENT = -1
        const val NATIVE_CLEANUP_REQUIRED = -2
        init { System.loadLibrary("yaya") }
    }
}
