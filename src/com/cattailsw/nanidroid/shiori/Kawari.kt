package com.cattailsw.nanidroid.shiori

import java.nio.charset.Charset

class Kawari(private val path: String) : JNIShiori() {
    init {
        System.loadLibrary("kawari8")
        load(path)
    }

    override fun request(request: String): String =
        modResponseWithCharSet(requestFromJNI(request.toByteArray(Charset.forName("Shift_JIS"))))

    override fun unloadShiori() {
        unload()
    }

    external fun load(path: String)
    external fun unload()
    external fun requestFromJNI(req: ByteArray): ByteArray
}
