package com.cattailsw.nanidroid.shiori

class Kawari {
    companion object { init { System.loadLibrary("kawari8") } }
    external fun nativeLoad(path: String): Int
    external fun nativeUnload(): Boolean
    external fun requestFromJNI(request: ByteArray): ByteArray
}
