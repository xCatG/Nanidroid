package com.cattailsw.nanidroid.install

import java.io.IOException

/** Binds exact, already-validated archive and retained-tree ownership after policy acceptance. */
internal object NarRetainedOverlayCoordinator {
    enum class Error { INPUT, BUSY, POLICY, TRANSFER }

    fun bind(session: NarVerifiedInstallSession?, claim: NarStagedTree.Claim?): Result {
        if (session == null || claim == null) return Result(null, Error.INPUT, null, "input")
        val archiveLease = session.lease() ?: return Result(null, Error.BUSY, null, "archive lease")
        val treeLease = claim.lease()
        if (treeLease == null) {
            session.release(archiveLease)
            return Result(null, Error.BUSY, null, "baseline lease")
        }
        var archiveConsumed = false
        var treeConsumed = false
        var transferred = false
        try {
            val policy = NarRetainedOverlayPolicy.build(
                archiveLease.plan(), treeLease.manifest(), treeLease.entries(),
            )
            if (!policy.isSuccess()) return Result(null, Error.POLICY, policy.error(), policy.detail())
            val candidate = Candidate(policy.recipe()!!, archiveLease, treeLease)
            if (session.consume(archiveLease) != NarVerifiedInstallSession.LeaseError.OK) {
                return Result(null, Error.TRANSFER, null, "archive transfer")
            }
            archiveConsumed = true
            if (claim.consume(treeLease) != NarStagedTree.Error.OK) {
                return Result(null, Error.TRANSFER, null, "baseline transfer")
            }
            treeConsumed = true
            transferred = true
            return Result(candidate, null, null, "")
        } finally {
            if (!transferred) {
                if (!treeConsumed) claim.release(treeLease)
                if (!archiveConsumed) session.release(archiveLease)
                if (archiveConsumed) try { archiveLease.cleanup() } catch (_: Throwable) { }
                if (treeConsumed) try { treeLease.discard() } catch (_: Throwable) { }
            }
        }
    }

    class Result internal constructor(
        private val candidateValue: Candidate?, private val errorValue: Error?,
        private val policyErrorValue: NarRetainedOverlayPolicy.Error?, detail: String?,
    ) {
        private val detailValue = detail ?: "input"
        fun isSuccess() = candidateValue != null
        fun candidate() = candidateValue
        fun error() = errorValue
        fun policyError() = policyErrorValue
        fun detail() = detailValue
    }

    class Candidate internal constructor(
        private val recipeValue: NarRetainedOverlayPolicy.Recipe,
        private val archiveLease: NarVerifiedInstallSession.Lease,
        private val treeLease: NarStagedTree.Claim.Lease,
    ) {
        private val baselineFingerprintValue = recipeValue.baselineFingerprint()
        private val fileCountValue = recipeValue.fileCount()
        private val knownTotalSizeValue = recipeValue.hasKnownTotalSize()
        private val totalSizeValue = recipeValue.totalSize()
        private var archiveCleaned = false
        private var treeCleaned = false

        fun baselineFingerprint() = baselineFingerprintValue.clone()
        fun fileCount() = fileCountValue
        fun hasKnownTotalSize() = knownTotalSizeValue
        fun totalSize() = totalSizeValue
        fun recipe() = recipeValue
        @Synchronized fun isCleaned() = archiveCleaned && treeCleaned

        @Synchronized @Throws(IOException::class)
        fun cleanup() {
            var first: Throwable? = null
            var message: String? = null
            if (!archiveCleaned) try {
                archiveLease.cleanup(); archiveCleaned = true
            } catch (failure: Throwable) { first = failure; message = "archive cleanup" }
            if (!treeCleaned) try {
                val result = treeLease.discard()
                if (result == NarStagedTree.Error.OK) treeCleaned = true
                else if (first == null) message = "baseline discard ${result.name}"
            } catch (failure: Throwable) {
                if (first == null) { first = failure; message = "baseline discard" }
            }
            if (!archiveCleaned || !treeCleaned) when (first) {
                is IOException -> throw first
                is java.lang.Error -> throw first
                null -> throw IOException(message)
                else -> throw IOException(message, first)
            }
        }
    }
}
