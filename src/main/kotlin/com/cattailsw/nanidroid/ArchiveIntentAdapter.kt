package com.cattailsw.nanidroid

import android.content.Intent
import android.net.Uri

/** The deliberately narrow external archive entry point. */
object ArchiveIntentAdapter {
    private val supportedMimeTypes = setOf("application/zip", "application/x-nar")

    fun contentUri(intent: Intent?, resolvedMimeType: String? = intent?.type): Uri? {
        return contentUri(intent?.action, intent?.data, resolvedMimeType, intent?.flags ?: 0)
    }

    internal fun contentUri(
        action: String?,
        uri: Uri?,
        mimeType: String?,
        flags: Int,
    ): Uri? {
        return uri?.takeIf { accepts(action, it.scheme, mimeType, flags) }
    }

    internal fun accepts(action: String?, scheme: String?, mimeType: String?, flags: Int): Boolean =
        action == Intent.ACTION_VIEW &&
            scheme.equals("content", ignoreCase = true) &&
            mimeType?.lowercase() in supportedMimeTypes &&
            flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
}
