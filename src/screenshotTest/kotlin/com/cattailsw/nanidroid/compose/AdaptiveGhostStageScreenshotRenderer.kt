package com.cattailsw.nanidroid.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.SurfaceTransparencyPolicy
import com.cattailsw.nanidroid.compose.debug.DebugPresentation
import com.cattailsw.nanidroid.compose.debug.GhostDebugSurface
import com.cattailsw.nanidroid.compose.debug.SurfaceDebugSelection
import com.cattailsw.nanidroid.compose.debug.SurfacePointerDebugEvent
import com.cattailsw.nanidroid.compose.debug.collisionOverlaySpeaker
import com.cattailsw.nanidroid.compose.debug.resolveDebugPresentation
import com.cattailsw.nanidroid.compose.stage.BubbleUiState
import com.cattailsw.nanidroid.compose.stage.GhostBubble
import com.cattailsw.nanidroid.compose.stage.GhostStageMeasureState
import com.cattailsw.nanidroid.compose.stage.MeasuredGhostStageLayout
import com.cattailsw.nanidroid.compose.stage.RenderedSurfaceLayer
import com.cattailsw.nanidroid.runtime.BoundedShioriLog
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.stage.GhostStageLayoutPolicy
import com.cattailsw.nanidroid.runtime.stage.StageDpRect
import com.cattailsw.nanidroid.runtime.stage.StageEnvironment
import com.cattailsw.nanidroid.runtime.stage.StageInputCapabilities
import com.cattailsw.nanidroid.runtime.stage.StageLayoutDirection
import com.cattailsw.nanidroid.runtime.stage.StageMode
import kotlin.math.roundToInt

@Composable
internal fun AdaptiveGhostStageScreenshot(caseName: String) {
    val fixture = ADAPTIVE_GHOST_STAGE_SCREENSHOT_CASES.single { it.name == caseName }
    val fixtureDensity = Density(
        density = fixture.densityDpi / 160f,
        fontScale = fixture.fontScale,
    )
    CompositionLocalProvider(
        LocalDensity provides fixtureDensity,
    ) {
        ScreenshotHarness(theme = fixture.theme) {
            CompositionLocalProvider(LocalLayoutDirection provides fixture.layoutDirection) {
                AdaptiveGhostStageFixture(fixture)
            }
        }
    }
}

@Composable
private fun AdaptiveGhostStageFixture(fixture: StageScreenshotCase) {
    val density = LocalDensity.current
    val measureState = remember(fixture.name) { GhostStageMeasureState() }
    val sakura = remember(fixture.state.sakura) { fixture.state.sakura.composedSurface() }
    val kero = remember(fixture.state.kero) { fixture.state.kero.composedSurface() }
    val environment = remember(fixture, density.density, density.fontScale) {
        screenshotEnvironment(
            fixture = fixture,
            sizePx = fixture.windowSizeDp.toPixels(density),
            density = density.density,
            fontScale = density.fontScale,
        )
    }
    val mode = remember(environment, sakura, kero) {
        GhostStageLayoutPolicy.calculate(
            environment = environment,
            kero = kero.metrics(),
            sakura = sakura.metrics(),
        ).mode
    }
    val overlaySpeaker = fixture.state.debug.collisionOverlaySpeaker(
        loading = false,
        debugBuild = true,
    )

    NanidroidComposeShell(
        ghostStage = {
            Box(modifier = Modifier.fillMaxSize()) {
                MeasuredGhostStageLayout(
                    presentation = fixture.state.presentation,
                    environmentForSize = { size ->
                        screenshotEnvironment(
                            fixture = fixture,
                            sizePx = size,
                            density = density.density,
                            fontScale = density.fontScale,
                        )
                    },
                    measureState = measureState,
                    kero = kero,
                    sakura = sakura,
                    modifier = Modifier.fillMaxSize(),
                    showKeroBalloon = true,
                    showSakuraBalloon = true,
                    dialogueTalkId = 16L,
                    dialogueRevision = 1L,
                    keroBalloon = {
                        FixtureBubble(
                            fixture = fixture,
                            speaker = SurfaceSpeaker.KERO,
                        )
                    },
                    sakuraBalloon = {
                        FixtureBubble(
                            fixture = fixture,
                            speaker = SurfaceSpeaker.SAKURA,
                        )
                    },
                    surfaceContent = { snapshot ->
                        RenderedSurfaceLayer(
                            snapshot = snapshot,
                            showCollisionOverlay = overlaySpeaker == snapshot.speaker,
                        )
                    },
                )
                if (mode == StageMode.TINY) {
                    Text(
                        text = stringResource(R.string.stage_tiny_window_message),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    )
                }
            }
        },
        loading = false,
        progressMessage = "",
        toolbarVisible = true,
        onListGhost = {},
        onUpdate = {},
        onReadme = {},
        onPreferences = {},
        onHelp = {},
        onArchiveQueue = {},
        showDebugControls = true,
        onDebug = {},
        simpleDialog = null,
        onDismissSimpleDialog = {},
        stalledOperations = listOfNotNull(fixture.state.stalledOperation),
        staticDurablePromptPreview = true,
        transientOverlay = {
            FixtureDebugSurface(
                fixture = fixture,
                presentation = resolveDebugPresentation(fixture.windowSizeDp.width, mode),
            )
        },
    )
}

@Composable
private fun FixtureBubble(
    fixture: StageScreenshotCase,
    speaker: SurfaceSpeaker,
) {
    val presentation = when (speaker) {
        SurfaceSpeaker.SAKURA -> fixture.state.presentation.sakura
        SurfaceSpeaker.KERO -> fixture.state.presentation.kero
    }
    val ghostSpeaker = when (speaker) {
        SurfaceSpeaker.SAKURA -> GhostSpeaker.SAKURA
        SurfaceSpeaker.KERO -> GhostSpeaker.KERO
    }
    val longBubble = fixture.name.contains("long")
    GhostBubble(
        state = BubbleUiState(
            speaker = speaker,
            content = DialogueContent(
                speaker = ghostSpeaker,
                segments = presentation.text
                    .takeIf(String::isNotEmpty)
                    ?.let { listOf(DialogueSegment.Text(it)) }
                    .orEmpty(),
            ),
            pendingChoices = emptyList(),
            scrollPosition = if (longBubble) 18 else 0,
            userScrolledThisTalk = longBubble,
            scrollOwnerKey = fixture.name,
            talkId = 16L,
            contentRevision = 1L,
        ),
    )
}

@Composable
private fun FixtureDebugSurface(
    fixture: StageScreenshotCase,
    presentation: DebugPresentation,
) {
    val selected = when (fixture.state.debug.selectedSpeaker) {
        SurfaceSpeaker.SAKURA -> fixture.state.sakura
        SurfaceSpeaker.KERO -> fixture.state.kero
    }
    GhostDebugSurface(
        presentation = presentation,
        state = fixture.state.debug,
        selection = SurfaceDebugSelection(
            speaker = fixture.state.debug.selectedSpeaker,
            scope = fixture.state.debug.selectedSpeaker.name.lowercase(),
            surfaceId = selected.definition.id.toString(),
            intrinsicWidth = selected.definition.width,
            intrinsicHeight = selected.definition.height,
            composedLeft = 24,
            composedTop = 96,
            composedRight = 264,
            composedBottom = 356,
            composedWidth = 240,
            composedHeight = 260,
            visibleLeft = 0,
            visibleTop = 0,
            visibleRight = selected.definition.width,
            visibleBottom = selected.definition.height,
            animationId = "fixture-idle",
            visible = true,
            animationRunning = true,
            revision = 1L,
        ),
        lastInput = SurfacePointerDebugEvent(
            speaker = fixture.state.debug.selectedSpeaker,
            viewportX = 120,
            viewportY = 180,
            sourceX = 60,
            sourceY = 90,
            collisionId = 1,
            collisionName = "Face",
            buttonId = 0,
            eventName = "OnMouseDoubleClick",
            source = "touch",
        ),
        logs = listOf(
            BoundedShioriLog.Entry(
                event = "OnBoot",
                request = "GET SHIORI/3.0",
                responseStatus = 200,
                responseValue = "Hello from the deterministic fixture",
            ),
        ),
        onSelectSpeaker = {},
        onCollisionOverlayChange = {},
        onNarTest = {},
        onDismiss = {},
        staticPreview = true,
    )
}

private fun screenshotEnvironment(
    fixture: StageScreenshotCase,
    sizePx: IntSize,
    density: Float,
    fontScale: Float,
): StageEnvironment {
    val widthDp = sizePx.width / density
    val heightDp = sizePx.height / density
    return StageEnvironment(
        safeBounds = StageDpRect(0.dp, 0.dp, widthDp.dp, heightDp.dp),
        density = density,
        fontScale = fontScale,
        canonicalAppBarHeight = 64.dp,
        posture = fixture.posture,
        displayFeatures = fixture.state.displayFeatures,
        inputCapabilities = StageInputCapabilities(
            touch = true,
            mouse = true,
            stylus = false,
            hardwareKeyboard = true,
        ),
        layoutDirection = when (fixture.layoutDirection) {
            androidx.compose.ui.unit.LayoutDirection.Ltr -> StageLayoutDirection.LTR
            androidx.compose.ui.unit.LayoutDirection.Rtl -> StageLayoutDirection.RTL
        },
        ghostKey = "screenshot-${fixture.name}",
    )
}

private fun ScreenshotSurfaceFixture.composedSurface(): ComposedSurface {
    val assetPath = "fixture:${definition.id}:${definition.width}x${definition.height}"
    val plan = SurfaceRenderPlan(
        surfaceId = definition.id,
        width = definition.width,
        height = definition.height,
        base = SurfaceRenderBase.Layers(
            listOf(
                SurfaceRenderLayer(
                    imagePath = assetPath,
                    x = 0,
                    y = 0,
                    width = definition.width,
                    height = definition.height,
                ),
            ),
        ),
        animations = emptyList(),
        collisions = definition.collisions,
        transparencyPolicy = SurfaceTransparencyPolicy.AUTHORED_ALPHA,
    )
    return SurfaceCompositor(
        assets = SurfacePixelAssets { path -> image.takeIf { path == assetPath } },
    ).composeNormal(plan = plan, revision = 1L)
}

private fun DpSize.toPixels(density: Density): IntSize = IntSize(
    width = (width.value * density.density).roundToInt(),
    height = (height.value * density.density).roundToInt(),
)
