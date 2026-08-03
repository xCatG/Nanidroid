package com.cattailsw.nanidroid.compose.stage

import android.view.ViewConfiguration
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.stage.ClickDeadlineScheduler
import com.cattailsw.nanidroid.runtime.stage.IdempotentCancellationHandle
import com.cattailsw.nanidroid.runtime.stage.PhysicalClickSequencer
import com.cattailsw.nanidroid.runtime.stage.StageInputRouter
import com.cattailsw.nanidroid.runtime.stage.StageInputSnapshot
import com.cattailsw.nanidroid.runtime.stage.StageInputTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun interface SemanticStageActivation {
    fun activate(speaker: SurfaceSpeaker): Boolean
}

internal val LocalSemanticStageActivation = staticCompositionLocalOf {
    SemanticStageActivation { false }
}

/** The one full-stage pointer owner. Children retain visual and semantic roles only. */
@Composable
internal fun StagePointerInput(
    snapshotProvider: () -> StageInputSnapshot,
    onSurfaceEffect: (SurfaceInteractionEffect) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
    monotonicNowMillis: () -> Long = SystemClock::uptimeMillis,
    content: @Composable (SemanticStageActivation) -> Unit,
) {
    val latestSnapshotProvider by rememberUpdatedState(snapshotProvider)
    val latestSurfaceEffect by rememberUpdatedState(onSurfaceEffect)
    val latestToggleChrome by rememberUpdatedState(onToggleChrome)
    val latestMonotonicNowMillis by rememberUpdatedState(monotonicNowMillis)
    val scope = rememberCoroutineScope()
    val scheduler = remember(scope) {
        ClickDeadlineScheduler { delayMillis, action ->
            val job = scope.launch {
                delay(delayMillis)
                action()
            }
            IdempotentCancellationHandle(job::cancel)
        }
    }
    val sequencer = remember(scheduler) {
        PhysicalClickSequencer(scheduler, { latestMonotonicNowMillis() }) { latestSurfaceEffect(it) }
    }
    val currentGeometry = snapshotProvider().geometryToken
    SideEffect { sequencer.retainGeometry(currentGeometry) }
    DisposableEffect(sequencer) { onDispose { sequencer.cancelAll() } }

    val platformConfiguration = ViewConfiguration.get(LocalContext.current)
    val toggleChromeLabel = stringResource(R.string.toggle_stage_chrome_action)
    val doubleClickTimeoutMillis = ViewConfiguration.getDoubleTapTimeout().toLong()
    val doubleClickSlopPx = platformConfiguration.scaledDoubleTapSlop.toFloat()
    val semanticActivation = remember(sequencer) {
        SemanticStageActivation { speaker ->
            val snapshot = latestSnapshotProvider()
            val surface = snapshot.surfaces.firstOrNull { it.speaker == speaker }
                ?: return@SemanticStageActivation false
            val bounds = surface.transform.renderedBounds
            val point = Offset(
                bounds.left + bounds.width / 2f,
                bounds.top + bounds.height / 2f,
            )
            val resolution = StageInputRouter.resolve(snapshot, point, PointerSource.TOUCH, PRIMARY_BUTTON)
            val target = resolution.target as? StageInputTarget.Surface
            val effect = resolution.effect
            if (!resolution.activatable || target?.speaker != speaker || effect == null) {
                false
            } else {
                latestSurfaceEffect(effect)
                true
            }
        }
    }

    Box(
        modifier = modifier
            .semantics {
                onClick(label = toggleChromeLabel) {
                    if (latestSnapshotProvider().blocking) {
                        false
                    } else {
                        latestToggleChrome()
                        true
                    }
                }
            }
            .pointerInput(sequencer, doubleClickTimeoutMillis, doubleClickSlopPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val downEvent = currentEvent
                    if (downEvent.changes.count { it.pressed } != 1) return@awaitEachGesture
                    val source = down.type.toPointerSource() ?: return@awaitEachGesture
                    val primaryPressed = when (source) {
                        PointerSource.TOUCH -> true
                        PointerSource.MOUSE -> downEvent.buttons.isPrimaryPressed
                        PointerSource.PEN,
                        PointerSource.ERASER,
                        -> !downEvent.buttons.areAnyPressed || downEvent.buttons.isPrimaryPressed
                    }
                    val button = if (primaryPressed) PRIMARY_BUTTON else SECONDARY_BUTTON
                    val downSnapshot = latestSnapshotProvider()
                    val downPoint = down.position
                    val downResolution = StageInputRouter.resolve(downSnapshot, downPoint, source, button)
                    if (!downResolution.activatable) return@awaitEachGesture
                    val reservation = if (source != PointerSource.TOUCH && downResolution.target is StageInputTarget.Surface) {
                        downResolution.effect?.let { effect ->
                            sequencer.reserveSecond(
                                effect = effect,
                                stagePoint = downPoint,
                                eventTimeMillis = down.uptimeMillis,
                                doubleClickSlopPx = doubleClickSlopPx,
                                geometryToken = downSnapshot.geometryToken,
                            )
                        }
                    } else {
                        null
                    }
                    var reservationHandled = false
                    var restoreReservedSingle = true
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val currentSnapshot = latestSnapshotProvider()
                            if (currentSnapshot.geometryToken != downSnapshot.geometryToken) {
                                restoreReservedSingle = false
                                break
                            }
                            if (event.changes.count { it.pressed } > 1) break
                            val delta = change.position - down.position
                            if (delta.getDistance() > viewConfiguration.touchSlop || change.isConsumed) break
                            val currentResolution = StageInputRouter.resolve(
                                currentSnapshot,
                                change.position,
                                source,
                                button,
                            )
                            if (!sameScope(downResolution.target, currentResolution.target)) break
                            if (change.changedToUpIgnoreConsumed()) {
                                when (val target = downResolution.target) {
                                    is StageInputTarget.Surface -> {
                                        val effect = currentResolution.effect ?: break
                                        if (source == PointerSource.TOUCH) {
                                            latestSurfaceEffect(effect)
                                        } else if (reservation != null) {
                                            sequencer.completeSecond(
                                                reservation = reservation,
                                                effect = effect,
                                                geometryToken = currentSnapshot.geometryToken,
                                            )
                                            reservationHandled = true
                                        } else {
                                            sequencer.activate(
                                                effect = effect,
                                                stagePoint = change.position,
                                                eventTimeMillis = change.uptimeMillis,
                                                doubleClickTimeoutMillis = doubleClickTimeoutMillis,
                                                doubleClickSlopPx = doubleClickSlopPx,
                                                geometryToken = currentSnapshot.geometryToken,
                                                liveGeometryToken = { latestSnapshotProvider().geometryToken },
                                            )
                                        }
                                    }
                                    StageInputTarget.EmptyStage -> latestToggleChrome()
                                    StageInputTarget.Modal,
                                    is StageInputTarget.Bubble,
                                    -> Unit
                                }
                                break
                            } else if (!change.pressed) {
                                break
                            }
                        }
                        if (reservation != null && !reservationHandled) {
                            sequencer.cancelSecond(reservation, restoreReservedSingle)
                            reservationHandled = true
                        }
                    } finally {
                        if (reservation != null && !reservationHandled) {
                            sequencer.cancelSecond(reservation, restorePending = false)
                        }
                    }
                }
            },
    ) {
        CompositionLocalProvider(LocalSemanticStageActivation provides semanticActivation) {
            content(semanticActivation)
        }
    }
}

internal fun Modifier.stageSurfaceSemantics(
    tag: String,
    speaker: SurfaceSpeaker,
    label: String,
    semanticActivation: SemanticStageActivation,
): Modifier = testTag(tag).semantics {
    contentDescription = label
    onClick { semanticActivation.activate(speaker) }
}

internal fun PointerType.toPointerSource(): PointerSource? = when (this) {
    PointerType.Touch -> PointerSource.TOUCH
    PointerType.Mouse -> PointerSource.MOUSE
    PointerType.Stylus -> PointerSource.PEN
    PointerType.Eraser -> PointerSource.ERASER
    else -> null
}

private fun sameScope(first: StageInputTarget, second: StageInputTarget): Boolean = when {
    first is StageInputTarget.Surface && second is StageInputTarget.Surface ->
        first.speaker == second.speaker &&
            (first.hit as? com.cattailsw.nanidroid.SurfaceHitTarget.Collision)?.identifier ==
            (second.hit as? com.cattailsw.nanidroid.SurfaceHitTarget.Collision)?.identifier
    else -> first == second
}

private const val PRIMARY_BUTTON = 0
private const val SECONDARY_BUTTON = 1
