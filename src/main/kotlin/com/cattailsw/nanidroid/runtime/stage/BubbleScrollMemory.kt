package com.cattailsw.nanidroid.runtime.stage

import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import java.util.UUID

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
        val retained = currentBySpeaker[key.speaker]
        if (retained != null && retained.key.talkId > key.talkId) return retained.snapshot
        val previous = snapshot(key)
        val next = BubbleScrollSnapshot(
            position = position.coerceAtLeast(0),
            userScrolled = previous.userScrolled || origin == BubbleScrollOrigin.MANUAL,
        )
        currentBySpeaker[key.speaker] = RetainedScroll(key, next)
        return next
    }

    /** Bounded flat primitive payload: at most one current talk for each physical speaker. */
    fun saveValues(): List<Any> = buildList {
        SurfaceSpeaker.entries.forEach { speaker ->
            val retained = currentBySpeaker[speaker] ?: return@forEach
            add(speaker.name)
            add(retained.key.talkId)
            add(retained.snapshot.position)
            add(retained.snapshot.userScrolled)
        }
    }

    private data class RetainedScroll(
        val key: BubbleScrollKey,
        val snapshot: BubbleScrollSnapshot,
    )

    companion object {
        private const val VALUES_PER_SPEAKER = 4
        private const val MAX_SAVED_VALUES = VALUES_PER_SPEAKER * 2

        fun restoreValues(values: List<Any>): BubbleScrollMemory = BubbleScrollMemory().apply {
            values.take(MAX_SAVED_VALUES)
                .chunked(VALUES_PER_SPEAKER)
                .filter { it.size == VALUES_PER_SPEAKER }
                .forEach { entry ->
                    val speaker = (entry[0] as? String)?.let { name ->
                        SurfaceSpeaker.entries.firstOrNull { it.name == name }
                    } ?: return@forEach
                    if (speaker in currentBySpeaker) return@forEach
                    val talkId = entry[1] as? Long ?: return@forEach
                    val position = entry[2] as? Int ?: return@forEach
                    val userScrolled = entry[3] as? Boolean ?: return@forEach
                    if (talkId < 0L || position < 0) return@forEach
                    if (position == Int.MAX_VALUE && userScrolled) return@forEach
                    currentBySpeaker[speaker] = RetainedScroll(
                        key = BubbleScrollKey(speaker, talkId),
                        snapshot = BubbleScrollSnapshot(position, userScrolled),
                    )
                }
        }
    }
}

/**
 * Saveable owner that keeps restored scroll quarantined while the host has no stable ghost ID.
 * A real ghost-to-ghost change resets the presentation-only scroll state.
 */
class GhostBubbleScrollMemory private constructor(
    private var boundSessionKey: String?,
    private var boundGhostKey: String?,
    private var memory: BubbleScrollMemory,
) {
    private var unboundMemory = BubbleScrollMemory()

    fun memoryFor(sessionKey: String, ghostKey: String): BubbleScrollMemory {
        if (boundSessionKey != sessionKey) {
            boundSessionKey = sessionKey
            boundGhostKey = null
            memory = BubbleScrollMemory()
            unboundMemory = BubbleScrollMemory()
        }
        if (ghostKey.isEmpty()) return unboundMemory
        when {
            boundGhostKey == null -> boundGhostKey = ghostKey
            boundGhostKey != ghostKey -> {
                boundGhostKey = ghostKey
                memory = BubbleScrollMemory()
            }
        }
        return memory
    }

    /** Bounded primitive payload: stable ghost identity plus at most two speaker records. */
    fun saveValues(): List<Any> {
        val sessionKey = boundSessionKey
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_SESSION_KEY_LENGTH }
            ?: return emptyList()
        val ghostKey = boundGhostKey
            ?.takeIf { it.length <= MAX_GHOST_KEY_LENGTH }
            ?: return emptyList()
        return buildList {
            add(SAVE_VERSION)
            add(sessionKey)
            add(ghostKey)
            addAll(memory.saveValues())
        }
    }

    companion object {
        private const val SAVE_VERSION = 2
        private const val MAX_SESSION_KEY_LENGTH = 128
        private const val MAX_GHOST_KEY_LENGTH = 512

        fun forContext(sessionKey: String, ghostKey: String): GhostBubbleScrollMemory = GhostBubbleScrollMemory(
            boundSessionKey = sessionKey.takeIf { it.isNotEmpty() },
            boundGhostKey = ghostKey.takeUnless { it.isEmpty() },
            memory = BubbleScrollMemory(),
        )

        fun restoreValues(values: List<Any>): GhostBubbleScrollMemory {
            val version = values.getOrNull(0) as? Int
            val sessionKey = (values.getOrNull(1) as? String)
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_SESSION_KEY_LENGTH }
            val ghostKey = (values.getOrNull(2) as? String)
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_GHOST_KEY_LENGTH }
            if (version != SAVE_VERSION || sessionKey == null || ghostKey == null) {
                return forContext("", "")
            }
            return GhostBubbleScrollMemory(
                boundSessionKey = sessionKey,
                boundGhostKey = ghostKey,
                memory = BubbleScrollMemory.restoreValues(values.drop(3)),
            )
        }
    }
}

/** Changes after process recreation while remaining stable across Activity recreation. */
object BubbleScrollProcessSession {
    val key: String = UUID.randomUUID().toString()
}

/** Survives host recreation but cannot cross a runner's dialogue-session reset. */
fun bubbleScrollSessionIdentity(dialogueIncarnation: Long): String =
    "${BubbleScrollProcessSession.key}:$dialogueIncarnation"
