package com.cattailsw.nanidroid.compose.stage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cattailsw.nanidroid.compose.SurfaceCompositorImage

/** One surface node whose image, input, overlay, semantics, and debug state share [snapshot]. */
@Composable
fun RenderedSurfaceLayer(
    snapshot: StageSurfaceSnapshot,
    showCollisionOverlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val semanticActivation = LocalSemanticStageActivation.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .stageSurfaceSemantics(
                tag = "surface-${snapshot.speaker.name.lowercase()}",
                speaker = snapshot.speaker,
                semanticActivation = semanticActivation,
            ),
    ) {
        SurfaceCompositorImage(
            surface = snapshot.composedSurface,
            transform = snapshot.rendererTransform,
            modifier = Modifier.fillMaxSize(),
        )
        CollisionOverlay(
            collisions = snapshot.composedSurface.effectiveCollisions,
            transform = snapshot.overlayTransform,
            visible = showCollisionOverlay,
        )
    }
}
