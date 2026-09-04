package com.cattailsw.nanidroid.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.SurfaceHitTarget
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey
import com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx
import com.cattailsw.nanidroid.surface.CollisionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SurfacePointerInteractionTest {
    @Test
    fun `first collision wins over later collisions and transparent pixels`() {
        val resolution = map(
            image = image(0x00000000),
            collisions = listOf(
                collision(id = 41, x = 1, y = 1),
                collision(id = 42, x = 1, y = 1),
            ),
            position = Offset(1f, 1f),
        )

        assertEquals(SurfaceHitTarget.Collision(41, "Collision41"), resolution.target)
        assertEquals(
            SurfaceInteractionEffect(
                PointerEventKind.CLICK,
                SurfaceSpeaker.SAKURA,
                IntOffset(1, 1),
                0,
                PointerSource.TOUCH,
                "Collision41",
                41,
                viewportPosition = IntOffset(1, 1),
            ),
            resolution.effect,
        )
    }

    @Test
    fun `opaque transparent and unavailable noncollision pixels retain legacy generic click`() {
        val opaque = map(image = image(0xff001122.toInt()), position = Offset.Zero)
        val transparent = map(image = image(0x00000000), position = Offset.Zero)
        val unavailable = map(image = null, position = Offset.Zero)

        assertSame(SurfaceHitTarget.OpaquePixel, opaque.target)
        assertSame(SurfaceHitTarget.TransparentPixel, transparent.target)
        assertSame(SurfaceHitTarget.PixelUnavailable, unavailable.target)
        assertEquals(null, opaque.effect.collisionIdentifier)
        assertEquals(null, transparent.effect.collisionIdentifier)
        assertEquals(null, unavailable.effect.collisionIdentifier)
        assertEquals(-1, opaque.effect.diagnosticCollisionId)
        assertEquals(-1, transparent.effect.diagnosticCollisionId)
        assertEquals(-1, unavailable.effect.diagnosticCollisionId)
    }

    @Test
    fun `shared transform uses stage origin exact scale and half open bounds`() {
        val transform = SurfaceTransformPx(IntSize(10, 5), IntRect(10, 30, 30, 40), 2f, IntOffset.Zero)
        val resolution = requireNotNull(SurfacePointerInteractionMapper.map(
            SurfaceSpeaker.KERO,
            surface(image(0xff000000.toInt(), width = 10, height = 5)),
            transform,
            Offset(16.9f, 34.1f),
            PointerSource.TOUCH,
        ))

        assertEquals(
            SurfaceInteractionEffect(
                PointerEventKind.CLICK,
                SurfaceSpeaker.KERO,
                IntOffset(3, 2),
                0,
                PointerSource.TOUCH,
                null,
                -1,
                viewportPosition = IntOffset(17, 34),
            ),
            resolution.effect,
        )
        assertNull(
            SurfacePointerInteractionMapper.map(
                SurfaceSpeaker.KERO,
                surface(null),
                transform,
                Offset(30f, 35f),
                PointerSource.TOUCH,
            ),
        )
    }

    @Test
    fun `mapper retains event-local physical source and primary button`() {
        val resolution = requireNotNull(SurfacePointerInteractionMapper.map(
            SurfaceSpeaker.KERO,
            surface(image(0xff000000.toInt())),
            SurfaceTransformPx(IntSize(2, 2), IntRect(0, 0, 2, 2), 1f, IntOffset.Zero),
            Offset(1f, 1f),
            PointerSource.ERASER,
            button = 0,
        ))

        assertEquals(PointerSource.ERASER, resolution.effect.source)
        assertEquals(0, resolution.effect.button)
        assertEquals("", resolution.effect.collisionIdentifier.orEmpty())
    }

    private fun map(
        image: SurfacePixelImage?,
        collisions: List<SurfaceCollision> = emptyList(),
        position: Offset,
    ) = requireNotNull(
        SurfacePointerInteractionMapper.map(
            SurfaceSpeaker.SAKURA,
            surface(image, collisions),
            SurfaceTransformPx(IntSize(2, 2), IntRect(0, 0, 2, 2), 1f, IntOffset.Zero),
            position,
            PointerSource.TOUCH,
        ),
    )

    private fun surface(
        image: SurfacePixelImage?,
        collisions: List<SurfaceCollision> = emptyList(),
    ): ComposedSurface {
        val canvas = image?.let { IntSize(it.width, it.height) } ?: IntSize(2, 2)
        return ComposedSurface(
            image = image ?: SurfacePixelImage.Empty,
            canvasSize = canvas,
            visiblePixelBounds = image?.let { IntRect(0, 0, it.width, it.height) },
            effectiveCollisions = collisions,
            surfaceKey = SurfaceKey(0, canvas),
            revision = 0,
            explicitlyHidden = false,
        )
    }

    private fun collision(id: Int, x: Int, y: Int) = SurfaceCollision(
        id = id,
        identifier = "Collision$id",
        shape = CollisionShape.Rectangle(IntRect(x, y, x + 1, y + 1)),
        authoredOrder = 0,
    )

    private fun image(color: Int, width: Int = 2, height: Int = 2) =
        SurfacePixelImage.of(width, height, IntArray(width * height) { color })
}
