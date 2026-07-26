package com.cattailsw.nanidroid.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.runtime.GhostPresentationReducer
import com.cattailsw.nanidroid.runtime.GhostPresentationState

/**
 * Declarative counterpart to the central legacy stage layout.
 *
 * The stage owns presentation placement only: Kero is bottom-start, Sakura is
 * bottom-end, and their balloons remain above their respective surfaces.
 * Image rendering and hit testing are injected as slots so the legacy surface
 * engine can be adapted incrementally without returning View mutation to the
 * Sakura Script interpreter.
 */
@Composable
fun GhostPresentationStage(
    presentation: GhostPresentationState,
    modifier: Modifier = Modifier,
    sakuraSurface: @Composable BoxScope.() -> Unit = {},
    keroSurface: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (presentation.kero.balloonVisible) {
            GhostBalloon(
                text = presentation.kero.text,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
        if (presentation.sakura.balloonVisible) {
            GhostBalloon(
                text = presentation.sakura.text,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Box(contentAlignment = Alignment.BottomStart) {
            keroSurface()
        }
        Box(contentAlignment = Alignment.BottomEnd) {
            sakuraSurface()
        }
    }
}

@Composable
private fun GhostBalloon(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colorResource(R.color.ghost_list_bg))
            .padding(8.dp),
        style = TextStyle(color = colorResource(R.color.ghost_list_text)),
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun GhostPresentationStagePreview() {
    GhostPresentationStage(
        presentation = GhostPresentationReducer.snapshot(
            sakuraText = "Hello from Sakura",
            sakuraSurfaceId = "0",
            sakuraAnimationId = null,
            sakuraBalloonId = "0",
            keroText = "Hello from Kero",
            keroSurfaceId = "10",
            keroAnimationId = null,
            keroBalloonId = "0",
        ),
    )
}
