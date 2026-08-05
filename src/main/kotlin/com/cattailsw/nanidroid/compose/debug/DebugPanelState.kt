package com.cattailsw.nanidroid.compose.debug

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.compose.stage.StageMeasuredSnapshot
import com.cattailsw.nanidroid.runtime.GhostPresentationRuntimeState
import com.cattailsw.nanidroid.runtime.stage.StageMode

data class DebugPanelState(
    val visible: Boolean = false,
    val selectedSpeaker: SurfaceSpeaker = SurfaceSpeaker.SAKURA,
    val showCollisionOverlay: Boolean = false,
    val sampleQueued: Boolean = false,
)

fun DebugPanelState.collisionOverlaySpeaker(
    loading: Boolean,
    debugBuild: Boolean,
): SurfaceSpeaker? = selectedSpeaker.takeIf {
    !loading && debugBuild && showCollisionOverlay
}

fun DebugPanelState.dismissDebugSurface(): DebugPanelState = copy(visible = false)

enum class DebugPresentation {
    FULL_STAGE_MODAL,
    BOTTOM_SHEET,
    SIDE_PANEL,
}

data class SurfaceDebugSelection(
    val speaker: SurfaceSpeaker,
    val scope: String,
    val surfaceId: String,
    val intrinsicWidth: Int,
    val intrinsicHeight: Int,
    val composedLeft: Int,
    val composedTop: Int,
    val composedRight: Int,
    val composedBottom: Int,
    val composedWidth: Int,
    val composedHeight: Int,
    val visibleLeft: Int,
    val visibleTop: Int,
    val visibleRight: Int,
    val visibleBottom: Int,
    val animationId: String?,
    val visible: Boolean,
    val animationRunning: Boolean,
    val revision: Long,
)

data class SurfacePointerDebugEvent(
    val speaker: SurfaceSpeaker,
    val viewportX: Int,
    val viewportY: Int,
    val sourceX: Int,
    val sourceY: Int,
    val collisionId: Int,
    val collisionName: String?,
    val buttonId: Int,
    val eventName: String,
    val source: String,
)

data class DebugAvailabilityPolicy(private val isDebuggable: Boolean) {
    val showDebugIcon: Boolean get() = isDebuggable
    val exposeDebugSemantics: Boolean get() = isDebuggable
}

private val sidePanelMinWidth = 840.dp

fun resolveDebugPresentation(width: Dp, stageMode: StageMode): DebugPresentation = when {
    stageMode == StageMode.COMPACT_LANDSCAPE -> DebugPresentation.FULL_STAGE_MODAL
    width < sidePanelMinWidth -> DebugPresentation.BOTTOM_SHEET
    else -> DebugPresentation.SIDE_PANEL
}

fun StageMeasuredSnapshot?.debugSelection(
    selectedSpeaker: SurfaceSpeaker,
    runtime: GhostPresentationRuntimeState,
): SurfaceDebugSelection? {
    val snapshot = when (selectedSpeaker) {
        SurfaceSpeaker.SAKURA -> this?.sakura
        SurfaceSpeaker.KERO -> this?.kero
    } ?: return null
    val presentation = when (selectedSpeaker) {
        SurfaceSpeaker.SAKURA -> runtime.presentation.sakura
        SurfaceSpeaker.KERO -> runtime.presentation.kero
    }
    val rendered = snapshot.debugTransform.renderedBounds
    val visible = snapshot.composedSurface.visiblePixelBounds
    return SurfaceDebugSelection(
        speaker = selectedSpeaker,
        scope = selectedSpeaker.legacyReference,
        surfaceId = presentation.surfaceId,
        intrinsicWidth = snapshot.debugTransform.intrinsicSize.width,
        intrinsicHeight = snapshot.debugTransform.intrinsicSize.height,
        composedLeft = rendered.left,
        composedTop = rendered.top,
        composedRight = rendered.right,
        composedBottom = rendered.bottom,
        composedWidth = rendered.width,
        composedHeight = rendered.height,
        visibleLeft = visible?.left ?: 0,
        visibleTop = visible?.top ?: 0,
        visibleRight = visible?.right ?: 0,
        visibleBottom = visible?.bottom ?: 0,
        animationId = presentation.animationId,
        visible = !snapshot.composedSurface.explicitlyHidden,
        animationRunning = presentation.animationId != null,
        revision = snapshot.composedSurface.revision,
    )
}
