package com.cattailsw.nanidroid.runtime

/**
 * Temporary pixel compatibility facade retained from the View-era layout manager.
 *
 * The adaptive DP policy lives in [com.cattailsw.nanidroid.runtime.stage]. This
 * facade remains unchanged until the Task 11 host cutover. Dimensions are
 * pixels and all scaling intentionally truncates toward zero, matching the
 * historical View code.
 */
data class GhostStageSize(
    @JvmField val width: Int,
    @JvmField val height: Int,
)

data class GhostStagePlacement(
    @JvmField val size: GhostStageSize,
    @JvmField val horizontal: Horizontal,
    @JvmField val vertical: Vertical,
    @JvmField val bottomMargin: Int = 0,
) {
    enum class Horizontal { START, END }
    enum class Vertical { TOP, BOTTOM }
}

data class GhostStageLayout(
    @JvmField val sakura: GhostStagePlacement,
    @JvmField val kero: GhostStagePlacement,
    @JvmField val sakuraBalloon: GhostStagePlacement,
    @JvmField val keroBalloon: GhostStagePlacement,
)

object GhostStageLayoutPolicy {
    /** Returns null until the containing stage has a usable measured size. */
    @JvmStatic
    fun calculate(
        stage: GhostStageSize,
        sakuraOriginal: GhostStageSize,
        keroOriginal: GhostStageSize,
    ): GhostStageLayout? {
        if (stage.width <= 0 || stage.height <= 0) return null

        val widthScale = if (sakuraOriginal.width + keroOriginal.width > stage.width) {
            stage.width.toFloat() / (sakuraOriginal.width + keroOriginal.width)
        } else {
            1f
        }
        val tallestSurface = maxOf(sakuraOriginal.height, keroOriginal.height)
        val heightScale = if (tallestSurface > stage.height) {
            stage.height.toFloat() / tallestSurface
        } else {
            1f
        }
        val scale = minOf(widthScale, heightScale)
        val sakura = GhostStageSize(
            (sakuraOriginal.width * scale).toInt(),
            (sakuraOriginal.height * scale).toInt(),
        )
        val kero = GhostStageSize(
            (keroOriginal.width * scale).toInt(),
            (keroOriginal.height * scale).toInt(),
        )
        val sakuraPlacement = GhostStagePlacement(
            sakura,
            GhostStagePlacement.Horizontal.END,
            GhostStagePlacement.Vertical.BOTTOM,
        )
        val keroPlacement = GhostStagePlacement(
            kero,
            GhostStagePlacement.Horizontal.START,
            GhostStagePlacement.Vertical.BOTTOM,
        )

        return if (kero.height * 2 < sakura.height) {
            val keroBalloonWidth = if (kero.width < stage.width - sakura.width) {
                stage.width - sakura.width
            } else {
                kero.width
            }
            GhostStageLayout(
                sakuraPlacement,
                keroPlacement,
                GhostStagePlacement(
                    GhostStageSize(stage.width, stage.height - sakura.height),
                    GhostStagePlacement.Horizontal.END,
                    GhostStagePlacement.Vertical.TOP,
                ),
                GhostStagePlacement(
                    GhostStageSize(keroBalloonWidth, maxOf(kero.height, sakura.height - kero.height)),
                    GhostStagePlacement.Horizontal.START,
                    GhostStagePlacement.Vertical.BOTTOM,
                    kero.height,
                ),
            )
        } else {
            val balloon = GhostStageSize(stage.width / 2, stage.height - sakura.height)
            GhostStageLayout(
                sakuraPlacement,
                keroPlacement,
                GhostStagePlacement(balloon, GhostStagePlacement.Horizontal.END, GhostStagePlacement.Vertical.TOP),
                GhostStagePlacement(balloon, GhostStagePlacement.Horizontal.START, GhostStagePlacement.Vertical.TOP),
            )
        }
    }
}
