package com.cattailsw.nanidroid

import android.os.Handler
import android.os.Looper
import androidx.compose.ui.unit.IntOffset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.shiori.Shiori
import com.cattailsw.nanidroid.shiori.ShioriLoadResult
import com.cattailsw.nanidroid.shiori.ShioriUnloadResult
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SScriptRunnerMainThreadRequestInstrumentationTest {
    @Test
    fun exitRejectsBlockedPreExitChoiceAndPlaysCloseExactlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val choiceEntered = CountDownLatch(1)
        val releaseChoice = CountDownLatch(1)
        val closePlayed = CountDownLatch(1)
        val exitDelivered = CountDownLatch(1)
        val staleInputShown = CountDownLatch(1)
        val mainPulse = CountDownLatch(1)
        val exitCount = AtomicInteger()
        val requestOrder = CopyOnWriteArrayList<String>()
        val adapter = BlockingExitChoiceShiori(choiceEntered, releaseChoice, requestOrder)
        val root = File(context.cacheDir, "pre-exit-choice-runtime").canonicalFile
        val runtime = newRuntime(context, adapter)

        try {
            val handle = runBlocking {
                (runtime.startOrJoin("pre-exit-choice", root) as RuntimeResult.Success).value
            }
            runBlocking {
                assertTrue(runtime.attachHost(handle.generation) is RuntimeResult.Success)
            }
            runtime.runner.bindHost(
                SScriptRunner.HostToken(),
                { frame -> if (frame.sakura.text == "Close") closePlayed.countDown() },
                {},
                object : SScriptRunner.UICallback {
                    override fun showUserInputBox(id: String) {
                        if (id == "stale") staleInputShown.countDown()
                    }

                    override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
                },
                object : SScriptRunner.StatusCallback {
                    override fun stop() = Unit
                    override fun canExit() {
                        exitCount.incrementAndGet()
                        exitDelivered.countDown()
                    }

                    override fun switchPlaybackComplete() = Unit
                },
            )
            val action = AtomicReference<com.cattailsw.nanidroid.runtime.dialogue.DialogueAction?>()
            instrumentation.runOnMainSync {
                runtime.runner.setNoWaitMode(true)
                runtime.runner.addMsgToQueue(arrayOf("\\h\\q[Choose,choice]\\e"))
                runtime.runner.run()
                action.set(runtime.runner.dialogueStateSnapshot().pendingChoices.single())
                runtime.runner.activateChoice(requireNotNull(action.get()))
            }
            assertTrue("Blocked pre-exit choice did not start", choiceEntered.await(2, TimeUnit.SECONDS))

            Handler(Looper.getMainLooper()).post {
                runtime.runner.doExit()
                mainPulse.countDown()
            }
            assertTrue(
                "Main looper blocked while submitting OnClose",
                mainPulse.await(500, TimeUnit.MILLISECONDS),
            )

            releaseChoice.countDown()
            assertTrue("Authored OnClose script did not play", closePlayed.await(2, TimeUnit.SECONDS))
            assertTrue("OnClose did not produce one exit terminal", exitDelivered.await(2, TimeUnit.SECONDS))
            assertFalse("Stale pre-exit choice opened input", staleInputShown.await(250, TimeUnit.MILLISECONDS))
            assertEquals(1, exitCount.get())
            assertEquals(listOf("OnChoiceSelectEx", "OnClose"), requestOrder.toList())
        } finally {
            releaseChoice.countDown()
            runtime.close()
        }
    }

    @Test
    fun keroPointerClearRejectsOlderDialogueResponseAndPlaysPointerReply() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val primaryEntered = CountDownLatch(1)
        val releasePrimary = CountDownLatch(1)
        val pointerObserved = CountDownLatch(1)
        val pointerPlayed = CountDownLatch(1)
        val staleInputShown = CountDownLatch(1)
        val mainPulse = CountDownLatch(1)
        val requestOrder = CopyOnWriteArrayList<String>()
        val adapter = BlockingKeroPointerShiori(
            primaryEntered,
            releasePrimary,
            pointerObserved,
            requestOrder,
        )
        val root = File(context.cacheDir, "kero-pointer-clear-runtime").canonicalFile
        val runtime = newRuntime(context, adapter)

        try {
            val handle = runBlocking {
                (runtime.startOrJoin("kero-pointer-clear", root) as RuntimeResult.Success).value
            }
            runBlocking {
                assertTrue(runtime.attachHost(handle.generation) is RuntimeResult.Success)
            }
            runtime.runner.setPresentationRenderer { frame ->
                if (frame.sakura.text == "Pointer") pointerPlayed.countDown()
            }
            runtime.runner.setUICallback(object : SScriptRunner.UICallback {
                override fun showUserInputBox(id: String) {
                    if (id == "stale") staleInputShown.countDown()
                }

                override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
            })
            val action = AtomicReference<com.cattailsw.nanidroid.runtime.dialogue.DialogueAction?>()
            instrumentation.runOnMainSync {
                runtime.runner.setNoWaitMode(true)
                runtime.runner.addMsgToQueue(arrayOf("\\h\\q[Choose,choice]\\e"))
                runtime.runner.run()
                action.set(runtime.runner.dialogueStateSnapshot().pendingChoices.single())
                runtime.runner.activateChoice(requireNotNull(action.get()))
            }
            assertTrue("Blocked authored request did not start", primaryEntered.await(2, TimeUnit.SECONDS))

            Handler(Looper.getMainLooper()).post {
                assertTrue(
                    runtime.runner.dispatchSurfaceInteraction(
                        SurfaceInteractionEffect(
                            kind = PointerEventKind.CLICK,
                            speaker = SurfaceSpeaker.KERO,
                            intrinsic = IntOffset(12, 34),
                            button = 0,
                            source = PointerSource.TOUCH,
                            collisionIdentifier = "Face",
                            diagnosticCollisionId = 42,
                        ),
                    ),
                )
                mainPulse.countDown()
            }
            assertTrue(
                "Main looper could not submit the Kero pointer request",
                mainPulse.await(500, TimeUnit.MILLISECONDS),
            )

            releasePrimary.countDown()
            assertTrue("Kero pointer request was not observed", pointerObserved.await(2, TimeUnit.SECONDS))
            assertTrue("Kero pointer response did not play", pointerPlayed.await(2, TimeUnit.SECONDS))
            assertFalse("Stale authored input was shown after Kero clear", staleInputShown.await(250, TimeUnit.MILLISECONDS))
            assertEquals(listOf("OnChoiceSelectEx", "OnMouseClick"), requestOrder.toList())
        } finally {
            releasePrimary.countDown()
            runtime.close()
        }
    }

    @Test
    fun preSwitchDialogueResponseCannotBlockGhostChangingHandoff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val primaryEntered = CountDownLatch(1)
        val releasePrimary = CountDownLatch(1)
        val ghostChangingObserved = CountDownLatch(1)
        val switchPlaybackCompleted = CountDownLatch(1)
        val staleInputShown = CountDownLatch(1)
        val requestOrder = CopyOnWriteArrayList<String>()
        val adapter = BlockingSwitchShiori(
            primaryEntered,
            releasePrimary,
            ghostChangingObserved,
            requestOrder,
        )
        val root = File(context.cacheDir, "pre-switch-dialogue-response-runtime").canonicalFile
        val targetRoot = File(context.cacheDir, "pre-switch-dialogue-response-target").canonicalFile
        val runtime = newRuntime(context, adapter)

        try {
            val handle = runBlocking {
                (runtime.startOrJoin("pre-switch-dialogue", root) as RuntimeResult.Success).value
            }
            runBlocking {
                assertTrue(runtime.attachHost(handle.generation) is RuntimeResult.Success)
            }
            runtime.runner.setUICallback(object : SScriptRunner.UICallback {
                override fun showUserInputBox(id: String) {
                    if (id == "stale") staleInputShown.countDown()
                }

                override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) = Unit
            })
            runtime.runner.setCallback(object : SScriptRunner.StatusCallback {
                override fun stop() = Unit
                override fun canExit() = Unit
                override fun switchPlaybackComplete() {
                    switchPlaybackCompleted.countDown()
                }
            })
            val action = AtomicReference<com.cattailsw.nanidroid.runtime.dialogue.DialogueAction?>()
            instrumentation.runOnMainSync {
                runtime.runner.setNoWaitMode(true)
                runtime.runner.addMsgToQueue(arrayOf("\\h\\q[Choose,choice]\\e"))
                runtime.runner.run()
                action.set(runtime.runner.dialogueStateSnapshot().pendingChoices.single())
                runtime.runner.activateChoice(requireNotNull(action.get()))
            }
            assertTrue("Blocked dialogue request did not start", primaryEntered.await(2, TimeUnit.SECONDS))

            instrumentation.runOnMainSync {
                val operationId = (runtime.beginSwitch(
                    handle.generation,
                    "switch-target",
                    targetRoot,
                ) as RuntimeResult.Success).value
                runtime.runner.stopClock()
                runtime.runner.clearMsgQueue()
                assertTrue(
                    runtime.runner.doGhostChanging(
                        operationId,
                        "Switch Target",
                        "manual",
                        targetRoot.path,
                    ),
                )
            }

            releasePrimary.countDown()
            assertTrue("OnGhostChanging request was not sent", ghostChangingObserved.await(2, TimeUnit.SECONDS))
            assertTrue(
                "Stale pre-switch dialogue reply blocked OnGhostChanging handoff",
                switchPlaybackCompleted.await(2, TimeUnit.SECONDS),
            )
            assertFalse("Stale pre-switch input was shown", staleInputShown.await(250, TimeUnit.MILLISECONDS))
            assertEquals(listOf("OnChoiceSelectEx", "OnGhostChanging"), requestOrder.toList())
        } finally {
            releasePrimary.countDown()
            runtime.close()
        }
    }

    @Test
    fun emptyRunDuringBlockedGhostChangingDoesNotCompleteHandoff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val responseReturned = CountDownLatch(1)
        val authoredRendered = CountDownLatch(1)
        val handoffCompleted = CountDownLatch(1)
        val handoffCount = AtomicInteger()
        val adapter = BlockingRequestShiori(
            eventId = "OnGhostChanging",
            entered = requestEntered,
            release = releaseRequest,
            returned = responseReturned,
            response = "SHIORI/3.0 200 OK\r\nValue: \\hAuthored goodbye\\e\r\n\r\n",
        )
        val root = File(context.cacheDir, "blocked-ghost-changing-resume-runtime").canonicalFile
        val targetRoot = File(context.cacheDir, "blocked-ghost-changing-resume-target").canonicalFile
        val runtime = newRuntime(context, adapter)

        try {
            val handle = runBlocking {
                (runtime.startOrJoin("blocked-ghost-changing", root) as RuntimeResult.Success).value
            }
            runBlocking {
                assertTrue(runtime.attachHost(handle.generation) is RuntimeResult.Success)
            }
            runtime.runner.setNoWaitMode(true)
            runtime.runner.setPresentationRendererForTesting { frame ->
                if (frame.sakura.text.contains("Authored goodbye")) authoredRendered.countDown()
            }
            runtime.runner.setCallback(object : SScriptRunner.StatusCallback {
                override fun stop() = Unit
                override fun canExit() = Unit
                override fun switchPlaybackComplete() {
                    handoffCount.incrementAndGet()
                    handoffCompleted.countDown()
                }
            })
            val operationId = (runtime.beginSwitch(
                handle.generation,
                "blocked-target",
                targetRoot,
            ) as RuntimeResult.Success).value
            instrumentation.runOnMainSync {
                assertTrue(
                    runtime.runner.doGhostChanging(
                        operationId,
                        "Blocked Target",
                        "manual",
                        targetRoot.path,
                    ),
                )
            }
            assertTrue("OnGhostChanging did not block", requestEntered.await(2, TimeUnit.SECONDS))

            instrumentation.runOnMainSync {
                runtime.runner.startClock()
                runtime.runner.run()
            }
            assertFalse(
                "Empty resume run completed the switch before OnGhostChanging returned",
                handoffCompleted.await(250, TimeUnit.MILLISECONDS),
            )

            releaseRequest.countDown()
            assertTrue("OnGhostChanging response did not return", responseReturned.await(2, TimeUnit.SECONDS))
            assertTrue("Authored OnGhostChanging script did not render", authoredRendered.await(2, TimeUnit.SECONDS))
            assertTrue("Authored OnGhostChanging terminal did not hand off", handoffCompleted.await(2, TimeUnit.SECONDS))
            assertEquals(1, handoffCount.get())
        } finally {
            releaseRequest.countDown()
            runtime.close()
        }
    }

    @Test
    fun blockedTimerResponseDoesNotPlayAfterClockStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val responseReturned = CountDownLatch(1)
        val responseAdmitted = CountDownLatch(1)
        val staleTimerPlayed = CountDownLatch(1)
        val mainPulse = CountDownLatch(1)
        val adapter = BlockingRequestShiori(
            eventId = "OnSecondChange",
            entered = requestEntered,
            release = releaseRequest,
            returned = responseReturned,
            response = "SHIORI/3.0 200 OK\r\nValue: \\hPausedTimer\\e\r\n\r\n",
        )
        val root = File(context.cacheDir, "stopped-clock-request-runtime").canonicalFile
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
                (runtime.startOrJoin("stopped-clock", root) as RuntimeResult.Success).value
            }
            runBlocking {
                assertTrue(runtime.attachHost(handle.generation) is RuntimeResult.Success)
            }
            runtime.runner.setPresentationRenderer { frame ->
                if (frame.sakura.text == "PausedTimer") staleTimerPlayed.countDown()
            }

            Handler(Looper.getMainLooper()).post {
                runtime.runner.setNoWaitMode(true)
                runtime.runner.startClock()
                runtime.runner.dispatchClockTickForTesting()
            }
            assertTrue("Blocking timer request did not start", requestEntered.await(2, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post {
                runtime.runner.stopClock()
                mainPulse.countDown()
            }
            assertTrue(
                "Main looper could not stop the clock while SHIORI was blocked",
                mainPulse.await(500, TimeUnit.MILLISECONDS),
            )

            releaseRequest.countDown()
            assertTrue("Stopped timer response was not admitted", responseAdmitted.await(2, TimeUnit.SECONDS))
            assertFalse(
                "Timer response played after the clock stopped",
                staleTimerPlayed.await(500, TimeUnit.MILLISECONDS),
            )
        } finally {
            releaseRequest.countDown()
            responseReturned.await(2, TimeUnit.SECONDS)
            runtime.close()
        }
    }

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

    @Test
    fun dialogueFallbackRemainsAdjacentWhenTimerQueuesBehindBlockedPrimary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val primaryEntered = CountDownLatch(1)
        val releasePrimary = CountDownLatch(1)
        val timerObserved = CountDownLatch(1)
        val fallbackPlayed = CountDownLatch(1)
        val mainPulse = CountDownLatch(1)
        val requestOrder = CopyOnWriteArrayList<String>()
        val adapter = AtomicDialogueShiori(
            primaryEntered,
            releasePrimary,
            timerObserved,
            requestOrder,
        )
        val root = File(context.cacheDir, "dialogue-fallback-order-runtime").canonicalFile
        val runtime = newRuntime(context, adapter)

        try {
            val handle = runBlocking {
                (runtime.startOrJoin("dialogue-fallback", root) as RuntimeResult.Success).value
            }
            runBlocking {
                assertTrue(runtime.attachHost(handle.generation) is RuntimeResult.Success)
            }
            runtime.runner.setPresentationRenderer { frame ->
                if (frame.sakura.text == "Fallback") fallbackPlayed.countDown()
            }
            val action = AtomicReference<com.cattailsw.nanidroid.runtime.dialogue.DialogueAction?>()
            instrumentation.runOnMainSync {
                runtime.runner.setNoWaitMode(true)
                runtime.runner.addMsgToQueue(arrayOf("\\h\\q[Choose,choice]\\e"))
                runtime.runner.run()
                action.set(runtime.runner.dialogueStateSnapshot().pendingChoices.single())
            }

            Handler(Looper.getMainLooper()).post {
                runtime.runner.activateChoice(requireNotNull(action.get()))
            }
            assertTrue("Blocked dialogue primary did not start", primaryEntered.await(2, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post {
                runtime.runner.dispatchClockTickForTesting()
                mainPulse.countDown()
            }
            assertTrue(
                "Main looper blocked behind dialogue primary",
                mainPulse.await(500, TimeUnit.MILLISECONDS),
            )

            releasePrimary.countDown()
            assertTrue("Fallback response was not played", fallbackPlayed.await(2, TimeUnit.SECONDS))
            assertTrue("Queued timer request was not observed", timerObserved.await(2, TimeUnit.SECONDS))
            assertEquals(
                listOf("OnChoiceSelectEx", "OnChoiceSelect", "OnSecondChange"),
                requestOrder.toList(),
            )
        } finally {
            releasePrimary.countDown()
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

    private class BlockingExitChoiceShiori(
        private val choiceEntered: CountDownLatch,
        private val releaseChoice: CountDownLatch,
        private val order: CopyOnWriteArrayList<String>,
    ) : Shiori {
        override fun getModuleName(): String = "BlockingExitChoice"

        override fun load(): ShioriLoadResult = ShioriLoadResult.Loaded

        override fun request(request: String): String = when {
            "ID: OnChoiceSelectEx\r\n" in request -> {
                order += "OnChoiceSelectEx"
                choiceEntered.countDown()
                assertTrue(
                    "Timed out waiting to release pre-exit choice",
                    releaseChoice.await(3, TimeUnit.SECONDS),
                )
                "SHIORI/3.0 200 OK\r\nValue: \\hStale\\![open,inputbox,stale]\\e\r\n\r\n"
            }
            "ID: OnClose\r\n" in request -> {
                order += "OnClose"
                "SHIORI/3.0 200 OK\r\nValue: \\hClose\\e\r\n\r\n"
            }
            else -> "SHIORI/3.0 204 No Content\r\n\r\n"
        }

        override fun unloadShiori(): ShioriUnloadResult = ShioriUnloadResult.Unloaded
    }

    private class AtomicDialogueShiori(
        private val primaryEntered: CountDownLatch,
        private val releasePrimary: CountDownLatch,
        private val timerObserved: CountDownLatch,
        private val order: CopyOnWriteArrayList<String>,
    ) : Shiori {
        override fun getModuleName(): String = "AtomicDialogue"

        override fun load(): ShioriLoadResult = ShioriLoadResult.Loaded

        override fun request(request: String): String = when {
            "ID: OnChoiceSelectEx\r\n" in request -> {
                order += "OnChoiceSelectEx"
                primaryEntered.countDown()
                assertTrue("Timed out waiting to release dialogue primary", releasePrimary.await(3, TimeUnit.SECONDS))
                "SHIORI/3.0 204 No Content\r\n\r\n"
            }
            "ID: OnChoiceSelect\r\n" in request -> {
                order += "OnChoiceSelect"
                "SHIORI/3.0 200 OK\r\nValue: \\hFallback\\e\r\n\r\n"
            }
            "ID: OnSecondChange\r\n" in request -> {
                order += "OnSecondChange"
                timerObserved.countDown()
                "SHIORI/3.0 204 No Content\r\n\r\n"
            }
            else -> "SHIORI/3.0 204 No Content\r\n\r\n"
        }

        override fun unloadShiori(): ShioriUnloadResult = ShioriUnloadResult.Unloaded
    }

    private class BlockingSwitchShiori(
        private val primaryEntered: CountDownLatch,
        private val releasePrimary: CountDownLatch,
        private val ghostChangingObserved: CountDownLatch,
        private val order: CopyOnWriteArrayList<String>,
    ) : Shiori {
        override fun getModuleName(): String = "BlockingSwitch"

        override fun load(): ShioriLoadResult = ShioriLoadResult.Loaded

        override fun request(request: String): String = when {
            "ID: OnChoiceSelectEx\r\n" in request -> {
                order += "OnChoiceSelectEx"
                primaryEntered.countDown()
                assertTrue("Timed out waiting to release pre-switch request", releasePrimary.await(3, TimeUnit.SECONDS))
                "SHIORI/3.0 200 OK\r\nValue: \\hStale\\![open,inputbox,stale]\\e\r\n\r\n"
            }
            "ID: OnGhostChanging\r\n" in request -> {
                order += "OnGhostChanging"
                ghostChangingObserved.countDown()
                "SHIORI/3.0 200 OK\r\nValue: \\hSwitching\\e\r\n\r\n"
            }
            else -> "SHIORI/3.0 204 No Content\r\n\r\n"
        }

        override fun unloadShiori(): ShioriUnloadResult = ShioriUnloadResult.Unloaded
    }

    private class BlockingKeroPointerShiori(
        private val primaryEntered: CountDownLatch,
        private val releasePrimary: CountDownLatch,
        private val pointerObserved: CountDownLatch,
        private val order: CopyOnWriteArrayList<String>,
    ) : Shiori {
        override fun getModuleName(): String = "BlockingKeroPointer"

        override fun load(): ShioriLoadResult = ShioriLoadResult.Loaded

        override fun request(request: String): String = when {
            "ID: Get_Supported_Events\r\n" in request ->
                "SHIORI/3.0 204 No Content\r\nX-SSTP-PassThru-local: OnMouseClick\r\n\r\n"
            "ID: OnChoiceSelectEx\r\n" in request -> {
                order += "OnChoiceSelectEx"
                primaryEntered.countDown()
                assertTrue("Timed out waiting to release authored request", releasePrimary.await(3, TimeUnit.SECONDS))
                "SHIORI/3.0 200 OK\r\nValue: \\hStale\\![open,inputbox,stale]\\e\r\n\r\n"
            }
            "ID: OnMouseClick\r\n" in request -> {
                order += "OnMouseClick"
                pointerObserved.countDown()
                "SHIORI/3.0 200 OK\r\nValue: \\hPointer\\e\r\n\r\n"
            }
            else -> "SHIORI/3.0 204 No Content\r\n\r\n"
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
