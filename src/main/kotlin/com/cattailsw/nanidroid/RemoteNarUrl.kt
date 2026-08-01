package com.cattailsw.nanidroid

import android.net.Uri
import java.net.URI
import java.util.Locale

/** Validates a user-entered remote NAR archive URL before a download is queued. */
object RemoteNarUrl {
    /** Produces the lowercase HTTPS scheme required by [android.app.DownloadManager]. */
    @JvmStatic
    internal fun normalizeForDownload(uri: Uri): Uri =
        uri.buildUpon().scheme(normalizedSchemeForDownload(uri.scheme)).build()

    @JvmStatic
    internal fun normalizedSchemeForDownload(scheme: String?): String? = scheme?.lowercase(Locale.US)

    @JvmStatic
    fun isApproved(uri: Uri?): Boolean = isApproved(uri?.toString())

    @JvmStatic
    fun isApproved(value: String?): Boolean = try {
        val target = URI(value ?: return false)
        if (!target.scheme.equals("https", ignoreCase = true) || target.host.isNullOrEmpty()) return false
        val path = target.rawPath ?: return false
        val lowerPath = path.lowercase(Locale.US)
        return lowerPath.endsWith(".nar") || lowerPath.endsWith(".zip")
    } catch (_: Exception) { false }
}
