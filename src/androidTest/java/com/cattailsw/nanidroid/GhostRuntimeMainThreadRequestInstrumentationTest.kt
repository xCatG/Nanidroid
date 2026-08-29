package com.cattailsw.nanidroid

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.runtime.ApplicationRuntimeScheduler
import com.cattailsw.nanidroid.runtime.RuntimeCatalogScanner
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeNativeLifecycleOutcome
import com.cattailsw.nanidroid.runtime.RuntimeNativeLoadOutcome
import com.cattailsw.nanidroid.runtime.RuntimeNativePort
import com.cattailsw.nanidroid.runtime.RuntimeRequestToken
import java.io.File
import java.util.Hashtable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostRuntimeMainThreadRequestInstrumentationTest {
    @Test
    fun mainThreadSubmissionReturnsBeforeNativeRequestAndRequestRunsOffMainLooper() {
        val requestThread = AtomicReference<Thread>()
        val requested = CountDownLatch(1)
        val root = File("main-thread").absoluteFile
        val runtime = GhostRuntime.testRuntime(
            context = null,
            preparer = GhostPreparer { operationId, id, root -> prepared(operationId, id, root) },
            persistence = NoOpPersistence,
            nativePort = object : RuntimeNativePort {
                override fun load(
                    operationId: Long,
                    generation: Long,
                    prepared: PreparedGhost,
                    complete: (RuntimeNativeLoadOutcome) -> Unit,
                ) = complete(
                    RuntimeNativeLoadOutcome.Loaded(
                        com.cattailsw.nanidroid.runtime.dialogue.PointerEventCapabilities(),
                    ),
                )

                override fun request(
                    token: RuntimeRequestToken,
                    intent: ShioriRequestIntent,
                    fallback: ShioriRequestIntent?,
                    complete: (RuntimeResult<TaggedShioriResponse>) -> Unit,
                ) {
                    requestThread.set(Thread.currentThread())
                    requested.countDown()
                    complete(
                        RuntimeResult.Success(
                            TaggedShioriResponse(
                                token.generation,
                                ShioriResponse("SHIORI/3.0 204 No Content", Hashtable()),
                            ),
                        ),
                    )
                }

                override fun unload(
                    operationId: Long,
                    generation: Long,
                    complete: (RuntimeNativeLifecycleOutcome) -> Unit,
                ) = complete(RuntimeNativeLifecycleOutcome.Success)
            },
            runtimeScheduler = ApplicationRuntimeScheduler(),
            catalogScanner = RuntimeCatalogScanner {
                listOf(InstalledGhostMetadata("main-thread", root, null, null, File(root, "readme.txt")))
            },
            canonicalizeRoot = File::getAbsoluteFile,
        )
        try {
            val returned = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                runtime.submit(RuntimeCommand.StartGhost("main-thread", root))
                returned.countDown()
            }

            assertTrue(returned.await(1L, TimeUnit.SECONDS))
            assertTrue(requested.await(5L, TimeUnit.SECONDS))
            assertNotEquals(Looper.getMainLooper().thread, requestThread.get())
        } finally {
            runtime.close()
        }
    }

    private fun prepared(operationId: Long, id: String, root: File) = PreparedGhost(
        operationId = operationId,
        id = id,
        canonicalRoot = root.absoluteFile,
        name = id,
        shellName = "master",
        crafterName = null,
        sakuraName = null,
        keroName = null,
        surfaces = SurfaceCatalog.freeze(emptyMap()),
        ghostDescriptor = emptyMap(),
        shellDescriptor = null,
        engine = GhostEngine.Unsupported,
        nanidroidContent = emptyMap(),
    )

    private object NoOpPersistence : GhostRuntimePersistence {
        override fun readLastRunGhostId(): String? = null
        override fun commitLastRunGhostId(ghostId: String) = Unit
        override fun readActivationCount(ghostId: String): Long = 0L
        override fun commitActivationCount(ghostId: String, count: Long) = Unit
    }
}
