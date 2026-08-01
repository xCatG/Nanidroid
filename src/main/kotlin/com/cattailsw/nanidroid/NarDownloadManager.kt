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
    fun handleCompletedDownload(context: Context, id: Long) {
        if (!isRecorded(context, id)) return
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        try {
            val query = manager.query(DownloadManager.Query().setFilterById(id)) ?: return
            val successful = query.use { cursor ->
                cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
            }
            if (!successful) return
            manager.openDownloadedFile(id).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    val result = NarContentUriImport.importStream(File(context.cacheDir, "nar-import"), { stream }) { staged ->
                        GhostMgr(context).installGhost("download", staged.path)
                    }
                    if (!result.isSuccess) Log.w(TAG, "Downloaded archive could not be installed: ${result.message}")
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Downloaded archive could not be opened", error)
        } finally {
            forget(context, id)
            manager.remove(id)
        }
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

    private fun randomName(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
