package com.cattailsw.nanidroid.compose.stage

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.cattailsw.nanidroid.compose.SurfaceCompositorImage
import com.cattailsw.nanidroid.compose.SurfaceInteractionPort
import com.cattailsw.nanidroid.compose.SurfacePointerInteractionDispatcher
import com.cattailsw.nanidroid.compose.SurfacePointerInteractionMapper
import com.cattailsw.nanidroid.compose.SurfacePointerPosition
import com.cattailsw.nanidroid.compose.stagePositionFromLocal
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource

/** One surface node whose image, input, overlay, semantics, and debug state share [snapshot]. */
@Composable
fun RenderedSurfaceLayer(
    snapshot: StageSurfaceSnapshot,
    interactionPort: SurfaceInteractionPort,
    onSurfaceTap: () -> Unit,
    showCollisionOverlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestPort by rememberUpdatedState(interactionPort)
    val latestTap by rememberUpdatedState(onSurfaceTap)
    fun dispatch(active: StageSurfaceSnapshot, stageX: Float, stageY: Float) {
        SurfacePointerInteractionDispatcher(latestPort).dispatch(
            SurfacePointerInteractionMapper.map(
                speaker = active.speaker,
                surface = active.composedSurface,
                transform = active.pointerTransform,
                position = SurfacePointerPosition(stageX, stageY),
                source = PointerSource.TOUCH,
            ),
        )
        latestTap()
    }
    val bounds = snapshot.transform.renderedBounds
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("surface-${snapshot.speaker.name.lowercase()}")
            .semantics {
                onClick {
                    val stage = snapshot.transform.stagePositionFromLocal(
                        Offset(bounds.width / 2f, bounds.height / 2f),
                    )
                    dispatch(snapshot, stage.x, stage.y)
                    true
                }
            }
            .pointerInput(snapshot.speaker, snapshot.transform, snapshot.composedSurface.surfaceKey) {
                detectTapGestures { local ->
                    // Pointer coordinates are local to this measured surface;
                    // the shared inverse accepts stage coordinates.
                    val active = latestSnapshot
                    val stage = active.transform.stagePositionFromLocal(local)
                    dispatch(active, stage.x, stage.y)
                }
            },
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
