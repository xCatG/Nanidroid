package com.cattailsw.nanidroid

import android.content.Intent
import android.net.Uri

/** The deliberately narrow external archive entry point. */
object ArchiveIntentAdapter {
    private val supportedMimeTypes = setOf("application/zip", "application/x-nar")

    fun contentUri(intent: Intent?): Uri? {
        return contentUri(intent?.action, intent?.data, intent?.type, intent?.flags ?: 0)
    }

    internal fun contentUri(
        action: String?,
        uri: Uri?,
        mimeType: String?,
        flags: Int,
    ): Uri? {
        if (action != Intent.ACTION_VIEW || uri == null) return null
        if (!uri.scheme.equals("content", ignoreCase = true)) return null
        if (mimeType?.lowercase() !in supportedMimeTypes) return null
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return null
        return uri
    }
}
