package com.cattailsw.nanidroid

import android.app.Activity
import android.os.Build
import com.android.debug.hv.ViewServer

/** Keeps the legacy debug ViewServer outside modern Android activity lifecycles. */
object ViewServerLifecycle {
    interface Backend {
        fun addWindow(activity: Activity?)

        fun setFocusedWindow(activity: Activity?)

        fun removeWindow(activity: Activity?)
    }

    private val legacyBackend = object : Backend {
        override fun addWindow(activity: Activity?) {
            ViewServer.get(activity!!).addWindow(activity)
        }

        override fun setFocusedWindow(activity: Activity?) {
            ViewServer.get(activity!!).setFocusedWindow(activity)
        }

        override fun removeWindow(activity: Activity?) {
            ViewServer.get(activity!!).removeWindow(activity)
        }
    }

    @JvmStatic
    fun onActivityCreated(activity: Activity) {
        onActivityCreated(Build.VERSION.SDK_INT, activity, legacyBackend)
    }

    @JvmStatic
    fun onActivityResumed(activity: Activity) {
        onActivityResumed(Build.VERSION.SDK_INT, activity, legacyBackend)
    }

    @JvmStatic
    fun onActivityDestroyed(activity: Activity) {
        onActivityDestroyed(Build.VERSION.SDK_INT, activity, legacyBackend)
    }

    @JvmStatic
    fun onActivityCreated(sdkInt: Int, activity: Activity?, backend: Backend) {
        if (usesLegacyViewServer(sdkInt)) backend.addWindow(activity)
    }

    @JvmStatic
    fun onActivityResumed(sdkInt: Int, activity: Activity?, backend: Backend) {
        if (usesLegacyViewServer(sdkInt)) backend.setFocusedWindow(activity)
    }

    @JvmStatic
    fun onActivityDestroyed(sdkInt: Int, activity: Activity?, backend: Backend) {
        if (usesLegacyViewServer(sdkInt)) backend.removeWindow(activity)
    }

    private fun usesLegacyViewServer(sdkInt: Int): Boolean =
        sdkInt < Build.VERSION_CODES.HONEYCOMB
}
