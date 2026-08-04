package com.cattailsw.nanidroid.compose.stage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.compose.SurfaceCompositorImage

/** One surface node whose image, input, overlay, semantics, and debug state share [snapshot]. */
@Composable
fun RenderedSurfaceLayer(
    snapshot: StageSurfaceSnapshot,
    showCollisionOverlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val semanticActivation = LocalSemanticStageActivation.current
    val semanticLabel = stringResource(
        when (snapshot.speaker) {
            SurfaceSpeaker.SAKURA -> R.string.sakura_character_description
            SurfaceSpeaker.KERO -> R.string.kero_character_description
        },
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .stageSurfaceSemantics(
                tag = "surface-${snapshot.speaker.name.lowercase()}",
                speaker = snapshot.speaker,
                label = semanticLabel,
                semanticActivation = semanticActivation,
            ),
    ) {
        SurfaceCompositorImage(
            surface = snapshot.composedSurface,
            transform = snapshot.rendererTransform,
            modifier = Modifier.fillMaxSize(),
        )
        if (showCollisionOverlay) {
            CollisionOverlay(
                collisions = snapshot.composedSurface.effectiveCollisions,
                transform = snapshot.overlayTransform,
                visible = true,
            )
        }
    }
}
