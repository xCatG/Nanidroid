package com.cattailsw.nanidroid

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import com.cattailsw.nanidroid.runtime.GhostStageLayoutPolicy
import com.cattailsw.nanidroid.runtime.GhostStagePlacement
import com.cattailsw.nanidroid.runtime.GhostStageSize

/**
 * View-era adapter for the shared ghost-stage geometry policy.
 *
 * Rendering still belongs to the retained surface Views, while the placement
 * calculation has one source of truth shared with the Compose stage.
 */
class LayoutManager private constructor(context: Context) {
    @Suppress("unused")
    private val applicationContext = context.applicationContext
    private lateinit var frameLayout: FrameLayout
    private lateinit var sakuraView: SakuraView
    private lateinit var keroView: KeroView
    private lateinit var sakuraBalloon: Balloon
    private lateinit var keroBalloon: Balloon

    fun setViews(
        frameLayout: FrameLayout,
        sakuraView: SakuraView,
        keroView: KeroView,
        sakuraBalloon: Balloon,
        keroBalloon: Balloon,
    ) {
        this.frameLayout = frameLayout
        this.sakuraView = sakuraView
        this.keroView = keroView
        this.sakuraBalloon = sakuraBalloon
        this.keroBalloon = keroBalloon
    }

    fun checkAndUpdateLayoutParam() {
        val layout = GhostStageLayoutPolicy.calculate(
            GhostStageSize(frameLayout.width, frameLayout.height),
            GhostStageSize(sakuraView.currentSurface!!.origW, sakuraView.currentSurface!!.origH),
            GhostStageSize(keroView.currentSurface!!.origW, keroView.currentSurface!!.origH),
        ) ?: return

        sakuraView.layoutParams = layout.sakura.toLayoutParams()
        keroView.layoutParams = layout.kero.toLayoutParams()
        sakuraBalloon.layoutParams = layout.sakuraBalloon.toLayoutParams()
        keroBalloon.layoutParams = layout.keroBalloon.toLayoutParams()
        frameLayout.invalidate()
    }

    private fun GhostStagePlacement.toLayoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            size.width,
            size.height,
            when (vertical) {
                GhostStagePlacement.Vertical.TOP -> when (horizontal) {
                    GhostStagePlacement.Horizontal.START -> Gravity.TOP or Gravity.LEFT
                    GhostStagePlacement.Horizontal.END -> Gravity.TOP or Gravity.RIGHT
                }
                GhostStagePlacement.Vertical.BOTTOM -> when (horizontal) {
                    GhostStagePlacement.Horizontal.START -> Gravity.BOTTOM or Gravity.LEFT
                    GhostStagePlacement.Horizontal.END -> Gravity.BOTTOM or Gravity.RIGHT
                }
            },
        ).also { params ->
            params.bottomMargin = bottomMargin
        }

    companion object {
        private var instance: LayoutManager? = null

        @JvmStatic
        fun getInstance(context: Context): LayoutManager {
            if (instance == null) instance = LayoutManager(context)
            return instance!!
        }
    }
}
