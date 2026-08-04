package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.unit.IntRect
import com.cattailsw.nanidroid.compose.SurfaceSpeaker

/** Identity and geometry fence captured by one bubble layout pass. */
data class BubbleRegionFence(
    val speaker: SurfaceSpeaker,
    val talkId: Long,
    val contentRevision: Long,
    val frame: IntRect,
)

/** One atomic speaker-local replacement: authored actions, viewport, then frame. */
data class BubbleRegionSet(
    val fence: BubbleRegionFence,
    val actionRegions: List<MeasuredBubbleHitRegion>,
    val scrollViewport: IntRect?,
)

class BubbleRegionPublication private constructor(
    val generation: Long,
    private val sets: Map<SurfaceSpeaker, BubbleRegionSet>,
    val regions: List<MeasuredBubbleHitRegion>,
) {
    internal fun fence(speaker: SurfaceSpeaker): BubbleRegionFence? = sets[speaker]?.fence

    internal fun replace(next: BubbleRegionSet): BubbleRegionPublication {
        val previous = sets[next.fence.speaker]
        if (previous == next) return this
        val replacements = sets.toMutableMap().apply {
            put(next.fence.speaker, next.immutableCopy())
        }.toMap()
        return withSets(replacements)
    }

    internal fun remove(speaker: SurfaceSpeaker): BubbleRegionPublication {
        if (speaker !in sets) return this
        return withSets(sets - speaker)
    }

    private fun withSets(next: Map<SurfaceSpeaker, BubbleRegionSet>) =
        BubbleRegionPublication(
            generation = generation + 1,
            sets = next,
            regions = next.values
                .sortedBy { it.fence.speaker.ordinal }
                .flatMap(BubbleRegionSet::orderedRegions),
        )

    companion object {
        val Empty = BubbleRegionPublication(0L, emptyMap(), emptyList())
    }
}

object BubbleRegionPublicationPolicy {
    /**
     * Replaces all regions for one speaker together. A callback captured from
     * an earlier talk/layout cannot overwrite the current geometry fence.
     */
    fun replace(
        current: BubbleRegionPublication,
        expectedFence: BubbleRegionFence?,
        next: BubbleRegionSet,
    ): BubbleRegionPublication {
        if (current.fence(next.fence.speaker) != expectedFence) return current
        return current.replace(next)
    }

    fun remove(
        current: BubbleRegionPublication,
        expectedFence: BubbleRegionFence,
        speaker: SurfaceSpeaker,
    ): BubbleRegionPublication {
        if (current.fence(speaker) != expectedFence) return current
        return current.remove(speaker)
    }
}

private fun BubbleRegionSet.immutableCopy() = copy(actionRegions = actionRegions.toList())

private fun BubbleRegionSet.orderedRegions(): List<MeasuredBubbleHitRegion> = buildList {
    actionRegions.forEach { region ->
        require(region.target.isSpecificAction()) {
            "bubble action regions must precede scroll and frame"
        }
        add(region)
    }
    scrollViewport?.let { bounds ->
        add(MeasuredBubbleHitRegion(bounds, BubbleInteractionTarget.Scroll(fence.speaker)))
    }
    add(MeasuredBubbleHitRegion(fence.frame, BubbleInteractionTarget.Frame(fence.speaker)))
}

private fun BubbleInteractionTarget.isSpecificAction(): Boolean = when (this) {
    is BubbleInteractionTarget.Choice,
    is BubbleInteractionTarget.Anchor,
    is BubbleInteractionTarget.ExternalUrl,
    is BubbleInteractionTarget.Input,
    -> true
    is BubbleInteractionTarget.Scroll,
    is BubbleInteractionTarget.Frame,
    -> false
}
