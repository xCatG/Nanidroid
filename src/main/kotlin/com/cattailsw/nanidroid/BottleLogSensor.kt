package com.cattailsw.nanidroid

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.LinkedList

/** Reads the bundled Bottle log fallback used when no network content is available. */
open class BottleLogSensor : SSTPBottleSensor() {
    companion object {
        @JvmStatic
        @Throws(SSTPBottleSensor.ApiException::class, SSTPBottleSensor.ParseException::class)
        fun getPageContent(ctx: Context): LinkedList<String> = getUrlContent(null, ctx)

        @JvmStatic
        @Throws(SSTPBottleSensor.ApiException::class)
        @Synchronized
        protected fun getUrlContent(url: String?, ctx: Context): LinkedList<String> {
            try {
                val assetManager = ctx.assets
                val input = assetManager.open("sstpbottlelog.log")
                val reader = BufferedReader(InputStreamReader(input))
                return SSTPBottleSensor.parseBuffer(reader)
            } catch (error: Exception) {
                throw SSTPBottleSensor.ApiException("Problem communicating with API", error)
            }
        }
    }
}
