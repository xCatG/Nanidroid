package com.cattailsw.nanidroid.compose.stage

import android.view.ViewConfiguration
import android.os.SystemClock
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
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
    fun activate(token: GhostStageSemanticToken, effect: SurfaceInteractionEffect): Boolean
}

internal val LocalSemanticStageActivation = staticCompositionLocalOf {
    SemanticStageActivation { _, _ -> false }
}

/** The one full-stage pointer owner. Children retain visual and semantic roles only. */
@Composable
internal fun StagePointerInput(
    snapshotProvider: () -> StageInputSnapshot,
    onSurfaceEffect: (SurfaceInteractionEffect) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    monotonicNowMillis: () -> Long = SystemClock::uptimeMillis,
    content: @Composable (SemanticStageActivation) -> Unit,
) {
    if (!enabled) {
        Box(modifier = modifier) {
            CompositionLocalProvider(
                LocalSemanticStageActivation provides SemanticStageActivation { _, _ -> false },
            ) {
                content(SemanticStageActivation { _, _ -> false })
            }
        }
        return
    }
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
    val keyActivationSequence = remember { StageKeyActivationSequence() }
    val currentGeometry = snapshotProvider().geometryToken
    SideEffect {
        sequencer.retainGeometry(currentGeometry)
        keyActivationSequence.retainGeometry(currentGeometry)
    }
    DisposableEffect(sequencer, keyActivationSequence) {
        onDispose {
            sequencer.cancelAll()
            keyActivationSequence.cancel()
        }
    }

    val platformConfiguration = ViewConfiguration.get(LocalContext.current)
    val toggleChromeLabel = stringResource(R.string.toggle_stage_chrome_action)
    val doubleClickTimeoutMillis = ViewConfiguration.getDoubleTapTimeout().toLong()
    val doubleClickSlopPx = platformConfiguration.scaledDoubleTapSlop.toFloat()
    val chromeActivation = remember {
        {
            if (latestSnapshotProvider().blocking) {
                false
            } else {
                latestToggleChrome()
                true
            }
        }
    }
    val semanticActivation = remember {
        SemanticStageActivation { token, proposed ->
            GhostStageSemantics.resolveActivation(
                current = latestSnapshotProvider(),
                token = token,
                proposed = proposed,
            )?.let { effect ->
                latestSurfaceEffect(effect)
                true
            } ?: false
        }
    }

    Box(
        modifier = modifier
            .semantics {
                onClick(label = toggleChromeLabel, action = chromeActivation)
            }
            .onKeyEvent { event ->
                val snapshot = latestSnapshotProvider()
                when (event.type) {
                    KeyEventType.KeyDown -> keyActivationSequence.onKeyDown(event.key, snapshot)
                    KeyEventType.KeyUp -> {
                        when {
                            !event.key.isStageActivationKey() -> false
                            keyActivationSequence.onKeyUp(event.key, snapshot) -> chromeActivation()
                            else -> true
                        }
                    }
                    else -> false
                }
            }
            .focusable()
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
                            if (!size.containsHalfOpen(change.position)) break
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
                                                stagePoint = downPoint,
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
    label: String,
    semantics: GhostSurfaceSemantics,
    genericActionLabel: String,
    collisionActionLabels: List<String>,
    semanticActivation: SemanticStageActivation,
    onSurfaceKeyEvent: (KeyEvent) -> Boolean = { false },
    onSurfaceFocusLost: () -> Unit = {},
): Modifier = testTag(tag).semantics {
    require(collisionActionLabels.size == semantics.collisionActions.size)
    contentDescription = label
    role = Role.Image
    onClick(label = genericActionLabel) {
        semanticActivation.activate(semantics.token, semantics.genericAction.effect)
    }
    customActions = semantics.collisionActions.zip(collisionActionLabels) { action, actionLabel ->
        CustomAccessibilityAction(actionLabel) {
            semanticActivation.activate(semantics.token, action.effect)
        }
    }
}.onKeyEvent(onSurfaceKeyEvent)
    .onFocusChanged { state -> if (!state.isFocused) onSurfaceFocusLost() }
    .focusable()

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

private fun IntSize.containsHalfOpen(point: Offset): Boolean =
    point.x >= 0f && point.x < width && point.y >= 0f && point.y < height

private class StageKeyActivationSequence {
    private val heldKeys = mutableSetOf<Key>()
    private var acceptedGeometryToken: Any? = null

    fun onKeyDown(key: Key, snapshot: StageInputSnapshot): Boolean {
        if (!key.isStageActivationKey()) return false
        if (!heldKeys.add(key)) {
            if (snapshot.blocking || acceptedGeometryToken != snapshot.geometryToken) {
                acceptedGeometryToken = null
            }
            return acceptedGeometryToken != null
        }
        if (heldKeys.size > 1) {
            acceptedGeometryToken = null
            return false
        }
        acceptedGeometryToken = snapshot.geometryToken.takeUnless { snapshot.blocking }
        return acceptedGeometryToken != null
    }

    fun onKeyUp(key: Key, snapshot: StageInputSnapshot): Boolean {
        if (!key.isStageActivationKey()) return false
        if (!heldKeys.remove(key)) {
            acceptedGeometryToken = null
            return false
        }
        val accepted =
            heldKeys.isEmpty() &&
                !snapshot.blocking &&
                acceptedGeometryToken == snapshot.geometryToken
        acceptedGeometryToken = null
        return accepted
    }

    fun retainGeometry(geometryToken: Any) {
        if (acceptedGeometryToken != null && acceptedGeometryToken != geometryToken) {
            acceptedGeometryToken = null
        }
    }

    fun cancel() {
        heldKeys.clear()
        acceptedGeometryToken = null
    }
}

private fun Key.isStageActivationKey(): Boolean = this == Key.Enter || this == Key.DirectionCenter

private const val PRIMARY_BUTTON = 0
private const val SECONDARY_BUTTON = 1
