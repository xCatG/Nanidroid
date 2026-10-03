package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.ghost.LAYER_METHODS
import com.cattailsw.nanidroid.ghost.SurfaceAnimation
import com.cattailsw.nanidroid.ghost.SurfaceDefinitions
import com.cattailsw.nanidroid.ghost.SurfaceLayer
import com.cattailsw.nanidroid.ghost.SurfacePattern

/** The authored image IDs and offsets to draw for one logical surface. */
data class SurfaceVisual(
    val baseSurfaceId: Int,
    val layers: List<SurfaceLayer>,
    val offsetX: Int,
    val offsetY: Int,
)

/** A deterministic SERIKO subset; all time is supplied by the owning runtime. */
class SurfaceAnimator(
    private val definitions: SurfaceDefinitions,
    private val nextLong: (boundExclusive: Long) -> Long,
) {
    private class Track(val animation: SurfaceAnimation) {
        val self = setOf(animation.id)
        var running = false
        var nextPattern = 0
        var remaining = 0L
        var displayed: SurfacePattern.Image? = null
        var alternativePath: Set<Int> = emptySet()
    }
    private class Speaker(var surface: Int) {
        val tracks = sortedMapOf<Int, Track>()
        var secondElapsed = 0L
        var talkElapsed = 0L
    }

    private val speakers = mutableMapOf<Int, Speaker>()
    private val explicitSpeakers = mutableSetOf<Int>()
    private var published: Map<Int, SurfaceVisual> = emptyMap()
    private var dirty = false

    fun setSurface(speaker: Int, surfaceId: Int) {
        val current = speakers[speaker]
        if (current?.surface == surfaceId) return
        val state = Speaker(surfaceId)
        definitions.definition(surfaceId)?.animations?.forEach { animation ->
            state.tracks[animation.id] = Track(animation).apply {
                if (animation.interval == "always" || animation.interval == "runonce") running = true
            }
        }
        speakers[speaker] = state
        dirty = true
    }

    fun startExplicit(speaker: Int, animationId: Int) {
        val state = speakers[speaker] ?: return
        if (animationId !in state.tracks) return
        explicitSpeakers += speaker
        start(state, animationId, emptySet())
    }

    fun advanceBy(elapsedMs: Long, talking: Set<Int>): Map<Int, SurfaceVisual>? {
        require(elapsedMs >= 0)
        for ((speakerId, speaker) in speakers) {
            // Alternative ancestry guards one advance only; a delayed target may restart
            // an earlier animation on a later tick.
            for (track in speaker.tracks.values) {
                if (track.running) track.alternativePath = track.self
            }
            val autoStarted = mutableSetOf<Int>()
            val seconds = speaker.secondElapsed + elapsedMs
            val rolls = seconds / 1_000
            speaker.secondElapsed = seconds % 1_000
            repeat(minOf(rolls, 64L).toInt()) {
                for (track in speaker.tracks.values) {
                    val odds = RANDOM_ODDS[track.animation.interval]
                    if (!track.running && odds != null && sample(odds) == 0L) {
                        start(speaker, track.animation.id, emptySet())
                        autoStarted += track.animation.id
                    }
                }
            }
            speaker.talkElapsed = if (elapsedMs >= 500 - speaker.talkElapsed) 500
                else speaker.talkElapsed + elapsedMs
            if (speakerId in talking && speaker.talkElapsed >= 500) {
                if (speakerId !in explicitSpeakers) for (track in speaker.tracks.values) {
                    if (!track.running && track.animation.interval == "talk") {
                        start(speaker, track.animation.id, emptySet())
                        autoStarted += track.animation.id
                    }
                }
                speaker.talkElapsed = 0
            }
            // start() only mutates tracks, so iterating the live map is safe.
            for (track in speaker.tracks.values) {
                if (track.animation.id !in autoStarted) advance(speaker, track, elapsedMs)
            }
        }
        explicitSpeakers.clear()
        // Every change to a displayed image or base surface marks the animator dirty.
        if (!dirty) return null
        dirty = false
        val visual = speakers.mapValues { (_, speaker) -> visual(speaker) }
        if (visual == published) return null
        published = visual
        return visual
    }

    private fun start(speaker: Speaker, id: Int, ancestors: Set<Int>) {
        if (id in ancestors) return
        val track = speaker.tracks[id] ?: return
        track.running = true
        track.nextPattern = 0
        track.remaining = 0
        // Starting a track replaces its old final image, including explicit restarts.
        track.displayed = null
        track.alternativePath = ancestors + id
        dirty = true
    }

    private fun advance(speaker: Speaker, track: Track, elapsed: Long) {
        if (!track.running) return
        var budget = elapsed
        var transitions = 0
        while (track.running && transitions++ < 64) {
            val patterns = track.animation.patterns
            if (track.nextPattern >= patterns.size) {
                if (track.animation.interval == "always" && patterns.isNotEmpty()) track.nextPattern = 0
                else { track.running = false; break }
            }
            val pattern = patterns.getOrNull(track.nextPattern) ?: break
            if (pattern is SurfacePattern.Alternative) {
                val targets = pattern.animationIds.filter { it in speaker.tracks && it != track.animation.id }
                if (targets.isNotEmpty()) start(speaker, targets[sample(targets.size.toLong()).toInt()],
                    track.alternativePath)
                track.nextPattern++
                continue
            }
            pattern as SurfacePattern.Image
            if (track.remaining == 0L) track.remaining = waitFor(pattern).let { if (it == 0L) 50L else it }
            if (budget < track.remaining) {
                track.remaining -= budget
                break
            }
            budget -= track.remaining
            track.remaining = 0
            track.nextPattern++
            track.displayed = if (pattern.surfaceId == -1) null else pattern
            dirty = true
            // A zero authored wait still consumes one runtime tick.
            if (budget == 0L) break
        }
    }

    private fun waitFor(pattern: SurfacePattern.Image): Long {
        val low = pattern.waitMinMs
        val high = pattern.waitMaxMs
        if (low < 0 || high < low || high > Int.MAX_VALUE) return Int.MAX_VALUE.toLong()
        return low + sample(high - low + 1)
    }

    private fun sample(bound: Long): Long = if (bound <= 1) 0 else nextLong(bound).coerceIn(0, bound - 1)

    private fun visual(speaker: Speaker): SurfaceVisual {
        val frames = speaker.tracks.values.mapNotNull { it.displayed }
        val base = frames.lastOrNull { it.method == "base" }
        val move = frames.lastOrNull { it.method == "move" }
        val layers = frames.filter { it.method in LAYER_METHODS }
            .map { SurfaceLayer(it.surfaceId, it.method, it.x, it.y) }
        return SurfaceVisual(base?.surfaceId ?: speaker.surface, layers, move?.x ?: 0, move?.y ?: 0)
    }

    private companion object {
        /** One-in-N chance per eligible second. */
        val RANDOM_ODDS = mapOf("sometimes" to 2L, "rarely" to 4L)
    }
}
