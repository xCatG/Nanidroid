package com.cattailsw.nanidroid.shiori

import java.nio.charset.Charset

/** JNI host for the maintained POSIX build of YAYA. */
class YayaShiori(path: String) : Shiori {
    init {
        nativeLoad(path)
    }

    override fun getModuleName(): String = "YAYA"

    override fun request(request: String): String =
        nativeRequest(request.toByteArray(SHIFT_JIS)).toString(SHIFT_JIS)

    override fun terminate() = nativeUnload()

    override fun unloadShiori() = nativeUnload()

    private external fun nativeLoad(path: String)
    private external fun nativeRequest(request: ByteArray): ByteArray
    private external fun nativeUnload()

    private companion object {
        val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

        init { System.loadLibrary("yaya") }
    }
}
