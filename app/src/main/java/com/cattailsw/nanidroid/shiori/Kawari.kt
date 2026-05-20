package com.cattailsw.nanidroid.shiori

import java.nio.charset.Charset

class Kawari(private val path: String) : JNIShiori() {
    companion object {
        private const val TAG = "Kawari"
        init {
            System.loadLibrary("kawari8")
        }
    }

    init {
        load(this.path)
    }

    override fun request(req: String): String {
        return modResponseWithCharSet(requestFromJNI(req.toByteArray(Charset.forName("Shift_JIS"))))
    }

    override fun unloadShiori() {
        unload()
    }

    external fun load(path: String)
    external fun unload()
    external fun requestFromJNI(req: ByteArray): ByteArray
}
