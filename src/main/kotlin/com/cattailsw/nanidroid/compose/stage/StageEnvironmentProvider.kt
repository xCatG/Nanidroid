package com.cattailsw.nanidroid.compose.stage

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.InputDevice
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.core.layout.WindowSizeClass
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.cattailsw.nanidroid.runtime.stage.StageDisplayFeature
import com.cattailsw.nanidroid.runtime.stage.StageDpRect
import com.cattailsw.nanidroid.runtime.stage.StageEnvironment
import com.cattailsw.nanidroid.runtime.stage.StageInputCapabilities
import com.cattailsw.nanidroid.runtime.stage.StageLayoutDirection
import com.cattailsw.nanidroid.runtime.stage.StagePosture
import kotlinx.coroutines.flow.Flow

data class IntInsetsPx(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class StageWindowFeature(
    val bounds: IntRect,
    val separating: Boolean,
    val occluding: Boolean,
    val orientation: FeatureOrientation,
    val halfOpened: Boolean,
)

enum class FeatureOrientation { VERTICAL, HORIZONTAL, UNKNOWN }

/** Injectable lifecycle-safe source; production delegates directly to WindowInfoTracker. */
internal fun interface WindowLayoutInfoSource {
    fun layoutInfo(activity: Activity): Flow<WindowLayoutInfo>
}

/** Window-local adaptive facts. IME is retained only for diagnostics and never classification. */
data class StageWindowEnvironment(
    val windowSizePx: IntSize,
    val safeBoundsInWindowPx: IntRect,
    val density: Float,
    val fontScale: Float,
    val canonicalWindowSizeClass: WindowSizeClass?,
    val posture: StagePosture,
    val displayFeatures: List<StageWindowFeature>,
    val inputCapabilities: StageInputCapabilities,
    val layoutDirection: StageLayoutDirection,
    val imeInsetsPx: IntInsetsPx,
) {
    fun toStageEnvironment(
        stageBoundsInWindowPx: IntRect,
        canonicalAppBarHeight: Dp,
        ghostKey: String,
    ): StageEnvironment {
        require(density.isFinite() && density > 0f)
        val safeIntersection = intersect(safeBoundsInWindowPx, stageBoundsInWindowPx)
            ?: IntRect(
                stageBoundsInWindowPx.left,
                stageBoundsInWindowPx.top,
                stageBoundsInWindowPx.left,
                stageBoundsInWindowPx.top,
            )
        fun x(value: Int) = ((value.toLong() - stageBoundsInWindowPx.left.toLong()) / density.toDouble()).toFloat().dp
        fun y(value: Int) = ((value.toLong() - stageBoundsInWindowPx.top.toLong()) / density.toDouble()).toFloat().dp
        val safe = StageDpRect(
            x(safeIntersection.left),
            y(safeIntersection.top),
            x(safeIntersection.right),
            y(safeIntersection.bottom),
        )
        val features = displayFeatures.map { feature ->
            StageDisplayFeature(
                bounds = StageDpRect(
                    x(feature.bounds.left),
                    y(feature.bounds.top),
                    x(feature.bounds.right),
                    y(feature.bounds.bottom),
                ),
                separating = feature.separating,
                occluding = feature.occluding,
            )
        }
        return StageEnvironment(
            safeBounds = safe,
            density = density,
            fontScale = fontScale,
            canonicalAppBarHeight = canonicalAppBarHeight,
            posture = posture,
            displayFeatures = features,
            inputCapabilities = inputCapabilities,
            layoutDirection = layoutDirection,
            ghostKey = ghostKey,
        )
    }

    companion object {
        fun forTest(
            windowSizePx: IntSize,
            safeBoundsInWindowPx: IntRect,
            density: Float,
            fontScale: Float,
            displayFeatures: List<DisplayFeature>,
            imeInsetsPx: IntInsetsPx = IntInsetsPx(0, 0, 0, 0),
        ) = create(
            windowSizePx = windowSizePx,
            safeBoundsInWindowPx = safeBoundsInWindowPx,
            density = density,
            fontScale = fontScale,
            canonicalWindowSizeClass = null,
            displayFeatures = displayFeatures,
            inputCapabilities = StageInputCapabilities(true, false, false, false),
            layoutDirection = StageLayoutDirection.LTR,
            imeInsetsPx = imeInsetsPx,
        )
    }
}

@Composable
fun StageEnvironmentProvider(
    content: @Composable (StageWindowEnvironment) -> Unit,
) = StageEnvironmentProviderImpl(
    windowLayoutInfoSource = null,
    content = content,
)

@Composable
internal fun StageEnvironmentProvider(
    windowLayoutInfoSource: WindowLayoutInfoSource,
    content: @Composable (StageWindowEnvironment) -> Unit,
) = StageEnvironmentProviderImpl(
    windowLayoutInfoSource = windowLayoutInfoSource,
    content = content,
)

@Composable
private fun StageEnvironmentProviderImpl(
    windowLayoutInfoSource: WindowLayoutInfoSource?,
    content: @Composable (StageWindowEnvironment) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val windowSize = currentWindowSize()
    val safeDrawing = WindowInsets.safeDrawing
    val ime = WindowInsets.ime
    val safeInsets = IntInsetsPx(
        safeDrawing.getLeft(density, layoutDirection),
        safeDrawing.getTop(density),
        safeDrawing.getRight(density, layoutDirection),
        safeDrawing.getBottom(density),
    )
    val imeInsets = IntInsetsPx(
        ime.getLeft(density, layoutDirection),
        ime.getTop(density),
        ime.getRight(density, layoutDirection),
        ime.getBottom(density),
    )
    val applicationContext = context.applicationContext
    val tracker = remember(applicationContext) { WindowInfoTracker.getOrCreate(applicationContext) }
    val defaultLayoutInfoSource = remember(tracker) {
        WindowLayoutInfoSource { owner -> tracker.windowLayoutInfo(owner) }
    }
    val layoutInfoSource = windowLayoutInfoSource ?: defaultLayoutInfoSource
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var layoutInfo by remember(layoutInfoSource, activity) { mutableStateOf<WindowLayoutInfo?>(null) }

    LaunchedEffect(layoutInfoSource, activity, lifecycle) {
        if (activity == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            layoutInfoSource.layoutInfo(activity).collect { info ->
                layoutInfo = info
            }
        }
    }

    val environment = remember(
        windowSize,
        safeInsets,
        imeInsets,
        density.density,
        density.fontScale,
        adaptiveInfo,
        layoutInfo,
        context,
        layoutDirection,
    ) {
        StageWindowEnvironment.create(
            windowSizePx = windowSize,
            safeBoundsInWindowPx = IntRect(
                safeInsets.left,
                safeInsets.top,
                (windowSize.width - safeInsets.right).coerceAtLeast(safeInsets.left),
                (windowSize.height - safeInsets.bottom).coerceAtLeast(safeInsets.top),
            ),
            density = density.density,
            fontScale = density.fontScale,
            canonicalWindowSizeClass = adaptiveInfo.windowSizeClass,
            displayFeatures = layoutInfo?.displayFeatures.orEmpty(),
            inputCapabilities = context.inputCapabilities(),
            layoutDirection = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) {
                StageLayoutDirection.RTL
            } else {
                StageLayoutDirection.LTR
            },
            imeInsetsPx = imeInsets,
        )
    }
    content(environment)
}

private fun StageWindowEnvironment.Companion.create(
    windowSizePx: IntSize,
    safeBoundsInWindowPx: IntRect,
    density: Float,
    fontScale: Float,
    canonicalWindowSizeClass: WindowSizeClass?,
    displayFeatures: List<DisplayFeature>,
    inputCapabilities: StageInputCapabilities,
    layoutDirection: StageLayoutDirection,
    imeInsetsPx: IntInsetsPx,
): StageWindowEnvironment {
    val features = displayFeatures.map(::toStageFeature).sortedWith(FEATURE_ORDER)
    val posture = when {
        features.any { it.orientation == FeatureOrientation.HORIZONTAL && it.halfOpened } ->
            StagePosture.TABLETOP
        features.any { it.orientation == FeatureOrientation.VERTICAL && it.separating } -> StagePosture.BOOK
        else -> StagePosture.FLAT
    }
    return StageWindowEnvironment(
        windowSizePx = windowSizePx,
        safeBoundsInWindowPx = safeBoundsInWindowPx,
        density = density,
        fontScale = fontScale,
        canonicalWindowSizeClass = canonicalWindowSizeClass,
        posture = posture,
        displayFeatures = features,
        inputCapabilities = inputCapabilities,
        layoutDirection = layoutDirection,
        imeInsetsPx = imeInsetsPx,
    )
}

private fun toStageFeature(feature: DisplayFeature): StageWindowFeature {
    val folding = feature as? FoldingFeature
    val bounds = feature.bounds
    return StageWindowFeature(
        bounds = IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
        separating = folding?.isSeparating ?: false,
        occluding = folding?.occlusionType == FoldingFeature.OcclusionType.FULL || folding == null,
        orientation = when (folding?.orientation) {
            FoldingFeature.Orientation.VERTICAL -> FeatureOrientation.VERTICAL
            FoldingFeature.Orientation.HORIZONTAL -> FeatureOrientation.HORIZONTAL
            else -> FeatureOrientation.UNKNOWN
        },
        halfOpened = folding?.state == FoldingFeature.State.HALF_OPENED,
    )
}

private fun Context.inputCapabilities(): StageInputCapabilities {
    val configuration = resources.configuration
    val sources = InputDevice.getDeviceIds().asSequence()
        .mapNotNull(InputDevice::getDevice)
        .map { device -> device.sources }
        .toList()
    return StageInputCapabilities(
        touch = configuration.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH,
        mouse = sources.any { it and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE },
        stylus = sources.any { it and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS },
        hardwareKeyboard = configuration.keyboard != Configuration.KEYBOARD_NOKEYS,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun intersect(first: IntRect, second: IntRect): IntRect? {
    val intersection = IntRect(
        maxOf(first.left, second.left),
        maxOf(first.top, second.top),
        minOf(first.right, second.right),
        minOf(first.bottom, second.bottom),
    )
    return intersection.takeIf { it.width > 0 && it.height > 0 }
}

private val FEATURE_ORDER = compareBy<StageWindowFeature> { it.bounds.top }
    .thenBy { it.bounds.left }
    .thenBy { it.bounds.bottom }
    .thenBy { it.bounds.right }
    .thenBy { !it.separating }
    .thenBy { !it.occluding }
