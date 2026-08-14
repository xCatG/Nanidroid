package com.cattailsw.nanidroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GhostSwitchRequestTest {
    @get:Rule
    val androidStubs = HostAndroidStubRule()

    @Test
    fun constructingAStaleGhostDoesNotConsumeItsFirstBootCount() {
        GhostActivationCounter.increments = 0

        val ghost = CountingGhost()

        assertTrue(GhostActivationCounter.increments == 0)
        ghost.recordActivation()
        assertTrue(GhostActivationCounter.increments == 1)
    }

    @Test
    fun activationPersistsItsCountThroughTheDeferredPath() {
        val ghost = DeferredPersistenceGhost()

        ghost.recordActivation()

        assertTrue(ghost.persistedCount == 1L)
    }

    @Test
    fun completedRequestIsDiscardedWhenANewerGhostIsPending() {
        assertFalse(ownsGhostSwitchRequest("ghost-a", "ghost-b"))
    }

    @Test
    fun completedRequestIsAppliedWhenItStillOwnsThePendingGhost() {
        assertTrue(ownsGhostSwitchRequest("ghost-a", "ghost-a"))
    }

    @Test
    fun destroyedOrStaleSwitchResultIsAbandonedExactlyOnceAndNeverApplied() {
        listOf(
            Triple(true, "ghost-a", "ghost-a"),
            Triple(false, "ghost-a", "ghost-b"),
        ).forEach { (destroyed, target, pending) ->
            var abandons = 0
            var applies = 0

            routeGhostSwitchResult(
                Any(),
                destroyed = destroyed,
                finishing = false,
                targetGhostId = target,
                pendingGhostId = pending,
                abandon = { abandons++ },
                apply = { applies++ },
            )

            assertTrue(abandons == 1)
            assertTrue(applies == 0)
        }
    }

    @Test
    fun startupCancellationAbandonsItsPreparedReservation() {
        var abandons = 0

        abandonUnclaimedReservation(Any(), claimed = false) { abandons++ }

        assertTrue(abandons == 1)
    }

    @Test
    fun claimedReservationIsNotAbandonedByItsCompletionFinallyBlock() {
        var abandons = 0

        abandonUnclaimedReservation(Any(), claimed = true) { abandons++ }

        assertTrue(abandons == 0)
    }

    private class CountingGhost : Ghost("counting-ghost") {
        override fun loadGhostInfo() = Unit

        override fun incrementCreateCount() {
            GhostActivationCounter.increments++
        }
    }

    private class DeferredPersistenceGhost : Ghost("deferred-persistence-ghost") {
        var persistedCount: Long? = null

        override fun loadGhostInfo() = Unit

        override fun getCreateCount() = 0L

        override fun persistActivationCount(count: Long) {
            persistedCount = count
        }
    }

    private object GhostActivationCounter {
        var increments = 0
    }
}
