package com.cattailsw.nanidroid.compose

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.cattailsw.nanidroid.GhostPresentationFrame
import com.cattailsw.nanidroid.KeroView
import com.cattailsw.nanidroid.SakuraView
import com.cattailsw.nanidroid.runtime.GhostPresentationReducer
import com.cattailsw.nanidroid.runtime.GhostPresentationState

/**
 * Compose balloon host layered over the retained surface Views.
 *
 * The legacy views continue to own bitmap rendering, scaling, and hit testing.
 * Their measured dimensions are reflected into Compose so each balloon is laid
 * out directly above the surface it describes.
 */
class GhostPresentationComposeHost(
    context: Context,
    sakuraView: SakuraView,
    keroView: KeroView,
) {
    private var presentation by mutableStateOf(emptyPresentation())
    private var showSakuraBalloon by mutableStateOf(false)
    private var showKeroBalloon by mutableStateOf(false)
    private var sakuraSize by mutableStateOf(IntSize.Zero)
    private var keroSize by mutableStateOf(IntSize.Zero)
    private val lifecycleOwner = StaticLifecycleOwner()

    private val composeView = ComposeView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setContent {
            GhostPresentationStage(
                presentation = presentation,
                showSakuraBalloon = showSakuraBalloon,
                showKeroBalloon = showKeroBalloon,
                sakuraSurface = { SurfaceSpace(sakuraSize) },
                keroSurface = { SurfaceSpace(keroSize) },
            )
        }
    }

    val view: View = PassthroughComposeContainer(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setViewTreeLifecycleOwner(lifecycleOwner)
        addView(
            composeView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    init {
        observeSize(sakuraView) { sakuraSize = it }
        observeSize(keroView) { keroSize = it }
    }

    fun render(frame: GhostPresentationFrame, showSakura: Boolean, showKero: Boolean) {
        composeView.post {
            showSakuraBalloon = showSakura
            showKeroBalloon = showKero
            presentation = GhostPresentationReducer.snapshot(
                sakuraText = frame.sakura.text,
                sakuraSurfaceId = frame.sakura.surfaceId,
                sakuraAnimationId = frame.sakura.animationId,
                sakuraBalloonId = if (frame.sakura.balloonVisible) "0" else "-1",
                keroText = frame.kero.text,
                keroSurfaceId = frame.kero.surfaceId,
                keroAnimationId = frame.kero.animationId,
                keroBalloonId = if (frame.kero.balloonVisible) "0" else "-1",
            )
        }
    }

    /** Installs the owner at the window root used by Compose's recomposer lookup. */
    fun installLifecycleOwner(root: View) {
        root.setViewTreeLifecycleOwner(lifecycleOwner)
        root.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    fun onHostResumed() = lifecycleOwner.resume()

    fun onHostPaused() = lifecycleOwner.pause()

    fun onHostDestroyed() = lifecycleOwner.destroy()

    private fun observeSize(view: View, update: (IntSize) -> Unit) {
        fun report() = update(IntSize(view.width, view.height))
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> report() }
        report()
    }

    private fun emptyPresentation(): GhostPresentationState =
        GhostPresentationReducer.snapshot("", "0", null, "-1", "", "10", null, "-1")
}

@Composable
private fun SurfaceSpace(size: IntSize) {
    val density = LocalDensity.current
    Spacer(
        modifier = Modifier.size(
            with(density) { size.width.toDp() },
            with(density) { size.height.toDp() },
        ),
    )
}

/** The overlay is display-only until Compose owns hit testing in a later migration. */
private class PassthroughComposeContainer(context: Context) : FrameLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
}

private class StaticLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this).apply {
        performAttach()
        performRestore(null)
    }

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun pause() {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }

    override val lifecycle: Lifecycle
        get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
}
