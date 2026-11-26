package com.cattailsw.nanidroid

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface NanoCallResult<out T> {
    data class Ready<T>(val value: T) : NanoCallResult<T>
    data object Downloading : NanoCallResult<Nothing>
    data object Unavailable : NanoCallResult<Nothing>
}

internal interface NanoModelHandle<out M : Any> : AutoCloseable {
    val model: M
    suspend fun checkStatus(): Int
    fun download(): Flow<DownloadStatus>
    suspend fun warmup()
}

internal class NanoFastClient<M : Any>(
    private val createHandle: () -> NanoModelHandle<M>,
    private val downloadScope: CoroutineScope,
    private val logInfo: (String) -> Unit = { Log.i(TAG, it) },
    private val logWarn: (String) -> Unit = { Log.w(TAG, it) }
) {
    private val mutex = Mutex()
    private val stateLock = Any()
    private var handle: NanoModelHandle<M>? = null
    private var warmedUp = false
    private var downloadJob: Job? = null

    suspend fun <T> withModel(run: suspend (M) -> T): NanoCallResult<T> {
        val readyHandle = mutex.withLock {
            val activeHandle = synchronized(stateLock) {
                handle ?: createHandle().also { handle = it }
            }
            when (val status = activeHandle.checkStatus()) {
                FeatureStatus.AVAILABLE -> {
                    val shouldWarmup = synchronized(stateLock) { handle === activeHandle && !warmedUp }
                    if (shouldWarmup) {
                        try {
                            activeHandle.warmup()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            logWarn("Gemini Nano 4 Fast warmup failed: ${error.javaClass.simpleName}")
                        }
                        synchronized(stateLock) {
                            if (handle === activeHandle) {
                                warmedUp = true
                            }
                        }
                    }
                    activeHandle
                }

                FeatureStatus.DOWNLOADABLE -> {
                    startDownloadIfNeeded(activeHandle)
                    return NanoCallResult.Downloading
                }

                FeatureStatus.DOWNLOADING -> {
                    return NanoCallResult.Downloading
                }

                else -> {
                    logInfo("Gemini Nano 4 Fast feature status: $status")
                    return NanoCallResult.Unavailable
                }
            }
        }
        return NanoCallResult.Ready(run(readyHandle.model))
    }

    fun close() {
        val activeDownload: Job?
        val currentHandle: NanoModelHandle<M>?
        synchronized(stateLock) {
            activeDownload = downloadJob
            downloadJob = null
            currentHandle = handle
            handle = null
            warmedUp = false
        }
        activeDownload?.cancel()
        if (currentHandle != null) {
            try {
                currentHandle.close()
            } catch (error: Exception) {
                logWarn("Gemini Nano 4 Fast close failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun startDownloadIfNeeded(activeHandle: NanoModelHandle<M>) {
        synchronized(stateLock) {
            if (handle !== activeHandle || downloadJob?.isActive == true) {
                return
            }
            val job = downloadScope.launch {
                collectDownload(activeHandle)
            }
            if (!job.isCompleted) {
                downloadJob = job
                job.invokeOnCompletion {
                    synchronized(stateLock) {
                        if (downloadJob === job) {
                            downloadJob = null
                        }
                    }
                }
            }
        }
    }

    private suspend fun collectDownload(activeHandle: NanoModelHandle<M>) {
        try {
            activeHandle.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> {
                        logInfo("Gemini Nano 4 Fast download started (${status.bytesToDownload} bytes)")
                    }

                    is DownloadStatus.DownloadProgress -> {
                        logInfo("Gemini Nano 4 Fast download progress: ${status.totalBytesDownloaded} bytes")
                    }

                    DownloadStatus.DownloadCompleted -> {
                        logInfo("Gemini Nano 4 Fast download completed")
                    }

                    is DownloadStatus.DownloadFailed -> {
                        logWarn(
                            "Gemini Nano 4 Fast download failed: " +
                                "${status.e.javaClass.simpleName} (${status.e.errorCode})"
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logWarn("Gemini Nano 4 Fast download threw: ${error.javaClass.simpleName}")
        }
    }

    private companion object {
        private const val TAG = "NanoFastClient"
    }
}

private class MlKitNanoModelHandle(
    override val model: GenerativeModel
) : NanoModelHandle<GenerativeModel> {
    override suspend fun checkStatus(): Int = model.checkStatus()

    override fun download(): Flow<DownloadStatus> = model.download()

    override suspend fun warmup() {
        model.warmup()
    }

    override fun close() {
        model.close()
    }
}

private val sharedNanoFastClient = NanoFastClient(
    createHandle = {
        MlKitNanoModelHandle(
            Generation.getClient(
                generationConfig {
                    modelConfig = modelConfig {
                        releaseStage = ModelReleaseStage.PREVIEW
                        preference = ModelPreference.FAST
                    }
                }
            )
        )
    },
    downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
)

internal suspend fun <T> withSharedNanoFastModel(run: suspend (GenerativeModel) -> T): NanoCallResult<T> =
    sharedNanoFastClient.withModel(run)

internal fun closeSharedNanoFastClient() {
    sharedNanoFastClient.close()
}
