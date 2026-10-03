package com.cattailsw.nanidroid.shiori

class YayaShiori {
    companion object { init { System.loadLibrary("ssu"); System.loadLibrary("yaya") } }
    external fun nativeLoad(path: String, cacheDirectory: String): Int
    external fun nativeTransportCharset(): String
    external fun nativeRequest(request: ByteArray): ByteArray
    external fun nativeUnload(): Boolean
}
