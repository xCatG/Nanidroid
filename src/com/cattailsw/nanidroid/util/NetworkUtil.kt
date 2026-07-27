package com.cattailsw.nanidroid.util

import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/** HTTPS-only network boundary for archive and update downloads. */
object NetworkUtil {
    private const val TIMEOUT_MILLIS = 20_000

    @JvmStatic
    fun exists(context: Context?, url: String?): Boolean {
        var connection: HttpsURLConnection? = null
        return try {
            connection = open(context, url)
            connection.responseCode == HttpsURLConnection.HTTP_OK
        } catch (_: IOException) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    /** The caller owns and must close the returned stream. */
    @JvmStatic
    @Throws(IOException::class)
    fun getURLStream(context: Context?, url: String?): InputStream {
        val connection = open(context, url)
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            connection.disconnect()
            throw IOException("HTTPS request failed: $responseCode")
        }
        val stream = connection.inputStream
        return if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
            GZIPInputStream(stream)
        } else {
            stream
        }
    }

    @Throws(IOException::class)
    internal fun requireHttps(value: String): URL {
        val url = URL(value)
        if (!url.protocol.equals("https", ignoreCase = true) || url.host.isEmpty()) {
            throw IOException("Only HTTPS URLs are supported")
        }
        return url
    }

    @Throws(IOException::class)
    private fun open(context: Context?, value: String?): HttpsURLConnection =
        (requireHttps(value ?: throw IOException("Only HTTPS URLs are supported"))
            .openConnection() as HttpsURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", buildUserAgent(context))
            instanceFollowRedirects = false
        }

    private fun buildUserAgent(context: Context?): String {
        if (context == null) return "Nanidroid (gzip)"
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.packageName}/${info.versionName} (${info.versionCode}) (gzip)"
        } catch (_: PackageManager.NameNotFoundException) {
            "Nanidroid (gzip)"
        }
    }
}
