package com.cattailsw.nanidroid.install

import java.io.File
import java.io.IOException
import java.io.InputStream

/** Package-only extraction authority over one retained ZIP owner. */
internal class NarVerifiedInstallSession private constructor(
    private val entries: List<NarInstallPlanValidator.ArchiveEntry>,
    private val planValue: NarInstallPlan,
    private val resource: Resource
) {
    internal constructor(
        io: NarInstallPlanValidator.ArchiveIo,
        stagedFile: File,
        archive: NarInstallPlanValidator.OpenArchive,
        entries: List<NarInstallPlanValidator.ArchiveEntry>,
        plan: NarInstallPlan
    ) : this(entries, plan, Resource(io, stagedFile, archive))

    internal enum class State { READY, BUSY, CONSUMED }
    internal enum class LeaseError { OK, BUSY, CONSUMED, FOREIGN, STALE }

    private var stateValue = State.READY
    private var current: Lease? = null
    private var directCleanup = false

    fun getPlan(): NarInstallPlan = planValue
    @Synchronized fun state(): State = stateValue
    fun isClosed(): Boolean = resource.isComplete()

    @Synchronized fun lease(): Lease? {
        if (stateValue != State.READY) return null
        return Lease(this, resource).also { current = it; stateValue = State.BUSY }
    }

    @Synchronized fun release(lease: Lease?): LeaseError {
        val checked = check(lease)
        if (checked != LeaseError.OK) return checked
        lease!!.active = false
        current = null
        stateValue = State.READY
        return LeaseError.OK
    }

    @Synchronized fun consume(lease: Lease?): LeaseError {
        val checked = check(lease)
        if (checked != LeaseError.OK) return checked
        lease!!.active = false
        lease.consumed = true
        current = null
        stateValue = State.CONSUMED
        return LeaseError.OK
    }

    private fun check(lease: Lease?): LeaseError = when {
        lease == null || lease.owner !== this -> LeaseError.FOREIGN
        lease.consumed -> LeaseError.CONSUMED
        !lease.active -> LeaseError.STALE
        stateValue == State.CONSUMED -> LeaseError.CONSUMED
        stateValue != State.BUSY || current !== lease -> LeaseError.BUSY
        else -> LeaseError.OK
    }

    @Synchronized private fun leasedPlan(lease: Lease): NarInstallPlan {
        if (check(lease) != LeaseError.OK) throw IllegalStateException("stale archive lease")
        return planValue
    }

    @Throws(IOException::class)
    @Synchronized fun open(entry: NarInstallPlan.Entry?): InputStream {
        if (stateValue != State.READY) throw IllegalStateException("session unavailable")
        if (entry == null || entry.isDirectory || !entry.isInstallEntry) throw IllegalArgumentException("entry not extractable")
        val ordinal = entry.ordinal
        if (ordinal < 0 || ordinal >= entries.size || ordinal >= planValue.entries.size || planValue.entries[ordinal] !== entry) throw IllegalArgumentException("foreign plan entry")
        return resource.open(entries[ordinal])
    }

    @Throws(IOException::class)
    fun close() {
        synchronized(this) {
            if (stateValue == State.BUSY) throw IllegalStateException("session busy")
            if (stateValue == State.READY) { stateValue = State.CONSUMED; directCleanup = true }
            else if (!directCleanup) throw IllegalStateException("session consumed")
        }
        resource.cleanup()
    }

    internal class Lease internal constructor(
        internal val owner: NarVerifiedInstallSession,
        private val resource: Resource
    ) {
        internal var active = true
        internal var consumed = false
        fun plan(): NarInstallPlan = owner.leasedPlan(this)
        @Throws(IOException::class) fun cleanup() {
            synchronized(owner) { if (!consumed) throw IllegalStateException("archive lease not consumed") }
            resource.cleanup()
        }
    }

    internal class Resource(
        private val io: NarInstallPlanValidator.ArchiveIo,
        private val stagedFile: File,
        private val archive: NarInstallPlanValidator.OpenArchive
    ) {
        private var archiveClosed = false
        private var deleted = false
        @Synchronized fun open(entry: NarInstallPlanValidator.ArchiveEntry): InputStream = archive.open(entry)
        @Synchronized fun isComplete(): Boolean = archiveClosed && deleted
        @Synchronized @Throws(IOException::class) fun cleanup() {
            var failed = false
            var first: Throwable? = null
            var message: String? = null
            if (!archiveClosed) try { archive.close(); archiveClosed = true } catch (failure: Throwable) { failed = true; first = failure; message = "archive close" }
            if (!deleted) try { if (io.delete(stagedFile)) deleted = true else if (!failed) { failed = true; message = "staging delete" } } catch (failure: Throwable) { if (!failed) { failed = true; first = failure; message = "staging delete" } }
            if (failed) rethrow(first, message!!)
        }
        private fun rethrow(failure: Throwable?, message: String): Nothing {
            if (failure == null) throw IOException(message)
            if (failure is IOException) throw failure
            if (failure is Error) throw failure
            throw IOException(message, failure)
        }
    }
}
