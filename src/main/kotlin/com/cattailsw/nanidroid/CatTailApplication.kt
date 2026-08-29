package com.cattailsw.nanidroid

import android.app.Application
import com.cattailsw.nanidroid.install.ArchiveInstallResult
import com.cattailsw.nanidroid.install.ForegroundNarImportCoordinator
import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarImportAttemptToken
import com.cattailsw.nanidroid.install.NarImportPrimaryOutcome
import com.cattailsw.nanidroid.install.NarTransactionalInstaller
import com.cattailsw.nanidroid.runtime.CatalogPublicationToken
import com.cattailsw.nanidroid.runtime.RuntimeCatalogState
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeHostId
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class CatTailApplication : Application() {
    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1),
    )
    private val nextHostId = AtomicLong()
    private val nextBundledOperationId = AtomicLong()
    private val bundledInstallStarted = AtomicBoolean()
    private val publishedImports = mutableSetOf<NarImportAttemptToken>()

    internal val ghostRuntime: GhostRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GhostRuntime(this)
    }

    internal val foregroundNarImport: ForegroundNarImportCoordinator by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ForegroundNarImportCoordinator.get(this)
    }

    internal fun allocateRuntimeHostId(): RuntimeHostId = RuntimeHostId(nextHostId.incrementAndGet())

    override fun onCreate() {
        super.onCreate()
        ghostRuntime
        foregroundNarImport
        applicationScope.launch { observeForegroundImports() }
        applicationScope.launch { observeBundledInstallationEligibility() }
    }

    private suspend fun observeForegroundImports() {
        foregroundNarImport.state.collectLatest { state ->
            val installed = foregroundCatalogPublication(state) ?: return@collectLatest
            if (!publishedImports.add(installed.first)) return@collectLatest
            ghostRuntime.submit(
                RuntimeCommand.CatalogChanged(
                    foregroundPublicationToken(installed.first),
                    installed.second,
                ),
            )
        }
    }

    private suspend fun observeBundledInstallationEligibility() {
        ghostRuntime.snapshots.collect { snapshot ->
            val ready = snapshot.catalog as? RuntimeCatalogState.Ready ?: return@collect
            if (ready.entries.isNotEmpty()) return@collect
            val storageRoot = getExternalFilesDir(null)?.let { File(it, "ghost") }
                ?: return@collect
            if (!shouldInstallBundledGhost(0, storageRoot.listFiles().orEmpty())) return@collect
            if (!bundledInstallStarted.compareAndSet(false, true)) return@collect
            val operationId = nextBundledOperationId.incrementAndGet()
            val result = installBundledGhost(storageRoot)
            if (result is ArchiveInstallResult.Installed) {
                ghostRuntime.submit(
                    RuntimeCommand.CatalogChanged(
                        CatalogPublicationToken("bundled-install", operationId.toString()),
                        result.targetId ?: "nanidroid",
                    ),
                )
            }
        }
    }

    private fun installBundledGhost(storageRoot: File): ArchiveInstallResult {
        if ((!storageRoot.exists() && !storageRoot.mkdirs()) || !storageRoot.isDirectory) {
            return ArchiveInstallResult.Failed(
                "Nanidroid cannot prepare its ghost storage.",
                com.cattailsw.nanidroid.install.ArchiveInstallFailure.StorageUnavailable,
            )
        }
        val archive = File.createTempFile("nanidroid-", ".nar", cacheDir)
        return try {
            assets.open("nanidroid.zip").use { input -> archive.outputStream().use(input::copyTo) }
            NarTransactionalInstaller.install(archive, storageRoot, "nanidroid") { false }
        } finally {
            archive.delete()
        }
    }

}

internal fun foregroundCatalogPublication(
    state: ForegroundNarImportState,
): Pair<NarImportAttemptToken, String>? = when (state) {
    is ForegroundNarImportState.Installed -> state.token to state.targetId
    is ForegroundNarImportState.RecoveryRequired -> {
        val installed = state.primary as? NarImportPrimaryOutcome.Installed
        installed?.let { state.token to it.targetId }
    }
    else -> null
}

internal fun foregroundPublicationToken(token: NarImportAttemptToken) = CatalogPublicationToken(
    source = "foreground-import",
    value = "${token.processNonce}:${token.sequence}:${token.ownerTaskId}",
)
