package com.cattailsw.nanidroid

import android.content.Intent
import android.net.Uri
import java.util.Locale

/**
 * The only externally accepted install/update entry point until the SAF picker
 * is introduced. Caller-controlled file URIs never become private paths.
 */
object IncomingNarIntent {
    @JvmStatic
    fun isApprovedDownload(intent: Intent?): Boolean =
        Intent.ACTION_VIEW == intent?.action && isApprovedDownload(intent.data)

    @JvmStatic
    fun isApprovedDownload(uri: Uri?): Boolean {
        val download = uri ?: return false
        if (!download.scheme.equals("https", ignoreCase = true)) return false
        if (download.host.isNullOrEmpty()) return false
        val path = download.path ?: return false
        val lowerPath = path.lowercase(Locale.US)
        return lowerPath.endsWith(".nar") || lowerPath.endsWith(".zip")
    }
}
