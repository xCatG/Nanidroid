package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.SScriptRunner.Companion.getInstance
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Arrays
import java.util.Hashtable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Characterizes the deterministic SScriptRunner ghost-handoff protocol without
 * filesystem discovery, Activity lifecycle, native engines, or view rebinding.
 */
class GhostSwitchingCharacterizationTest {
    @Rule
    @JvmField
    val androidStubs: com.cattailsw.nanidroid.HostAndroidStubRule =
        com.cattailsw.nanidroid.HostAndroidStubRule()
    private val trace = Trace()
    private lateinit var runner: com.cattailsw.nanidroid.SScriptRunner
    private var currentGhost: RecordingGhost? = null

    @Before
    fun setUp() {
        runner = com.cattailsw.nanidroid.SScriptRunner(
            null,
            GhostSessionCoordinator(),
            lifecycleDispatcher = SScriptLifecycleDispatcher { it() },
        )
        runner.setPresentationRenderer(TraceRenderer(trace))
        resetRunnerWithPublicApi()
        trace.clear()
    }

    @After
    fun tearDown() {
        try { resetRunnerWithPublicApi() } catch (_: IllegalStateException) { }
    }

    @Test
    fun requiredMigrationInvariant_outgoingScriptRendersBeforeSingleHandoffCallback() {
        val outgoing = RecordingGhost(
            "outgoing",
            "Old Ghost Metadata",
            "Old Sakura Display",
            1,
            TRANSITION_SCRIPT,
            trace
        )
        setGhost(outgoing)
        runner.setCallback(RecordingStatusCallback(trace))

        runner.doGhostChanging("Next Sakura", "manual", "/ghosts/next")

        Assert.assertEquals(
            Arrays.asList<String?>(
                "request:outgoing:OnGhostChanging:"
                        + "[Next Sakura, manual, null, /ghosts/next]",
                "render:Switching",
                "handoff"
            ),
            trace.events()
        )
    }

    @Test
    fun requiredMigrationInvariant_returningReplacementReceivesChangedFromOutgoingName() {
        val outgoing = RecordingGhost(
            "outgoing",
            "Old Ghost Metadata",
            "Old Sakura Display",
            1,
            TRANSITION_SCRIPT,
            trace
        )
        val replacement = RecordingGhost(
            "replacement",
            "New Ghost Metadata",
            "New Sakura Display",
            2,
            null,
            trace
        )

        // Prove setup cleanup does not depend on another test having cleared
        // the process singleton's named ghost.
        setGhost(
            RecordingGhost(
                "foreign",
                "Foreign Ghost Metadata",
                "Foreign Sakura Display",
                2,
                null,
                trace
            )
        )
        resetRunnerWithPublicApi()
        trace.clear()

        setGhost(outgoing)
        Assert.assertEquals(ArrayList<String?>(), trace.events())
        runner.setCallback(RecordingStatusCallback(trace))

        runner.doGhostChanging("Next Sakura", "manual", "/ghosts/next")

        setGhost(replacement)

        Assert.assertEquals(
            Arrays.asList<String?>(
                "request:outgoing:OnGhostChanging:"
                        + "[Next Sakura, manual, null, /ghosts/next]",
                "render:Switching",
                "handoff",
                "request:replacement:OnGhostChanged:"
                        + "[Old Ghost Metadata, null]"
            ),
            trace.events()
        )
    }

    @Test
    fun boundUpdateDispatchCannotSwitchGhostBetweenIdentityCheckAndSend() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val switched = CountDownLatch(1)
        val outgoing = RecordingGhost("outgoing", null, null, 2, null, trace, entered, release)
        val replacement = RecordingGhost("replacement", null, null, 2, null, trace)
        setGhost(outgoing)

        val dispatch = Thread {
            runner.doShioriEventForGhost("outgoing", "OnUpdateComplete", arrayOf("changed"))
        }.apply { start() }
        Assert.assertTrue(entered.await(2, TimeUnit.SECONDS))
        val switch = Thread {
            runner.unloadGhostForSwitchForTesting(outgoing)
            runner.attachReservedGhost(runner.reserveGhostForAttachmentForTesting(replacement))
            switched.countDown()
        }.apply { start() }

        Assert.assertFalse(switched.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        dispatch.join(2_000)
        switch.join(2_000)
        Assert.assertTrue(switched.await(0, TimeUnit.MILLISECONDS))
        Assert.assertFalse(
            runner.doShioriEventForGhost("outgoing", "OnUpdateFailure", arrayOf("late")),
        )
        Assert.assertTrue(trace.events().any { it?.startsWith("request:outgoing:OnUpdateComplete") == true })
        Assert.assertFalse(trace.events().any { it?.startsWith("request:replacement:OnUpdateComplete") == true })
    }

    @Test
    fun ghostUpdateQuiesceBlocksOnlyTheBoundGhostDispatch() {
        val gateEntered = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val outgoingDispatchFinished = CountDownLatch(1)
        val currentDispatchFinished = CountDownLatch(1)
        val replacementDispatchFinished = CountDownLatch(1)
        val outgoing = RecordingGhost("outgoing", null, null, 2, null, trace)
        val replacement = RecordingGhost("replacement", null, null, 2, null, trace)
        setGhost(outgoing)

        val gate = Thread {
            runner.withGhostUpdateQuiesced("outgoing") {
                gateEntered.countDown()
                releaseGate.await(2, TimeUnit.SECONDS)
            }
        }.apply { start() }
        Assert.assertTrue(gateEntered.await(2, TimeUnit.SECONDS))

        val outgoingDispatch = Thread {
            runner.doShioriEventForGhost("outgoing", "OnUpdateReady", arrayOf("late"))
            outgoingDispatchFinished.countDown()
        }.apply { start() }
        Assert.assertFalse(outgoingDispatchFinished.await(100, TimeUnit.MILLISECONDS))
        val currentDispatch = Thread {
            runner.doMinimize()
            currentDispatchFinished.countDown()
        }.apply { start() }
        Assert.assertFalse(currentDispatchFinished.await(100, TimeUnit.MILLISECONDS))

        val switch = Thread {
            runner.unloadGhostForSwitchForTesting(outgoing)
            runner.attachReservedGhost(runner.reserveGhostForAttachmentForTesting(replacement))
        }.apply { start() }
        Assert.assertTrue(switch.isAlive)
        releaseGate.countDown()
        switch.join(2_000)
        val replacementDispatch = Thread {
            runner.doShioriEventForGhost("replacement", "OnUpdateReady", arrayOf("current"))
            replacementDispatchFinished.countDown()
        }.apply { start() }
        Assert.assertTrue(replacementDispatchFinished.await(1, TimeUnit.SECONDS))
        Assert.assertTrue(outgoingDispatchFinished.await(1, TimeUnit.SECONDS))

        gate.join(2_000)
        outgoingDispatch.join(2_000)
        currentDispatch.join(2_000)
        replacementDispatch.join(2_000)
        Assert.assertTrue(outgoingDispatchFinished.await(0, TimeUnit.MILLISECONDS))
        Assert.assertTrue(currentDispatchFinished.await(0, TimeUnit.MILLISECONDS))
        Assert.assertTrue(trace.events().any { it?.startsWith("request:replacement:OnUpdateReady") == true })
    }

    @Test
    fun ghostSwitchUnloadWaitsForTheBoundGhostUpdateGate() {
        val gateEntered = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val unloadCalled = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val outgoing = RecordingGhost(
            "outgoing",
            "Outgoing",
            null,
            2,
            null,
            trace,
            unloadCalled = unloadCalled,
        )
        setGhost(outgoing)
        runner.setCallback(RecordingStatusCallback(trace))
        runner.doGhostChanging("Next", "manual", "/next")

        val gate = Thread {
            runner.withGhostUpdateQuiesced("outgoing") {
                gateEntered.countDown()
                releaseGate.await(2, TimeUnit.SECONDS)
            }
        }.apply { start() }
        Assert.assertTrue(gateEntered.await(2, TimeUnit.SECONDS))
        val stop = Thread {
            runner.stop()
            stopFinished.countDown()
        }.apply { start() }

        Assert.assertFalse(unloadCalled.await(100, TimeUnit.MILLISECONDS))
        Assert.assertFalse(stopFinished.await(100, TimeUnit.MILLISECONDS))
        releaseGate.countDown()
        gate.join(2_000)
        stop.join(2_000)
        Assert.assertTrue(unloadCalled.await(0, TimeUnit.MILLISECONDS))
        Assert.assertTrue(stopFinished.await(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun activeGhostCommitGateFlushesBeforeActionAndReloadsAfterSuccessOrFailure() {
        listOf(false, true).forEach { fail ->
            val lifecycle = Trace()
            val active = RecordingGhost(
                "active-${if (fail) "failure" else "success"}",
                null,
                null,
                2,
                null,
                trace,
                lifecycle = lifecycle,
            )
            setGhost(active)

            runner.withGhostUpdateCommitQuiesced(
                active.getGhostId(),
                File(active.getGhostPath()),
                onFailure = { error ->
                    Assert.assertEquals("simulated commit failure", error.message)
                    lifecycle.add("recover")
                },
            ) {
                lifecycle.add("commit")
                if (fail) throw IllegalStateException("simulated commit failure")
            }

            Assert.assertEquals(
                if (fail) Arrays.asList<String?>("unload", "commit", "recover", "reload")
                else Arrays.asList<String?>("unload", "commit", "reload"),
                lifecycle.events(),
            )
            runner.setGhost(null)
        }
    }

    @Test
    fun mutationInvalidatesTheLiveSessionInTheSameCoordinatorCriticalSectionAsUnload() {
        val coordinator = GhostSessionCoordinator()
        val lifecycle = Trace()
        val active = RecordingGhost(
            "atomic-invalidation", null, null, 2, null, trace, lifecycle = lifecycle,
        )
        val reservation = coordinator.reserveLoadedGhostForTesting(active)
        Assert.assertTrue(coordinator.attach(reservation, null) {})

        coordinator.withMutation(
            active.getGhostId(),
            File(active.getGhostPath()),
            onStopped = { Assert.fail("mutation must not stop") },
            onFailure = { error -> throw AssertionError(error) },
            onActiveSessionInvalidated = { lifecycle.add("invalidate") },
        ) { lifecycle.add("commit") }

        Assert.assertEquals(
            Arrays.asList<String?>("invalidate", "unload", "commit", "reload"),
            lifecycle.events(),
        )
    }

    @Test
    fun unloadFailureRunsRecoveryAndReloadBeforeReleasingCommitGate() {
        val lifecycle = Trace()
        val active = RecordingGhost(
            "active-unload-failure",
            null,
            null,
            2,
            null,
            trace,
            lifecycle = lifecycle,
            failUnload = true,
        )
        setGhost(active)

        runner.withGhostUpdateCommitQuiesced(
            active.getGhostId(),
            File(active.getGhostPath()),
            onFailure = { error ->
                Assert.assertEquals("simulated unload failure", error.message)
                lifecycle.add("recover")
            },
        ) {
            lifecycle.add("commit")
        }

        Assert.assertEquals(
            Arrays.asList<String?>("unload", "recover"),
            lifecycle.events(),
        )
        Assert.assertThrows(IllegalStateException::class.java) {
            runner.reserveGhostForAttachmentForTesting(
                RecordingGhost("later", null, null, 2, null, trace),
            )
        }
    }

    @Test
    fun reloadFailureDoesNotOverrideAuthoritativeCommitOrRecoveryResult() {
        listOf(
            Triple("success", false, "completed"),
            Triple("recovered-publish", true, "completed"),
            Triple("recovered-rollback", true, "failed"),
        ).forEach { (name, failCommit, recoveryResult) ->
            val lifecycle = Trace()
            val active = RecordingGhost(
                "reload-failure-$name",
                null,
                null,
                2,
                null,
                trace,
                lifecycle = lifecycle,
                failReload = true,
            )
            setGhost(active)
            var recoveryCalls = 0

            val result = runner.withGhostUpdateCommitQuiesced(
                active.getGhostId(),
                File(active.getGhostPath()),
                onFailure = {
                    recoveryCalls++
                    lifecycle.add("recover")
                    recoveryResult
                },
            ) {
                lifecycle.add("commit")
                if (failCommit) throw IllegalStateException("simulated commit failure")
                "completed"
            }

            Assert.assertEquals(recoveryResult, result)
            Assert.assertEquals(if (failCommit) 1 else 0, recoveryCalls)
            Assert.assertEquals(
                if (failCommit) {
                    Arrays.asList<String?>("unload", "commit", "recover", "reload", "deactivate")
                } else {
                    Arrays.asList<String?>("unload", "commit", "reload", "deactivate")
                },
                lifecycle.events(),
            )
        }
    }

    @Test
    fun suppressedDescriptorReloadFailureCannotRetainTheUnloadedSession() {
        listOf("missing", "empty", "malformed").forEach { failure ->
            val oldSession = RequestCountingShiori()
            val active = SuppressedDescriptorReloadGhost("descriptor-$failure", oldSession)
            runner.setGhost(active)

            val result = runner.withGhostUpdateCommitQuiesced(
                active.getGhostId(),
                File(active.getGhostPath()),
            ) { "completed" }

            Assert.assertEquals("completed", result)
            Assert.assertEquals(1, oldSession.unloadCalls)
            Assert.assertFalse(active.hasSession())
            Assert.assertTrue(active.ghostError())
            Assert.assertEquals(500, active.doShioriEvent("OnProbe", null).getStatusCode())
            Assert.assertEquals(0, oldSession.requestCalls)
        }
    }

    @Test
    fun commitGateDoesNotUnloadInactiveMismatchedOrReplacedGhost() {
        val lifecycle = Trace()
        val active = RecordingGhost(
            "active-isolation",
            null,
            null,
            2,
            null,
            trace,
            lifecycle = lifecycle,
        )
        setGhost(active)

        runner.withGhostUpdateCommitQuiesced("inactive", File("inactive")) {
            lifecycle.add("inactive-commit")
        }
        runner.withGhostUpdateCommitQuiesced(
            active.getGhostId(),
            File("different-root"),
            onFailure = { lifecycle.add("mismatched-rejected") },
        ) { lifecycle.add("mismatched-commit") }
        runner.withGhostUpdateCommitQuiesced(active.getGhostId(), File(active.getGhostPath())) {
            lifecycle.add("active-commit")
        }

        Assert.assertEquals(
            Arrays.asList<String?>("inactive-commit", "mismatched-rejected", "unload", "active-commit", "reload"),
            lifecycle.events(),
        )
    }

    @Test
    fun unreservedNativeGlobalReplacementPoisonsEveryLaterSessionOperation() {
        val active = RecordingGhost("active-poison", null, null, 2, null, trace)
        val replacement = RecordingGhost("replacement-poison", null, null, 2, null, trace)
        setGhost(active)

        Assert.assertThrows(IllegalStateException::class.java) { setGhost(replacement) }
        Assert.assertFalse(
            runner.doShioriEventForGhost(
                active.getGhostId(),
                File(active.getGhostPath()),
                "OnProbe",
                null,
            ),
        )
        var mutationFailure: Throwable? = null
        runner.withGhostUpdateCommitQuiesced(
            active.getGhostId(),
            File(active.getGhostPath()),
            onFailure = { mutationFailure = it },
        ) { Assert.fail("poisoned mutation must not run") }
        Assert.assertNotNull(mutationFailure)
        Assert.assertThrows(IllegalStateException::class.java) {
            runner.reserveGhostForAttachmentForTesting(
                RecordingGhost("later-poison", null, null, 2, null, trace),
            )
        }
    }

    @Test
    fun sameIdDifferentRootCannotReceiveBoundUpdateEvent() {
        val active = RecordingGhost(
            "same-id",
            null,
            null,
            2,
            null,
            trace,
            ghostPath = File("event-root-a", "same-id").path,
        )
        setGhost(active)

        Assert.assertFalse(
            runner.doShioriEventForGhost(
                "same-id",
                File("event-root-b", "same-id"),
                "OnUpdateComplete",
                arrayOf("ghost/master.txt"),
            ),
        )
        Assert.assertFalse(trace.events().any { it?.contains("OnUpdateComplete") == true })
    }

    @Test
    fun reloadDeactivationDoesNotMakeGhostSwitchStopSpin() {
        val lifecycle = Trace()
        val active = RecordingGhost(
            "reload-stop",
            "Reload Stop",
            null,
            2,
            null,
            trace,
            lifecycle = lifecycle,
            failReload = true,
        )
        setGhost(active)
        runner.setCallback(RecordingStatusCallback(trace))
        runner.doGhostChanging("Next", "manual", "/next")
        runner.withGhostUpdateCommitQuiesced(
            active.getGhostId(),
            File(active.getGhostPath()),
        ) { Unit }

        val stopped = CountDownLatch(1)
        Thread { runner.stop(); stopped.countDown() }.start()

        Assert.assertTrue(stopped.await(1, TimeUnit.SECONDS))
        Assert.assertEquals(listOf("unload", "reload", "deactivate"), lifecycle.events())
        Assert.assertTrue(trace.events().contains("handoff"))
    }

    @Test
    fun reservedConstructionCannotBeSwappedBeforeExactAttachment() {
        val lifecycle = Trace()
        val reservedGhost = RecordingGhost(
            "reserved-gap",
            null,
            null,
            2,
            null,
            trace,
            lifecycle = lifecycle,
        )
        val reservation = runner.reserveGhostForAttachmentForTesting(reservedGhost)
        val mutationEntered = CountDownLatch(1)
        val mutationFinished = CountDownLatch(1)
        val mutation = Thread {
            runner.withGhostUpdateCommitQuiesced(
                reservedGhost.getGhostId(),
                File(reservedGhost.getGhostPath()),
            ) {
                lifecycle.add("commit")
                mutationEntered.countDown()
            }
            mutationFinished.countDown()
        }.apply { start() }

        Assert.assertFalse(mutationEntered.await(100, TimeUnit.MILLISECONDS))
        Assert.assertTrue(runner.attachReservedGhost(reservation))
        Assert.assertTrue(mutationFinished.await(2, TimeUnit.SECONDS))
        mutation.join(2_000)

        Assert.assertEquals(
            Arrays.asList<String?>("unload", "commit", "reload"),
            lifecycle.events(),
        )
    }

    @Test
    fun reservationsUseExactRootAndIdAndStaleReleaseCannotConsumeReplacement() {
        val root = File("reservation-shared-root/expected-id")
        val first = RecordingGhost(
            "expected-id", null, null, 2, null, trace,
            lifecycle = Trace(), ghostPath = root.path,
        )
        val replacementLifecycle = Trace()
        val replacement = RecordingGhost(
            "expected-id", null, null, 2, null, trace,
            lifecycle = replacementLifecycle, ghostPath = root.path,
        )
        val firstReservation = runner.reserveGhostForAttachmentForTesting(first)

        val wrongId = runner.withGhostUpdateCommitQuiesced(
            "different-id",
            root,
            onFailure = { "failed" },
        ) { "committed" }
        Assert.assertEquals("failed", wrongId)

        val otherRoot = runner.withGhostUpdateCommitQuiesced(
            "expected-id",
            File("reservation-other-root/expected-id"),
        ) { "independent" }
        Assert.assertEquals("independent", otherRoot)

        Assert.assertTrue(runner.abandonReservedGhost(firstReservation))
        val replacementReservation = runner.reserveGhostForAttachmentForTesting(replacement)
        val mutationEntered = CountDownLatch(1)
        val mutation = Thread {
            runner.withGhostUpdateCommitQuiesced("expected-id", root) {
                mutationEntered.countDown()
            }
        }.apply { start() }
        Assert.assertFalse(mutationEntered.await(100, TimeUnit.MILLISECONDS))
        Assert.assertFalse(runner.abandonReservedGhost(firstReservation))
        Assert.assertEquals(0, replacementLifecycle.events().size)
        Assert.assertTrue(runner.attachReservedGhost(replacementReservation))
        Assert.assertTrue(mutationEntered.await(2, TimeUnit.SECONDS))
        mutation.join(2_000)
        Assert.assertEquals(
            Arrays.asList<String?>("unload", "reload"),
            replacementLifecycle.events(),
        )
    }

    @Test
    fun abandonedReservationUnloadsBeforeReleasingMutation() {
        val lifecycle = Trace()
        val reservedGhost = RecordingGhost(
            "abandoned-reservation", null, null, 2, null, trace, lifecycle = lifecycle,
        )
        val reservation = runner.reserveGhostForAttachmentForTesting(reservedGhost)
        val mutationEntered = CountDownLatch(1)
        val mutation = Thread {
            runner.withGhostUpdateCommitQuiesced(
                reservedGhost.getGhostId(), File(reservedGhost.getGhostPath()),
            ) {
                lifecycle.add("commit")
                mutationEntered.countDown()
            }
        }.apply { start() }

        Assert.assertFalse(mutationEntered.await(100, TimeUnit.MILLISECONDS))
        Assert.assertTrue(runner.abandonReservedGhost(reservation))
        Assert.assertTrue(mutationEntered.await(2, TimeUnit.SECONDS))
        mutation.join(2_000)
        Assert.assertEquals(
            Arrays.asList<String?>("unload", "commit"),
            lifecycle.events(),
        )
    }

    @Test
    fun concurrentFirstCallersShareOneRunnerAuthority() {
        resetRunnerWithPublicApi()
        SScriptRunner.resetInstanceForTesting()
        val start = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<SScriptRunner>())
        val callers = List(12) {
            Thread {
                start.await(2, TimeUnit.SECONDS)
                results += SScriptRunner.getInstance(null)
            }.apply { start() }
        }

        start.countDown()
        callers.forEach { it.join(2_000) }

        Assert.assertEquals(12, results.size)
        Assert.assertEquals(1, results.map(System::identityHashCode).toSet().size)
        runner = results.first()
    }

    private fun setGhost(ghost: RecordingGhost) {
        currentGhost = ghost
        runner.setGhost(ghost)
    }

    private fun resetRunnerWithPublicApi() {
        runner.setNoWaitMode(true)

        // Drain a failed in-flight transition while its callback and inert fake
        // are still installed. This clears changingPending through stop().
        runner.clearMsgQueue()

        runner.setCallback(null)
        runner.setUICallback(null)

        // setGhost(null) dereferences the replacement when the outgoing name is
        // non-null. Suppressing the test fake's name takes the public silent
        // assignment path and avoids coupling this characterization to fields.
        if (currentGhost == null) {
            // A previous or future suite may leave a named ghost in the process
            // singleton. Replacing it with a null-name count-2 fake avoids the
            // production null-replacement dereference without reflection.
            setGhost(
                RecordingGhost(
                    "cleanup",
                    null,
                    null,
                    2,
                    null,
                    trace
                )
            )
        } else {
            currentGhost!!.suppressOutgoingName()
        }
        runner.setGhost(null)
        currentGhost = null
    }

    private class Trace {
        private val events: MutableList<String?> = ArrayList<String?>()

        fun add(event: String?) {
            events.add(event)
        }

        fun clear() {
            events.clear()
        }

        fun events(): MutableList<String?> {
            return ArrayList<String?>(events)
        }
    }

    private class RecordingStatusCallback
        (private val trace: Trace) : com.cattailsw.nanidroid.SScriptRunner.StatusCallback {
        override fun stop() {
            // Generic runner-stop notification is outside the handoff oracle.
        }

        override fun canExit() {
            // Exit handling is outside the handoff oracle.
        }

        override fun ghostSwitchScriptComplete() {
            trace.add("handoff")
        }
    }

    private class RecordingGhost(
        ghostId: String,
        ghostName: String?,
        sakuraName: String?,
        createCount: Long,
        private val transitionScript: String?,
        private val trace: Trace,
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
        private val unloadCalled: CountDownLatch? = null,
        private val lifecycle: Trace? = null,
        failUnload: Boolean = false,
        private val failReload: Boolean = false,
        ghostPath: String = ghostId,
    ) : com.cattailsw.nanidroid.Ghost(
        ghostPath
    ) {
        private val fakeGhostId = ghostId
        private var fakeGhostName = ghostName
        private var fakeSakuraName = sakuraName
        private val fakeCreateCount = createCount
        private var unloadFailuresRemaining = if (failUnload) 1 else 0

        override fun getGhostId(): String = fakeGhostId
        override fun getGhostName(): String? = fakeGhostName
        override fun getSakuraName(): String? = fakeSakuraName
        override fun getKeroName(): String = "Kero"
        override fun getUsername(): String = "User"
        override fun getCreateCount(): Long = fakeCreateCount

        override fun loadGhostInfo() {
            // Test-only fake: no descriptors, surfaces, filesystem, or SHIORI engine.
        }

        override fun incrementCreateCount() {
            // Test-only fake: create-count values are supplied explicitly.
        }

        fun suppressOutgoingName() {
            fakeGhostName = null
            fakeSakuraName = null
        }
public override fun doShioriEvent(
            event: String,
            references: Array<String>?
        ): com.cattailsw.nanidroid.ShioriResponse {
            entered?.countDown()
            release?.await(2, TimeUnit.SECONDS)
            trace.add(
                ("request:" + fakeGhostId + ":" + event + ":"
                        + references.contentToString())
            )
            if ("OnGhostChanging" == event && transitionScript != null) {
                val values = Hashtable<String, String>()
                values.put("Value", transitionScript)
                return com.cattailsw.nanidroid.ShioriResponse("SHIORI/3.0 200 OK", values)
            }
            return com.cattailsw.nanidroid.ShioriResponse("SHIORI/3.0 204 No Content")
        }

        public override fun unload() {
            unloadCalled?.countDown()
            lifecycle?.add("unload")
            if (unloadFailuresRemaining > 0) {
                unloadFailuresRemaining--
                throw IllegalStateException("simulated unload failure")
            }
        }

        override fun reloadAfterGhostUpdate() {
            lifecycle?.add("reload")
            if (failReload) throw IllegalStateException("simulated reload failure")
        }

        override fun deactivateAfterGhostUpdateReloadFailure() {
            lifecycle?.add("deactivate")
        }
    }

    private class SuppressedDescriptorReloadGhost(
        ghostId: String,
        oldSession: com.cattailsw.nanidroid.shiori.Shiori,
    ) : com.cattailsw.nanidroid.Ghost(ghostId) {
        private val fakeGhostId = ghostId

        init {
            shiori = oldSession
            error = false
        }

        override fun loadGhostInfo() {
            error = true
        }

        override fun getGhostId(): String = fakeGhostId
        override fun getGhostName(): String? = null
        override fun getSakuraName(): String? = null
        override fun getKeroName(): String = "Kero"
        override fun getUsername(): String = "User"
        override fun getCreateCount(): Long = 2
        override fun incrementCreateCount() = Unit

        fun hasSession(): Boolean = shiori != null
    }

    private class RequestCountingShiori : com.cattailsw.nanidroid.shiori.Shiori {
        var requestCalls = 0
        var unloadCalls = 0

        override fun getModuleName(): String = "test"

        override fun request(request: String): String {
            requestCalls++
            return "SHIORI/3.0 204 No Content\r\n\r\n"
        }

        override fun terminate() = Unit

        override fun unloadShiori() {
            unloadCalls++
        }
    }

    /** Fail-fast UI-free collaborator for the runner's complete render frame.  */
    private class TraceRenderer(private val trace: Trace) :
        com.cattailsw.nanidroid.GhostPresentationRenderer {
        private var previousText = ""

        public override fun render(frame: com.cattailsw.nanidroid.GhostPresentationFrame) {
            val value: String = frame.sakura.text
            if (value != previousText && value.length > 0) {
                trace.add("render:" + value)
            }
            previousText = value
        }
    }

    companion object {
        private const val TRANSITION_SCRIPT = "\\_qSwitching\\e"
    }
}
