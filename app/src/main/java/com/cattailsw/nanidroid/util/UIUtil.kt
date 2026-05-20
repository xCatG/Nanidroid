package com.cattailsw.nanidroid.util

import android.os.Build

object UIUtil {
    @JvmStatic
    fun isAfterEclair(): Boolean {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.ECLAIR_MR1
    }

    @JvmStatic
    fun isGingerbread(): Boolean {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.FROYO
    }
}
