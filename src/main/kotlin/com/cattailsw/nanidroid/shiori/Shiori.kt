package com.cattailsw.nanidroid.shiori

interface Shiori {
    fun getModuleName(): String

    fun request(request: String): String

    fun terminate()

    fun unloadShiori()
}
