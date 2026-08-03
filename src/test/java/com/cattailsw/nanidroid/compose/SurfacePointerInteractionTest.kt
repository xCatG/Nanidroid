package com.cattailsw.nanidroid.compose

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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfacePointerInteractionTest {
    @Test
    fun `collision rectangles win over transparent pixels and preserve their id`() {
        val resolution = map(
            image = image(0x00000000),
            collisions = listOf(collision(id = 41, x = 1, y = 1)),
            position = SurfacePointerPosition(1f, 1f),
        ) as SurfacePointerResolution.Hit

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
            ),
            resolution.effect,
        )
    }

    @Test
    fun `opaque transparent and unavailable noncollision pixels retain legacy generic click`() {
        val opaque = map(image = image(0xff001122.toInt()), position = SurfacePointerPosition(0f, 0f)) as SurfacePointerResolution.Hit
        val transparent = map(image = image(0x00000000), position = SurfacePointerPosition(0f, 0f)) as SurfacePointerResolution.Hit
        val unavailable = map(image = null, position = SurfacePointerPosition(0f, 0f)) as SurfacePointerResolution.Hit

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
        val resolution = SurfacePointerInteractionMapper.map(
            SurfaceSpeaker.KERO,
            surface(image(0xff000000.toInt(), width = 10, height = 5)),
            transform,
            SurfacePointerPosition(16.9f, 34.1f),
            PointerSource.TOUCH,
        ) as SurfacePointerResolution.Hit

        assertEquals(
            SurfaceInteractionEffect(
                PointerEventKind.CLICK,
                SurfaceSpeaker.KERO,
                IntOffset(3, 2),
                0,
                PointerSource.TOUCH,
                null,
                -1,
            ),
            resolution.effect,
        )
        assertSame(
            SurfacePointerResolution.OutsideSurface,
            SurfacePointerInteractionMapper.map(
                SurfaceSpeaker.KERO,
                surface(null),
                transform,
                SurfacePointerPosition(30f, 35f),
                PointerSource.TOUCH,
            ),
        )
    }

    @Test
    fun `port receives only typed in bounds effects`() {
        val delivered = mutableListOf<SurfaceInteractionEffect>()
        val dispatcher = SurfacePointerInteractionDispatcher(SurfaceInteractionPort(delivered::add))
        val hit = map(image = image(0xff000000.toInt()), position = SurfacePointerPosition(0f, 0f))

        dispatcher.dispatch(hit)
        dispatcher.dispatch(SurfacePointerResolution.OutsideSurface)

        assertEquals(listOf((hit as SurfacePointerResolution.Hit).effect), delivered)
        assertTrue(hit.effect.speaker.legacyReference == "0")
    }

    @Test
    fun `unknown pointer source emits no effect rather than defaulting to touch`() {
        assertSame(
            SurfacePointerResolution.UnsupportedPointerSource,
            SurfacePointerInteractionMapper.map(
                SurfaceSpeaker.SAKURA,
                surface(image(0xff000000.toInt())),
                SurfaceTransformPx(IntSize(2, 2), IntRect(0, 0, 2, 2), 1f, IntOffset.Zero),
                SurfacePointerPosition(0f, 0f),
                null,
            ),
        )
    }

    private fun map(
        image: SurfacePixelImage?,
        collisions: List<SurfaceCollision> = emptyList(),
        position: SurfacePointerPosition,
    ) = SurfacePointerInteractionMapper.map(
        SurfaceSpeaker.SAKURA,
        surface(image, collisions),
        SurfaceTransformPx(IntSize(2, 2), IntRect(0, 0, 2, 2), 1f, IntOffset.Zero),
        position,
        PointerSource.TOUCH,
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
