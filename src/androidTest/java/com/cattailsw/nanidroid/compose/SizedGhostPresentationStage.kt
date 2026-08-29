package com.cattailsw.nanidroid.compose

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.RuntimePresentation
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey
import com.cattailsw.nanidroid.compose.stage.GhostStageMeasureState

@Composable
internal fun SizedGhostPresentationStage(
    presentation: RuntimePresentation,
    sakuraSurfaceSize: IntSize,
    keroSurfaceSize: IntSize,
    showSakuraBalloon: Boolean = true,
    showKeroBalloon: Boolean = true,
    modifier: Modifier = Modifier,
    sakuraSurface: @Composable BoxScope.() -> Unit = {},
    keroSurface: @Composable BoxScope.() -> Unit = {},
) {
    val measureState = remember { GhostStageMeasureState().also { it.resetFor("sized-test-stage") } }
    val sakura = remember(sakuraSurfaceSize) { testSurface(0, sakuraSurfaceSize) }
    val kero = remember(keroSurfaceSize) { testSurface(10, keroSurfaceSize) }
    GhostPresentationStage(
        presentation = presentation,
        sakuraComposedSurface = sakura,
        keroComposedSurface = kero,
        measureState = measureState,
        ghostKey = "sized-test-stage",
        sakuraDialogue = presentation.sakura.dialogue(GhostSpeaker.SAKURA),
        keroDialogue = presentation.kero.dialogue(GhostSpeaker.KERO),
        showSakuraBalloon = showSakuraBalloon,
        showKeroBalloon = showKeroBalloon,
        modifier = modifier,
        sakuraSurface = { sakuraSurface() },
        keroSurface = { keroSurface() },
    )
}

private fun com.cattailsw.nanidroid.runtime.RuntimeSpeakerPresentation.dialogue(
    speaker: GhostSpeaker,
) = DialogueContent(
    speaker,
    if (text.isEmpty()) emptyList() else listOf(DialogueSegment.Text(text)),
)

private fun testSurface(id: Int, size: IntSize): ComposedSurface? {
    if (size.width <= 0 || size.height <= 0) return null
    return ComposedSurface(
        image = SurfacePixelImage.of(size.width, size.height, IntArray(size.width * size.height)),
        canvasSize = size,
        visiblePixelBounds = IntRect(0, 0, size.width, size.height),
        effectiveCollisions = emptyList(),
        surfaceKey = SurfaceKey(id, size),
        revision = 0L,
        explicitlyHidden = false,
    )
}
