package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.SScriptRunner.Companion.getInstance
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Arrays
import java.util.Hashtable

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
        runner = com.cattailsw.nanidroid.SScriptRunner.getInstance(null)
        runner.setPresentationRenderer(TraceRenderer(trace))
        resetRunnerWithPublicApi()
        trace.clear()
    }

    @After
    fun tearDown() {
        resetRunnerWithPublicApi()
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
        runner.setGhost(
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
        private val trace: Trace
    ) : com.cattailsw.nanidroid.Ghost(
        ghostId
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
            references: Array<String>?
        ): com.cattailsw.nanidroid.ShioriResponse {
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
            // Ownership and unload ordering are intentionally deferred.
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
