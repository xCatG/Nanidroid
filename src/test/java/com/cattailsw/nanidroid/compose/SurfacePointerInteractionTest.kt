package com.cattailsw.nanidroid.compose

import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.SurfaceDefinition
import com.cattailsw.nanidroid.SurfaceHitTarget
import com.cattailsw.nanidroid.surface.CollisionShape
import androidx.compose.ui.unit.IntRect
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
        assertEquals(SurfaceInteractionEffect.MouseDoubleClick(SurfaceSpeaker.SAKURA, 1, 1, 41), resolution.effect)
    }

    @Test
    fun `opaque and transparent noncollision pixels both retain legacy generic double click`() {
        val opaque = map(image = image(0xff001122.toInt()), position = SurfacePointerPosition(0f, 0f)) as SurfacePointerResolution.Hit
        val transparent = map(image = image(0x00000000), position = SurfacePointerPosition(0f, 0f)) as SurfacePointerResolution.Hit
        val unavailable = map(image = null, position = SurfacePointerPosition(0f, 0f)) as SurfacePointerResolution.Hit

        assertSame(SurfaceHitTarget.OpaquePixel, opaque.target)
        assertSame(SurfaceHitTarget.TransparentPixel, transparent.target)
        assertSame(SurfaceHitTarget.PixelUnavailable, unavailable.target)
        assertEquals(-1, opaque.effect.collisionId)
        assertEquals(-1, transparent.effect.collisionId)
        assertEquals(-1, unavailable.effect.collisionId)
    }

    @Test
    fun `transform uses stage origin scale and half open bounds`() {
        val transform = SurfacePointerTransform(10f, 30f, 20f, 10f, 10, 5)
        val resolution = SurfacePointerInteractionMapper.map(
            SurfaceSpeaker.KERO,
            definition(),
            image(0xff000000.toInt(), width = 10, height = 5),
            transform,
            SurfacePointerPosition(16.9f, 34.1f),
        ) as SurfacePointerResolution.Hit

        assertEquals(SurfaceInteractionEffect.MouseDoubleClick(SurfaceSpeaker.KERO, 3, 2, -1), resolution.effect)
        assertSame(SurfacePointerResolution.OutsideSurface, SurfacePointerInteractionMapper.map(
            SurfaceSpeaker.KERO, definition(), null, transform, SurfacePointerPosition(30f, 35f),
        ))
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

    private fun map(
        image: SurfacePixelImage?,
        collisions: List<SurfaceCollision> = emptyList(),
        position: SurfacePointerPosition,
    ) = SurfacePointerInteractionMapper.map(
        SurfaceSpeaker.SAKURA,
        definition(collisions),
        image,
        SurfacePointerTransform(0f, 0f, 2f, 2f, 2, 2),
        position,
    )

    private fun definition(collisions: List<SurfaceCollision> = emptyList()) = SurfaceDefinition(
        id = 0, type = 0, imagePath = null, fallbackImagePath = null,
        width = 2, height = 2, collisions = collisions, animations = emptyList(), elements = emptyList(),
    )

    private fun collision(id: Int, x: Int, y: Int) = SurfaceCollision(
        id = id,
        identifier = "Collision$id",
        shape = CollisionShape.Rectangle(IntRect(x, y, x + 1, y + 1)),
        authoredOrder = 0,
    )
    private fun image(color: Int, width: Int = 2, height: Int = 2) =
        SurfacePixelImage.of(width, height, IntArray(width * height) { color })
}
