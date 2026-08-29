package com.cattailsw.nanidroid

import org.junit.Test

/** Production-cutover entry point for the reviewed target-process host contract. */
class NanidroidLifecycleInstrumentationTest {
    @Test
    fun lifecycleCommandsUseOneHostIdIncreasingEpochsAndMainLoopSubmission() = runHostProof {
        lifecycleCommandsUseOneHostIdIncreasingEpochsAndMainLoopSubmission()
    }

    @Test
    fun overlappingActivitiesKeepOldStartedButOnlyNewHostPlaysAndAcknowledgesCues() = runHostProof {
        overlappingActivitiesKeepOldStartedButOnlyNewHostPlaysAndAcknowledgesCues()
    }

    @Test
    fun stoppedCollectorDoesNotRenderUntilStartedAgain() = runHostProof {
        stoppedCollectorDoesNotRenderUntilStartedAgain()
    }

    @Test
    fun staleSameHostEpochSnapshotCannotPlayAcknowledgeOrDeliverExit() = runHostProof {
        staleSameHostEpochSnapshotCannotPlayAcknowledgeOrDeliverExit()
    }

    @Test
    fun expiredOldHostCueCannotAliasReplacementHostCue() = runHostProof {
        expiredOldHostCueCannotAliasReplacementHostCue()
    }

    @Test
    fun sixtyFiveHostlessCuesAdvanceWithoutInventoryOrBackpressure() = runHostProof {
        sixtyFiveHostlessCuesAdvanceWithoutInventoryOrBackpressure()
    }

    @Test
    fun exitDeliveryClaimsFinishesAcknowledgesBeforeLifecycleRevocationAndDoesNotFinishLaterHost() = runHostProof {
        exitDeliveryClaimsFinishesAcknowledgesBeforeLifecycleRevocationAndDoesNotFinishLaterHost()
    }

    private fun runHostProof(block: GhostRuntimeHostAdapterInstrumentationTest.() -> Unit) {
        GhostRuntimeHostAdapterInstrumentationTest().also { proof ->
            try {
                proof.block()
            } finally {
                proof.tearDown()
            }
        }
    }
}
