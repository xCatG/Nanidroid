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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface NanoCallResult<out T> {
    data class Ready<T>(val value: T) : NanoCallResult<T>
    data class Downloading(val percent: Int? = null, val startedNow: Boolean = false) : NanoCallResult<Nothing>
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
    private val createFallbackHandle: (() -> NanoModelHandle<M>)? = null,
    private val logInfo: (String) -> Unit = { Log.i(TAG, it) },
    private val logWarn: (String) -> Unit = { Log.w(TAG, it) }
) {
    private val mutex = Mutex()
    private val stateLock = Any()
    private var handle: NanoModelHandle<M>? = null
    private var usingFallback = false
    private var warmedUp = false
    private var downloadJob: Job? = null
    private val _downloadState = MutableStateFlow<NanoDownloadState>(NanoDownloadState.Idle)
    val downloadState: StateFlow<NanoDownloadState> = _downloadState.asStateFlow()

    suspend fun <T> withModel(run: suspend (M) -> T): NanoCallResult<T> {
        val readyHandle = mutex.withLock {
            val (activeHandle, status) = resolveActiveHandle()
            val modelLabel = modelLabelFor(activeHandle)
            when (status) {
                FeatureStatus.AVAILABLE -> {
                    writeDownloadState(activeHandle, NanoDownloadState.Ready(modelLabel))
                    warmupIfNeeded(activeHandle, modelLabel)
                    activeHandle
                }

                FeatureStatus.DOWNLOADABLE -> {
                    val (startedNow, percent) = startDownloadIfNeeded(activeHandle, modelLabel)
                    return NanoCallResult.Downloading(percent = percent, startedNow = startedNow)
                }

                FeatureStatus.DOWNLOADING -> {
                    val percent = currentDownloadingPercent(activeHandle, modelLabel)
                    writeDownloadState(activeHandle, NanoDownloadState.Downloading(percent, modelLabel))
                    return NanoCallResult.Downloading(percent = percent, startedNow = false)
                }

                else -> {
                    logInfo("$modelLabel feature status: $status")
                    writeDownloadState(activeHandle, NanoDownloadState.Unavailable)
                    return NanoCallResult.Unavailable
                }
            }
        }
        return NanoCallResult.Ready(run(readyHandle.model))
    }

    suspend fun refreshStatus() {
        mutex.withLock {
            val (activeHandle, status) = resolveActiveHandle()
            val modelLabel = modelLabelFor(activeHandle)
            when (status) {
                FeatureStatus.AVAILABLE -> {
                    writeDownloadState(activeHandle, NanoDownloadState.Ready(modelLabel))
                }

                FeatureStatus.DOWNLOADABLE -> {
                    val keepExisting = synchronized(stateLock) {
                        handle === activeHandle &&
                            (downloadJob?.isActive == true || _downloadState.value is NanoDownloadState.Failed)
                    }
                    if (!keepExisting) {
                        writeDownloadState(activeHandle, NanoDownloadState.Idle)
                    }
                }

                FeatureStatus.DOWNLOADING -> {
                    val percent = currentDownloadingPercent(activeHandle, modelLabel)
                    writeDownloadState(activeHandle, NanoDownloadState.Downloading(percent, modelLabel))
                }

                else -> {
                    writeDownloadState(activeHandle, NanoDownloadState.Unavailable)
                }
            }
        }
    }

    fun close() {
        val activeDownload: Job?
        val currentHandle: NanoModelHandle<M>?
        synchronized(stateLock) {
            activeDownload = downloadJob
            downloadJob = null
            currentHandle = handle
            handle = null
            usingFallback = false
            warmedUp = false
            _downloadState.value = NanoDownloadState.Idle
        }
        activeDownload?.cancel()
        if (currentHandle != null) {
            closeHandleQuietly(currentHandle)
        }
    }

    private suspend fun resolveActiveHandle(): Pair<NanoModelHandle<M>, Int> {
        var activeHandle = synchronized(stateLock) {
            handle ?: createHandle().also { handle = it }
        }
        var status = activeHandle.checkStatus()
        if (status == FeatureStatus.UNAVAILABLE && createFallbackHandle != null) {
            val fallback = synchronized(stateLock) {
                if (handle === activeHandle && !usingFallback) {
                    usingFallback = true
                    warmedUp = false
                    createFallbackHandle.invoke().also { handle = it }
                } else {
                    null
                }
            }
            if (fallback != null) {
                logInfo("$PRIMARY_MODEL_LABEL unavailable; falling back to default $FALLBACK_MODEL_LABEL")
                closeHandleQuietly(activeHandle)
                activeHandle = fallback
                status = activeHandle.checkStatus()
            }
        }
        return activeHandle to status
    }

    private suspend fun warmupIfNeeded(activeHandle: NanoModelHandle<M>, modelLabel: String) {
        val shouldWarmup = synchronized(stateLock) { handle === activeHandle && !warmedUp }
        if (!shouldWarmup) return
        try {
            activeHandle.warmup()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logWarn("$modelLabel warmup failed: ${error.javaClass.simpleName}")
        }
        synchronized(stateLock) {
            if (handle === activeHandle) {
                warmedUp = true
            }
        }
    }

    private fun modelLabelFor(targetHandle: NanoModelHandle<M>): String =
        synchronized(stateLock) {
            if (handle === targetHandle && usingFallback) FALLBACK_MODEL_LABEL else PRIMARY_MODEL_LABEL
        }

    private fun writeDownloadState(expectedHandle: NanoModelHandle<M>, state: NanoDownloadState) {
        synchronized(stateLock) {
            if (handle === expectedHandle) {
                _downloadState.value = state
            }
        }
    }

    private fun currentDownloadingPercent(expectedHandle: NanoModelHandle<M>, modelLabel: String): Int? =
        synchronized(stateLock) {
            if (handle !== expectedHandle) return null
            (_downloadState.value as? NanoDownloadState.Downloading)
                ?.takeIf { it.modelLabel == modelLabel }
                ?.percent
        }

    private fun closeHandleQuietly(target: NanoModelHandle<M>) {
        try {
            target.close()
        } catch (error: Exception) {
            logWarn("Gemini Nano close failed: ${error.javaClass.simpleName}")
        }
    }

    private fun startDownloadIfNeeded(
        activeHandle: NanoModelHandle<M>,
        modelLabel: String
    ): Pair<Boolean, Int?> {
        synchronized(stateLock) {
            if (handle !== activeHandle) return false to null
            if (downloadJob?.isActive == true) {
                val percent = (_downloadState.value as? NanoDownloadState.Downloading)
                    ?.takeIf { it.modelLabel == modelLabel }
                    ?.percent
                return false to percent
            }
            _downloadState.value = NanoDownloadState.Downloading(percent = null, modelLabel = modelLabel)
            val job = downloadScope.launch {
                collectDownload(activeHandle, modelLabel)
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
            return true to null
        }
    }

    private suspend fun collectDownload(activeHandle: NanoModelHandle<M>, modelLabel: String) {
        var totalBytesToDownload = -1L
        var lastPercent: Int? = null
        try {
            activeHandle.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> {
                        totalBytesToDownload = status.bytesToDownload
                        lastPercent = null
                        logInfo("$modelLabel download started (${status.bytesToDownload} bytes)")
                        writeDownloadState(activeHandle, NanoDownloadState.Downloading(null, modelLabel))
                    }

                    is DownloadStatus.DownloadProgress -> {
                        val percent = if (totalBytesToDownload > 0L) {
                            ((status.totalBytesDownloaded * 100L) / totalBytesToDownload).toInt().coerceIn(0, 100)
                        } else {
                            null
                        }
                        if (percent != lastPercent) {
                            lastPercent = percent
                            if (percent != null) {
                                logInfo("$modelLabel download progress: $percent%")
                            }
                            writeDownloadState(activeHandle, NanoDownloadState.Downloading(percent, modelLabel))
                        }
                    }

                    DownloadStatus.DownloadCompleted -> {
                        logInfo("$modelLabel download completed")
                        writeDownloadState(activeHandle, NanoDownloadState.Ready(modelLabel))
                        downloadScope.launch {
                            mutex.withLock {
                                val stillActive = synchronized(stateLock) { handle === activeHandle }
                                if (stillActive) {
                                    warmupIfNeeded(activeHandle, modelLabel)
                                }
                            }
                        }
                    }

                    is DownloadStatus.DownloadFailed -> {
                        val reason = "${status.e.javaClass.simpleName} (${status.e.errorCode})"
                        logWarn("$modelLabel download failed: $reason")
                        synchronized(stateLock) {
                            if (handle === activeHandle) {
                                downloadJob = null
                                _downloadState.value = NanoDownloadState.Failed(reason, modelLabel)
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val reason = error.javaClass.simpleName
            logWarn("$modelLabel download threw: $reason")
            synchronized(stateLock) {
                if (handle === activeHandle) {
                    downloadJob = null
                    _downloadState.value = NanoDownloadState.Failed(reason, modelLabel)
                }
            }
        }
    }

    private companion object {
        private const val TAG = "NanoFastClient"
        private const val PRIMARY_MODEL_LABEL = "Gemini Nano 4 Fast"
        private const val FALLBACK_MODEL_LABEL = "Gemini Nano"
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

private val sharedNanoDownloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    downloadScope = sharedNanoDownloadScope,
    createFallbackHandle = { MlKitNanoModelHandle(Generation.getClient()) }
)

internal suspend fun <T> withSharedNanoFastModel(run: suspend (GenerativeModel) -> T): NanoCallResult<T> =
    sharedNanoFastClient.withModel(run)

internal fun sharedNanoDownloadState(): StateFlow<NanoDownloadState> = sharedNanoFastClient.downloadState

internal fun refreshSharedNanoStatus() {
    sharedNanoDownloadScope.launch {
        sharedNanoFastClient.refreshStatus()
    }
}

internal fun closeSharedNanoFastClient() {
    sharedNanoFastClient.close()
}
