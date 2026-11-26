package com.cattailsw.nanidroid

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NanoFastClientTest {
    @Test
    fun availableReusesSingleHandleWarmsUpOnceAndReturnsReady() = runTest {
        val handles = mutableListOf<FakeNanoModelHandle>()
        val client = NanoFastClient(
            createHandle = {
                FakeNanoModelHandle(model = "model-${handles.size + 1}", status = FeatureStatus.AVAILABLE)
                    .also(handles::add)
            },
            downloadScope = backgroundScope,
            logInfo = {},
            logWarn = {}
        )

        val first = client.withModel { "$it-first" }
        val second = client.withModel { "$it-second" }

        assertEquals(NanoCallResult.Ready("model-1-first"), first)
        assertEquals(NanoCallResult.Ready("model-1-second"), second)
        assertEquals(1, handles.size)
        assertEquals(2, handles.single().checkStatusCalls)
        assertEquals(1, handles.single().warmupCalls)
    }

    @Test
    fun warmupFailureIsLoggedAndDoesNotBlockInference() = runTest {
        val warnings = mutableListOf<String>()
        val handle = FakeNanoModelHandle(
            model = "model",
            status = FeatureStatus.AVAILABLE,
            warmupError = IllegalStateException("warmup failed")
        )
        val client = NanoFastClient(
            createHandle = { handle },
            downloadScope = backgroundScope,
            logInfo = {},
            logWarn = warnings::add
        )

        val result = client.withModel { "$it-ok" }

        assertEquals(NanoCallResult.Ready("model-ok"), result)
        assertEquals(1, handle.warmupCalls)
        assertEquals(1, warnings.size)
    }

    @Test
    fun downloadableStartsSingleDeduplicatedBackgroundDownloadAndReturnsImmediately() = runTest {
        val infos = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val handle = FakeNanoModelHandle(
            model = "model",
            status = FeatureStatus.DOWNLOADABLE,
            downloadFlowFactory = {
                flow {
                    emit(DownloadStatus.DownloadStarted(100L))
                    gate.await()
                    emit(DownloadStatus.DownloadProgress(50L))
                    emit(DownloadStatus.DownloadCompleted)
                    emit(
                        DownloadStatus.DownloadFailed(
                            GenAiException("failed", null, GenAiException.ErrorCode.UNKNOWN)
                        )
                    )
                }
            }
        )
        val client = NanoFastClient(
            createHandle = { handle },
            downloadScope = backgroundScope,
            logInfo = infos::add,
            logWarn = warnings::add
        )

        val first = client.withModel { error("should not run while downloadable") }
        runCurrent()
        val second = client.withModel { error("should not run while downloading") }

        assertEquals(NanoCallResult.Downloading, first)
        assertEquals(NanoCallResult.Downloading, second)
        assertEquals(1, handle.downloadCalls)

        gate.complete(Unit)
        runCurrent()

        assertTrue(infos.any { it.contains("started") })
        assertTrue(infos.any { it.contains("progress") })
        assertTrue(infos.any { it.contains("completed") })
        assertEquals(1, warnings.size)
    }

    @Test
    fun downloadingReturnsDownloadingWithoutStartingDuplicateDownload() = runTest {
        val handle = FakeNanoModelHandle(model = "model", status = FeatureStatus.DOWNLOADING)
        val client = NanoFastClient(
            createHandle = { handle },
            downloadScope = backgroundScope,
            logInfo = {},
            logWarn = {}
        )

        val result = client.withModel { error("should not run while downloading") }

        assertEquals(NanoCallResult.Downloading, result)
        assertEquals(0, handle.downloadCalls)
        assertEquals(0, handle.warmupCalls)
    }

    @Test
    fun unavailableReturnsUnavailable() = runTest {
        val handle = FakeNanoModelHandle(model = "model", status = FeatureStatus.UNAVAILABLE)
        val client = NanoFastClient(
            createHandle = { handle },
            downloadScope = backgroundScope,
            logInfo = {},
            logWarn = {}
        )

        val result = client.withModel { error("should not run when unavailable") }

        assertEquals(NanoCallResult.Unavailable, result)
        assertEquals(0, handle.warmupCalls)
        assertEquals(0, handle.downloadCalls)
    }

    @Test
    fun unavailablePrimaryFallsBackToDefaultHandleAndClosesPrimary() = runTest {
        val primary = FakeNanoModelHandle(model = "preview-fast", status = FeatureStatus.UNAVAILABLE)
        val fallback = FakeNanoModelHandle(model = "default-nano", status = FeatureStatus.AVAILABLE)
        val client = NanoFastClient(
            createHandle = { primary },
            downloadScope = backgroundScope,
            createFallbackHandle = { fallback },
            logInfo = {},
            logWarn = {}
        )

        val first = client.withModel { "$it-1" }
        val second = client.withModel { "$it-2" }

        assertEquals(NanoCallResult.Ready("default-nano-1"), first)
        assertEquals(NanoCallResult.Ready("default-nano-2"), second)
        assertTrue(primary.closed)
        assertEquals(1, primary.checkStatusCalls)
        assertEquals(2, fallback.checkStatusCalls)
        assertEquals(1, fallback.warmupCalls)
    }

    @Test
    fun closeClosesHandleCancelsDownloadAndResetsForFreshHandle() = runTest {
        val handles = mutableListOf<FakeNanoModelHandle>()
        val downloadGate = CompletableDeferred<Unit>()
        var downloadCancelled = false
        val client = NanoFastClient(
            createHandle = {
                val index = handles.size + 1
                if (index == 1) {
                    FakeNanoModelHandle(
                        model = "model-1",
                        status = FeatureStatus.DOWNLOADABLE,
                        downloadFlowFactory = {
                            flow {
                                try {
                                    downloadGate.await()
                                } finally {
                                    downloadCancelled = true
                                }
                            }
                        }
                    )
                } else {
                    FakeNanoModelHandle(model = "model-$index", status = FeatureStatus.AVAILABLE)
                }.also(handles::add)
            },
            downloadScope = backgroundScope,
            logInfo = {},
            logWarn = {}
        )

        assertEquals(NanoCallResult.Downloading, client.withModel { it })
        runCurrent()
        assertFalse(downloadCancelled)

        client.close()
        runCurrent()

        assertTrue(handles[0].closed)
        assertTrue(downloadCancelled)

        val afterClose = client.withModel { "$it-recreated" }
        assertEquals(NanoCallResult.Ready("model-2-recreated"), afterClose)
        assertEquals(2, handles.size)
        assertEquals(1, handles[1].warmupCalls)
    }

    private class FakeNanoModelHandle(
        override val model: String,
        private val status: Int,
        private val warmupError: Exception? = null,
        private val downloadFlowFactory: () -> Flow<DownloadStatus> = { emptyFlow() }
    ) : NanoModelHandle<String> {
        var checkStatusCalls = 0
        var warmupCalls = 0
        var downloadCalls = 0
        var closed = false

        override suspend fun checkStatus(): Int {
            checkStatusCalls++
            return status
        }

        override fun download(): Flow<DownloadStatus> {
            downloadCalls++
            return downloadFlowFactory()
        }

        override suspend fun warmup() {
            warmupCalls++
            warmupError?.let { throw it }
        }

        override fun close() {
            closed = true
        }
    }
}
