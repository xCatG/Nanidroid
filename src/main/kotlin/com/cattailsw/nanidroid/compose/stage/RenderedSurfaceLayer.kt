package com.cattailsw.nanidroid.compose.stage

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.compose.SurfaceCompositorImage
import com.cattailsw.nanidroid.compose.SurfaceSpeaker

/** One surface node whose image, input, overlay, semantics, and debug state share [snapshot]. */
@Composable
fun RenderedSurfaceLayer(
    snapshot: StageSurfaceSnapshot,
    showCollisionOverlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val semanticActivation = LocalSemanticStageActivation.current
    val semantics = remember(snapshot) { GhostStageSemantics.build(snapshot) }
    val keyActivation = remember { SurfaceSemanticKeyActivationSequence() }
    SideEffect { keyActivation.retain(semantics.token) }
    val semanticLabel = stringResource(
        when (snapshot.speaker) {
            SurfaceSpeaker.SAKURA -> R.string.sakura_character_description
            SurfaceSpeaker.KERO -> R.string.kero_character_description
        },
    )
    val genericActionLabel = stringResource(R.string.stage_surface_activate_action, semanticLabel)
    val collisionActionLabels = semantics.collisionActions.map { action ->
        val spokenIdentifier = action.spokenIdentifier
            ?: stringResource(R.string.stage_collision_unnamed_region)
        val distinguishedIdentifier = action.spokenDisambiguationOrdinal?.let { ordinal ->
            stringResource(
                R.string.stage_collision_disambiguated_identifier,
                spokenIdentifier,
                ordinal,
            )
        } ?: spokenIdentifier
        if (action.duplicateCount > 1) {
            stringResource(
                R.string.stage_collision_activate_repeated_action,
                semanticLabel,
                distinguishedIdentifier,
                action.ordinal,
            )
        } else {
            stringResource(
                R.string.stage_collision_activate_action,
                semanticLabel,
                distinguishedIdentifier,
            )
        }
    }
    val omissionGate = remember { OmissionDiagnosticGate() }
    LaunchedEffect(semantics.omissionLogKey, semantics.omissionDiagnostic) {
        val key = semantics.omissionLogKey
        val diagnostic = semantics.omissionDiagnostic
        if (key != null && diagnostic != null && omissionGate.shouldLog(key)) {
            Log.w(TAG, diagnostic)
        }
    }
    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        SurfaceCompositorImage(
            surface = snapshot.composedSurface,
            transform = snapshot.rendererTransform,
            modifier = Modifier.fillMaxSize(),
        )
        if (showCollisionOverlay) {
            CollisionOverlay(
                collisions = snapshot.composedSurface.effectiveCollisions,
                transform = snapshot.overlayTransform,
                visible = true,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .requiredSizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .fillMaxSize()
                .stageSurfaceSemantics(
                    tag = "surface-${snapshot.speaker.name.lowercase()}",
                    label = semanticLabel,
                    semantics = semantics,
                    genericActionLabel = genericActionLabel,
                    collisionActionLabels = collisionActionLabels,
                    semanticActivation = semanticActivation,
                    onSurfaceKeyEvent = { event ->
                        keyActivation.onKeyEvent(event, semantics.token) {
                            semanticActivation.activate(semantics.token, semantics.genericAction.effect)
                        }
                    },
                    onSurfaceFocusLost = keyActivation::cancel,
                ),
        )
    }
}

private const val TAG = "GhostStageSemantics"

internal class OmissionDiagnosticGate(private val capacity: Int = 32) {
    private val seen = LinkedHashSet<GhostStageOmissionLogKey>()

    init {
        require(capacity > 0)
    }

    fun shouldLog(key: GhostStageOmissionLogKey): Boolean {
        if (!seen.add(key)) return false
        if (seen.size > capacity) seen.remove(seen.first())
        return true
    }
}

private class SurfaceSemanticKeyActivationSequence {
    private val held = mutableSetOf<Key>()
    private var acceptedToken: GhostStageSemanticToken? = null

    fun retain(token: GhostStageSemanticToken) {
        if (acceptedToken != null && acceptedToken != token) cancel()
    }

    fun onKeyEvent(
        event: KeyEvent,
        token: GhostStageSemanticToken,
        activate: () -> Boolean,
    ): Boolean {
        if (!event.key.isActivationKey()) return false
        return when (event.type) {
            KeyEventType.KeyDown -> {
                if (held.add(event.key) && held.size == 1) acceptedToken = token
                if (held.size > 1) acceptedToken = null
                true
            }
            KeyEventType.KeyUp -> {
                val wasHeld = held.remove(event.key)
                val accepted = wasHeld && held.isEmpty() && acceptedToken == token
                acceptedToken = null
                if (accepted) activate() else true
            }
            else -> false
        }
    }

    fun cancel() {
        held.clear()
        acceptedToken = null
    }

    private fun Key.isActivationKey(): Boolean =
        this == Key.Enter || this == Key.NumPadEnter || this == Key.DirectionCenter
}
