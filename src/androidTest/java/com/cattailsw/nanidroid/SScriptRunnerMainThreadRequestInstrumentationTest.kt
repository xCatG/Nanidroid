package com.cattailsw.nanidroid

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.shiori.Shiori
import com.cattailsw.nanidroid.shiori.ShioriLoadResult
import com.cattailsw.nanidroid.shiori.ShioriUnloadResult
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
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
        val adapter = BlockingRequestShiori(
            eventId = "OnSecondChange",
            entered = requestEntered,
            release = releaseRequest,
            returned = responseReturned,
        )
        val root = File(context.cacheDir, "main-looper-request-runtime").canonicalFile
        val runtime = newRuntime(
            context,
            adapter,
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

    @Test
    fun blockedSurfaceResponsePausesItsPlaybackThenPlaysReturnedScriptInOrder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val responseReturned = CountDownLatch(1)
        val mainPulse = CountDownLatch(1)
        val returnedScriptPlayed = CountDownLatch(1)
        val frames = CopyOnWriteArrayList<String>()
        val adapter = BlockingRequestShiori(
            eventId = "OnSurfaceChange",
            entered = requestEntered,
            release = releaseRequest,
            returned = responseReturned,
            response = "SHIORI/3.0 200 OK\r\nValue: \\hReturned\\e\r\n\r\n",
        )
        val root = File(context.cacheDir, "surface-response-playback-runtime").canonicalFile
        val runtime = newRuntime(context, adapter)

        try {
            val handle = runBlocking {
                (runtime.startOrJoin("surface-response", root) as RuntimeResult.Success).value
            }
            runBlocking {
                assertTrue(runtime.attachHost(handle.generation) is RuntimeResult.Success)
            }
            runtime.runner.setPresentationRenderer { frame ->
                frames += frame.sakura.text
                if (frame.sakura.text == "Returned") returnedScriptPlayed.countDown()
            }

            Handler(Looper.getMainLooper()).post {
                runtime.runner.setNoWaitMode(true)
                runtime.runner.addMsgToQueue(arrayOf("\\hA\\s[1]B\\e"))
                runtime.runner.run()
            }
            assertTrue("Blocking surface request did not start", requestEntered.await(2, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post { mainPulse.countDown() }

            assertTrue(
                "Main looper could not run while the surface request was blocked",
                mainPulse.await(500, TimeUnit.MILLISECONDS),
            )
            assertTrue(
                "Initiating playback advanced past its pending surface response: $frames",
                frames.none { "B" in it },
            )

            releaseRequest.countDown()
            assertTrue(
                "Returned surface script was not played",
                returnedScriptPlayed.await(2, TimeUnit.SECONDS),
            )
            val initiatingTail = frames.indexOfFirst { it == "AB" }
            val returnedScript = frames.indexOfFirst { it == "Returned" }
            assertTrue(
                "Playback order was not initiating tail then returned script: $frames",
                initiatingTail >= 0 && returnedScript > initiatingTail,
            )
        } finally {
            releaseRequest.countDown()
            responseReturned.await(2, TimeUnit.SECONDS)
            runtime.close()
        }
    }

    @Test
    fun stoppedSurfaceRequestCannotReviveOrContaminateReplacementPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val responseReturned = CountDownLatch(1)
        val responseAdmitted = CountDownLatch(1)
        val replacementPlayed = CountDownLatch(1)
        val frames = CopyOnWriteArrayList<String>()
        val adapter = BlockingRequestShiori(
            eventId = "OnSurfaceChange",
            entered = requestEntered,
            release = releaseRequest,
            returned = responseReturned,
            response = "SHIORI/3.0 200 OK\r\nValue: \\hReturned\\e\r\n\r\n",
        )
        val root = File(context.cacheDir, "stopped-surface-response-runtime").canonicalFile
        val runtime = newRuntime(
            context,
            adapter,
            runnerConfiguration = SScriptRunnerConfiguration(
                playbackHooks = SScriptPlaybackHooks(
                    beforeRequestResponseAdmission = { responseAdmitted.countDown() },
                ),
            ),
        )

        try {
            val handle = runBlocking {
                (runtime.startOrJoin("stopped-surface", root) as RuntimeResult.Success).value
            }
            runBlocking {
                assertTrue(runtime.attachHost(handle.generation) is RuntimeResult.Success)
            }
            runtime.runner.setPresentationRenderer { frame ->
                frames += frame.sakura.text
                if (frame.sakura.text == "Replacement") replacementPlayed.countDown()
            }
            Handler(Looper.getMainLooper()).post {
                runtime.runner.setNoWaitMode(true)
                runtime.runner.addMsgToQueue(arrayOf("\\hA\\s[1]B\\e"))
                runtime.runner.run()
            }
            assertTrue("Blocking surface request did not start", requestEntered.await(2, TimeUnit.SECONDS))

            instrumentation.runOnMainSync {
                runtime.runner.stop()
                runtime.runner.addMsgToQueue(arrayOf("\\hReplacement\\e"))
                runtime.runner.run()
            }
            assertTrue("Replacement playback did not finish", replacementPlayed.await(2, TimeUnit.SECONDS))
            releaseRequest.countDown()
            assertTrue("Stopped response was not admitted", responseAdmitted.await(2, TimeUnit.SECONDS))

            assertTrue("Stopped response contaminated replacement playback: $frames", "Returned" !in frames)
            assertTrue("Replacement playback was lost: $frames", "Replacement" in frames)
        } finally {
            releaseRequest.countDown()
            responseReturned.await(2, TimeUnit.SECONDS)
            runtime.close()
        }
    }

    private fun newRuntime(
        context: android.content.Context,
        adapter: Shiori,
        runnerConfiguration: SScriptRunnerConfiguration? = null,
    ): GhostRuntime = GhostRuntime.testRuntime(
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
        runnerConfiguration = runnerConfiguration,
    )

    private class BlockingRequestShiori(
        private val eventId: String,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
        private val returned: CountDownLatch,
        private val response: String = "SHIORI/3.0 204 No Content\r\n\r\n",
    ) : Shiori {
        override fun getModuleName(): String = "BlockingRequest"

        override fun load(): ShioriLoadResult = ShioriLoadResult.Loaded

        override fun request(request: String): String {
            if ("ID: $eventId\r\n" in request) {
                entered.countDown()
                assertTrue("Timed out waiting to release blocked request", release.await(3, TimeUnit.SECONDS))
                returned.countDown()
                return response
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
