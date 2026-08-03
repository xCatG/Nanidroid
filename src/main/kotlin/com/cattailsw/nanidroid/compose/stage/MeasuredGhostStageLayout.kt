package com.cattailsw.nanidroid.compose.stage

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.cattailsw.nanidroid.compose.ComposedSurface
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.GhostPresentationState
import com.cattailsw.nanidroid.runtime.stage.GhostStageLayoutPolicy
import com.cattailsw.nanidroid.runtime.stage.BubbleInteractionTarget
import com.cattailsw.nanidroid.runtime.stage.MeasuredBubbleHitRegion
import com.cattailsw.nanidroid.runtime.stage.StageEnvironment
import com.cattailsw.nanidroid.runtime.stage.StageLayoutDp
import com.cattailsw.nanidroid.runtime.stage.StageLayoutPx
import com.cattailsw.nanidroid.runtime.stage.StageSizingBaseline
import com.cattailsw.nanidroid.runtime.stage.StageSurfaceHitGeometryToken
import com.cattailsw.nanidroid.runtime.stage.SurfaceScope
import com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx

/** Exact atomic payload consumed by every presentation concern for one speaker. */
data class StageSurfaceSnapshot(
    val speaker: SurfaceSpeaker,
    val composedSurface: ComposedSurface,
    val transform: SurfaceTransformPx,
) {
    val rendererTransform: SurfaceTransformPx get() = transform
    val pointerTransform: SurfaceTransformPx get() = transform
    val overlayTransform: SurfaceTransformPx get() = transform
    val semanticsTransform: SurfaceTransformPx get() = transform
    val debugTransform: SurfaceTransformPx get() = transform
}

data class StageMeasuredSnapshot(
    val layoutDp: StageLayoutDp,
    val layoutPx: StageLayoutPx,
    val kero: StageSurfaceSnapshot?,
    val sakura: StageSurfaceSnapshot?,
    val bubbleRegions: List<MeasuredBubbleHitRegion> = emptyList(),
    val bubbleGeneration: Long = 0,
)

/**
 * Host-owned stable sizing memory. Commits happen as a Compose side effect,
 * never from the measure pass that computes the active input geometry.
 */
class GhostStageMeasureState {
    private var owner: Any? = UnsetOwner
    internal var baseline: StageSizingBaseline? = null
        private set
    private var committedBubbleRegions: List<MeasuredBubbleHitRegion> = emptyList()
    private var committedInputGeometry: StageMeasuredInputGeometry? = null
    private var bubbleGeneration = 0L
    internal var inputEpoch = 0L
        private set

    var latest: StageMeasuredSnapshot? by mutableStateOf(null)
        private set

    fun resetFor(newOwner: Any?) {
        if (owner !== newOwner) {
            owner = newOwner
            baseline = null
            latest = null
            committedBubbleRegions = emptyList()
            bubbleGeneration = 0L
            committedInputGeometry = null
            inputEpoch++
        }
    }

    internal fun commit(snapshot: StageMeasuredSnapshot) {
        val inputGeometry = snapshot.inputGeometry()
        if (committedInputGeometry != inputGeometry) {
            committedInputGeometry = inputGeometry
            inputEpoch++
        }
        if (committedBubbleRegions != snapshot.bubbleRegions) {
            committedBubbleRegions = snapshot.bubbleRegions
            bubbleGeneration++
        }
        val committed = if (snapshot.bubbleGeneration == bubbleGeneration) {
            snapshot
        } else {
            snapshot.copy(bubbleGeneration = bubbleGeneration)
        }
        baseline = committed.layoutDp.sizingBaseline
        latest = committed
    }

    private data object UnsetOwner
}

private data class StageMeasuredInputGeometry(
    val bubbles: List<MeasuredBubbleHitRegion>,
    val surfaces: List<StageSurfaceHitGeometryToken>,
)

private fun StageMeasuredSnapshot.inputGeometry() = StageMeasuredInputGeometry(
    bubbles = bubbleRegions.toList(),
    surfaces = listOfNotNull(kero, sakura).map { surface ->
        StageSurfaceHitGeometryToken(
            speaker = surface.speaker,
            surfaceKey = surface.composedSurface.surfaceKey,
            inputAuthority = surface.composedSurface.inputAuthority,
            visible = !surface.composedSurface.explicitlyHidden,
            transform = surface.transform,
            collisions = surface.composedSurface.effectiveCollisions.toList(),
        )
    },
)

/**
 * A measure-aware stage: policy, one-time rounding, transforms, subcomposition,
 * measurement, and placement all happen from the same constraints snapshot.
 */
@Composable
fun MeasuredGhostStageLayout(
    presentation: GhostPresentationState,
    environmentForSize: (IntSize) -> StageEnvironment,
    measureState: GhostStageMeasureState,
    kero: ComposedSurface?,
    sakura: ComposedSurface?,
    modifier: Modifier = Modifier,
    stageToRoot: IntOffset = IntOffset.Zero,
    showKeroBalloon: Boolean = true,
    showSakuraBalloon: Boolean = true,
    keroBalloon: @Composable () -> Unit = {},
    sakuraBalloon: @Composable () -> Unit = {},
    surfaceContent: @Composable BoxScope.(StageSurfaceSnapshot) -> Unit = {},
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val stageSize = IntSize(
            constraints.maxWidth.takeUnless { it == Constraints.Infinity } ?: constraints.minWidth,
            constraints.maxHeight.takeUnless { it == Constraints.Infinity } ?: constraints.minHeight,
        )
        val environment = environmentForSize(stageSize)
        val layoutDp = GhostStageLayoutPolicy.calculate(
            environment = environment,
            kero = kero?.metrics(),
            sakura = sakura?.metrics(),
            previousBaseline = measureState.baseline,
        )
        val layoutPx = StageLayoutPx.from(layoutDp, environment.density, stageToRoot)
        val keroSnapshot = kero.snapshot(SurfaceSpeaker.KERO, SurfaceScope.KERO, layoutPx)
        val sakuraSnapshot = sakura.snapshot(SurfaceSpeaker.SAKURA, SurfaceScope.SAKURA, layoutPx)
        val bubbleRegions = buildList {
            if (showKeroBalloon && presentation.kero.balloonVisible) {
                layoutPx.keroBubble?.let { add(MeasuredBubbleHitRegion(it, BubbleInteractionTarget.Frame(SurfaceSpeaker.KERO))) }
            }
            if (showSakuraBalloon && presentation.sakura.balloonVisible) {
                layoutPx.sakuraBubble?.let { add(MeasuredBubbleHitRegion(it, BubbleInteractionTarget.Frame(SurfaceSpeaker.SAKURA))) }
            }
        }
        val snapshot = StageMeasuredSnapshot(layoutDp, layoutPx, keroSnapshot, sakuraSnapshot, bubbleRegions)

        // This zero-size slot publishes stable baseline/debug state after a
        // successful composition. Active children already consume [snapshot]
        // directly, so they never wait for a state round trip.
        subcompose(StageSlot.COMMIT) {
            SideEffect { measureState.commit(snapshot) }
            Spacer(Modifier)
        }.forEach { it.measure(Constraints.fixed(0, 0)) }

        val children = buildList {
            keroSnapshot?.let { surface ->
                add(measureSurface(StageSlot.KERO_SURFACE, surface, surfaceContent))
            }
            sakuraSnapshot?.let { surface ->
                add(measureSurface(StageSlot.SAKURA_SURFACE, surface, surfaceContent))
            }
            if (showKeroBalloon && presentation.kero.balloonVisible) {
                layoutPx.keroBubble?.let { bounds -> add(measureSlot(StageSlot.KERO_BALLOON, bounds, keroBalloon)) }
            }
            if (showSakuraBalloon && presentation.sakura.balloonVisible) {
                layoutPx.sakuraBubble?.let { bounds -> add(measureSlot(StageSlot.SAKURA_BALLOON, bounds, sakuraBalloon)) }
            }
        }

        layout(stageSize.width, stageSize.height) {
            children.forEach { child ->
                child.placeables.forEach { placeable -> placeable.place(child.bounds.left, child.bounds.top) }
            }
        }
    }
}

private fun ComposedSurface?.snapshot(
    speaker: SurfaceSpeaker,
    scope: SurfaceScope,
    layout: StageLayoutPx,
): StageSurfaceSnapshot? {
    this ?: return null
    if (explicitlyHidden) return null
    val transform = layout.transformFor(scope, canvasSize) ?: return null
    return StageSurfaceSnapshot(speaker, this, transform)
}

private data class MeasuredChild(
    val bounds: IntRect,
    val placeables: List<androidx.compose.ui.layout.Placeable>,
)

private fun androidx.compose.ui.layout.SubcomposeMeasureScope.measureSurface(
    slot: StageSlot,
    snapshot: StageSurfaceSnapshot,
    content: @Composable BoxScope.(StageSurfaceSnapshot) -> Unit,
): MeasuredChild {
    val required = snapshot.transform.renderedBounds
    val placeables = subcompose(slot) {
        androidx.compose.foundation.layout.Box { content(snapshot) }
    }.map { measurable -> measurable.measure(required.fixedConstraints()) }
    return MeasuredChild(required, placeables)
}

private fun androidx.compose.ui.layout.SubcomposeMeasureScope.measureSlot(
    slot: StageSlot,
    bounds: IntRect,
    content: @Composable () -> Unit,
): MeasuredChild = MeasuredChild(
    bounds,
    subcompose(slot, content).map { measurable -> measurable.measure(bounds.fixedConstraints()) },
)

private fun IntRect.fixedConstraints() = Constraints.fixed(
    width.coerceAtLeast(0),
    height.coerceAtLeast(0),
)

private enum class StageSlot {
    COMMIT,
    KERO_SURFACE,
    SAKURA_SURFACE,
    KERO_BALLOON,
    SAKURA_BALLOON,
}
