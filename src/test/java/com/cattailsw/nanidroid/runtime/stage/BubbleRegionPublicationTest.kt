package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleRegionPublicationTest {
    @Test
    fun replacementIsAtomicOrderedHalfOpenAndTracksGeneration() {
        val firstAction = DialogueAction.Normal("First", "first", emptyList())
        val replacementAction = DialogueAction.Normal("Replacement", "replacement", emptyList())
        val firstFence = BubbleRegionFence(
            SurfaceSpeaker.SAKURA,
            talkId = 1L,
            contentRevision = 1L,
            frame = IntRect(0, 0, 30, 30),
        )
        val replacementFence = firstFence.copy(contentRevision = 2L)
        val first = BubbleRegionPublication.Empty.replace(
            BubbleRegionSet(
                fence = firstFence,
                actionRegions = listOf(
                    MeasuredBubbleHitRegion(
                        IntRect(0, 0, 10, 10),
                        BubbleInteractionTarget.Choice(firstAction),
                    ),
                ),
                scrollViewport = IntRect(0, 0, 20, 20),
            ),
        )

        assertEquals(1L, first.generation)
        assertEquals(
            listOf(
                BubbleInteractionTarget.Choice(firstAction),
                BubbleInteractionTarget.Scroll(SurfaceSpeaker.SAKURA),
                BubbleInteractionTarget.Frame(SurfaceSpeaker.SAKURA),
            ),
            first.regions.map { it.target },
        )
        val firstRegistry = BubbleHitRegionRegistry.from(first.regions)
        assertEquals(BubbleInteractionTarget.Choice(firstAction), firstRegistry.resolve(Offset(9.999f, 5f)))
        assertEquals(BubbleInteractionTarget.Scroll(SurfaceSpeaker.SAKURA), firstRegistry.resolve(Offset(10f, 5f)))
        assertEquals(BubbleInteractionTarget.Frame(SurfaceSpeaker.SAKURA), firstRegistry.resolve(Offset(20f, 5f)))
        assertEquals(null, firstRegistry.resolve(Offset(30f, 5f)))

        val replacement = first.replace(
            BubbleRegionSet(
                fence = replacementFence,
                actionRegions = listOf(
                    MeasuredBubbleHitRegion(
                        IntRect(12, 0, 18, 10),
                        BubbleInteractionTarget.Choice(replacementAction),
                    ),
                ),
                scrollViewport = IntRect(0, 0, 20, 20),
            ),
        )

        assertEquals(2L, replacement.generation)
        val replacementRegistry = BubbleHitRegionRegistry.from(replacement.regions)
        assertEquals(BubbleInteractionTarget.Scroll(SurfaceSpeaker.SAKURA), replacementRegistry.resolve(Offset(5f, 5f)))
        assertEquals(
            BubbleInteractionTarget.Choice(replacementAction),
            replacementRegistry.resolve(Offset(12f, 5f)),
        )

        val removed = replacement.remove(SurfaceSpeaker.SAKURA)
        assertEquals(3L, removed.generation)
        assertEquals(emptyList<MeasuredBubbleHitRegion>(), removed.regions)
        assertEquals(null, removed.fence(SurfaceSpeaker.SAKURA))
    }
}
