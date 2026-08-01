package com.cattailsw.nanidroid

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.cattailsw.nanidroid.install.NarContentUriImport
import java.io.File
import java.io.FileInputStream
import java.security.SecureRandom

/** Owns app-initiated remote archive downloads and their verified install handoff. */
object NarDownloadManager {
    private const val TAG = "NarDownloadManager"
    private const val PREFS = "nar_downloads"

    @JvmStatic
    fun enqueue(context: Context, url: Uri): Long? {
        if (!RemoteNarUrl.isApproved(url)) return null
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        return try {
            val request = DownloadManager.Request(RemoteNarUrl.normalizeForDownload(url))
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "nar/${randomName()}.zip")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverRoaming(false)
            manager.enqueue(request).also { record(context, it) }
        } catch (error: IllegalStateException) {
            Log.w(TAG, "External download storage is unavailable", error)
            null
        }
    }

    @JvmStatic
    fun handleCompletedDownload(context: Context, id: Long): Boolean {
        if (!isRecorded(context, id)) return false
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return true
        var installed = false
        try {
            val query = manager.query(DownloadManager.Query().setFilterById(id)) ?: return true
            val successful = query.use { cursor ->
                cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
            }
            if (!successful) return false
            manager.openDownloadedFile(id).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    val ghostMgr = GhostMgr(context)
                    val result = NarContentUriImport.importStream(
                        File(context.cacheDir, "nar-import"),
                        { stream },
                        maxBytes = MAX_ARCHIVE_BYTES,
                    ) { staged -> ghostMgr.installGhost("download", staged.path) }
                    if (!result.isSuccess) {
                        Log.w(TAG, "Downloaded archive could not be installed: ${result.message}")
                        return result.retryable || ghostMgr.getLastInstallError()?.startsWith("Nanidroid cannot") == true
                    }
                    installed = true
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Downloaded archive could not be opened", error)
            return true
        } finally {
            if (installed) {
                forget(context, id)
                manager.remove(id)
            }
        }
        return false
    }

    private fun record(context: Context, id: Long) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.edit().putBoolean(pendingKey(id), true).apply()
    }

    private fun isRecorded(context: Context, id: Long): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(pendingKey(id), false)

    private fun forget(context: Context, id: Long) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.edit().remove(pendingKey(id)).apply()
    }

    private fun pendingKey(id: Long): String = "pending_$id"

    private const val MAX_ARCHIVE_BYTES = 544L * 1024L * 1024L

    private fun randomName(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
