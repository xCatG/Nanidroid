package com.cattailsw.nanidroid.shiori

import android.content.Context
import java.nio.charset.Charset

/** JNI host for the maintained POSIX build of YAYA. */
class YayaShiori(path: String, context: Context?) : Shiori {
    init {
        nativeLoad(path, context?.codeCacheDir?.absolutePath ?: path)
    }

    override fun getModuleName(): String = "YAYA"

    override fun request(request: String): String {
        val requestCharset = transportCharset()
        val response = nativeRequest(request.toByteArray(requestCharset))
        return response.toString(transportCharset())
    }

    override fun terminate() = nativeUnload()

    override fun unloadShiori() = nativeUnload()

    private external fun nativeLoad(path: String, cacheDirectory: String)
    private external fun nativeTransportCharset(): String
    private external fun nativeRequest(request: ByteArray): ByteArray
    private external fun nativeUnload()

    private fun transportCharset(): Charset = when (nativeTransportCharset().lowercase()) {
        "default", "osnative" -> Charset.defaultCharset()
        "binary" -> Charsets.ISO_8859_1
        else -> Charset.forName(nativeTransportCharset())
    }

    private companion object {
        init { System.loadLibrary("yaya") }
    }
}
