package com.cattailsw.nanidroid.install

import android.content.Context
import android.net.Uri
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.Executor

/** Acquires a temporary-grant stream synchronously, then stages it on a background executor. */
internal class NarLiveGrantHandoff(
    private val repository: NarDownloadRepository,
    private val executor: Executor,
    private val stage: (
        source: InputStream,
        isCancelled: () -> Boolean,
        onProgress: (phase: String, completed: Long) -> Unit,
    ) -> NarLocalArchiveStager.Result,
) {
    constructor(
        repository: NarDownloadRepository,
        executor: Executor,
        privateDirectory: File,
    ) : this(
        repository,
        executor,
        { source, isCancelled, onProgress ->
            NarLocalArchiveStager.stage(
                directory = privateDirectory,
                open = { source },
                isCancelled = isCancelled,
                onProgress = { completed -> onProgress("Copying archive", completed) },
            )
        },
    )

    fun enqueue(
        uri: String,
        replacementId: String?,
        open: () -> InputStream?,
    ): NarDownload? {
        val source = try {
            open()
        } catch (_: Exception) {
            null
        } ?: return null
        val item = if (replacementId == null) {
            repository.enqueueLiveLocalCopy(uri)
        } else {
            repository.replaceLocalSourceForLiveCopy(replacementId, uri)
        }
        if (item == null) {
            runCatching { source.close() }
            return null
        }
        try {
            executor.execute {
                try {
                    repository.stageLiveLocal(
                        item.id,
                        item.attemptId,
                        { Thread.currentThread().isInterrupted },
                    ) { _, isCancelled, onProgress ->
                        stage(BorrowedInputStream(source), isCancelled, onProgress)
                    }
                } finally {
                    runCatching { source.close() }
                }
            }
        } catch (_: RuntimeException) {
            runCatching { source.close() }
            repository.abandonLiveLocalCopy(item.id, item.attemptId)
            repository.stop(item.id)
        }
        return item
    }

    private class BorrowedInputStream(source: InputStream) : FilterInputStream(source) {
        override fun close() = Unit
    }
}

/** Copies one retained document URI into app-owned storage before installation. */
class StageLocalNarWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val itemId = inputData.getString(INPUT_ITEM_ID) ?: return Result.success()
        val attemptId = inputData.getLong(INPUT_ATTEMPT_ID, NO_ATTEMPT)
        if (attemptId == NO_ATTEMPT) return Result.success()
        val repository = NarDownloadRepository.get(applicationContext)
        val accepted = repository.stageLocal(
            itemId,
            attemptId,
            { isStopped },
        ) { download, isCancelled, onProgress ->
            val location = download.retainedUri ?: (download.source as? NarDownloadSource.Local)?.uri
                ?: return@stageLocal NarLocalArchiveStager.Result.Failed(
                    "The selected document is no longer available.",
                )
            stageGrantedSource(applicationContext, location, isCancelled) { completed ->
                onProgress("Copying archive", completed)
            }
        }
        return if (accepted) Result.success() else Result.retry()
    }

    override fun onStopped() {
        val itemId = inputData.getString(INPUT_ITEM_ID)
        val attemptId = inputData.getLong(INPUT_ATTEMPT_ID, NO_ATTEMPT)
        if (itemId != null && attemptId != NO_ATTEMPT) {
            NarDownloadRepository.get(applicationContext).workerStopped(itemId, attemptId)
        }
        super.onStopped()
    }

    companion object {
        internal const val INPUT_ITEM_ID = "nar-local-stage-item-id"
        internal const val INPUT_ATTEMPT_ID = "nar-local-stage-attempt-id"
        private const val NO_ATTEMPT = -1L
        private const val LOCAL_IMPORT_DIRECTORY = "nar-local-imports"

        internal fun stageGrantedSource(
            context: Context,
            location: String,
            isCancelled: () -> Boolean = { false },
            onProgress: (completed: Long) -> Unit = { },
        ): NarLocalArchiveStager.Result = NarLocalArchiveStager.stage(
            directory = localImportDirectory(context),
            open = { open(context, location) },
            isCancelled = isCancelled,
            onProgress = onProgress,
        )

        internal fun localImportDirectory(context: Context) =
            File(context.applicationContext.filesDir, LOCAL_IMPORT_DIRECTORY)

        private fun open(context: Context, location: String) =
            when (URI(location).scheme?.lowercase()) {
                "content" -> context.contentResolver.openInputStream(Uri.parse(location))
                "file" -> FileInputStream(File(URI(location)))
                null -> FileInputStream(File(location))
                else -> null
            }
    }
}
