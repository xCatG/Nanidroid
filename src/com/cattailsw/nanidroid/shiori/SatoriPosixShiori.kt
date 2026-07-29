package com.cattailsw.nanidroid.shiori

import java.nio.charset.Charset

class SatoriPosixShiori(private val path: String) : JNIShiori() {
    init {
        System.loadLibrary("satoriya")
        load(path)
    }

    override fun request(request: String): String {
        val rewritten = request.substring(0, request.length - 2) + "Charset: Shift_JIS\r\n\r\n"
        return try {
            modResponseWithCharSet(requestFromJNI2(rewritten.toByteArray(Charset.forName("Shift_JIS"))))
        } catch (error: Exception) {
            error.printStackTrace()
            NanidroidShiori.RES_NO_CONTENT
        }
    }

    override fun unloadShiori() { unload() }

    external fun load(path: String)
    external fun unload()
    external fun requestFromJNI2(req: ByteArray): ByteArray
}
