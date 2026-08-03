package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.unit.dp

object SurfaceSizingPolicy {
    fun calculate(
        geometryKey: StageGeometryKey,
        keroRegion: StageDpRect,
        sakuraRegion: StageDpRect,
        kero: ComposedSurfaceMetrics?,
        sakura: ComposedSurfaceMetrics?,
        previousBaseline: StageSizingBaseline?,
    ): SurfaceSizingResult {
        val keroCandidate = candidate(kero, keroRegion)
        val sakuraCandidate = candidate(sakura, sakuraRegion)
        val maxScales = listOfNotNull(keroCandidate?.maxScale, sakuraCandidate?.maxScale)
        val prior = previousBaseline?.takeIf { it.geometryKey == geometryKey }
        val sharedScale = prior?.sharedAuthoredScale
            ?.takeIf { it.isFinite() && it > 0f }
            ?: maxScales.minOrNull()
            ?: 0f

        val keroAnchor = anchor(
            candidate = keroCandidate,
            region = keroRegion,
            sharedScale = sharedScale,
            prior = prior?.keroAnchor,
        )
        val sakuraAnchor = anchor(
            candidate = sakuraCandidate,
            region = sakuraRegion,
            sharedScale = sharedScale,
            prior = prior?.sakuraAnchor,
        )
        val baseline = StageSizingBaseline(
            geometryKey = geometryKey,
            sharedAuthoredScale = sharedScale,
            keroAnchor = keroAnchor,
            sakuraAnchor = sakuraAnchor,
        )
        return SurfaceSizingResult(keroAnchor?.rect, sakuraAnchor?.rect, baseline)
    }

    private fun candidate(metrics: ComposedSurfaceMetrics?, region: StageDpRect): SizingCandidate? {
        metrics ?: return null
        if (metrics.explicitlyHidden) return null
        val width = metrics.canvasSize.width
        val height = metrics.canvasSize.height
        if (width <= 0 || height <= 0) return null
        val visible = metrics.visiblePixelBounds?.takeIf { it.width > 0 && it.height > 0 }
        if (visible == null && metrics.collisions.isEmpty()) return null
        val maxScale = minOf(region.width.value / width, region.height.value / height)
        if (!maxScale.isFinite() || maxScale <= 0f) return null
        return SizingCandidate(metrics, visible?.let { minOf(it.width, it.height) }, maxScale)
    }

    private fun anchor(
        candidate: SizingCandidate?,
        region: StageDpRect,
        sharedScale: Float,
        prior: StageSurfaceAnchor?,
    ): StageSurfaceAnchor? {
        candidate ?: return null
        prior?.takeIf { it.surfaceKey == candidate.metrics.surfaceKey }?.let { return it }

        val baseScale = minOf(sharedScale, candidate.maxScale)
        val visibleFloorScale = candidate.visibleShortSide
            ?.takeIf { it > 0 }
            ?.let { MIN_VISIBLE_SHORT_SIDE_DP / it }
            ?: baseScale
        val scale = maxOf(baseScale, visibleFloorScale)
            .coerceAtMost(candidate.maxScale)
            .coerceAtMost(sharedScale * MAX_INDEPENDENT_BOOST)
            .coerceAtLeast(0f)
        val width = candidate.metrics.canvasSize.width * scale
        val height = candidate.metrics.canvasSize.height * scale
        val left = maxOf(region.left.value, region.left.value + (region.width.value - width) / 2f)
        val top = maxOf(region.top.value, region.bottom.value - height)
        val right = minOf(region.right.value, left + width)
        val rect = StageDpRect(
            left.dp,
            top.dp,
            right.dp,
            region.bottom,
        )
        return StageSurfaceAnchor(candidate.metrics.surfaceKey, scale, rect)
    }

    private data class SizingCandidate(
        val metrics: ComposedSurfaceMetrics,
        val visibleShortSide: Int?,
        val maxScale: Float,
    )

    private const val MIN_VISIBLE_SHORT_SIDE_DP = 96f
    private const val MAX_INDEPENDENT_BOOST = 2f
}

data class SurfaceSizingResult(
    val kero: StageDpRect?,
    val sakura: StageDpRect?,
    val baseline: StageSizingBaseline,
)
