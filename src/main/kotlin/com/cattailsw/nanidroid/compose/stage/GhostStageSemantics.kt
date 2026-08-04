package com.cattailsw.nanidroid.compose.stage

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.cattailsw.nanidroid.NO_COLLISION
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.PointerEventKind
import com.cattailsw.nanidroid.runtime.dialogue.PointerSource
import com.cattailsw.nanidroid.runtime.dialogue.SurfaceInteractionEffect
import com.cattailsw.nanidroid.runtime.stage.SurfaceKey
import com.cattailsw.nanidroid.runtime.stage.SurfaceTransformPx
import com.cattailsw.nanidroid.runtime.stage.StageInputSnapshot

data class GhostStageSemanticToken(
    val speaker: SurfaceSpeaker,
    val surfaceKey: SurfaceKey,
    val revision: Long,
    val inputAuthority: Any,
    val transform: SurfaceTransformPx,
)

data class GhostStageGenericAction(
    val effect: SurfaceInteractionEffect,
)

data class GhostStageCollisionAction(
    val authoredIdentifier: String,
    val spokenIdentifier: String?,
    val spokenDisambiguationOrdinal: Int?,
    val ordinal: Int,
    val duplicateCount: Int,
    val effect: SurfaceInteractionEffect,
)

data class GhostStageOmissionLogKey(
    val speaker: SurfaceSpeaker,
    val surfaceKey: SurfaceKey,
    val inputAuthority: Any,
    val omittedUnrepresentable: Int,
    val omittedByCap: Int,
)

data class GhostSurfaceSemantics(
    val token: GhostStageSemanticToken,
    val genericAction: GhostStageGenericAction,
    val collisionActions: List<GhostStageCollisionAction>,
    val omittedUnrepresentable: Int,
    val omittedByCap: Int,
    val omissionDiagnostic: String?,
    val omissionLogKey: GhostStageOmissionLogKey?,
)

/** Pure policy for the bounded semantic alternatives to exact authored hit geometry. */
object GhostStageSemantics {
    const val MAX_COLLISION_ACTIONS = 8
    const val MAX_SPOKEN_IDENTIFIER_CHARS = 64
    const val MAX_DIAGNOSTIC_CHARS = 160

    fun build(snapshot: StageSurfaceSnapshot): GhostSurfaceSemantics {
        val surface = snapshot.composedSurface
        val transform = snapshot.semanticsTransform
        val token = GhostStageSemanticToken(
            speaker = snapshot.speaker,
            surfaceKey = surface.surfaceKey,
            revision = surface.revision,
            inputAuthority = surface.inputAuthority,
            transform = transform,
        )
        val collisions = surface.effectiveCollisions
        val duplicateCounts = collisions.groupingBy(SurfaceCollision::identifier).eachCount()
        val ordinals = mutableMapOf<String, Int>()
        val representable = distinguishSanitizedCopies(buildList {
            collisions.forEach { collision ->
                val ordinal = (ordinals[collision.identifier] ?: 0) + 1
                ordinals[collision.identifier] = ordinal
                val point = transform.representativeIntrinsicPoint(collision.shape) ?: return@forEach
                val viewportPoint = transform.stageCenterForIntrinsic(point) ?: return@forEach
                add(
                    GhostStageCollisionAction(
                        authoredIdentifier = collision.identifier,
                        spokenIdentifier = spokenCopy(collision.identifier),
                        spokenDisambiguationOrdinal = null,
                        ordinal = ordinal,
                        duplicateCount = duplicateCounts.getValue(collision.identifier),
                        effect = effect(
                            speaker = snapshot.speaker,
                            intrinsic = point,
                            viewportPosition = viewportPoint,
                            collision = collision,
                        ),
                    ),
                )
            }
        })
        val accepted = representable.take(MAX_COLLISION_ACTIONS)
        val omittedUnrepresentable = collisions.size - representable.size
        val omittedByCap = representable.size - accepted.size
        val diagnostic = omissionDiagnostic(omittedUnrepresentable, omittedByCap)
        val omissionLogKey = diagnostic?.let {
            GhostStageOmissionLogKey(
                speaker = snapshot.speaker,
                surfaceKey = surface.surfaceKey,
                inputAuthority = surface.inputAuthority,
                omittedUnrepresentable = omittedUnrepresentable,
                omittedByCap = omittedByCap,
            )
        }
        val intrinsicCenter = IntOffset(
            surface.canvasSize.width / 2,
            surface.canvasSize.height / 2,
        )
        val rendered = transform.renderedBounds
        val viewportCenter = IntOffset(
            rendered.left + rendered.width / 2,
            rendered.top + rendered.height / 2,
        )

        return GhostSurfaceSemantics(
            token = token,
            genericAction = GhostStageGenericAction(
                effect = SurfaceInteractionEffect(
                    kind = PointerEventKind.CLICK,
                    speaker = snapshot.speaker,
                    intrinsic = intrinsicCenter,
                    button = PRIMARY_BUTTON,
                    source = PointerSource.TOUCH,
                    collisionIdentifier = null,
                    diagnosticCollisionId = NO_COLLISION,
                    viewportPosition = viewportCenter,
                ),
            ),
            collisionActions = accepted,
            omittedUnrepresentable = omittedUnrepresentable,
            omittedByCap = omittedByCap,
            omissionDiagnostic = diagnostic,
            omissionLogKey = omissionLogKey,
        )
    }

    /** Returns the proposed exact effect only while its semantic snapshot remains authoritative. */
    fun resolveActivation(
        current: StageInputSnapshot,
        token: GhostStageSemanticToken,
        proposed: SurfaceInteractionEffect,
    ): SurfaceInteractionEffect? {
        if (current.blocking || proposed.speaker != token.speaker) return null
        if (current.bubbleRegistry.resolve(proposed.viewportPosition.toOffset()) != null) return null
        val surface = current.surfaces.firstOrNull { candidate ->
            candidate.speaker == token.speaker &&
                candidate.composedSurface.surfaceKey == token.surfaceKey &&
                candidate.composedSurface.revision == token.revision &&
                candidate.composedSurface.inputAuthority == token.inputAuthority &&
                candidate.transform == token.transform &&
                !candidate.composedSurface.explicitlyHidden
        } ?: return null
        val authoritative = build(surface)
        val remainsExposed = if (proposed.collisionIdentifier == null) {
            proposed == authoritative.genericAction.effect
        } else {
            authoritative.collisionActions.any { it.effect == proposed }
        }
        return proposed.takeIf { remainsExposed }
    }

    private fun effect(
        speaker: SurfaceSpeaker,
        intrinsic: IntOffset,
        viewportPosition: IntOffset,
        collision: SurfaceCollision,
    ) = SurfaceInteractionEffect(
        kind = PointerEventKind.CLICK,
        speaker = speaker,
        intrinsic = intrinsic,
        button = PRIMARY_BUTTON,
        source = PointerSource.TOUCH,
        collisionIdentifier = collision.identifier,
        diagnosticCollisionId = collision.id,
        viewportPosition = viewportPosition,
    )

    private fun omissionDiagnostic(unrepresentable: Int, capped: Int): String? {
        if (unrepresentable == 0 && capped == 0) return null
        return buildString {
            append("Accessibility collision actions omitted: ")
            append(unrepresentable)
            append(" unrepresentable, ")
            append(capped)
            append(" over the action cap")
        }.take(MAX_DIAGNOSTIC_CHARS)
    }

    private fun spokenCopy(authored: String): String? {
        val copy = StringBuilder()
        var index = 0
        while (index < authored.length) {
            val codePoint = authored.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.isISOControl(codePoint) || codePoint.isBidiControl()) continue
            if (copy.length + Character.charCount(codePoint) > MAX_SPOKEN_IDENTIFIER_CHARS) break
            copy.appendCodePoint(codePoint)
        }
        return copy.toString().trim().ifEmpty { null }
    }

    private fun distinguishSanitizedCopies(
        actions: List<GhostStageCollisionAction>,
    ): List<GhostStageCollisionAction> {
        val ambiguous = actions.groupBy(GhostStageCollisionAction::spokenIdentifier)
            .filterValues { group -> group.map(GhostStageCollisionAction::authoredIdentifier).distinct().size > 1 }
        if (ambiguous.isEmpty()) return actions
        val nextOrdinal = mutableMapOf<String?, Int>()
        return actions.map { action ->
            if (action.spokenIdentifier !in ambiguous) return@map action
            val ordinal = (nextOrdinal[action.spokenIdentifier] ?: 0) + 1
            nextOrdinal[action.spokenIdentifier] = ordinal
            action.copy(
                spokenDisambiguationOrdinal = ordinal,
            )
        }
    }

    private fun IntOffset.toOffset() = Offset(x.toFloat(), y.toFloat())

    private fun Int.isBidiControl(): Boolean =
        this == 0x061C || this == 0x200E || this == 0x200F ||
            this in 0x202A..0x202E || this in 0x2066..0x2069

    private const val PRIMARY_BUTTON = 0
}
