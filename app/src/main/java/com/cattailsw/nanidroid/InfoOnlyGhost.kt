package com.cattailsw.nanidroid

import android.util.Log

class InfoOnlyGhost(path: String) : Ghost(path, null, infoOnly = true) {
    companion object {
        private const val TAG = "InfoOnlyGhost"
    }

    override fun unload() {}
}
