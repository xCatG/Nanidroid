package com.cattailsw.nanidroid.runtime

import java.util.Collections

internal data class RuntimeHostState(
    val registeredEpochs: Map<RuntimeHostId, Long>,
    val activeHostIds: Set<RuntimeHostId>,
    val resumed: Set<RuntimeHostLease>,
    val topResumed: RuntimeHostLease?,
    val nextExitLeaseId: Long,
    val exit: RuntimeExitSnapshot?,
    val claimedExitLease: RuntimeExitLease?,
    val cues: List<RuntimePresentationCue>,
    val playerBackpressured: Boolean,
) {
    companion object {
        fun empty(): RuntimeHostState = RuntimeHostState(
            registeredEpochs = emptyMap(),
            activeHostIds = emptySet(),
            resumed = emptySet(),
            topResumed = null,
            nextExitLeaseId = 1L,
            exit = null,
            claimedExitLease = null,
            cues = emptyList(),
            playerBackpressured = false,
        ).frozenCopy()
    }
}

internal data class RuntimeCuePayload(
    val target: RuntimeSurfaceIdentity,
    val kind: RuntimeCueKind,
    val animationId: String?,
)

internal sealed interface RuntimeHostInput {
    data class Command(val command: RuntimeCommand) : RuntimeHostInput

    data class OfferExit(val operationId: Long, val generation: Long?) : RuntimeHostInput

    data class Cue(val cueId: Long, val payload: RuntimeCuePayload) : RuntimeHostInput
}

internal data class RuntimeHostTransition(
    val state: RuntimeHostState,
    val effects: List<RuntimeHostEffect>,
)

internal sealed interface RuntimeHostEffect {
    data class OfferExit(val lease: RuntimeExitLease) : RuntimeHostEffect

    data class BackpressureChanged(val paused: Boolean) : RuntimeHostEffect
}

internal object RuntimeHostReducer {
    private const val CUE_CAPACITY = 64

    fun reduce(state: RuntimeHostState, input: RuntimeHostInput): RuntimeHostTransition {
        val transition = when (input) {
            is RuntimeHostInput.Command -> reduceCommand(state, input.command)
            is RuntimeHostInput.OfferExit -> offerExit(state, input)
            is RuntimeHostInput.Cue -> enqueueCue(state, input)
        }
        return RuntimeHostTransition(
            state = transition.state.frozenCopy(),
            effects = frozenList(transition.effects),
        )
    }

    private fun reduceCommand(
        state: RuntimeHostState,
        command: RuntimeCommand,
    ): RuntimeHostTransition = when (command) {
        is RuntimeCommand.RegisterHost -> register(state, command.lease)
        is RuntimeCommand.SetResumed -> setResumed(state, command.lease, command.resumed)
        is RuntimeCommand.SetTopResumed -> setTopResumed(state, command.lease, command.topResumed)
        is RuntimeCommand.UnregisterHost -> unregister(state, command.lease)
        is RuntimeCommand.ClaimExit -> claimExit(state, command.lease)
        is RuntimeCommand.AcknowledgeExit -> acknowledgeExit(state, command.lease)
        is RuntimeCommand.AcknowledgeCues -> acknowledgeCues(state, command.host, command.throughCueId)
        else -> unchanged(state)
    }

    private fun register(state: RuntimeHostState, lease: RuntimeHostLease): RuntimeHostTransition {
        val previousEpoch = state.registeredEpochs[lease.hostId]
        if (previousEpoch != null && lease.hostEpoch <= previousEpoch) return unchanged(state)

        var next = state.copy(
            registeredEpochs = state.registeredEpochs + (lease.hostId to lease.hostEpoch),
            activeHostIds = state.activeHostIds + lease.hostId,
            resumed = state.resumed.filterNot { it.hostId == lease.hostId }.toSet(),
        )
        val effects = mutableListOf<RuntimeHostEffect>()
        if (state.topResumed?.hostId == lease.hostId) {
            next = loseTopHost(next)
            appendBackpressureRelease(state, effects)
        }
        return RuntimeHostTransition(next, effects)
    }

    private fun setResumed(
        state: RuntimeHostState,
        lease: RuntimeHostLease,
        resumed: Boolean,
    ): RuntimeHostTransition {
        if (!state.canAdvance(lease)) return unchanged(state)
        val wasTop = state.topResumed?.hostId == lease.hostId
        val effects = mutableListOf<RuntimeHostEffect>()
        var next = state
        if (wasTop) {
            next = loseTopHost(next)
            appendBackpressureRelease(state, effects)
        }
        next = next.copy(
            registeredEpochs = next.registeredEpochs + (lease.hostId to lease.hostEpoch),
            resumed = next.resumed.filterNot { it.hostId == lease.hostId }.toSet().let {
                if (resumed) it + lease else it
            },
            topResumed = if (wasTop && resumed) lease else next.topResumed,
        )
        if (wasTop && resumed) {
            val offered = offerExistingTerminal(next)
            next = offered.state
            effects += offered.effects
        }
        return RuntimeHostTransition(next, effects)
    }

    private fun setTopResumed(
        state: RuntimeHostState,
        lease: RuntimeHostLease,
        topResumed: Boolean,
    ): RuntimeHostTransition {
        if (!state.canAdvance(lease)) return unchanged(state)
        val wasResumed = state.resumed.any { it.hostId == lease.hostId }
        val effects = mutableListOf<RuntimeHostEffect>()
        val supersedesTop = if (topResumed) {
            wasResumed && state.topResumed != null
        } else {
            state.topResumed?.hostId == lease.hostId
        }
        var next = if (supersedesTop) {
            appendBackpressureRelease(state, effects)
            loseTopHost(state)
        } else {
            state
        }
        next = next.copy(
            registeredEpochs = next.registeredEpochs + (lease.hostId to lease.hostEpoch),
            resumed = next.resumed.filterNot { it.hostId == lease.hostId }.toSet().let {
                if (wasResumed) it + lease else it
            },
            topResumed = if (topResumed && wasResumed) lease else next.topResumed,
        )
        if (topResumed && wasResumed) {
            val offered = offerExistingTerminal(next)
            next = offered.state
            effects += offered.effects
        }
        return RuntimeHostTransition(next, effects)
    }

    private fun unregister(state: RuntimeHostState, lease: RuntimeHostLease): RuntimeHostTransition {
        val currentEpoch = state.registeredEpochs[lease.hostId] ?: return unchanged(state)
        if (lease.hostEpoch <= currentEpoch) return unchanged(state)
        var next = state.copy(
            registeredEpochs = state.registeredEpochs + (lease.hostId to lease.hostEpoch),
            activeHostIds = state.activeHostIds - lease.hostId,
            resumed = state.resumed.filterNot { it.hostId == lease.hostId }.toSet(),
        )
        val effects = mutableListOf<RuntimeHostEffect>()
        if (state.topResumed?.hostId == lease.hostId) {
            next = loseTopHost(next)
            appendBackpressureRelease(state, effects)
        }
        return RuntimeHostTransition(next, effects)
    }

    private fun offerExit(
        state: RuntimeHostState,
        input: RuntimeHostInput.OfferExit,
    ): RuntimeHostTransition {
        if (state.exit != null) return unchanged(state)
        val terminal = state.copy(
            exit = RuntimeExitSnapshot(input.operationId, input.generation, offeredLease = null),
            claimedExitLease = null,
        )
        return offerExistingTerminal(terminal)
    }

    private fun offerExistingTerminal(state: RuntimeHostState): RuntimeHostTransition {
        val terminal = state.exit ?: return unchanged(state)
        val host = state.topResumed?.takeIf { state.isValidTop(it) } ?: return unchanged(state)
        if (terminal.offeredLease != null || state.claimedExitLease != null) return unchanged(state)
        val lease = RuntimeExitLease(
            operationId = terminal.operationId,
            leaseId = state.nextExitLeaseId,
            generation = terminal.generation,
            hostLease = host,
        )
        return RuntimeHostTransition(
            state.copy(
                nextExitLeaseId = state.nextExitLeaseId + 1L,
                exit = terminal.copy(offeredLease = lease),
            ),
            listOf(RuntimeHostEffect.OfferExit(lease)),
        )
    }

    private fun claimExit(state: RuntimeHostState, lease: RuntimeExitLease): RuntimeHostTransition {
        val exit = state.exit ?: return unchanged(state)
        if (exit.offeredLease != lease || state.claimedExitLease != null || !state.isValidTop(lease.hostLease)) {
            return unchanged(state)
        }
        return RuntimeHostTransition(
            state.copy(
                exit = exit.copy(offeredLease = null),
                claimedExitLease = lease,
            ),
            emptyList(),
        )
    }

    private fun acknowledgeExit(state: RuntimeHostState, lease: RuntimeExitLease): RuntimeHostTransition {
        val exit = state.exit ?: return unchanged(state)
        if (state.claimedExitLease != lease ||
            exit.operationId != lease.operationId ||
            exit.generation != lease.generation
        ) {
            return unchanged(state)
        }
        return RuntimeHostTransition(
            state.copy(exit = null, claimedExitLease = null),
            emptyList(),
        )
    }

    private fun enqueueCue(state: RuntimeHostState, input: RuntimeHostInput.Cue): RuntimeHostTransition {
        val host = state.topResumed?.takeIf { state.isValidTop(it) } ?: return unchanged(
            if (state.cues.isEmpty() && !state.playerBackpressured) state else loseTopHost(state),
        )
        if (state.cues.size >= CUE_CAPACITY) return unchanged(state)
        val cue = RuntimePresentationCue(
            cueId = input.cueId,
            target = input.payload.target,
            hostLease = host,
            kind = input.payload.kind,
            animationId = input.payload.animationId,
        )
        val cues = state.cues + cue
        val paused = cues.size == CUE_CAPACITY
        return RuntimeHostTransition(
            state.copy(cues = cues, playerBackpressured = paused),
            if (paused && !state.playerBackpressured) {
                listOf(RuntimeHostEffect.BackpressureChanged(true))
            } else {
                emptyList()
            },
        )
    }

    private fun acknowledgeCues(
        state: RuntimeHostState,
        host: RuntimeHostLease,
        throughCueId: Long,
    ): RuntimeHostTransition {
        if (!state.isValidTop(host)) return unchanged(state)
        val index = state.cues.indexOfFirst { it.hostLease == host && it.cueId == throughCueId }
        if (index < 0) return unchanged(state)
        val remaining = state.cues.drop(index + 1)
        val released = state.playerBackpressured && remaining.size < CUE_CAPACITY
        return RuntimeHostTransition(
            state.copy(cues = remaining, playerBackpressured = state.playerBackpressured && !released),
            if (released) listOf(RuntimeHostEffect.BackpressureChanged(false)) else emptyList(),
        )
    }

    private fun loseTopHost(state: RuntimeHostState): RuntimeHostState = state.copy(
        topResumed = null,
        exit = state.exit?.let { exit ->
            if (state.claimedExitLease == null) exit.copy(offeredLease = null) else exit
        },
        cues = emptyList(),
        playerBackpressured = false,
    )

    private fun appendBackpressureRelease(
        state: RuntimeHostState,
        effects: MutableList<RuntimeHostEffect>,
    ) {
        if (state.playerBackpressured) effects += RuntimeHostEffect.BackpressureChanged(false)
    }

    private fun RuntimeHostState.isCurrent(lease: RuntimeHostLease): Boolean =
        lease.hostId in activeHostIds && registeredEpochs[lease.hostId] == lease.hostEpoch

    private fun RuntimeHostState.canAdvance(lease: RuntimeHostLease): Boolean {
        val currentEpoch = registeredEpochs[lease.hostId] ?: return false
        return lease.hostId in activeHostIds && lease.hostEpoch > currentEpoch
    }

    private fun RuntimeHostState.isValidTop(lease: RuntimeHostLease): Boolean =
        topResumed == lease && isCurrent(lease) && lease in resumed

    private fun unchanged(state: RuntimeHostState): RuntimeHostTransition =
        RuntimeHostTransition(state, emptyList())
}

private fun RuntimeHostState.frozenCopy(): RuntimeHostState = copy(
    registeredEpochs = frozenMap(registeredEpochs),
    activeHostIds = frozenSet(activeHostIds),
    resumed = frozenSet(resumed),
    cues = frozenList(cues.map { it.copy(hostLease = it.hostLease.copy()) }),
    exit = exit?.copy(offeredLease = exit.offeredLease?.copy(hostLease = exit.offeredLease.hostLease.copy())),
    claimedExitLease = claimedExitLease?.copy(hostLease = claimedExitLease.hostLease.copy()),
)

private fun <T> frozenList(source: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))

private fun <T> frozenSet(source: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(source))

private fun <K, V> frozenMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))
