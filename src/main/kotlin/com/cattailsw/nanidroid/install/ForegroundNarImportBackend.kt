package com.cattailsw.nanidroid.install

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import java.io.InputStream

/** Android boundary for privately staging and transactionally installing picker content. */
internal class AndroidForegroundNarImportBackend internal constructor(
    private val importRoot: File,
    private val ghostRoot: () -> File?,
    private val openContent: (String) -> InputStream?,
    private val install: (
        archive: File,
        installRoot: File,
        forcedId: String?,
        isCancelled: () -> Boolean,
        onProgress: (String, Long) -> Unit,
    ) -> ArchiveInstallResult = { archive, installRoot, forcedId, isCancelled, onProgress ->
        NarTransactionalInstaller.install(
            archive,
            installRoot,
            forcedId,
            isCancelled,
            onProgress,
        )
    },
    private val recoverImportStaging: (File) -> OwnedStagingRecoveryResult = ::recoverImportRoot,
    private val recoverInstallerStaging: (File) -> OwnedStagingRecoveryResult =
        NarTransactionalInstaller::recoverOwnedStaging,
    private val maximumArchiveBytes: Long = NarContentUriImport.MAX_ARCHIVE_BYTES,
) : ForegroundNarImportBackend {
    override fun importDocument(
        selection: NarDocumentSelection,
        isCancelled: () -> Boolean,
        onInstallingProgress: (phase: String, completed: Long) -> Unit,
    ): ArchiveInstallResult {
        val root = ghostRoot() ?: return ArchiveInstallResult.Failed(
            STORAGE_UNAVAILABLE_MESSAGE,
            ArchiveInstallFailure.StorageUnavailable,
        )
        return NarContentUriImport.importContent(
            scheme = selection.scheme,
            cacheDir = importRoot,
            open = { openContent(selection.uri) },
            install = { staged ->
                onInstallingProgress("Preparing installer", 0L)
                when (val result = install(staged, root, null, isCancelled, onInstallingProgress)) {
                    is ArchiveInstallResult.Installed -> if (result.targetId == null) {
                        result.copy(targetId = File(result.installedPath).name)
                    } else {
                        result
                    }
                    else -> result
                }
            },
            isCancelled = isCancelled,
            maximumArchiveBytes = maximumArchiveBytes,
        )
    }

    override fun recoverOwnedStaging(): NarImportRecoveryResult {
        val root = ghostRoot()
        val importResult = recoverImportStaging(importRoot).toNarImportRecoveryResult()
        val installerResult = root
            ?.let(recoverInstallerStaging)
            ?.toNarImportRecoveryResult()
            ?: NarImportRecoveryResult.Failed(STORAGE_UNAVAILABLE_MESSAGE)
        return combineRecovery(importResult, installerResult)
    }

    companion object {
        fun create(context: Context): AndroidForegroundNarImportBackend {
            val applicationContext = context.applicationContext
            return AndroidForegroundNarImportBackend(
                importRoot = File(applicationContext.noBackupFilesDir, "nar-import-v1"),
                ghostRoot = {
                    applicationContext.getExternalFilesDir(null)?.let { File(it, "ghost") }
                },
                openContent = { uri ->
                    applicationContext.contentResolver.openInputStream(uri.toUri())
                },
            )
        }

        private const val STORAGE_UNAVAILABLE_MESSAGE = "Nanidroid cannot access its ghost storage."
    }
}

private fun recoverImportRoot(root: File): OwnedStagingRecoveryResult =
    OwnedStagingRecovery.reconcile(
        root = root,
        expectedParent = root.parentFile,
        entryPattern = Regex("^nar-import-[0-9a-f]{24}\\.zip$"),
        entryKind = OwnedStagingEntryKind.REGULAR_FILE,
    )

private fun OwnedStagingRecoveryResult.toNarImportRecoveryResult(): NarImportRecoveryResult =
    when (this) {
        OwnedStagingRecoveryResult.Clean -> NarImportRecoveryResult.Clean
        OwnedStagingRecoveryResult.Cleaned -> NarImportRecoveryResult.Cleaned
        is OwnedStagingRecoveryResult.Failed -> NarImportRecoveryResult.Failed(message)
    }

private fun combineRecovery(
    importResult: NarImportRecoveryResult,
    installerResult: NarImportRecoveryResult,
): NarImportRecoveryResult = when {
    importResult is NarImportRecoveryResult.Failed -> importResult
    installerResult is NarImportRecoveryResult.Failed -> installerResult
    importResult == NarImportRecoveryResult.Cleaned || installerResult == NarImportRecoveryResult.Cleaned ->
        NarImportRecoveryResult.Cleaned
    else -> NarImportRecoveryResult.Clean
}
