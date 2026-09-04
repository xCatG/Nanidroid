package com.cattailsw.nanidroid.compose.stage

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.adaptive.currentWindowSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.cattailsw.nanidroid.runtime.stage.StageDisplayFeature
import com.cattailsw.nanidroid.runtime.stage.StageDpRect
import com.cattailsw.nanidroid.runtime.stage.StageEnvironment
import com.cattailsw.nanidroid.runtime.stage.StageInputCapabilities
import com.cattailsw.nanidroid.runtime.stage.StagePosture
import com.cattailsw.nanidroid.runtime.stage.StagePointingDeviceCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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

/** Application-context device capability stream; each subscription starts with a full enumeration. */
internal fun interface InputCapabilitySource {
    fun capabilities(): Flow<StagePointingDeviceCapabilities>
}

internal interface InputDeviceRegistry {
    interface Listener {
        fun onAdded()
        fun onChanged()
        fun onRemoved()
    }

    fun currentSources(): List<Int>
    fun register(listener: Listener)
    fun unregister(listener: Listener)
}

internal class RegisteredInputCapabilitySource(
    private val registry: InputDeviceRegistry,
) : InputCapabilitySource {
    override fun capabilities(): Flow<StagePointingDeviceCapabilities> = callbackFlow {
        fun publishCurrent() {
            val sources = registry.currentSources()
            trySend(
                StagePointingDeviceCapabilities(
                    mouse = sources.any { it and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE },
                    stylus = sources.any { it and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS },
                ),
            )
        }
        val listener = object : InputDeviceRegistry.Listener {
            override fun onAdded() = publishCurrent()
            override fun onChanged() = publishCurrent()
            override fun onRemoved() = publishCurrent()
        }
        registry.register(listener)
        publishCurrent()
        awaitClose { registry.unregister(listener) }
    }
}

/** Window-local adaptive facts. */
data class StageWindowEnvironment(
    val windowSizePx: IntSize,
    val safeBoundsInWindowPx: IntRect,
    val density: Float,
    val posture: StagePosture,
    val displayFeatures: List<StageWindowFeature>,
    val inputCapabilities: StageInputCapabilities,
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
            canonicalAppBarHeight = canonicalAppBarHeight,
            posture = posture,
            displayFeatures = features,
            ghostKey = ghostKey,
        )
    }
}

@Composable
fun StageEnvironmentProvider(
    content: @Composable (StageWindowEnvironment) -> Unit,
) = StageEnvironmentProviderImpl(
    windowLayoutInfoSource = null,
    inputCapabilitySource = null,
    content = content,
)

@Composable
internal fun StageEnvironmentProvider(
    windowLayoutInfoSource: WindowLayoutInfoSource,
    inputCapabilitySource: InputCapabilitySource? = null,
    content: @Composable (StageWindowEnvironment) -> Unit,
) = StageEnvironmentProviderImpl(
    windowLayoutInfoSource = windowLayoutInfoSource,
    inputCapabilitySource = inputCapabilitySource,
    content = content,
)

@Composable
private fun StageEnvironmentProviderImpl(
    windowLayoutInfoSource: WindowLayoutInfoSource?,
    inputCapabilitySource: InputCapabilitySource?,
    content: @Composable (StageWindowEnvironment) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findActivity() }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val windowSize = currentWindowSize()
    val safeDrawing = WindowInsets.safeDrawing
    val safeLeft = safeDrawing.getLeft(density, layoutDirection)
    val safeTop = safeDrawing.getTop(density)
    val safeRight = safeDrawing.getRight(density, layoutDirection)
    val safeBottom = safeDrawing.getBottom(density)
    val safeBoundsInWindowPx = IntRect(
        safeLeft,
        safeTop,
        (windowSize.width - safeRight).coerceAtLeast(safeLeft),
        (windowSize.height - safeBottom).coerceAtLeast(safeTop),
    )
    val applicationContext = context.applicationContext
    val tracker = remember(applicationContext) { WindowInfoTracker.getOrCreate(applicationContext) }
    val defaultLayoutInfoSource = remember(tracker) {
        WindowLayoutInfoSource { owner -> tracker.windowLayoutInfo(owner) }
    }
    val layoutInfoSource = windowLayoutInfoSource ?: defaultLayoutInfoSource
    val defaultInputCapabilitySource = remember(applicationContext) {
        RegisteredInputCapabilitySource(AndroidInputDeviceRegistry(applicationContext))
    }
    val activeInputCapabilitySource = inputCapabilitySource ?: defaultInputCapabilitySource
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var layoutInfo by remember(layoutInfoSource, activity) { mutableStateOf<WindowLayoutInfo?>(null) }
    var pointingCapabilities by remember(activeInputCapabilitySource) {
        mutableStateOf(StagePointingDeviceCapabilities(mouse = false, stylus = false))
    }

    LaunchedEffect(layoutInfoSource, activity, lifecycle) {
        if (activity == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            layoutInfoSource.layoutInfo(activity).collect { info ->
                layoutInfo = info
            }
        }
    }

    LaunchedEffect(activeInputCapabilitySource, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            activeInputCapabilitySource.capabilities().collect { capabilities ->
                pointingCapabilities = capabilities
            }
        }
    }

    val environment = remember(
        windowSize,
        safeBoundsInWindowPx,
        density.density,
        density.fontScale,
        layoutInfo,
        configuration.touchscreen,
        configuration.keyboard,
        pointingCapabilities,
        layoutDirection,
    ) {
        createStageWindowEnvironment(
            windowSizePx = windowSize,
            safeBoundsInWindowPx = safeBoundsInWindowPx,
            density = density.density,
            displayFeatures = layoutInfo?.displayFeatures.orEmpty(),
            inputCapabilities = StageInputCapabilities(
                touch = configuration.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH,
                mouse = pointingCapabilities.mouse,
                stylus = pointingCapabilities.stylus,
                hardwareKeyboard = configuration.keyboard != Configuration.KEYBOARD_NOKEYS,
            ),
        )
    }
    content(environment)
}

private fun createStageWindowEnvironment(
    windowSizePx: IntSize,
    safeBoundsInWindowPx: IntRect,
    density: Float,
    displayFeatures: List<DisplayFeature>,
    inputCapabilities: StageInputCapabilities,
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
        posture = posture,
        displayFeatures = features,
        inputCapabilities = inputCapabilities,
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

private class AndroidInputDeviceRegistry(context: Context) : InputDeviceRegistry {
    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(InputManager::class.java)
    private val listeners = java.util.IdentityHashMap<InputDeviceRegistry.Listener, InputManager.InputDeviceListener>()

    override fun currentSources(): List<Int> = InputDevice.getDeviceIds().asSequence()
        .mapNotNull(InputDevice::getDevice)
        .map { it.sources }
        .toList()

    override fun register(listener: InputDeviceRegistry.Listener) {
        check(!listeners.containsKey(listener))
        val androidListener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = listener.onAdded()
            override fun onInputDeviceChanged(deviceId: Int) = listener.onChanged()
            override fun onInputDeviceRemoved(deviceId: Int) = listener.onRemoved()
        }
        listeners[listener] = androidListener
        manager.registerInputDeviceListener(androidListener, null)
    }

    override fun unregister(listener: InputDeviceRegistry.Listener) {
        listeners.remove(listener)?.let(manager::unregisterInputDeviceListener)
    }
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
