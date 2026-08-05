package com.cattailsw.nanidroid.runtime.stage

import com.cattailsw.nanidroid.compose.SurfaceSpeaker

data class BubbleScrollKey(
    val speaker: SurfaceSpeaker,
    val talkId: Long,
)

enum class BubbleScrollOrigin {
    MANUAL,
    PROGRAMMATIC,
}

data class BubbleScrollSnapshot(
    val position: Int,
    val userScrolled: Boolean,
) {
    companion object {
        val FollowNewest = BubbleScrollSnapshot(Int.MAX_VALUE, false)
    }
}

/** Presentation-only scroll memory isolated by physical speaker and stable talk. */
class BubbleScrollMemory {
    private val currentBySpeaker = mutableMapOf<SurfaceSpeaker, RetainedScroll>()

    fun snapshot(key: BubbleScrollKey): BubbleScrollSnapshot {
        val retained = currentBySpeaker[key.speaker]
        if (retained?.key == key) return retained.snapshot
        return BubbleScrollSnapshot.FollowNewest.also { initial ->
            currentBySpeaker[key.speaker] = RetainedScroll(key, initial)
        }
    }

    fun update(
        key: BubbleScrollKey,
        position: Int,
        origin: BubbleScrollOrigin,
    ): BubbleScrollSnapshot {
        val previous = snapshot(key)
        val next = BubbleScrollSnapshot(
            position = position.coerceAtLeast(0),
            userScrolled = previous.userScrolled || origin == BubbleScrollOrigin.MANUAL,
        )
        currentBySpeaker[key.speaker] = RetainedScroll(key, next)
        return next
    }

    private data class RetainedScroll(
        val key: BubbleScrollKey,
        val snapshot: BubbleScrollSnapshot,
    )
}
