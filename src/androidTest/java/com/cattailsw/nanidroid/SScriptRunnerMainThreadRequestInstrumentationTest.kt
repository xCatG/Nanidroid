package com.cattailsw.nanidroid

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.shiori.Shiori
import com.cattailsw.nanidroid.shiori.ShioriLoadResult
import com.cattailsw.nanidroid.shiori.ShioriUnloadResult
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SScriptRunnerMainThreadRequestInstrumentationTest {
    @Test
    fun blockedTimerRequestDoesNotBlockMainLooperAndAdmitsOnMain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val responseReturned = CountDownLatch(1)
        val responseAdmitted = CountDownLatch(1)
        val admissionThread = AtomicReference<Thread?>()
        val mainPulse = CountDownLatch(1)
        val adapter = BlockingRequestShiori(requestEntered, releaseRequest, responseReturned)
        val root = File(context.cacheDir, "main-looper-request-runtime").canonicalFile
        val runtime = GhostRuntime.testRuntime(
            context = context,
            preparer = GhostPreparer { operationId, ghostId, canonicalRoot ->
                PreparedGhost(
                    operationId = operationId,
                    id = ghostId,
                    canonicalRoot = canonicalRoot,
                    name = ghostId,
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
            },
            adapterFactory = { adapter },
            persistence = InstrumentationPersistence(),
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackHooks = SScriptPlaybackHooks(
                    beforeRequestResponseAdmission = {
                        admissionThread.set(Thread.currentThread())
                        responseAdmitted.countDown()
                    },
                ),
            ),
        )

        try {
            val handle = runBlocking {
                (runtime.startOrJoin("main-looper", root) as RuntimeResult.Success).value
            }
            runBlocking {
                assertTrue(runtime.attachHost(handle.generation) is RuntimeResult.Success)
            }

            Handler(Looper.getMainLooper()).post {
                runtime.runner.dispatchClockTickForTesting()
            }
            assertTrue("Blocking adapter request did not start", requestEntered.await(2, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post { mainPulse.countDown() }

            assertTrue(
                "Main looper could not run a queued pulse while SHIORI was blocked",
                mainPulse.await(500, TimeUnit.MILLISECONDS),
            )
            releaseRequest.countDown()
            assertTrue("SHIORI response was not admitted", responseAdmitted.await(2, TimeUnit.SECONDS))
            assertTrue(
                "SHIORI response was not admitted on the main looper",
                admissionThread.get() === Looper.getMainLooper().thread,
            )
        } finally {
            releaseRequest.countDown()
            responseReturned.await(2, TimeUnit.SECONDS)
            runtime.close()
        }
    }

    private class BlockingRequestShiori(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
        private val returned: CountDownLatch,
    ) : Shiori {
        override fun getModuleName(): String = "BlockingRequest"

        override fun load(): ShioriLoadResult = ShioriLoadResult.Loaded

        override fun request(request: String): String {
            if ("ID: OnSecondChange\r\n" in request) {
                entered.countDown()
                assertTrue("Timed out waiting to release blocked request", release.await(3, TimeUnit.SECONDS))
                returned.countDown()
            }
            return "SHIORI/3.0 204 No Content\r\n\r\n"
        }

        override fun unloadShiori(): ShioriUnloadResult = ShioriUnloadResult.Unloaded
    }

    private class InstrumentationPersistence : GhostRuntimePersistence {
        override fun readLastRunGhostId(): String? = null
        override fun commitLastRunGhostId(ghostId: String) = Unit
        override fun readActivationCount(ghostId: String): Long = 1L
        override fun commitActivationCount(ghostId: String, count: Long) = Unit
    }
}
