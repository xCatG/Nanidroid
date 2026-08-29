package com.cattailsw.nanidroid

/** Process-owned startup fallback progress, retained across Activity recreation. */
internal class StartupCandidateAttempts {
    private var epoch = Long.MIN_VALUE
    private var configured = false
    private var candidates = emptyList<String>()
    private var index = 0
    private var inFlight: String? = null
    private var sawBusy = false
    private var submittedRevision = Long.MIN_VALUE
    private var submittedNotice: com.cattailsw.nanidroid.runtime.RuntimeNotice? = null
    private var completed = false

    @Synchronized
    fun reserve(candidateEpoch: Long): Boolean {
        if (epoch == candidateEpoch) return !configured
        epoch = candidateEpoch
        configured = false
        candidates = emptyList()
        index = 0
        inFlight = null
        sawBusy = false
        submittedRevision = Long.MIN_VALUE
        submittedNotice = null
        completed = false
        return true
    }

    @Synchronized
    fun configure(candidateEpoch: Long, orderedCandidates: List<String>) {
        if (epoch != candidateEpoch || configured) return
        configured = true
        candidates = orderedCandidates.distinctBy(String::lowercase)
    }

    @Synchronized
    fun nextCandidate(
        candidateEpoch: Long,
        phase: GhostRuntimePhase,
        generation: Long?,
        parentOperationId: Long?,
        exitPresent: Boolean,
        revision: Long,
        notice: com.cattailsw.nanidroid.runtime.RuntimeNotice?,
    ): String? {
        if (epoch != candidateEpoch || !configured) return null
        if (generation != null) {
            completed = true
            inFlight = null
            return null
        }
        if (
            completed ||
            parentOperationId != null ||
            exitPresent ||
            phase == GhostRuntimePhase.Poisoned
        ) return null
        if (phase != GhostRuntimePhase.Idle) {
            if (inFlight != null) sawBusy = true
            return null
        }
        if (inFlight != null) {
            val retryableFailureObserved = revision > submittedRevision &&
                notice != submittedNotice &&
                notice?.code in setOf(
                    com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.PREPARATION_FAILED,
                    com.cattailsw.nanidroid.runtime.RuntimeNoticeCode.NATIVE_LOAD_FAILED,
                )
            if (!sawBusy && !retryableFailureObserved) return null
            index += 1
            inFlight = null
            sawBusy = false
        }
        val next = candidates.getOrNull(index) ?: return null
        inFlight = next
        submittedRevision = revision
        submittedNotice = notice
        return next
    }
}
