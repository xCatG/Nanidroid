package com.cattailsw.nanidroid

import android.content.Context
import android.util.Log
import com.cattailsw.nanidroid.util.NetworkUtil
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.LinkedList

/** Retrieves the most recent messages from the remote SSTP Bottle log. */
open class SSTPBottleSensor {
    class ApiException : Exception {
        constructor(detailMessage: String, throwable: Throwable) : super(detailMessage, throwable)

        constructor(detailMessage: String) : super(detailMessage)
    }

    class ParseException(detailMessage: String, throwable: Throwable) : Exception(detailMessage, throwable)

    companion object {
        private const val TAG = "SSTPBottleSensor"
        private const val BOTTLE_LOG =
            "https://bottle.mikage.to/fetchlog.cgi?recent=10&encoding=utf8"

        @JvmStatic
        @Throws(ApiException::class, ParseException::class)
        fun getPageContent(ctx: Context): LinkedList<String> = getUrlContent(BOTTLE_LOG, ctx)

        @JvmStatic
        @Throws(ApiException::class)
        @Synchronized
        protected fun getUrlContent(url: String?, ctx: Context): LinkedList<String> {
            Log.d(TAG, "getUrlContent: url = $url")
            try {
                BufferedReader(InputStreamReader(NetworkUtil.getURLStream(ctx, url), Charsets.UTF_8)).use {
                    return parseBuffer(it)
                }
            } catch (error: IOException) {
                throw ApiException("Problem communicating with API", error)
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        protected fun parseBuffer(br: BufferedReader): LinkedList<String> {
            while (true) {
                if (br.readLine()!!.isEmpty()) break
            }

            val results = LinkedList<String>()
            var line = br.readLine()
            while (line != null) {
                results.add(line.split("\t")[7])
                line = br.readLine()
            }
            return results
        }
    }
}
