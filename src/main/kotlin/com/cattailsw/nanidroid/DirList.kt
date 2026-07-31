package com.cattailsw.nanidroid

import android.content.Context
import android.util.Log
import java.io.File

/** Enumerates installed ghost metadata without creating active SHIORI sessions. */
object DirList {
    private const val TAG = "DirList"

    @JvmStatic
    fun parseDataDir(ctx: Context): List<InfoOnlyGhost>? {
        val dataDir = File(ctx.getExternalFilesDir(null), "ghost")
        Log.d(TAG, "dir path=${dataDir.absolutePath}")
        val directoryPath = dataDir.absolutePath
        val directories = dataDir.list() ?: return null

        return directories.mapNotNull { directory ->
            val ghost = InfoOnlyGhost("$directoryPath/$directory")
            if (ghost.ghostError()) {
                Log.d(TAG, "error in ghost in:$directory")
                null
            } else {
                Log.d(TAG, "got ghost [${ghost.getGhostName()}]")
                Log.d(TAG, " craftman [${ghost.getCrafterName()}]")
                Log.d(TAG, "   sakura [${ghost.getSakuraName()}]")
                Log.d(TAG, "     kero [${ghost.getKeroName()}]")
                ghost
            }
        }
    }
}
