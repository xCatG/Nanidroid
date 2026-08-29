package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.runtime.RuntimeCatalogState
import com.cattailsw.nanidroid.runtime.RuntimeCommand
import com.cattailsw.nanidroid.runtime.RuntimeGhostMetadata
import com.cattailsw.nanidroid.runtime.RuntimeHostId
import com.cattailsw.nanidroid.runtime.RuntimeHostLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupCandidateAttemptsTest {
    @Test
    fun preferredThenBundledThenCatalogOrderIsDeterministic() {
        assertEquals(
            listOf("preferred", "nanidroid", "other"),
            launchCandidateIds("PREFERRED", listOf("other", "preferred", "nanidroid")),
        )
    }

    // Mutation caught: the catalog epoch is consumed after the first retryable failure instead of advancing.
    @Test
    fun retryableIdleAfterBusyAdvancesFromBadPreferredToInstalledFallback() {
        val attempts = StartupCandidateAttempts()
        assertTrue(attempts.reserve(7L))
        attempts.configure(7L, listOf("bad-preferred", "valid-fallback"))

        assertEquals("bad-preferred", next(attempts, 7L, GhostRuntimePhase.Idle, revision = 10L))
        assertNull(next(attempts, 7L, GhostRuntimePhase.Starting, revision = 11L))
        assertEquals("valid-fallback", next(attempts, 7L, GhostRuntimePhase.Idle, revision = 12L))
        assertNull(next(attempts, 7L, GhostRuntimePhase.Starting, revision = 13L))
        assertNull(next(attempts, 7L, GhostRuntimePhase.Attached, generation = 2L, revision = 14L))
    }

    // Mutation caught: Activity recreation reconfigures the same epoch and retries the failed preferred ghost.
    @Test
    fun sameEpochConfigurationDoesNotResetAttemptProgress() {
        val attempts = StartupCandidateAttempts()
        assertTrue(attempts.reserve(8L))
        attempts.configure(8L, listOf("bad", "good"))
        assertEquals("bad", next(attempts, 8L, GhostRuntimePhase.Idle, revision = 20L))
        assertNull(next(attempts, 8L, GhostRuntimePhase.Starting, revision = 21L))

        assertFalse(attempts.reserve(8L))
        attempts.configure(8L, listOf("bad", "good"))
        assertEquals("good", next(attempts, 8L, GhostRuntimePhase.Idle, revision = 22L))
    }

    // Mutation caught: Activity destruction after reserve but before configure strands the epoch forever.
    @Test
    fun unconfiguredSameEpochCanReserveAgainAfterCancelledActivityRead() {
        val attempts = StartupCandidateAttempts()

        assertTrue(attempts.reserve(81L))
        assertTrue(attempts.reserve(81L))
        attempts.configure(81L, listOf("candidate"))
        assertFalse(attempts.reserve(81L))
        assertEquals("candidate", next(attempts, 81L, GhostRuntimePhase.Idle, revision = 1L))
    }

    // Mutation caught: Idle Ready fallback starts while a no-generation exit/parent operation is in flight.
    @Test
    fun exitBeforeFirstCandidatePermanentlyCancelsAutoStartForTheEpoch() {
        val attempts = StartupCandidateAttempts()
        attempts.reserve(9L)
        attempts.configure(9L, listOf("candidate"))

        assertNull(next(attempts, 9L, GhostRuntimePhase.Idle, exitPresent = true, revision = 30L))
        assertNull(next(attempts, 9L, GhostRuntimePhase.Idle, revision = 31L))
    }

    // Mutation caught: an acknowledged no-generation exit resumes a previously busy fallback chain.
    @Test
    fun observedExitPermanentlyCancelsBusyAutoStartChainForTheCatalogEpoch() {
        val attempts = StartupCandidateAttempts()
        attempts.reserve(91L)
        attempts.configure(91L, listOf("bad", "must-not-start"))
        assertEquals("bad", next(attempts, 91L, GhostRuntimePhase.Idle, revision = 1L))
        assertNull(next(attempts, 91L, GhostRuntimePhase.Starting, revision = 2L))

        assertNull(next(attempts, 91L, GhostRuntimePhase.Idle, parentOperationId = 44L, exitPresent = true, revision = 3L))
        assertNull(next(attempts, 91L, GhostRuntimePhase.Idle, revision = 4L))
        assertFalse(attempts.reserve(91L))
        assertNull(next(attempts, 91L, GhostRuntimePhase.Idle, revision = 5L))
    }

    // Mutation caught: Starting is conflated and retryable failed Idle cannot advance the submitted candidate.
    @Test
    fun retryableFailureNoticeAdvancesEvenWhenBusySnapshotWasNotObserved() {
        val attempts = StartupCandidateAttempts()
        attempts.reserve(10L)
        attempts.configure(10L, listOf("bad", "good"))

        assertEquals("bad", next(attempts, 10L, GhostRuntimePhase.Idle, revision = 40L))
        assertNull(next(attempts, 10L, GhostRuntimePhase.Idle, revision = 40L))
        assertEquals(
            "good",
            next(
                attempts,
                10L,
                GhostRuntimePhase.Idle,
                revision = 42L,
                notice = com.cattailsw.nanidroid.runtime.RuntimeNotice(
                    2L,
                    com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.PREPARATION_FAILED,
                ),
            ),
        )
    }

    // Mutation caught: a persistent old retryable notice consumes the next candidate on an unrelated revision.
    @Test
    fun persistentOldRetryableNoticeDoesNotAdvanceCurrentCandidate() {
        val attempts = StartupCandidateAttempts()
        val oldNotice = com.cattailsw.nanidroid.runtime.RuntimeNotice(
            5L,
            com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.PREPARATION_FAILED,
        )
        attempts.reserve(11L)
        attempts.configure(11L, listOf("first", "second"))

        assertEquals(
            "first",
            next(attempts, 11L, GhostRuntimePhase.Idle, revision = 50L, notice = oldNotice),
        )
        assertNull(next(attempts, 11L, GhostRuntimePhase.Idle, revision = 51L, notice = oldNotice))
        assertEquals(
            "second",
            next(
                attempts,
                11L,
                GhostRuntimePhase.Idle,
                revision = 52L,
                notice = oldNotice.copy(operationId = 6L),
            ),
        )
    }

    // Mutation caught: a later manual switch failure is mistaken for failure of the successful startup candidate.
    @Test
    fun successfulGenerationCompletesAutoStartChainAcrossLaterIdleFailure() {
        val attempts = StartupCandidateAttempts()
        attempts.reserve(12L)
        attempts.configure(12L, listOf("started", "must-not-auto-start"))

        assertEquals("started", next(attempts, 12L, GhostRuntimePhase.Idle, revision = 60L))
        assertNull(next(attempts, 12L, GhostRuntimePhase.Attached, generation = 3L, revision = 61L))
        assertNull(
            next(
                attempts,
                12L,
                GhostRuntimePhase.Idle,
                revision = 70L,
                notice = com.cattailsw.nanidroid.runtime.RuntimeNotice(
                    99L,
                    com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_LOAD_FAILED,
                ),
            ),
        )
    }

    // Mutation caught: selection remains a SwitchGhost after the outgoing generation was unloaded and target failed.
    @Test
    fun idleNoGenerationSelectionStartsAnotherInstalledGhost() {
        val target = RuntimeGhostMetadata("fallback", "C:/ghost/fallback", null, null, "C:/ghost/fallback/readme.txt")
        val snapshot = com.cattailsw.nanidroid.runtime.RuntimeSnapshot.initial().copy(
            revision = 50L,
            phase = GhostRuntimePhase.Idle,
            generation = null,
            catalog = RuntimeCatalogState.Ready(3L, listOf(target), emptyMap()),
        )

        val command = ghostSelectionCommand(
            snapshot,
            RuntimeHostLease(RuntimeHostId(5L), 7L),
            "fallback",
        )

        assertTrue(command is RuntimeCommand.StartGhost)
        assertEquals("fallback", (command as RuntimeCommand.StartGhost).ghostId)
        assertEquals(java.io.File(target.canonicalRootPath).path, command.canonicalRoot.path)
    }

    // Mutation caught: a restored selection starts during an Idle no-generation terminal parent/exit.
    @Test
    fun idleSelectionIsRejectedWhileParentOrExitIsPresent() {
        val target = RuntimeGhostMetadata("fallback", "C:/ghost/fallback", null, null, "C:/ghost/fallback/readme.txt")
        val base = com.cattailsw.nanidroid.runtime.RuntimeSnapshot.initial().copy(
            phase = GhostRuntimePhase.Idle,
            catalog = RuntimeCatalogState.Ready(3L, listOf(target), emptyMap()),
        )
        val lease = RuntimeHostLease(RuntimeHostId(5L), 7L)

        assertNull(ghostSelectionCommand(base.copy(modeIdentity = base.modeIdentity.copy(parentOperationId = 2L)), lease, "fallback"))
        assertNull(
            ghostSelectionCommand(
                base.copy(exit = com.cattailsw.nanidroid.runtime.RuntimeExitSnapshot(2L, null, null)),
                lease,
                "fallback",
            ),
        )
    }

    private fun next(
        attempts: StartupCandidateAttempts,
        epoch: Long,
        phase: GhostRuntimePhase,
        generation: Long? = null,
        parentOperationId: Long? = null,
        exitPresent: Boolean = false,
        revision: Long,
        notice: com.cattailsw.nanidroid.runtime.RuntimeNotice? = null,
    ): String? = attempts.nextCandidate(
        epoch,
        phase,
        generation,
        parentOperationId,
        exitPresent,
        revision,
        notice,
    )
}
