package com.cattailsw.nanidroid.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.compose.stage.GhostStageMeasureState
import com.cattailsw.nanidroid.compose.stage.BubbleUiState
import com.cattailsw.nanidroid.compose.stage.DialogueActionSurface
import com.cattailsw.nanidroid.compose.stage.useCompactDialogueActionSurface
import com.cattailsw.nanidroid.compose.stage.GhostBubble
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
import com.cattailsw.nanidroid.runtime.stage.StageMode
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.runtime.stage.BubbleScrollKey
import com.cattailsw.nanidroid.runtime.stage.GhostBubbleScrollMemory
import com.cattailsw.nanidroid.runtime.stage.BubbleScrollProcessSession
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import kotlin.math.roundToInt

/** Production adaptive stage consuming atomic composed surfaces. */
@Composable
fun GhostPresentationStage(
    presentation: GhostPresentationState,
    sakuraComposedSurface: ComposedSurface?,
    keroComposedSurface: ComposedSurface?,
    measureState: GhostStageMeasureState,
    ghostKey: String,
    bubbleScrollSessionKey: String = BubbleScrollProcessSession.key,
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
    sakuraDialogue: DialogueContent = DialogueContent(GhostSpeaker.SAKURA, emptyList()),
    keroDialogue: DialogueContent = DialogueContent(GhostSpeaker.KERO, emptyList()),
    sakuraPendingChoices: List<DialogueAction> = emptyList(),
    keroPendingChoices: List<DialogueAction> = emptyList(),
    sakuraPendingInput: PendingInputState? = null,
    keroPendingInput: PendingInputState? = null,
    dialogueTalkId: Long = 0L,
    dialogueRevision: Long = 0L,
    sakuraActiveAnimationId: String? = null,
    keroActiveAnimationId: String? = null,
    onDialogueChoice: (DialogueAction) -> Unit = {},
    onDialogueAnchor: (AnchorAction) -> Unit = {},
    onDialogueExternalUrl: (String) -> Unit = {},
    onDialogueInput: (DialogueSegment.InputBox) -> Unit = {},
    sakuraSurface: @Composable BoxScope.(StageSurfaceSnapshot) -> Unit = {},
    keroSurface: @Composable BoxScope.(StageSurfaceSnapshot) -> Unit = {},
) {
    StageEnvironmentProvider { windowEnvironment ->
        var placement by remember { mutableStateOf<StagePlacement?>(null) }
        // Presentation-open state is intentionally not saveable: a recreated
        // Activity must re-open from the runner's still-pending exact actions.
        var actionSurfaceSpeakerName by remember { mutableStateOf<String?>(null) }
        val actionSurfaceSpeaker = actionSurfaceSpeakerName?.let(SurfaceSpeaker::valueOf)
        var actionSurfaceActionIdentities by remember { mutableStateOf<List<DialogueAction>?>(null) }
        var restoreChooseFocus by remember { mutableStateOf<SurfaceSpeaker?>(null) }
        val keroChooseFocusRequester = remember { FocusRequester() }
        val sakuraChooseFocusRequester = remember { FocusRequester() }
        val stageView = LocalView.current
        val inputModeManager = LocalInputModeManager.current
        DisposableEffect(stageView, restoreChooseFocus) {
            val requestFocus = Runnable {
                val speaker = restoreChooseFocus ?: return@Runnable
                if (!stageView.hasWindowFocus()) return@Runnable
                inputModeManager.requestInputMode(InputMode.Keyboard)
                val requester = when (speaker) {
                    SurfaceSpeaker.KERO -> keroChooseFocusRequester
                    SurfaceSpeaker.SAKURA -> sakuraChooseFocusRequester
                }
                if (runCatching { requester.requestFocus() }.getOrDefault(false)) {
                    restoreChooseFocus = null
                }
            }
            val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                if (focused && restoreChooseFocus != null) stageView.post(requestFocus)
            }
            stageView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            if (restoreChooseFocus != null && stageView.hasWindowFocus()) stageView.post(requestFocus)
            onDispose {
                stageView.removeCallbacks(requestFocus)
                if (stageView.viewTreeObserver.isAlive) {
                    stageView.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
                }
            }
        }
        val bubbleScrollOwner = rememberSaveable(
            saver = listSaver(
                save = { owner -> owner.saveValues() },
                restore = { values -> GhostBubbleScrollMemory.restoreValues(values) },
            ),
        ) { GhostBubbleScrollMemory.forContext(bubbleScrollSessionKey, ghostKey) }
        val bubbleScrollMemory = bubbleScrollOwner.memoryFor(bubbleScrollSessionKey, ghostKey)
        val bubbleScrollOwnerKey = "$bubbleScrollSessionKey:$ghostKey"
        val keroScrollKey = BubbleScrollKey(SurfaceSpeaker.KERO, dialogueTalkId)
        val sakuraScrollKey = BubbleScrollKey(SurfaceSpeaker.SAKURA, dialogueTalkId)
        val keroScroll = bubbleScrollMemory.snapshot(keroScrollKey)
        val sakuraScroll = bubbleScrollMemory.snapshot(sakuraScrollKey)
        val actionSurfaceActions = when (actionSurfaceSpeaker) {
            SurfaceSpeaker.KERO -> keroPendingChoices
            SurfaceSpeaker.SAKURA -> sakuraPendingChoices
            null -> emptyList()
        }
        val actionSurfaceStale = actionSurfaceSpeaker != null && (
            actionSurfaceActions.isEmpty() ||
                actionSurfaceActionIdentities?.hasSameRuntimeIdentities(actionSurfaceActions) == false
            )
        val activeActionSurfaceSpeaker = actionSurfaceSpeaker.takeUnless { actionSurfaceStale }
        val tinyFallback = measureState.latest?.layoutDp?.mode == StageMode.TINY
        if (actionSurfaceStale) {
            SideEffect {
                actionSurfaceSpeakerName = null
                actionSurfaceActionIdentities = null
                restoreChooseFocus = null
            }
        }
        if (tinyFallback && actionSurfaceSpeakerName != null) {
            SideEffect {
                actionSurfaceSpeakerName = null
                actionSurfaceActionIdentities = null
                restoreChooseFocus = null
            }
        }
        LaunchedEffect(
            actionSurfaceSpeaker,
            actionSurfaceActions,
            dialogueTalkId,
            dialogueRevision,
        ) {
            if (actionSurfaceSpeaker == null) {
                actionSurfaceActionIdentities = null
            } else if (!actionSurfaceStale && actionSurfaceActionIdentities == null) {
                // A restored open surface adopts the runner's current exact objects.
                actionSurfaceActionIdentities = actionSurfaceActions.toList()
            }
        }
        // StagePointerInput evaluates this provider during composition for
        // eager cancellation and again at event time for authoritative state.
        StagePointerInput(
            snapshotProvider = {
                currentStageInputSnapshot(
                    measured = measureState.latest,
                    blocking = blockingInputProvider() || activeActionSurfaceSpeaker != null,
                    ghostKey = ghostKey,
                    ghostIdentity = ghostIdentityProvider(),
                    routingEpoch = StagePresentationRoutingEpoch(
                        external = routingEpochProvider(),
                        measured = measureState.inputEpoch,
                        modalSpeaker = activeActionSurfaceSpeaker,
                    ),
                )
            },
            onSurfaceEffect = onSurfaceEffect,
            onToggleChrome = onToggleChrome,
            enabled = !tinyFallback,
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
                    forceKeroBalloon = keroPendingChoices.isNotEmpty() || keroPendingInput != null,
                    forceSakuraBalloon = sakuraPendingChoices.isNotEmpty() || sakuraPendingInput != null,
                    dialogueTalkId = dialogueTalkId,
                    dialogueRevision = dialogueRevision,
                    sakuraActiveAnimationId = sakuraActiveAnimationId,
                    keroActiveAnimationId = keroActiveAnimationId,
                    keroBalloon = {
                        GhostBubble(
                            state = BubbleUiState(
                                speaker = SurfaceSpeaker.KERO,
                                content = keroDialogue,
                                pendingChoices = keroPendingChoices,
                                pendingInput = keroPendingInput,
                                scrollPosition = keroScroll.position,
                                userScrolledThisTalk = keroScroll.userScrolled,
                                scrollOwnerKey = bubbleScrollOwnerKey,
                                talkId = dialogueTalkId,
                                contentRevision = dialogueRevision,
                            ),
                            onRegionSet = { measureState.publishBubbleRegions(it) },
                            onAnchor = onDialogueAnchor,
                            onExternalUrl = onDialogueExternalUrl,
                            onInput = onDialogueInput,
                            onChoose = {
                                actionSurfaceActionIdentities = keroPendingChoices.toList()
                                actionSurfaceSpeakerName = SurfaceSpeaker.KERO.name
                            },
                            chooseFocusRequester = keroChooseFocusRequester,
                            onScrollPositionChanged = { position, origin ->
                                bubbleScrollMemory.update(keroScrollKey, position, origin)
                            },
                        )
                    },
                    sakuraBalloon = {
                        GhostBubble(
                            state = BubbleUiState(
                                speaker = SurfaceSpeaker.SAKURA,
                                content = sakuraDialogue,
                                pendingChoices = sakuraPendingChoices,
                                pendingInput = sakuraPendingInput,
                                scrollPosition = sakuraScroll.position,
                                userScrolledThisTalk = sakuraScroll.userScrolled,
                                scrollOwnerKey = bubbleScrollOwnerKey,
                                talkId = dialogueTalkId,
                                contentRevision = dialogueRevision,
                            ),
                            onRegionSet = { measureState.publishBubbleRegions(it) },
                            onAnchor = onDialogueAnchor,
                            onExternalUrl = onDialogueExternalUrl,
                            onInput = onDialogueInput,
                            onChoose = {
                                actionSurfaceActionIdentities = sakuraPendingChoices.toList()
                                actionSurfaceSpeakerName = SurfaceSpeaker.SAKURA.name
                            },
                            chooseFocusRequester = sakuraChooseFocusRequester,
                            onScrollPositionChanged = { position, origin ->
                                bubbleScrollMemory.update(sakuraScrollKey, position, origin)
                            },
                        )
                    },
                    surfaceContent = { snapshot ->
                        when (snapshot.speaker) {
                            SurfaceSpeaker.KERO -> keroSurface(snapshot)
                            SurfaceSpeaker.SAKURA -> sakuraSurface(snapshot)
                        }
                    },
                )
                if (tinyFallback) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        TinyStageFallback(modifier = Modifier.padding(12.dp))
                    }
                }
                activeActionSurfaceSpeaker?.takeUnless { tinyFallback }?.let { speaker ->
                    DialogueActionSurface(
                        actions = actionSurfaceActions,
                        speaker = speaker,
                        open = true,
                        compact = useCompactDialogueActionSurface(
                            widthPx = windowEnvironment.windowSizePx.width,
                            density = windowEnvironment.density,
                            touch = windowEnvironment.inputCapabilities.touch,
                        ),
                        onDismiss = {
                            restoreChooseFocus = speaker
                            actionSurfaceSpeakerName = null
                            actionSurfaceActionIdentities = null
                        },
                        onAction = { action ->
                            actionSurfaceSpeakerName = null
                            actionSurfaceActionIdentities = null
                            onDialogueChoice(action)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun TinyStageFallback(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = stringResource(R.string.stage_tiny_window_message),
            modifier = Modifier.padding(24.dp),
            textAlign = TextAlign.Center,
        )
    }
}

private fun List<DialogueAction>.hasSameRuntimeIdentities(other: List<DialogueAction>): Boolean =
    size == other.size && indices.all { this[it] === other[it] }

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
    val sakuraDialogue = remember(presentation.sakura.text) {
        DialogueContent(
            GhostSpeaker.SAKURA,
            if (presentation.sakura.text.isEmpty()) emptyList() else listOf(
                DialogueSegment.Text(presentation.sakura.text),
            ),
        )
    }
    val keroDialogue = remember(presentation.kero.text) {
        DialogueContent(
            GhostSpeaker.KERO,
            if (presentation.kero.text.isEmpty()) emptyList() else listOf(
                DialogueSegment.Text(presentation.kero.text),
            ),
        )
    }
    GhostPresentationStage(
        presentation = presentation,
        sakuraComposedSurface = sakura,
        keroComposedSurface = kero,
        measureState = measureState,
        ghostKey = "legacy-preview",
        sakuraDialogue = sakuraDialogue,
        keroDialogue = keroDialogue,
        showSakuraBalloon = showSakuraBalloon,
        showKeroBalloon = showKeroBalloon,
        modifier = modifier,
        sakuraSurface = { sakuraSurface() },
        keroSurface = { keroSurface() },
    )
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
private data class StagePresentationRoutingEpoch(
    val external: Any,
    val measured: Long,
    val modalSpeaker: SurfaceSpeaker?,
)

private fun Offset.roundedOffset() = IntOffset(x.roundToInt(), y.roundToInt())

private fun saturatingAdd(first: Int, second: Int): Int =
    (first.toLong() + second.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

private data object LegacyPreviewOwner

private val CANONICAL_APP_BAR_HEIGHT = 64.dp

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
