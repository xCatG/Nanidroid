package com.cattailsw.nanidroid

import android.content.Context
import android.util.Log
import java.io.File
import java.util.ArrayList

object DirList {
    private const val TAG = "DirList"

    @JvmStatic
    fun parseDataDir(ctx: Context): List<InfoOnlyGhost>? {
        val dataDir = File(ctx.getExternalFilesDir(null), "ghost")
        Log.d(TAG, "dir path=${dataDir.absolutePath}")
        val dP = dataDir.absolutePath
        val dirz = dataDir.list() ?: return null

        val ret = ArrayList<InfoOnlyGhost>(dirz.size)
        for (d in dirz) {
            val g = InfoOnlyGhost("$dP/$d")
            if (g.ghostError()) {
                Log.d(TAG, "error in ghost in:$d")
                continue
            }

            Log.d(TAG, "got ghost [${g.getGhostName()}]")
            Log.d(TAG, " craftman [${g.getCrafterName()}]")
            Log.d(TAG, "   sakura [${g.getSakuraName()}]")
            Log.d(TAG, "     kero [${g.getKeroName()}]")

            ret.add(g)
        }
        return ret
    }
}
