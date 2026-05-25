package com.cattailsw.nanidroid

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.LinkedList

class BottleLogSensor : SSTPBottleSensor() {
    companion object {
        @JvmStatic
        @Throws(ApiException::class, ParseException::class)
        fun getPageContent(ctx: Context): LinkedList<String> {
            return getAssetContent(ctx)
        }

        @JvmStatic
        @Synchronized
        @Throws(ApiException::class)
        private fun getAssetContent(ctx: Context): LinkedList<String> {
            return try {
                val assetManager = ctx.assets
                val isStream = assetManager.open("sstpbottlelog.log")
                val reader = BufferedReader(InputStreamReader(isStream))
                val results = parseBuffer(reader)
                reader.close()
                results
            } catch (e: Exception) {
                throw ApiException("Problem communicating with API", e)
            }
        }
    }
}
