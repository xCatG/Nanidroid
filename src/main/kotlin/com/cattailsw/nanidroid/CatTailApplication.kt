package com.cattailsw.nanidroid

import android.app.Application
import com.cattailsw.nanidroid.install.ForegroundNarImportCoordinator

class CatTailApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ForegroundNarImportCoordinator.get(this)
    }
}
