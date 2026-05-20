package com.cattailsw.nanidroid.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object NetworkUtil {
    private const val TAG = "NetworkUtil"

    @JvmStatic
    fun exists(ctx: Context?, urlString: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", buildUserAgent(ctx))
            connection.setRequestProperty("Accept-Encoding", "gzip")
            val responseCode = connection.responseCode
            responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            connection?.disconnect()
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun getURLStream(ctx: Context?, urlString: String): InputStream {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", buildUserAgent(ctx))
        connection.setRequestProperty("Accept-Encoding", "gzip")

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("HTTP error code: $responseCode")
        }

        val encoding = connection.contentEncoding
        val stream = connection.inputStream
        return if (encoding != null && encoding.lowercase().contains("gzip")) {
            GZIPInputStream(stream)
        } else {
            stream
        }
    }

    private fun buildUserAgent(context: Context?): String {
        if (context == null) {
            return "cattail software default UA (gzip)"
        }
        return try {
            val manager = context.packageManager
            val info = manager.getPackageInfo(context.packageName, 0)
            "${info.packageName}/${info.versionName} (${info.versionCode}) (gzip)"
        } catch (e: PackageManager.NameNotFoundException) {
            "cattail software default UA (gzip)"
        }
    }
}
