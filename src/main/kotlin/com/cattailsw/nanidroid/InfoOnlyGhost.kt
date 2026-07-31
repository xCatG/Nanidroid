package com.cattailsw.nanidroid

import android.util.Log

/** A ghost used only to read metadata while discovering installed ghosts. */
class InfoOnlyGhost(path: String) : Ghost(path) {
    override fun loadGhostInfo() {
        val masterGhost = "$rootPath/ghost/master"
        val ghostReader = DescReader("$masterGhost/descript.txt")
        val masterShell = "$rootPath/shell/master"
        val shellReader = DescReader("$masterShell/descript.txt")

        try {
            ghostDesc = ghostReader.parse()
        } catch (exception: Exception) {
            Log.d(TAG, "desc parsing error")
            exception.printStackTrace()
            error = true
        }
        try {
            shellDesc = shellReader.parse()
        } catch (exception: Exception) {
            Log.d(TAG, "shell desc parse error")
            exception.printStackTrace()
        }
    }

    override fun unload() = Unit

    override fun incrementCreateCount() = Unit

    private companion object {
        const val TAG = "InfoOnlyGhost"
    }
}
