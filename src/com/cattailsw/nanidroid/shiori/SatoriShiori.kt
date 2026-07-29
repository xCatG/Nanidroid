package com.cattailsw.nanidroid.shiori

import android.content.Context
import java.nio.charset.Charset

/** Android JNI host for the bundled Satori SHIORI implementation. */
class SatoriShiori(path: String, context: Context?) : Shiori {
    init {
        nativeLoad(path, context?.codeCacheDir?.absolutePath ?: path)
    }

    override fun getModuleName(): String = "Satori"

    override fun request(request: String): String {
        // Satori consumes the original Shift_JIS SHIORI protocol. Its response
        // announces the charset, so decode with the same transport encoding.
        val normalized = request.removeSuffix("\r\n\r\n") +
            "\r\nCharset: Shift_JIS\r\n\r\n"
        return nativeRequest(normalized.toByteArray(SHIFT_JIS)).toString(SHIFT_JIS)
    }

    override fun terminate() = nativeUnload()

    override fun unloadShiori() = nativeUnload()

    private external fun nativeLoad(path: String, cacheDirectory: String)
    private external fun nativeRequest(request: ByteArray): ByteArray
    private external fun nativeUnload()

    private companion object {
        val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

        init {
            System.loadLibrary("ssu")
            System.loadLibrary("satoriya")
        }
    }
}
