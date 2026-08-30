package com.cattailsw.nanidroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHostStateTest {
    @Test
    fun strictlyNewerLifecycleEpochsMigrateResumedAndTopStateInOrder() {
        val registered = lease(1L, 1L)
        val resumed = lease(1L, 2L)
        val top = lease(1L, 3L)
        var transition = reduce(RuntimeHostState.empty(), RuntimeCommand.RegisterHost(registered))

        transition = reduce(transition.state, RuntimeCommand.SetResumed(resumed, true))
        assertEquals(2L, transition.state.registeredEpochs[registered.hostId])
        assertEquals(setOf(resumed), transition.state.resumed)

        transition = reduce(transition.state, RuntimeCommand.SetTopResumed(top, true))
        assertEquals(3L, transition.state.registeredEpochs[registered.hostId])
        assertEquals(setOf(top), transition.state.resumed)
        assertEquals(top, transition.state.topResumed)

        val equalEpoch = reduce(transition.state, RuntimeCommand.SetTopResumed(top, false))
        val olderEpoch = reduce(transition.state, RuntimeCommand.UnregisterHost(resumed))
        assertEquals(transition.state, equalEpoch.state)
        assertEquals(transition.state, olderEpoch.state)
        assertTrue(equalEpoch.effects.isEmpty())
        assertTrue(olderEpoch.effects.isEmpty())
    }

    @Test
    fun topResumedFalsePreservesResumedStatusForANewerFocusEpoch() {
        val top = lease(1L, 3L)
        var transition = registerAndFocus(RuntimeHostState.empty(), top)

        val notTop = lease(1L, 4L)
        transition = reduce(transition.state, RuntimeCommand.SetTopResumed(notTop, false))
        assertNull(transition.state.topResumed)
        assertEquals(setOf(notTop), transition.state.resumed)

        val focusedAgain = lease(1L, 5L)
        transition = reduce(transition.state, RuntimeCommand.SetTopResumed(focusedAgain, true))
        assertEquals(focusedAgain, transition.state.topResumed)
        assertEquals(setOf(focusedAgain), transition.state.resumed)
    }

    @Test
    fun unregisterTombstoneBlocksResurrectionUntilStrictlyNewerRegistration() {
        val top = lease(1L, 3L)
        var transition = registerAndFocus(RuntimeHostState.empty(), top)
        val unregistered = lease(1L, 4L)
        transition = reduce(transition.state, RuntimeCommand.UnregisterHost(unregistered))
        assertEquals(4L, transition.state.registeredEpochs[top.hostId])
        assertNull(transition.state.topResumed)
        assertTrue(transition.state.resumed.isEmpty())

        val sameEpochResume = reduce(transition.state, RuntimeCommand.SetResumed(unregistered, true))
        val olderRegister = reduce(transition.state, RuntimeCommand.RegisterHost(top))
        assertEquals(transition.state, sameEpochResume.state)
        assertEquals(transition.state, olderRegister.state)

        transition = reduce(transition.state, RuntimeCommand.RegisterHost(lease(1L, 5L)))
        transition = reduce(transition.state, RuntimeCommand.SetResumed(lease(1L, 6L), true))
        transition = reduce(transition.state, RuntimeCommand.SetTopResumed(lease(1L, 7L), true))
        assertEquals(lease(1L, 7L), transition.state.topResumed)
    }

    @Test
    fun newerRegistrationSupersedesTheOldLeaseAndRejectsStaleLifecycleCommands() {
        val old = lease(hostId = 1L, epoch = 3L)
        val newer = lease(hostId = 1L, epoch = 4L)
        var transition = registerAndFocus(RuntimeHostState.empty(), old)
        transition = reduce(transition.state, RuntimeCommand.RegisterHost(newer))

        assertEquals(mapOf(RuntimeHostId(1L) to 4L), transition.state.registeredEpochs)
        assertNull(transition.state.topResumed)

        transition = reduce(transition.state, RuntimeCommand.SetResumed(old, true))
        transition = reduce(transition.state, RuntimeCommand.SetTopResumed(old, true))
        transition = reduce(transition.state, RuntimeCommand.UnregisterHost(old))

        assertEquals(mapOf(RuntimeHostId(1L) to 4L), transition.state.registeredEpochs)
        assertTrue(transition.state.resumed.isEmpty())
        assertNull(transition.state.topResumed)
    }

    @Test
    fun onlyARegisteredResumedLeaseCanBecomeTopResumed() {
        val hostId = 1L
        var transition = reduce(RuntimeHostState.empty(), RuntimeCommand.SetTopResumed(lease(hostId, 1L), true))
        assertNull(transition.state.topResumed)

        transition = reduce(transition.state, RuntimeCommand.RegisterHost(lease(hostId, 1L)))
        transition = reduce(transition.state, RuntimeCommand.SetTopResumed(lease(hostId, 2L), true))
        assertNull(transition.state.topResumed)

        transition = reduce(transition.state, RuntimeCommand.SetResumed(lease(hostId, 3L), true))
        transition = reduce(transition.state, RuntimeCommand.SetTopResumed(lease(hostId, 4L), true))
        assertEquals(lease(hostId, 4L), transition.state.topResumed)
    }

    @Test
    fun cuePayloadRetainsExactEmissionSurfaceIdentity() {
        val host = lease(1L, 3L)
        val target = RuntimeSurfaceIdentity(7L, GhostSpeaker.SAKURA, "12", 4L)
        val focused = registerAndFocus(RuntimeHostState.empty(), host).state

        val transition = RuntimeHostReducer.reduce(
            focused,
            RuntimeHostInput.Cue(
                cueId = 9L,
                payload = RuntimeCuePayload(target, RuntimeCueKind.ONE_SHOT, "3"),
            ),
        )

        assertEquals(target, transition.state.cues.single().target)
        assertEquals(host, transition.state.cues.single().hostLease)
    }

    @Test
    fun newerTopResumedFalseFencesAndRevokesAnOlderTopLease() {
        val old = lease(1L, 3L)
        val newer = lease(1L, 4L)
        var state = registerAndFocus(RuntimeHostState.empty(), old).state
        for (id in 1L..64L) state = cue(state, id).state

        val transition = reduce(state, RuntimeCommand.SetTopResumed(newer, false))

        assertEquals(4L, transition.state.registeredEpochs[old.hostId])
        assertNull(transition.state.topResumed)
        assertEquals(setOf(newer), transition.state.resumed)
        assertTrue(transition.state.cues.isEmpty())
        assertFalse(transition.state.playerBackpressured)
        assertEquals(listOf(RuntimeHostEffect.BackpressureChanged(false)), transition.effects)
    }

    @Test
    fun newerUnregisterFencesAndRevokesAnOlderTopLease() {
        val old = lease(1L, 3L)
        val newer = lease(1L, 4L)
        val state = registerAndFocus(RuntimeHostState.empty(), old).state

        val transition = reduce(state, RuntimeCommand.UnregisterHost(newer))

        assertEquals(4L, transition.state.registeredEpochs[old.hostId])
        assertNull(transition.state.topResumed)
        assertTrue(transition.state.resumed.isEmpty())
        val stale = reduce(transition.state, RuntimeCommand.SetTopResumed(old, true))
        assertNull(stale.state.topResumed)
    }

    @Test
    fun exitOfferMovesBetweenValidHostsOnlyBeforeItIsClaimed() {
        val first = lease(1L, 3L)
        val second = lease(2L, 3L)
        var transition = registerAndFocus(RuntimeHostState.empty(), first)
        transition = registerAndFocus(transition.state, second)
        transition = RuntimeHostReducer.reduce(
            transition.state,
            RuntimeHostInput.OfferExit(operationId = 91L, generation = 7L),
        )
        val firstOffer = transition.effects.single() as RuntimeHostEffect.OfferExit
        assertEquals(second, firstOffer.lease.hostLease)

        val secondNotTop = second.copy(hostEpoch = second.hostEpoch + 1L)
        transition = reduce(transition.state, RuntimeCommand.SetTopResumed(secondNotTop, false))
        assertNull(transition.state.exit?.offeredLease)

        val firstFocusedAgain = first.copy(hostEpoch = first.hostEpoch + 1L)
        transition = reduce(transition.state, RuntimeCommand.SetTopResumed(firstFocusedAgain, true))
        val reassigned = transition.effects.single() as RuntimeHostEffect.OfferExit
        assertEquals(firstFocusedAgain, reassigned.lease.hostLease)
        assertTrue(reassigned.lease.leaseId > firstOffer.lease.leaseId)
    }

    @Test
    fun exitClaimAndAcknowledgementRequireTheExactInternalClaim() {
        val host = lease(1L, 3L)
        val offered = offeredExit(registerAndFocus(RuntimeHostState.empty(), host).state)

        val stale = offered.copy(leaseId = offered.leaseId + 1L)
        var transition = reduce(stateWith(offered), RuntimeCommand.ClaimExit(stale))
        assertEquals(offered, transition.state.exit?.offeredLease)
        assertNull(transition.state.claimedExitLease)

        transition = reduce(transition.state, RuntimeCommand.ClaimExit(offered))
        assertNull(transition.state.exit?.offeredLease)
        assertEquals(offered, transition.state.claimedExitLease)

        transition = reduce(transition.state, RuntimeCommand.AcknowledgeExit(stale))
        assertEquals(offered.operationId, transition.state.exit?.operationId)

        transition = reduce(transition.state, RuntimeCommand.AcknowledgeExit(offered))
        assertNull(transition.state.exit)
        assertNull(transition.state.claimedExitLease)
    }

    @Test
    fun claimThenAckPrecedeFinishTriggeredLifecycleLoss() {
        val host = lease(1L, 3L)
        val offered = offeredExit(registerAndFocus(RuntimeHostState.empty(), host).state)
        val afterClaim = reduce(stateWith(offered), RuntimeCommand.ClaimExit(offered))
        val afterAck = reduce(afterClaim.state, RuntimeCommand.AcknowledgeExit(offered))
        val afterLoss = reduce(
            afterAck.state,
            RuntimeCommand.SetTopResumed(host.copy(hostEpoch = host.hostEpoch + 1L), false),
        )

        assertNull(afterLoss.state.exit)
        assertTrue(afterLoss.effects.none { it is RuntimeHostEffect.OfferExit })
    }

    @Test
    fun lifecycleLossCannotReassignAnAlreadyClaimedExit() {
        val first = lease(1L, 3L)
        val second = lease(2L, 3L)
        var state = registerAndFocus(RuntimeHostState.empty(), first).state
        state = registerAndFocus(state, second).state
        val offered = offeredExit(state)
        state = reduce(stateWith(offered), RuntimeCommand.ClaimExit(offered)).state

        var transition = reduce(
            state,
            RuntimeCommand.SetTopResumed(second.copy(hostEpoch = second.hostEpoch + 1L), false),
        )
        transition = reduce(
            transition.state,
            RuntimeCommand.SetTopResumed(first.copy(hostEpoch = first.hostEpoch + 1L), true),
        )

        assertEquals(offered, transition.state.claimedExitLease)
        assertNull(transition.state.exit?.offeredLease)
        assertTrue(transition.effects.none { it is RuntimeHostEffect.OfferExit })
    }

    @Test
    fun cueAcknowledgementConsumesOnlyAnExactContiguousPrefix() {
        val host = lease(1L, 3L)
        var state = registerAndFocus(RuntimeHostState.empty(), host).state
        state = cue(state, 10L).state
        state = cue(state, 20L).state
        state = cue(state, 30L).state

        var transition = reduce(state, RuntimeCommand.AcknowledgeCues(host, 19L))
        assertEquals(listOf(10L, 20L, 30L), transition.state.cues.map { it.cueId })

        transition = reduce(transition.state, RuntimeCommand.AcknowledgeCues(host, 20L))
        assertEquals(listOf(30L), transition.state.cues.map { it.cueId })

        transition = reduce(transition.state, RuntimeCommand.AcknowledgeCues(host, 20L))
        assertEquals(listOf(30L), transition.state.cues.map { it.cueId })
    }

    @Test
    fun activeHostCueCapacityPausesAtSixtyFourAndResumesAfterAcknowledgement() {
        val host = lease(1L, 3L)
        var state = registerAndFocus(RuntimeHostState.empty(), host).state
        var last = RuntimeHostTransition(state, emptyList())
        for (id in 1L..64L) {
            last = cue(last.state, id)
        }

        assertEquals(64, last.state.cues.size)
        assertTrue(last.state.playerBackpressured)
        assertEquals(listOf(RuntimeHostEffect.BackpressureChanged(true)), last.effects)

        val overflow = cue(last.state, 65L)
        assertEquals(64, overflow.state.cues.size)
        assertTrue(overflow.effects.isEmpty())

        val acknowledged = reduce(
            overflow.state,
            RuntimeCommand.AcknowledgeCues(host, throughCueId = 1L),
        )
        assertEquals(63, acknowledged.state.cues.size)
        assertFalse(acknowledged.state.playerBackpressured)
        assertEquals(listOf(RuntimeHostEffect.BackpressureChanged(false)), acknowledged.effects)
    }

    @Test
    fun hostLossExpiresCuesAndClearsBackpressure() {
        val host = lease(1L, 3L)
        var state = registerAndFocus(RuntimeHostState.empty(), host).state
        for (id in 1L..64L) state = cue(state, id).state

        val transition = reduce(
            state,
            RuntimeCommand.UnregisterHost(host.copy(hostEpoch = host.hostEpoch + 1L)),
        )

        assertTrue(transition.state.cues.isEmpty())
        assertFalse(transition.state.playerBackpressured)
        assertEquals(listOf(RuntimeHostEffect.BackpressureChanged(false)), transition.effects)
    }

    @Test
    fun hostlessCuesExpireWithoutBackpressure() {
        val final = (1L..65L).fold(RuntimeHostState.empty()) { current, id ->
            cue(current, id).state
        }

        assertTrue(final.cues.isEmpty())
        assertFalse(final.playerBackpressured)
    }

    @Test
    fun returnedHostCollectionsAreCopiedAndJavaUnmodifiable() {
        val epochs = linkedMapOf(RuntimeHostId(1L) to 1L)
        val activeHostIds = linkedSetOf(RuntimeHostId(1L))
        val resumed = linkedSetOf(lease(1L, 1L))
        val cues = arrayListOf<RuntimePresentationCue>()
        val source = RuntimeHostState(
            registeredEpochs = epochs,
            activeHostIds = activeHostIds,
            resumed = resumed,
            topResumed = null,
            nextExitLeaseId = 1L,
            exit = null,
            claimedExitLease = null,
            cues = cues,
            playerBackpressured = false,
        )

        val returned = RuntimeHostReducer.reduce(
            source,
            RuntimeHostInput.Command(RuntimeCommand.ActivateChoice(dialogueKey(), lease(1L, 1L))),
        ).state
        epochs.clear()
        activeHostIds.clear()
        resumed.clear()
        cues += presentationCue(1L, lease(1L, 1L))

        assertEquals(1, returned.registeredEpochs.size)
        assertEquals(1, returned.activeHostIds.size)
        assertEquals(1, returned.resumed.size)
        assertTrue(returned.cues.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) {
            (returned.registeredEpochs as MutableMap<RuntimeHostId, Long>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (returned.activeHostIds as MutableSet<RuntimeHostId>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (returned.resumed as MutableSet<RuntimeHostLease>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (returned.cues as MutableList<RuntimePresentationCue>).clear()
        }
    }

    private fun offeredExit(state: RuntimeHostState): RuntimeExitLease =
        (RuntimeHostReducer.reduce(state, RuntimeHostInput.OfferExit(91L, 7L)).effects.single()
            as RuntimeHostEffect.OfferExit).lease

    private fun stateWith(lease: RuntimeExitLease): RuntimeHostState {
        val focused = registerAndFocus(RuntimeHostState.empty(), lease.hostLease).state
        return RuntimeHostReducer.reduce(
            focused,
            RuntimeHostInput.OfferExit(lease.operationId, lease.generation),
        ).state
    }

    private fun registerAndFocus(state: RuntimeHostState, host: RuntimeHostLease): RuntimeHostTransition {
        var transition = reduce(
            state,
            RuntimeCommand.RegisterHost(host.copy(hostEpoch = host.hostEpoch - 2L)),
        )
        transition = reduce(
            transition.state,
            RuntimeCommand.SetResumed(host.copy(hostEpoch = host.hostEpoch - 1L), true),
        )
        return reduce(transition.state, RuntimeCommand.SetTopResumed(host, true))
    }

    private fun cue(state: RuntimeHostState, id: Long): RuntimeHostTransition = RuntimeHostReducer.reduce(
        state,
        RuntimeHostInput.Cue(
            cueId = id,
            payload = RuntimeCuePayload(
                RuntimeSurfaceIdentity(7L, GhostSpeaker.SAKURA, "0", 0L),
                RuntimeCueKind.ONE_SHOT,
                "1",
            ),
        ),
    )

    private fun reduce(state: RuntimeHostState, command: RuntimeCommand): RuntimeHostTransition =
        RuntimeHostReducer.reduce(state, RuntimeHostInput.Command(command))

    private fun lease(hostId: Long, epoch: Long): RuntimeHostLease =
        RuntimeHostLease(RuntimeHostId(hostId), epoch)

    private fun presentationCue(id: Long, host: RuntimeHostLease) = RuntimePresentationCue(
        cueId = id,
        target = RuntimeSurfaceIdentity(7L, GhostSpeaker.SAKURA, "0", 0L),
        hostLease = host,
        kind = RuntimeCueKind.ONE_SHOT,
        animationId = "1",
    )

    private fun dialogueKey() = com.cattailsw.nanidroid.runtime.dialogue.DialogueActionKey(
        generation = 1L,
        incarnation = 1L,
        actionId = 1L,
    )
}
