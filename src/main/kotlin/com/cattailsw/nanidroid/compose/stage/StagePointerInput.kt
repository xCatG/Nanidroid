package com.cattailsw.nanidroid.compose.stage

import android.view.ViewConfiguration
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
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

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
    content: @Composable (SemanticStageActivation) -> Unit,
) {
    val latestSnapshotProvider by rememberUpdatedState(snapshotProvider)
    val latestSurfaceEffect by rememberUpdatedState(onSurfaceEffect)
    val latestToggleChrome by rememberUpdatedState(onToggleChrome)
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
    val sequencer = remember(scheduler) { PhysicalClickSequencer(scheduler) { latestSurfaceEffect(it) } }
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
            val point = IntOffset(
                bounds.left + bounds.width / 2,
                bounds.top + bounds.height / 2,
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
                    latestToggleChrome()
                    true
                }
            }
            .pointerInput(sequencer, doubleClickTimeoutMillis, doubleClickSlopPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val downEvent = currentEvent
                    if (downEvent.changes.count { it.pressed } != 1) return@awaitEachGesture
                    val source = down.type.toPointerSource() ?: return@awaitEachGesture
                    val primaryPressed = source == PointerSource.TOUCH || downEvent.buttons.isPrimaryPressed
                    val button = if (primaryPressed) PRIMARY_BUTTON else SECONDARY_BUTTON
                    val downSnapshot = latestSnapshotProvider()
                    val downPoint = down.position.rounded()
                    val downResolution = StageInputRouter.resolve(downSnapshot, downPoint, source, button)
                    if (!downResolution.activatable) return@awaitEachGesture

                    var valid = true
                    while (valid) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.count { it.pressed } > 1) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.position - down.position
                        if (delta.getDistance() > viewConfiguration.touchSlop || change.isConsumed) break
                        val currentSnapshot = latestSnapshotProvider()
                        if (currentSnapshot.geometryToken != downSnapshot.geometryToken) break
                        val currentResolution = StageInputRouter.resolve(
                            currentSnapshot,
                            change.position.rounded(),
                            source,
                            button,
                        )
                        if (!sameScope(downResolution.target, currentResolution.target)) break
                        if (change.changedToUpIgnoreConsumed()) {
                            when (val target = downResolution.target) {
                                is StageInputTarget.Surface -> {
                                    val effect = downResolution.effect ?: break
                                    if (source == PointerSource.TOUCH) {
                                        latestSurfaceEffect(effect)
                                    } else {
                                        sequencer.activate(
                                            effect = effect,
                                            stagePoint = downPoint,
                                            eventTimeMillis = change.uptimeMillis,
                                            doubleClickTimeoutMillis = doubleClickTimeoutMillis,
                                            doubleClickSlopPx = doubleClickSlopPx,
                                            geometryToken = downSnapshot.geometryToken,
                                        )
                                    }
                                }
                                StageInputTarget.EmptyStage -> latestToggleChrome()
                                StageInputTarget.Modal,
                                is StageInputTarget.Bubble,
                                -> Unit
                            }
                            valid = false
                        } else if (!change.pressed) {
                            valid = false
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
    semanticActivation: SemanticStageActivation,
): Modifier = testTag(tag).semantics {
    onClick { semanticActivation.activate(speaker) }
}

internal fun PointerType.toPointerSource(): PointerSource? = when (this) {
    PointerType.Touch -> PointerSource.TOUCH
    PointerType.Mouse -> PointerSource.MOUSE
    PointerType.Stylus -> PointerSource.PEN
    PointerType.Eraser -> PointerSource.ERASER
    else -> null
}

private fun Offset.rounded() = IntOffset(x.roundToInt(), y.roundToInt())

private fun sameScope(first: StageInputTarget, second: StageInputTarget): Boolean = when {
    first is StageInputTarget.Surface && second is StageInputTarget.Surface ->
        first.speaker == second.speaker &&
            (first.hit as? com.cattailsw.nanidroid.SurfaceHitTarget.Collision)?.identifier ==
            (second.hit as? com.cattailsw.nanidroid.SurfaceHitTarget.Collision)?.identifier
    else -> first == second
}

private const val PRIMARY_BUTTON = 0
private const val SECONDARY_BUTTON = 1
