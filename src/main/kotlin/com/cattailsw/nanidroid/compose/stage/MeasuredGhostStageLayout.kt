package com.cattailsw.nanidroid.compose.stage

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.cattailsw.nanidroid.compose.ComposedSurface
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.GhostPresentationState
import com.cattailsw.nanidroid.runtime.stage.GhostStageLayoutPolicy
import com.cattailsw.nanidroid.runtime.stage.BubbleRegionFence
import com.cattailsw.nanidroid.runtime.stage.BubbleRegionPublication
import com.cattailsw.nanidroid.runtime.stage.BubbleRegionPublicationPolicy
import com.cattailsw.nanidroid.runtime.stage.BubbleRegionSet
import com.cattailsw.nanidroid.runtime.stage.MeasuredBubbleHitRegion
import com.cattailsw.nanidroid.runtime.stage.StageEnvironment
import com.cattailsw.nanidroid.runtime.stage.StageLayoutDp
import com.cattailsw.nanidroid.runtime.stage.StageLayoutPx
import com.cattailsw.nanidroid.runtime.stage.StageMode
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
    val activeBubbleFences: Map<SurfaceSpeaker, BubbleRegionFence> = emptyMap(),
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
    private var bubblePublication = BubbleRegionPublication.Empty
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
            bubblePublication = BubbleRegionPublication.Empty
            bubbleGeneration = 0L
            committedInputGeometry = null
            inputEpoch++
        }
    }

    internal fun commit(snapshot: StageMeasuredSnapshot) {
        SurfaceSpeaker.entries.forEach { speaker ->
            val publishedFence = bubblePublication.fence(speaker)
            if (publishedFence != null && snapshot.activeBubbleFences[speaker] != publishedFence) {
                bubblePublication = BubbleRegionPublicationPolicy.remove(
                    current = bubblePublication,
                    expectedFence = publishedFence,
                    speaker = speaker,
                )
            }
        }
        bubbleGeneration = bubblePublication.generation
        val committed = if (
            snapshot.bubbleGeneration == bubbleGeneration &&
            snapshot.bubbleRegions == bubblePublication.regions
        ) {
            snapshot
        } else {
            snapshot.copy(
                bubbleRegions = bubblePublication.regions,
                bubbleGeneration = bubbleGeneration,
            )
        }
        updateInputGeometry(committed)
        baseline = committed.layoutDp.sizingBaseline
        latest = committed
    }

    /** Accepts one complete child publication only while its measure fence is active. */
    internal fun publishBubbleRegions(next: BubbleRegionSet): Boolean {
        val speaker = next.fence.speaker
        if (latest?.activeBubbleFences?.get(speaker) != next.fence) return false
        val replacement = BubbleRegionPublicationPolicy.replace(
            current = bubblePublication,
            expectedFence = bubblePublication.fence(speaker),
            next = next,
        )
        if (replacement === bubblePublication) return false
        bubblePublication = replacement
        bubbleGeneration = replacement.generation
        val current = latest ?: return false
        val committed = current.copy(
            bubbleRegions = replacement.regions,
            bubbleGeneration = replacement.generation,
        )
        updateInputGeometry(committed)
        latest = committed
        return true
    }

    private fun updateInputGeometry(snapshot: StageMeasuredSnapshot) {
        val inputGeometry = snapshot.inputGeometry()
        if (committedInputGeometry != inputGeometry) {
            committedInputGeometry = inputGeometry
            inputEpoch++
        }
    }

    private data object UnsetOwner
}

private data class StageMeasuredInputGeometry(
    val activeBubbleFences: Map<SurfaceSpeaker, BubbleRegionFence>,
    val bubbles: List<MeasuredBubbleHitRegion>,
    val surfaces: List<StageSurfaceHitGeometryToken>,
)

private fun StageMeasuredSnapshot.inputGeometry() = StageMeasuredInputGeometry(
    activeBubbleFences = activeBubbleFences.toMap(),
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
    forceKeroBalloon: Boolean = false,
    forceSakuraBalloon: Boolean = false,
    dialogueTalkId: Long = 0L,
    dialogueRevision: Long = 0L,
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
        val activeBubbleFences = buildMap {
            if (showKeroBalloon && (presentation.kero.balloonVisible || forceKeroBalloon)) {
                layoutPx.keroBubble?.let { frame ->
                    put(SurfaceSpeaker.KERO, BubbleRegionFence(SurfaceSpeaker.KERO, dialogueTalkId, dialogueRevision, frame))
                }
            }
            if (showSakuraBalloon && (presentation.sakura.balloonVisible || forceSakuraBalloon)) {
                layoutPx.sakuraBubble?.let { frame ->
                    put(SurfaceSpeaker.SAKURA, BubbleRegionFence(SurfaceSpeaker.SAKURA, dialogueTalkId, dialogueRevision, frame))
                }
            }
        }
        val snapshot = StageMeasuredSnapshot(
            layoutDp = layoutDp,
            layoutPx = layoutPx,
            kero = keroSnapshot,
            sakura = sakuraSnapshot,
            activeBubbleFences = activeBubbleFences,
        )

        // This zero-size slot publishes stable baseline/debug state after a
        // successful composition. Active children already consume [snapshot]
        // directly, so they never wait for a state round trip.
        subcompose(StageSlot.COMMIT) {
            SideEffect { measureState.commit(snapshot) }
            Spacer(Modifier)
        }.forEach { it.measure(Constraints.fixed(0, 0)) }

        val children = buildList {
            add(measureSafeStage(layoutPx.content))
            keroSnapshot?.let { surface ->
                add(measureSurface(StageSlot.KERO_SURFACE, surface, surfaceContent))
            }
            sakuraSnapshot?.let { surface ->
                add(measureSurface(StageSlot.SAKURA_SURFACE, surface, surfaceContent))
            }
            if (showKeroBalloon && (presentation.kero.balloonVisible || forceKeroBalloon)) {
                activeBubbleFences[SurfaceSpeaker.KERO]?.let { fence ->
                    add(
                        measureSlot(
                            StageSlot.KERO_BALLOON,
                            fence,
                            pointerDirection(layoutPx.mode, SurfaceSpeaker.KERO),
                            keroBalloon,
                        ),
                    )
                }
            }
            if (showSakuraBalloon && (presentation.sakura.balloonVisible || forceSakuraBalloon)) {
                activeBubbleFences[SurfaceSpeaker.SAKURA]?.let { fence ->
                    add(
                        measureSlot(
                            StageSlot.SAKURA_BALLOON,
                            fence,
                            pointerDirection(layoutPx.mode, SurfaceSpeaker.SAKURA),
                            sakuraBalloon,
                        ),
                    )
                }
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

private fun androidx.compose.ui.layout.SubcomposeMeasureScope.measureSafeStage(
    bounds: IntRect,
): MeasuredChild = MeasuredChild(
    bounds = bounds,
    placeables = subcompose(StageSlot.SAFE_STAGE) {
        Spacer(Modifier.testTag("ghost-safe-stage"))
    }.map { measurable -> measurable.measure(bounds.fixedConstraints()) },
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

internal val LocalBubbleRegionFence = staticCompositionLocalOf<BubbleRegionFence?> { null }

private fun androidx.compose.ui.layout.SubcomposeMeasureScope.measureSlot(
    slot: StageSlot,
    fence: BubbleRegionFence,
    pointerDirection: BubblePointerDirection,
    content: @Composable () -> Unit,
): MeasuredChild = MeasuredChild(
    fence.frame,
    subcompose(slot) {
        CompositionLocalProvider(
            LocalBubbleRegionFence provides fence,
            LocalBubblePointerDirection provides pointerDirection,
        ) { content() }
    }.map { measurable -> measurable.measure(fence.frame.fixedConstraints()) },
)

private fun pointerDirection(
    mode: StageMode,
    speaker: SurfaceSpeaker,
): BubblePointerDirection = when (mode) {
    StageMode.COMPACT_LANDSCAPE -> when (speaker) {
        SurfaceSpeaker.KERO -> BubblePointerDirection.LEFT
        SurfaceSpeaker.SAKURA -> BubblePointerDirection.RIGHT
    }
    StageMode.STANDARD,
    StageMode.TINY,
    -> BubblePointerDirection.DOWN
}

private fun IntRect.fixedConstraints() = Constraints.fixed(
    width.coerceAtLeast(0),
    height.coerceAtLeast(0),
)

private enum class StageSlot {
    COMMIT,
    SAFE_STAGE,
    KERO_SURFACE,
    SAKURA_SURFACE,
    KERO_BALLOON,
    SAKURA_BALLOON,
}
