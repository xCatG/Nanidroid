package com.cattailsw.nanidroid.compose.stage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.runtime.stage.BubbleInteractionTarget
import com.cattailsw.nanidroid.runtime.stage.BubbleRegionSet
import com.cattailsw.nanidroid.runtime.stage.BubbleScrollOrigin
import com.cattailsw.nanidroid.runtime.stage.MeasuredBubbleHitRegion
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor

/** Compose presentation state only; dialogue and pending actions stay runtime-owned. */
data class BubbleUiState(
    val speaker: SurfaceSpeaker,
    val content: DialogueContent,
    val pendingChoices: List<DialogueAction>,
    val pendingInput: PendingInputState? = null,
    val scrollPosition: Int,
    val userScrolledThisTalk: Boolean,
    val scrollOwnerKey: String = "",
    val talkId: Long = 0L,
    val contentRevision: Long = 0L,
)

/** Decorative pointer orientation; the fixed bubble frame remains the interaction authority. */
internal enum class BubblePointerDirection {
    DOWN,
    LEFT,
    RIGHT,
}

internal val LocalBubblePointerDirection = staticCompositionLocalOf { BubblePointerDirection.DOWN }

/**
 * A fixed-cell dialogue bubble. Real buttons and the standard scroll container
 * own their gestures; the stage receives only immutable hit-test geometry.
 */
@Composable
fun GhostBubble(
    state: BubbleUiState,
    onRegionSet: (BubbleRegionSet) -> Unit = {},
    onAnchor: (AnchorAction) -> Unit = {},
    onExternalUrl: (String) -> Unit = {},
    onInput: (DialogueSegment.InputBox) -> Unit = {},
    onChoose: () -> Unit = {},
    chooseFocusRequester: FocusRequester? = null,
    onScrollPositionChanged: (Int, BubbleScrollOrigin) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val pointerDirection = LocalBubblePointerDirection.current
    val bubbleColor = colorResource(R.color.ghost_list_bg)
    val chooseLabel = stringResource(R.string.dialogue_choose_action)
    val inputLabel = stringResource(R.string.user_input_dlg_title)
    val fence = LocalBubbleRegionFence.current?.takeIf {
        it.speaker == state.speaker &&
            it.talkId == state.talkId &&
            it.contentRevision == state.contentRevision
    }
    val controls = remember(
        state.talkId,
        state.contentRevision,
        state.content,
        state.pendingChoices,
        state.pendingInput,
        chooseLabel,
        inputLabel,
    ) {
        state.controls(chooseLabel, inputLabel)
    }
    val coordinates = remember(state.talkId, state.contentRevision) {
        mutableStateMapOf<Int, LayoutCoordinates>()
    }
    var rootCoordinates by remember(state.talkId, state.contentRevision) {
        mutableStateOf<LayoutCoordinates?>(null)
    }
    var scrollCoordinates by remember(state.talkId, state.contentRevision) {
        mutableStateOf<LayoutCoordinates?>(null)
    }
    var layoutGeneration by remember(state.talkId, state.contentRevision) { mutableLongStateOf(0L) }
    val latestPublication by rememberUpdatedState(onRegionSet)
    val scrollState = remember(state.scrollOwnerKey, state.talkId) {
        ScrollState(
            initial = state.scrollPosition.takeUnless { it == Int.MAX_VALUE }?.coerceAtLeast(0) ?: 0,
        )
    }
    var manuallyScrolled by remember(state.scrollOwnerKey, state.talkId) {
        mutableStateOf(state.userScrolledThisTalk)
    }
    var programmaticPosition by remember(state.scrollOwnerKey, state.talkId) { mutableStateOf<Int?>(null) }
    var viewportHeight by remember(state.scrollOwnerKey, state.talkId) { mutableStateOf(0) }
    val scrollScope = rememberCoroutineScope()
    val latestScrollChange by rememberUpdatedState(onScrollPositionChanged)
    val announcementCandidate = remember(state.content) { state.content.accessibleText() }
    var announcedDialogue by remember(state.talkId) { mutableStateOf("") }

    LaunchedEffect(state.talkId, announcementCandidate) {
        delay(BUBBLE_ANNOUNCEMENT_SETTLE_MILLIS)
        announcedDialogue = announcementCandidate
    }

    LaunchedEffect(state.scrollOwnerKey, state.talkId, scrollState.maxValue, manuallyScrolled) {
        if (!manuallyScrolled) {
            val target = scrollState.maxValue
            programmaticPosition = target
            scrollState.scrollTo(target)
            latestScrollChange(target, BubbleScrollOrigin.PROGRAMMATIC)
        }
    }
    LaunchedEffect(scrollState, state.scrollOwnerKey, state.talkId) {
        var observedMaxValue = scrollState.maxValue
        snapshotFlow { scrollState.value to scrollState.maxValue }
            .drop(1)
            .collect { (position, maxValue) ->
                val viewportOrContentChanged = maxValue != observedMaxValue
                observedMaxValue = maxValue
                if (programmaticPosition == position) {
                    programmaticPosition = null
                } else if (!viewportOrContentChanged) {
                    manuallyScrolled = true
                    latestScrollChange(position, BubbleScrollOrigin.MANUAL)
                }
            }
    }
    LaunchedEffect(fence, controls, layoutGeneration) {
        val activeFence = fence ?: return@LaunchedEffect
        val root = rootCoordinates?.takeIf(LayoutCoordinates::isAttached)
        if (root == null) {
            latestPublication(BubbleRegionSet(activeFence, emptyList(), null))
            return@LaunchedEffect
        }
        val actionRegions = controls.mapNotNull { control ->
            val child = coordinates[control.index]?.takeIf(LayoutCoordinates::isAttached) ?: return@mapNotNull null
            root.rootClippedBoundsIn(activeFence.frame, child)?.let { bounds ->
                MeasuredBubbleHitRegion(bounds, control.target)
            }
        }
        val scrollViewport = scrollCoordinates
            ?.takeIf(LayoutCoordinates::isAttached)
            ?.let { scroll -> root.rootClippedBoundsIn(activeFence.frame, scroll) }
        latestPublication(
            BubbleRegionSet(
                fence = activeFence,
                actionRegions = actionRegions,
                scrollViewport = scrollViewport,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("ghost-bubble-${state.speaker.tag}")
            .semantics {
                if (announcedDialogue.isNotBlank()) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = announcedDialogue
                }
            }
            .onGloballyPositioned { next ->
                rootCoordinates = next
                viewportHeight = next.size.height
                layoutGeneration++
            },
    ) {
        BubbleBackground(pointerDirection, bubbleColor)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerBodyPadding(pointerDirection)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .onGloballyPositioned { next ->
                        scrollCoordinates = next
                        layoutGeneration++
                    }
                    .testTag("ghost-bubble-scroll-${state.speaker.tag}")
                    .padding(8.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val delta = when (event.key) {
                            Key.DirectionUp -> -48f
                            Key.DirectionDown -> 48f
                            Key.PageUp -> -viewportHeight.toFloat()
                            Key.PageDown -> viewportHeight.toFloat()
                            else -> return@onPreviewKeyEvent false
                        }
                        manuallyScrolled = true
                        programmaticPosition = null
                        latestScrollChange(scrollState.value, BubbleScrollOrigin.MANUAL)
                        scrollScope.launch { scrollState.scrollBy(delta) }
                        true
                    }
                    .focusable(),
            ) {
                state.content.segments.forEachIndexed { segmentIndex, segment ->
                    when (segment) {
                        is DialogueSegment.Text -> SelectionContainer {
                            Text(segment.value, color = colorResource(R.color.ghost_list_text))
                        }
                        DialogueSegment.NewLine -> Spacer(Modifier.height(8.dp))
                        else -> controls.firstOrNull { it.segmentIndex == segmentIndex }?.let { control ->
                            key(state.talkId, state.contentRevision, control.identity) {
                                BubbleControlButton(
                                    control = control,
                                    speaker = state.speaker,
                                    onPositioned = { child ->
                                        coordinates[control.index] = child
                                        layoutGeneration++
                                    },
                                    onAnchor = onAnchor,
                                    onExternalUrl = onExternalUrl,
                                    onInput = onInput,
                                    onChoose = onChoose,
                                    focusRequester = null,
                                )
                            }
                        }
                    }
                }
                controls.filter { it.segmentIndex == null }.forEach { control ->
                    key(state.talkId, state.contentRevision, control.identity) {
                        BubbleControlButton(
                            control = control,
                            speaker = state.speaker,
                            onPositioned = { child ->
                                coordinates[control.index] = child
                                layoutGeneration++
                            },
                            onAnchor = onAnchor,
                            onExternalUrl = onExternalUrl,
                            onInput = onInput,
                            onChoose = onChoose,
                            focusRequester = chooseFocusRequester.takeIf {
                                control.target is BubbleInteractionTarget.Choice
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Maps a measured descendant into the stage frame, clipping it to the bubble root. */
private fun LayoutCoordinates.rootClippedBoundsIn(
    frame: IntRect,
    descendant: LayoutCoordinates,
): IntRect? {
    val localBounds = localBoundingBoxOf(descendant, clipBounds = true)
    val local = IntRect(
        floor(localBounds.left).toInt().coerceIn(0, frame.width),
        floor(localBounds.top).toInt().coerceIn(0, frame.height),
        ceil(localBounds.right).toInt().coerceIn(0, frame.width),
        ceil(localBounds.bottom).toInt().coerceIn(0, frame.height),
    )
    return IntRect(
        frame.left + local.left,
        frame.top + local.top,
        frame.left + local.right,
        frame.top + local.bottom,
    ).takeIf { it.width > 0 && it.height > 0 }
}

private fun DialogueContent.accessibleText(): String = buildString {
    segments.forEach { segment ->
        when (segment) {
            is DialogueSegment.Text -> append(segment.value)
            DialogueSegment.NewLine -> append('\n')
            else -> Unit
        }
    }
}.trim()

private const val BUBBLE_ANNOUNCEMENT_SETTLE_MILLIS = 500L

@Composable
private fun BubbleBackground(
    direction: BubblePointerDirection,
    color: Color,
) {
    Canvas(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {},
    ) {
        val pointer = 12.dp.toPx().coerceAtMost(minOf(size.width, size.height) / 3f)
        val radius = 8.dp.toPx()
        val bodyTopLeft: Offset
        val bodySize: Size
        val pointerPath = Path()
        when (direction) {
            BubblePointerDirection.DOWN -> {
                bodyTopLeft = Offset.Zero
                bodySize = Size(size.width, (size.height - pointer).coerceAtLeast(0f))
                val center = size.width * 0.5f
                pointerPath.moveTo(center - pointer, bodySize.height)
                pointerPath.lineTo(center, size.height)
                pointerPath.lineTo(center + pointer, bodySize.height)
            }
            BubblePointerDirection.LEFT -> {
                bodyTopLeft = Offset(pointer, 0f)
                bodySize = Size((size.width - pointer).coerceAtLeast(0f), size.height)
                val center = size.height * 0.65f
                pointerPath.moveTo(pointer, center - pointer)
                pointerPath.lineTo(0f, center)
                pointerPath.lineTo(pointer, center + pointer)
            }
            BubblePointerDirection.RIGHT -> {
                bodyTopLeft = Offset.Zero
                bodySize = Size((size.width - pointer).coerceAtLeast(0f), size.height)
                val center = size.height * 0.65f
                pointerPath.moveTo(bodySize.width, center - pointer)
                pointerPath.lineTo(size.width, center)
                pointerPath.lineTo(bodySize.width, center + pointer)
            }
        }
        pointerPath.close()
        drawRoundRect(
            color = color,
            topLeft = bodyTopLeft,
            size = bodySize,
            cornerRadius = CornerRadius(radius, radius),
        )
        drawPath(pointerPath, color)
    }
}

private fun Modifier.pointerBodyPadding(direction: BubblePointerDirection): Modifier = when (direction) {
    BubblePointerDirection.DOWN -> padding(bottom = 12.dp)
    BubblePointerDirection.LEFT -> padding(start = 12.dp)
    BubblePointerDirection.RIGHT -> padding(end = 12.dp)
}

@Composable
private fun BubbleControlButton(
    control: BubbleControl,
    speaker: SurfaceSpeaker,
    onPositioned: (LayoutCoordinates) -> Unit,
    onAnchor: (AnchorAction) -> Unit,
    onExternalUrl: (String) -> Unit,
    onInput: (DialogueSegment.InputBox) -> Unit,
    onChoose: () -> Unit,
    focusRequester: FocusRequester?,
) {
    TextButton(
        onClick = {
            when (val target = control.target) {
                is BubbleInteractionTarget.Anchor -> onAnchor(target.action)
                is BubbleInteractionTarget.ExternalUrl -> onExternalUrl(target.uri)
                is BubbleInteractionTarget.Input -> onInput(target.input)
                is BubbleInteractionTarget.Choice -> onChoose()
                is BubbleInteractionTarget.Scroll,
                is BubbleInteractionTarget.Frame,
                -> Unit
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .testTag(control.tag(speaker))
            .onGloballyPositioned(onPositioned),
    ) {
        Text(control.label)
    }
}

private data class BubbleControl(
    val index: Int,
    val segmentIndex: Int?,
    val target: BubbleInteractionTarget,
    val label: String,
    val kind: String,
    val identity: IdentityToken,
) {
    fun tag(speaker: SurfaceSpeaker): String = when (kind) {
        "choose" -> "ghost-bubble-choose-${speaker.tag}"
        else -> "ghost-bubble-$kind-${speaker.tag}-$index"
    }
}

private class IdentityToken(private val value: Any) {
    override fun equals(other: Any?): Boolean = other is IdentityToken && other.value === value
    override fun hashCode(): Int = System.identityHashCode(value)
}

private fun BubbleUiState.controls(chooseLabel: String, inputLabel: String): List<BubbleControl> = buildList {
    content.segments.forEachIndexed { segmentIndex, segment ->
        when (segment) {
            is DialogueSegment.Anchor -> add(
                BubbleControl(
                    index = size,
                    segmentIndex = segmentIndex,
                    target = BubbleInteractionTarget.Anchor(segment.action),
                    label = segment.action.label,
                    kind = "anchor",
                    identity = IdentityToken(segment.action),
                ),
            )
            is DialogueSegment.ExternalUrl -> add(
                BubbleControl(
                    index = size,
                    segmentIndex = segmentIndex,
                    target = BubbleInteractionTarget.ExternalUrl(segment.uri),
                    label = segment.label,
                    kind = "external-url",
                    identity = IdentityToken(segment),
                ),
            )
            is DialogueSegment.InputBox -> if (pendingInput?.spec === segment.spec) {
                add(
                    BubbleControl(
                        index = size,
                        segmentIndex = segmentIndex,
                        target = BubbleInteractionTarget.Input(segment),
                        label = inputLabel,
                        kind = "input",
                        identity = IdentityToken(segment),
                    ),
                )
            }
            else -> Unit
        }
    }
    pendingInput?.takeIf { pending ->
        content.segments.none { segment ->
            (segment as? DialogueSegment.InputBox)?.spec === pending.spec
        }
    }?.let { pending ->
        val input = DialogueSegment.InputBox(pending.spec)
        add(
            BubbleControl(
                index = size,
                segmentIndex = null,
                target = BubbleInteractionTarget.Input(input),
                label = inputLabel,
                kind = "input",
                identity = IdentityToken(pending),
            ),
        )
    }
    pendingChoices.firstOrNull()?.let { action ->
        add(
            BubbleControl(
                index = size,
                segmentIndex = null,
                target = BubbleInteractionTarget.Choice(action),
            label = chooseLabel,
                kind = "choose",
                identity = IdentityToken(action),
            ),
        )
    }
}

private val AnchorAction.label: String
    get() = when (this) {
        is AnchorAction.Normal -> label
        is AnchorAction.DirectEvent -> label
    }

private val SurfaceSpeaker.tag: String get() = name.lowercase()
