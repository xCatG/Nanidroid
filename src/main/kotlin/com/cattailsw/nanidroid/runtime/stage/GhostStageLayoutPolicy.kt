package com.cattailsw.nanidroid.runtime.stage

import androidx.compose.ui.unit.dp

object GhostStageLayoutPolicy {
    fun calculate(
        environment: StageEnvironment,
        kero: ComposedSurfaceMetrics? = null,
        sakura: ComposedSurfaceMetrics? = null,
        previousBaseline: StageSizingBaseline? = null,
    ): StageLayoutDp {
        val candidate = selectStableCandidate(environment)
        val mode = classify(candidate)
        if (mode == StageMode.TINY) return tinyLayout(candidate, environment)

        val contentWidth = minOf(candidate.width.value, MAX_CONTENT_WIDTH_DP)
        val contentLeft = candidate.left.value + (candidate.width.value - contentWidth) / 2f
        val content = StageDpRect(
            contentLeft.dp,
            candidate.top,
            (contentLeft + contentWidth).dp,
            candidate.bottom,
        )
        val geometry = laneGeometry(mode, content)
        val geometryKey = StageGeometryKey(
            ghostKey = environment.ghostKey,
            windowKey = environment.windowKey(),
            mode = mode,
            content = content,
            keroRegion = geometry.keroSurfaceRegion,
            sakuraRegion = geometry.sakuraSurfaceRegion,
        )
        val sizing = SurfaceSizingPolicy.calculate(
            geometryKey = geometryKey,
            keroRegion = requireNotNull(geometry.keroSurfaceRegion),
            sakuraRegion = requireNotNull(geometry.sakuraSurfaceRegion),
            kero = kero,
            sakura = sakura,
            previousBaseline = previousBaseline,
        )
        return StageLayoutDp(
            mode = mode,
            content = content,
            keroLane = geometry.keroLane,
            sakuraLane = geometry.sakuraLane,
            keroBubble = geometry.keroBubble,
            sakuraBubble = geometry.sakuraBubble,
            keroSurfaceRegion = geometry.keroSurfaceRegion,
            sakuraSurfaceRegion = geometry.sakuraSurfaceRegion,
            keroSurface = sizing.kero,
            sakuraSurface = sizing.sakura,
            sizingBaseline = sizing.baseline,
            tinyFallback = false,
        )
    }

    internal fun selectStableCandidate(environment: StageEnvironment): StageDpRect {
        val safe = environment.safeBounds
        val reservedTop = minOf(
            safe.bottom.value,
            safe.top.value + environment.canonicalAppBarHeight.value.coerceAtLeast(0f),
        )
        val available = StageDpRect(safe.left, reservedTop.dp, safe.right, safe.bottom)
        if (available.width.value <= 0f || available.height.value <= 0f) return available

        val blockers = environment.displayFeatures
            .asSequence()
            .filter { it.separating || it.occluding }
            .mapNotNull { feature -> clipFeature(feature, available) }
            .filterNot { feature ->
                !feature.separating &&
                    (feature.bounds.width.value <= 0f || feature.bounds.height.value <= 0f)
            }
            .sortedWith(DISPLAY_FEATURE_ORDER)
            .toList()
        if (blockers.isEmpty()) return available

        val xBoundaries = buildSet {
            add(available.left.value)
            add(available.right.value)
            blockers.forEach { feature ->
                add(feature.bounds.left.value)
                add(feature.bounds.right.value)
            }
        }.sorted()
        val yBoundaries = buildSet {
            add(available.top.value)
            add(available.bottom.value)
            blockers.forEach { feature ->
                add(feature.bounds.top.value)
                add(feature.bounds.bottom.value)
            }
        }.sorted()

        val candidates = mutableListOf<StageDpRect>()
        for (leftIndex in 0 until xBoundaries.lastIndex) {
            for (rightIndex in leftIndex + 1 until xBoundaries.size) {
                for (topIndex in 0 until yBoundaries.lastIndex) {
                    for (bottomIndex in topIndex + 1 until yBoundaries.size) {
                        val candidate = StageDpRect(
                            xBoundaries[leftIndex].dp,
                            yBoundaries[topIndex].dp,
                            xBoundaries[rightIndex].dp,
                            yBoundaries[bottomIndex].dp,
                        )
                        if (blockers.none { feature -> candidate.crosses(feature) }) candidates += candidate
                    }
                }
            }
        }
        val ranked = candidates.sortedWith(CANDIDATE_ORDER)
        return ranked.firstOrNull { classify(it) != StageMode.TINY }
            ?: ranked.firstOrNull()
            ?: StageDpRect(
                available.left,
                available.top,
                available.left,
                available.top,
            )
    }

    private fun classify(candidate: StageDpRect): StageMode {
        val width = candidate.width.value
        val height = candidate.height.value
        val wide = width.toDouble() >= height.toDouble() * WIDE_RATIO
        return when {
            wide && (width < MIN_WIDE_WIDTH_DP || height < MIN_WIDE_HEIGHT_DP) -> StageMode.TINY
            !wide && (width < MIN_TALL_WIDTH_DP || height < MIN_TALL_HEIGHT_DP) -> StageMode.TINY
            wide && width >= MIN_WIDE_WIDTH_DP && height >= MIN_WIDE_HEIGHT_DP && height < COMPACT_HEIGHT_LIMIT_DP ->
                StageMode.COMPACT_LANDSCAPE
            else -> StageMode.STANDARD
        }
    }

    private fun laneGeometry(mode: StageMode, content: StageDpRect): LaneGeometry = when (mode) {
        StageMode.STANDARD -> {
            val middle = content.left.value + content.width.value / 2f
            val bubbleBottom = content.top.value + content.height.value * BUBBLE_HEIGHT_FRACTION
            LaneGeometry(
                keroLane = StageDpRect(content.left, content.top, middle.dp, content.bottom),
                sakuraLane = StageDpRect(middle.dp, content.top, content.right, content.bottom),
                keroBubble = StageDpRect(content.left, content.top, middle.dp, bubbleBottom.dp),
                sakuraBubble = StageDpRect(middle.dp, content.top, content.right, bubbleBottom.dp),
                keroSurfaceRegion = StageDpRect(content.left, bubbleBottom.dp, middle.dp, content.bottom),
                sakuraSurfaceRegion = StageDpRect(middle.dp, bubbleBottom.dp, content.right, content.bottom),
            )
        }
        StageMode.COMPACT_LANDSCAPE -> {
            val centerWidth = if (content.width.value < EQUAL_THIRDS_WIDTH_DP) {
                FIXED_CENTER_WIDTH_DP
            } else {
                content.width.value / 3f
            }
            val outerWidth = (content.width.value - centerWidth) / 2f
            val centerLeft = content.left.value + outerWidth
            val centerRight = centerLeft + centerWidth
            val halfHeight = content.top.value + content.height.value / 2f
            LaneGeometry(
                keroLane = StageDpRect(content.left, content.top, centerLeft.dp, content.bottom),
                sakuraLane = StageDpRect(centerRight.dp, content.top, content.right, content.bottom),
                keroBubble = StageDpRect(centerLeft.dp, content.top, centerRight.dp, halfHeight.dp),
                sakuraBubble = StageDpRect(centerLeft.dp, halfHeight.dp, centerRight.dp, content.bottom),
                keroSurfaceRegion = StageDpRect(content.left, content.top, centerLeft.dp, content.bottom),
                sakuraSurfaceRegion = StageDpRect(centerRight.dp, content.top, content.right, content.bottom),
            )
        }
        StageMode.TINY -> error("tiny mode has no lane geometry")
    }

    private fun tinyLayout(content: StageDpRect, environment: StageEnvironment): StageLayoutDp {
        val geometryKey = StageGeometryKey(
            ghostKey = environment.ghostKey,
            windowKey = environment.windowKey(),
            mode = StageMode.TINY,
            content = content,
            keroRegion = null,
            sakuraRegion = null,
        )
        return StageLayoutDp(
            mode = StageMode.TINY,
            content = content,
            keroLane = null,
            sakuraLane = null,
            keroBubble = null,
            sakuraBubble = null,
            keroSurfaceRegion = null,
            sakuraSurfaceRegion = null,
            keroSurface = null,
            sakuraSurface = null,
            sizingBaseline = StageSizingBaseline(geometryKey, 0f, null, null),
            tinyFallback = true,
        )
    }

    private fun clipFeature(feature: StageDisplayFeature, available: StageDpRect): StageDisplayFeature? {
        val clipped = StageDpRect(
            maxOf(feature.bounds.left.value, available.left.value).dp,
            maxOf(feature.bounds.top.value, available.top.value).dp,
            minOf(feature.bounds.right.value, available.right.value).dp,
            minOf(feature.bounds.bottom.value, available.bottom.value).dp,
        )
        if (clipped.right < clipped.left || clipped.bottom < clipped.top) return null
        if (clipped.width.value == 0f && clipped.height.value == 0f) return null
        return feature.copy(bounds = clipped)
    }

    private fun StageDpRect.crosses(feature: StageDisplayFeature): Boolean {
        if (positiveIntersection(feature.bounds) != null) return true
        if (!feature.separating) return false
        val bounds = feature.bounds
        val crossesVertical = bounds.width.value == 0f &&
            left < bounds.left && right > bounds.left &&
            intervalOverlap(top.value, bottom.value, bounds.top.value, bounds.bottom.value)
        val crossesHorizontal = bounds.height.value == 0f &&
            top < bounds.top && bottom > bounds.top &&
            intervalOverlap(left.value, right.value, bounds.left.value, bounds.right.value)
        return crossesVertical || crossesHorizontal
    }

    private fun intervalOverlap(firstStart: Float, firstEnd: Float, secondStart: Float, secondEnd: Float): Boolean =
        maxOf(firstStart, secondStart) < minOf(firstEnd, secondEnd)

    private fun StageEnvironment.windowKey() = StageWindowKey(
        safeBounds = safeBounds,
        density = density,
        canonicalAppBarHeight = canonicalAppBarHeight,
        posture = posture,
        displayFeatures = displayFeatures.sortedWith(DISPLAY_FEATURE_ORDER),
    )

    private data class LaneGeometry(
        val keroLane: StageDpRect,
        val sakuraLane: StageDpRect,
        val keroBubble: StageDpRect,
        val sakuraBubble: StageDpRect,
        val keroSurfaceRegion: StageDpRect,
        val sakuraSurfaceRegion: StageDpRect,
    )

    private val CANDIDATE_ORDER = compareByDescending<StageDpRect> { it.area }
        .thenBy { it.top.value }
        .thenBy { it.left.value }
        .thenByDescending { it.width.value }
        .thenByDescending { it.height.value }

    private val DISPLAY_FEATURE_ORDER = compareBy<StageDisplayFeature> { it.bounds.top.value }
        .thenBy { it.bounds.left.value }
        .thenBy { it.bounds.bottom.value }
        .thenBy { it.bounds.right.value }
        .thenBy { !it.separating }
        .thenBy { !it.occluding }

    private const val WIDE_RATIO = 1.2
    private const val MIN_WIDE_WIDTH_DP = 420f
    private const val MIN_WIDE_HEIGHT_DP = 240f
    private const val MIN_TALL_WIDTH_DP = 240f
    private const val MIN_TALL_HEIGHT_DP = 320f
    private const val COMPACT_HEIGHT_LIMIT_DP = 480f
    private const val MAX_CONTENT_WIDTH_DP = 960f
    private const val FIXED_CENTER_WIDTH_DP = 180f
    private const val EQUAL_THIRDS_WIDTH_DP = 540f
    private const val BUBBLE_HEIGHT_FRACTION = 0.36f
}
