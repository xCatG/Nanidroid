package com.cattailsw.nanidroid.util

import android.os.Build

/** SDK predicates retained as Java-callable static APIs for existing callers. */
object UIUtil {
    @JvmStatic
    fun isAfterEclair(): Boolean = Build.VERSION.SDK_INT > Build.VERSION_CODES.ECLAIR_MR1

    @JvmStatic
    fun isGingerbread(): Boolean = Build.VERSION.SDK_INT > Build.VERSION_CODES.FROYO
}
