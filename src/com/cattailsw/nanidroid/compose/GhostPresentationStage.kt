package com.cattailsw.nanidroid.compose

import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.runtime.GhostStageLayout
import com.cattailsw.nanidroid.runtime.GhostStageLayoutPolicy
import com.cattailsw.nanidroid.runtime.GhostStagePlacement
import com.cattailsw.nanidroid.runtime.GhostStageSize
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
    sakuraSurfaceSize: IntSize,
    keroSurfaceSize: IntSize,
    showSakuraBalloon: Boolean = true,
    showKeroBalloon: Boolean = true,
    modifier: Modifier = Modifier,
    sakuraSurface: @Composable BoxScope.() -> Unit = {},
    keroSurface: @Composable BoxScope.() -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val stage = IntSize(constraints.maxWidth, constraints.maxHeight)
        val layout = GhostStageLayoutPolicy.calculate(
            stage.toStageSize(),
            sakuraSurfaceSize.toStageSize(),
            keroSurfaceSize.toStageSize(),
        ) ?: return@BoxWithConstraints

        StageNode(layout.kero) { keroSurface() }
        StageNode(layout.sakura) { sakuraSurface() }
        if (showKeroBalloon && presentation.kero.balloonVisible) {
            StageNode(layout.keroBalloon) { GhostBalloon(text = presentation.kero.text) }
        }
        if (showSakuraBalloon && presentation.sakura.balloonVisible) {
            StageNode(layout.sakuraBalloon) { GhostBalloon(text = presentation.sakura.text) }
        }
    }
}

@Composable
private fun BoxScope.StageNode(
    placement: GhostStagePlacement,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .size(
                with(density) { placement.size.width.toDp() },
                with(density) { placement.size.height.toDp() },
            )
            .align(
                when (placement.vertical) {
                    GhostStagePlacement.Vertical.TOP -> when (placement.horizontal) {
                        GhostStagePlacement.Horizontal.START -> Alignment.TopStart
                        GhostStagePlacement.Horizontal.END -> Alignment.TopEnd
                    }
                    GhostStagePlacement.Vertical.BOTTOM -> when (placement.horizontal) {
                        GhostStagePlacement.Horizontal.START -> Alignment.BottomStart
                        GhostStagePlacement.Horizontal.END -> Alignment.BottomEnd
                    }
                }
            )
            .offset { IntOffset(0, -placement.bottomMargin) },
        content = content,
    )
}

private fun IntSize.toStageSize(): GhostStageSize = GhostStageSize(width, height)

@Composable
private fun GhostBalloon(text: String, modifier: Modifier = Modifier) {
    val annotatedText = remember(text) { linkifyForCompose(text) }
    val scrollState = rememberScrollState()
    val uriHandler = LocalUriHandler.current
    // Balloon.setText reset its scroll and then revealed the last line when
    // content overflowed. Compose owns that behavior without returning to a
    // TextView/AndroidView bridge.
    LaunchedEffect(text, scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    ClickableText(
        text = annotatedText,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colorResource(R.color.ghost_list_bg))
            .padding(8.dp)
            .verticalScroll(scrollState),
        style = TextStyle(color = colorResource(R.color.ghost_list_text)),
        onClick = { offset ->
            annotatedText.getStringAnnotations(URL_ANNOTATION, offset, offset)
                .firstOrNull()
                ?.item
                ?.let { url -> runCatching { uriHandler.openUri(url) } }
        },
    )
}

private fun linkifyForCompose(text: String) = buildAnnotatedString {
    val spanned = SpannableString(text).also { Linkify.addLinks(it, Linkify.ALL) }
    val links = spanned.getSpans(0, spanned.length, URLSpan::class.java)
        .sortedBy { spanned.getSpanStart(it) }
    var cursor = 0
    links.forEach { link ->
        val start = spanned.getSpanStart(link).coerceAtLeast(cursor)
        val end = spanned.getSpanEnd(link).coerceAtMost(spanned.length)
        if (start >= end) return@forEach
        append(spanned.subSequence(cursor, start).toString())
        pushStringAnnotation(URL_ANNOTATION, link.url)
        append(spanned.subSequence(start, end).toString())
        pop()
        cursor = end
    }
    append(spanned.subSequence(cursor, spanned.length).toString())
}

private const val URL_ANNOTATION = "nanidroid-balloon-url"

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
        sakuraSurfaceSize = IntSize(180, 360),
        keroSurfaceSize = IntSize(120, 180),
    )
}
