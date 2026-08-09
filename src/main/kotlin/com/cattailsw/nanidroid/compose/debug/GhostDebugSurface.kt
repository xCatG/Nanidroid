@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cattailsw.nanidroid.compose.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.BoundedShioriLog

internal const val GHOST_DEBUG_SURFACE_FULL_STAGE_MODAL_TAG = "ghost-debug-surface-full-stage-modal"
internal const val GHOST_DEBUG_SURFACE_BOTTOM_SHEET_TAG = "ghost-debug-surface-bottom-sheet"
internal const val GHOST_DEBUG_SURFACE_SIDE_PANEL_TAG = "ghost-debug-surface-side-panel"
internal const val GHOST_DEBUG_SURFACE_SAKURA_TAG = "ghost-debug-surface-speaker-sakura"
internal const val GHOST_DEBUG_SURFACE_KERO_TAG = "ghost-debug-surface-speaker-kero"
internal const val GHOST_DEBUG_SURFACE_COLLISION_SWITCH_TAG = "ghost-debug-surface-collision-switch"
internal const val GHOST_DEBUG_SURFACE_SHOW_ON_STAGE_TAG = "ghost-debug-surface-show-on-stage"
internal const val GHOST_DEBUG_SURFACE_NAR_TEST_TAG = "ghost-debug-surface-nar-test"
internal const val GHOST_DEBUG_SURFACE_SAMPLE_FEEDBACK_TAG = "ghost-debug-surface-sample-feedback"
internal const val GHOST_DEBUG_SURFACE_DISMISS_TAG = "ghost-debug-surface-dismiss"
internal const val GHOST_DEBUG_SURFACE_EMPTY_LOG_TAG = "ghost-debug-surface-empty-log"
internal const val GHOST_DEBUG_SURFACE_SHIORI_LOG_TAG = "ghost-debug-surface-shiori-log"
internal const val GHOST_DEBUG_SURFACE_SHIORI_LOG_LIST_TAG = "ghost-debug-surface-shiori-log-list"

@Composable
internal fun GhostDebugSurface(
    presentation: DebugPresentation,
    state: DebugPanelState,
    selection: SurfaceDebugSelection?,
    lastInput: SurfacePointerDebugEvent?,
    logs: List<BoundedShioriLog.Entry>,
    onSelectSpeaker: (SurfaceSpeaker) -> Unit,
    onCollisionOverlayChange: (Boolean) -> Unit,
    onNarTest: () -> Unit,
    onDismiss: () -> Unit,
    onShowCollisionOverlayOnStage: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    staticPreview: Boolean = false,
) {
    if (!state.visible) return

    when (presentation) {
        DebugPresentation.FULL_STAGE_MODAL -> if (staticPreview) StaticFullStageGhostDebugSurface(
            state = state,
            selection = selection,
            lastInput = lastInput,
            logs = logs,
            onSelectSpeaker = onSelectSpeaker,
            onCollisionOverlayChange = onCollisionOverlayChange,
            onNarTest = onNarTest,
            onDismiss = onDismiss,
            onShowCollisionOverlayOnStage = onShowCollisionOverlayOnStage,
            modifier = modifier,
        ) else FullStageGhostDebugSurface(
            state = state,
            selection = selection,
            lastInput = lastInput,
            logs = logs,
            onSelectSpeaker = onSelectSpeaker,
            onCollisionOverlayChange = onCollisionOverlayChange,
            onNarTest = onNarTest,
            onDismiss = onDismiss,
            onShowCollisionOverlayOnStage = onShowCollisionOverlayOnStage,
            modifier = modifier,
        )
        DebugPresentation.BOTTOM_SHEET -> if (staticPreview) StaticBottomSheetGhostDebugSurface(
            state = state,
            selection = selection,
            lastInput = lastInput,
            logs = logs,
            onSelectSpeaker = onSelectSpeaker,
            onCollisionOverlayChange = onCollisionOverlayChange,
            onNarTest = onNarTest,
            onDismiss = onDismiss,
            modifier = modifier,
        ) else BottomSheetGhostDebugSurface(
            state = state,
            selection = selection,
            lastInput = lastInput,
            logs = logs,
            onSelectSpeaker = onSelectSpeaker,
            onCollisionOverlayChange = onCollisionOverlayChange,
            onNarTest = onNarTest,
            onDismiss = onDismiss,
            modifier = modifier,
        )
        DebugPresentation.SIDE_PANEL -> SidePanelGhostDebugSurface(
            state = state,
            selection = selection,
            lastInput = lastInput,
            logs = logs,
            onSelectSpeaker = onSelectSpeaker,
            onCollisionOverlayChange = onCollisionOverlayChange,
            onNarTest = onNarTest,
            onDismiss = onDismiss,
            modifier = modifier,
        )
    }
}

/** Layoutlib-only deterministic host; runtime callers use platform modal surfaces. */
@Composable
private fun StaticFullStageGhostDebugSurface(
    state: DebugPanelState,
    selection: SurfaceDebugSelection?,
    lastInput: SurfacePointerDebugEvent?,
    logs: List<BoundedShioriLog.Entry>,
    onSelectSpeaker: (SurfaceSpeaker) -> Unit,
    onCollisionOverlayChange: (Boolean) -> Unit,
    onNarTest: () -> Unit,
    onDismiss: () -> Unit,
    onShowCollisionOverlayOnStage: (() -> Unit)?,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.56f)),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .testTag(GHOST_DEBUG_SURFACE_FULL_STAGE_MODAL_TAG),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            GhostDebugSurfaceContent(
                state = state,
                selection = selection,
                lastInput = lastInput,
                logs = logs,
                onSelectSpeaker = onSelectSpeaker,
                onCollisionOverlayChange = onCollisionOverlayChange,
                onNarTest = onNarTest,
                onDismiss = onDismiss,
                onShowCollisionOverlayOnStage = onShowCollisionOverlayOnStage,
            )
        }
    }
}

/** Layoutlib-only deterministic host; runtime callers use [ModalBottomSheet]. */
@Composable
private fun StaticBottomSheetGhostDebugSurface(
    state: DebugPanelState,
    selection: SurfaceDebugSelection?,
    lastInput: SurfacePointerDebugEvent?,
    logs: List<BoundedShioriLog.Entry>,
    onSelectSpeaker: (SurfaceSpeaker) -> Unit,
    onCollisionOverlayChange: (Boolean) -> Unit,
    onNarTest: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .testTag(GHOST_DEBUG_SURFACE_BOTTOM_SHEET_TAG),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 6.dp,
        ) {
            GhostDebugSurfaceContent(
                state = state,
                selection = selection,
                lastInput = lastInput,
                logs = logs,
                onSelectSpeaker = onSelectSpeaker,
                onCollisionOverlayChange = onCollisionOverlayChange,
                onNarTest = onNarTest,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun FullStageGhostDebugSurface(
    state: DebugPanelState,
    selection: SurfaceDebugSelection?,
    lastInput: SurfacePointerDebugEvent?,
    logs: List<BoundedShioriLog.Entry>,
    onSelectSpeaker: (SurfaceSpeaker) -> Unit,
    onCollisionOverlayChange: (Boolean) -> Unit,
    onNarTest: () -> Unit,
    onDismiss: () -> Unit,
    onShowCollisionOverlayOnStage: (() -> Unit)?,
    modifier: Modifier,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp)
                .testTag(GHOST_DEBUG_SURFACE_FULL_STAGE_MODAL_TAG),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            GhostDebugSurfaceContent(
                state = state,
                selection = selection,
                lastInput = lastInput,
                logs = logs,
                onSelectSpeaker = onSelectSpeaker,
                onCollisionOverlayChange = onCollisionOverlayChange,
                onNarTest = onNarTest,
                onDismiss = onDismiss,
                onShowCollisionOverlayOnStage = onShowCollisionOverlayOnStage,
            )
        }
    }
}

@Composable
private fun BottomSheetGhostDebugSurface(
    state: DebugPanelState,
    selection: SurfaceDebugSelection?,
    lastInput: SurfacePointerDebugEvent?,
    logs: List<BoundedShioriLog.Entry>,
    onSelectSpeaker: (SurfaceSpeaker) -> Unit,
    onCollisionOverlayChange: (Boolean) -> Unit,
    onNarTest: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.testTag(GHOST_DEBUG_SURFACE_BOTTOM_SHEET_TAG),
    ) {
        GhostDebugSurfaceContent(
            state = state,
            selection = selection,
            lastInput = lastInput,
            logs = logs,
            onSelectSpeaker = onSelectSpeaker,
            onCollisionOverlayChange = onCollisionOverlayChange,
            onNarTest = onNarTest,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun SidePanelGhostDebugSurface(
    state: DebugPanelState,
    selection: SurfaceDebugSelection?,
    lastInput: SurfacePointerDebugEvent?,
    logs: List<BoundedShioriLog.Entry>,
    onSelectSpeaker: (SurfaceSpeaker) -> Unit,
    onCollisionOverlayChange: (Boolean) -> Unit,
    onNarTest: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    BackHandler(onBack = onDismiss)
    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .widthIn(min = 320.dp, max = 420.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
                        }
                    }
                }
                .testTag(GHOST_DEBUG_SURFACE_SIDE_PANEL_TAG)
                .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)),
            tonalElevation = 6.dp,
        ) {
            GhostDebugSurfaceContent(
                state = state,
                selection = selection,
                lastInput = lastInput,
                logs = logs,
                onSelectSpeaker = onSelectSpeaker,
                onCollisionOverlayChange = onCollisionOverlayChange,
                onNarTest = onNarTest,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun GhostDebugSurfaceContent(
    state: DebugPanelState,
    selection: SurfaceDebugSelection?,
    lastInput: SurfacePointerDebugEvent?,
    logs: List<BoundedShioriLog.Entry>,
    onSelectSpeaker: (SurfaceSpeaker) -> Unit,
    onCollisionOverlayChange: (Boolean) -> Unit,
    onNarTest: () -> Unit,
    onDismiss: () -> Unit,
    onShowCollisionOverlayOnStage: (() -> Unit)? = null,
) {
    val selectedSpeakerText = if (state.selectedSpeaker == SurfaceSpeaker.SAKURA) {
        stringResource(R.string.debug_surface_sakura_speaker_label)
    } else {
        stringResource(R.string.debug_surface_kero_speaker_label)
    }
    val collisionOverlayLabel = stringResource(R.string.debug_surface_collision_overlay_toggle)
    val narLabel = stringResource(R.string.debug_surface_nar_test_button)
    val showOnStageLabel = stringResource(R.string.debug_surface_show_on_stage)
    val closeLabel = stringResource(R.string.close_btn_text)
    val emptyValue = stringResource(R.string.debug_surface_empty_value)
    var expandedLogEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    val feedbackRequester = remember { BringIntoViewRequester() }
    val scrollState = rememberScrollState()

    LaunchedEffect(state.sampleFeedbackToken) {
        if (state.sampleFeedbackToken != 0L) feedbackRequester.bringIntoView()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GhostDebugTopBar(
            selectedSpeakerLabel = selectedSpeakerText,
            closeLabel = closeLabel,
            onDismiss = onDismiss,
        )
        GhostSurfaceSpeakerSection(
            selectedSpeaker = state.selectedSpeaker,
            onSelectSpeaker = onSelectSpeaker,
        )
        GhostDebugSurfaceSection(
            title = stringResource(R.string.debug_surface_surface_section_title),
            content = {
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_scope_label),
                    value = selection?.scope ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_surface_id_label),
                    value = selection?.surfaceId ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_intrinsic_dims_label),
                    value = selection?.let {
                        stringResource(
                            R.string.debug_surface_value_dimensions,
                            it.intrinsicWidth,
                            it.intrinsicHeight,
                        )
                    } ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_composed_bounds_label),
                    value = selection?.let {
                        stringResource(
                            R.string.debug_surface_value_bounds,
                            it.composedLeft,
                            it.composedTop,
                            it.composedRight,
                            it.composedBottom,
                        )
                    } ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_composed_dims_label),
                    value = selection?.let {
                        stringResource(
                            R.string.debug_surface_value_dimensions,
                            it.composedWidth,
                            it.composedHeight,
                        )
                    } ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_visible_bounds_label),
                    value = selection?.let {
                        stringResource(
                            R.string.debug_surface_value_bounds,
                            it.visibleLeft,
                            it.visibleTop,
                            it.visibleRight,
                            it.visibleBottom,
                        )
                    } ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_visible_label),
                    value = selection?.let {
                        stringResource(
                            if (it.visible) R.string.debug_surface_value_true else R.string.debug_surface_value_false,
                        )
                    } ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_animation_label),
                    value = selection?.animationId ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_animation_running_label),
                    value = selection?.let {
                        stringResource(
                            if (it.animationRunning) {
                                R.string.debug_surface_value_true
                            } else {
                                R.string.debug_surface_value_false
                            },
                        )
                    } ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_revision_label),
                    value = selection?.revision?.toString() ?: emptyValue,
                )
            },
        )
        GhostDebugSurfaceSection(
            title = stringResource(R.string.debug_surface_pointer_section_title),
            content = {
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_viewport_coords_label),
                    value = lastInput?.let {
                        stringResource(R.string.debug_surface_value_coordinates, it.viewportX, it.viewportY)
                    } ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_intrinsic_coords_label),
                    value = lastInput?.let {
                        stringResource(R.string.debug_surface_value_coordinates, it.sourceX, it.sourceY)
                    } ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_collision_id_label),
                    value = lastInput?.let {
                        stringResource(R.string.debug_surface_value_collision, it.collisionId, it.collisionName.orEmpty())
                    } ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_pointer_button_label),
                    value = lastInput?.buttonId?.toString() ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_pointer_source_label),
                    value = lastInput?.source ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_pointer_candidate_event_label),
                    value = lastInput?.candidateEvent ?: emptyValue,
                )
                SurfaceInfoField(
                    label = stringResource(R.string.debug_surface_pointer_dispatch_outcome_label),
                    value = lastInput?.let { input ->
                        stringResource(
                            when (input.dispatchOutcome) {
                                PointerDispatchOutcome.NOT_RESOLVED -> R.string.debug_surface_pointer_dispatch_not_resolved
                                PointerDispatchOutcome.REJECTED -> R.string.debug_surface_pointer_dispatch_rejected
                                PointerDispatchOutcome.ACCEPTED -> R.string.debug_surface_pointer_dispatch_accepted
                            },
                        )
                    } ?: emptyValue,
                )
                if (lastInput != null) {
                    SurfaceInfoField(
                        label = stringResource(R.string.debug_surface_pointer_scope_label),
                        value = lastInput.speaker.legacyReference,
                    )
                    SurfaceInfoField(
                        label = stringResource(R.string.debug_surface_pointer_speaker_label),
                        value = stringResource(
                            if (lastInput.speaker == SurfaceSpeaker.SAKURA) {
                                R.string.debug_surface_sakura_speaker_label
                            } else {
                                R.string.debug_surface_kero_speaker_label
                            },
                        ),
                    )
                }
            },
        )
        GhostDebugSurfaceSection(
            title = stringResource(R.string.debug_surface_collision_section_title),
            content = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(collisionOverlayLabel)
                    Switch(
                        checked = state.showCollisionOverlay,
                        onCheckedChange = onCollisionOverlayChange,
                        modifier = Modifier
                            .testTag(GHOST_DEBUG_SURFACE_COLLISION_SWITCH_TAG)
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = collisionOverlayLabel
                            },
                    )
                }
                if (state.showCollisionOverlay && onShowCollisionOverlayOnStage != null) {
                    TextButton(
                        onClick = onShowCollisionOverlayOnStage,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag(GHOST_DEBUG_SURFACE_SHOW_ON_STAGE_TAG),
                    ) {
                        Text(showOnStageLabel)
                    }
                }
            },
        )
        TextButton(
            onClick = onNarTest,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = narLabel
                }
                .testTag(GHOST_DEBUG_SURFACE_NAR_TEST_TAG),
        ) {
            Text(narLabel)
        }
        if (state.sampleFeedbackToken != 0L) {
            Text(
                text = stringResource(R.string.debug_surface_sample_queued),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .bringIntoViewRequester(feedbackRequester)
                    .testTag(GHOST_DEBUG_SURFACE_SAMPLE_FEEDBACK_TAG),
            )
        }
        GhostDebugSurfaceSection(
            title = stringResource(R.string.debug_surface_shiori_section_title),
            content = {
                if (logs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.debug_surface_empty_log),
                        modifier = Modifier.testTag(GHOST_DEBUG_SURFACE_EMPTY_LOG_TAG),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .testTag(GHOST_DEBUG_SURFACE_SHIORI_LOG_TAG),
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(GHOST_DEBUG_SURFACE_SHIORI_LOG_LIST_TAG),
                        ) {
                            items(logs, key = { it.id }) { log ->
                                GhostShioriLogRow(
                                    entry = log,
                                    expanded = expandedLogEntryId == log.id,
                                    onToggleExpanded = {
                                        expandedLogEntryId = if (expandedLogEntryId == log.id) null else log.id
                                    },
                                    modifier = Modifier.testTag("ghost-debug-surface-shiori-log-${log.id}"),
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun GhostDebugTopBar(
    selectedSpeakerLabel: String,
    closeLabel: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.debug_surface_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.debug_surface_selected_speaker_label, selectedSpeakerLabel),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(GHOST_DEBUG_SURFACE_DISMISS_TAG)
                .semantics { contentDescription = closeLabel },
        ) {
            Text(closeLabel)
        }
    }
}

@Composable
private fun GhostDebugSurfaceSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun GhostSurfaceSpeakerSection(
    selectedSpeaker: SurfaceSpeaker,
    onSelectSpeaker: (SurfaceSpeaker) -> Unit,
) {
    val sakuraLabel = stringResource(R.string.debug_surface_sakura_speaker_label)
    val keroLabel = stringResource(R.string.debug_surface_kero_speaker_label)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.debug_surface_speaker_label), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSelectSpeaker(SurfaceSpeaker.SAKURA) },
                    enabled = selectedSpeaker != SurfaceSpeaker.SAKURA,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag(GHOST_DEBUG_SURFACE_SAKURA_TAG)
                        .semantics {
                            contentDescription = sakuraLabel
                        },
                ) {
                    Text(sakuraLabel)
                }
                Button(
                    onClick = { onSelectSpeaker(SurfaceSpeaker.KERO) },
                    enabled = selectedSpeaker != SurfaceSpeaker.KERO,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag(GHOST_DEBUG_SURFACE_KERO_TAG)
                        .semantics {
                            contentDescription = keroLabel
                        },
                ) {
                    Text(keroLabel)
                }
            }
        }
    }
}

@Composable
private fun SurfaceInfoField(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.4f))
    }
}

@Composable
private fun GhostShioriLogRow(
    entry: BoundedShioriLog.Entry,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val request = if (expanded) entry.request else entry.request.payloadPreview()
    val response = if (expanded) entry.response else entry.response.payloadPreview()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.debug_surface_log_entry_event, entry.event),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.debug_surface_log_entry_request, request),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(R.string.debug_surface_log_entry_status, entry.responseStatus),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(R.string.debug_surface_log_entry_response, response),
            style = MaterialTheme.typography.bodySmall,
        )
        if (entry.request != request || entry.response != response || expanded) {
            TextButton(
                onClick = onToggleExpanded,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("ghost-debug-surface-shiori-log-${entry.id}-toggle"),
            ) {
                Text(
                    stringResource(
                        if (expanded) {
                            R.string.debug_surface_log_collapse_payload
                        } else {
                            R.string.debug_surface_log_show_full_payload
                        },
                    ),
                )
            }
        }
    }
}

private fun String.payloadPreview(maxCodePoints: Int = 2_048): String {
    val count = codePointCount(0, length)
    if (count <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints)) + "…"
}
