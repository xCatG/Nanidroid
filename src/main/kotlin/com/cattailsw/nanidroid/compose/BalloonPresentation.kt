package com.cattailsw.nanidroid.compose

/**
 * Pure render input for the eventual Compose balloon.
 *
 * The retained [com.cattailsw.nanidroid.Balloon] is deliberately not used
 * here.  Its observable contract is captured as state and effects so that a
 * Compose implementation can own scrolling and link activation without
 * putting View mutation back into the Sakura Script runtime.
 */
data class BalloonPresentation(
    val text: String,
    val visible: Boolean,
    val linkification: BalloonLinkification,
    val effects: List<BalloonPresentationEffect>,
)

/** Mirrors the legacy `Linkify.ALL` request without parsing Android spans. */
enum class BalloonLinkification { ALL }

/**
 * One update's imperative-looking consequences, represented as data.
 *
 * A renderer applies these in order after it has committed [BalloonPresentation.text].
 * User selection (`\\q`) is intentionally absent: Sakura Script removes those
 * commands and emits its separate dialog callback before a balloon is rendered.
 */
sealed interface BalloonPresentationEffect {
    data object ResetScroll : BalloonPresentationEffect
    data object RefreshLinks : BalloonPresentationEffect
    data object EnableScrolling : BalloonPresentationEffect
    data object DisableScrolling : BalloonPresentationEffect
    data class ScrollBy(val pixels: Int) : BalloonPresentationEffect
    /** Text was updated before its line layout exists; retain the legacy deferred decision. */
    data object AwaitMeasurement : BalloonPresentationEffect
}

/** Measured facts needed to reproduce the legacy bottom-scroll calculation. */
data class BalloonTextLayout(
    val lastLineBottom: Int,
    val height: Int,
    val compoundPaddingTop: Int,
    val compoundPaddingBottom: Int,
) {
    /** Invalid platform measurements are normalized rather than producing a negative viewport. */
    val contentHeight: Int
        get() = (height.coerceAtLeast(0) -
            compoundPaddingTop.coerceAtLeast(0) -
            compoundPaddingBottom.coerceAtLeast(0)).coerceAtLeast(0)
}

/**
 * Translates legacy balloon facts into Compose-ready value data.
 *
 * A null text is safe empty text. A null balloon selection is treated as
 * unselected, avoiding an accidental empty balloon while preserving the
 * historical rule that any non-empty text remains visible.
 */
object BalloonPresentationReducer {
    @JvmStatic
    fun render(
        balloonId: String?,
        text: CharSequence?,
        layout: BalloonTextLayout? = null,
    ): BalloonPresentation {
        val normalizedText = text?.toString().orEmpty()
        val effects = mutableListOf<BalloonPresentationEffect>(
            BalloonPresentationEffect.ResetScroll,
            BalloonPresentationEffect.RefreshLinks,
        )
        when {
            layout == null -> effects += BalloonPresentationEffect.AwaitMeasurement
            layout.lastLineBottom > layout.contentHeight -> {
                effects += BalloonPresentationEffect.EnableScrolling
                effects += BalloonPresentationEffect.ScrollBy(
                    layout.lastLineBottom - layout.contentHeight,
                )
            }
            else -> effects += BalloonPresentationEffect.DisableScrolling
        }
        return BalloonPresentation(
            text = normalizedText,
            visible = normalizedText.isNotEmpty() || balloonId?.equals("-1", ignoreCase = true) == false,
            linkification = BalloonLinkification.ALL,
            effects = effects,
        )
    }
}
