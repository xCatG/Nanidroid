package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.SurfaceCollision

enum class StageMode { TINY, COMPACT_LANDSCAPE, STANDARD }

enum class StagePosture { FLAT, BOOK, TABLETOP }

enum class StageLayoutDirection { LTR, RTL }

data class StageDpRect(
    val left: Dp,
    val top: Dp,
    val right: Dp,
    val bottom: Dp,
) {
    val width: Dp get() = (right.value - left.value).coerceAtLeast(0f).dp
    val height: Dp get() = (bottom.value - top.value).coerceAtLeast(0f).dp
    val area: Float get() = width.value * height.value

    fun positiveIntersection(other: StageDpRect): StageDpRect? {
        val intersection = StageDpRect(
            maxOf(left.value, other.left.value).dp,
            maxOf(top.value, other.top.value).dp,
            minOf(right.value, other.right.value).dp,
            minOf(bottom.value, other.bottom.value).dp,
        )
        return intersection.takeIf { it.width.value > 0f && it.height.value > 0f }
    }

    fun contains(other: StageDpRect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

data class StageDisplayFeature(
    val bounds: StageDpRect,
    val separating: Boolean,
    val occluding: Boolean,
)

data class StageInputCapabilities(
    val touch: Boolean,
    val mouse: Boolean,
    val stylus: Boolean,
    val hardwareKeyboard: Boolean,
)

data class StageEnvironment(
    val safeBounds: StageDpRect,
    val density: Float,
    val fontScale: Float,
    val canonicalAppBarHeight: Dp,
    val posture: StagePosture,
    val displayFeatures: List<StageDisplayFeature>,
    val inputCapabilities: StageInputCapabilities,
    val layoutDirection: StageLayoutDirection = StageLayoutDirection.LTR,
    val ghostKey: String = "",
) {
    val safeSize: DpSize get() = DpSize(safeBounds.width, safeBounds.height)
}

data class SurfaceKey(
    val surfaceId: Int?,
    val canvasSize: IntSize,
)

data class ComposedSurfaceMetrics(
    val canvasSize: IntSize,
    val visiblePixelBounds: IntRect?,
    val collisions: List<SurfaceCollision>,
    val explicitlyHidden: Boolean,
    val surfaceKey: SurfaceKey,
    val revision: Long,
)

data class StageGeometryKey(
    val ghostKey: String,
    val windowKey: StageWindowKey,
    val mode: StageMode,
    val content: StageDpRect,
    val keroRegion: StageDpRect?,
    val sakuraRegion: StageDpRect?,
)

data class StageWindowKey(
    val safeBounds: StageDpRect,
    val density: Float,
    val canonicalAppBarHeight: Dp,
    val posture: StagePosture,
    val displayFeatures: List<StageDisplayFeature>,
)

data class StageSurfaceAnchor(
    val surfaceKey: SurfaceKey,
    val scale: Float,
    val rect: StageDpRect,
)

data class StageSizingBaseline(
    val geometryKey: StageGeometryKey,
    val sharedAuthoredScale: Float,
    val keroAnchor: StageSurfaceAnchor?,
    val sakuraAnchor: StageSurfaceAnchor?,
)

data class StageLayoutDp(
    val mode: StageMode,
    val content: StageDpRect,
    val keroLane: StageDpRect?,
    val sakuraLane: StageDpRect?,
    val keroBubble: StageDpRect?,
    val sakuraBubble: StageDpRect?,
    val keroSurfaceRegion: StageDpRect?,
    val sakuraSurfaceRegion: StageDpRect?,
    val keroSurface: StageDpRect?,
    val sakuraSurface: StageDpRect?,
    val sizingBaseline: StageSizingBaseline,
    val tinyFallback: Boolean,
)
