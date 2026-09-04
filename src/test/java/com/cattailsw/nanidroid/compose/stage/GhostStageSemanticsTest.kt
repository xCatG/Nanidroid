package com.cattailsw.nanidroid.compose.stage

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.cattailsw.nanidroid.NO_COLLISION
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.compose.ComposedSurface
import com.cattailsw.nanidroid.compose.SurfacePixelImage
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey
import com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx
import com.cattailsw.nanidroid.runtime.stage.BubbleHitRegionRegistry
import com.cattailsw.nanidroid.runtime.stage.BubbleInteractionTarget
import com.cattailsw.nanidroid.runtime.stage.StageInputRouter
import com.cattailsw.nanidroid.surface.CollisionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostStageSemanticsTest {
    @Test
    fun `authored order and case-sensitive duplicate ordinals survive omission`() {
        val semantics = GhostStageSemantics.build(
            snapshot(
                collisions = listOf(
                    collision(1, "Door", point = IntOffset(1, 1)),
                    collision(2, "Door", representable = false),
                    collision(3, "door", point = IntOffset(3, 3)),
                    collision(4, "Door", point = IntOffset(4, 4)),
                ),
            ),
        )

        assertEquals(listOf(1, 3, 4), semantics.collisionActions.map { it.effect.diagnosticCollisionId })
        assertEquals(listOf(1, 1, 3), semantics.collisionActions.map { it.ordinal })
        assertEquals(listOf(3, 1, 3), semantics.collisionActions.map { it.duplicateCount })
        assertEquals(listOf("Door", "door", "Door"), semantics.collisionActions.map { it.spokenIdentifier })
        assertEquals(1, semantics.omittedUnrepresentable)
        assertTrue(requireNotNull(semantics.omissionDiagnostic).length <= GhostStageSemantics.MAX_DIAGNOSTIC_CHARS)
    }

    @Test
    fun `action cap preserves the accepted authored prefix`() {
        val collisions = List(GhostStageSemantics.MAX_COLLISION_ACTIONS + 3) { index ->
            collision(index + 1, "item-$index", point = IntOffset(index % 10, index % 10))
        }

        val semantics = GhostStageSemantics.build(snapshot(collisions))

        assertEquals(
            (1..GhostStageSemantics.MAX_COLLISION_ACTIONS).toList(),
            semantics.collisionActions.map { it.effect.diagnosticCollisionId },
        )
        assertEquals(3, semantics.omittedByCap)
        assertTrue(requireNotNull(semantics.omissionDiagnostic).contains("3"))
    }

    @Test
    fun `display copy is sanitized and bounded while dispatch identity is untouched`() {
        val authored = "Face\u0000\u061c\u202e\u2067" + "x".repeat(100)

        val action = GhostStageSemantics.build(
            snapshot(listOf(collision(7, authored, point = IntOffset(2, 2)))),
        ).collisionActions.single()
        val spoken = requireNotNull(action.spokenIdentifier)

        assertEquals(authored, action.authoredIdentifier)
        assertEquals(authored, action.effect.collisionIdentifier)
        assertTrue(spoken.length <= GhostStageSemantics.MAX_SPOKEN_IDENTIFIER_CHARS)
        assertTrue(spoken.none { it.isISOControl() })
        assertTrue(spoken.none { it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 })
    }

    @Test
    fun `named action uses exact representative intrinsic and viewport coordinates`() {
        val transform = SurfaceTransformPx(
            intrinsicSize = IntSize(10, 10),
            renderedBounds = IntRect(10, 20, 111, 222),
            scale = 10f,
            stageToRoot = IntOffset(30, 40),
        )

        val action = GhostStageSemantics.build(
            snapshot(
                collisions = listOf(collision(42, "Face", point = IntOffset(5, 7))),
                transform = transform,
            ),
        ).collisionActions.single()

        assertEquals(IntOffset(5, 7), action.effect.intrinsic)
        assertEquals(IntOffset(66, 172), action.effect.viewportPosition)
        assertEquals(PointerEventKind.CLICK, action.effect.kind)
        assertEquals(PointerSource.TOUCH, action.effect.source)
        assertEquals(0, action.effect.button)
        assertEquals(42, action.effect.diagnosticCollisionId)
    }

    @Test
    fun `named action chooses a contained point from the visible canvas intersection`() {
        val action = GhostStageSemantics.build(
            snapshot(
                collisions = listOf(
                    SurfaceCollision(
                        id = 43,
                        identifier = "Partially clipped",
                        shape = CollisionShape.Rectangle(IntRect(-10, 2, 3, 5)),
                        authoredOrder = 0,
                    ),
                ),
            ),
        ).collisionActions.single()

        assertEquals(IntOffset(0, 2), action.effect.intrinsic)
        assertEquals(IntOffset(10, 30), action.effect.viewportPosition)
    }

    @Test
    fun `partially clipped non-rectangular shapes choose exact visible hit pixels`() {
        val shapes = listOf(
            CollisionShape.Ellipse.fromAuthored(-4, 2, 2, 6),
            CollisionShape.Circle.fromAuthored(-1, 5, 2),
            CollisionShape.Polygon(
                listOf(
                    IntOffset(-5, -5),
                    IntOffset(15, -5),
                    IntOffset(15, 15),
                    IntOffset(-5, 15),
                ),
            ),
        )

        shapes.forEachIndexed { index, shape ->
            val action = GhostStageSemantics.build(
                snapshot(
                    collisions = listOf(
                        SurfaceCollision(index, "shape-$index", shape, index),
                    ),
                ),
            ).collisionActions.single()

            assertTrue(action.effect.intrinsic.x in 0 until 10)
            assertTrue(action.effect.intrinsic.y in 0 until 10)
            assertTrue(shape.contains(action.effect.intrinsic))
        }
    }

    @Test
    fun `generic activation is explicitly collision null even when center is authored collision`() {
        val semantics = GhostStageSemantics.build(
            snapshot(listOf(collision(9, "Center", point = IntOffset(5, 5)))),
        )

        assertNull(semantics.genericAction.effect.collisionIdentifier)
        assertEquals(NO_COLLISION, semantics.genericAction.effect.diagnosticCollisionId)
        assertEquals(IntOffset(5, 5), semantics.genericAction.effect.intrinsic)
        assertEquals(IntOffset(55, 55), semantics.genericAction.effect.viewportPosition)
    }

    @Test
    fun `representative viewport point remains inside a heavily downscaled half-open surface`() {
        val transform = SurfaceTransformPx(
            intrinsicSize = IntSize(10, 10),
            renderedBounds = IntRect(0, 0, 1, 1),
            scale = 0.1f,
            stageToRoot = IntOffset.Zero,
        )

        assertEquals(IntOffset.Zero, transform.stageCenterForIntrinsic(IntOffset(9, 9)))
    }

    @Test
    fun `freshness token captures semantic revision and exact input authority`() {
        val authority = Any()
        val snapshot = snapshot(inputAuthority = authority, revision = 91L)

        val token = GhostStageSemantics.build(snapshot).token

        assertEquals(SurfaceSpeaker.SAKURA, token.speaker)
        assertEquals(snapshot.composedSurface.surfaceKey, token.surfaceKey)
        assertEquals(91L, token.revision)
        assertEquals(authority, token.inputAuthority)
        assertEquals(snapshot.transform, token.transform)
    }

    @Test
    fun `different authored identifiers remain distinguishable after spoken sanitization`() {
        val actions = GhostStageSemantics.build(
            snapshot(
                listOf(
                    collision(1, "Face\u0000", point = IntOffset(1, 1)),
                    collision(2, "Face", point = IntOffset(2, 2)),
                ),
            ),
        ).collisionActions

        assertEquals(
            2,
            actions.map { it.spokenIdentifier to it.spokenDisambiguationOrdinal }.distinct().size,
        )
        assertEquals(listOf("Face\u0000", "Face"), actions.map { it.authoredIdentifier })
    }

    @Test
    fun `control-only identifier requests localized unnamed copy without changing dispatch identity`() {
        val authored = "\u0000\u061c\u202e\u2067"

        val action = GhostStageSemantics.build(
            snapshot(listOf(collision(8, authored, point = IntOffset(2, 2)))),
        ).collisionActions.single()

        assertNull(action.spokenIdentifier)
        assertEquals(authored, action.authoredIdentifier)
        assertEquals(authored, action.effect.collisionIdentifier)
    }

    @Test
    fun `omission logging identity is stable across raster revisions`() {
        val collisions = List(GhostStageSemantics.MAX_COLLISION_ACTIONS + 1) { index ->
            collision(index, "item-$index", point = IntOffset(index, index))
        }

        val first = GhostStageSemantics.build(snapshot(collisions, revision = 1L))
        val nextFrame = GhostStageSemantics.build(snapshot(collisions, revision = 2L))

        assertEquals(first.omissionLogKey, nextFrame.omissionLogKey)
        assertEquals(first.omissionDiagnostic, nextFrame.omissionDiagnostic)
    }

    @Test
    fun `bounded omission gate suppresses repeats and evicts oldest identity`() {
        val gate = OmissionDiagnosticGate(capacity = 2)
        val first = GhostStageOmissionLogKey(SurfaceSpeaker.SAKURA, SurfaceKey(1, IntSize(1, 1)), "a", 0, 1)
        val second = first.copy(surfaceKey = SurfaceKey(2, IntSize(1, 1)))
        val third = first.copy(surfaceKey = SurfaceKey(3, IntSize(1, 1)))

        assertTrue(gate.shouldLog(first))
        assertTrue(!gate.shouldLog(first))
        assertTrue(gate.shouldLog(second))
        assertTrue(gate.shouldLog(third))
        assertTrue(gate.shouldLog(first))
    }

    @Test
    fun `semantic activation revalidates the exact current surface and named action`() {
        val surface = snapshot(listOf(collision(7, "Face", point = IntOffset(2, 3))))
        val semantics = GhostStageSemantics.build(surface)
        val action = semantics.collisionActions.single().effect

        assertEquals(
            action,
            GhostStageSemantics.resolveActivation(
                current = inputSnapshot(surface),
                token = semantics.token,
                proposed = action,
            ),
        )

        val replacement = surface.copy(
            composedSurface = surface.composedSurface.copy(revision = surface.composedSurface.revision + 1),
        )
        assertNull(
            GhostStageSemantics.resolveActivation(
                current = inputSnapshot(replacement),
                token = semantics.token,
                proposed = action,
            ),
        )
    }

    @Test
    fun `semantic activation is blocked by modal bubble or absent tiny surface`() {
        val surface = snapshot(listOf(collision(7, "Face", point = IntOffset(2, 3))))
        val semantics = GhostStageSemantics.build(surface)
        val action = semantics.collisionActions.single().effect
        val covered = BubbleHitRegionRegistry {
            BubbleInteractionTarget.Frame(SurfaceSpeaker.SAKURA)
        }

        assertNull(
            GhostStageSemantics.resolveActivation(
                current = inputSnapshot(surface, blocking = true),
                token = semantics.token,
                proposed = action,
            ),
        )
        assertNull(
            GhostStageSemantics.resolveActivation(
                current = inputSnapshot(surface, bubbleRegistry = covered),
                token = semantics.token,
                proposed = action,
            ),
        )
        assertNull(
            GhostStageSemantics.resolveActivation(
                current = StageInputRouter.snapshot(
                    blocking = false,
                    bubbleRegistry = BubbleHitRegionRegistry.from(emptyList()),
                    bubbleGeneration = 0,
                    ghostKey = "tiny",
                    surfaces = emptyList(),
                ),
                token = semantics.token,
                proposed = action,
            ),
        )
    }

    @Test
    fun `generic activation stays collision null during revalidation`() {
        val surface = snapshot(listOf(collision(9, "Center", point = IntOffset(5, 5))))
        val semantics = GhostStageSemantics.build(surface)

        val resolved = GhostStageSemantics.resolveActivation(
            current = inputSnapshot(surface),
            token = semantics.token,
            proposed = semantics.genericAction.effect,
        )

        assertNull(requireNotNull(resolved).collisionIdentifier)
        assertEquals(NO_COLLISION, resolved.diagnosticCollisionId)
    }

    private fun snapshot(
        collisions: List<SurfaceCollision> = emptyList(),
        transform: SurfaceTransformPx = SurfaceTransformPx(
            IntSize(10, 10),
            IntRect(5, 5, 105, 105),
            10f,
            IntOffset.Zero,
        ),
        inputAuthority: Any = "authority",
        revision: Long = 7L,
    ) = StageSurfaceSnapshot(
        speaker = SurfaceSpeaker.SAKURA,
        composedSurface = ComposedSurface(
            image = SurfacePixelImage.of(10, 10, IntArray(100)),
            canvasSize = IntSize(10, 10),
            visiblePixelBounds = IntRect(0, 0, 10, 10),
            effectiveCollisions = collisions,
            surfaceKey = SurfaceKey(7, IntSize(10, 10)),
            revision = revision,
            explicitlyHidden = false,
            inputAuthority = inputAuthority,
        ),
        transform = transform,
    )

    private fun collision(
        id: Int,
        identifier: String,
        point: IntOffset = IntOffset.Zero,
        representable: Boolean = true,
    ) = SurfaceCollision(
        id = id,
        identifier = identifier,
        shape = if (representable) {
            CollisionShape.Rectangle(IntRect(point.x, point.y, point.x + 1, point.y + 1))
        } else {
            CollisionShape.Rectangle(IntRect.Zero)
        },
        authoredOrder = id,
    )

    private fun inputSnapshot(
        surface: StageSurfaceSnapshot,
        blocking: Boolean = false,
        bubbleRegistry: BubbleHitRegionRegistry = BubbleHitRegionRegistry.from(emptyList()),
    ) = StageInputRouter.snapshot(
        blocking = blocking,
        bubbleRegistry = bubbleRegistry,
        bubbleGeneration = 0,
        ghostKey = "semantics",
        surfaces = listOf(surface),
    )
}
