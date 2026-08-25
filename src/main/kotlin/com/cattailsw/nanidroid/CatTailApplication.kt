package com.cattailsw.nanidroid

import android.app.Application
import com.cattailsw.nanidroid.install.ForegroundNarImportCoordinator

class CatTailApplication : Application() {
    internal val ghostRuntime: GhostRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GhostRuntime(this)
    }

    override fun onCreate() {
        super.onCreate()
        ghostRuntime
        ForegroundNarImportCoordinator.get(this)
    }
}
