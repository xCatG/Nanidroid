package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import com.cattailsw.nanidroid.SurfaceHitTarget
import com.cattailsw.nanidroid.compose.SurfacePointerInteractionMapper
import com.cattailsw.nanidroid.compose.SurfacePointerPosition
import com.cattailsw.nanidroid.compose.SurfacePointerResolution
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.compose.stage.StageSurfaceSnapshot
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect

sealed interface BubbleInteractionTarget {
    data class Choice(val action: DialogueAction) : BubbleInteractionTarget
    data class Anchor(val id: String, val arguments: List<String>) : BubbleInteractionTarget
    data class ExternalUrl(val uri: String) : BubbleInteractionTarget
    data class Input(val input: DialogueSegment.InputBox) : BubbleInteractionTarget
    data class Scroll(val speaker: SurfaceSpeaker) : BubbleInteractionTarget
    data class Frame(val speaker: SurfaceSpeaker) : BubbleInteractionTarget
}

data class MeasuredBubbleHitRegion(
    val bounds: IntRect,
    val target: BubbleInteractionTarget,
)

fun interface BubbleHitRegionRegistry {
    fun resolve(stagePoint: Offset): BubbleInteractionTarget?

    companion object {
        val Empty = BubbleHitRegionRegistry { null }

        fun from(regions: List<MeasuredBubbleHitRegion>): BubbleHitRegionRegistry {
            val immutable = regions.toList()
            return BubbleHitRegionRegistry { point ->
                immutable.firstOrNull { it.bounds.containsHalfOpen(point) }?.target
            }
        }
    }
}

sealed interface StageInputTarget {
    data object Modal : StageInputTarget
    data class Bubble(val target: BubbleInteractionTarget) : StageInputTarget
    data class Surface(val speaker: SurfaceSpeaker, val hit: SurfaceHitTarget) : StageInputTarget
    data object EmptyStage : StageInputTarget
}

data class StageInputResolution(
    val target: StageInputTarget,
    val effect: SurfaceInteractionEffect? = null,
    val activatable: Boolean = true,
)

data class StageSurfaceHitGeometryToken(
    val speaker: SurfaceSpeaker,
    val surfaceKey: SurfaceKey,
    val inputAuthority: Any,
    val visible: Boolean,
    val transform: SurfaceTransformPx,
    val collisions: List<com.cattailsw.nanidroid.SurfaceCollision>,
)

data class StageHitGeometryToken(
    val ghostIdentity: Any,
    val blocking: Boolean,
    val bubbleGeneration: Long,
    val routingEpoch: Any,
    val surfaces: List<StageSurfaceHitGeometryToken>,
)

data class StageInputSnapshot(
    val blocking: Boolean,
    val bubbleRegistry: BubbleHitRegionRegistry,
    val surfaces: List<StageSurfaceSnapshot>,
    val geometryToken: StageHitGeometryToken,
)

object StageInputRouter {
    fun snapshot(
        blocking: Boolean,
        bubbleRegistry: BubbleHitRegionRegistry,
        bubbleGeneration: Long,
        ghostKey: String,
        surfaces: List<StageSurfaceSnapshot>,
        ghostIdentity: Any = ghostKey,
        routingEpoch: Any = Unit,
    ): StageInputSnapshot {
        val visibleSurfaces = surfaces.filterNot { it.composedSurface.explicitlyHidden }
        return StageInputSnapshot(
            blocking = blocking,
            bubbleRegistry = bubbleRegistry,
            surfaces = visibleSurfaces.toList(),
            geometryToken = StageHitGeometryToken(
                ghostIdentity = ghostIdentity,
                blocking = blocking,
                bubbleGeneration = bubbleGeneration,
                routingEpoch = routingEpoch,
                surfaces = visibleSurfaces.map { surface ->
                    StageSurfaceHitGeometryToken(
                        speaker = surface.speaker,
                        surfaceKey = surface.composedSurface.surfaceKey,
                        inputAuthority = surface.composedSurface.inputAuthority,
                        visible = !surface.composedSurface.explicitlyHidden,
                        transform = surface.transform,
                        collisions = surface.composedSurface.effectiveCollisions.toList(),
                    )
                },
            ),
        )
    }

    fun resolve(
        snapshot: StageInputSnapshot,
        stagePoint: Offset,
        source: PointerSource,
        button: Int,
    ): StageInputResolution {
        if (snapshot.blocking) return StageInputResolution(StageInputTarget.Modal, activatable = false)
        snapshot.bubbleRegistry.resolve(stagePoint)?.let { target ->
            return StageInputResolution(StageInputTarget.Bubble(target), activatable = false)
        }
        if (button != PRIMARY_BUTTON) {
            return StageInputResolution(StageInputTarget.EmptyStage, activatable = false)
        }

        val hits = snapshot.surfaces.mapNotNull { surface ->
            val resolution = SurfacePointerInteractionMapper.map(
                speaker = surface.speaker,
                surface = surface.composedSurface,
                transform = surface.pointerTransform,
                position = SurfacePointerPosition(stagePoint.x, stagePoint.y),
                source = source,
                button = button,
            ) as? SurfacePointerResolution.Hit
            resolution?.let { surface to it }
        }
        val selected = hits.firstOrNull { (_, hit) -> hit.target is SurfaceHitTarget.Collision }
            ?: hits.firstOrNull()
        if (selected != null) {
            val (surface, hit) = selected
            return StageInputResolution(
                StageInputTarget.Surface(surface.speaker, hit.target),
                effect = hit.effect,
            )
        }
        return StageInputResolution(StageInputTarget.EmptyStage)
    }

    private const val PRIMARY_BUTTON = 0
}

private fun IntRect.containsHalfOpen(point: Offset): Boolean =
    point.x >= left && point.x < right && point.y >= top && point.y < bottom
