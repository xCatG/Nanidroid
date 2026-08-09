package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.SurfaceHitTarget
import com.cattailsw.nanidroid.compose.ComposedSurface
import com.cattailsw.nanidroid.compose.SurfacePixelImage
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.compose.stage.StageSurfaceSnapshot
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.InputBoxSpec
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.surface.CollisionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageInputRouterTest {
    @Test
    fun `blocking UI wins before bubbles and surfaces`() {
        val snapshot = snapshot(
            blocking = true,
            bubbles = listOf(region(IntRect(0, 0, 20, 20), BubbleInteractionTarget.Frame(SurfaceSpeaker.KERO))),
            surfaces = listOf(surface(SurfaceSpeaker.KERO, IntRect(0, 0, 20, 20))),
        )

        assertEquals(StageInputTarget.Modal, resolve(snapshot, 2, 2).target)
    }

    @Test
    fun `bubble variants resolve in first-match order and consume the complete frame`() {
        val choice = BubbleInteractionTarget.Choice(DialogueAction.Normal("Yes", "yes", listOf("r1")))
        val anchorAction = AnchorAction.Normal("Topic", "topic", listOf("one", "two"))
        val anchor = BubbleInteractionTarget.Anchor(anchorAction)
        val url = BubbleInteractionTarget.ExternalUrl("https://example.test/")
        val input = BubbleInteractionTarget.Input(
            DialogueSegment.InputBox(InputBoxSpec(
                dispatch = InputDispatch.Normal("name"),
                timeoutMillis = null,
                initialText = "",
                supplement = "",
                extraReferences = emptyList(),
                unknownOptions = emptyList(),
            )),
        )
        val scroll = BubbleInteractionTarget.Scroll(SurfaceSpeaker.KERO)
        val frame = BubbleInteractionTarget.Frame(SurfaceSpeaker.SAKURA)
        val targets = listOf(choice, anchor, url, input, scroll, frame)
        val regions = targets.mapIndexed { index, target -> region(IntRect(index * 10, 0, index * 10 + 10, 10), target) }
        val overlap = region(IntRect(0, 0, 10, 10), frame)
        val snapshot = snapshot(
            bubbles = listOf(regions.first(), overlap) + regions.drop(1),
            surfaces = listOf(surface(SurfaceSpeaker.SAKURA, IntRect(0, 0, 80, 20))),
        )

        targets.forEachIndexed { index, target ->
            assertEquals(StageInputTarget.Bubble(target), resolve(snapshot, index * 10 + 1, 1).target)
        }
    }

    @Test
    fun `visible fallback frame consumes but an absent reserved half does not`() {
        val visibleFrame = snapshot(
            bubbles = listOf(region(IntRect(0, 0, 20, 20), BubbleInteractionTarget.Frame(SurfaceSpeaker.KERO))),
            surfaces = emptyList(),
        )
        val absentReservedHalf = snapshot(bubbles = emptyList(), surfaces = emptyList())

        assertEquals(
            StageInputTarget.Bubble(BubbleInteractionTarget.Frame(SurfaceSpeaker.KERO)),
            resolve(visibleFrame, 10, 10).target,
        )
        assertEquals(StageInputTarget.EmptyStage, resolve(absentReservedHalf, 10, 10).target)
    }

    @Test
    fun `named collision wins across speakers before generic transparent canvas`() {
        val genericKero = surface(SurfaceSpeaker.KERO, IntRect(0, 0, 20, 20), transparent = true)
        val namedSakura = surface(
            SurfaceSpeaker.SAKURA,
            IntRect(0, 0, 20, 20),
            collision = SurfaceCollision(
                id = 9,
                identifier = "HeadCase",
                shape = CollisionShape.Rectangle(IntRect(4, 4, 7, 7)),
                authoredOrder = 0,
            ),
        )

        val named = resolve(snapshot(surfaces = listOf(genericKero, namedSakura)), 5, 5, PointerSource.PEN)
        val generic = resolve(snapshot(surfaces = listOf(genericKero, namedSakura)), 12, 12, PointerSource.MOUSE)

        assertEquals(
            StageInputTarget.Surface(SurfaceSpeaker.SAKURA, SurfaceHitTarget.Collision(9, "HeadCase")),
            named.target,
        )
        assertEquals("HeadCase", named.effect?.collisionIdentifier)
        assertEquals(PointerSource.PEN, named.effect?.source)
        assertEquals(
            StageInputTarget.Surface(SurfaceSpeaker.KERO, SurfaceHitTarget.TransparentPixel),
            generic.target,
        )
        assertNull(generic.effect?.collisionIdentifier)
        assertEquals(PointerSource.MOUSE, generic.effect?.source)
    }

    @Test
    fun `complete canvas is half open and hidden placeholders do not route`() {
        val visible = surface(SurfaceSpeaker.KERO, IntRect(10, 20, 30, 40), transparent = true)
        val hidden = surface(SurfaceSpeaker.SAKURA, IntRect(40, 20, 60, 40), hidden = true)
        val snapshot = snapshot(surfaces = listOf(visible, hidden))

        assertTrue(resolve(snapshot, 10, 20).target is StageInputTarget.Surface)
        assertTrue(resolve(snapshot, 29, 39).target is StageInputTarget.Surface)
        assertEquals(StageInputTarget.EmptyStage, resolve(snapshot, 30, 39).target)
        assertEquals(StageInputTarget.EmptyStage, resolve(snapshot, 45, 25).target)
    }

    @Test
    fun `resized transform changes routing immediately`() {
        val before = snapshot(surfaces = listOf(surface(SurfaceSpeaker.SAKURA, IntRect(0, 0, 10, 10))))
        val after = snapshot(surfaces = listOf(surface(SurfaceSpeaker.SAKURA, IntRect(20, 30, 40, 50))))

        assertTrue(resolve(before, 5, 5).target is StageInputTarget.Surface)
        assertEquals(StageInputTarget.EmptyStage, resolve(after, 5, 5).target)
        assertTrue(resolve(after, 25, 35).target is StageInputTarget.Surface)
    }

    @Test
    fun `fractional stage coordinates remain continuous through inverse scaling`() {
        val size = IntSize(200, 100)
        val collision = SurfaceCollision(
            7,
            "FractionalEdge",
            CollisionShape.Rectangle(IntRect(99, 0, 100, 100)),
            0,
        )
        val composed = ComposedSurface(
            image = SurfacePixelImage.of(size.width, size.height, IntArray(size.width * size.height)),
            canvasSize = size,
            visiblePixelBounds = null,
            effectiveCollisions = listOf(collision),
            surfaceKey = SurfaceKey(0, size),
            revision = 1,
            explicitlyHidden = false,
        )
        val surface = StageSurfaceSnapshot(
            SurfaceSpeaker.SAKURA,
            composed,
            SurfaceTransformPx(size, IntRect(0, 0, 100, 50), 0.5f, IntOffset.Zero),
        )

        val resolution = StageInputRouter.resolve(
            snapshot(surfaces = listOf(surface)),
            Offset(49.75f, 10f),
            PointerSource.MOUSE,
            button = 0,
        )

        assertEquals(
            StageInputTarget.Surface(SurfaceSpeaker.SAKURA, SurfaceHitTarget.Collision(7, "FractionalEdge")),
            resolution.target,
        )
        assertEquals(IntOffset(99, 20), resolution.effect?.intrinsic)
    }

    @Test
    fun `geometry token ignores raster revision but invalidates identity transform collisions and bubbles`() {
        val baseSurface = surface(SurfaceSpeaker.SAKURA, IntRect(0, 0, 20, 20), revision = 1)
        val base = snapshot(ghostKey = "manager-a", bubbleGeneration = 7, surfaces = listOf(baseSurface))
        val rasterOnly = snapshot(
            ghostKey = "manager-a",
            bubbleGeneration = 7,
            surfaces = listOf(baseSurface.copy(composedSurface = baseSurface.composedSurface.copy(revision = 999))),
        )
        assertEquals(base.geometryToken, rasterOnly.geometryToken)

        assertNotEquals(
            base.geometryToken,
            snapshot(blocking = true, ghostKey = "manager-a", bubbleGeneration = 7, surfaces = listOf(baseSurface)).geometryToken,
        )
        assertNotEquals(base.geometryToken, snapshot(ghostKey = "manager-b", bubbleGeneration = 7, surfaces = listOf(baseSurface)).geometryToken)
        assertNotEquals(base.geometryToken, snapshot(ghostKey = "manager-a", bubbleGeneration = 8, surfaces = listOf(baseSurface)).geometryToken)
        assertNotEquals(
            base.geometryToken,
            snapshot(
                ghostKey = "manager-a",
                bubbleGeneration = 7,
                surfaces = listOf(
                    baseSurface.copy(
                        transform = baseSurface.transform.copy(renderedBounds = IntRect(1, 0, 21, 20)),
                    ),
                ),
            ).geometryToken,
        )
        assertNotEquals(
            base.geometryToken,
            snapshot(
                ghostKey = "manager-a",
                bubbleGeneration = 7,
                surfaces = listOf(
                    baseSurface.copy(
                        composedSurface = baseSurface.composedSurface.copy(
                            surfaceKey = SurfaceKey(99, baseSurface.composedSurface.canvasSize),
                        ),
                    ),
                ),
            ).geometryToken,
        )
        assertNotEquals(
            base.geometryToken,
            snapshot(
                ghostKey = "manager-a",
                bubbleGeneration = 7,
                surfaces = listOf(
                    baseSurface.copy(
                        composedSurface = baseSurface.composedSurface.copy(
                            effectiveCollisions = listOf(
                                SurfaceCollision(1, "Head", CollisionShape.Rectangle(IntRect(0, 0, 2, 2)), 0),
                            ),
                        ),
                    ),
                ),
            ).geometryToken,
        )
        assertNotEquals(
            snapshot(
                surfaces = listOf(
                    baseSurface.copy(composedSurface = baseSurface.composedSurface.copy(inputAuthority = "base-a")),
                ),
            ).geometryToken,
            snapshot(
                surfaces = listOf(
                    baseSurface.copy(composedSurface = baseSurface.composedSurface.copy(inputAuthority = "base-b")),
                ),
            ).geometryToken,
        )
        assertNotEquals(
            snapshot(ghostKey = "manager-a", routingEpoch = 0, surfaces = listOf(baseSurface)).geometryToken,
            snapshot(ghostKey = "manager-a", routingEpoch = 2, surfaces = listOf(baseSurface)).geometryToken,
        )
    }

    @Test
    fun `primary button and event-local source are retained without inventing secondary dispatch`() {
        val snapshot = snapshot(surfaces = listOf(surface(SurfaceSpeaker.SAKURA, IntRect(0, 0, 20, 20))))

        val primary = StageInputRouter.resolve(snapshot, Offset(2f, 3f), PointerSource.ERASER, button = 0)
        val secondary = StageInputRouter.resolve(snapshot, Offset(2f, 3f), PointerSource.MOUSE, button = 1)

        assertEquals(PointerSource.ERASER, primary.effect?.source)
        assertEquals(0, primary.effect?.button)
        assertEquals(StageInputTarget.EmptyStage, secondary.target)
        assertNull(secondary.effect)
    }

    private fun resolve(
        snapshot: StageInputSnapshot,
        x: Int,
        y: Int,
        source: PointerSource = PointerSource.TOUCH,
    ) = StageInputRouter.resolve(snapshot, Offset(x.toFloat(), y.toFloat()), source, button = 0)

    private fun snapshot(
        blocking: Boolean = false,
        bubbles: List<MeasuredBubbleHitRegion> = emptyList(),
        bubbleGeneration: Long = 0,
        routingEpoch: Long = 0,
        ghostKey: String = "manager-a",
        surfaces: List<StageSurfaceSnapshot> = emptyList(),
    ) = StageInputRouter.snapshot(
        blocking = blocking,
        bubbleRegistry = BubbleHitRegionRegistry.from(bubbles),
        bubbleGeneration = bubbleGeneration,
        routingEpoch = routingEpoch,
        ghostKey = ghostKey,
        surfaces = surfaces,
    )

    private fun region(bounds: IntRect, target: BubbleInteractionTarget) = MeasuredBubbleHitRegion(bounds, target)

    private fun surface(
        speaker: SurfaceSpeaker,
        bounds: IntRect,
        transparent: Boolean = false,
        hidden: Boolean = false,
        revision: Long = 0,
        collision: SurfaceCollision? = null,
    ): StageSurfaceSnapshot {
        val size = IntSize(20, 20)
        val image = SurfacePixelImage.of(
            size.width,
            size.height,
            IntArray(size.width * size.height) { if (transparent) 0 else 0xff203040.toInt() },
        )
        val composed = ComposedSurface(
            image = image,
            canvasSize = size,
            visiblePixelBounds = if (transparent) null else IntRect(0, 0, size.width, size.height),
            effectiveCollisions = listOfNotNull(collision),
            surfaceKey = SurfaceKey(if (speaker == SurfaceSpeaker.SAKURA) 0 else 10, size),
            revision = revision,
            explicitlyHidden = hidden,
        )
        return StageSurfaceSnapshot(
            speaker,
            composed,
            SurfaceTransformPx(size, bounds, bounds.width / size.width.toFloat(), IntOffset.Zero),
        )
    }
}
