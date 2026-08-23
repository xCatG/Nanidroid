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
    fun unreservedNativeGlobalReplacementPoisonsEveryLaterSessionOperation() {
        val active = RecordingGhost("active-poison", null, null, 2, null, trace)
        val replacement = RecordingGhost("replacement-poison", null, null, 2, null, trace)
        setGhost(active)

        Assert.assertThrows(IllegalStateException::class.java) { setGhost(replacement) }
        Assert.assertFalse(runner.doShioriEvent("OnProbe", null))
        Assert.assertThrows(IllegalStateException::class.java) {
            runner.reserveGhostForAttachmentForTesting(
                RecordingGhost("later-poison", null, null, 2, null, trace),
            )
        }
    }

    @Test
    fun reservationsUseExactIdentityAndStaleReleaseCannotConsumeReplacement() {
        val root = File("reservation-shared-root/expected-id")
        val firstLifecycle = Trace()
        val first = RecordingGhost(
            "expected-id", null, null, 2, null, trace,
            lifecycle = firstLifecycle, ghostPath = root.path,
        )
        val replacementLifecycle = Trace()
        val replacement = RecordingGhost(
            "expected-id", null, null, 2, null, trace,
            lifecycle = replacementLifecycle, ghostPath = root.path,
        )
        val firstReservation = runner.reserveGhostForAttachmentForTesting(first)

        Assert.assertTrue(runner.abandonReservedGhost(firstReservation))
        Assert.assertEquals(listOf("unload"), firstLifecycle.events())

        val replacementReservation = runner.reserveGhostForAttachmentForTesting(replacement)
        Assert.assertFalse(runner.abandonReservedGhost(firstReservation))
        Assert.assertTrue(runner.attachReservedGhost(replacementReservation))
        Assert.assertEquals(emptyList<String?>(), replacementLifecycle.events())
    }

    @Test
    fun abandonedReservationUnloadsBeforeRelease() {
        val lifecycle = Trace()
        val reservedGhost = RecordingGhost(
            "abandoned-reservation", null, null, 2, null, trace, lifecycle = lifecycle,
        )
        val reservation = runner.reserveGhostForAttachmentForTesting(reservedGhost)

        Assert.assertTrue(runner.abandonReservedGhost(reservation))
        Assert.assertEquals(listOf("unload"), lifecycle.events())
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
        private val lifecycle: Trace? = null,
        ghostPath: String = ghostId,
    ) : com.cattailsw.nanidroid.Ghost(
        ghostPath
    ) {
        private val fakeGhostId = ghostId
        private var fakeGhostName = ghostName
        private var fakeSakuraName = sakuraName
        private val fakeCreateCount = createCount

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
            ref: Array<String>?
        ): com.cattailsw.nanidroid.ShioriResponse {
            trace.add(
                ("request:" + fakeGhostId + ":" + event + ":"
                        + ref.contentToString())
            )
            if ("OnGhostChanging" == event && transitionScript != null) {
                val values = Hashtable<String, String>()
                values.put("Value", transitionScript)
                return com.cattailsw.nanidroid.ShioriResponse("SHIORI/3.0 200 OK", values)
            }
            return com.cattailsw.nanidroid.ShioriResponse("SHIORI/3.0 204 No Content")
        }

        public override fun unload() {
            lifecycle?.add("unload")
        }

    }

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
