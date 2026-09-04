package com.cattailsw.nanidroid.compose

import com.cattailsw.nanidroid.ShellSurface
import com.cattailsw.nanidroid.SurfaceAnimation
import com.cattailsw.nanidroid.SurfaceAnimationFrame
import com.cattailsw.nanidroid.SurfaceDefinition
import com.cattailsw.nanidroid.SurfaceElement
import com.cattailsw.nanidroid.SurfaceTransparencyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceRenderPlanTest {
    @Test
    fun `base and element fixtures preserve legacy layer geometry`() {
        val base = surface(type = ShellSurface.S_TYPE_BASE, imagePath = "surface0.png")
        val basePlan = base.toSurfaceRenderPlan()
        assertEquals(
            listOf(SurfaceRenderLayer("surface0.png", 0, 0, 100, 80)),
            (basePlan.base as SurfaceRenderBase.Layers).layers,
        )

        val elements = surface(
            type = ShellSurface.S_TYPE_ELEMENT,
            imagePath = null,
            elements = listOf(
                SurfaceElement(0, "base.png", 19, 23, 100, 80),
                SurfaceElement(1, "hat.png", -4, 7, 20, 30),
            ),
        )
        assertEquals(
            listOf(
                SurfaceRenderLayer("base.png", 0, 0, 100, 80),
                SurfaceRenderLayer("hat.png", -4, 7, 20, 30),
            ),
            (elements.toSurfaceRenderPlan().base as SurfaceRenderBase.Layers).layers,
        )
    }

    @Test
    fun `animation plan preserves reset overlay base move timing and policy`() {
        val frames = listOf(
            frame(type = ShellSurface.TYPE_RESET, duration = 37, x = 9, y = 8),
            frame(
                type = ShellSurface.TYPE_OVERLAY,
                duration = 83,
                sourceSurfaceId = "12",
                imagePath = "ignored-when-12-exists.png",
                x = -4,
                y = 7,
                width = 20,
                height = 30,
            ),
            frame(type = ShellSurface.TYPE_BASE, duration = 101, imagePath = "replacement.png", width = 40, height = 50),
            frame(type = ShellSurface.TYPE_MOVE, duration = 149, x = 11, y = -13),
        )
        val plan = surface(
            animations = listOf(SurfaceAnimation("talk", 2, true, frames)),
        ).toSurfaceRenderPlan().animations.single()

        assertEquals("talk", plan.id)
        assertEquals(2, plan.interval)
        assertTrue(plan.exclusive)
        assertEquals(SurfaceRenderFrame.Reset(37), plan.frames[0])
        assertEquals(
            SurfaceRenderFrame.Overlay("12", "ignored-when-12-exists.png", -4, 7, 20, 30, 83),
            plan.frames[1],
        )
        assertEquals(SurfaceRenderFrame.Base(null, "replacement.png", 40, 50, 101), plan.frames[2])
        assertEquals(SurfaceRenderFrame.Move(11, -13, 149), plan.frames[3])
    }

    @Test
    fun `alternate candidates stay deterministic data and do not select a target`() {
        val plan = surface(
            animations = listOf(
                SurfaceAnimation("alt", 0, false, emptyList(), listOf("1", "2")),
            ),
        ).toSurfaceRenderPlan().animations.single()

        assertEquals(emptyList<SurfaceRenderFrame>(), plan.frames)
        assertEquals(listOf("1", "2"), plan.alternatives)
        assertFalse(plan.exclusive)
    }

    @Test
    fun `missing and unknown inputs remain representable`() {
        assertSame(SurfaceRenderPlan.Missing, (null as SurfaceDefinition?).toSurfaceRenderPlan())
        val unknown = surface(type = 99, animations = listOf(
            SurfaceAnimation("unknown", 1, false, listOf(frame(type = 88, duration = 3))),
        )).toSurfaceRenderPlan()
        assertSame(SurfaceRenderBase.Missing, unknown.base)
        assertEquals(SurfaceRenderFrame.Unknown(88, null, null, 0, 0, 0, 0, 3), unknown.animations.single().frames.single())
    }

    @Test
    fun `Snake BASE frame retains source surface identity 3031`() {
        val planned = surface(
            animations = listOf(
                SurfaceAnimation(
                    "31",
                    0,
                    false,
                    listOf(frame(type = ShellSurface.TYPE_BASE, duration = 100, sourceSurfaceId = "3031")),
                ),
            ),
        ).toSurfaceRenderPlan().animations.single().frames.single()

        assertEquals(SurfaceRenderFrame.Base("3031", null, 0, 0, 100), planned)
    }

    @Test
    fun `surface render plan carries explicit shell transparency policy`() {
        val planned = surface(
            transparencyPolicy = SurfaceTransparencyPolicy.AUTHORED_ALPHA,
        ).toSurfaceRenderPlan()

        assertEquals(SurfaceTransparencyPolicy.AUTHORED_ALPHA, planned.transparencyPolicy)
    }

    private fun surface(
        type: Int = ShellSurface.S_TYPE_BASE,
        imagePath: String? = "surface.png",
        elements: List<SurfaceElement> = emptyList(),
        animations: List<SurfaceAnimation> = emptyList(),
        transparencyPolicy: SurfaceTransparencyPolicy = SurfaceTransparencyPolicy.LEGACY_COLOR_KEY,
    ) = SurfaceDefinition(
        7,
        type,
        imagePath,
        "surface0007.png",
        100,
        80,
        emptyList(),
        animations,
        elements,
        transparencyPolicy,
    )

    private fun frame(
        type: Int,
        duration: Int,
        sourceSurfaceId: String? = null,
        imagePath: String? = null,
        x: Int = 0,
        y: Int = 0,
        width: Int = 0,
        height: Int = 0,
    ) = SurfaceAnimationFrame(0, sourceSurfaceId, imagePath, type, duration, x, y, width, height)
}
