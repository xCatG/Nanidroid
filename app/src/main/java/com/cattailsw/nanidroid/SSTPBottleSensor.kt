package com.cattailsw.nanidroid

import android.content.Context
import android.util.Log
import com.cattailsw.nanidroid.util.NetworkUtil
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.LinkedList

open class SSTPBottleSensor {
    companion object {
        private const val TAG = "SSTPBottleSensor"
        private const val BOTTLE_LOG = "http://bottle.mikage.to/fetchlog.cgi?recent=10&encoding=utf8"

        @JvmStatic
        @Throws(ApiException::class, ParseException::class)
        fun getPageContent(ctx: Context): LinkedList<String> {
            return getUrlContent(BOTTLE_LOG, ctx)
        }

        @JvmStatic
        @Synchronized
        @Throws(ApiException::class)
        protected fun getUrlContent(url: String, ctx: Context): LinkedList<String> {
            Log.d(TAG, "getUrlContent: url = $url")
            try {
                val stream = NetworkUtil.getURLStream(ctx, url)
                val br = BufferedReader(InputStreamReader(stream, "UTF-8"))
                val results = parseBuffer(br)
                br.close()
                return results
            } catch (e: IOException) {
                throw ApiException("Problem communicating with API", e)
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        protected fun parseBuffer(br: BufferedReader): LinkedList<String> {
            // skip status lines on top
            while (true) {
                val line = br.readLine() ?: break
                if (line.isEmpty()) break
            }

            val results = LinkedList<String>()
            var line = br.readLine()
            while (line != null) {
                val column = line.split("\t").toTypedArray()
                if (column.size > 7) {
                    results.add(column[7])
                }
                line = br.readLine()
            }
            return results
        }
    }

    class ApiException : Exception {
        constructor(detailMessage: String, throwable: Throwable?) : super(detailMessage, throwable)
        constructor(detailMessage: String) : super(detailMessage)
    }

    class ParseException : Exception {
        constructor(detailMessage: String, throwable: Throwable?) : super(detailMessage, throwable)
    }
}
