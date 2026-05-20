package com.cattailsw.nanidroid.shiori

import java.nio.charset.Charset

abstract class JNIShiori : Shiori {
    override fun getModuleName(): String {
        return getModuleNameFromJNI()
    }

    override fun request(req: String): String {
        return modResponseWithCharSet(requestFromJNI(req))
    }

    override fun terminate() {
        terminateFromJNI()
    }

    abstract override fun unloadShiori()

    protected fun modResponseWithCharSet(bytes: ByteArray): String {
        val s = String(bytes)
        val posCharset = s.indexOf("Charset:")
        if (posCharset == -1) {
            return s
        } else {
            val posCrlf = s.indexOf("\r\n", posCharset)
            if (posCrlf == -1) return s
            val charsetName = s.substring(posCharset + 8, posCrlf).trim()
            return try {
                String(bytes, Charset.forName(charsetName))
            } catch (e: Exception) {
                s
            }
        }
    }

    external fun getModuleNameFromJNI(): String
    external fun requestFromJNI(req: String): ByteArray
    external fun terminateFromJNI()
}
