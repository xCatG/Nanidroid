package com.cattailsw.nanidroid

import android.app.Application
import android.util.Log
import com.cattailsw.nanidroid.data.PreferencesBootStateStore
import com.cattailsw.nanidroid.data.PreferencesLastGhostStore
import com.cattailsw.nanidroid.engine.BuiltInShiori
import com.cattailsw.nanidroid.engine.NativeShioriHost
import com.cattailsw.nanidroid.ghost.BundledGhostRepository
import com.cattailsw.nanidroid.ghost.InstalledGhostRepository
import com.cattailsw.nanidroid.install.GhostImporter
import com.cattailsw.nanidroid.install.ImportCoordinator
import com.cattailsw.nanidroid.install.ImportAttemptStore
import com.cattailsw.nanidroid.runtime.GhostRuntime
import com.cattailsw.nanidroid.runtime.HostNativePort
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NanidroidApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val ghostImporter: GhostImporter by lazy { GhostImporter(filesDir) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            try {
                ghostImporter.recoverAbandonedAttempts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("NanidroidApplication", "Unable to recover abandoned ghost imports", e)
            }
        }
    }

    val importCoordinator: ImportCoordinator by lazy {
        ImportCoordinator(ghostImporter, runtime, applicationScope)
    }
    val importAttemptStore: ImportAttemptStore by lazy {
        ImportAttemptStore.fromPreferences(getSharedPreferences("import_attempt", MODE_PRIVATE))
    }

    val runtime: GhostRuntime by lazy {
        val repository = BundledGhostRepository(
            openAsset = { assets.open("nanidroid.zip") },
            ghostRoot = File(filesDir, "ghost"),
        )
        GhostRuntime(
            loadGhost = {
                withContext(Dispatchers.IO) {
                    repository.load(repository.ensureInstalled(), resources.configuration.locales[0].language)
                }
            },
            engineFactory = { BuiltInShiori(it.content) },
            bootState = PreferencesBootStateStore(getSharedPreferences("boot_state", MODE_PRIVATE)),
            scope = applicationScope,
            installedGhosts = InstalledGhostRepository(File(filesDir, "ghost")),
            lastGhostStore = PreferencesLastGhostStore(getSharedPreferences("last_ghost", MODE_PRIVATE)),
            nativeHost = HostNativePort(NativeShioriHost.process),
        )
    }
}
