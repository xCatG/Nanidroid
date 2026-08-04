package com.cattailsw.nanidroid.compose.debug

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.stage.StageMode

data class DebugPanelState(
    val visible: Boolean = false,
    val selectedSpeaker: SurfaceSpeaker = SurfaceSpeaker.SAKURA,
    val showCollisionOverlay: Boolean = false,
)

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
