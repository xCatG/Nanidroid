package com.cattailsw.nanidroid.shiori

import java.nio.charset.Charset

/** JNI host for the maintained POSIX build of YAYA. */
class YayaShiori(path: String) : Shiori {
    private val transportCharset: Charset

    init {
        nativeLoad(path)
        transportCharset = Charset.forName(nativeTransportCharset())
    }

    override fun getModuleName(): String = "YAYA"

    override fun request(request: String): String =
        nativeRequest(request.toByteArray(transportCharset)).toString(transportCharset)

    override fun terminate() = nativeUnload()

    override fun unloadShiori() = nativeUnload()

    private external fun nativeLoad(path: String)
    private external fun nativeTransportCharset(): String
    private external fun nativeRequest(request: ByteArray): ByteArray
    private external fun nativeUnload()

    private companion object {
        init { System.loadLibrary("yaya") }
    }
}
