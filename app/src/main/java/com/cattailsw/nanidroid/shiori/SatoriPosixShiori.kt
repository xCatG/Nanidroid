package com.cattailsw.nanidroid.shiori

import java.nio.charset.Charset

class SatoriPosixShiori(private val path: String) : JNIShiori() {
    companion object {
        init {
            System.loadLibrary("satoriya")
        }
    }

    init {
        load(path)
    }

    override fun request(req: String): String {
        val tmpR = req.substring(0, req.length - 2) + "Charset: Shift_JIS\r\n\r\n"
        val result = try {
            requestFromJNI2(tmpR.toByteArray(Charset.forName("Shift_JIS")))
        } catch (e: Exception) {
            e.printStackTrace()
            return NanidroidShiori.RES_NO_CONTENT
        }
        return modResponseWithCharSet(result)
    }

    override fun unloadShiori() {
        unload()
    }

    external fun load(path: String)
    external fun unload()
    external fun requestFromJNI2(req: ByteArray): ByteArray
}
