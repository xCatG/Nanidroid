package com.cattailsw.nanidroid.shiori

import java.nio.charset.Charset

/** JNI-backed SHIORI envelope bridge. Native member names are ABI-stable. */
abstract class JNIShiori : Shiori {
    override fun getModuleName(): String = getModuleNameFromJNI()

    open override fun request(request: String): String =
        modResponseWithCharSet(requestFromJNI(request))

    override fun terminate() {
        terminateFromJNI()
    }

    abstract override fun unloadShiori()

    protected fun modResponseWithCharSet(bytes: ByteArray?): String {
        val text = String(bytes!!)
        val charsetPosition = text.indexOf("Charset:")
        if (charsetPosition == -1) return text
        val crlfPosition = text.indexOf("\r\n", charsetPosition)
        val charset = text.substring(charsetPosition + 8, crlfPosition).trim()
        return try {
            String(bytes, Charset.forName(charset))
        } catch (_: Exception) {
            text
        }
    }

    open external fun getModuleNameFromJNI(): String
    open external fun requestFromJNI(req: String): ByteArray
    open external fun terminateFromJNI()
}
