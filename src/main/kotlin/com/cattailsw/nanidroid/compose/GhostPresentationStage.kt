package com.cattailsw.nanidroid.compose

import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.compose.stage.GhostStageMeasureState
import com.cattailsw.nanidroid.compose.stage.MeasuredGhostStageLayout
import com.cattailsw.nanidroid.compose.stage.StageEnvironmentProvider
import com.cattailsw.nanidroid.compose.stage.StageSurfaceSnapshot
import com.cattailsw.nanidroid.compose.stage.StageMeasuredSnapshot
import com.cattailsw.nanidroid.compose.stage.StagePointerInput
import com.cattailsw.nanidroid.runtime.GhostPresentationReducer
import com.cattailsw.nanidroid.runtime.GhostPresentationState
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey
import com.cattailsw.nanidroid.runtime.stage.BubbleHitRegionRegistry
import com.cattailsw.nanidroid.runtime.stage.StageInputRouter
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import kotlin.math.roundToInt

/** Production adaptive stage consuming atomic composed surfaces. */
@Composable
fun GhostPresentationStage(
    presentation: GhostPresentationState,
    sakuraComposedSurface: ComposedSurface?,
    keroComposedSurface: ComposedSurface?,
    measureState: GhostStageMeasureState,
    ghostKey: String,
    ghostIdentity: Any = ghostKey,
    blockingInput: Boolean = false,
    ghostIdentityProvider: () -> Any = { ghostIdentity },
    blockingInputProvider: () -> Boolean = { blockingInput },
    routingEpochProvider: () -> Any = { Unit },
    onSurfaceEffect: (SurfaceInteractionEffect) -> Unit = {},
    onToggleChrome: () -> Unit = {},
    modifier: Modifier = Modifier,
    showSakuraBalloon: Boolean = true,
    showKeroBalloon: Boolean = true,
    sakuraSurface: @Composable BoxScope.(StageSurfaceSnapshot) -> Unit = {},
    keroSurface: @Composable BoxScope.(StageSurfaceSnapshot) -> Unit = {},
) {
    StageEnvironmentProvider { windowEnvironment ->
        var placement by remember { mutableStateOf<StagePlacement?>(null) }
        // StagePointerInput evaluates this provider during composition for
        // eager cancellation and again at event time for authoritative state.
        StagePointerInput(
            snapshotProvider = {
                currentStageInputSnapshot(
                    measured = measureState.latest,
                    blocking = blockingInputProvider(),
                    ghostKey = ghostKey,
                    ghostIdentity = ghostIdentityProvider(),
                    routingEpoch = StagePresentationRoutingEpoch(
                        external = routingEpochProvider(),
                        measured = measureState.inputEpoch,
                    ),
                )
            },
            onSurfaceEffect = onSurfaceEffect,
            onToggleChrome = onToggleChrome,
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val next = StagePlacement(
                        window = coordinates.positionInWindow().roundedOffset(),
                        root = coordinates.positionInRoot().roundedOffset(),
                    )
                    if (placement != next) placement = next
                },
        ) { _ ->
            placement?.let { measuredPlacement ->
                MeasuredGhostStageLayout(
                    presentation = presentation,
                    environmentForSize = { size ->
                        val stageBounds = IntRect(
                            measuredPlacement.window.x,
                            measuredPlacement.window.y,
                            saturatingAdd(measuredPlacement.window.x, size.width),
                            saturatingAdd(measuredPlacement.window.y, size.height),
                        )
                        windowEnvironment.toStageEnvironment(
                            stageBoundsInWindowPx = stageBounds,
                            canonicalAppBarHeight = CANONICAL_APP_BAR_HEIGHT,
                            ghostKey = ghostKey,
                        )
                    },
                    measureState = measureState,
                    kero = keroComposedSurface,
                    sakura = sakuraComposedSurface,
                    modifier = Modifier.fillMaxSize(),
                    stageToRoot = measuredPlacement.root,
                    showKeroBalloon = showKeroBalloon,
                    showSakuraBalloon = showSakuraBalloon,
                    keroBalloon = { GhostBalloon(text = presentation.kero.text) },
                    sakuraBalloon = { GhostBalloon(text = presentation.sakura.text) },
                    surfaceContent = { snapshot ->
                        when (snapshot.speaker) {
                            SurfaceSpeaker.KERO -> keroSurface(snapshot)
                            SurfaceSpeaker.SAKURA -> sakuraSurface(snapshot)
                        }
                    },
                )
            }
        }
    }
}

internal fun currentStageInputSnapshot(
    measured: StageMeasuredSnapshot?,
    blocking: Boolean,
    ghostKey: String,
    ghostIdentity: Any,
    routingEpoch: Any = Unit,
) = StageInputRouter.snapshot(
    blocking = blocking,
    bubbleRegistry = BubbleHitRegionRegistry.from(measured?.bubbleRegions.orEmpty()),
    bubbleGeneration = measured?.bubbleGeneration ?: 0,
    ghostKey = ghostKey,
    surfaces = listOfNotNull(measured?.kero, measured?.sakura),
    ghostIdentity = ghostIdentity,
    routingEpoch = routingEpoch,
)

/**
 * Compatibility facade for characterization tests that inject their own
 * surface content. Production uses the atomic [ComposedSurface] overload.
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
    val measureState = remember { GhostStageMeasureState().also { it.resetFor(LegacyPreviewOwner) } }
    val sakura = remember(sakuraSurfaceSize) { layoutOnlySurface(0, sakuraSurfaceSize) }
    val kero = remember(keroSurfaceSize) { layoutOnlySurface(10, keroSurfaceSize) }
    GhostPresentationStage(
        presentation = presentation,
        sakuraComposedSurface = sakura,
        keroComposedSurface = kero,
        measureState = measureState,
        ghostKey = "legacy-preview",
        showSakuraBalloon = showSakuraBalloon,
        showKeroBalloon = showKeroBalloon,
        modifier = modifier,
        sakuraSurface = { sakuraSurface() },
        keroSurface = { keroSurface() },
    )
}

@Composable
internal fun GhostBalloon(text: String, modifier: Modifier = Modifier) {
    val annotatedText = remember(text) { linkifyForCompose(text) }
    val scrollState = rememberScrollState()
    val uriHandler = LocalUriHandler.current
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

private fun layoutOnlySurface(id: Int, size: IntSize): ComposedSurface? {
    if (size.width <= 0 || size.height <= 0) return null
    val pixels = IntArray(size.width * size.height) { 0xff404040.toInt() }
    return ComposedSurface(
        image = SurfacePixelImage.of(size.width, size.height, pixels),
        canvasSize = size,
        visiblePixelBounds = IntRect(0, 0, size.width, size.height),
        effectiveCollisions = emptyList(),
        surfaceKey = SurfaceKey(id, size),
        revision = 0,
        explicitlyHidden = false,
    )
}

private data class StagePlacement(val window: IntOffset, val root: IntOffset)
private data class StagePresentationRoutingEpoch(val external: Any, val measured: Long)

private fun Offset.roundedOffset() = IntOffset(x.roundToInt(), y.roundToInt())

private fun saturatingAdd(first: Int, second: Int): Int =
    (first.toLong() + second.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

private data object LegacyPreviewOwner

private val CANONICAL_APP_BAR_HEIGHT = 64.dp
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
