package com.cattailsw.nanidroid.shiori

interface Shiori {
    fun getModuleName(): String
    fun request(req: String): String
    fun terminate()
    fun unloadShiori()
}
