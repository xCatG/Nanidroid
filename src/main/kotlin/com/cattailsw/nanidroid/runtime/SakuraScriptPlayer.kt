package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.ShioriRequestIntent
import com.cattailsw.nanidroid.ShioriResponse
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueActionKey
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.InputBoxSpec
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeAnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeChoiceAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeInputAction
import com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptCommandParser
import com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptOccurrence
import com.cattailsw.nanidroid.runtime.dialogue.SakuraScriptTokenizer
import com.cattailsw.nanidroid.runtime.dialogue.tokenizeWithInteractions
import java.util.Collections

internal data class PlayerPayload(
    val script: String,
    val parent: PlayerParent?,
)

internal data class PlayerCursor(
    val payload: PlayerPayload,
    val authoredDialogue: AuthoredDialogueScript,
    val charIndex: Int,
    val adoptedElapsedMillis: Long,
    val scope: Int,
    val speaker: GhostSpeaker,
    val waitMillis: Long,
    val wholeLine: Boolean,
    val quickSession: Boolean,
    val synchronizedSession: Boolean,
    val renderedFrameIndex: Int,
    val dialogueProjection: DialogueProjectionCursor,
)

internal data class DialogueProjectionCursor(
    val scope: Int,
    val speaker: GhostSpeaker,
    val synchronizedSession: Boolean,
    val nextOccurrence: Int,
    val activeAnchor: ActiveAnchorProjection?,
)

internal data class ActiveAnchorProjection(
    val owner: GhostSpeaker,
    val mirroredOwner: GhostSpeaker?,
    val ownerStartIndex: Int,
    val mirroredStartIndex: Int?,
    val occurrenceIndex: Int?,
)

internal data class AuthoredDialogueScript(
    val contents: List<DialogueContent>,
    val markers: List<ActionMarker>,
    val sourceVisits: Int,
)

internal enum class ActionKind { CHOICE, ANCHOR, INPUT }

internal data class ActionMarker(
    val sourceEnd: Int,
    val speaker: GhostSpeaker,
    val kind: ActionKind,
    val value: Any,
)

internal data class PendingInputSeed(
    val spec: InputBoxSpec,
    val timeoutMillis: Long?,
    val speaker: GhostSpeaker,
)

internal data class PlayerState(
    val generation: Long,
    val queue: List<PlayerPayload>,
    val current: PlayerCursor?,
    val presentation: RuntimePresentation,
    val dialogue: RuntimeDialogueSnapshot,
    val passive: Boolean,
    val authoredRequest: RuntimeRequestOrigin.Playback?,
    val playbackToken: Long,
    val nextActionId: Long,
    val talkingFrameIndex: Int,
) {
    companion object {
        fun initial(generation: Long): PlayerState = freeze(
            PlayerState(
                generation = generation,
                queue = emptyList(),
                current = null,
                presentation = RuntimePresentation(
                    sakura = RuntimeSpeakerPresentation("", "0", 0L, false),
                    kero = RuntimeSpeakerPresentation("", "10", 0L, false),
                    talkingAnimationEnabled = false,
                ),
                dialogue = RuntimeDialogueSnapshot(
                    state = DialogueRuntimeState(),
                    choices = emptyList(),
                    anchors = emptyList(),
                    input = null,
                ),
                passive = false,
                authoredRequest = null,
                playbackToken = 0L,
                nextActionId = 1L,
                talkingFrameIndex = 0,
            ),
        )
    }
}

internal sealed interface PlayerParent {
    data class Switch(val operationId: Long) : PlayerParent
    data class Exit(val operationId: Long) : PlayerParent
}

internal sealed interface PlayerCommand {
    data class Enqueue(val script: String, val parent: PlayerParent?) : PlayerCommand
    data class Advance(val token: Long, val elapsedMillis: Long) : PlayerCommand
    data class NativeResponse(val token: RuntimeRequestToken, val response: PlayerResponse) : PlayerCommand
    data class ActivateChoice(val key: DialogueActionKey) : PlayerCommand
    data class ActivateAnchor(val key: DialogueActionKey) : PlayerCommand
    data class SubmitInput(val key: DialogueActionKey, val value: String) : PlayerCommand
    data class DismissInput(val key: DialogueActionKey) : PlayerCommand
    data class InputExpired(val key: DialogueActionKey, val elapsedMillis: Long) : PlayerCommand
    data class Clear(val owner: PlayerParent?) : PlayerCommand
}

internal sealed interface PlayerResponse {
    data class Returned(val response: ShioriResponse) : PlayerResponse
    data object ReplayableFailure : PlayerResponse
    data object FatalFailure : PlayerResponse
    data object StaleGeneration : PlayerResponse
}

internal sealed interface PlayerEffect {
    data class SchedulePlayback(val token: Long, val delayMillis: Long) : PlayerEffect
    data class RequestShiori(
        val origin: RuntimeRequestOrigin,
        val intent: ShioriRequestIntent,
        val fallback: ShioriRequestIntent?,
    ) : PlayerEffect

    data class PresentationCue(
        val target: RuntimeSurfaceIdentity,
        val kind: RuntimeCueKind,
        val animationId: String?,
    ) : PlayerEffect

    data class ParentCompleted(val parent: PlayerParent) : PlayerEffect
    data class Failure(val parent: PlayerParent?, val reason: RuntimeNoticeCode) : PlayerEffect
}

internal data class PlayerWork(
    val authoredSourceVisits: Int = 0,
    val projectedSourceVisits: Int = 0,
    val canonicalOccurrenceVisits: Int = 0,
) {
    operator fun plus(other: PlayerWork): PlayerWork = PlayerWork(
        authoredSourceVisits = authoredSourceVisits + other.authoredSourceVisits,
        projectedSourceVisits = projectedSourceVisits + other.projectedSourceVisits,
        canonicalOccurrenceVisits = canonicalOccurrenceVisits + other.canonicalOccurrenceVisits,
    )
}

internal data class PlayerTransition(
    val state: PlayerState,
    val effects: List<PlayerEffect>,
    val work: PlayerWork = PlayerWork(),
)

internal object SakuraScriptPlayer {
    private const val WAIT_UNIT = 50L
    private const val WAIT_YEN_E = 1_000L

    fun reduce(state: PlayerState, command: PlayerCommand): PlayerTransition = when (command) {
        is PlayerCommand.Enqueue -> enqueue(state, command)
        is PlayerCommand.Advance -> advance(state, command)
        is PlayerCommand.NativeResponse -> nativeResponse(state, command)
        is PlayerCommand.ActivateChoice -> activateChoice(state, command.key)
        is PlayerCommand.ActivateAnchor -> activateAnchor(state, command.key)
        is PlayerCommand.SubmitInput -> submitInput(state, command.key, command.value)
        is PlayerCommand.DismissInput -> cancelInput(state, command.key, "close", false)
        is PlayerCommand.InputExpired -> expireInput(state, command)
        is PlayerCommand.Clear -> clear(state, command.owner)
    }.frozen()

    private fun enqueue(state: PlayerState, command: PlayerCommand.Enqueue): PlayerTransition {
        val queued = state.queue + PlayerPayload(command.script, command.parent)
        if (state.current != null || state.authoredRequest != null || state.dialogue.input != null) {
            return transition(state.copy(queue = queued))
        }
        return schedule(state.copy(queue = queued), 0L)
    }

    private fun advance(state: PlayerState, command: PlayerCommand.Advance): PlayerTransition {
        if (command.token != state.playbackToken || state.authoredRequest != null || state.dialogue.input != null) {
            return transition(state)
        }
        return try {
            var adopted = state
            var work = PlayerWork()
            if (adopted.current == null) {
                if (adopted.queue.isEmpty()) return transition(adopted)
                val adoption = adopt(adopted, command.elapsedMillis)
                adopted = adoption.first
                work += adoption.second
            } else if (adopted.current.charIndex >= adopted.current.payload.script.length) {
                val completed = completeCurrent(adopted, command.elapsedMillis)
                if (completed.state.current != null || completed.state.queue.isEmpty()) return completed
                val adoption = adopt(completed.state, command.elapsedMillis)
                return schedule(
                    adoption.first,
                    WAIT_UNIT,
                    completed.effects,
                    completed.work + adoption.second,
                )
            }
            parseStep(adopted, work)
        } catch (_: Throwable) {
            failPlayback(state, state.current?.payload?.parent ?: state.queue.firstOrNull()?.parent)
        }
    }

    private fun adopt(
        state: PlayerState,
        elapsedMillis: Long,
    ): Pair<PlayerState, PlayerWork> {
        val payload = state.queue.first()
        val authoredDialogue = authoredDialogue(payload.script)
        val markers = authoredDialogue.markers
        val nextDialogue = state.dialogue.copy(
            state = DialogueRuntimeState(
                revision = state.dialogue.state.revision + 1,
                incarnation = state.dialogue.state.incarnation + 1,
                talkId = state.dialogue.state.talkId + 1,
                pendingInput = state.dialogue.state.pendingInput.takeIf {
                    markers.none { marker -> marker.kind == ActionKind.INPUT }
                },
            ),
            choices = emptyList(),
            anchors = emptyList(),
            input = state.dialogue.input.takeIf { markers.none { marker -> marker.kind == ActionKind.INPUT } },
        )
        return state.copy(
            queue = state.queue.drop(1),
            current = PlayerCursor(
                payload = payload,
                authoredDialogue = authoredDialogue,
                charIndex = 0,
                adoptedElapsedMillis = elapsedMillis,
                scope = 0,
                speaker = GhostSpeaker.SAKURA,
                waitMillis = WAIT_UNIT,
                wholeLine = false,
                quickSession = false,
                synchronizedSession = false,
                renderedFrameIndex = state.talkingFrameIndex,
                dialogueProjection = DialogueProjectionCursor(
                    scope = 0,
                    speaker = GhostSpeaker.SAKURA,
                    synchronizedSession = false,
                    nextOccurrence = 0,
                    activeAnchor = null,
                ),
            ),
            presentation = resetTransient(state.presentation),
            dialogue = nextDialogue,
            nextActionId = state.nextActionId + markers.size,
        ) to PlayerWork(authoredSourceVisits = authoredDialogue.sourceVisits)
    }

    private fun parseStep(state: PlayerState, priorWork: PlayerWork = PlayerWork()): PlayerTransition {
        var next = state
        var cursor = requireNotNull(state.current)
        val script = cursor.payload.script
        val startIndex = cursor.charIndex
        var explicitCue: PlayerEffect.PresentationCue? = null
        var scheduledDelay: Long? = null
        var request: PlayerEffect.RequestShiori? = null
        val talkingFrame = cursor.renderedFrameIndex == 0
        var presentation = next.presentation
        val sakuraText = StringBuilder(presentation.sakura.text)
        val keroText = StringBuilder(presentation.kero.text)
        var sakuraBalloonVisible = presentation.sakura.balloonVisible
        var keroBalloonVisible = presentation.kero.balloonVisible
        var projection = cursor.dialogueProjection
        val projectedSegments = next.dialogue.state.contents.associate { content ->
            content.speaker to content.segments.toMutableList()
        }.toMutableMap()
        val projectedText = mutableMapOf<GhostSpeaker, StringBuilder>()
        var projectedChoices = next.dialogue.choices.toMutableList()
        var projectedAnchors = next.dialogue.anchors.toMutableList()
        var projectedInput = next.dialogue.input
        var canonicalOccurrenceVisits = 0
        val markers = cursor.authoredDialogue.markers
        val baseActionId = next.nextActionId - markers.size

        fun flushProjectedText(owner: GhostSpeaker) {
            val builder = projectedText.remove(owner) ?: return
            if (builder.isNotEmpty()) {
                val values = projectedSegments.getOrPut(owner) { mutableListOf() }
                val activeAnchorStart = projection.activeAnchor?.let { anchor ->
                    when (owner) {
                        anchor.owner -> anchor.ownerStartIndex
                        anchor.mirroredOwner -> anchor.mirroredStartIndex
                        else -> null
                    }
                }
                val lastIndex = values.lastIndex
                val lastText = values.lastOrNull() as? DialogueSegment.Text
                if (lastText != null && (activeAnchorStart == null || lastIndex >= activeAnchorStart)) {
                    values[lastIndex] = DialogueSegment.Text(lastText.value + builder)
                } else {
                    values += DialogueSegment.Text(builder.toString())
                }
            }
        }

        fun appendProjectedText(owner: GhostSpeaker, value: CharSequence) {
            if (value.isEmpty()) return
            projectedText.getOrPut(owner) { StringBuilder() }.append(value)
        }

        fun appendProjectedSegment(owner: GhostSpeaker, segment: DialogueSegment) {
            flushProjectedText(owner)
            projectedSegments.getOrPut(owner) { mutableListOf() } += segment
        }

        fun mirrorOf(owner: GhostSpeaker): GhostSpeaker = when (owner) {
            GhostSpeaker.SAKURA -> GhostSpeaker.KERO
            GhostSpeaker.KERO -> GhostSpeaker.SAKURA
        }

        fun projectText(value: CharSequence) {
            val anchor = projection.activeAnchor
            if (anchor != null) {
                appendProjectedText(anchor.owner, value)
                anchor.mirroredOwner?.let { appendProjectedText(it, value) }
                return
            }
            if (projection.scope >= 2) return
            appendProjectedText(projection.speaker, value)
            if (projection.synchronizedSession) {
                appendProjectedText(mirrorOf(projection.speaker), value)
            }
        }

        fun projectSegment(segment: DialogueSegment) {
            val anchor = projection.activeAnchor
            if (anchor != null) {
                if (segment == DialogueSegment.NewLine) projectText("\n")
                return
            }
            if (projection.scope >= 2) return
            appendProjectedSegment(projection.speaker, segment)
            if (!projection.synchronizedSession) return
            val other = mirrorOf(projection.speaker)
            when (segment) {
                DialogueSegment.NewLine -> appendProjectedSegment(other, DialogueSegment.NewLine)
                is DialogueSegment.Choice -> appendProjectedText(other, segment.action.visibleLabel())
                is DialogueSegment.Anchor -> appendProjectedText(other, segment.action.visibleLabel())
                else -> Unit
            }
        }

        fun markerOwner(key: DialogueActionKey): GhostSpeaker? {
            val index = key.actionId - baseActionId
            if (index < 0L || index > markers.lastIndex.toLong()) return null
            return markers[index.toInt()].speaker
        }

        fun retireActions(owner: GhostSpeaker, speakerChange: Boolean) {
            projectedAnchors.removeAll { markerOwner(it.key) == owner }
            if (!speakerChange) {
                projectedChoices.removeAll { markerOwner(it.key) == owner }
                if (projectedInput?.pending?.owner == owner) projectedInput = null
            }
        }

        fun projectClear(speakerChange: Boolean) {
            if (projection.activeAnchor != null || projection.scope >= 2) return
            val segment = if (speakerChange) DialogueSegment.SpeakerChangeClear else DialogueSegment.Clear
            appendProjectedSegment(projection.speaker, segment)
            retireActions(projection.speaker, speakerChange)
        }

        fun selectProjectionSpeaker(owner: GhostSpeaker, clearIfCurrent: Boolean = false) {
            if (projection.activeAnchor != null) return
            if (projection.speaker == owner) {
                if (clearIfCurrent) projectClear(speakerChange = true)
                return
            }
            projection = projection.copy(speaker = owner)
            projectClear(speakerChange = true)
        }

        fun revealOccurrence(kind: ActionKind, sourceEnd: Int): ActionMarker? {
            if (projection.activeAnchor != null || projection.scope >= 2) return null
            val marker = markers.getOrNull(projection.nextOccurrence)
                ?.takeIf { it.kind == kind && it.sourceEnd == sourceEnd }
                ?: return null
            projection = projection.copy(nextOccurrence = projection.nextOccurrence + 1)
            canonicalOccurrenceVisits++
            return marker
        }

        fun openAnchor() {
            if (projection.activeAnchor != null || projection.scope >= 2) return
            GhostSpeaker.entries.forEach(::flushProjectedText)
            val owner = projection.speaker
            val mirror = mirrorOf(owner).takeIf { projection.synchronizedSession }
            val markerIndex = projection.nextOccurrence.takeIf { index ->
                markers.getOrNull(index)?.kind == ActionKind.ANCHOR
            }
            projection = projection.copy(
                activeAnchor = ActiveAnchorProjection(
                    owner = owner,
                    mirroredOwner = mirror,
                    ownerStartIndex = projectedSegments[owner].orEmpty().size,
                    mirroredStartIndex = mirror?.let { projectedSegments[it].orEmpty().size },
                    occurrenceIndex = markerIndex,
                ),
            )
        }

        fun closeAnchor(sourceEnd: Int) {
            val active = projection.activeAnchor ?: return
            GhostSpeaker.entries.forEach(::flushProjectedText)
            projectedSegments.getOrPut(active.owner) { mutableListOf() }.let { values ->
                while (values.size > active.ownerStartIndex) values.removeAt(values.lastIndex)
            }
            active.mirroredOwner?.let { owner ->
                val start = requireNotNull(active.mirroredStartIndex)
                projectedSegments.getOrPut(owner) { mutableListOf() }.let { values ->
                    while (values.size > start) values.removeAt(values.lastIndex)
                }
            }
            val marker = active.occurrenceIndex?.let(markers::getOrNull)
                ?.takeIf { it.kind == ActionKind.ANCHOR && it.sourceEnd == sourceEnd }
            if (marker != null) {
                val action = marker.value as AnchorAction
                appendProjectedSegment(active.owner, DialogueSegment.Anchor(action))
                active.mirroredOwner?.let { appendProjectedText(it, action.visibleLabel()) }
                val key = DialogueActionKey(next.generation, next.dialogue.state.incarnation, baseActionId + active.occurrenceIndex)
                projectedAnchors += RuntimeAnchorAction(key, action)
                projection = projection.copy(nextOccurrence = projection.nextOccurrence + 1)
                canonicalOccurrenceVisits++
            }
            projection = projection.copy(activeAnchor = null)
        }

        fun appendCharacter(activeCursor: PlayerCursor, value: Char) {
            val speakers = if (activeCursor.synchronizedSession) GhostSpeaker.entries else listOf(activeCursor.speaker)
            speakers.forEach { speaker ->
                when (speaker) {
                    GhostSpeaker.SAKURA -> {
                        sakuraText.append(value)
                        sakuraBalloonVisible = true
                    }
                    GhostSpeaker.KERO -> {
                        keroText.append(value)
                        keroBalloonVisible = true
                    }
                }
            }
        }

        fun appendText(activeCursor: PlayerCursor, value: CharSequence) {
            if (value.isEmpty()) return
            val speakers = if (activeCursor.synchronizedSession) GhostSpeaker.entries else listOf(activeCursor.speaker)
            speakers.forEach { speaker ->
                when (speaker) {
                    GhostSpeaker.SAKURA -> {
                        sakuraText.append(value)
                        sakuraBalloonVisible = true
                    }
                    GhostSpeaker.KERO -> {
                        keroText.append(value)
                        keroBalloonVisible = true
                    }
                }
            }
        }

        fun clearText(speaker: GhostSpeaker) {
            when (speaker) {
                GhostSpeaker.SAKURA -> {
                    sakuraText.setLength(0)
                    sakuraBalloonVisible = false
                }
                GhostSpeaker.KERO -> {
                    keroText.setLength(0)
                    keroBalloonVisible = false
                }
            }
        }

        fun setBalloon(speaker: GhostSpeaker, visible: Boolean) {
            when (speaker) {
                GhostSpeaker.SAKURA -> sakuraBalloonVisible = visible || sakuraText.isNotEmpty()
                GhostSpeaker.KERO -> keroBalloonVisible = visible || keroText.isNotEmpty()
            }
        }

        while (cursor.charIndex < script.length && scheduledDelay == null && request == null) {
            val character = script[cursor.charIndex]
            cursor = cursor.copy(charIndex = cursor.charIndex + 1, waitMillis = WAIT_UNIT)
            if (character != '\\') {
                if (cursor.scope < 2) appendCharacter(cursor, character)
                projectText(character.toString())
                if (!cursor.wholeLine) scheduledDelay = WAIT_UNIT
                continue
            }
            if (cursor.charIndex >= script.length) {
                continue
            }
            val command = script[cursor.charIndex]
            cursor = cursor.copy(charIndex = cursor.charIndex + 1)
            val scope = cursor.scope
            when (command) {
                '\\' -> Unit
                '0', 'h' -> {
                    val previous = cursor.speaker
                    cursor = cursor.copy(scope = 0, speaker = GhostSpeaker.SAKURA)
                    if (projection.activeAnchor == null) {
                        projection = projection.copy(scope = 0)
                        selectProjectionSpeaker(GhostSpeaker.SAKURA)
                    }
                    if (previous == GhostSpeaker.KERO) {
                        clearText(GhostSpeaker.SAKURA)
                    }
                }
                '1', 'u' -> {
                    cursor = cursor.copy(scope = 1, speaker = GhostSpeaker.KERO)
                    if (projection.activeAnchor == null) {
                        projection = projection.copy(scope = 1)
                        selectProjectionSpeaker(GhostSpeaker.KERO, clearIfCurrent = true)
                    }
                    clearText(GhostSpeaker.KERO)
                }
                'p' -> {
                    val parsed = SakuraScriptCommandParser.parseScope(script, cursor.charIndex)
                    if (parsed != null) {
                        val oldSpeaker = cursor.speaker
                        cursor = cursor.copy(
                            charIndex = parsed.second,
                            scope = parsed.first,
                            speaker = when (parsed.first) {
                                0 -> GhostSpeaker.SAKURA
                                1 -> GhostSpeaker.KERO
                                else -> oldSpeaker
                            },
                        )
                        if (projection.activeAnchor == null) {
                            projection = projection.copy(scope = parsed.first)
                            when (parsed.first) {
                                0 -> selectProjectionSpeaker(GhostSpeaker.SAKURA)
                                1 -> selectProjectionSpeaker(GhostSpeaker.KERO)
                            }
                        }
                        if (parsed.first == 0 && oldSpeaker == GhostSpeaker.KERO) {
                            clearText(GhostSpeaker.SAKURA)
                        } else if (parsed.first == 1 && oldSpeaker == GhostSpeaker.SAKURA) {
                            clearText(GhostSpeaker.KERO)
                        }
                    } else if (script.getOrNull(cursor.charIndex) == '[') {
                        cursor = cursor.copy(charIndex = resumeAfterMalformed(script, cursor.charIndex))
                    }
                }
                's' -> {
                    val parsed = parseId(script, cursor.charIndex)
                    if (parsed != null) {
                        require(parsed.first.isNotEmpty())
                        cursor = cursor.copy(charIndex = parsed.second)
                        if (scope < 2) {
                            presentation = changeSurface(presentation, cursor.speaker, parsed.first)
                            val origin = RuntimeRequestOrigin.Playback(next.playbackToken + 1)
                            request = PlayerEffect.RequestShiori(
                                origin,
                                ShioriRequestIntent.event(
                                    "OnSurfaceChange",
                                    listOf(presentation.sakura.surfaceId, presentation.kero.surfaceId),
                                ),
                                null,
                            )
                            next = next.copy(playbackToken = origin.playbackToken, authoredRequest = origin)
                        } else scheduledDelay = WAIT_UNIT
                    }
                }
                'i' -> {
                    val parsed = parseBracketId(script, cursor.charIndex)
                    if (parsed != null) {
                        require(parsed.first.isNotEmpty())
                        cursor = cursor.copy(charIndex = parsed.second)
                        if (scope < 2) {
                            explicitCue = PlayerEffect.PresentationCue(
                                presentation.surfaceIdentity(next.generation, cursor.speaker),
                                RuntimeCueKind.ONE_SHOT,
                                parsed.first,
                            )
                            presentation = presentation.copy(talkingAnimationEnabled = false)
                        }
                        scheduledDelay = WAIT_UNIT
                    }
                }
                'b' -> {
                    val parsed = parseId(script, cursor.charIndex)
                    if (parsed != null) {
                        cursor = cursor.copy(charIndex = parsed.second)
                        if (scope < 2) setBalloon(cursor.speaker, parsed.first != "-1")
                        scheduledDelay = WAIT_UNIT
                    }
                }
                'e' -> {
                    cursor = cursor.copy(charIndex = script.length, waitMillis = WAIT_YEN_E)
                    scheduledDelay = WAIT_YEN_E
                }
                'n' -> {
                    val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                    if (bracket != null) cursor = cursor.copy(charIndex = bracket.nextIndex)
                    if (scope < 2) appendText(cursor, "\n")
                    projectSegment(DialogueSegment.NewLine)
                    scheduledDelay = WAIT_UNIT
                }
                'c' -> if (scope < 2) {
                    clearText(cursor.speaker)
                    projectClear(speakerChange = false)
                }
                '_' -> {
                    if (cursor.charIndex >= script.length) continue
                    val underscore = script[cursor.charIndex]
                    cursor = cursor.copy(charIndex = cursor.charIndex + 1)
                    when (underscore) {
                        's' -> {
                            cursor = cursor.copy(synchronizedSession = !cursor.synchronizedSession)
                            if (projection.activeAnchor == null) {
                                projection = projection.copy(
                                    synchronizedSession = !projection.synchronizedSession,
                                )
                            }
                        }
                        'q' -> cursor = cursor.copy(
                            wholeLine = !cursor.wholeLine,
                            quickSession = !cursor.quickSession,
                        )
                        'w' -> {
                            val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                            if (bracket != null) {
                                cursor = cursor.copy(charIndex = bracket.nextIndex)
                                bracket.value.toLongOrNull()?.let {
                                    cursor = cursor.copy(waitMillis = it)
                                    projectSegment(DialogueSegment.Wait(it))
                                    scheduledDelay = it
                                }
                            }
                        }
                        'b' -> {
                            val parsed = parseId(script, cursor.charIndex)
                            if (parsed != null) {
                                cursor = cursor.copy(charIndex = parsed.second)
                                if (scope < 2) setBalloon(cursor.speaker, parsed.first != "-1")
                                scheduledDelay = WAIT_UNIT
                            }
                        }
                        'a' -> {
                            val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                            if (bracket != null) {
                                // Only consume the opening command. Its label is ordinary visible
                                // playback text; the later payload-less \_a consumes the close.
                                cursor = cursor.copy(charIndex = bracket.nextIndex)
                                openAnchor()
                            } else {
                                closeAnchor(cursor.charIndex)
                            }
                        }
                        else -> {
                            val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                            if (bracket != null) cursor = cursor.copy(charIndex = bracket.nextIndex)
                            else if (script.getOrNull(cursor.charIndex) == '[') {
                                cursor = cursor.copy(charIndex = resumeAfterMalformed(script, cursor.charIndex))
                            }
                        }
                    }
                }
                '!' -> {
                    val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                    if (bracket != null) {
                        cursor = cursor.copy(charIndex = bracket.nextIndex)
                        val args = SakuraScriptCommandParser.splitArguments(bracket.value)
                        if (args.size == 2 && args[1] == "passivemode" && args[0] in setOf("enter", "leave")) {
                            next = next.copy(passive = args[0] == "enter")
                            projectSegment(DialogueSegment.PassiveMode(args[0] == "enter"))
                        } else if (
                            scope < 2 && args.firstOrNull() == "open" &&
                            args.getOrNull(1) in setOf("inputbox", "passwordinput")
                        ) {
                            revealOccurrence(ActionKind.INPUT, cursor.charIndex)?.let { marker ->
                                val pending = marker.value as PendingInputSeed
                                projectSegment(DialogueSegment.InputBox(pending.spec))
                                if (projectedInput == null) {
                                    val markerIndex = projection.nextOccurrence - 1
                                    projectedInput = RuntimeInputAction(
                                        DialogueActionKey(
                                            next.generation,
                                            next.dialogue.state.incarnation,
                                            baseActionId + markerIndex,
                                        ),
                                        PendingInputState(
                                            generation = baseActionId + markerIndex,
                                            spec = pending.spec,
                                            deadlineElapsedMillis = inputDeadline(
                                                cursor.adoptedElapsedMillis,
                                                pending.timeoutMillis,
                                            ),
                                            owner = pending.speaker,
                                        ),
                                    )
                                }
                            }
                            scheduledDelay = WAIT_UNIT
                        }
                    } else if (script.getOrNull(cursor.charIndex) == '[') {
                        cursor = cursor.copy(charIndex = resumeAfterMalformed(script, cursor.charIndex))
                    }
                }
                'q' -> {
                    val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                    if (bracket != null) {
                        cursor = cursor.copy(charIndex = bracket.nextIndex)
                        val args = SakuraScriptCommandParser.splitArguments(bracket.value)
                        if (scope < 2 && args.size >= 2) {
                            appendText(cursor, args.first())
                            revealOccurrence(ActionKind.CHOICE, cursor.charIndex)?.let { marker ->
                                val action = marker.value as DialogueAction
                                projectSegment(DialogueSegment.Choice(action))
                                val markerIndex = projection.nextOccurrence - 1
                                projectedChoices += RuntimeChoiceAction(
                                    DialogueActionKey(
                                        next.generation,
                                        next.dialogue.state.incarnation,
                                        baseActionId + markerIndex,
                                    ),
                                    action,
                                )
                            }
                            cursor = cursor.copy(wholeLine = true)
                        }
                    } else if (script.getOrNull(cursor.charIndex) == '[') {
                        cursor = cursor.copy(charIndex = resumeAfterMalformed(script, cursor.charIndex))
                    }
                }
                'w' -> {
                    val digit = script.getOrNull(cursor.charIndex)?.digitToIntOrNull()
                    if (digit != null) {
                        cursor = cursor.copy(charIndex = cursor.charIndex + 1, waitMillis = digit * WAIT_UNIT)
                        projectSegment(DialogueSegment.Wait(cursor.waitMillis))
                        scheduledDelay = cursor.waitMillis
                    }
                }
                'j' -> {
                    val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                    if (bracket != null) {
                        cursor = cursor.copy(charIndex = bracket.nextIndex)
                        val uri = SakuraScriptCommandParser.splitArguments(bracket.value).singleOrNull()
                            ?: bracket.value
                        if (uri.startsWith("http://") || uri.startsWith("https://")) {
                            projectSegment(DialogueSegment.ExternalUrl(uri, uri))
                        }
                    } else if (script.getOrNull(cursor.charIndex) == '[') {
                        cursor = cursor.copy(charIndex = resumeAfterMalformed(script, cursor.charIndex))
                    }
                }
                else -> {
                    val bracket = SakuraScriptCommandParser.readBracket(script, cursor.charIndex)
                    if (bracket != null) cursor = cursor.copy(charIndex = bracket.nextIndex)
                    else if (script.getOrNull(cursor.charIndex) == '[') {
                        cursor = cursor.copy(charIndex = resumeAfterMalformed(script, cursor.charIndex))
                    } else if (command in setOf('s', 'b') && script.getOrNull(cursor.charIndex)?.isDigit() == true) {
                        cursor = cursor.copy(charIndex = cursor.charIndex + 1)
                    }
                }
            }
        }

        GhostSpeaker.entries.forEach(::flushProjectedText)
        val nextTalkingFrameIndex = (cursor.renderedFrameIndex + 1) % 10
        cursor = cursor.copy(
            renderedFrameIndex = nextTalkingFrameIndex,
            dialogueProjection = projection,
        )
        presentation = presentation.copy(
            sakura = presentation.sakura.copy(
                text = sakuraText.toString(),
                balloonVisible = sakuraBalloonVisible,
            ),
            kero = presentation.kero.copy(
                text = keroText.toString(),
                balloonVisible = keroBalloonVisible,
            ),
            talkingAnimationEnabled = talkingFrame,
        )
        next = next.copy(
            current = cursor,
            talkingFrameIndex = nextTalkingFrameIndex,
            presentation = presentation,
            dialogue = next.dialogue.copy(
                state = next.dialogue.state.copy(
                    revision = next.dialogue.state.revision + 1,
                    contents = projectedSegments.map { (owner, values) ->
                        DialogueContent(owner, values.toList())
                    },
                    pendingChoices = projectedChoices.map(RuntimeChoiceAction::action),
                    pendingInput = projectedInput?.pending,
                ),
                choices = projectedChoices,
                anchors = projectedAnchors,
                input = projectedInput,
            ),
        )
        val work = priorWork + PlayerWork(
            projectedSourceVisits = cursor.charIndex - startIndex,
            canonicalOccurrenceVisits = canonicalOccurrenceVisits,
        )
        val effects = mutableListOf<PlayerEffect>()
        explicitCue?.let(effects::add)
        if (talkingFrame) {
            GhostSpeaker.entries.filter { speaker ->
                next.presentation.speaker(speaker).balloonVisible && explicitCue?.target?.speaker != speaker
            }.forEach { speaker ->
                effects += PlayerEffect.PresentationCue(
                    next.presentation.surfaceIdentity(next.generation, speaker),
                    RuntimeCueKind.TALKING,
                    null,
                )
            }
        }
        request?.let(effects::add)
        if (request == null && next.dialogue.input == null) {
            val delay = scheduledDelay ?: WAIT_UNIT
            val scheduled = schedule(next, delay, effects, work)
            return scheduled
        }
        return transition(next, effects, work)
    }

    private fun nativeResponse(state: PlayerState, command: PlayerCommand.NativeResponse): PlayerTransition {
        val pending = state.authoredRequest ?: return transition(state)
        if (
            command.token.generation != state.generation ||
            command.token.origin != pending ||
            command.token.parentOperationId != state.current?.payload?.parent?.operationId()
        ) return transition(state)
        return when (val response = command.response) {
            PlayerResponse.StaleGeneration -> transition(state)
            PlayerResponse.FatalFailure -> poisonPlayback(
                state,
                state.current?.payload?.parent,
            )
            PlayerResponse.ReplayableFailure -> schedule(
                state.copy(authoredRequest = null),
                0L,
                listOf(PlayerEffect.Failure(null, RuntimeNoticeCode.REQUEST_FAILED)),
            )
            is PlayerResponse.Returned -> {
                val value = response.response.takeIf { it.getStatusCode() == 200 }
                    ?.getKey("Value")
                    ?.takeIf(String::isNotEmpty)
                val resumed = state.copy(
                    queue = if (value == null) state.queue else state.queue + PlayerPayload(
                        value,
                        state.current?.payload?.parent,
                    ),
                    authoredRequest = null,
                )
                schedule(resumed, 0L)
            }
        }
    }

    private fun activateChoice(state: PlayerState, key: DialogueActionKey): PlayerTransition {
        val selected = state.dialogue.choices.firstOrNull { it.key == key } ?: return transition(state)
        val cleared = state.copy(
            dialogue = state.dialogue.copy(
                state = state.dialogue.state.copy(
                    revision = state.dialogue.state.revision + 1,
                    pendingChoices = emptyList(),
                ),
                choices = emptyList(),
            ),
        )
        return when (val action = selected.action) {
            is DialogueAction.Normal -> transition(
                cleared,
                listOf(
                    PlayerEffect.RequestShiori(
                        RuntimeRequestOrigin.Dialogue(key),
                        ShioriRequestIntent.event(
                            "OnChoiceSelectEx",
                            listOf(action.label, action.id) + action.extraReferences,
                        ),
                        ShioriRequestIntent.event("OnChoiceSelect", listOf(action.id)),
                    ),
                ),
            )
            is DialogueAction.DirectEvent -> transition(
                cleared,
                listOf(
                    PlayerEffect.RequestShiori(
                        RuntimeRequestOrigin.Dialogue(key),
                        ShioriRequestIntent.event(action.eventId, action.references),
                        null,
                    ),
                ),
            )
            is DialogueAction.Script -> {
                val queued = cleared.copy(queue = cleared.queue + PlayerPayload(action.sakuraScript, null))
                if (queued.current == null) schedule(queued, 0L) else transition(queued)
            }
        }
    }

    private fun activateAnchor(state: PlayerState, key: DialogueActionKey): PlayerTransition {
        val selected = state.dialogue.anchors.firstOrNull { it.key == key } ?: return transition(state)
        val (intent, fallback) = when (val action = selected.action) {
            is AnchorAction.Normal -> ShioriRequestIntent.event(
                "OnAnchorSelectEx",
                listOf(action.label, action.id) + action.extraReferences,
            ) to ShioriRequestIntent.event("OnAnchorSelect", listOf(action.id))
            is AnchorAction.DirectEvent -> ShioriRequestIntent.event(action.eventId, action.references) to null
        }
        return transition(
            state,
            listOf(PlayerEffect.RequestShiori(RuntimeRequestOrigin.Dialogue(key), intent, fallback)),
        )
    }

    private fun submitInput(state: PlayerState, key: DialogueActionKey, value: String): PlayerTransition {
        val input = state.dialogue.input?.takeIf { it.key == key } ?: return transition(state)
        val references = when (val dispatch = input.pending.spec.dispatch) {
            is InputDispatch.Normal -> listOf(dispatch.id, value, input.pending.spec.supplement) +
                input.pending.spec.extraReferences
            is InputDispatch.DirectEvent -> listOf(value, input.pending.spec.supplement) +
                input.pending.spec.extraReferences
        }
        val event = when (val dispatch = input.pending.spec.dispatch) {
            is InputDispatch.Normal -> "OnUserInput"
            is InputDispatch.DirectEvent -> dispatch.eventId
        }
        return claimedInputTransition(
            state,
            input,
            PlayerEffect.RequestShiori(RuntimeRequestOrigin.Dialogue(key), ShioriRequestIntent.event(event, references), null),
        )
    }

    private fun expireInput(state: PlayerState, command: PlayerCommand.InputExpired): PlayerTransition {
        val input = state.dialogue.input?.takeIf { it.key == command.key } ?: return transition(state)
        if (
            input.pending.deadlineElapsedMillis == Long.MAX_VALUE ||
            command.elapsedMillis < input.pending.deadlineElapsedMillis
        ) return transition(state)
        return cancelInput(state, command.key, "timeout", true)
    }

    private fun cancelInput(
        state: PlayerState,
        key: DialogueActionKey,
        reason: String,
        withFallback: Boolean,
    ): PlayerTransition {
        val input = state.dialogue.input?.takeIf { it.key == key } ?: return transition(state)
        val id = when (val dispatch = input.pending.spec.dispatch) {
            is InputDispatch.Normal -> dispatch.id
            is InputDispatch.DirectEvent -> dispatch.eventId
        }
        val references = listOf(id, reason, input.pending.spec.supplement) + input.pending.spec.extraReferences
        return claimedInputTransition(
            state,
            input,
            PlayerEffect.RequestShiori(
                RuntimeRequestOrigin.Dialogue(key),
                ShioriRequestIntent.event("OnUserInputCancel", references),
                ShioriRequestIntent.event("OnUserInput", references).takeIf { withFallback },
            ),
        )
    }

    private fun claimedInputTransition(
        state: PlayerState,
        input: RuntimeInputAction,
        request: PlayerEffect.RequestShiori,
    ): PlayerTransition {
        val cleared = state.copy(
            dialogue = state.dialogue.copy(
                state = state.dialogue.state.copy(
                    revision = state.dialogue.state.revision + 1,
                    pendingInput = null,
                ),
                input = null,
            ),
        )
        val effects = mutableListOf<PlayerEffect>(request)
        return if (cleared.current != null) schedule(cleared, 0L, effects) else transition(cleared, effects)
    }

    private fun clear(state: PlayerState, owner: PlayerParent?): PlayerTransition {
        if (owner == null && state.current?.payload?.parent != null) return transition(state)
        val clearsCurrent = state.current?.payload?.parent == owner && state.current != null
        val clearsDormantOrdinaryState = owner == null && state.current == null && (
            state.dialogue.choices.isNotEmpty() ||
                state.dialogue.anchors.isNotEmpty() ||
                state.dialogue.input != null ||
                state.passive ||
                state.authoredRequest != null
            )
        val remainingQueue = if (owner == null) {
            state.queue.dropWhile { it.parent == null }
        } else {
            state.queue.filterNot { it.parent == owner }
        }
        if (!clearsCurrent && !clearsDormantOrdinaryState) {
            return if (remainingQueue.size == state.queue.size) transition(state)
            else transition(state.copy(queue = remainingQueue))
        }
        val cleared = state.copy(
            queue = remainingQueue,
            current = null,
            dialogue = emptyDialogue(state.dialogue),
            passive = false,
            authoredRequest = null,
            playbackToken = state.playbackToken + 1,
            presentation = resetTransient(state.presentation),
        )
        return if (remainingQueue.isEmpty()) transition(cleared) else schedule(cleared, 0L)
    }

    private fun completeCurrent(state: PlayerState, elapsedMillis: Long): PlayerTransition {
        val parent = state.current?.payload?.parent
        val hasQueuedTalk = state.queue.isNotEmpty()
        val talkingFrameIndex = if (hasQueuedTalk) {
            state.current?.renderedFrameIndex ?: state.talkingFrameIndex
        } else {
            ((state.current?.renderedFrameIndex ?: state.talkingFrameIndex) + 1) % 10
        }
        val next = state.copy(
            current = null,
            authoredRequest = null,
            talkingFrameIndex = talkingFrameIndex,
            presentation = resetTransient(state.presentation),
        )
        val stillOwned = next.queue.any { it.parent == parent }
        val effects = if (parent != null && !stillOwned) listOf(PlayerEffect.ParentCompleted(parent)) else emptyList()
        return if (next.queue.isNotEmpty()) {
            val adoption = adopt(next, elapsedMillis)
            schedule(adoption.first, WAIT_UNIT, effects, adoption.second)
        } else transition(next, effects)
    }

    private fun failPlayback(
        state: PlayerState,
        parent: PlayerParent?,
        reason: RuntimeNoticeCode = RuntimeNoticeCode.PLAYER_FAILED,
    ): PlayerTransition {
        val remainingQueue = if (parent == null) {
            state.queue.dropWhile { it.parent == null }
        } else {
            state.queue.filterNot { it.parent == parent }
        }
        val failed = state.copy(
            queue = remainingQueue,
            current = null,
            authoredRequest = null,
            dialogue = emptyDialogue(state.dialogue),
            passive = false,
            playbackToken = state.playbackToken + 1,
            presentation = resetTransient(state.presentation),
        )
        val effects = listOf(PlayerEffect.Failure(parent, reason))
        return if (remainingQueue.isEmpty()) transition(failed, effects) else schedule(failed, 0L, effects)
    }

    private fun poisonPlayback(state: PlayerState, parent: PlayerParent?): PlayerTransition = transition(
        state.copy(
            queue = emptyList(),
            current = null,
            authoredRequest = null,
            dialogue = emptyDialogue(state.dialogue),
            passive = false,
            playbackToken = state.playbackToken + 1,
            presentation = resetTransient(state.presentation),
        ),
        listOf(PlayerEffect.Failure(parent, RuntimeNoticeCode.RUNTIME_POISONED)),
    )

    private fun authoredDialogue(script: String): AuthoredDialogueScript {
        val tokenization = SakuraScriptTokenizer.tokenizeWithInteractions(script)
        return AuthoredDialogueScript(
            contents = tokenization.contents,
            markers = tokenization.occurrences.map { occurrence ->
                when (occurrence) {
                    is SakuraScriptOccurrence.Choice -> ActionMarker(
                        occurrence.sourceEnd,
                        occurrence.speaker,
                        ActionKind.CHOICE,
                        occurrence.action,
                    )
                    is SakuraScriptOccurrence.Anchor -> ActionMarker(
                        occurrence.sourceEnd,
                        occurrence.speaker,
                        ActionKind.ANCHOR,
                        occurrence.action,
                    )
                    is SakuraScriptOccurrence.Input -> ActionMarker(
                        occurrence.sourceEnd,
                        occurrence.speaker,
                        ActionKind.INPUT,
                        PendingInputSeed(occurrence.spec, occurrence.spec.timeoutMillis, occurrence.speaker),
                    )
                }
            },
            sourceVisits = tokenization.sourceVisits,
        )
    }

    private fun changeSurface(
        presentation: RuntimePresentation,
        speaker: GhostSpeaker,
        surfaceId: String,
    ): RuntimePresentation {
        val current = presentation.speaker(speaker)
        return presentation.withSpeaker(
            speaker,
            current.copy(
                surfaceId = surfaceId,
                surfaceEpoch = current.surfaceEpoch + if (surfaceId == current.surfaceId) 0 else 1,
            ),
        )
    }

    private fun RuntimePresentation.speaker(speaker: GhostSpeaker): RuntimeSpeakerPresentation = when (speaker) {
        GhostSpeaker.SAKURA -> sakura
        GhostSpeaker.KERO -> kero
    }

    private fun RuntimePresentation.surfaceIdentity(
        generation: Long,
        speaker: GhostSpeaker,
    ): RuntimeSurfaceIdentity = speaker(speaker).let { current ->
        RuntimeSurfaceIdentity(generation, speaker, current.surfaceId, current.surfaceEpoch)
    }

    private fun RuntimePresentation.withSpeaker(
        speaker: GhostSpeaker,
        value: RuntimeSpeakerPresentation,
    ): RuntimePresentation = when (speaker) {
        GhostSpeaker.SAKURA -> copy(sakura = value)
        GhostSpeaker.KERO -> copy(kero = value)
    }

    private fun resetTransient(presentation: RuntimePresentation): RuntimePresentation = presentation.copy(
        sakura = presentation.sakura.copy(text = "", balloonVisible = false),
        kero = presentation.kero.copy(text = "", balloonVisible = false),
        talkingAnimationEnabled = false,
    )

    private fun emptyDialogue(dialogue: RuntimeDialogueSnapshot): RuntimeDialogueSnapshot = RuntimeDialogueSnapshot(
        state = DialogueRuntimeState(
            revision = dialogue.state.revision + 1,
            incarnation = dialogue.state.incarnation + 1,
            talkId = dialogue.state.talkId,
        ),
        choices = emptyList(),
        anchors = emptyList(),
        input = null,
    )

    private fun inputDeadline(now: Long, timeout: Long?): Long {
        if (timeout == null || timeout <= 0L || now > Long.MAX_VALUE - timeout) return Long.MAX_VALUE
        return now + timeout
    }

    private fun DialogueAction.visibleLabel(): String = when (this) {
        is DialogueAction.Normal -> label
        is DialogueAction.DirectEvent -> label
        is DialogueAction.Script -> label
    }

    private fun AnchorAction.visibleLabel(): String = when (this) {
        is AnchorAction.Normal -> label
        is AnchorAction.DirectEvent -> label
    }

    private fun parseId(script: String, index: Int): Pair<String, Int>? =
        parseBracketId(script, index) ?: script.getOrNull(index)?.takeIf { it.isDigit() || it == '-' }
            ?.let { character ->
                val end = if (character == '-' && script.getOrNull(index + 1)?.isDigit() == true) index + 2 else index + 1
                script.substring(index, end) to end
            }

    private fun parseBracketId(script: String, index: Int): Pair<String, Int>? =
        SakuraScriptCommandParser.readBracket(script, index)?.let { it.value to it.nextIndex }

    private fun resumeAfterMalformed(script: String, start: Int): Int =
        script.indexOf('\\', start).takeIf { it >= 0 } ?: script.length

    private fun schedule(
        state: PlayerState,
        delayMillis: Long,
        priorEffects: List<PlayerEffect> = emptyList(),
        work: PlayerWork = PlayerWork(),
    ): PlayerTransition {
        val next = state.copy(playbackToken = state.playbackToken + 1)
        return transition(next, priorEffects + PlayerEffect.SchedulePlayback(next.playbackToken, delayMillis), work)
    }

    private fun transition(
        state: PlayerState,
        effects: List<PlayerEffect> = emptyList(),
        work: PlayerWork = PlayerWork(),
    ): PlayerTransition = PlayerTransition(state, effects, work)

    private fun PlayerParent.operationId(): Long = when (this) {
        is PlayerParent.Switch -> operationId
        is PlayerParent.Exit -> operationId
    }

}

private fun PlayerTransition.frozen(): PlayerTransition = PlayerTransition(
    state = freeze(state),
    effects = immutableList(effects),
    work = work.copy(),
)

private fun freeze(state: PlayerState): PlayerState {
    val freezer = DialogueGraphFreezer()
    return state.copy(
        queue = immutableList(state.queue.map { it.copy() }),
        current = state.current?.let { cursor ->
            cursor.copy(
                payload = cursor.payload.copy(),
                authoredDialogue = cursor.authoredDialogue.copy(
                    contents = freezer.freezeContents(cursor.authoredDialogue.contents),
                    markers = immutableList(cursor.authoredDialogue.markers.map { marker ->
                        marker.copy(
                            value = when (val value = marker.value) {
                                is DialogueAction -> freezer.freezeDialogueAction(value)
                                is AnchorAction -> freezer.freezeAnchorAction(value)
                                is PendingInputSeed -> value.copy(spec = freezer.freezeInputSpec(value.spec))
                                else -> value
                            },
                        )
                    }),
                ),
            )
        },
        dialogue = freezer.freeze(state.dialogue),
    )
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
