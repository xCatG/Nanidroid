package com.cattailsw.nanidroid

import org.junit.After
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

/** Characterizes boot delivery across runner clock and ghost lifecycles.  */
class SScriptRunnerBootDispatchTest {
    @Rule
    @JvmField
    val androidStubs: com.cattailsw.nanidroid.HostAndroidStubRule =
        com.cattailsw.nanidroid.HostAndroidStubRule()

    private val runners: MutableList<com.cattailsw.nanidroid.SScriptRunner> =
        ArrayList<com.cattailsw.nanidroid.SScriptRunner>()

    @After
    fun stopClocks() {
        for (runner in runners) {
            runner.stopClock()
        }
    }

    @Test
    fun dispatchesBootOnceAcrossDuplicateStartResumeAndNamedGhostHandoff() {
        val trace: MutableList<String?> = ArrayList<String?>()
        val runner: com.cattailsw.nanidroid.SScriptRunner = runner()
        val initial = RecordingGhost("initial", "Initial Ghost", 2, trace)
        val replacement = RecordingGhost("replacement", "Replacement Ghost", 2, trace)

        runner.setGhost(initial)
        runner.startClock()
        runner.startClock()
        runner.stopClock()
        runner.startClock()
        runner.stopClock()
        runner.setGhost(replacement)
        runner.startClock()

        Assert.assertEquals(
            mutableListOf<String?>(
                "initial:OnBoot:[master]",
                "replacement:OnGhostChanged:[Initial Ghost, null]"
            ),
            trace
        )
    }

    @Test
    fun newlyConstructedRunnerDispatchesBootOnceAfterAppRecreation() {
        val trace: MutableList<String?> = ArrayList<String?>()
        val runner: com.cattailsw.nanidroid.SScriptRunner = runner()

        runner.setGhost(RecordingGhost("recreated", "Recreated Ghost", 2, trace))
        runner.startClock()

        Assert.assertEquals(mutableListOf<String?>("recreated:OnBoot:[master]"), trace)
    }

    @Test
    fun firstActivationReplacementSendsFirstBootWithoutAdditionalBoot() {
        val trace: MutableList<String?> = ArrayList<String?>()
        val runner: com.cattailsw.nanidroid.SScriptRunner = runner()
        runner.setGhost(RecordingGhost("initial", "Initial Ghost", 2, trace))
        runner.startClock()
        runner.stopClock()
        runner.setGhost(RecordingGhost("replacement", "New Ghost", 0, trace))
        runner.startClock()

        Assert.assertEquals(
            mutableListOf<String?>(
                "initial:OnBoot:[master]",
                "replacement:OnFirstBoot:[0]"
            ),
            trace
        )
    }

    private fun runner(): com.cattailsw.nanidroid.SScriptRunner {
        val runner: com.cattailsw.nanidroid.SScriptRunner =
            com.cattailsw.nanidroid.SScriptRunner(null)
        runners.add(runner)
        return runner
    }

    private class RecordingGhost(
        ghostId: String,
        ghostName: String?,
        createCount: Long,
        private val trace: MutableList<String?>
    ) : com.cattailsw.nanidroid.Ghost(
        ghostId
    ) {
        private val fakeGhostId = ghostId
        private val fakeGhostName = ghostName
        private val fakeCreateCount = createCount

        override fun getGhostId(): String = fakeGhostId
        override fun getGhostName(): String? = fakeGhostName
        override fun getCreateCount(): Long = fakeCreateCount

        override fun loadGhostInfo() {
            // The fake owns all metadata needed by this lifecycle trace.
        }

        override fun incrementCreateCount() {
            // Creation counts are fixed test fixtures, not persisted state.
        }

        public override fun doShioriEvent(
            event: String,
            references: Array<String>?
        ): com.cattailsw.nanidroid.ShioriResponse {
            trace.add(fakeGhostId + ":" + event + ":" + references.contentToString())
            return com.cattailsw.nanidroid.ShioriResponse("SHIORI/3.0 204 No Content")
        }
    }
}