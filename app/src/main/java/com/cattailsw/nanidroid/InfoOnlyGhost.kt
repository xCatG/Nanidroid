package com.cattailsw.nanidroid

import android.util.Log

class InfoOnlyGhost(path: String) : Ghost(path) {
    companion object {
        private const val TAG = "InfoOnlyGhost"
    }

    override fun loadGhostInfo() {
        val masterGhost = "$rootPath/ghost/master"
        val masterGhostDesc = "$masterGhost/descript.txt"
        val ghostDr = DescReader(masterGhostDesc)

        val masterShell = "$rootPath/shell/master"
        val masterShellDesc = "$masterShell/descript.txt"
        val shellDr = DescReader(masterShellDesc)

        try {
            ghostDesc = ghostDr.parse()
        } catch (e: Exception) {
            Log.d(TAG, "desc parsing error")
            e.printStackTrace()
            error = true
        }
        try {
            shellDesc = shellDr.parse()
        } catch (e: Exception) {
            Log.d(TAG, "shell desc parse error")
            e.printStackTrace()
        }
    }

    override fun unload() {}

    override fun incrementCreateCount() {}
}
