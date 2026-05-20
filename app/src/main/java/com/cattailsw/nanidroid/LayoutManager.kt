package com.cattailsw.nanidroid

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout

class LayoutManager private constructor(context: Context) {
    private val mCtx: Context = context.applicationContext
    private var sv: SakuraView? = null
    private var kv: KeroView? = null
    private var bSakura: Balloon? = null
    private var bKero: Balloon? = null
    private var fl: FrameLayout? = null

    companion object {
        private var _self: LayoutManager? = null

        @JvmStatic
        fun getInstance(ctx: Context): LayoutManager {
            if (_self == null) {
                _self = LayoutManager(ctx)
            }
            return _self!!
        }
    }

    fun setViews(f: FrameLayout?, s: SakuraView?, k: KeroView?, bS: Balloon?, bK: Balloon?) {
        fl = f
        sv = s
        kv = k
        bSakura = bS
        bKero = bK
    }

    fun checkAndUpdateLayoutParam() {
        val layout = fl ?: return
        val sakuraView = sv ?: return
        val keroView = kv ?: return
        val sakuraBalloon = bSakura ?: return
        val keroBalloon = bKero ?: return

        val layoutWidth = layout.width
        val layoutHeight = layout.height
        if (layoutHeight <= 0 || layoutWidth <= 0) return

        val sH = sakuraView.currentSurface?.origH ?: 300
        val sW = sakuraView.currentSurface?.origW ?: 200
        val kH = keroView.currentSurface?.origH ?: 300
        val kW = keroView.currentSurface?.origW ?: 200

        var wScale = 1.0f
        if (sW + kW > layoutWidth) {
            wScale = layoutWidth.toFloat() / (sW + kW).toFloat()
        }

        var hScale = 1.0f
        val vH = Math.max(sH, kH)
        if (vH > layoutHeight) {
            hScale = layoutHeight.toFloat() / vH.toFloat()
        }

        val scale = Math.min(wScale, hScale)

        val scaledSakuraHeight = (sH * scale).toInt()
        val scaledSakuraWidth = (sW * scale).toInt()
        val lpS = FrameLayout.LayoutParams(
            scaledSakuraWidth,
            scaledSakuraHeight,
            Gravity.BOTTOM or Gravity.RIGHT
        )
        sakuraView.layoutParams = lpS

        val scaledKeroHeight = (kH * scale).toInt()
        val scaledKeroWidth = (kW * scale).toInt()
        val lpK = FrameLayout.LayoutParams(
            scaledKeroWidth,
            scaledKeroHeight,
            Gravity.BOTTOM or Gravity.LEFT
        )
        keroView.layoutParams = lpK

        if (scaledKeroHeight * 2 < scaledSakuraHeight) {
            val kbH = Math.max(scaledKeroHeight, scaledSakuraHeight - scaledKeroHeight)
            var kbW = scaledKeroWidth
            if (scaledKeroWidth < layoutWidth - scaledSakuraWidth) {
                kbW = layoutWidth - scaledSakuraWidth
            }
            val lpBK = FrameLayout.LayoutParams(kbW, kbH, Gravity.BOTTOM or Gravity.LEFT).apply {
                bottomMargin = scaledKeroHeight
            }
            keroBalloon.layoutParams = lpBK

            val bH = layoutHeight - scaledSakuraHeight
            val lpBS = FrameLayout.LayoutParams(layoutWidth, bH, Gravity.TOP or Gravity.RIGHT)
            sakuraBalloon.layoutParams = lpBS
        } else {
            val bH = layoutHeight - scaledSakuraHeight
            val bW = layoutWidth / 2
            val lpBS = FrameLayout.LayoutParams(bW, bH, Gravity.TOP or Gravity.RIGHT)
            sakuraBalloon.layoutParams = lpBS
            val lpBK = FrameLayout.LayoutParams(bW, bH, Gravity.TOP or Gravity.LEFT)
            keroBalloon.layoutParams = lpBK
        }

        layout.invalidate()
    }
}
