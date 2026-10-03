package com.cattailsw.nanidroid.shiori

class SatoriShiori {
    companion object { init { System.loadLibrary("ssu"); System.loadLibrary("satoriya") } }
    external fun nativeLoad(path: String, cacheDirectory: String): Int
    external fun nativeRequest(request: ByteArray): ByteArray
    external fun nativeUnload(): Boolean
}
