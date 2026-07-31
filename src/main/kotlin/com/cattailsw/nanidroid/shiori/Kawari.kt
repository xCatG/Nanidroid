package com.cattailsw.nanidroid.shiori

import java.nio.charset.Charset

/** Kawari 8's retained JNI bridge.  Its protocol remains Shift_JIS. */
class Kawari(private val path: String) : Shiori {
    init {
        System.loadLibrary("kawari8")
        load(path)
    }

    override fun getModuleName(): String = "Kawari 8"

    override fun request(request: String): String =
        requestFromJNI(request.toByteArray(SHIFT_JIS)).toString(SHIFT_JIS)

    override fun unloadShiori() = unload()

    override fun terminate() = unload()

    private external fun load(path: String)
    private external fun unload()
    private external fun requestFromJNI(req: ByteArray): ByteArray

    private companion object {
        val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
    }
}
