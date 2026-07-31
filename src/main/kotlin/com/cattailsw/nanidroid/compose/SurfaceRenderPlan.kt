package com.cattailsw.nanidroid.compose

import com.cattailsw.nanidroid.ShellSurface
import com.cattailsw.nanidroid.SurfaceAnimationFrame
import com.cattailsw.nanidroid.SurfaceDefinition
import com.cattailsw.nanidroid.SurfaceElement

/**
 * Platform-neutral, complete description of how the legacy surface compositor
 * assembles a surface and each animation frame.  This is deliberately a plan,
 * not a renderer: it neither opens image files nor chooses an alternate
 * animation.  A later Compose compositor can consume the same immutable plan
 * without consulting [ShellSurface].
 *
 * The plan records two legacy quirks rather than silently "improving" them:
 * the first element is always anchored at (0, 0), and MOVE frames render the
 * normal surface until move support is implemented.
 */
data class SurfaceRenderPlan(
    val surfaceId: Int?,
    val width: Int,
    val height: Int,
    val base: SurfaceRenderBase,
    val animations: List<SurfaceRenderAnimation>,
) {
    companion object {
        /** Total result for an absent surface, suitable for an empty stage. */
        val Missing = SurfaceRenderPlan(
            surfaceId = null,
            width = 0,
            height = 0,
            base = SurfaceRenderBase.Missing,
            animations = emptyList(),
        )
    }
}

sealed interface SurfaceRenderBase {
    data class Layers(val layers: List<SurfaceRenderLayer>) : SurfaceRenderBase
    data object Missing : SurfaceRenderBase
}

/** A positioned image layer. [imagePath] is intentionally nullable: parsing
 * may describe a missing asset and planning must remain total. */
data class SurfaceRenderLayer(
    val imagePath: String?,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class SurfaceRenderAnimation(
    val id: String,
    val interval: Int,
    val exclusive: Boolean,
    val frames: List<SurfaceRenderFrame>,
    /** Candidates only; legacy selection is random and belongs to scheduling. */
    val alternatives: List<String>,
)

sealed interface SurfaceRenderFrame {
    val durationMillis: Int

    /** Legacy TYPE_RESET redraws the normal surface for this duration. */
    data class Reset(override val durationMillis: Int) : SurfaceRenderFrame

    /** Legacy TYPE_BASE replaces the normal surface with this image. */
    data class Base(
        val imagePath: String?,
        val width: Int,
        val height: Int,
        override val durationMillis: Int,
    ) : SurfaceRenderFrame

    /**
     * Legacy TYPE_OVERLAY starts from the normal surface and places [overlay]
     * over it.  A manager surface id takes precedence over [fallbackImagePath]
     * when a future compositor resolves the reference.
     */
    data class Overlay(
        val sourceSurfaceId: String?,
        val fallbackImagePath: String?,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        override val durationMillis: Int,
    ) : SurfaceRenderFrame

    /**
     * TYPE_MOVE is explicitly represented, while [legacyBaseFallback] captures
     * the current renderer's behavior: it redraws the normal surface and
     * ignores the requested displacement.
     */
    data class Move(
        val x: Int,
        val y: Int,
        override val durationMillis: Int,
        val legacyBaseFallback: Boolean = true,
    ) : SurfaceRenderFrame

    /** Unknown frame kinds retain their timing and parser data without loss. */
    data class Unknown(
        val type: Int,
        val sourceSurfaceId: String?,
        val imagePath: String?,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        override val durationMillis: Int,
    ) : SurfaceRenderFrame
}

/** Builds a renderer-neutral plan and is total for a missing current surface. */
fun SurfaceDefinition?.toSurfaceRenderPlan(): SurfaceRenderPlan {
    val definition = this ?: return SurfaceRenderPlan.Missing
    return SurfaceRenderPlan(
        surfaceId = definition.id,
        width = definition.width,
        height = definition.height,
        base = definition.toRenderBase(),
        animations = definition.animations.map { animation ->
            SurfaceRenderAnimation(
                id = animation.id,
                interval = animation.interval,
                exclusive = animation.exclusive,
                frames = animation.frames.map(::toRenderFrame),
                alternatives = animation.alternativeAnimationIds.toList(),
            )
        },
    )
}

private fun SurfaceDefinition.toRenderBase(): SurfaceRenderBase = when (type) {
    ShellSurface.S_TYPE_BASE -> SurfaceRenderBase.Layers(
        listOf(SurfaceRenderLayer(imagePath, 0, 0, width, height)),
    )
    ShellSurface.S_TYPE_ELEMENT -> SurfaceRenderBase.Layers(
        elements.mapIndexed(::toElementLayer),
    )
    else -> SurfaceRenderBase.Missing
}

private fun toElementLayer(index: Int, element: SurfaceElement): SurfaceRenderLayer =
    SurfaceRenderLayer(
        imagePath = element.imagePath,
        // LayerDrawable leaves its first child at the origin regardless of the
        // parsed offset; later children use their declared offsets.
        x = if (index == 0) 0 else element.x,
        y = if (index == 0) 0 else element.y,
        width = element.width,
        height = element.height,
    )

private fun toRenderFrame(frame: SurfaceAnimationFrame): SurfaceRenderFrame = when (frame.type) {
    ShellSurface.TYPE_RESET -> SurfaceRenderFrame.Reset(frame.durationMillis)
    ShellSurface.TYPE_BASE -> SurfaceRenderFrame.Base(
        imagePath = frame.imagePath,
        width = frame.width,
        height = frame.height,
        durationMillis = frame.durationMillis,
    )
    ShellSurface.TYPE_OVERLAY -> SurfaceRenderFrame.Overlay(
        sourceSurfaceId = frame.sourceSurfaceId,
        fallbackImagePath = frame.imagePath,
        x = frame.x,
        y = frame.y,
        width = frame.width,
        height = frame.height,
        durationMillis = frame.durationMillis,
    )
    ShellSurface.TYPE_MOVE -> SurfaceRenderFrame.Move(
        x = frame.x,
        y = frame.y,
        durationMillis = frame.durationMillis,
    )
    else -> SurfaceRenderFrame.Unknown(
        type = frame.type,
        sourceSurfaceId = frame.sourceSurfaceId,
        imagePath = frame.imagePath,
        x = frame.x,
        y = frame.y,
        width = frame.width,
        height = frame.height,
        durationMillis = frame.durationMillis,
    )
}
