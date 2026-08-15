package com.cattailsw.nanidroid

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class GhostConstructionReservation internal constructor(
    private val coordinator: GhostSessionCoordinator,
    internal val ghostId: String,
    internal val canonicalRoot: File,
    internal val generation: Long,
) {
    fun bind(ghost: Ghost): ReservedGhost = coordinator.bind(this, ghost)

    fun failConstruction() = coordinator.failConstruction(this)
}

internal class ReservedGhost internal constructor(
    val ghost: Ghost,
    internal val ghostId: String,
    internal val canonicalRoot: File,
    internal val generation: Long,
    internal val reusedActive: Boolean = false,
    internal val coordinator: GhostSessionCoordinator,
)

/**
 * Serializes process-global native SHIORI ownership with canonical ghost-root mutations.
 * Lock order is filesystem lock -> sorted root state(s) -> global session -> runner -> native.
 */
internal class GhostSessionCoordinator {
    private class RootState {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        val monitor = java.lang.Object()
        var reservation: ReservedGhost? = null
        var construction: GhostConstructionReservation? = null
        var active: Ghost? = null
        var poison: Throwable? = null
    }

    private data class GlobalOwner(
        val generation: Long,
        val ghost: Ghost?,
        val phase: Phase,
    )

    private enum class Phase { CONSTRUCTING, RESERVED, ACTIVE, MUTATING, POISONED }

    private val roots = ConcurrentHashMap<String, RootState>()
    private val generations = AtomicLong()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val globalMonitor = java.lang.Object()
    private var globalOwner: GlobalOwner? = null
    private var globalPoison: Throwable? = null

    fun beginConstruction(ghostId: String, ghostRoot: File): GhostConstructionReservation {
        val root = ghostRoot.canonicalFile
        require(root.name == ghostId) { "ghost ID does not match canonical root" }
        val state = state(root)
        synchronized(state.monitor) {
            if (state.active != null) throw IllegalStateException("ghost root is already active")
            state.poison?.let { throw IllegalStateException("ghost root session is poisoned", it) }
            if (state.construction != null || state.reservation != null) {
                throw IllegalStateException("ghost root already has a pending construction")
            }
            synchronized(globalMonitor) {
                globalPoison?.let { throw IllegalStateException("process SHIORI session is poisoned", it) }
                while (globalOwner != null) {
                    if (globalOwner?.phase == Phase.ACTIVE) {
                        throw IllegalStateException("active SHIORI owner must be unloaded before construction")
                    }
                    try {
                        globalMonitor.wait(50L)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IllegalStateException("ghost construction was interrupted", interrupted)
                    }
                }
                val reservation = GhostConstructionReservation(
                    this,
                    ghostId,
                    root,
                    generations.incrementAndGet(),
                )
                state.construction = reservation
                globalOwner = GlobalOwner(reservation.generation, null, Phase.CONSTRUCTING)
                return reservation
            }
        }
    }

    fun bind(construction: GhostConstructionReservation, ghost: Ghost): ReservedGhost {
        val identity = identity(ghost)
        if (identity.first != construction.ghostId || identity.second != construction.canonicalRoot) {
            rejectConstructedGhost(construction, ghost)
            throw IllegalArgumentException("constructed ghost does not match its reservation")
        }
        val state = state(construction.canonicalRoot)
        synchronized(state.monitor) {
            synchronized(globalMonitor) {
                if (state.construction !== construction || globalOwner?.generation != construction.generation) {
                    poisonStaleConstructedGhost(state, ghost)
                    throw IllegalStateException("ghost construction reservation is no longer current")
                }
                val reserved = ReservedGhost(
                    ghost,
                    construction.ghostId,
                    construction.canonicalRoot,
                    construction.generation,
                    coordinator = this,
                )
                state.construction = null
                state.reservation = reserved
                globalOwner = GlobalOwner(reserved.generation, ghost, Phase.RESERVED)
                state.monitor.notifyAll()
                return reserved
            }
        }
    }

    fun failConstruction(construction: GhostConstructionReservation) {
        val state = state(construction.canonicalRoot)
        synchronized(state.monitor) {
            synchronized(globalMonitor) {
                if (state.construction === construction) state.construction = null
                if (globalOwner?.generation == construction.generation) globalOwner = null
                state.monitor.notifyAll()
                globalMonitor.notifyAll()
            }
        }
    }

    private fun rejectConstructedGhost(construction: GhostConstructionReservation, ghost: Ghost) {
        val state = state(construction.canonicalRoot)
        synchronized(state.monitor) {
            synchronized(globalMonitor) {
                if (state.construction !== construction || globalOwner?.generation != construction.generation) return
                try {
                    ghost.unload()
                } catch (error: Exception) {
                    state.poison = error
                    poisonGlobal(construction.generation, ghost, error)
                    return
                } catch (error: LinkageError) {
                    state.poison = error
                    poisonGlobal(construction.generation, ghost, error)
                    return
                } finally {
                    state.construction = null
                    if (globalPoison == null) globalOwner = null
                    state.monitor.notifyAll()
                    globalMonitor.notifyAll()
                }
            }
        }
    }

    fun reuseActive(ghostId: String, ghostRoot: File): ReservedGhost? {
        val root = ghostRoot.canonicalFile
        if (root.name != ghostId) return null
        val state = state(root)
        synchronized(state.monitor) {
            synchronized(globalMonitor) {
                val active = state.active ?: return null
                val owner = globalOwner ?: return null
                if (active.getGhostId() != ghostId || owner.ghost !== active || owner.phase != Phase.ACTIVE) return null
                return ReservedGhost(active, ghostId, root, owner.generation, reusedActive = true, coordinator = this)
            }
        }
    }

    fun attach(
        reservation: ReservedGhost,
        outgoing: Ghost?,
        assign: () -> Unit,
    ): Boolean = withRootStates(listOfNotNull(outgoing?.let(::rootOf), reservation.canonicalRoot)) {
        if (reservation.coordinator !== this) return@withRootStates false
        val incomingState = state(reservation.canonicalRoot)
        synchronized(globalMonitor) {
            if (reservation.reusedActive) {
                if (
                    incomingState.active !== reservation.ghost ||
                    globalOwner?.generation != reservation.generation ||
                    globalOwner?.ghost !== reservation.ghost ||
                    globalOwner?.phase != Phase.ACTIVE
                ) return@withRootStates false
                assign()
                return@withRootStates true
            }
            if (
                incomingState.reservation !== reservation ||
                globalOwner?.generation != reservation.generation ||
                globalOwner?.ghost !== reservation.ghost ||
                incomingState.poison != null
            ) return@withRootStates false
            assign()
            outgoing?.let { old -> state(rootOf(old)).active = null }
            incomingState.active = reservation.ghost
            incomingState.reservation = null
            globalOwner = GlobalOwner(reservation.generation, reservation.ghost, Phase.ACTIVE)
            incomingState.monitor.notifyAll()
            true
        }
    }

    fun transition(outgoing: Ghost?, incoming: Ghost?, assign: () -> Unit) {
        val rootList = listOfNotNull(outgoing?.let(::rootOf), incoming?.let(::rootOf))
        withRootStates(rootList) {
            synchronized(globalMonitor) {
                val incomingState = incoming?.let { state(rootOf(it)) }
                if (incoming != null && incomingState?.reservation != null) {
                    throw IllegalStateException("reserved ghost requires its exact attachment token")
                }
                val owner = globalOwner
                globalPoison?.let { throw IllegalStateException("process SHIORI session is poisoned", it) }
                if (incoming !== outgoing && owner != null && owner.ghost === outgoing) {
                    if (incoming != null) {
                        val error = IllegalStateException(
                            "live SHIORI owner must be unloaded before constructing an unreserved replacement",
                        )
                        outgoing?.let { state(rootOf(it)).poison = error }
                        incomingState?.poison = error
                        poisonGlobal(owner.generation, null, error)
                        throw error
                    }
                    if (owner.phase != Phase.ACTIVE) {
                        throw IllegalStateException("cannot detach SHIORI while its generation is ${owner.phase}")
                    }
                    try {
                        outgoing?.unload()
                    } catch (error: Exception) {
                        state(rootOf(outgoing!!)).poison = error
                        poisonGlobal(owner.generation, outgoing, error)
                        throw IllegalStateException("cannot detach the live SHIORI owner", error)
                    } catch (error: LinkageError) {
                        state(rootOf(outgoing!!)).poison = error
                        poisonGlobal(owner.generation, outgoing, error)
                        throw IllegalStateException("cannot detach the live SHIORI owner", error)
                    }
                    globalOwner = null
                } else if (incoming !== outgoing && owner != null && owner.ghost !== outgoing) {
                    throw IllegalStateException("another SHIORI generation owns the process")
                }
                assign()
                outgoing?.let { state(rootOf(it)).active = null }
                if (incoming == null) {
                    if (globalOwner?.ghost === outgoing) globalOwner = null
                } else {
                    incomingState!!.active = incoming
                    globalOwner = GlobalOwner(generations.incrementAndGet(), incoming, Phase.ACTIVE)
                }
            }
        }
    }

    fun abandon(reservation: ReservedGhost): Boolean {
        if (reservation.reusedActive) return false
        val state = state(reservation.canonicalRoot)
        synchronized(state.monitor) {
            synchronized(globalMonitor) {
                if (state.reservation !== reservation) return false
                if (globalOwner?.generation == reservation.generation) {
                try {
                    reservation.ghost.unload()
                } catch (error: Exception) {
                    state.poison = error
                    poisonGlobal(reservation.generation, reservation.ghost, error)
                    state.monitor.notifyAll()
                    return false
                } catch (error: LinkageError) {
                    state.poison = error
                    poisonGlobal(reservation.generation, reservation.ghost, error)
                        state.monitor.notifyAll()
                        return false
                    }
                    globalOwner = null
                    globalMonitor.notifyAll()
                }
                state.reservation = null
                state.monitor.notifyAll()
                return true
            }
        }
    }

    fun markActiveUnloaded(ghost: Ghost): Boolean = markActiveUnloadedIf(ghost) { true }

    fun markActiveUnloadedIf(ghost: Ghost, shouldUnload: () -> Boolean): Boolean {
        val root = rootOf(ghost)
        val state = state(root)
        synchronized(state.monitor) {
            synchronized(globalMonitor) {
                globalPoison?.let { throw IllegalStateException("process SHIORI session is poisoned", it) }
                if (!shouldUnload()) return false
                if (state.active == null && globalOwner == null) return true
                if (state.active !== ghost || globalOwner?.ghost !== ghost) {
                    throw IllegalStateException("runner ghost does not own the process SHIORI session")
                }
                try {
                    ghost.unload()
                } catch (error: Exception) {
                    state.poison = error
                    poisonGlobal(globalOwner!!.generation, ghost, error)
                    throw IllegalStateException("cannot unload the active SHIORI owner", error)
                } catch (error: LinkageError) {
                    state.poison = error
                    poisonGlobal(globalOwner!!.generation, ghost, error)
                    throw IllegalStateException("cannot unload the active SHIORI owner", error)
                }
                globalOwner = null
                state.active = null
                state.monitor.notifyAll()
                globalMonitor.notifyAll()
                return true
            }
        }
    }

    fun <T> withGhostGate(ghost: Ghost, action: (live: Boolean) -> T): T {
        val state = state(rootOf(ghost))
        synchronized(state.monitor) {
            synchronized(globalMonitor) {
                return action(
                    state.active === ghost &&
                        globalOwner?.ghost === ghost &&
                        globalOwner?.phase == Phase.ACTIVE,
                )
            }
        }
    }

    fun <T> withMutation(
        ghostId: String,
        ghostRoot: File,
        shouldStop: () -> Boolean = { false },
        onStopped: () -> T,
        onFailure: (Throwable) -> T,
        onActiveSessionInvalidated: (Ghost) -> Unit = {},
        onActiveSessionReloaded: (Ghost, Boolean) -> Unit = { _, _ -> },
        action: () -> T,
    ): T {
        val root = ghostRoot.canonicalFile
        if (root.name != ghostId) return onFailure(IOException("ghost ID does not match canonical root"))
        val state = state(root)
        synchronized(state.monitor) {
            while (state.construction != null || state.reservation != null) {
                val pendingId = state.construction?.ghostId ?: state.reservation?.ghostId
                if (pendingId != ghostId) return onFailure(IOException("ghost root identity mismatch"))
                state.poison?.let { return onFailure(it) }
                if (shouldStop()) return onStopped()
                try {
                    state.monitor.wait(50L)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return onStopped()
                }
            }
            state.poison?.let { return onFailure(it) }
            val active = state.active
            if (active != null && active.getGhostId() != ghostId) {
                return onFailure(IOException("active ghost root identity mismatch"))
            }
            synchronized(globalMonitor) {
                globalPoison?.let { return onFailure(it) }
                val owner = globalOwner
                val liveActive = active?.takeIf { owner?.ghost === it && owner.phase == Phase.ACTIVE }
                val invalidationFailure = try {
                    liveActive?.let(onActiveSessionInvalidated)
                    null
                } catch (error: Exception) {
                    error
                } catch (error: LinkageError) {
                    error
                }
                if (invalidationFailure != null) return onFailure(invalidationFailure)
                val unloadFailure = try {
                    liveActive?.unload()
                    null
                } catch (error: Exception) {
                    error
                } catch (error: LinkageError) {
                    error
                }
                if (unloadFailure != null) {
                    state.poison = unloadFailure
                    poisonGlobal(owner!!.generation, liveActive, unloadFailure)
                    return onFailure(unloadFailure)
                }
                if (liveActive != null) {
                    globalOwner = owner?.copy(phase = Phase.MUTATING)
                }
                val result = try {
                    try {
                        action()
                    } catch (error: Exception) {
                        onFailure(error)
                    }
                } finally {
                    if (
                        liveActive != null &&
                        state.active === liveActive &&
                        globalOwner?.generation == owner?.generation &&
                        globalOwner?.phase == Phase.MUTATING
                    ) {
                        val reloaded = reload(liveActive)
                        if (reloaded) {
                            globalOwner = owner?.copy(phase = Phase.ACTIVE)
                        } else {
                            globalOwner = null
                            state.active = null
                            globalMonitor.notifyAll()
                        }
                        onActiveSessionReloaded(liveActive, reloaded)
                    }
                }
                return result
            }
        }
    }

    fun reserveLoadedGhostForTesting(ghost: Ghost): ReservedGhost {
        val construction = beginConstruction(ghost.getGhostId(), rootOf(ghost))
        return bind(construction, ghost)
    }

    fun clearForTesting() = withAllRootStates {
        synchronized(globalMonitor) {
            check(globalOwner == null && globalPoison == null) {
                "cannot reset while SHIORI ownership is live or uncertain"
            }
            check(roots.values.none { it.construction != null || it.reservation != null || it.active != null }) {
                "cannot reset while a ghost session is pending or active"
            }
            roots.clear()
            globalMonitor.notifyAll()
        }
    }

    private fun reload(ghost: Ghost): Boolean {
        try {
            ghost.reloadAfterGhostUpdate()
            return true
        } catch (error: Exception) {
            deactivate(ghost, error)
        } catch (error: LinkageError) {
            deactivate(ghost, error)
        }
        return false
    }

    private fun deactivate(ghost: Ghost, error: Throwable) {
        try {
            ghost.deactivateAfterGhostUpdateReloadFailure()
        } catch (deactivationError: Exception) {
            LegacyPlatform.debug("SScriptRunner", "ghost reload deactivation failed: ${deactivationError.message}")
        } catch (deactivationError: LinkageError) {
            LegacyPlatform.debug("SScriptRunner", "ghost reload deactivation failed: ${deactivationError.message}")
        }
        LegacyPlatform.debug("SScriptRunner", "ghost reload after update failed: ${error.message}")
    }

    private fun identity(ghost: Ghost): Pair<String, File> = ghost.getGhostId() to rootOf(ghost)

    private fun poisonStaleConstructedGhost(state: RootState, ghost: Ghost) {
        val error = IllegalStateException("stale construction may have replaced the process SHIORI session")
        state.poison = error
        globalPoison = error
        val owner = globalOwner
        globalOwner = GlobalOwner(owner?.generation ?: generations.incrementAndGet(), owner?.ghost ?: ghost, Phase.POISONED)
    }

    private fun poisonGlobal(generation: Long, ghost: Ghost?, error: Throwable) {
        globalPoison = error
        globalOwner = GlobalOwner(generation, ghost, Phase.POISONED)
    }

    private fun rootOf(ghost: Ghost): File = File(ghost.getGhostPath()).canonicalFile

    private fun state(root: File): RootState = roots.computeIfAbsent(root.canonicalPath) { RootState() }

    private fun <T> withRootStates(roots: List<File>, action: () -> T): T {
        val states = roots.map { it.canonicalFile }.distinctBy(File::getPath).sortedBy(File::getPath).map(::state)
        fun lock(index: Int): T = if (index == states.size) action()
        else synchronized(states[index].monitor) { lock(index + 1) }
        return lock(0)
    }

    private fun <T> withAllRootStates(action: () -> T): T {
        val states = roots.entries.sortedBy { it.key }.map { it.value }
        fun lock(index: Int): T = if (index == states.size) action()
        else synchronized(states[index].monitor) { lock(index + 1) }
        return lock(0)
    }
}
